package app.lumen.player.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import app.lumen.player.BuildConfig
import app.lumen.player.R
import app.lumen.player.jellyfin.ApiException
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.Session
import app.lumen.player.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Home screen: status, how-to, settings and optional Jellyfin server linking. */
class MainActivity : ComponentActivity() {
	private lateinit var prefs: Prefs
	private lateinit var api: JellyfinApi
	private lateinit var builder: PanelBuilder

	private lateinit var status: TextView
	private lateinit var linkOverlay: View
	private lateinit var serverUrl: EditText
	private lateinit var passwordGroup: View
	private lateinit var username: EditText
	private lateinit var password: EditText
	private lateinit var quickConnectCode: TextView
	private lateinit var linkStatus: TextView
	private var linkJob: Job? = null

	private val isJellyfinBuild: Boolean get() = packageName == "is.xyz.mpv"

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		prefs = Prefs(this)
		api = JellyfinApi(prefs)
		builder = PanelBuilder(findViewById<LinearLayout>(R.id.settings_list))

		status = findViewById(R.id.status)
		linkOverlay = findViewById(R.id.link_overlay)
		serverUrl = findViewById(R.id.server_url)
		passwordGroup = findViewById(R.id.password_group)
		username = findViewById(R.id.username)
		password = findViewById(R.id.password)
		quickConnectCode = findViewById(R.id.quick_connect_code)
		linkStatus = findViewById(R.id.link_status)
		findViewById<TextView>(R.id.version).text = "v${BuildConfig.VERSION_NAME}  ·  $packageName"

