package app.lumen.player.util

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.UUID

/** All persistent settings, backed by a single SharedPreferences file. */
class Prefs(context: Context) {
	private val sp = context.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

	init {
		// 3.0: the Li'Server login used to be shared by every user. Remove that copy for good.
		if (SHARED_LISERVER_KEYS.any { sp.contains(it) }) sp.edit { SHARED_LISERVER_KEYS.forEach { remove(it) } }
	}

	enum class EngineMode(val label: String) {
		AUTO("Auto (ExoPlayer, VLC fallback)"),
		EXO("ExoPlayer only"),
		VLC("VLC only"),
	}

	private fun bool(key: String, default: Boolean) = sp.getBoolean(key, default)
	private fun int(key: String, default: Int) = sp.getInt(key, default)
	private fun str(key: String): String? = sp.getString(key, null)
	private fun putBool(key: String, value: Boolean) = sp.edit { putBoolean(key, value) }
	private fun putInt(key: String, value: Int) = sp.edit { putInt(key, value) }
	private fun putStr(key: String, value: String?) = sp.edit { putString(key, value) }

	// Playback ------------------------------------------------------------------------------------

	var engineMode: EngineMode
		get() = runCatching { EngineMode.valueOf(str("engine") ?: "AUTO") }.getOrDefault(EngineMode.AUTO)
		set(value) = putStr("engine", value.name)

	/** Name of the default [app.lumen.player.engine.PlayerMode] for playback started inside the app. */
	var defaultPlayerMode: String
		get() = str("default_player_mode") ?: "EXO_DIRECT"
		set(value) = putStr("default_player_mode", value)

	/** 0 = off, 1 = normal, 2 = sensitive. */
	var stutterDetection: Int
		get() = int("stutter_detection", 1)
		set(value) = putInt("stutter_detection", value)

	var rememberWorkingPlayer: Boolean
		get() = bool("remember_player", true)
		set(value) = putBool("remember_player", value)

	var matchFrameRate: Boolean
		get() = bool("match_frame_rate", true)
		set(value) = putBool("match_frame_rate", value)

	var tunneling: Boolean
		get() = bool("tunneling", false)
		set(value) = putBool("tunneling", value)

	var useLibass: Boolean
		get() = bool("libass", true)
		set(value) = putBool("libass", value)

	var vlcPassthrough: Boolean
		get() = bool("vlc_passthrough", true)
		set(value) = putBool("vlc_passthrough", value)

	var seekStepSec: Int
		get() = int("seek_step", 10)
		set(value) = putInt("seek_step", value)

	var rememberDelays: Boolean
		get() = bool("remember_delays", true)
		set(value) = putBool("remember_delays", value)

	var showStats: Boolean
		get() = bool("show_stats", false)
		set(value) = putBool("show_stats", value)

	var autoPlayNext: Boolean
		get() = bool("autoplay_next", true)
		set(value) = putBool("autoplay_next", value)

	// Subtitles ---------------------------------------------------------------------------------

	/** Multiplier for the default subtitle size (1.0 = 5.33% of the video height). */
	var subtitleScale: Float
		get() = sp.getFloat("sub_scale", 1.0f)
		set(value) = sp.edit { putFloat("sub_scale", value) }

	/** Distance of the subtitles from the bottom, as a fraction of the video height. */
	var subtitleBottom: Float
		get() = sp.getFloat("sub_bottom", 0.08f)
		set(value) = sp.edit { putFloat("sub_bottom", value) }

	/** 0 = outline, 1 = drop shadow, 2 = background box. */
	var subtitleStyle: Int
		get() = int("sub_style", 0)
		set(value) = putInt("sub_style", value)

	/** 0 = white, 1 = yellow. */
	var subtitleColor: Int
		get() = int("sub_color", 0)
		set(value) = putInt("sub_color", value)

