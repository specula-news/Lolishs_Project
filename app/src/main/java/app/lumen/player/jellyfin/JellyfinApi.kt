package app.lumen.player.jellyfin

import android.os.Build
import app.lumen.player.BuildConfig
import app.lumen.player.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

class ApiException(val code: Int, message: String) : Exception(message)

data class Session(val baseUrl: String, val token: String, val userId: String)

/** Result of a sign-in: the session plus what we need to save the profile. */
data class SignedIn(val session: Session, val userName: String, val imageTag: String?, val deviceId: String)

data class Chapter(val startMs: Long, val name: String)

/** Intro / Outro / Recap / Preview / Commercial ranges from the server (Jellyfin 10.10+). */
data class Segment(val type: String, val startMs: Long, val endMs: Long)

data class TrickplayInfo(
	val width: Int,
	val height: Int,
	val tileWidth: Int,
	val tileHeight: Int,
	val thumbnailCount: Int,
	val intervalMs: Int,
)

data class ServerSubtitle(
	val index: Int,
	val title: String,
	val language: String?,
	val codec: String?,
	val isDefault: Boolean,
	val isForced: Boolean,
	val isExternal: Boolean,
) {
	val isText: Boolean
		get() = codec !in setOf("pgssub", "pgs", "dvdsub", "dvbsub", "vobsub", "sup", "dvb_subtitle", "hdmv_pgs_subtitle", "dvd_subtitle")
}

/** Everything the player needs to know about the item it plays. */
data class ItemMetadata(
	val itemId: String,
	val mediaSourceId: String?,
	val type: String?,
	val title: String,
	val subtitle: String?,
	val runtimeMs: Long,
	val chapters: List<Chapter>,
	val trickplay: TrickplayInfo?,
	val subtitles: List<ServerSubtitle>,
	val segments: List<Segment>,
	val seriesId: String? = null,
	val resumeMs: Long = 0L,
	val container: String? = null,
	val bitrate: Int = 0,
) {
	val isEpisode: Boolean get() = type.equals("Episode", ignoreCase = true)
}

data class TranscodeInfo(val url: String, val playSessionId: String, val mediaSourceId: String?)

/** Items query for library grids and searches. */
data class ItemQuery(
	val parentId: String? = null,
	val includeTypes: List<String> = emptyList(),
	val sortBy: String = "SortName",
	val descending: Boolean = false,
	val filters: List<String> = emptyList(),
	val searchTerm: String? = null,
	val recursive: Boolean = true,
	val startIndex: Int = 0,
	val limit: Int = 100,
	val genres: List<String> = emptyList(),
)

/**
 * Tiny Jellyfin REST client (HttpURLConnection + org.json, no extra dependencies).
 */
class JellyfinApi(private val prefs: Prefs) {

	fun authorizationHeader(token: String?, deviceId: String? = null): String {
		val device = ascii(Build.MODEL ?: "Android TV").ifBlank { "Android TV" }
		val parts = mutableListOf(
			"Client=\"Lolish's Project\"",
			"Device=\"$device\"",
			"DeviceId=\"${ascii(deviceId ?: prefs.deviceIdFor(token))}\"",
			"Version=\"${BuildConfig.VERSION_NAME}\"",
		)
		if (!token.isNullOrBlank()) parts += "Token=\"${ascii(token)}\""
		return "MediaBrowser " + parts.joinToString(", ")
	}

	private fun ascii(value: String) = value.filter { it.code in 32..126 && it != '"' && it != ',' }

	/** The signed-in session, or null when nobody is logged in. */
	fun session(): Session? {
		val base = prefs.serverUrl ?: return null
		val token = prefs.accessToken ?: return null
		val user = prefs.userId ?: return null
		return Session(normalizeBaseUrl(base), token, user)
	}

	// Server / auth ------------------------------------------------------------------------------

	/** Returns the server name, or throws if the URL is not a Jellyfin server. */
	suspend fun publicServerName(baseUrl: String): String {
		val json = JSONObject(request("GET", "$baseUrl/System/Info/Public", null))
		return json.str("ServerName") ?: "Jellyfin"
	}

