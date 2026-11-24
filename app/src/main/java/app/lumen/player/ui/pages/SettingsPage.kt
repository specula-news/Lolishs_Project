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
	private val scroll = app.lumen.player.ui.TvScrollView(host)
	private val list = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(Ui.dp(host, 44), Ui.dp(host, 30), Ui.dp(host, 44), Ui.dp(host, 80))
	}
	override val view: View = scroll
	private val rows = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
	private val rowsBuilder = PanelBuilder(rows)
	private val prefs: Prefs get() = host.prefs
	private var appearanceChanged = false

	init {
		scroll.addView(list)
	}

	override fun onShow() = build()

	override fun onHide() {
		if (appearanceChanged) host.restart()
	}

	override fun focusFirst(): Boolean = sectionPills[current]?.requestFocus() == true

	private fun appearance(change: () -> Unit) {
		change()
		appearanceChanged = true
	}

	/** Sections shown as tabs, so the list stays short and the remote never runs off the end. */
	private enum class Section(val label: String) {
		USERS("Users"), APPEARANCE("Appearance"), PLAYBACK("Playback"), SUBTITLES("Subtitles & audio"), LISERVER("Li'Server"), ABOUT("About")
	}

	private val sectionPills = mutableMapOf<Section, View>()

	private fun build() {
		list.removeAllViews()
		list.addView(host.cards.heading("Settings", null, 40f))

		// Updates first: one press away.
		val updates = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER_VERTICAL
			setPadding(Ui.dp(host, 6), Ui.dp(host, 14), 0, Ui.dp(host, 4))
		}
		updates.addView(android.widget.TextView(host).apply {
			text = "Lolish's Project ${BuildConfig.VERSION_NAME}"
			setTextColor(Ui.color(host, app.lumen.player.R.attr.lumenText2))
			setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
			setPadding(0, 0, Ui.dp(host, 18), 0)
		})
		app.lumen.player.ui.Pills.create(updates, "Check for updates", app.lumen.player.R.drawable.ic_refresh).setOnClickListener { host.checkForUpdates() }
		val auto = app.lumen.player.ui.Pills.create(updates, autoLabel())
		auto.isSelected = prefs.autoUpdateCheck
		auto.setOnClickListener {
			prefs.autoUpdateCheck = !prefs.autoUpdateCheck
			auto.text = autoLabel()
			auto.isSelected = prefs.autoUpdateCheck
		}
		list.addView(updates)

		// Section tabs
		val tabs = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(Ui.dp(host, 6), Ui.dp(host, 16), 0, Ui.dp(host, 10))
		}
		val tabScroll = android.widget.HorizontalScrollView(host).apply {
			isHorizontalScrollBarEnabled = false
			clipToPadding = false
			addView(tabs)
		}
		if (BuildConfig.DEBUG) host.intent.getStringExtra("shot_section")?.let { name ->
			Section.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { current = it }
		}
		sectionPills.clear()
		Section.entries.forEach { s ->
			val pill = app.lumen.player.ui.Pills.create(tabs, s.label)
			pill.isSelected = s == current
			pill.setOnClickListener { showSection(s, focusList = true) }
			sectionPills[s] = pill
		}
		list.addView(tabScroll)
		list.addView(rows)
		showSection(current, focusList = false)
	}

	private fun autoLabel() = if (prefs.autoUpdateCheck) "Auto-check: on" else "Auto-check: off"

	private fun showSection(section: Section, focusList: Boolean) {
		current = section
		sectionPills.forEach { (key, pill) -> pill.isSelected = key == section }
		val b = rowsBuilder
		b.clear()
		when (section) {
			Section.USERS -> buildUsers(b)
			Section.APPEARANCE -> buildAppearance(b)
			Section.PLAYBACK -> buildPlayback(b)
			Section.SUBTITLES -> buildSubtitles(b)
			Section.LISERVER -> buildLiServer(b)
			Section.ABOUT -> buildAbout(b)
		}
		if (prefs.animations) {
			for (i in 0 until minOf(rows.childCount, 8)) {
				val child = rows.getChildAt(i)
				child.alpha = 0f
				child.translationY = Ui.dp(host, 10).toFloat()
				child.animate().alpha(1f).translationY(0f).setStartDelay(i * 25L).setDuration(180L).withLayer().start()
			}
		}
		if (focusList) rowsBuilder.initialFocus()?.requestFocus()
	}

	private fun buildUsers(b: PanelBuilder) {
		val profiles = prefs.accounts
		b.action("Switch user", "Who's watching? · ${profiles.size} saved on this TV", prefs.userName ?: "") { host.switchUser() }
		b.action("Add user", "Sign in another Jellyfin user and keep both") { host.addUser() }
		b.toggle("Ask \"Who's watching?\" at start", "Only when more than one user is saved", isOn = { prefs.askWhoIsWatching }) {
			prefs.askWhoIsWatching = it
		}
		b.action("Remove ${prefs.userName ?: "user"} from this TV", "Signs out for good. To just change user, use Switch user") {
			Dialogs.confirm(
				host,
				"Remove ${prefs.userName ?: "this user"}?",
				"Signs ${prefs.userName ?: "the user"} out on this TV. The name stays on the sign-in screen, so signing in again only needs the password.",
				"Remove",
			) { host.signOut() }
		}
		b.action("Server", prefs.serverName ?: "Jellyfin", JellyfinApi.normalizeBaseUrl(prefs.serverUrl ?: Prefs.DEFAULT_SERVER)) {
			Dialogs.input(host, "Jellyfin server address", prefs.serverUrl, Prefs.DEFAULT_SERVER) { value ->
				if (value.isNotBlank()) host.changeServer(JellyfinApi.normalizeBaseUrl(value))
			}
		}

	}

	private fun buildAppearance(b: PanelBuilder) {
		// Theme and navigation change right away (the screen is rebuilt and you stay here).
		val themeRow = b.stepper("Theme", "Lolish amber · Glass (frosted pane over the artwork) · Li'Server purple · saved per user", value = { app.lumen.player.util.Themes.NAMES[prefs.theme.coerceIn(0, 2)] }) { d, _ ->
			prefs.theme = (prefs.theme + d + 3) % 3
			host.restart(openPage = "settings", focusRow = 0)
		}
		val navRow = b.stepper("Navigation", "Buttons on top, or a sidebar on the left · saved per user", value = { if (prefs.navPosition == 1) "Top" else "Left" }) { _, _ ->
			prefs.navPosition = if (prefs.navPosition == 1) 0 else 1
			host.restart(openPage = "settings", focusRow = 1)
		}
		val pending = host.intent.getIntExtra(app.lumen.player.ui.MainActivity.EXTRA_FOCUS_ROW, -1)
		if (pending >= 0) {
			host.intent.removeExtra(app.lumen.player.ui.MainActivity.EXTRA_FOCUS_ROW)
			val target = if (pending == 1) navRow else themeRow
			target.post { target.requestFocus() }
		}
		b.toggle("Clock", "Top right corner of the menus (not while watching)", isOn = { prefs.showClock }) { appearance { prefs.showClock = it } }
		b.stepper("Previews", "Focused title plays after a few seconds", value = { PREVIEWS[prefs.previewMode.coerceIn(0, 2)] }) { d, _ ->
			prefs.previewMode = (prefs.previewMode + d + 3) % 3
		}
		b.toggle("Preview sound", isOn = { prefs.previewSound }) { prefs.previewSound = it }
		b.stepper("Home rows", "Wide cards like the streaming apps, or posters", value = { if (prefs.homeWideCards) "Wide" else "Posters" }) { _, _ ->
			appearance { prefs.homeWideCards = !prefs.homeWideCards }
		}
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
		b.stepper("Sidebar size", "Compact fits more on screen", value = { SIDEBAR_SIZES[prefs.sidebarSize.coerceIn(0, 2)] }) { d, _ ->
			appearance { prefs.sidebarSize = (prefs.sidebarSize + d).coerceIn(0, 2) }
		}
		b.stepper("Sidebar", value = { SIDEBAR[prefs.sidebarMode.coerceIn(0, 2)] }) { d, _ ->
			appearance { prefs.sidebarMode = (prefs.sidebarMode + d + 3) % 3 }
		}
		b.action("Sidebar items", "Hold OK on any sidebar item to move or hide it", "Reset") {
			Dialogs.confirm(host, "Reset the sidebar?", "Shows every item again in the default order (Settings first).", "Reset") {
				prefs.navOrder = emptyList()
				prefs.navHidden = emptySet()
				host.restart()
			}
		}
		b.toggle("Titles on cards", isOn = { prefs.showCardTitles }) { appearance { prefs.showCardTitles = it } }
		b.toggle("Ratings on posters", isOn = { prefs.showRatings }) { appearance { prefs.showRatings = it } }
		b.toggle("Big featured banner on Home", isOn = { prefs.showHero }) { appearance { prefs.showHero = it } }
		b.toggle("Home: Continue watching", isOn = { prefs.rowContinue }) { appearance { prefs.rowContinue = it } }
		b.toggle("Home: Next up", isOn = { prefs.rowNextUp }) { appearance { prefs.rowNextUp = it } }
		b.toggle("Home: Latest per library", isOn = { prefs.rowLatest }) { appearance { prefs.rowLatest = it } }
		b.toggle("Home: Upcoming", isOn = { prefs.rowUpcoming }) { appearance { prefs.rowUpcoming = it } }
		b.toggle("Home: Favorites", isOn = { prefs.rowFavorites }) { appearance { prefs.rowFavorites = it } }
		b.toggle("Animations", "Page, row and button motion", isOn = { prefs.animations }) { appearance { prefs.animations = it } }
		b.action("Apply appearance now", "Otherwise applied when you leave Settings") { host.restart() }

	}

	private fun buildPlayback(b: PanelBuilder) {
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

	}

	private fun buildSubtitles(b: PanelBuilder) {
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

	}

	private fun buildLiServer(b: PanelBuilder) {
		b.stepper("Open Li'Server as", "TV screen works with the remote; the website has every feature", value = { if (prefs.liServerUseWebsite) "Website" else "TV screen" }) { _, _ ->
			appearance { prefs.liServerUseWebsite = !prefs.liServerUseWebsite }
		}
		b.action("Address", null, prefs.liServerUrl) {
			Dialogs.input(host, "Li'Server address", prefs.liServerUrl, Prefs.DEFAULT_LISERVER) { value ->
				if (value.isNotBlank()) prefs.liServerUrl = value
				showSection(Section.LISERVER, focusList = true)
			}
		}
		b.action("Username", null, prefs.liServerUser ?: "Not saved") {
			Dialogs.input(host, "Li'Server username", prefs.liServerUser, "") { value ->
				prefs.liServerUser = value.ifBlank { null }
				showSection(Section.LISERVER, focusList = true)
			}
		}
		b.action("Password", "Stored encrypted on this device", if (prefs.liServerPasswordEncrypted != null) "Saved" else "Not saved") {
			Dialogs.input(host, "Li'Server password", "", "", password = true) { value ->
				prefs.liServerPasswordEncrypted = if (value.isBlank()) null else SecureStore.encrypt(value)
				showSection(Section.LISERVER, focusList = true)
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
				prefs.liServerCookies = emptyMap()
				prefs.liServerCsrf = null
				CookieManager.getInstance().removeAllCookies(null)
				CookieManager.getInstance().flush()
				showSection(Section.LISERVER, focusList = true)
				host.toast("Li'Server login cleared")
			}
		}

	}

	private fun buildAbout(b: PanelBuilder) {
		b.note(
			"Lolish's Project ${BuildConfig.VERSION_NAME} (${host.packageName})\n" +
				"Also works as the external player for the Jellyfin app: Settings → Playback → Video player → External app."
		)

	}

	private fun languageLabel(code: String): String = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

	private fun cycleLanguage(current: String, direction: Int): String {
		val index = LANGUAGES.indexOfFirst { it.first == current }.takeIf { it >= 0 } ?: 0
		return LANGUAGES[(index + direction + LANGUAGES.size) % LANGUAGES.size].first
	}

	private companion object {
		/** Remembered while the app runs, so Settings reopens where you were (e.g. after "Apply"). */
		var current = Section.USERS
		val SIDEBAR_SIZES = listOf("Compact", "Normal", "Large")
		val PREVIEWS = listOf("Off", "Small window", "Small, then full screen")
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
