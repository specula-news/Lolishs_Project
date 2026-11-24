package app.lumen.player.liserver

import app.lumen.player.util.Prefs
import app.lumen.player.util.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class LiServerException(val code: Int, val error: String?, message: String) : Exception(message) {
	val needsLogin: Boolean get() = code == 401 || error == "Auth" || error == "CSRF"
	val untrustedCertificate: Boolean get() = code == CODE_CERT

	companion object {
		const val CODE_CERT = -2
		const val CODE_NETWORK = -1
	}
}

/** One search hit. */
data class LiResult(
	val name: String,
	val magnet: String,
	val sizeText: String,
	val seeds: Int,
	val date: Long,
	val tracker: String,
) {
	/** Private tracker (TorrentDay) torrents are added as private, like the website does. */
	val isPrivate: Boolean get() = tracker.contains("torrentday", ignoreCase = true)

	/** 1080p and x265 / HEVC / AV1 — the quality Li'Server marks as "Best Choice". */
	val isBest: Boolean get() = BEST_RES.containsMatchIn(name) && BEST_CODEC.containsMatchIn(name) && seeds > 0

	private companion object {
		val BEST_RES = Regex("(?:^|[^0-9a-z])1080p(?![0-9a-z])", RegexOption.IGNORE_CASE)
		val BEST_CODEC = Regex("(?:^|[^0-9a-z])(?:x\\.?265|h\\.?265|hevc|av1)(?![0-9a-z])", RegexOption.IGNORE_CASE)
	}
}

/** A torrent on the server. */
data class LiDownload(
	val hash: String,
	val name: String,
	val state: String,
	val progress: Double,
	val speed: String,
	val uploadSpeed: String,
	val sizeText: String,
	val waiting: Boolean,
	val error: String?,
	/** Seeding rule progress: hours seeded so far and the goal (null when not seeding). */
	val seedHours: Double? = null,
	val seedGoal: Double? = null,
	val isPrivate: Boolean = false,
) {
	/** Download speed in MB/s for sorting ("2.31 MB/s" → 2.31, "512 KB/s" → 0.5). */
	val speedMb: Double get() = rate(speed)
	val uploadMb: Double get() = rate(uploadSpeed)

	private fun rate(text: String): Double {
		val m = Regex("([\\d.]+)\\s*(B|KB|MB|GB)", RegexOption.IGNORE_CASE).find(text) ?: return 0.0
		val value = m.groupValues[1].toDoubleOrNull() ?: return 0.0
		return when (m.groupValues[2].uppercase()) { "B" -> value / 1_048_576; "KB" -> value / 1024; "GB" -> value * 1024; else -> value }
	}

	val isSeeding: Boolean get() = state.contains("Seeding", ignoreCase = true) || progress >= 100.0
	val isPaused: Boolean get() = state.contains("Paused", ignoreCase = true) || state.contains("Stopped", ignoreCase = true)
}

/** A finished download from the History list. */
data class LiHistory(val name: String, val path: String, val date: String, val timestamp: Long)

/** "Airing today" episodes and trending movies from the start page. */
data class LiDiscover(
	val isEpisode: Boolean,
	val title: String,
	val subtitle: String,
	val image: String?,
	val query: String?,
	val directName: String?,
	val directMagnet: String?,
	val inLibrary: Boolean,
)

/** Where a download will be saved, worked out the same way as the website's download dialog. */
data class LiPlan(val base: String, val crumbs: List<String>, val notes: List<String>, val usePlan: Boolean)

/**
 * Client for Li'Server's own JSON API (the one its website uses): cookie session + CSRF token,
 * kept in the app's settings so you stay signed in across restarts and app updates. When the
 * session ends, it signs in again by itself with the saved (encrypted) login.
 */
class LiServerApi(private val prefs: Prefs) {
	private val base: String get() = prefs.liServerUrl.trim().trimEnd('/')

	/** Signed-in user name, or null. */
	suspend fun me(): String? {
		val json = call("GET", "/api/auth/me", relogin = false) as? JSONObject ?: return null
		if (!json.optBoolean("authenticated", json.has("user"))) return null
		json.optString("csrf").takeIf { it.isNotBlank() }?.let { prefs.liServerCsrf = it }
		return userName(json) ?: prefs.liServerUser ?: "you"
	}

	suspend fun login(user: String, password: String): String {
		val json = call("POST", "/api/auth/login", body = JSONObject().put("username", user).put("password", password).put("remember", true), relogin = false)
			as? JSONObject ?: throw LiServerException(0, null, "Unexpected answer from Li'Server")
		json.optString("csrf").takeIf { it.isNotBlank() }?.let { prefs.liServerCsrf = it }
		prefs.liServerUser = user
		return userName(json) ?: user
	}