	suspend fun publicUsers(baseUrl: String): List<PublicUser> {
		val array = JSONArray(request("GET", "$baseUrl/Users/Public", null).ifBlank { "[]" }.let { if (it == "{}") "[]" else it })
		return array.objects().mapNotNull { user ->
			val id = user.str("Id") ?: return@mapNotNull null
			PublicUser(id, user.str("Name") ?: "", user.str("PrimaryImageTag"), user.optBoolean("HasPassword", true))
		}
	}

	/** Starts Quick Connect. Returns (secret, code). */
	suspend fun quickConnectInitiate(baseUrl: String, deviceId: String): Pair<String, String> {
		val json = JSONObject(request("POST", "$baseUrl/QuickConnect/Initiate", null, body = "", deviceId = deviceId))
		val secret = json.str("Secret") ?: throw ApiException(0, "Quick Connect returned no secret")
		val code = json.str("Code") ?: throw ApiException(0, "Quick Connect returned no code")
		return secret to code
	}

	suspend fun quickConnectAuthorized(baseUrl: String, secret: String, deviceId: String): Boolean {
		val json = JSONObject(request("GET", "$baseUrl/QuickConnect/Connect?secret=${enc(secret)}", null, deviceId = deviceId))
		return json.optBoolean("Authenticated", false)
	}

	suspend fun authenticateWithQuickConnect(baseUrl: String, secret: String, deviceId: String): SignedIn {
		val body = JSONObject().put("Secret", secret).toString()
		return parseAuth(baseUrl, request("POST", "$baseUrl/Users/AuthenticateWithQuickConnect", null, body, deviceId), deviceId)
	}

	suspend fun authenticateByName(baseUrl: String, user: String, password: String, deviceId: String): SignedIn {
		val body = JSONObject().put("Username", user).put("Pw", password).toString()
		return parseAuth(baseUrl, request("POST", "$baseUrl/Users/AuthenticateByName", null, body, deviceId), deviceId)
	}

	private fun parseAuth(baseUrl: String, text: String, deviceId: String): SignedIn {
		val json = JSONObject(text)
		val token = json.str("AccessToken") ?: throw ApiException(0, "No access token in response")
		val user = json.optJSONObject("User") ?: throw ApiException(0, "No user in response")
		val userId = user.str("Id") ?: throw ApiException(0, "No user id in response")
		return SignedIn(Session(baseUrl, token, userId), user.str("Name") ?: "User", user.str("PrimaryImageTag"), deviceId)
	}

	/** Name and avatar tag of the signed-in user (used to refresh saved profiles). */
	suspend fun me(session: Session): Pair<String, String?> {
		val json = JSONObject(get(session, "/Users/Me", emptyMap()))
		return (json.str("Name") ?: "User") to json.str("PrimaryImageTag")
	}

	/** Revokes a profile's token on the server (best effort). */
	suspend fun logout(baseUrl: String, token: String, deviceId: String) {
		runCatching { request("POST", "${normalizeBaseUrl(baseUrl)}/Sessions/Logout", token, body = "", deviceId = deviceId) }
	}

	// Browsing ---------------------------------------------------------------------------------

	suspend fun views(session: Session): List<JfItem> =
		itemsArray(get(session, "/UserViews", mapOf("userId" to session.userId)))

	suspend fun resume(session: Session, limit: Int = 24): List<JfItem> =
		itemsArray(get(session, "/UserItems/Resume", mapOf(
			"userId" to session.userId, "limit" to "$limit", "mediaTypes" to "Video",
			"fields" to LIST_FIELDS, "enableImageTypes" to "Primary,Backdrop,Thumb,Logo",
		)))

