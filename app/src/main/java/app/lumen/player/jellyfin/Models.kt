package app.lumen.player.jellyfin

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class UserData(
	val positionTicks: Long,
	val played: Boolean,
	val favorite: Boolean,
	val unplayedCount: Int,
	val playedPercent: Double,
	/** ISO date of the last time it was watched (for sorting "Continue watching"). */
	val lastPlayed: String? = null,
) {
	val positionMs: Long get() = positionTicks / 10_000L
	val hasResume: Boolean get() = positionTicks > 0 && !played
}

data class Person(val id: String, val name: String, val role: String?, val type: String?, val primaryTag: String?)

data class PublicUser(val id: String, val name: String, val primaryTag: String?, val hasPassword: Boolean)

data class MediaLine(val video: String?, val audio: String?, val subtitles: Int)

/** The parts of a Jellyfin BaseItemDto the UI needs. */
data class JfItem(
	val id: String,
	val name: String,
	val type: String,
	val collectionType: String?,
	val seriesId: String?,
	val seriesName: String?,
	val seasonId: String?,
	val index: Int?,
	val parentIndex: Int?,
	val year: Int?,
	val officialRating: String?,
	val communityRating: Double?,
	val criticRating: Int?,
	val runtimeTicks: Long,
	val overview: String?,
	val genres: List<String>,
	val tagline: String?,
	val primaryTag: String?,
	val thumbTag: String?,
	val logoTag: String?,
	val backdropTags: List<String>,
	val parentBackdropItemId: String?,
	val parentBackdropTags: List<String>,
	val parentThumbItemId: String?,
	val parentThumbTag: String?,
	val parentLogoItemId: String?,
	val parentLogoTag: String?,
	val seriesPrimaryTag: String?,
	val userData: UserData?,
	val childCount: Int,
	val premiereDate: String?,
	val status: String?,
	val people: List<Person>,
	val studios: List<String>,
	val media: MediaLine?,
	val dateCreated: String? = null,
	/** "Virtual" for an episode the server knows about but has no file for (yet). */
	val locationType: String? = null,
) {
	val runtimeMs: Long get() = runtimeTicks / 10_000L
	val isFolderLike: Boolean get() = type in setOf("Series", "Season", "BoxSet", "Folder", "CollectionFolder", "UserView", "Playlist")
	val isPlayable: Boolean get() = type in setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer")

	/** "S1 · E3" for episodes. */
	val episodeCode: String?
		get() = when {
			type != "Episode" -> null
			parentIndex != null && index != null -> "S$parentIndex · E$index"
			index != null -> "E$index"
			else -> null
		}

	companion object {
		fun parse(json: JSONObject): JfItem {
			val tags = json.optJSONObject("ImageTags")
			val user = json.optJSONObject("UserData")
			return JfItem(
				id = json.str("Id") ?: "",
				name = json.str("Name") ?: "",
				type = json.str("Type") ?: "",
				collectionType = json.str("CollectionType")?.lowercase(Locale.US),
				seriesId = json.str("SeriesId"),
				seriesName = json.str("SeriesName"),
				seasonId = json.str("SeasonId"),
				index = json.intOrNull("IndexNumber"),
				parentIndex = json.intOrNull("ParentIndexNumber"),
				year = json.intOrNull("ProductionYear"),
				officialRating = json.str("OfficialRating"),
				communityRating = if (json.has("CommunityRating") && !json.isNull("CommunityRating")) json.optDouble("CommunityRating") else null,
				criticRating = json.intOrNull("CriticRating"),
				runtimeTicks = json.optLong("RunTimeTicks", 0L),
				overview = json.str("Overview"),
				genres = json.optJSONArray("Genres").strings(),
				tagline = json.optJSONArray("Taglines").strings().firstOrNull(),
				primaryTag = tags?.str("Primary"),
				thumbTag = tags?.str("Thumb"),
				logoTag = tags?.str("Logo"),
				backdropTags = json.optJSONArray("BackdropImageTags").strings(),
				parentBackdropItemId = json.str("ParentBackdropItemId"),
				parentBackdropTags = json.optJSONArray("ParentBackdropImageTags").strings(),
				parentThumbItemId = json.str("ParentThumbItemId"),
				parentThumbTag = json.str("ParentThumbImageTag"),
				parentLogoItemId = json.str("ParentLogoItemId"),
				parentLogoTag = json.str("ParentLogoImageTag"),
				seriesPrimaryTag = json.str("SeriesPrimaryImageTag"),
				userData = user?.let {
					UserData(
						positionTicks = it.optLong("PlaybackPositionTicks", 0L),
						played = it.optBoolean("Played", false),
						favorite = it.optBoolean("IsFavorite", false),
						unplayedCount = it.optInt("UnplayedItemCount", 0),
						playedPercent = it.optDouble("PlayedPercentage", 0.0).let { p -> if (p.isNaN()) 0.0 else p },
						lastPlayed = it.str("LastPlayedDate"),
					)
				},
				childCount = json.optInt("ChildCount", 0),
				premiereDate = json.str("PremiereDate"),
				status = json.str("Status"),
				people = json.optJSONArray("People").objects().mapNotNull { p ->
					val id = p.str("Id") ?: return@mapNotNull null
					Person(id, p.str("Name") ?: "", p.str("Role"), p.str("Type"), p.str("PrimaryImageTag"))
				},
				studios = json.optJSONArray("Studios").objects().mapNotNull { it.str("Name") },
				media = parseMedia(json),
				dateCreated = json.str("DateCreated"),
				locationType = json.str("LocationType"),
			)
		}

		private fun parseMedia(json: JSONObject): MediaLine? {
			val source = json.optJSONArray("MediaSources").objects().firstOrNull() ?: return null
			val streams = source.optJSONArray("MediaStreams").objects()
			val video = streams.firstOrNull { it.str("Type") == "Video" }?.str("DisplayTitle")
			val audio = streams.firstOrNull { it.str("Type") == "Audio" && it.optBoolean("IsDefault") }?.str("DisplayTitle")
				?: streams.firstOrNull { it.str("Type") == "Audio" }?.str("DisplayTitle")
			val subtitles = streams.count { it.str("Type") == "Subtitle" }
			return MediaLine(video, audio, subtitles)
		}
	}
}

data class ItemPage(val items: List<JfItem>, val total: Int)

internal fun JSONObject.str(name: String): String? =
	if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

internal fun JSONObject.intOrNull(name: String): Int? =
	if (!has(name) || isNull(name)) null else optInt(name)

internal fun JSONArray?.objects(): List<JSONObject> {
	if (this == null) return emptyList()
	return (0 until length()).mapNotNull { optJSONObject(it) }
}

internal fun JSONArray?.strings(): List<String> {
	if (this == null) return emptyList()
	return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
