package app.lumen.player.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** When a show's next episode airs. */
data class NextAiring(val airsAtMs: Long?, val season: Int?, val episode: Int?, val name: String?, val ended: Boolean)

/** TVmaze (free, no key): air dates of upcoming episodes, found by IMDb or TheTVDB id. Call off the main thread. */
object TvMaze {
	fun next(imdb: String?, tvdb: String?): NextAiring? {
		val lookup = when {
			!imdb.isNullOrBlank() -> "imdb=$imdb"
			!tvdb.isNullOrBlank() -> "thetvdb=$tvdb"
			else -> return null
		}
		val show = get("https://api.tvmaze.com/lookup/shows?$lookup")?.let { JSONObject(it) } ?: return null
		val id = show.optInt("id").takeIf { it > 0 } ?: return null
		val full = get("https://api.tvmaze.com/shows/$id?embed=nextepisode")?.let { JSONObject(it) } ?: show
		val next = full.optJSONObject("_embedded")?.optJSONObject("nextepisode")
		return NextAiring(
			airsAtMs = next?.let { Formatters.epochMs(it.optString("airstamp").ifBlank { it.optString("airdate") }) },
			season = next?.optInt("season")?.takeIf { it > 0 },
			episode = next?.optInt("number")?.takeIf { it > 0 },
			name = next?.optString("name")?.takeIf { it.isNotBlank() && it != "null" },
			ended = full.optString("status").equals("Ended", ignoreCase = true),
		)
	}

	private fun get(url: String): String? = runCatching {
		val connection = (URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = 6_000
			readTimeout = 8_000
			instanceFollowRedirects = true
			setRequestProperty("Accept", "application/json")
			setRequestProperty("User-Agent", "LolishPlayer")
		}
		try {
			if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().use { it.readText() }
		} finally {
			connection.disconnect()
		}
	}.getOrNull()
}
