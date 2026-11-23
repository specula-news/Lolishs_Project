package app.lumen.player

import android.net.Uri
import java.util.Locale

/** A subtitle file passed by the launching app or found on the Jellyfin server. */
data class ExternalSubtitle(
	val uri: Uri,
	val name: String?,
	val language: String?,
	val enabled: Boolean,
	val forced: Boolean = false,
) {
	/** Media3 MIME type guessed from the file extension (Jellyfin URLs end in Stream.srt etc). */
	val mimeType: String
		get() = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.US)) {
			"ass", "ssa" -> "text/x-ssa"
			"vtt", "webvtt" -> "text/vtt"
			"ttml", "dfxp", "xml" -> "application/ttml+xml"
			else -> "application/x-subrip"
		}

	val formatLabel: String
		get() = when (mimeType) {
			"text/x-ssa" -> "ASS"
			"text/vtt" -> "WebVTT"
			"application/ttml+xml" -> "TTML"
			else -> "SRT"
		}
}

/** Points at an item on a Jellyfin server, parsed from a stream URL like /Videos/{id}/stream. */
data class JellyfinRef(
	val baseUrl: String,
	val itemId: String,
	val mediaSourceId: String?,
	val apiKey: String?,
) {
	companion object {
		private val VIDEO_PATH = Regex(
			"""^(.*?)/(?:videos|audio)/([0-9a-fA-F]{32}|[0-9a-fA-F-]{36})/(?:stream|master\.m3u8|main\.m3u8|universal)""",
			RegexOption.IGNORE_CASE,
		)

		fun parse(uri: Uri): JellyfinRef? {
			val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
			if (scheme != "http" && scheme != "https") return null
			val path = uri.encodedPath ?: return null
			val match = VIDEO_PATH.find(path) ?: return null
			val prefix = match.groupValues[1]
			val base = "$scheme://${uri.encodedAuthority}$prefix".trimEnd('/')
			fun query(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
				runCatching { uri.getQueryParameter(name) }.getOrNull()?.takeIf { it.isNotBlank() }
			}
			return JellyfinRef(
				baseUrl = base,
				itemId = match.groupValues[2].replace("-", "").lowercase(Locale.US),
				mediaSourceId = query("mediaSourceId", "MediaSourceId")?.replace("-", "")?.lowercase(Locale.US),
				apiKey = query("api_key", "ApiKey", "apiKey"),
			)
		}
	}
}

data class PlayRequest(
	val uri: Uri,
	val title: String?,
	val startPositionMs: Long,
	val subtitles: List<ExternalSubtitle>,
	val headers: Map<String, String>,
	val fileName: String?,
	val callerPackage: String?,
) {
	val jellyfin: JellyfinRef? by lazy { JellyfinRef.parse(uri) }

	/** Stable key used to remember sync offsets per video. */
	val itemKey: String
		get() = jellyfin?.itemId ?: (uri.lastPathSegment ?: uri.toString()).take(120)

	val displayTitle: String
		get() = title?.takeIf { it.isNotBlank() }
			?: fileName?.substringBeforeLast('.')
			?: uri.lastPathSegment?.takeUnless { it.equals("stream", ignoreCase = true) }?.substringBeforeLast('.')
			?: "Now playing"
}