	/** Makes sure there is a live session; signs in with the saved login when needed. */
	suspend fun ensureSession(): String? {
		me()?.let { return it }
		val user = prefs.liServerUser ?: return null
		val password = SecureStore.decrypt(prefs.liServerPasswordEncrypted) ?: return null
		return login(user, password)
	}

	suspend fun logout() {
		runCatching { call("POST", "/api/auth/logout", relogin = false) }
		prefs.liServerCookies = emptyMap()
		prefs.liServerCsrf = null
	}

	suspend fun search(query: String, privateTrackers: Boolean): List<LiResult> {
		val array = call("GET", "/api/search", mapOf("q" to query, "mode" to if (privateTrackers) "private" else "public")) as? JSONArray
			?: return emptyList()
		return (0 until array.length()).mapNotNull { i ->
			val o = array.optJSONObject(i) ?: return@mapNotNull null
			val magnet = o.optString("magnet").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			LiResult(
				name = o.optString("name"),
				magnet = magnet,
				sizeText = o.optString("size_str"),
				seeds = o.optInt("seeds", 0),
				date = o.optLong("date", 0L),
				tracker = o.optString("tracker"),
			)
		}
	}

	suspend fun downloads(): List<LiDownload> {
		val json = call("GET", "/api/downloads") as? JSONObject ?: return emptyList()
		val all = json.optJSONArray("all") ?: return emptyList()
		return (0 until all.length()).mapNotNull { i ->
			val o = all.optJSONObject(i) ?: return@mapNotNull null
			if (o.optBoolean("nameless", false)) return@mapNotNull null
			LiDownload(
				hash = o.optString("hash"),
				name = o.optString("name"),
				state = o.optString("state"),
				progress = o.optDouble("progress", 0.0).takeIf { !it.isNaN() } ?: 0.0,
				speed = o.optString("speed"),
				uploadSpeed = o.optString("upload_speed"),
				sizeText = o.optString("total_size_str").ifBlank { o.optString("size_str") },
				waiting = o.optBoolean("waiting", false),
				error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
				seedHours = o.optJSONObject("seed")?.optDouble("h")?.takeIf { !it.isNaN() },
				seedGoal = o.optJSONObject("seed")?.optDouble("goal")?.takeIf { !it.isNaN() },
				isPrivate = o.optBoolean("is_private", false) || o.optBoolean("private", false),
			)
		}
	}

	suspend fun history(): List<LiHistory> {
		val array = call("GET", "/api/history") as? JSONArray ?: return emptyList()
		return (0 until array.length()).mapNotNull { i ->
			val o = array.optJSONObject(i) ?: return@mapNotNull null
			LiHistory(o.optString("name"), o.optString("path"), o.optString("date"), o.optLong("timestamp", 0L))
		}.sortedByDescending { it.timestamp }
	}

	/** Seed [hours] longer than the seeding rule says. */
	suspend fun seedLonger(hash: String, hours: Int) {
		call("POST", "/api/seeding/extend", body = JSONObject().put("hash", hash).put("hours", hours))
	}

	/** Stop seeding now; the files move into the library. */
	suspend fun stopSeeding(hash: String) {
		call("POST", "/api/seeding/stop", body = JSONObject().put("hash", hash))
	}

	/** [action]: pause, resume, force or delete (delete also removes the files). */
	suspend fun control(hash: String, action: String) {
		call("POST", "/api/control", body = JSONObject().put("hash", hash).put("action", action))
	}

	suspend fun discover(): Pair<List<LiDiscover>, List<LiDiscover>> {
		val json = call("GET", "/api/discover") as? JSONObject ?: return emptyList<LiDiscover>() to emptyList()
		fun list(key: String): List<LiDiscover> {
			val array = json.optJSONArray(key) ?: return emptyList()
			return (0 until array.length()).mapNotNull { i ->
				val x = array.optJSONObject(i) ?: return@mapNotNull null
				val episode = x.optString("kind") == "episode"
				val direct = x.optJSONObject("direct")
				val sub = if (episode) {
					listOf(x.optString("code"), x.optString("network")).filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
				} else {
					listOfNotNull(
						x.optString("year").takeIf { it.isNotBlank() && it != "null" },
						x.optString("rating").takeIf { it.isNotBlank() && it != "null" }?.let { "★ $it" },
						direct?.optString("label")?.takeIf { it.isNotBlank() },
					).joinToString(" · ")
				}
				LiDiscover(
					isEpisode = episode,
					title = if (episode) x.optString("show").ifBlank { x.optString("title") } else x.optString("title"),
					subtitle = sub,
					image = x.optString("image").takeIf { it.startsWith("http") },
					query = x.optString("query").takeIf { it.isNotBlank() && it != "null" },
					directName = direct?.optString("name")?.takeIf { it.isNotBlank() },
					directMagnet = direct?.optString("magnet")?.takeIf { it.isNotBlank() },
					inLibrary = x.optBoolean("in_library", false),
				)
			}
		}
		return list("aired") to list("movies")
	}