	suspend fun nextUp(session: Session, limit: Int = 24, seriesId: String? = null): List<JfItem> =
		itemsArray(get(session, "/Shows/NextUp", buildMap {
			put("userId", session.userId)
			put("limit", "$limit")
			put("fields", LIST_FIELDS)
			put("enableResumable", "false")
			seriesId?.let { put("seriesId", it) }
		}))

	suspend fun latest(session: Session, parentId: String, limit: Int = 24): List<JfItem> {
		val text = get(session, "/Items/Latest", mapOf(
			"userId" to session.userId, "parentId" to parentId, "limit" to "$limit", "fields" to LIST_FIELDS,
		))
		return JSONArray(text.ifBlank { "[]" }).objects().map(JfItem::parse)
	}

	suspend fun upcoming(session: Session, limit: Int = 24): List<JfItem> =
		itemsArray(get(session, "/Shows/Upcoming", mapOf("userId" to session.userId, "limit" to "$limit", "fields" to LIST_FIELDS)))

	suspend fun items(session: Session, query: ItemQuery): ItemPage {
		val params = buildMap {
			put("userId", session.userId)
			query.parentId?.let { put("parentId", it) }
			if (query.includeTypes.isNotEmpty()) put("includeItemTypes", query.includeTypes.joinToString(","))
			put("sortBy", query.sortBy)
			put("sortOrder", if (query.descending) "Descending" else "Ascending")
			if (query.filters.isNotEmpty()) put("filters", query.filters.joinToString(","))
			if (query.genres.isNotEmpty()) put("genres", query.genres.joinToString("|"))
			query.searchTerm?.let { put("searchTerm", it) }
			put("recursive", query.recursive.toString())
			put("startIndex", "${query.startIndex}")
			put("limit", "${query.limit}")
			put("fields", LIST_FIELDS)
			put("enableTotalRecordCount", "true")
			put("imageTypeLimit", "1")
		}
		val json = JSONObject(get(session, "/Items", params))
		return ItemPage(json.optJSONArray("Items").objects().map(JfItem::parse), json.optInt("TotalRecordCount", 0))
	}

	suspend fun itemDetails(session: Session, itemId: String): JfItem =
		JfItem.parse(JSONObject(get(session, "/Items/$itemId", mapOf("userId" to session.userId))))

	suspend fun seasons(session: Session, seriesId: String): List<JfItem> =
		itemsArray(get(session, "/Shows/$seriesId/Seasons", mapOf("userId" to session.userId, "fields" to LIST_FIELDS)))

	suspend fun episodes(session: Session, seriesId: String, seasonId: String?): List<JfItem> =
		itemsArray(get(session, "/Shows/$seriesId/Episodes", buildMap {
			put("userId", session.userId)
			put("fields", "$LIST_FIELDS,MediaSources")
			seasonId?.let { put("seasonId", it) }
		}))

	/** The episode after [episodeId], if any. */
	suspend fun nextEpisode(session: Session, seriesId: String, episodeId: String): JfItem? {
		val list = itemsArray(get(session, "/Shows/$seriesId/Episodes", mapOf(
			"userId" to session.userId, "adjacentTo" to episodeId, "fields" to LIST_FIELDS,
		)))
		val index = list.indexOfFirst { normalizeId(it.id) == normalizeId(episodeId) }
		return if (index >= 0) list.getOrNull(index + 1) else null
	}

	suspend fun similar(session: Session, itemId: String, limit: Int = 16): List<JfItem> =
		itemsArray(get(session, "/Items/$itemId/Similar", mapOf("userId" to session.userId, "limit" to "$limit", "fields" to LIST_FIELDS)))

	suspend fun setFavorite(session: Session, itemId: String, favorite: Boolean) {
		request(if (favorite) "POST" else "DELETE", url(session, "/UserFavoriteItems/$itemId", mapOf("userId" to session.userId)), session.token, if (favorite) "" else null)
	}

	suspend fun setPlayed(session: Session, itemId: String, played: Boolean) {
		request(if (played) "POST" else "DELETE", url(session, "/UserPlayedItems/$itemId", mapOf("userId" to session.userId)), session.token, if (played) "" else null)
	}