	/** 0 = as the file / Jellyfin says, 1 = always show preferred language, 2 = start with subtitles off. */
	var subtitleMode: Int
		get() = int("sub_mode", 0)
		set(value) = putInt("sub_mode", value)

	/** Player picture: a ScaleMode name (Auto fills the sides without stretching; remembered). */
	var pictureMode: String?
		get() = str("player_picture_mode")
		set(value) = putStr("player_picture_mode", value)

	/** Language last used for "Download subtitles…" (English name, see SubtitleFinder). */
	var subtitleDownloadLanguage: String?
		get() = str("sub_download_lang")
		set(value) = putStr("sub_download_lang", value)

	/** ISO 639-1 code or empty for "no preference". */
	var preferredSubtitleLanguage: String
		get() = str("sub_lang") ?: ""
		set(value) = putStr("sub_lang", value)

	var preferredAudioLanguage: String
		get() = str("audio_lang") ?: ""
		set(value) = putStr("audio_lang", value)

	// Appearance ------------------------------------------------------------------------------

	/** 0 = small, 1 = medium, 2 = large, 3 = extra large, 4 = huge. */
	var posterSize: Int
		get() = int("ui_poster_size", 1)
		set(value) = putInt("ui_poster_size", value)

	/** Landscape cards (Home rows, episodes, continue watching): 0 = small … 4 = huge. */
	var thumbSize: Int
		get() = int("ui_thumb_size", 1)
		set(value) = putInt("ui_thumb_size", value)

	/** How much the selected card grows: 0 = a little, 1 = big (default), 2 = bigger. */
	var cardZoom: Int
		get() = int("ui_card_zoom", 1)
		set(value) = putInt("ui_card_zoom", value)

	/** 0 = extra small, 1 = small (default in every theme), 2 = medium, 3 = large. */
	var iconSize: Int
		get() = int("ui_icon_size_v2", 1)
		set(value) = putInt("ui_icon_size_v2", value)

	/** Text size in percent (85..130). */
	var textScale: Int
		get() = int("ui_text_scale", 100)
		set(value) = putInt("ui_text_scale", value)

	/** [Themes.AMBER], [Themes.GLASS] or [Themes.PURPLE]. */
	// Per profile: every user on the TV keeps their own theme, navigation and sidebar.

	/** Key of the signed-in profile's own copy of a setting. */
	private fun profileKey(key: String): String = str("user_id")?.let { "$key@$it" } ?: key

	var theme: Int
		get() = sp.getInt(profileKey("ui_theme"), sp.getInt("ui_theme", Themes.AMBER))
		set(value) = putInt(profileKey("ui_theme"), value)

	/** Navigation: 0 = sidebar on the left, 1 = bar on top. */
	var navPosition: Int
		get() = sp.getInt(profileKey("ui_nav_position_v3"), 1) // 3.0: buttons on top by default
		set(value) = putInt(profileKey("ui_nav_position_v3"), value)

	/** Page / row / button motion (off = everything appears instantly). */
	var animations: Boolean
		get() = bool("ui_animations", true)
		set(value) = putBool("ui_animations", value)

	/** Sidebar row size: 0 = compact (default), 1 = normal, 2 = large. */
	var sidebarSize: Int
		get() = int("ui_sidebar_size", 0)
		set(value) = putInt("ui_sidebar_size", value)

	/** 0 = expanded, 1 = icons only, 2 = expand when focused. */
	var sidebarMode: Int
		get() = int("ui_sidebar_mode", 2)
		set(value) = putInt("ui_sidebar_mode", value)

	/** Sidebar item keys in the user's order (hold OK on an item → Move). Empty = default order. */
	var navOrder: List<String>
		get() = (str(profileKey("nav_order")) ?: str("nav_order"))?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
		set(value) = putStr(profileKey("nav_order"), value.joinToString("\n"))

