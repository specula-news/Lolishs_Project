package app.lumen.player.util

import java.util.Locale
import kotlin.math.abs

object Formatters {
	/** Jellyfin / ISO date ("2026-11-20T18:04:11.1234567Z" or "2026-11-20") → epoch ms (UTC). */
	fun epochMs(date: String?): Long? {
		if (date.isNullOrBlank()) return null
		val (pattern, text) = if (date.length >= 19) "yyyy-MM-dd'T'HH:mm:ss" to date.substring(0, 19) else "yyyy-MM-dd" to date.take(10)
		return runCatching {
			java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(text)?.time
		}.getOrNull()
	}

	/** "Friday 28 Nov" (+ "today" / "tomorrow" / "in 4 days") for an air date. */
	fun airDate(epochMs: Long): String {
		val local = java.util.Calendar.getInstance()
		val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
		local.timeInMillis = epochMs
		val diff = epochMs - today.timeInMillis
		val days = if (diff >= 0) diff / (24L * 3600 * 1000) else -1L
		val day = java.text.SimpleDateFormat("EEEE d MMM", java.util.Locale.ENGLISH).format(local.time)
		val relative = when {
			days < 0 -> null
			days == 0L -> "today"
			days == 1L -> "tomorrow"
			else -> "in $days days"
		}
		return if (relative != null) "$day  ·  $relative" else day
	}
	/** 1:02:03 or 4:05 */
	fun time(ms: Long): String {
		val totalSec = (ms.coerceAtLeast(0L) + 500L) / 1000L
		val h = totalSec / 3600
		val m = (totalSec % 3600) / 60
		val s = totalSec % 60
		return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
		else String.format(Locale.US, "%d:%02d", m, s)
	}

	/** +0.250 s / −1.500 s */
	fun delay(ms: Long): String {
		val sign = when {
			ms > 0 -> "+"
			ms < 0 -> "−"
			else -> ""
		}
		return sign + String.format(Locale.US, "%.3f s", abs(ms) / 1000.0)
	}

	fun language(code: String?): String? {
		if (code.isNullOrBlank() || code == "und" || code == "unknown") return null
		val name = runCatching { Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()
		return if (name.isNullOrBlank() || name.equals(code, ignoreCase = true)) code.uppercase(Locale.US)
		else name.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
	}

	fun channels(count: Int): String? = when (count) {
		1 -> "Mono"
		2 -> "Stereo"
		3 -> "2.1"
		6 -> "5.1"
		7 -> "6.1"
		8 -> "7.1"
		in 9..32 -> "$count ch"
		else -> null
	}

	fun audioCodec(mime: String?, codecs: String? = null): String? = when (mime) {
		"audio/ac3" -> "Dolby Digital"
		"audio/eac3" -> "Dolby Digital+"
		"audio/eac3-joc" -> "Dolby Atmos"
		"audio/ac4" -> "Dolby AC-4"
		"audio/true-hd" -> "Dolby TrueHD"
		"audio/vnd.dts" -> "DTS"
		"audio/vnd.dts.hd" -> "DTS-HD MA"
		"audio/vnd.dts.hd;profile=lbr" -> "DTS Express"
		"audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
		"audio/mp4a-latm" -> "AAC"
		"audio/mpeg" -> "MP3"
		"audio/flac" -> "FLAC"
		"audio/alac" -> "ALAC"
		"audio/opus" -> "Opus"
		"audio/vorbis" -> "Vorbis"
		"audio/raw" -> "PCM"
		null -> codecs
		else -> mime.substringAfter('/').uppercase(Locale.US)
	}

	fun videoCodec(mime: String?): String? = when (mime) {
		"video/avc" -> "H.264"
		"video/hevc" -> "HEVC"
		"video/dolby-vision" -> "Dolby Vision"
		"video/av01" -> "AV1"
		"video/x-vnd.on2.vp9" -> "VP9"
		"video/x-vnd.on2.vp8" -> "VP8"
		"video/mpeg2" -> "MPEG-2"
		"video/mp4v-es" -> "MPEG-4"
		"video/wvc1" -> "VC-1"
		null -> null
		else -> mime.substringAfter('/').uppercase(Locale.US)
	}

	fun subtitleCodec(mime: String?): String? = when (mime) {
		"application/x-subrip" -> "SRT"
		"text/x-ssa" -> "ASS"
		"text/vtt" -> "WebVTT"
		"application/ttml+xml" -> "TTML"
		"application/pgs" -> "PGS"
		"application/vobsub" -> "VobSub"
		"application/dvbsubs" -> "DVB"
		"application/cea-608", "application/cea-708", "application/x-mp4-cea-608" -> "CC"
		"application/x-quicktime-tx3g", "text/x-tx3g" -> "TX3G"
		null -> null
		else -> mime.substringAfter('/').uppercase(Locale.US)
	}

	fun resolution(width: Int, height: Int): String? = when {
		width <= 0 || height <= 0 -> null
		width >= 3800 || height >= 2100 -> "4K"
		width >= 2500 || height >= 1400 -> "1440p"
		width >= 1900 || height >= 1000 -> "1080p"
		width >= 1260 || height >= 700 -> "720p"
		else -> "${height}p"
	}

	fun fps(value: Float): String? =
		if (value <= 0f) null else String.format(Locale.US, if (value % 1f == 0f) "%.0f fps" else "%.3f fps", value)

	fun bitrate(bps: Int): String? = when {
		bps <= 0 -> null
		bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
		else -> String.format(Locale.US, "%d kbps", bps / 1000)
	}
}