	// Images ---------------------------------------------------------------------------------------

	fun imageUrl(session: Session, itemId: String?, type: String, tag: String?, maxWidth: Int, index: Int? = null): String? {
		if (itemId.isNullOrBlank() || tag.isNullOrBlank()) return null
		val path = if (index != null) "Images/$type/$index" else "Images/$type"
		return "${session.baseUrl}/Items/$itemId/$path?maxWidth=$maxWidth&quality=90&tag=$tag"
	}

	/** Portrait poster; episodes use their series poster. */
	fun posterUrl(session: Session, item: JfItem, maxWidth: Int): String? = when {
		item.type == "Episode" && item.seriesPrimaryTag != null -> imageUrl(session, item.seriesId, "Primary", item.seriesPrimaryTag, maxWidth)
		else -> imageUrl(session, item.id, "Primary", item.primaryTag, maxWidth)
	}

	/** 16:9 artwork for landscape cards. */
	fun thumbUrl(session: Session, item: JfItem, maxWidth: Int): String? = when {
		item.type == "Episode" && item.primaryTag != null -> imageUrl(session, item.id, "Primary", item.primaryTag, maxWidth)
		item.thumbTag != null -> imageUrl(session, item.id, "Thumb", item.thumbTag, maxWidth)
		item.backdropTags.isNotEmpty() -> imageUrl(session, item.id, "Backdrop", item.backdropTags.first(), maxWidth, 0)
		item.parentThumbTag != null -> imageUrl(session, item.parentThumbItemId, "Thumb", item.parentThumbTag, maxWidth)
		item.parentBackdropTags.isNotEmpty() -> imageUrl(session, item.parentBackdropItemId, "Backdrop", item.parentBackdropTags.first(), maxWidth, 0)
		else -> imageUrl(session, item.id, "Primary", item.primaryTag, maxWidth)
	}

	fun backdropUrl(session: Session, item: JfItem, maxWidth: Int = 1920): String? = when {
		item.backdropTags.isNotEmpty() -> imageUrl(session, item.id, "Backdrop", item.backdropTags.first(), maxWidth, 0)
		item.parentBackdropTags.isNotEmpty() -> imageUrl(session, item.parentBackdropItemId, "Backdrop", item.parentBackdropTags.first(), maxWidth, 0)
		else -> null
	}

	fun logoUrl(session: Session, item: JfItem, maxWidth: Int = 800): String? = when {
		item.logoTag != null -> imageUrl(session, item.id, "Logo", item.logoTag, maxWidth)
		item.parentLogoTag != null -> imageUrl(session, item.parentLogoItemId, "Logo", item.parentLogoTag, maxWidth)
		else -> null
	}

	fun personUrl(session: Session, person: Person, maxWidth: Int = 300): String? =
		imageUrl(session, person.id, "Primary", person.primaryTag, maxWidth)

	fun userImageUrl(baseUrl: String, user: PublicUser): String? =
		user.primaryTag?.let { "$baseUrl/Users/${user.id}/Images/Primary?maxWidth=240&tag=$it" }

	// Player metadata ----------------------------------------------------------------------------