	/** Sidebar items the user hid. */
	var navHidden: Set<String>
		get() = (sp.getStringSet(profileKey("nav_hidden"), null) ?: sp.getStringSet("nav_hidden", emptySet()))?.toSet() ?: emptySet()
		set(value) = sp.edit { putStringSet(profileKey("nav_hidden"), value.toSet()) }

	/** Icons the user picked for navigation items (key → icon name, see NavIcons). */
	var navIcons: Map<String, String>
		get() = runCatching {
			val o = JSONObject(str(profileKey("nav_icons")) ?: "{}")
			o.keys().asSequence().associateWith { o.optString(it) }
		}.getOrDefault(emptyMap())
		set(value) = putStr(profileKey("nav_icons"), JSONObject(value as Map<*, *>).toString())

	// Series tracking (per user) ----------------------------------------------------------------

	private fun profileSet(key: String): Set<String> = sp.getStringSet(profileKey(key), emptySet())?.toSet() ?: emptySet()
	private fun putProfileSet(key: String, value: Set<String>) = sp.edit { putStringSet(profileKey(key), value.toSet()) }

	/** Series you marked "Track" on their page. */
	var trackedSeries: Set<String>
		get() = profileSet("tracked_series")
		set(value) = putProfileSet("tracked_series", value)

	/** Series you watch a lot, picked automatically (refreshed on Home). */
	var autoTrackedSeries: Set<String>
		get() = profileSet("auto_tracked_series")
		set(value) = putProfileSet("auto_tracked_series", value)

	/** Auto-tracked series you turned off. */
	var untrackedSeries: Set<String>
		get() = profileSet("untracked_series")
		set(value) = putProfileSet("untracked_series", value)

	fun isTracked(seriesId: String): Boolean =
		seriesId !in untrackedSeries && (seriesId in trackedSeries || seriesId in autoTrackedSeries)

	fun setTracked(seriesId: String, tracked: Boolean) {
		if (tracked) {
			trackedSeries = trackedSeries + seriesId
			untrackedSeries = untrackedSeries - seriesId
		} else {
			trackedSeries = trackedSeries - seriesId
			if (seriesId in autoTrackedSeries) untrackedSeries = untrackedSeries + seriesId
		}
	}

	/** New episodes Home already told you about. */
	var newEpisodesSeen: Set<String>
		get() = profileSet("new_episodes_seen")
		set(value) = putProfileSet("new_episodes_seen", value)

	/** Sort / filter last used in a library (names of LibraryPage's options). */
	fun librarySort(libraryId: String): String? = str(profileKey("lib_sort:$libraryId"))
	fun setLibrarySort(libraryId: String, sort: String) = putStr(profileKey("lib_sort:$libraryId"), sort)
	fun libraryFilter(libraryId: String): String? = str(profileKey("lib_filter:$libraryId"))
	fun setLibraryFilter(libraryId: String, filter: String) = putStr(profileKey("lib_filter:$libraryId"), filter)

	/** Banner / card previews: 0 = off, 1 = small only, 2 = small, then full screen. */
	var previewMode: Int
		get() = int("ui_preview", 2)
		set(value) = putInt("ui_preview", value)

	var previewSound: Boolean
		get() = bool("ui_preview_sound", true)
		set(value) = putBool("ui_preview_sound", value)

	/** Clock in the top right corner of the menus (never during playback). */
	var showClock: Boolean
		get() = bool("ui_clock", true)
		set(value) = putBool("ui_clock", value)

	var showCardTitles: Boolean
		get() = bool("ui_card_titles", true)
		set(value) = putBool("ui_card_titles", value)

	var showHero: Boolean
		get() = bool("ui_hero", true)
		set(value) = putBool("ui_hero", value)

	/** Home rows as wide 16:9 cards (streaming-app style) instead of posters. */
	var homeWideCards: Boolean
		get() = bool("ui_home_wide", true)
		set(value) = putBool("ui_home_wide", value)

	var showRatings: Boolean
		get() = bool("ui_ratings", true)
		set(value) = putBool("ui_ratings", value)

