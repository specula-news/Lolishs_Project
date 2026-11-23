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

	var engineMode: EngineMode
		get() = runCatching { EngineMode.valueOf(sp.getString("engine", null) ?: "AUTO") }.getOrDefault(EngineMode.AUTO)
		set(value) = sp.edit { putString("engine", value.name) }

	var matchFrameRate: Boolean
		get() = sp.getBoolean("match_frame_rate", true)
		set(value) = sp.edit { putBoolean("match_frame_rate", value) }

	var tunneling: Boolean
		get() = sp.getBoolean("tunneling", false)
		set(value) = sp.edit { putBoolean("tunneling", value) }

	var useLibass: Boolean
		get() = sp.getBoolean("libass", true)
		set(value) = sp.edit { putBoolean("libass", value) }

	var vlcPassthrough: Boolean
		get() = sp.getBoolean("vlc_passthrough", true)
		set(value) = sp.edit { putBoolean("vlc_passthrough", value) }

	var seekStepSec: Int
		get() = sp.getInt("seek_step", 10)
		set(value) = sp.edit { putInt("seek_step", value) }

	var rememberDelays: Boolean
		get() = sp.getBoolean("remember_delays", true)
		set(value) = sp.edit { putBoolean("remember_delays", value) }

	var showStats: Boolean
		get() = sp.getBoolean("show_stats", false)
		set(value) = sp.edit { putBoolean("show_stats", value) }

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
		get() = sp.getInt("sub_style", 0)
		set(value) = sp.edit { putInt("sub_style", value) }

	/** 0 = white, 1 = yellow. */
	var subtitleColor: Int
		get() = sp.getInt("sub_color", 0)
		set(value) = sp.edit { putInt("sub_color", value) }

	/** 0 = as the file / Jellyfin says, 1 = always show preferred language, 2 = start with subtitles off. */
	var subtitleMode: Int
		get() = sp.getInt("sub_mode", 0)
		set(value) = sp.edit { putInt("sub_mode", value) }

	/** ISO 639-1 code or empty for "no preference". */
	var preferredSubtitleLanguage: String
		get() = sp.getString("sub_lang", "") ?: ""
		set(value) = sp.edit { putString("sub_lang", value) }

	var preferredAudioLanguage: String
		get() = sp.getString("audio_lang", "") ?: ""
		set(value) = sp.edit { putString("audio_lang", value) }

	// Jellyfin server link ----------------------------------------------------------------------

	var serverUrl: String?
		get() = sp.getString("server_url", null)
		set(value) = sp.edit { putString("server_url", value) }

	var serverName: String?
		get() = sp.getString("server_name", null)
		set(value) = sp.edit { putString("server_name", value) }

	var accessToken: String?
		get() = sp.getString("access_token", null)
		set(value) = sp.edit { putString("access_token", value) }

	var userId: String?
		get() = sp.getString("user_id", null)
		set(value) = sp.edit { putString("user_id", value) }

	var userName: String?
		get() = sp.getString("user_name", null)
		set(value) = sp.edit { putString("user_name", value) }

	/** Base URL of the last Jellyfin server that sent us a stream; used to pre-fill linking. */
	var lastSeenServer: String?
		get() = sp.getString("last_seen_server", null)
		set(value) = sp.edit { putString("last_seen_server", value) }

	val deviceId: String
		get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also { id ->
			sp.edit { putString("device_id", id) }
		}

	val isLinked: Boolean
		get() = !serverUrl.isNullOrBlank() && !accessToken.isNullOrBlank() && !userId.isNullOrBlank()

	fun unlink() = sp.edit {
		remove("access_token")
		remove("user_id")
		remove("user_name")
		remove("server_name")
	}

	// Per-video sync offsets ---------------------------------------------------------------------

	/** Returns (subtitleDelayMs, audioDelayMs) remembered for [key]. */
	fun delaysFor(key: String): Pair<Long, Long> {
		val obj = delays().optJSONObject(key) ?: return 0L to 0L
		return obj.optLong("s", 0L) to obj.optLong("a", 0L)
	}

	fun saveDelays(key: String, subtitleMs: Long, audioMs: Long) {
		val all = delays()
		all.remove(key)
		if (subtitleMs != 0L || audioMs != 0L) {
			all.put(key, JSONObject().put("s", subtitleMs).put("a", audioMs).put("t", System.currentTimeMillis()))
		}
		// Keep the store small: drop the oldest entries beyond 300.
		if (all.length() > 300) {
			val entries = all.keys().asSequence().toList()
				.sortedBy { all.optJSONObject(it)?.optLong("t") ?: 0L }
			entries.take(all.length() - 300).forEach { all.remove(it) }
		}
		sp.edit { putString("delays", all.toString()) }
	}

	private fun delays(): JSONObject =
		runCatching { JSONObject(sp.getString("delays", null) ?: "{}") }.getOrDefault(JSONObject())
}
