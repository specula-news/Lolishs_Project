package app.lumen.player.util

import app.lumen.player.jellyfin.SubtitleTarget
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/** A subtitle file found online. */
data class OnlineSubtitle(
	val source: String,
	val release: String,
	val language: String,
	val format: String,
	val downloads: Int,
	val hearingImpaired: Boolean,
	/** Direct download link (OpenSubtitles: gzip, Gestdown: plain text). */
	val url: String?,
	val encoding: String? = null,
	/** Set for subtitles the Jellyfin server's own plugins found: the server downloads those. */
	val serverId: String? = null,
)

/**
 * A language as each site names it: [os] OpenSubtitles code, [iso3] Jellyfin's three-letter
 * code, [english] the name Addic7ed / Gestdown use.
 */
data class SubLanguage(val english: String, val os: String, val iso3: String, val eastEuropean: Boolean = false)

/**
 * Free subtitle sites, no account needed: OpenSubtitles.org and Addic7ed (through Gestdown, TV shows).
 * Everything here blocks: call it off the main thread.
 */
object SubtitleFinder {
	val LANGUAGES = listOf(
		SubLanguage("Swedish", "swe", "swe"),
		SubLanguage("English", "eng", "eng"),
		SubLanguage("Bosnian", "bos", "bos", eastEuropean = true),
		SubLanguage("Croatian", "hrv", "hrv", eastEuropean = true),
		SubLanguage("Serbian", "scc", "srp", eastEuropean = true),
		SubLanguage("Norwegian", "nor", "nor"),
		SubLanguage("Danish", "dan", "dan"),
		SubLanguage("Finnish", "fin", "fin"),
		SubLanguage("German", "ger", "ger"),
		SubLanguage("Dutch", "dut", "dut"),
		SubLanguage("Spanish", "spa", "spa"),
		SubLanguage("French", "fre", "fre"),
		SubLanguage("Italian", "ita", "ita"),
		SubLanguage("Portuguese", "por", "por"),
		SubLanguage("Polish", "pol", "pol", eastEuropean = true),
		SubLanguage("Turkish", "tur", "tur"),
		SubLanguage("Arabic", "ara", "ara"),
	)

	private val FORMATS = setOf("srt", "ass", "ssa", "vtt")

	// OpenSubtitles.org (the classic REST API, no key) ---------------------------------------------

	fun openSubtitles(target: SubtitleTarget, language: SubLanguage): List<OnlineSubtitle> {
		fun imdb(id: String?) = id?.filter { it.isDigit() }?.trimStart('0')?.takeIf { it.isNotEmpty() }
		// Path parts have to be in alphabetical order.
		val path = when {
			target.isEpisode && target.season != null && target.episode != null && imdb(target.seriesImdb) != null ->
				"episode-${target.episode}/imdbid-${imdb(target.seriesImdb)}/season-${target.season}"
			imdb(target.imdb) != null -> "imdbid-${imdb(target.imdb)}"
			target.isEpisode && target.season != null && target.episode != null && !target.seriesName.isNullOrBlank() ->
				"episode-${target.episode}/query-${pathEnc(target.seriesName)}/season-${target.season}"
			target.title.isNotBlank() -> "query-${pathEnc(target.title)}"
			else -> return emptyList()
		}
		val text = get("https://rest.opensubtitles.org/search/$path/sublanguageid-${language.os}", mapOf(
			"X-User-Agent" to "TemporaryUserAgent",
			"User-Agent" to "TemporaryUserAgent",
		)) ?: return emptyList()
		val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
		return (0 until array.length()).mapNotNull { i ->
			val o = array.optJSONObject(i) ?: return@mapNotNull null
			val format = o.optString("SubFormat").lowercase()
			if (format !in FORMATS) return@mapNotNull null
			OnlineSubtitle(
				source = "OpenSubtitles",
				release = o.optString("MovieReleaseName").ifBlank { o.optString("SubFileName") }.trim(),
				language = o.optString("LanguageName").ifBlank { language.english },
				format = format,
				downloads = o.optString("SubDownloadsCnt").toIntOrNull() ?: 0,
				hearingImpaired = o.optString("SubHearingImpaired") == "1",
				url = o.optString("SubDownloadLink").takeIf { it.startsWith("http") },
				encoding = o.optString("SubEncoding").takeIf { it.isNotBlank() },
			)
		}.sortedByDescending { it.downloads }
	}