	/** Picks the folder like the website: a show/season plan, a confident match, or the first library. */
	suspend fun plan(name: String): LiPlan {
		val plan = runCatching { (call("POST", "/api/plan_download", body = JSONObject().put("name", name)) as? JSONObject)?.optJSONObject("plan") }.getOrNull()
		if (plan != null) {
			val crumbs = plan.optJSONArray("crumbs")
			val labels = (0 until (crumbs?.length() ?: 0)).mapNotNull { crumbs?.optJSONObject(it)?.optString("label") }
			val notes = mutableListOf<String>()
			val last = crumbs?.optJSONObject((crumbs.length() - 1).coerceAtLeast(0))
			if (last?.optString("state") == "rename") notes += "The existing “${last.optString("from")}” folder will be renamed to “${last.optString("label")}”."
			if (plan.optBoolean("new_show", false)) notes += "A new show folder will be created."
			else if (plan.optBoolean("new_season", false)) notes += "A new “${labels.lastOrNull().orEmpty()}” folder will be created."
			val already = plan.optJSONArray("already")
			if (already != null && already.length() > 0) notes += "Something like this is already on the server."
			return LiPlan(base = "", crumbs = labels, notes = notes, usePlan = true)
		}
		val suggestions = runCatching { (call("POST", "/api/resolve_path", body = JSONObject().put("name", name)) as? JSONObject)?.optJSONArray("suggestions") }.getOrNull()
		val best = suggestions?.optJSONObject(0)?.takeIf { it.optDouble("score", 0.0) > 75.0 }
		val libraries = libraryPaths()
		val chosen = best?.optString("path")?.takeIf { it.isNotBlank() }
			?: (if (LOOKS_LIKE_SERIES.containsMatchIn(name)) libraries.firstOrNull { isSeriesRoot(it) } else null)
			?: libraries.firstOrNull()
			?: ""
		val notes = if (best != null) listOf("Already on the server: ${best.optString("name")}") else emptyList()
		return LiPlan(base = chosen, crumbs = chosen.replace('\\', '/').split('/').filter { it.isNotBlank() }.takeLast(3), notes = notes, usePlan = false)
	}

	/** Adds the torrent exactly like the website's Download button. Returns the folder it goes to. */
	suspend fun add(name: String, magnet: String, isPrivate: Boolean, plan: LiPlan): String {
		val body = JSONObject().put("name", name)
		if (plan.base.isNotBlank()) body.put("base", plan.base)
		val prep = call("POST", "/api/prepare_download", body = body) as? JSONObject
		val save = if (prep?.optBoolean("applicable", false) == true) prep.optString("target") else plan.base
		if (save.isBlank()) throw LiServerException(0, null, "Li'Server doesn't know where to save this. Pick a folder on the website once.")
		call("POST", "/api/add", body = JSONObject().put("magnet", magnet).put("save_path", save).put("is_private", isPrivate).put("is_torrentday", isPrivate))
		return save
	}

	suspend fun libraryPaths(): List<String> {
		val json = runCatching { call("GET", "/api/library") as? JSONObject }.getOrNull() ?: return emptyList()
		val paths = json.optJSONArray("paths") ?: return emptyList()
		return (0 until paths.length()).map { paths.optString(it) }.filter { it.isNotBlank() }
	}

	// Plumbing ---------------------------------------------------------------------------------

	private fun userName(json: JSONObject): String? {
		val user = json.opt("user")
		return when (user) {
			is JSONObject -> user.optString("display").ifBlank { user.optString("username") }.takeIf { it.isNotBlank() }
			is String -> user.takeIf { it.isNotBlank() }
			else -> null
		}
	}