	var rowContinue: Boolean
		get() = bool("row_continue", true)
		set(value) = putBool("row_continue", value)

	var rowNextUp: Boolean
		get() = bool("row_next_up", true)
		set(value) = putBool("row_next_up", value)

	var rowLatest: Boolean
		get() = bool("row_latest", true)
		set(value) = putBool("row_latest", value)

	var rowUpcoming: Boolean
		get() = bool("row_upcoming", true)
		set(value) = putBool("row_upcoming", value)

	var rowFavorites: Boolean
		get() = bool("row_favorites", true)
		set(value) = putBool("row_favorites", value)

	// Jellyfin server ---------------------------------------------------------------------------

	var serverUrl: String?
		get() = str("server_url") ?: DEFAULT_SERVER
		set(value) = putStr("server_url", value)

	/** Servers to choose between (Settings › Server, sign-in screen). The Lolish server is always first. */
	var servers: List<String>
		get() {
			val saved = str("servers")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
			val known = saved + accounts.map { it.serverUrl } + listOfNotNull(serverUrl)
			return (listOf(DEFAULT_SERVER) + known).map { it.trimEnd('/') }.distinctBy { it.lowercase() }
		}
		set(value) = putStr("servers", value.joinToString("\n"))

	fun addServer(url: String) {
		servers = (servers + url.trimEnd('/')).distinctBy { it.lowercase() }
	}

	fun removeServer(url: String) {
		putStr("servers", servers.filter { !it.equals(url.trimEnd('/'), ignoreCase = true) && !it.equals(DEFAULT_SERVER, ignoreCase = true) }.joinToString("\n"))
	}

	var serverName: String?
		get() = str("server_name")
		set(value) = putStr("server_name", value)

	var accessToken: String?
		get() = str("access_token")
		set(value) = putStr("access_token", value)

	var userId: String?
		get() = str("user_id")
		set(value) = putStr("user_id", value)

	var userName: String?
		get() = str("user_name")
		set(value) = putStr("user_name", value)

	/** Base URL of the last Jellyfin server that sent us a stream; used to pre-fill linking. */
	var lastSeenServer: String?
		get() = str("last_seen_server")
		set(value) = putStr("last_seen_server", value)

	val deviceId: String
		get() = str("device_id") ?: UUID.randomUUID().toString().also { id -> putStr("device_id", id) }

	val isLinked: Boolean
		get() = !serverUrl.isNullOrBlank() && !accessToken.isNullOrBlank() && !userId.isNullOrBlank()

	/** Clears the active user (the saved profiles stay). */
	fun unlink() = sp.edit {
		remove("access_token")
		remove("user_id")
		remove("user_name")
		remove("server_name")
		remove("active_device_id")
	}

	// Profiles (several Jellyfin users on this TV) ----------------------------------------------------

	/** Every signed-in user saved on this device. The active one is mirrored in the fields above. */
	val accounts: List<Account>
		get() {
			val raw = str("accounts")
			val list = if (raw == null) emptyList() else Account.parseList(raw)
			if (list.isEmpty() && isLinked) {
				// First run after the update: keep the user who was already signed in.
				val legacy = Account(
					serverUrl = serverUrl ?: DEFAULT_SERVER,
					serverName = serverName,
					userId = userId!!,
					userName = userName ?: "User",
					token = accessToken!!,
					deviceId = deviceId,
					imageTag = null,
				)
				putStr("accounts", Account.toJson(listOf(legacy)))
				putStr("active_device_id", legacy.deviceId)
				return listOf(legacy)
			}
			return list
		}

	val activeAccount: Account?
		get() {
			val id = userId ?: return null
			val token = accessToken ?: return null
			return accounts.firstOrNull { it.userId == id && it.token == token }
		}

	/** DeviceId sent to Jellyfin for [token]; every profile has its own so the server keeps them apart. */
	fun deviceIdFor(token: String?): String {
		if (token != null) {
			if (token == accessToken) str("active_device_id")?.let { return it }
			accounts.firstOrNull { it.token == token }?.let { return it.deviceId }
		}
		return deviceId
	}