	suspend fun item(session: Session, itemId: String, mediaSourceId: String?): ItemMetadata {
		val json = JSONObject(get(session, "/Items/$itemId", if (session.userId.isNotBlank()) mapOf("userId" to session.userId) else emptyMap()))
		val type = json.str("Type")
		val name = json.str("Name") ?: ""

		val title: String
		val subtitle: String?
		if (type.equals("Episode", ignoreCase = true)) {
			title = json.str("SeriesName") ?: name
			val season = json.optInt("ParentIndexNumber", -1)
			val episode = json.optInt("IndexNumber", -1)
			val code = when {
				season >= 0 && episode >= 0 -> "S$season · E$episode"
				episode >= 0 -> "E$episode"
				else -> null
			}
			subtitle = listOfNotNull(code, name.takeIf { it.isNotBlank() && it != title }).joinToString("  —  ").ifBlank { null }
		} else {
			title = name
			subtitle = listOfNotNull(
				json.optInt("ProductionYear", 0).takeIf { it > 0 }?.toString(),
				json.str("OfficialRating"),
			).joinToString("  ·  ").ifBlank { null }
		}

		val chapters = json.optJSONArray("Chapters").objects().mapNotNull { chapter ->
			val start = chapter.optLong("StartPositionTicks", -1L)
			if (start < 0) null else Chapter(start / 10_000L, chapter.str("Name") ?: "")
		}

		val sources = json.optJSONArray("MediaSources").objects()
		val source = sources.firstOrNull { normalizeId(it.str("Id")) == mediaSourceId } ?: sources.firstOrNull()
		val sourceId = normalizeId(source?.str("Id")) ?: mediaSourceId
		val streams = (source?.optJSONArray("MediaStreams") ?: json.optJSONArray("MediaStreams")).objects()
		val subtitles = streams.filter { it.str("Type").equals("Subtitle", ignoreCase = true) }.map { stream ->
			ServerSubtitle(
				index = stream.optInt("Index", -1),
				title = stream.str("DisplayTitle") ?: stream.str("Title") ?: stream.str("Language") ?: "Subtitle",
				language = stream.str("Language"),
				codec = stream.str("Codec")?.lowercase(Locale.US),
				isDefault = stream.optBoolean("IsDefault", false),
				isForced = stream.optBoolean("IsForced", false),
				isExternal = stream.optBoolean("IsExternal", false),
			)
		}

		val trickplay = json.optJSONObject("Trickplay")?.let trick@{ all ->
			val keys = all.keys().asSequence().toList()
			val key = keys.firstOrNull { normalizeId(it) == sourceId } ?: keys.firstOrNull() ?: return@trick null
			val widths = all.optJSONObject(key) ?: return@trick null
			val widthKey = widths.keys().asSequence().toList()
				.minByOrNull { kotlin.math.abs((it.toIntOrNull() ?: 0) - 320) } ?: return@trick null
			val info = widths.optJSONObject(widthKey) ?: return@trick null
			TrickplayInfo(
				width = info.optInt("Width"),
				height = info.optInt("Height"),
				tileWidth = info.optInt("TileWidth"),
				tileHeight = info.optInt("TileHeight"),
				thumbnailCount = info.optInt("ThumbnailCount"),
				intervalMs = info.optInt("Interval"),
			).takeIf { it.width > 0 && it.height > 0 && it.tileWidth > 0 && it.tileHeight > 0 && it.intervalMs > 0 && it.thumbnailCount > 0 }
		}

		val segments = runCatching { segments(session, itemId) }.getOrDefault(emptyList())
		val userData = json.optJSONObject("UserData")

		return ItemMetadata(
			itemId = itemId,
			mediaSourceId = sourceId,
			type = type,
			title = title.ifBlank { "Now playing" },
			subtitle = subtitle,
			runtimeMs = json.optLong("RunTimeTicks", 0L) / 10_000L,
			chapters = chapters,
			trickplay = trickplay,
			subtitles = subtitles,
			segments = segments,
			seriesId = json.str("SeriesId"),
			resumeMs = (userData?.optLong("PlaybackPositionTicks", 0L) ?: 0L) / 10_000L,
			container = source?.str("Container"),
			bitrate = source?.optInt("Bitrate", 0) ?: 0,
		)
	}

	private suspend fun segments(session: Session, itemId: String): List<Segment> {
		val json = JSONObject(get(session, "/MediaSegments/$itemId", emptyMap()))
		return json.optJSONArray("Items").objects().mapNotNull { segment ->
			val type = segment.str("Type") ?: return@mapNotNull null
			val start = segment.optLong("StartTicks", -1L) / 10_000L
			val end = segment.optLong("EndTicks", -1L) / 10_000L
			if (start < 0 || end <= start) null else Segment(type, start, end)
		}.sortedBy { it.startMs }
	}