	private suspend fun call(
		method: String,
		path: String,
		query: Map<String, String> = emptyMap(),
		body: JSONObject? = null,
		relogin: Boolean = true,
	): Any? {
		try {
			return request(method, path, query, body)
		} catch (e: LiServerException) {
			if (!relogin || !e.needsLogin) throw e
			// Session ended (server restart, expired cookie): sign in again with the saved login and retry once.
			val user = prefs.liServerUser ?: throw e
			val password = SecureStore.decrypt(prefs.liServerPasswordEncrypted) ?: throw e
			login(user, password)
			return request(method, path, query, body)
		}
	}

	private suspend fun request(method: String, path: String, query: Map<String, String>, body: JSONObject?): Any? = withContext(Dispatchers.IO) {
		val queryString = query.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
		val url = URL(base + path + if (queryString.isNotEmpty()) "?$queryString" else "")
		val connection = open(url)
		try {
			connection.requestMethod = method
			connection.connectTimeout = 12_000
			connection.readTimeout = if (path == "/api/search") 60_000 else 25_000
			connection.setRequestProperty("Accept", "application/json")
			connection.setRequestProperty("X-Li-Client", "1")
			prefs.liServerCsrf?.let { connection.setRequestProperty("X-CSRF-Token", it) }
			val cookies = prefs.liServerCookies
			if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
			val code = try {
				if (method != "GET") {
					connection.doOutput = true
					connection.setRequestProperty("Content-Type", "application/json")
					connection.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
				}
				connection.responseCode
			} catch (e: SSLHandshakeException) {
				throw LiServerException(LiServerException.CODE_CERT, "Certificate", "Li'Server's certificate isn't trusted by Android")
			} catch (e: java.io.IOException) {
				throw LiServerException(LiServerException.CODE_NETWORK, "Network", "Can't reach Li'Server (${e.message ?: e.javaClass.simpleName})")
			}
			storeCookies(connection)
			val stream = if (code in 200..299) connection.inputStream else connection.errorStream
			val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
			val data: Any? = when {
				text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
				text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
				else -> null
			}
			if (code !in 200..299) {
				val error = (data as? JSONObject)?.optString("error")?.takeIf { it.isNotBlank() }
				val message = when {
					code == 429 -> "Too many sign-in attempts. Wait a few minutes."
					error == "Forbidden" -> "Your Li'Server account isn't allowed to do that."
					error == "Blocked" -> "Li'Server denied access."
					code == 401 -> "Wrong username or password, or the session ended."
					else -> (data as? JSONObject)?.optString("message")?.takeIf { it.isNotBlank() } ?: error ?: "Li'Server answered $code"
				}
				throw LiServerException(code, error, message)
			}
			data
		} finally {
			connection.disconnect()
		}
	}

	private fun storeCookies(connection: HttpURLConnection) {
		val headers = connection.headerFields.entries.filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
		if (headers.isEmpty()) return
		val jar = prefs.liServerCookies.toMutableMap()
		headers.forEach { header ->
			val first = header.substringBefore(';')
			val name = first.substringBefore('=').trim()
			val value = first.substringAfter('=', "").trim()
			if (name.isEmpty()) return@forEach
			val expired = header.contains("Max-Age=0", ignoreCase = true) || header.contains("expires=Thu, 01 Jan 1970", ignoreCase = true)
			if (value.isEmpty() || expired) jar.remove(name) else jar[name] = value
		}
		prefs.liServerCookies = jar
	}

	private fun open(url: URL): HttpURLConnection {
		val connection = url.openConnection() as HttpURLConnection
		connection.instanceFollowRedirects = true
		if (connection is HttpsURLConnection && url.host in prefs.trustedCertHosts) {
			// You chose to trust this server's own (self-signed) certificate.
			connection.sslSocketFactory = trustingFactory
			connection.hostnameVerifier = HostnameVerifier { host, _ -> host.equals(url.host, ignoreCase = true) }
		}
		return connection
	}

	companion object {
		private val LOOKS_LIKE_SERIES = Regex("(\\bS\\d+|\\bSeason\\b|\\bAvsnitt\\b|\\bE\\d+|\\b\\d{1,2}x\\d{2}\\b|\\bEpisodes?\\b)", RegexOption.IGNORE_CASE)
		private val SERIES_ROOT = Regex("(series|serie|\\btv\\b)", RegexOption.IGNORE_CASE)

		private fun isSeriesRoot(path: String): Boolean {
			val name = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')
			return SERIES_ROOT.containsMatchIn(name) && !Regex("(movie|film)", RegexOption.IGNORE_CASE).containsMatchIn(name)
		}

		private val trustingFactory: SSLSocketFactory by lazy {
			val trustAll = object : X509TrustManager {
				override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
				override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
				override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
			}
			SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory
		}
	}
}
