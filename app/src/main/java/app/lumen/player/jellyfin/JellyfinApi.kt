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

class ApiException(val code: Int, message: String) : Exception(message)

data class Session(val baseUrl: String, val token: String, val userId: String)

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
)

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
) {
	val isEpisode: Boolean get() = type.equals("Episode", ignoreCase = true)
}

/**
 * Tiny Jellyfin REST client (HttpURLConnection + org.json, no extra dependencies).
 * Only used for optional extras; playback itself never depends on it.
 */
class JellyfinApi(private val prefs: Prefs) {

	fun authorizationHeader(token: String?): String {
		val device = ascii(Build.MODEL ?: "Android TV").ifBlank { "Android TV" }
		val parts = mutableListOf(
			"Client=\"Lumen Player\"",
			"Device=\"$device\"",
			"DeviceId=\"${ascii(prefs.deviceId)}\"",
			"Version=\"${BuildConfig.VERSION_NAME}\"",
		)
		if (!token.isNullOrBlank()) parts += "Token=\"${ascii(token)}\""
		return "MediaBrowser " + parts.joinToString(", ")
	}

	private fun ascii(value: String) = value.filter { it.code in 32..126 && it != '"' && it != ',' }

	// Server / auth ------------------------------------------------------------------------------

	/** Returns the server name, or throws if the URL is not a Jellyfin server. */
	suspend fun publicServerName(baseUrl: String): String {
		val json = JSONObject(request("GET", "$baseUrl/System/Info/Public", null))
		return json.str("ServerName") ?: "Jellyfin"
	}

	/** Starts Quick Connect. Returns (secret, code). */
	suspend fun quickConnectInitiate(baseUrl: String): Pair<String, String> {
		val json = JSONObject(request("POST", "$baseUrl/QuickConnect/Initiate", null, body = ""))
		val secret = json.str("Secret") ?: throw ApiException(0, "Quick Connect returned no secret")
		val code = json.str("Code") ?: throw ApiException(0, "Quick Connect returned no code")
		return secret to code
	}

	suspend fun quickConnectAuthorized(baseUrl: String, secret: String): Boolean {
		val json = JSONObject(request("GET", "$baseUrl/QuickConnect/Connect?secret=${enc(secret)}", null))
		return json.optBoolean("Authenticated", false)
	}

	suspend fun authenticateWithQuickConnect(baseUrl: String, secret: String): Session {
		val body = JSONObject().put("Secret", secret).toString()
		return parseAuth(baseUrl, request("POST", "$baseUrl/Users/AuthenticateWithQuickConnect", null, body))
	}

	suspend fun authenticateByName(baseUrl: String, user: String, password: String): Session {
		val body = JSONObject().put("Username", user).put("Pw", password).toString()
		return parseAuth(baseUrl, request("POST", "$baseUrl/Users/AuthenticateByName", null, body))
	}

	private fun parseAuth(baseUrl: String, text: String): Session {
		val json = JSONObject(text)
		val token = json.str("AccessToken") ?: throw ApiException(0, "No access token in response")
		val user = json.optJSONObject("User") ?: throw ApiException(0, "No user in response")
		val userId = user.str("Id") ?: throw ApiException(0, "No user id in response")
		prefs.userName = user.str("Name")
		return Session(baseUrl, token, userId)
	}

	// Item details -------------------------------------------------------------------------------

	suspend fun item(session: Session, itemId: String, mediaSourceId: String?): ItemMetadata {
		val userQuery = if (session.userId.isNotBlank()) "?userId=${session.userId}" else ""
		val json = JSONObject(request("GET", "${session.baseUrl}/Items/$itemId$userQuery", session.token))
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

		// Pick the media source the stream came from (or the first one).
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
		)
	}

	private suspend fun segments(session: Session, itemId: String): List<Segment> {
		val json = JSONObject(request("GET", "${session.baseUrl}/MediaSegments/$itemId", session.token))
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
			"pgssub", "pgs", "dvdsub", "dvbsub", "vobsub", "sup" -> return null
			else -> "srt" // the server converts other text formats on the fly
		}
		return "$baseUrl/Videos/$itemId/$mediaSourceId/Subtitles/${sub.index}/Stream.$format"
	}

	fun trickplayTileUrl(session: Session, itemId: String, mediaSourceId: String?, width: Int, index: Int): String {
		val query = mediaSourceId?.let { "?mediaSourceId=$it" } ?: ""
		return "${session.baseUrl}/Videos/$itemId/Trickplay/$width/$index.jpg$query"
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

	private suspend fun request(method: String, url: String, token: String?, body: String? = null): String =
		withContext(Dispatchers.IO) {
			val connection = open(method, url, token)
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

	private fun open(method: String, url: String, token: String?): HttpURLConnection =
		(URL(url).openConnection() as HttpURLConnection).apply {
			requestMethod = method
			connectTimeout = 8_000
			readTimeout = 15_000
			instanceFollowRedirects = true
			setRequestProperty("Accept", "application/json")
			setRequestProperty("Authorization", authorizationHeader(token))
		}

	companion object {
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

private fun JSONObject.str(name: String): String? =
	if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

private fun JSONArray?.objects(): List<JSONObject> {
	if (this == null) return emptyList()
	return (0 until length()).mapNotNull { optJSONObject(it) }
}