	/** Stable DeviceId for a username, so signing in again replaces the old token instead of piling up. */
	fun deviceIdForUser(baseUrl: String, userName: String): String {
		val digest = java.security.MessageDigest.getInstance("SHA-256")
			.digest("$deviceId|$baseUrl|${userName.lowercase()}".toByteArray())
		return digest.take(16).joinToString("") { "%02x".format(it) }
	}

	/** Saves (or refreshes) a profile and makes it the active user. */
	fun saveAccount(account: Account) {
		rememberUser(account.serverUrl, account.userName)
		val others = accounts.filterNot { it.sameUser(account) }
		putStr("accounts", Account.toJson(others + account))
		activate(account)
	}

	fun activate(account: Account) {
		// Another user: the Li'Server website must not stay signed in as the previous one.
		if (str("user_id") != account.userId) {
			runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
		}
		activateKeys(account)
	}

	private fun activateKeys(account: Account) = sp.edit {
		putString("server_url", account.serverUrl)
		putString("server_name", account.serverName)
		putString("access_token", account.token)
		putString("user_id", account.userId)
		putString("user_name", account.userName)
		putString("active_device_id", account.deviceId)
		putString("last_account", account.key)
	}

	fun removeAccount(account: Account) {
		putStr("accounts", Account.toJson(accounts.filterNot { it.sameUser(account) }))
		if (activeAccount?.sameUser(account) == true || userId == account.userId) unlink()
	}

	/** Usernames that signed in on this TV before (per server), shown on the sign-in screen. */
	fun recentUsers(baseUrl: String): List<String> {
		val prefix = baseUrl.trimEnd('/') + "|"
		return (sp.getStringSet("recent_users", emptySet()) ?: emptySet())
			.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.filter { it.isNotBlank() }.sorted()
	}

	fun rememberUser(baseUrl: String, userName: String) {
		val all = (sp.getStringSet("recent_users", emptySet()) ?: emptySet()).toMutableSet()
		all.removeAll { it.equals(baseUrl.trimEnd('/') + "|" + userName, ignoreCase = true) }
		all += baseUrl.trimEnd('/') + "|" + userName
		sp.edit { putStringSet("recent_users", all) }
	}

	/** Key of the profile used last, to pre-select it in "Who's watching?". */
	val lastAccountKey: String?
		get() = str("last_account")

	/** Show "Who's watching?" when the app starts and more than one profile is saved. */
	var askWhoIsWatching: Boolean
		get() = bool("ask_who_is_watching", true)
		set(value) = putBool("ask_who_is_watching", value)

	// App updates ------------------------------------------------------------------------------

	var autoUpdateCheck: Boolean
		get() = bool("auto_update_check", true)
		set(value) = putBool("auto_update_check", value)

	var lastUpdateCheck: Long
		get() = sp.getLong("last_update_check", 0L)
		set(value) = sp.edit { putLong("last_update_check", value) }

	/** A release the user chose to skip (tag name). */
	var skippedUpdate: String?
		get() = str("skipped_update")
		set(value) = putStr("skipped_update", value)

	// Li'Server (torrents) -----------------------------------------------------------------------

	var liServerUrl: String
		get() = str("liserver_url") ?: DEFAULT_LISERVER
		set(value) = putStr("liserver_url", value)

	// The Li'Server login belongs to the Jellyfin user that's signed in: every user types their own
	// once, and nobody else on the TV sees it (no shared fallback).

	var liServerUser: String?
		get() = str(profileKey("liserver_user"))
		set(value) = putStr(profileKey("liserver_user"), value)

	/** Encrypted with the Android Keystore (see [SecureStore]). */
	var liServerPasswordEncrypted: String?
		get() = str(profileKey("liserver_pass"))
		set(value) = putStr(profileKey("liserver_pass"), value)

