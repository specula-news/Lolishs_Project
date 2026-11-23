package app.lumen.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable

/**
 * Understands the "external player" intent dialects used by Jellyfin, Emby, Plex, Kodi add-ons and
 * file managers: mpv-android, MX Player, VLC, Vimu and plain ACTION_VIEW.
 *
 * mpv:  title, position (int ms), subs (Uri[]), subs.enable (Uri[])
 * MX:   title, position (int ms), subs, subs.name (String[]), subs.enable, headers (String[]), filename
 * VLC:  title, position (long ms), subtitles_location (String)
 * Vimu: forcename, startfrom (int ms), forcedsrt (String)
 */
object IntentParser {
	fun parse(activity: Activity, intent: Intent): PlayRequest? {
		val uri = intent.data ?: return null
		val extras = intent.extras

		val title = extras?.stringOrNull("title")
			?: extras?.stringOrNull("forcename")
			?: extras?.stringOrNull(Intent.EXTRA_TITLE)

		val position = extras?.number("position")
			?: extras?.number("startfrom")
			?: 0L

		val subtitles = mutableListOf<ExternalSubtitle>()
		val subUris = extras?.uriArray("subs").orEmpty()
		val subNames = extras?.stringArray("subs.name").orEmpty()
		val enabled = extras?.uriArray("subs.enable").orEmpty().map { it.toString() }.toSet()
		subUris.forEachIndexed { index, subUri ->
			subtitles += ExternalSubtitle(
				uri = subUri,
				name = subNames.getOrNull(index)?.takeIf { it.isNotBlank() },
				language = null,
				enabled = subUri.toString() in enabled,
			)
		}
		listOfNotNull(extras?.stringOrNull("subtitles_location"), extras?.stringOrNull("forcedsrt"))
			.map(Uri::parse)
			.filter { candidate -> subtitles.none { it.uri == candidate } }
			.forEach { subtitles += ExternalSubtitle(it, null, null, enabled = true) }

		val headers = mutableMapOf<String, String>()
		extras?.stringArray("headers")?.toList()?.chunked(2)?.forEach { pair ->
			if (pair.size == 2 && pair[0].isNotBlank()) headers[pair[0]] = pair[1]
		}

		return PlayRequest(
			uri = uri,
			title = title,
			startPositionMs = position.coerceAtLeast(0L),
			subtitles = subtitles,
			headers = headers,
			fileName = extras?.stringOrNull("filename"),
			callerPackage = activity.callingPackage,
		)
	}

	/**
	 * Result understood by Jellyfin (mpv + MX parsers), Emby, Plex and VLC-aware apps.
	 * mpv omits "position" when playback reached the end; MX uses "end_by".
	 */
	fun buildResult(positionMs: Long, durationMs: Long, completed: Boolean): Intent =
		Intent("is.xyz.mpv.MPVActivity.result").apply {
			if (!completed) putExtra("position", positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
			if (durationMs > 0) putExtra("duration", durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
			putExtra("end_by", if (completed) "playback_completion" else "user")
			putExtra("extra_position", if (completed && durationMs > 0) durationMs else positionMs)
			putExtra("extra_duration", durationMs)
		}

	@Suppress("DEPRECATION")
	private fun Bundle.number(key: String): Long? = when (val value = get(key)) {
		is Number -> value.toLong()
		is String -> value.toLongOrNull()
		else -> null
	}

	@Suppress("DEPRECATION")
	private fun Bundle.stringOrNull(key: String): String? =
		(get(key) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }

	@Suppress("DEPRECATION")
	private fun Bundle.stringArray(key: String): Array<String>? = when (val value = get(key)) {
		is Array<*> -> value.map { it?.toString() ?: "" }.toTypedArray()
		is ArrayList<*> -> value.map { it?.toString() ?: "" }.toTypedArray()
		else -> null
	}

	@Suppress("DEPRECATION")
	private fun Bundle.uriArray(key: String): List<Uri>? = when (val value = get(key)) {
		is Array<*> -> value.mapNotNull { it.toUriOrNull() }
		is ArrayList<*> -> value.mapNotNull { it.toUriOrNull() }
		is Uri -> listOf(value)
		is String -> listOf(Uri.parse(value))
		else -> null
	}

	private fun Any?.toUriOrNull(): Uri? = when (this) {
		is Uri -> this
		is String -> Uri.parse(this)
		is Parcelable -> runCatching { Uri.parse(toString()) }.getOrNull()
		else -> null
	}
}