	fun subtitleUrl(baseUrl: String, itemId: String, mediaSourceId: String, sub: ServerSubtitle): String? {
		val format = when (sub.codec) {
			"ass" -> "ass"
			"ssa" -> "ssa"
			"webvtt", "vtt" -> "vtt"
			"ttml" -> "ttml"
			"subrip", "srt" -> "srt"
			// Bitmap subtitles can't be converted to a text file; they play from the video itself.
			"pgssub", "pgs", "dvdsub", "dvbsub", "vobsub", "sup", "dvb_subtitle", "hdmv_pgs_subtitle", "dvd_subtitle" -> return null
			else -> "srt" // the server converts other text formats on the fly
		}
		return "$baseUrl/Videos/$itemId/$mediaSourceId/Subtitles/${sub.index}/Stream.$format"
	}

	fun trickplayTileUrl(session: Session, itemId: String, mediaSourceId: String?, width: Int, index: Int): String {
		val query = mediaSourceId?.let { "?mediaSourceId=$it" } ?: ""
		return "${session.baseUrl}/Videos/$itemId/Trickplay/$width/$index.jpg$query"
	}

	// Playback -------------------------------------------------------------------------------------

	fun newPlaySessionId(): String = UUID.randomUUID().toString().replace("-", "")

	/** Direct (original file) stream URL. Works for VLC too, since the token is in the query. */
	fun directStreamUrl(session: Session, itemId: String, mediaSourceId: String?, playSessionId: String): String {
		val source = mediaSourceId?.let { "&mediaSourceId=$it" } ?: ""
		return "${session.baseUrl}/Videos/$itemId/stream?static=true$source&PlaySessionId=$playSessionId&ApiKey=${session.token}&api_key=${session.token}"
	}

	/**
	 * Asks the server to transcode to H.264 HLS at [maxBitrate] (bits/s). Used when the original
	 * file stutters (too big for the connection, or a codec the TV struggles with).
	 */
	suspend fun transcode(
		session: Session,
		itemId: String,
		mediaSourceId: String?,
		maxBitrate: Int,
		startMs: Long,
		audioStreamIndex: Int? = null,
	): TranscodeInfo {
		val profile = JSONObject()
			.put("Name", "Lolish's Project")
			.put("MaxStreamingBitrate", maxBitrate)
			.put("MaxStaticBitrate", maxBitrate)
			.put("DirectPlayProfiles", JSONArray())
			.put("CodecProfiles", JSONArray())
			.put("ContainerProfiles", JSONArray())
			.put("TranscodingProfiles", JSONArray().put(
				JSONObject()
					.put("Container", "ts")
					.put("Type", "Video")
					.put("VideoCodec", "h264")
					.put("AudioCodec", "aac,ac3,eac3,mp3")
					.put("Protocol", "hls")
					.put("Context", "Streaming")
					.put("MaxAudioChannels", "6")
					.put("MinSegments", 1)
					.put("BreakOnNonKeyFrames", true)
			))
			.put("SubtitleProfiles", JSONArray().apply {
				listOf("srt", "subrip", "ass", "ssa", "vtt").forEach { put(JSONObject().put("Format", it).put("Method", "External")) }
			})
		val body = JSONObject()
			.put("UserId", session.userId)
			.put("MaxStreamingBitrate", maxBitrate)
			.put("StartTimeTicks", startMs * 10_000L)
			.put("EnableDirectPlay", false)
			.put("EnableDirectStream", false)
			.put("EnableTranscoding", true)
			.put("AllowVideoStreamCopy", false)
			.put("AllowAudioStreamCopy", true)
			.put("AutoOpenLiveStream", true)
			.put("DeviceProfile", profile)
		mediaSourceId?.let { body.put("MediaSourceId", it) }
		audioStreamIndex?.let { body.put("AudioStreamIndex", it) }

		val json = JSONObject(request("POST", url(session, "/Items/$itemId/PlaybackInfo", mapOf("userId" to session.userId)), session.token, body.toString()))
		json.str("ErrorCode")?.let { throw ApiException(0, "Server refused to transcode ($it)") }
		val source = json.optJSONArray("MediaSources").objects().firstOrNull() ?: throw ApiException(0, "No media source")
		val path = source.str("TranscodingUrl") ?: throw ApiException(0, "Server did not offer a transcode")
		val playSessionId = json.str("PlaySessionId") ?: newPlaySessionId()
		val full = if (path.startsWith("http")) path else session.baseUrl + path
		return TranscodeInfo(full, playSessionId, normalizeId(source.str("Id")))
	}

