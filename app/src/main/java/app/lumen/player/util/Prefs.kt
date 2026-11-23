package app.lumen.player.util

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.UUID

/** All persistent settings, backed by a single SharedPreferences file. */
class Prefs(context: Context) {
	private val sp = context.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

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

	/** ISO 639-1 code or empty for "no preference". */
	var preferredSubtitleLanguage: String
		get() = str("sub_lang") ?: ""
		set(value) = putStr("sub_lang", value)

	var preferredAudioLanguage: String
		get() = str("audio_lang") ?: ""
		set(value) = putStr("audio_lang", value)

	// Appearance ------------------------------------------------------------------------------

	/** 0 = small, 1 = medium, 2 = large, 3 = extra large. */
	var posterSize: Int
		get() = int("ui_poster_size", 1)
		set(value) = putInt("ui_poster_size", value)

	/** Landscape cards (episodes, continue watching): 0 = small, 1 = medium, 2 = large. */
	var thumbSize: Int
		get() = int("ui_thumb_size", 1)
		set(value) = putInt("ui_thumb_size", value)

	/** 0 = small, 1 = medium, 2 = large. */
	var iconSize: Int
		get() = int("ui_icon_size", 1)
		set(value) = putInt("ui_icon_size", value)

	/** Text size in percent (85..130). */
	var textScale: Int
		get() = int("ui_text_scale", 100)
		set(value) = putInt("ui_text_scale", value)

	/** 0 = expanded, 1 = icons only, 2 = expand when focused. */
	var sidebarMode: Int
		get() = int("ui_sidebar_mode", 2)
		set(value) = putInt("ui_sidebar_mode", value)

	var showCardTitles: Boolean
		get() = bool("ui_card_titles", true)
		set(value) = putBool("ui_card_titles", value)

	var showHero: Boolean
		get() = bool("ui_hero", true)
		set(value) = putBool("ui_hero", value)

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
		val others = accounts.filterNot { it.sameUser(account) }
		putStr("accounts", Account.toJson(others + account))
		activate(account)
	}

	fun activate(account: Account) = sp.edit {
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

	var liServerUser: String?
		get() = str("liserver_user")
		set(value) = putStr("liserver_user", value)

	/** Encrypted with the Android Keystore (see [SecureStore]). */
	var liServerPasswordEncrypted: String?
		get() = str("liserver_pass")
		set(value) = putStr("liserver_pass", value)

	var liServerAutoLogin: Boolean
		get() = bool("liserver_auto_login", true)
		set(value) = putBool("liserver_auto_login", value)

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
		const val DEFAULT_SERVER = "http://lolish.no-ip.com:8096"
		const val DEFAULT_LISERVER = "https://lolish.no-ip.com:5000/"
	}
}