		findViewById<View>(R.id.btn_quick_connect).setOnClickListener { startQuickConnect() }
		findViewById<View>(R.id.btn_password).setOnClickListener {
			passwordGroup.visibility = View.VISIBLE
			quickConnectCode.visibility = View.GONE
			linkJob?.cancel()
			username.requestFocus()
		}
		findViewById<View>(R.id.btn_sign_in).setOnClickListener { signInWithPassword() }
		findViewById<View>(R.id.btn_cancel_link).setOnClickListener { closeLink() }
		password.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				signInWithPassword()
				true
			} else false
		}

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (linkOverlay.visibility == View.VISIBLE) closeLink() else finish()
			}
		})

		buildSettings()
		updateStatus()
		builder.initialFocus()?.requestFocus()
	}

	private fun updateStatus() {
		val setup = if (isJellyfinBuild) {
			"Ready to use.\n\nIn the Jellyfin app open Settings → Playback → Video player, choose “External app” and pick Lumen Player. " +
				"Jellyfin will hand over the stream and subtitles, and your resume position and watched status are saved when you stop."
		} else {
			"Standalone build — works with “Open with” from any app.\n\nFor full Jellyfin integration (subtitles, resume position, watched status) install the “jellyfin” build instead."
		}
		val link = if (prefs.isLinked) {
			"\n\nLinked to ${prefs.serverName ?: "Jellyfin"} as ${prefs.userName ?: "user"}: skip intro, chapters and seek thumbnails are on."
		} else {
			"\n\nTip: link your server below to get skip intro, chapters and seek thumbnails."
		}
		status.text = setup + link
	}

	// Settings list -----------------------------------------------------------------------------

	private fun buildSettings() {
		val b = builder
		b.clear()

		b.header("Jellyfin server")
		b.action(
			if (prefs.isLinked) "Linked server" else "Link server",
			if (prefs.isLinked) prefs.serverUrl else "Quick Connect or username & password",
			if (prefs.isLinked) "${prefs.userName ?: ""} @ ${prefs.serverName ?: ""}".trim() else null,
		) { openLink() }
		if (prefs.isLinked) {
			b.action("Unlink server") {
				prefs.unlink()
				buildSettings()
				updateStatus()
				builder.initialFocus()?.requestFocus()
			}
		}

		b.header("Playback")
		b.stepper("Engine", value = { prefs.engineMode.label }) { direction, _ ->
			val values = Prefs.EngineMode.entries
			prefs.engineMode = values[(prefs.engineMode.ordinal + direction + values.size) % values.size]
		}
		b.toggle("Match frame rate", "Switch the TV to 23.976 / 24 / 25 / 50 / 60 Hz to match the video", isOn = { prefs.matchFrameRate }) {
			prefs.matchFrameRate = it
		}
		b.toggle("Tunneled playback", "Can make 4K HDR smoother on some TVs (ExoPlayer)", isOn = { prefs.tunneling }) {
			prefs.tunneling = it
		}
		b.stepper("Seek step", value = { "${prefs.seekStepSec} s" }) { direction, _ ->
			val index = (SEEK_STEPS.indexOf(prefs.seekStepSec).takeIf { it >= 0 } ?: 1) + direction
			prefs.seekStepSec = SEEK_STEPS[index.coerceIn(0, SEEK_STEPS.lastIndex)]
		}
		b.toggle("Remember sync per video", "Keep subtitle / audio delay for each video", isOn = { prefs.rememberDelays }) {
			prefs.rememberDelays = it
		}

		b.header("Audio")
		b.stepper("Preferred language", value = { languageLabel(prefs.preferredAudioLanguage) }) { direction, _ ->
			prefs.preferredAudioLanguage = cycleLanguage(prefs.preferredAudioLanguage, direction)
		}
		b.toggle("VLC passthrough", "Send Dolby / DTS bitstream to the receiver when VLC is used", isOn = { prefs.vlcPassthrough }) {
			prefs.vlcPassthrough = it
		}

		b.header("Subtitles")
		b.stepper("Show subtitles", value = { SUBTITLE_MODES[prefs.subtitleMode.coerceIn(0, 2)] }) { direction, _ ->
			prefs.subtitleMode = (prefs.subtitleMode + direction + 3) % 3
		}
		b.stepper("Preferred language", value = { languageLabel(prefs.preferredSubtitleLanguage) }) { direction, _ ->
			prefs.preferredSubtitleLanguage = cycleLanguage(prefs.preferredSubtitleLanguage, direction)
		}
		b.toggle("libass for ASS / SSA", "Full anime-style subtitle styling", isOn = { prefs.useLibass }) {
			prefs.useLibass = it
		}
		b.stepper("Size", value = { "${(prefs.subtitleScale * 100).roundToInt()}%" }) { direction, _ ->
			prefs.subtitleScale = ((prefs.subtitleScale * 20).roundToInt() + direction).coerceIn(10, 50) / 20f
		}
		b.stepper("Position", value = { "${(prefs.subtitleBottom * 100).roundToInt()}%" }) { direction, _ ->
			prefs.subtitleBottom = ((prefs.subtitleBottom * 50).roundToInt() + direction).coerceIn(0, 20) / 50f
		}
		b.stepper("Style", value = { SUBTITLE_STYLES[prefs.subtitleStyle.coerceIn(0, 2)] }) { direction, _ ->
			prefs.subtitleStyle = (prefs.subtitleStyle + direction + 3) % 3
		}
		b.stepper("Colour", value = { if (prefs.subtitleColor == 1) "Yellow" else "White" }) { _, _ ->
			prefs.subtitleColor = if (prefs.subtitleColor == 1) 0 else 1
		}

		b.header("Remote shortcuts")
		b.note(
			"OK: play / pause   ·   ◀ ▶: seek (hold to go faster)   ·   ▲ ▼: show controls\n" +
				"Menu: settings   ·   Back: hide controls, then exit\n" +
				"In the player, the clock button opens Sync for subtitle and audio delay."
		)
	}

	private fun languageLabel(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

	private fun cycleLanguage(current: String, direction: Int): String {
		val index = LANGUAGES.indexOfFirst { it.first == current }.takeIf { it >= 0 } ?: 0
		return LANGUAGES[(index + direction + LANGUAGES.size) % LANGUAGES.size].first
	}

	// Linking -------------------------------------------------------------------------------------

	private fun openLink() {
		linkOverlay.visibility = View.VISIBLE
		serverUrl.setText(prefs.serverUrl ?: prefs.lastSeenServer ?: "")
		passwordGroup.visibility = View.GONE
		quickConnectCode.visibility = View.GONE
		linkStatus.text = if (prefs.lastSeenServer != null && prefs.serverUrl == null) {
			"Filled in from the last stream Jellyfin sent to this player."
		} else ""
		findViewById<View>(R.id.btn_quick_connect).requestFocus()
	}

	private fun closeLink() {
		linkJob?.cancel()
		linkOverlay.visibility = View.GONE
		buildSettings()
		updateStatus()
		builder.initialFocus()?.requestFocus()
	}

	private fun startQuickConnect() {
		val base = JellyfinApi.normalizeBaseUrl(serverUrl.text.toString())
		passwordGroup.visibility = View.GONE
		linkJob?.cancel()
		linkStatus.text = "Connecting to $base…"
		linkJob = lifecycleScope.launch {
			try {
				val name = api.publicServerName(base)
				val (secret, code) = api.quickConnectInitiate(base)
				quickConnectCode.text = code
				quickConnectCode.visibility = View.VISIBLE
				linkStatus.text = "On your phone or computer open Jellyfin → your profile → Quick Connect, and enter this code."
				var authorized = false
				while (isActive && !authorized) {
					delay(3_000L)
					authorized = api.quickConnectAuthorized(base, secret)
				}
				val session = api.authenticateWithQuickConnect(base, secret)
				onLinked(base, name, session)
			} catch (e: CancellationException) {
				throw e
			} catch (e: ApiException) {
				quickConnectCode.visibility = View.GONE
				linkStatus.text = if (e.code == 401 || e.code == 403) {
					"Quick Connect is turned off on this server. Enable it in Dashboard → General, or use username & password."
				} else {
					"Quick Connect failed: ${e.message}"
				}
			} catch (e: Exception) {
				quickConnectCode.visibility = View.GONE
				linkStatus.text = "Couldn't reach $base (${e.message ?: e.javaClass.simpleName})."
			}
		}
	}

	private fun signInWithPassword() {
		val base = JellyfinApi.normalizeBaseUrl(serverUrl.text.toString())
		val user = username.text.toString().trim()
		if (user.isEmpty()) {
			linkStatus.text = "Enter your Jellyfin username."
			return
		}
		linkJob?.cancel()
		linkStatus.text = "Signing in…"
		linkJob = lifecycleScope.launch {
			try {
				val name = api.publicServerName(base)
				val session = api.authenticateByName(base, user, password.text.toString())
				onLinked(base, name, session)
			} catch (e: CancellationException) {
				throw e
			} catch (e: ApiException) {
				linkStatus.text = if (e.code == 401) "Wrong username or password." else "Sign-in failed: ${e.message}"
			} catch (e: Exception) {
				linkStatus.text = "Couldn't reach $base (${e.message ?: e.javaClass.simpleName})."
			}
		}
	}

	private suspend fun onLinked(base: String, serverName: String, session: Session) {
		prefs.serverUrl = base
		prefs.serverName = serverName
		prefs.accessToken = session.token
		prefs.userId = session.userId
		password.setText("")
		quickConnectCode.visibility = View.GONE
		linkStatus.text = "Linked to $serverName ✓"
		delay(900L)
		closeLink()
	}

	private companion object {
		val SEEK_STEPS = listOf(5, 10, 15, 30)
		val SUBTITLE_MODES = listOf("As Jellyfin / file says", "Always (preferred language)", "Start with subtitles off")
		val SUBTITLE_STYLES = listOf("Outline", "Drop shadow", "Background box")
		val LANGUAGES = listOf(
			"" to "No preference",
			"en" to "English",
			"sv" to "Swedish",
			"bs" to "Bosnian",
			"hr" to "Croatian",
			"sr" to "Serbian",
			"no" to "Norwegian",
			"da" to "Danish",
			"fi" to "Finnish",
			"de" to "German",
			"fr" to "French",
			"es" to "Spanish",
			"it" to "Italian",
			"nl" to "Dutch",
			"pl" to "Polish",
			"pt" to "Portuguese",
			"ru" to "Russian",
			"tr" to "Turkish",
			"ar" to "Arabic",
			"ja" to "Japanese",
			"ko" to "Korean",
			"zh" to "Chinese",
		)
	}
}