	var liServerAutoLogin: Boolean
		get() = bool(profileKey("liserver_auto_login"), true)
		set(value) = putBool(profileKey("liserver_auto_login"), value)

	/** Session cookies of the native Li'Server screen (kept across restarts and app updates). */
	var liServerCookies: Map<String, String>
		get() = runCatching {
			val o = JSONObject(str(profileKey("liserver_cookies")) ?: "{}")
			o.keys().asSequence().associateWith { o.optString(it) }
		}.getOrDefault(emptyMap())
		set(value) = putStr(profileKey("liserver_cookies"), JSONObject(value as Map<*, *>).toString())

	var liServerCsrf: String?
		get() = str(profileKey("liserver_csrf"))
		set(value) = putStr(profileKey("liserver_csrf"), value)

	/** Open the Li'Server website instead of the TV screen. */
	var liServerUseWebsite: Boolean
		get() = bool("liserver_use_website", false)
		set(value) = putBool("liserver_use_website", value)

	/** Page zoom in percent. */
	var liServerZoom: Int
		get() = int("liserver_zoom", 80)
		set(value) = putInt("liserver_zoom", value)

	/** Host names whose (self-signed) certificates the user chose to trust. */
	var trustedCertHosts: Set<String>
		get() = sp.getStringSet("trusted_cert_hosts", emptySet()) ?: emptySet()
		set(value) = sp.edit { putStringSet("trusted_cert_hosts", value) }

	// Per-video memory ---------------------------------------------------------------------------

	/** Returns (subtitleDelayMs, audioDelayMs) remembered for [key]. */
	fun delaysFor(key: String): Pair<Long, Long> {
		val obj = json("delays").optJSONObject(key) ?: return 0L to 0L
		return obj.optLong("s", 0L) to obj.optLong("a", 0L)
	}

	fun saveDelays(key: String, subtitleMs: Long, audioMs: Long) {
		val all = json("delays")
		all.remove(key)
		if (subtitleMs != 0L || audioMs != 0L) {
			all.put(key, JSONObject().put("s", subtitleMs).put("a", audioMs).put("t", System.currentTimeMillis()))
		}
		trim(all)
		sp.edit { putString("delays", all.toString()) }
	}

	/** Player mode that worked for an item or a whole series. */
	fun rememberedPlayerMode(vararg keys: String?): String? {
		val all = json("player_modes")
		return keys.filterNotNull().firstNotNullOfOrNull { key -> all.optJSONObject(key)?.optString("m")?.takeIf { it.isNotBlank() } }
	}

	fun rememberPlayerMode(mode: String, vararg keys: String?) {
		val all = json("player_modes")
		keys.filterNotNull().forEach { key -> all.put(key, JSONObject().put("m", mode).put("t", System.currentTimeMillis())) }
		trim(all)
		sp.edit { putString("player_modes", all.toString()) }
	}

	fun forgetPlayerMode(vararg keys: String?) {
		val all = json("player_modes")
		keys.filterNotNull().forEach { all.remove(it) }
		sp.edit { putString("player_modes", all.toString()) }
	}

	private fun trim(all: JSONObject) {
		if (all.length() <= 400) return
		val entries = all.keys().asSequence().toList().sortedBy { all.optJSONObject(it)?.optLong("t") ?: 0L }
		entries.take(all.length() - 400).forEach { all.remove(it) }
	}

	private fun json(key: String): JSONObject =
		runCatching { JSONObject(sp.getString(key, null) ?: "{}") }.getOrDefault(JSONObject())

	companion object {
		private val SHARED_LISERVER_KEYS = listOf("liserver_user", "liserver_pass", "liserver_cookies", "liserver_csrf", "liserver_auto_login")
		const val DEFAULT_SERVER = "http://lolish.no-ip.com:8096"
		const val DEFAULT_LISERVER = "https://lolish.no-ip.com:5000/"
	}
}
