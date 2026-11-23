package app.lumen.player.ui.pages

import android.view.View
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.ScrollView
import app.lumen.player.BuildConfig
import app.lumen.player.engine.PlayerMode
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.ui.Dialogs
import app.lumen.player.ui.MainActivity
import app.lumen.player.ui.PanelBuilder
import app.lumen.player.util.Prefs
import app.lumen.player.util.SecureStore
import app.lumen.player.util.Ui
import kotlin.math.roundToInt

/** All options: account, appearance (sizes), playback, subtitles, audio and Li'Server. */
class SettingsPage(host: MainActivity, private val scrollToAccount: Boolean = false) : Page(host) {
	private val scroll = ScrollView(host).apply {
		isVerticalScrollBarEnabled = false
		clipToPadding = false
	}
	private val list = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(Ui.dp(host, 44), Ui.dp(host, 30), Ui.dp(host, 44), Ui.dp(host, 80))
	}
	override val view: View = scroll
	private val builder = PanelBuilder(list)
	private val prefs: Prefs get() = host.prefs
	private var appearanceChanged = false

	init {
		scroll.addView(list)
	}

	override fun onShow() = build()

	override fun onHide() {
		if (appearanceChanged) host.restart()
	}

	override fun focusFirst(): Boolean = builder.initialFocus()?.requestFocus() == true

	private fun appearance(change: () -> Unit) {
		change()
		appearanceChanged = true
	}

	private fun build() {
		val b = builder
		b.clear()
		list.addView(host.cards.heading("Settings", null, 40f))

		// Account
		b.header("Jellyfin account")
		b.action("Server", prefs.serverName ?: "Jellyfin", JellyfinApi.normalizeBaseUrl(prefs.serverUrl ?: Prefs.DEFAULT_SERVER)) {
			Dialogs.input(host, "Jellyfin server address", prefs.serverUrl, Prefs.DEFAULT_SERVER) { value ->
				if (value.isNotBlank()) {
					prefs.serverUrl = JellyfinApi.normalizeBaseUrl(value)
					host.signOut()
				}
			}
		}
		b.action("Signed in as ${prefs.userName ?: "user"}", "Switch user or sign out", "Sign out") {
			Dialogs.confirm(host, "Sign out?", "You can sign in again with Quick Connect or your password.", "Sign out") {
				host.signOut()
			}
		}

		// Appearance
		b.header("Appearance")
		b.stepper("Poster size", value = { SIZES4[prefs.posterSize.coerceIn(0, 3)] }) { d, _ ->
			appearance { prefs.posterSize = (prefs.posterSize + d).coerceIn(0, 3) }
		}
		b.stepper("Episode / thumbnail size", value = { SIZES3[prefs.thumbSize.coerceIn(0, 2)] }) { d, _ ->
			appearance { prefs.thumbSize = (prefs.thumbSize + d).coerceIn(0, 2) }
		}
		b.stepper("Icon size", "Sidebar and player buttons", value = { SIZES3[prefs.iconSize.coerceIn(0, 2)] }) { d, _ ->
			appearance { prefs.iconSize = (prefs.iconSize + d).coerceIn(0, 2) }
		}
		b.stepper("Text size", value = { "${prefs.textScale}%" }) { d, _ ->
			appearance {
				val index = (TEXT_SCALES.indexOf(prefs.textScale).takeIf { it >= 0 } ?: 2) + d
				prefs.textScale = TEXT_SCALES[index.coerceIn(0, TEXT_SCALES.lastIndex)]
			}
		}
		b.stepper("Sidebar", value = { SIDEBAR[prefs.sidebarMode.coerceIn(0, 2)] }) { d, _ ->
			appearance { prefs.sidebarMode = (prefs.sidebarMode + d + 3) % 3 }
		}
		b.toggle("Titles under posters", isOn = { prefs.showCardTitles }) { appearance { prefs.showCardTitles = it } }
		b.toggle("Ratings on posters", isOn = { prefs.showRatings }) { appearance { prefs.showRatings = it } }
		b.toggle("Featured banner on Home", isOn = { prefs.showHero }) { appearance { prefs.showHero = it } }
		b.toggle("Home: Continue watching", isOn = { prefs.rowContinue }) { appearance { prefs.rowContinue = it } }
		b.toggle("Home: Next up", isOn = { prefs.rowNextUp }) { appearance { prefs.rowNextUp = it } }
		b.toggle("Home: Latest per library", isOn = { prefs.rowLatest }) { appearance { prefs.rowLatest = it } }
		b.toggle("Home: Upcoming", isOn = { prefs.rowUpcoming }) { appearance { prefs.rowUpcoming = it } }
		b.toggle("Home: Favorites", isOn = { prefs.rowFavorites }) { appearance { prefs.rowFavorites = it } }
		b.action("Apply appearance now", "Otherwise applied when you leave Settings") { host.restart() }

		// Playback
		b.header("Playback")
		b.stepper("Default player", value = { PlayerMode.fromName(prefs.defaultPlayerMode)?.label ?: PlayerMode.EXO_DIRECT.label }) { d, _ ->
			val modes = PlayerMode.entries
			val current = PlayerMode.fromName(prefs.defaultPlayerMode) ?: PlayerMode.EXO_DIRECT
			prefs.defaultPlayerMode = modes[(current.ordinal + d + modes.size) % modes.size].name
		}
		b.stepper("Stutter detection", "Ask to switch player when playback stutters", value = { STUTTER[prefs.stutterDetection.coerceIn(0, 2)] }) { d, _ ->
			prefs.stutterDetection = (prefs.stutterDetection + d + 3) % 3
		}
		b.toggle("Remember the player that works", "Per video and per show", isOn = { prefs.rememberWorkingPlayer }) {
			prefs.rememberWorkingPlayer = it
		}
		b.toggle("Play next episode automatically", isOn = { prefs.autoPlayNext }) { prefs.autoPlayNext = it }
		b.toggle("Match frame rate", "Switch the TV to 23.976 / 24 / 25 / 50 / 60 Hz", isOn = { prefs.matchFrameRate }) {
			prefs.matchFrameRate = it
		}
		b.toggle("Tunneled playback", "Can smooth 4K HDR on some TVs (ExoPlayer)", isOn = { prefs.tunneling }) { prefs.tunneling = it }
		b.stepper("Seek step", value = { "${prefs.seekStepSec} s" }) { d, _ ->
			val index = (SEEK_STEPS.indexOf(prefs.seekStepSec).takeIf { it >= 0 } ?: 1) + d
			prefs.seekStepSec = SEEK_STEPS[index.coerceIn(0, SEEK_STEPS.lastIndex)]
		}
		b.toggle("Remember sync per video", "Subtitle / audio delay", isOn = { prefs.rememberDelays }) { prefs.rememberDelays = it }
		b.toggle("VLC passthrough", "Dolby / DTS bitstream to the receiver with VLC", isOn = { prefs.vlcPassthrough }) {
			prefs.vlcPassthrough = it
		}
		b.stepper("External-player engine", "When another app (e.g. Jellyfin) opens a video here", value = { prefs.engineMode.label }) { d, _ ->
			val values = Prefs.EngineMode.entries
			prefs.engineMode = values[(prefs.engineMode.ordinal + d + values.size) % values.size]
		}

		// Subtitles
		b.header("Subtitles")
		b.stepper("Show subtitles", value = { SUBTITLE_MODES[prefs.subtitleMode.coerceIn(0, 2)] }) { d, _ ->
			prefs.subtitleMode = (prefs.subtitleMode + d + 3) % 3
		}
		b.stepper("Preferred language", value = { languageLabel(prefs.preferredSubtitleLanguage) }) { d, _ ->
			prefs.preferredSubtitleLanguage = cycleLanguage(prefs.preferredSubtitleLanguage, d)
		}
		b.toggle("libass for ASS / SSA", "Full anime-style subtitle styling", isOn = { prefs.useLibass }) { prefs.useLibass = it }
		b.stepper("Size", value = { "${(prefs.subtitleScale * 100).roundToInt()}%" }) { d, _ ->
			prefs.subtitleScale = ((prefs.subtitleScale * 20).roundToInt() + d).coerceIn(10, 50) / 20f
		}
		b.stepper("Position", value = { "${(prefs.subtitleBottom * 100).roundToInt()}%" }) { d, _ ->
			prefs.subtitleBottom = ((prefs.subtitleBottom * 50).roundToInt() + d).coerceIn(0, 20) / 50f
		}
		b.stepper("Style", value = { SUBTITLE_STYLES[prefs.subtitleStyle.coerceIn(0, 2)] }) { d, _ ->
			prefs.subtitleStyle = (prefs.subtitleStyle + d + 3) % 3
		}
		b.stepper("Colour", value = { if (prefs.subtitleColor == 1) "Yellow" else "White" }) { _, _ ->
			prefs.subtitleColor = if (prefs.subtitleColor == 1) 0 else 1
		}

		// Audio
		b.header("Audio")
		b.stepper("Preferred language", value = { languageLabel(prefs.preferredAudioLanguage) }) { d, _ ->
			prefs.preferredAudioLanguage = cycleLanguage(prefs.preferredAudioLanguage, d)
		}

		// Li'Server
		b.header("Li'Server")
		b.action("Address", null, prefs.liServerUrl) {
			Dialogs.input(host, "Li'Server address", prefs.liServerUrl, Prefs.DEFAULT_LISERVER) { value ->
				if (value.isNotBlank()) prefs.liServerUrl = value
				build()
			}
		}
		b.action("Username", null, prefs.liServerUser ?: "Not saved") {
			Dialogs.input(host, "Li'Server username", prefs.liServerUser, "") { value ->
				prefs.liServerUser = value.ifBlank { null }
				build()
			}
		}
		b.action("Password", "Stored encrypted on this device", if (prefs.liServerPasswordEncrypted != null) "Saved" else "Not saved") {
			Dialogs.input(host, "Li'Server password", "", "", password = true) { value ->
				prefs.liServerPasswordEncrypted = if (value.isBlank()) null else SecureStore.encrypt(value)
				build()
			}
		}
		b.toggle("Sign in automatically", isOn = { prefs.liServerAutoLogin }) { prefs.liServerAutoLogin = it }
		b.stepper("Page zoom", "Make the Li'Server page fit the TV", value = { "${prefs.liServerZoom}%" }) { d, _ ->
			prefs.liServerZoom = (prefs.liServerZoom + d * 10).coerceIn(50, 150)
		}
		b.action("Forget Li'Server login", "Clears saved password and cookies") {
			Dialogs.confirm(host, "Forget Li'Server login?", "You will need to sign in again.", "Forget") {
				prefs.liServerUser = null
				prefs.liServerPasswordEncrypted = null
				CookieManager.getInstance().removeAllCookies(null)
				CookieManager.getInstance().flush()
				build()
				host.toast("Li'Server login cleared")
			}
		}

		// About
		b.header("About")
		b.note(
			"Lolish's Project ${BuildConfig.VERSION_NAME} (${host.packageName})\n" +
				"Also works as the external player for the Jellyfin app: Settings → Playback → Video player → External app."
		)

		if (scrollToAccount) list.post { builder.initialFocus()?.requestFocus() }
	}

	private fun languageLabel(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

	private fun cycleLanguage(current: String, direction: Int): String {
		val index = LANGUAGES.indexOfFirst { it.first == current }.takeIf { it >= 0 } ?: 0
		return LANGUAGES[(index + direction + LANGUAGES.size) % LANGUAGES.size].first
	}

	private companion object {
		val SIZES4 = listOf("Small", "Medium", "Large", "Extra large")
		val SIZES3 = listOf("Small", "Medium", "Large")
		val TEXT_SCALES = listOf(85, 90, 100, 110, 120, 130)
		val SIDEBAR = listOf("Always expanded", "Icons only", "Expand when focused")
		val STUTTER = listOf("Off", "Normal", "Sensitive")
		val SEEK_STEPS = listOf(5, 10, 15, 30)
		val SUBTITLE_MODES = listOf("As Jellyfin / file says", "Always (preferred language)", "Start with subtitles off")
		val SUBTITLE_STYLES = listOf("Outline", "Drop shadow", "Background box")
		val LANGUAGES = listOf(
			"" to "No preference", "en" to "English", "sv" to "Swedish", "bs" to "Bosnian", "hr" to "Croatian",
			"sr" to "Serbian", "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish", "de" to "German",
			"fr" to "French", "es" to "Spanish", "it" to "Italian", "nl" to "Dutch", "pl" to "Polish",
			"pt" to "Portuguese", "ru" to "Russian", "tr" to "Turkish", "ar" to "Arabic", "ja" to "Japanese",
			"ko" to "Korean", "zh" to "Chinese",
		)
	}
}