	// Addic7ed through Gestdown (TV episodes) --------------------------------------------------------

	fun gestdown(target: SubtitleTarget, language: SubLanguage): List<OnlineSubtitle> {
		if (!target.isEpisode || target.season == null || target.episode == null) return emptyList()
		val show = target.seriesName?.takeIf { it.isNotBlank() } ?: return emptyList()
		val found = get("https://api.gestdown.info/shows/search/${pathEnc(show)}")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
		val id = found.optJSONArray("shows")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() } ?: return emptyList()
		val text = get("https://api.gestdown.info/subtitles/get/$id/${target.season}/${target.episode}/${pathEnc(language.english)}") ?: return emptyList()
		val list = runCatching { JSONObject(text).optJSONArray("matchingSubtitles") }.getOrNull() ?: return emptyList()
		return (0 until list.length()).mapNotNull { i ->
			val o = list.optJSONObject(i) ?: return@mapNotNull null
			val uri = o.optString("downloadUri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			OnlineSubtitle(
				source = "Addic7ed",
				release = "${show} S${target.season}E${target.episode}  ${o.optString("version")}".trim(),
				language = o.optString("language").ifBlank { language.english },
				format = "srt",
				downloads = o.optInt("downloadCount"),
				hearingImpaired = o.optBoolean("hearingImpaired"),
				url = "https://api.gestdown.info$uri",
			)
		}.sortedByDescending { it.downloads }
	}

	// Download ---------------------------------------------------------------------------------------

	/** The subtitle as text (unzipped, in the right character set). */
	fun download(subtitle: OnlineSubtitle, language: SubLanguage): String {
		val url = subtitle.url ?: throw java.io.IOException("No download link")
		val connection = open(url, emptyMap())
		try {
			val code = connection.responseCode
			if (code !in 200..299) throw java.io.IOException("HTTP $code from ${subtitle.source}")
			val raw = connection.inputStream.use { it.readAll() }
			val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(raw.inputStream()).use { it.readAll() } else raw
			return decode(bytes, subtitle.encoding, language).removePrefix("﻿")
		} finally {
			connection.disconnect()
		}
	}

	private fun decode(bytes: ByteArray, declared: String?, language: SubLanguage): String {
		// UTF-8 when it is valid UTF-8; otherwise what the site says, or the usual Windows set for the language.
		runCatching {
			return Charsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString()
		}
		val name = declared?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) && !it.equals("UTF-8", ignoreCase = true) }
			?: if (language.eastEuropean) "windows-1250" else if (language.os == "ara") "windows-1256" else if (language.os == "tur") "windows-1254" else "windows-1252"
		return String(bytes, Charset.forName(name))
	}

	// Plumbing ---------------------------------------------------------------------------------------

	private fun pathEnc(text: String): String = URLEncoder.encode(text.trim(), "UTF-8").replace("+", "%20")

	private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
		(URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = 8_000
			readTimeout = 15_000
			instanceFollowRedirects = true
			setRequestProperty("Accept", "application/json, text/plain, */*")
			if ("User-Agent" !in headers) setRequestProperty("User-Agent", "LolishPlayer")
			headers.forEach { (key, value) -> setRequestProperty(key, value) }
		}

	private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
		val connection = open(url, headers)
		try {
			if (connection.responseCode != 200) null else connection.inputStream.use { String(it.readAll(), Charsets.UTF_8) }
		} finally {
			connection.disconnect()
		}
	}.getOrNull()

	private fun InputStream.readAll(): ByteArray {
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(16 * 1024)
		while (true) {
			val n = read(buffer)
			if (n < 0) break
			out.write(buffer, 0, n)
			if (out.size() > 8 * 1024 * 1024) break
		}
		return out.toByteArray()
	}
}