	/** Ends a server transcode right away (otherwise it keeps running until the server notices). */
	suspend fun stopEncoding(session: Session, playSessionId: String) {
		request("DELETE", url(session, "/Videos/ActiveEncodings", mapOf(
			"deviceId" to prefs.deviceIdFor(session.token),
			"playSessionId" to playSessionId,
		)), session.token)
	}

	suspend fun reportPlayback(session: Session, event: String, info: JSONObject) {
		val path = when (event) {
			"start" -> "/Sessions/Playing"
			"stop" -> "/Sessions/Playing/Stopped"
			else -> "/Sessions/Playing/Progress"
		}
		request("POST", url(session, path, emptyMap()), session.token, info.toString())
	}

	suspend fun bytes(url: String, token: String?): ByteArray = withContext(Dispatchers.IO) {
		val connection = open("GET", url, token)
		try {
			val code = connection.responseCode
			if (code !in 200..299) throw ApiException(code, "HTTP $code")
			connection.inputStream.use { it.readBytes() }
		} finally {
			connection.disconnect()
		}
	}

	// Plumbing ---------------------------------------------------------------------------------

	private fun itemsArray(text: String): List<JfItem> =
		JSONObject(text.ifBlank { "{}" }).optJSONArray("Items").objects().map(JfItem::parse)

	private fun url(session: Session, path: String, params: Map<String, String>): String {
		val query = params.entries.joinToString("&") { (key, value) -> "$key=${enc(value)}" }
		return session.baseUrl + path + if (query.isNotEmpty()) "?$query" else ""
	}

	private suspend fun get(session: Session, path: String, params: Map<String, String>): String =
		request("GET", url(session, path, params), session.token)

	private suspend fun request(method: String, url: String, token: String?, body: String? = null, deviceId: String? = null): String =
		withContext(Dispatchers.IO) {
			val connection = open(method, url, token, deviceId)
			try {
				if (body != null) {
					connection.doOutput = true
					connection.setRequestProperty("Content-Type", "application/json")
					connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
				}
				val code = connection.responseCode
				val stream = if (code in 200..299) connection.inputStream else connection.errorStream
				val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
				if (code !in 200..299) throw ApiException(code, "HTTP $code ${text.take(200)}")
				text.ifBlank { "{}" }
			} finally {
				connection.disconnect()
			}
		}

	private fun open(method: String, url: String, token: String?, deviceId: String? = null): HttpURLConnection =
		(URL(url).openConnection() as HttpURLConnection).apply {
			requestMethod = method
			connectTimeout = 10_000
			readTimeout = 20_000
			instanceFollowRedirects = true
			setRequestProperty("Accept", "application/json")
			setRequestProperty("Authorization", authorizationHeader(token, deviceId))
		}

	companion object {
		const val LIST_FIELDS = "PrimaryImageAspectRatio,Overview,Genres,ChildCount,MediaSourceCount"

		fun normalizeId(id: String?): String? = id?.replace("-", "")?.lowercase(Locale.US)

		/** Normalizes user input like "192.168.1.10:8096/" into "http://192.168.1.10:8096". */
		fun normalizeBaseUrl(input: String): String {
			var url = input.trim().trimEnd('/')
			if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
				url = "http://$url"
			}
			if (url.endsWith("/web", ignoreCase = true)) url = url.dropLast(4)
			return url
		}

		private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
	}
}
