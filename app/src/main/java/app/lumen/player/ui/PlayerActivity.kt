package app.lumen.player.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.lumen.player.ExternalSubtitle
import app.lumen.player.IntentParser
import app.lumen.player.PlayRequest
import app.lumen.player.R
import app.lumen.player.engine.EngineListener
import app.lumen.player.engine.ExoEngine
import app.lumen.player.engine.PlayerEngine
import app.lumen.player.engine.PlayerMode
import app.lumen.player.engine.ScaleMode
import app.lumen.player.engine.VlcEngine
import app.lumen.player.jellyfin.ItemMetadata
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.Segment
import app.lumen.player.jellyfin.Session
import app.lumen.player.util.DisplayModeHelper
import app.lumen.player.util.Formatters
import app.lumen.player.util.Images
import app.lumen.player.util.Ui
import app.lumen.player.util.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity(), EngineListener, TimelineView.Listener {

	companion object {
		private const val EXTRA_ITEM_ID = "lolish.item_id"
		private const val EXTRA_START_MS = "lolish.start_ms"
		private const val EXTRA_MODE = "lolish.mode"
		private val SKIPPABLE = setOf("Intro", "Outro", "Recap", "Preview", "Commercial")
		private val SPEEDS = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
		/** "Track 3", "Undefined", "External 1"… names that say nothing about the language. */
		private val GENERIC_TRACK = Regex("(?i)(track|spår|stream|external|subtitle|audio)?\\s*\\d*|undefined|unknown|und|disable")
		/** VLC: "Track 2 - [Swedish]". */
		private val VLC_LANGUAGE = Regex("\\[([^\\]]+)]\\s*$")
		private val SDH = Regex("(?i)\\b(sdh|cc|hi)\\b")
		/** libVLC's own names: "Track 3", "Track 3 - [und]". */
		private val GENERIC_VLC = Regex("(?i)(track|spår)\\s*\\d+(\\s*-\\s*\\[(und|unknown|undefined)?])?")
		private val SUBTITLE_STYLES = listOf("Outline", "Drop shadow", "Background box")
		private val SUBTITLE_INDEX = Regex("/Subtitles/(\\d+)/", RegexOption.IGNORE_CASE)

		/** Plays a Jellyfin item inside the app. [startMs] -1 resumes where you left off. */
		fun play(context: Context, itemId: String, startMs: Long = -1L, mode: PlayerMode? = null) {
			context.startActivity(
				Intent(context, PlayerActivity::class.java)
					.putExtra(EXTRA_ITEM_ID, itemId)
					.putExtra(EXTRA_START_MS, startMs)
					.putExtra(EXTRA_MODE, mode?.name)
			)
		}
	}

	private enum class EngineKind { EXO, VLC }
	private enum class PanelType { SUBTITLES, AUDIO, SYNC, PLAYER, SETTINGS, CHAPTERS }

	private lateinit var prefs: Prefs
	private lateinit var api: JellyfinApi
	private lateinit var request: PlayRequest

	private var engine: PlayerEngine? = null
	private var engineKind = EngineKind.EXO
	private var metadata: ItemMetadata? = null
	private var session: Session? = null
	private var trickplay: TrickplayLoader? = null
	private var subtitlesForEngine: List<ExternalSubtitle> = emptyList()

	private val handler = Handler(Looper.getMainLooper())
	private var subtitleDelayMs = 0L
	private var audioDelayMs = 0L
	private var speed = 1f
	private var scaleMode = ScaleMode.AUTO
	private var completed = false
	private var finishing = false
	private var firstFrameShown = false
	private var lastKnownPositionMs = 0L
	private var lastKnownDurationMs = 0L
	private var tickCount = 0
	/** "Last episode – next airs…" is checked once, about a minute before the end. */
	private var lastEpisodeChecked = false
	private var airCard: View? = null

	private var controlsVisible = false
	/** Seeking with LEFT / RIGHT: only the timeline and times show; OK brings up the buttons. */
	private var seekOnly = false
	private var panelType: PanelType? = null
	private var panelReturnFocus: View? = null
	private var scrubbing = false
	private var scrubTargetMs = 0L
	private var activeSegment: Segment? = null
	private val dismissedSegments = mutableSetOf<Segment>()

	// In-app playback (launched from the library rather than by another app)
	private var internalItemId: String? = null
	private val isInternal: Boolean get() = internalItemId != null
	private var mode = PlayerMode.EXO_DIRECT
	private val triedModes = mutableSetOf<PlayerMode>()
	private var reporter: PlaybackReporter? = null
	private var lastReportedPaused = false
	private val stutter = StutterMonitor()
	private var firstFrameAt = 0L
	private var stutterSuppressedUntil = 0L
	private var stutterOffForItem = false
	private var lastSeekAt = 0L
	/** True between choosing another player and its first frame: keep the menus out of the way. */
	private var switching = false
	private var rememberCandidate: PlayerMode? = null
	private var goodPlaybackMs = 0L
	private var upNextJob: kotlinx.coroutines.Job? = null
	private var upNextCountdown = 0

	// Views
	private lateinit var root: FrameLayout
	private lateinit var videoContainer: FrameLayout
	private lateinit var loading: View
	private lateinit var loadingTitle: TextView
	private lateinit var buffering: View
	private lateinit var centerIcon: ImageView
	private lateinit var statsView: TextView
	private lateinit var topBar: ViewGroup
	private lateinit var bottomBar: ViewGroup
	private lateinit var titleView: TextView
	private lateinit var subtitleView: TextView
	private lateinit var badges: LinearLayout
	private lateinit var clock: TextView
	private lateinit var endsAt: TextView
	private lateinit var elapsed: TextView
	private lateinit var remaining: TextView
	private lateinit var timeline: TimelineView
	private lateinit var btnPlay: ImageButton
	private lateinit var btnChapters: ImageButton
	private lateinit var focusLabel: TextView
	private lateinit var engineLabel: TextView
	private lateinit var preview: View
	private lateinit var previewImage: ImageView
	private lateinit var previewTime: TextView
	private lateinit var previewChapter: TextView
	private lateinit var skipButton: TextView
	private lateinit var toastView: TextView
	private lateinit var panel: ViewGroup
	private lateinit var panelTitle: TextView
	private lateinit var panelScroll: ScrollView
	private lateinit var panelList: LinearLayout
	private lateinit var panelBuilder: PanelBuilder
	private lateinit var errorCard: View
	private lateinit var errorMessage: TextView
	private lateinit var errorActions: LinearLayout
	private lateinit var promptCard: View
	private lateinit var promptTitle: TextView
	private lateinit var promptMessage: TextView
	private lateinit var promptActions: LinearLayout
	private lateinit var promptActions2: LinearLayout
	private lateinit var upNext: View

	private val ticker = object : Runnable {
		override fun run() {
			tick()
			handler.postDelayed(this, 500L)
		}
	}
	private val hideControlsRunnable = Runnable {
		if (engine?.isPlaying == true && panelType == null && !scrubbing) hideControls()
	}
	private val commitScrubRunnable = Runnable { commitScrub() }
	private val hideToastRunnable = Runnable { toastView.animate().alpha(0f).setDuration(250L).start() }

	// Lifecycle -----------------------------------------------------------------------------------

	override fun attachBaseContext(newBase: Context) {
		val scaled = runCatching {
			val scale = Prefs(newBase).textScale.coerceIn(80, 140) / 100f
			if (scale == 1f) return@runCatching newBase
			val config = Configuration(newBase.resources.configuration)
			config.fontScale = config.fontScale * scale
			newBase.createConfigurationContext(config)
		}.getOrDefault(newBase)
		super.attachBaseContext(scaled)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		app.lumen.player.util.Themes.apply(this, Prefs(this), player = true)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_player)
		prefs = Prefs(this)
		scaleMode = ScaleMode.entries.firstOrNull { it.name == prefs.pictureMode } ?: ScaleMode.AUTO
		api = JellyfinApi(prefs)
		hideSystemUi()
		bindViews()
		Images.init(this)

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() = handleBack()
		})

		val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
		if (itemId != null) {
			val current = api.session()
			if (current == null) {
				Toast.makeText(this, "Sign in first", Toast.LENGTH_LONG).show()
				finish()
				return
			}
			session = current
			internalItemId = itemId
			loadingTitle.text = getString(R.string.loading)
			val startMs = intent.getLongExtra(EXTRA_START_MS, -1L)
			val requested = PlayerMode.fromName(intent.getStringExtra(EXTRA_MODE))
			lifecycleScope.launch { startInternal(itemId, startMs, requested) }
			handler.post(ticker)
			return
		}

		val parsed = IntentParser.parse(this, intent)
		if (parsed == null) {
			Toast.makeText(this, "Nothing to play", Toast.LENGTH_LONG).show()
			finish()
			return
		}
		request = parsed
		request.jellyfin?.let { prefs.lastSeenServer = it.baseUrl }
		if (prefs.rememberDelays) {
			val (sub, audio) = prefs.delaysFor(request.itemKey)
			subtitleDelayMs = sub
			audioDelayMs = audio
		}
		titleView.text = request.displayTitle
		loadingTitle.text = request.displayTitle
		updateResult()

		lifecycleScope.launch {
			loadMetadata()
			val kind = if (prefs.engineMode == Prefs.EngineMode.VLC) EngineKind.VLC else EngineKind.EXO
			startEngine(kind, request.startPositionMs)
		}
		handler.post(ticker)
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) hideSystemUi()
	}

	override fun onPause() {
		super.onPause()
		if (::request.isInitialized) {
			updateResult()
			saveDelays()
		}
	}

	override fun onStop() {
		super.onStop()
		if (!isFinishing) engine?.pause()
	}

	override fun onDestroy() {
		handler.removeCallbacksAndMessages(null)
		engine?.listener = null
		engine?.release()
		engine = null
		super.onDestroy()
	}

	private fun hideSystemUi() {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		WindowInsetsControllerCompat(window, window.decorView).apply {
			hide(WindowInsetsCompat.Type.systemBars())
			systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
	}

	private fun bindViews() {
		root = findViewById(R.id.root)
		videoContainer = findViewById(R.id.video_container)
		loading = findViewById(R.id.loading)
		loadingTitle = findViewById(R.id.loading_title)
		buffering = findViewById(R.id.buffering)
		centerIcon = findViewById(R.id.center_icon)
		statsView = findViewById(R.id.stats)
		topBar = findViewById(R.id.top_bar)
		bottomBar = findViewById(R.id.bottom_bar)
		titleView = findViewById(R.id.title)
		subtitleView = findViewById(R.id.subtitle)
		badges = findViewById(R.id.badges)
		clock = findViewById(R.id.clock)
		endsAt = findViewById(R.id.ends_at)
		elapsed = findViewById(R.id.elapsed)
		remaining = findViewById(R.id.remaining)
		timeline = findViewById(R.id.timeline)
		btnPlay = findViewById(R.id.btn_play)
		btnChapters = findViewById(R.id.btn_chapters)
		focusLabel = findViewById(R.id.focus_label)
		engineLabel = findViewById(R.id.engine_label)
		preview = findViewById(R.id.preview)
		previewImage = findViewById(R.id.preview_image)
		previewTime = findViewById(R.id.preview_time)
		previewChapter = findViewById(R.id.preview_chapter)
		skipButton = findViewById(R.id.skip_button)
		toastView = findViewById(R.id.toast)
		panel = findViewById(R.id.panel)
		panelTitle = findViewById(R.id.panel_title)
		panelScroll = findViewById(R.id.panel_scroll)
		panelList = findViewById(R.id.panel_list)
		panelBuilder = PanelBuilder(panelList)
		errorCard = findViewById(R.id.error_card)
		errorMessage = findViewById(R.id.error_message)
		errorActions = findViewById(R.id.error_actions)
		promptCard = findViewById(R.id.prompt_card)
		promptTitle = findViewById(R.id.prompt_title)
		promptMessage = findViewById(R.id.prompt_message)
		promptActions = findViewById(R.id.prompt_actions)
		promptActions2 = findViewById(R.id.prompt_actions_2)
		upNext = findViewById(R.id.up_next)

		topBar.visibility = View.INVISIBLE
		bottomBar.visibility = View.INVISIBLE
		topBar.alpha = 0f
		bottomBar.alpha = 0f

		timeline.listener = this
		btnPlay.setOnClickListener { togglePlayPause() }
		findViewById<View>(R.id.btn_rewind).setOnClickListener { seekBy(-prefs.seekStepSec * 1000L) }
		findViewById<View>(R.id.btn_forward).setOnClickListener { seekBy(prefs.seekStepSec * 1000L) }
		findViewById<View>(R.id.btn_subtitles).setOnClickListener { openPanel(PanelType.SUBTITLES) }
		findViewById<View>(R.id.btn_audio).setOnClickListener { openPanel(PanelType.AUDIO) }
		findViewById<View>(R.id.btn_sync).setOnClickListener { openPanel(PanelType.SYNC) }
		findViewById<View>(R.id.btn_player).setOnClickListener { openPanel(PanelType.PLAYER) }
		findViewById<View>(R.id.btn_settings).setOnClickListener { openPanel(PanelType.SETTINGS) }
		btnChapters.setOnClickListener { openPanel(PanelType.CHAPTERS) }
		findViewById<View>(R.id.btn_info).setOnClickListener {
			prefs.showStats = !prefs.showStats
			updateStats()
		}
		skipButton.setOnClickListener { onSkipClicked() }

		// Show the name of the focused button next to the row, like the Google TV player.
		val buttons = listOf(R.id.btn_play, R.id.btn_rewind, R.id.btn_forward, R.id.btn_subtitles, R.id.btn_audio,
			R.id.btn_sync, R.id.btn_player, R.id.btn_chapters, R.id.btn_settings, R.id.btn_info)
		val buttonSize = Ui.dp(this, Ui.playerButtonDp(prefs))
		buttons.forEach { id ->
			// Play / pause is the main control, so it's a little bigger than the rest.
			val size = if (id == R.id.btn_play) (buttonSize * 1.22f).toInt() else buttonSize
			findViewById<View>(id).layoutParams = findViewById<View>(id).layoutParams.apply {
				width = size
				height = size
			}
			findViewById<View>(id).setOnFocusChangeListener { view, hasFocus ->
				if (hasFocus) focusLabel.text = view.contentDescription
				else if (bottomBar.findFocus() == null) focusLabel.text = ""
				view.animate().scaleX(if (hasFocus) 1.12f else 1f).scaleY(if (hasFocus) 1.12f else 1f).setDuration(120L).start()
			}
		}
		timeline.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focusLabel.text = "" }
	}

	// Metadata from Jellyfin (optional) ----------------------------------------------------------

	private suspend fun loadMetadata() {
		subtitlesForEngine = request.subtitles
		val ref = request.jellyfin ?: return
		val token = when {
			prefs.isLinked -> prefs.accessToken
			!ref.apiKey.isNullOrBlank() -> ref.apiKey
			else -> null
		} ?: return
		val userId = if (prefs.isLinked) prefs.userId.orEmpty() else ""
		val newSession = Session(ref.baseUrl, token, userId)
		session = newSession
		metadata = withTimeoutOrNull(4_000L) {
			runCatching { api.item(newSession, ref.itemId, ref.mediaSourceId) }.getOrNull()
		}
		subtitlesForEngine = buildSubtitleList()
		applyMetadata()
	}

	private fun buildSubtitleList(): List<ExternalSubtitle> {
		val meta = metadata
		val ref = request.jellyfin
		if (request.subtitles.isNotEmpty()) {
			if (meta == null) return request.subtitles
			// Give the subtitles the launching app sent proper names from the server.
			return request.subtitles.map { sub ->
				val index = SUBTITLE_INDEX.find(sub.uri.path ?: "")?.groupValues?.get(1)?.toIntOrNull()
				val info = meta.subtitles.firstOrNull { it.index == index } ?: return@map sub
				sub.copy(name = sub.name ?: info.title, language = sub.language ?: info.language, forced = info.isForced)
			}
		}
		if (meta == null || ref == null) return emptyList()
		val mediaSourceId = meta.mediaSourceId ?: ref.mediaSourceId ?: return emptyList()
		return meta.subtitles.filter { it.isExternal }.mapNotNull { sub ->
			val url = api.subtitleUrl(ref.baseUrl, ref.itemId, mediaSourceId, sub) ?: return@mapNotNull null
			ExternalSubtitle(Uri.parse(url), sub.title, sub.language, enabled = sub.isDefault, forced = sub.isForced)
		}
	}

	private fun applyMetadata() {
		val meta = metadata ?: return
		titleView.text = meta.title
		loadingTitle.text = meta.title
		if (!meta.subtitle.isNullOrBlank()) {
			subtitleView.text = meta.subtitle
			subtitleView.visibility = View.VISIBLE
		}
		timeline.chapterMarksMs = meta.chapters.map { it.startMs }
		timeline.segmentsMs = meta.segments.filter { it.type in SKIPPABLE }.map { it.startMs to it.endMs }
		btnChapters.visibility = if (meta.chapters.size > 1) View.VISIBLE else View.GONE
		val currentSession = session
		val info = meta.trickplay
		if (currentSession != null && info != null) {
			trickplay = TrickplayLoader(api, currentSession, meta.itemId, meta.mediaSourceId, info, lifecycleScope)
			previewImage.layoutParams = previewImage.layoutParams.apply {
				height = (width / (info.width.toFloat() / info.height)).roundToInt()
			}
		}
	}

	// In-app playback: modes (players), switching, up next ------------------------------------

	private suspend fun startInternal(itemId: String, startMsArg: Long, requested: PlayerMode?) {
		val currentSession = session ?: return
		loading.visibility = View.VISIBLE
		errorCard.visibility = View.GONE
		val meta = runCatching { api.item(currentSession, itemId, null) }.getOrElse {
			showFatal("Couldn’t load this video: ${it.message}")
			return
		}
		internalItemId = itemId
		metadata = meta
		lastEpisodeChecked = false
		airCard?.let { (it.parent as? ViewGroup)?.removeView(it) }
		airCard = null
		completed = false
		finishing = false
		dismissedSegments.clear()
		activeSegment = null
		triedModes.clear()
		stutter.reset()
		stutterOffForItem = false
		rememberCandidate = null
		titleView.text = meta.title
		loadingTitle.text = meta.title
		subtitleView.visibility = View.GONE
		applyMetadata()
		if (prefs.rememberDelays) {
			val (sub, audio) = prefs.delaysFor(itemId)
			subtitleDelayMs = sub
			audioDelayMs = audio
		}
		val startMs = if (startMsArg >= 0) startMsArg else meta.resumeMs
		mode = requested
			?: PlayerMode.fromName(prefs.rememberedPlayerMode(itemId, meta.seriesId))
			?: PlayerMode.fromName(prefs.defaultPlayerMode)
			?: PlayerMode.EXO_DIRECT
		startMode(mode, startMs)
	}

	/** Builds the stream for [newMode] (original file or server transcode) and starts the engine. */
	private suspend fun startMode(newMode: PlayerMode, positionMs: Long) {
		val currentSession = session ?: return
		val itemId = internalItemId ?: return
		val meta = metadata ?: return
		mode = newMode
		loading.visibility = View.VISIBLE
		val mediaSourceId = meta.mediaSourceId
		val url: String
		val playSessionId: String
		val method: String
		val subtitles: List<ExternalSubtitle>
		var sourceId = mediaSourceId
		if (newMode.isTranscode) {
			val info = runCatching { api.transcode(currentSession, itemId, mediaSourceId, newMode.bitrate, positionMs) }.getOrElse {
				showError("The server couldn’t transcode this video (${it.message}).", positionMs)
				return
			}
			url = info.url
			playSessionId = info.playSessionId
			sourceId = info.mediaSourceId ?: mediaSourceId
			method = "Transcode"
			// The transcoded stream carries no subtitles, so offer every text track as a file.
			subtitles = meta.subtitles.filter { it.isText }.mapNotNull { sub ->
				val link = api.subtitleUrl(currentSession.baseUrl, itemId, sourceId ?: return@mapNotNull null, sub) ?: return@mapNotNull null
				ExternalSubtitle(Uri.parse(link), sub.title, sub.language, enabled = sub.isDefault || sub.isForced, forced = sub.isForced)
			}
		} else {
			playSessionId = api.newPlaySessionId()
			url = api.directStreamUrl(currentSession, itemId, mediaSourceId, playSessionId)
			method = "DirectPlay"
			subtitles = meta.subtitles.filter { it.isExternal }.mapNotNull { sub ->
				val link = api.subtitleUrl(currentSession.baseUrl, itemId, mediaSourceId ?: return@mapNotNull null, sub) ?: return@mapNotNull null
				ExternalSubtitle(Uri.parse(link), sub.title, sub.language, enabled = sub.isDefault, forced = sub.isForced)
			}
		}
		request = PlayRequest(
			uri = Uri.parse(url),
			title = meta.title,
			startPositionMs = positionMs,
			subtitles = subtitles,
			headers = mapOf("Authorization" to api.authorizationHeader(currentSession.token)),
			fileName = null,
			callerPackage = null,
		)
		subtitlesForEngine = subtitles
		reporter = PlaybackReporter(api, currentSession, itemId, sourceId, playSessionId, method)
		lastReportedPaused = false
		stutter.reset()
		startEngine(if (newMode.engine == PlayerMode.Engine.VLC) EngineKind.VLC else EngineKind.EXO, positionMs)
		engineLabel.text = newMode.label
	}

	/** Switch "player" at the current position. Works from the Player panel and the stutter prompt. */
	private fun switchMode(newMode: PlayerMode, fromPrompt: Boolean = false) {
		val position = currentPositionForResult()
		val previous = mode
		triedModes += previous
		hidePrompt()
		// Clean screen while switching: no panel, no bars, just the loading card.
		closePanel(showBars = false)
		hideControls()
		cancelScrub()
		switching = true
		engine?.pause()
		loadingTitle.text = "Switching to ${newMode.label}…"
		loading.visibility = View.VISIBLE
		stutter.reset()
		stutterSuppressedUntil = SystemClock.elapsedRealtime() + 20_000L
		if (isInternal) {
			val oldSessionId = reporter?.playSessionId
			reporter?.stop(position)
			reporter = null
			if (fromPrompt || newMode != previous) rememberCandidate = newMode
			goodPlaybackMs = 0L
			lifecycleScope.launch {
				// Stop the old server transcode first so the new one gets the server's full attention.
				val currentSession = session
				if (previous.isTranscode && oldSessionId != null && currentSession != null) {
					withTimeoutOrNull(2_500L) { runCatching { api.stopEncoding(currentSession, oldSessionId) } }
				}
				startMode(newMode, position)
			}
		} else {
			mode = newMode
			startEngine(if (newMode.engine == PlayerMode.Engine.VLC) EngineKind.VLC else EngineKind.EXO, position)
			engineLabel.text = newMode.label
		}
	}

	/** Modes that make sense right now: transcoding needs a signed-in Jellyfin item. */
	private fun availableModes(): List<PlayerMode> =
		if (isInternal) PlayerMode.entries else listOf(PlayerMode.EXO_DIRECT, PlayerMode.VLC_DIRECT)

	private fun reportProgress(event: String = "timeupdate") {
		val current = engine ?: return
		reporter?.progress(current.positionMs, !current.isPlaying, event)
	}

	/** End of an episode: offer the next one (Jellyfin-style "Up next"), otherwise close. */
	private fun onPlaybackFinished() {
		if (!isInternal) {
			finishWithResult(completed = true)
			return
		}
		val duration = currentDurationForResult()
		reporter?.stop(if (duration > 0) duration else currentPositionForResult())
		reporter = null
		val meta = metadata
		val currentSession = session
		if (meta == null || currentSession == null || !meta.isEpisode || meta.seriesId == null || !prefs.autoPlayNext) {
			finishWithResult(completed = true)
			return
		}
		lifecycleScope.launch {
			val next = runCatching { api.nextEpisode(currentSession, meta.seriesId, meta.itemId) }.getOrNull()
			if (next == null) finishWithResult(completed = true) else showUpNext(next)
		}
	}

	// Download subtitles ---------------------------------------------------------------------------------

	private fun downloadSubtitles() {
		val last = prefs.subtitleDownloadLanguage ?: "Swedish"
		val languages = app.lumen.player.util.SubtitleFinder.LANGUAGES.sortedBy { if (it.english == last) 0 else 1 }
		val wasPlaying = engine?.isPlaying == true
		engine?.pause()
		android.app.AlertDialog.Builder(this)
			.setTitle("Download subtitles in…")
			.setItems(languages.map { it.english }.toTypedArray()) { _, which ->
				val language = languages[which]
				prefs.subtitleDownloadLanguage = language.english
				searchSubtitles(language, wasPlaying)
			}
			.setNegativeButton("Cancel") { _, _ -> if (wasPlaying) engine?.play() }
			.setOnCancelListener { if (wasPlaying) engine?.play() }
			.show()
	}

	private fun searchSubtitles(language: app.lumen.player.util.SubLanguage, wasPlaying: Boolean) {
		val waiting = android.app.AlertDialog.Builder(this)
			.setTitle("Searching ${language.english} subtitles…")
			.setMessage("OpenSubtitles, Addic7ed and your server")
			.show()
		val job = lifecycleScope.launch {
			val meta = metadata
			val currentSession = session
			val target = (if (meta != null && currentSession != null) runCatching { api.subtitleTarget(currentSession, meta.itemId) }.getOrNull() else null)
				?: app.lumen.player.jellyfin.SubtitleTarget(
					title = meta?.title ?: request.title.orEmpty(), year = null, imdb = null, isEpisode = false,
					seriesName = null, seriesImdb = null, season = null, episode = null,
				)
			val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
				kotlinx.coroutines.coroutineScope {
					val openSubs = async { runCatching { app.lumen.player.util.SubtitleFinder.openSubtitles(target, language) }.getOrDefault(emptyList()) }
					val addic7ed = async { runCatching { app.lumen.player.util.SubtitleFinder.gestdown(target, language) }.getOrDefault(emptyList()) }
					val server = async {
						if (meta == null || currentSession == null) emptyList() else runCatching {
							api.remoteSubtitles(currentSession, meta.itemId, language.iso3).mapNotNull { o ->
								val id = o.optString("Id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
								app.lumen.player.util.OnlineSubtitle(
									source = o.optString("ProviderName").ifBlank { "Server" } + " (server)",
									release = o.optString("Name"),
									language = language.english,
									format = o.optString("Format").lowercase(),
									downloads = o.optInt("DownloadCount"),
									hearingImpaired = o.optBoolean("HearingImpaired"),
									url = null,
									serverId = id,
								)
							}
						}.getOrDefault(emptyList())
					}
					openSubs.await().take(40) + addic7ed.await().take(20) + server.await().take(20)
				}
			}
			waiting.dismiss()
			if (results.isEmpty()) {
				showToast("No ${language.english} subtitles found for this video")
				if (wasPlaying) engine?.play()
				return@launch
			}
			showSubtitleResults(language, results, wasPlaying)
		}
		waiting.setOnCancelListener {
			job.cancel()
			if (wasPlaying) engine?.play()
		}
	}

	private fun showSubtitleResults(language: app.lumen.player.util.SubLanguage, results: List<app.lumen.player.util.OnlineSubtitle>, wasPlaying: Boolean) {
		val labels = results.map { r ->
			val facts = listOfNotNull(
				r.source,
				r.format.uppercase().ifBlank { null },
				if (r.downloads > 0) "${r.downloads} downloads" else null,
				if (r.hearingImpaired) "hearing impaired" else null,
			).joinToString("  ·  ")
			"${r.release.ifBlank { "(no name)" }}\n$facts"
		}
		android.app.AlertDialog.Builder(this)
			.setTitle("${language.english} subtitles (${results.size})")
			.setItems(labels.toTypedArray()) { _, which -> fetchSubtitle(language, results[which], wasPlaying) }
			.setNegativeButton("Cancel") { _, _ -> if (wasPlaying) engine?.play() }
			.setOnCancelListener { if (wasPlaying) engine?.play() }
			.show()
	}

	/** Downloads the pick, adds it to the subtitles and turns it on (the player restarts where it was). */
	private fun fetchSubtitle(language: app.lumen.player.util.SubLanguage, choice: app.lumen.player.util.OnlineSubtitle, wasPlaying: Boolean) {
		showToast("Downloading subtitles…")
		lifecycleScope.launch {
			val position = engine?.positionMs?.takeIf { it > 0 } ?: lastKnownPositionMs
			try {
				val sub = if (choice.serverId != null) serverSubtitle(language, choice.serverId) else {
					val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.lumen.player.util.SubtitleFinder.download(choice, language) }
					val dir = java.io.File(cacheDir, "subs").apply { mkdirs() }
					// Old downloads (over a week) are cleaned up.
					dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7L * 86_400_000L }?.forEach { it.delete() }
					val name = (metadata?.itemId ?: "video").take(32)
					val file = java.io.File(dir, "$name-${language.os}-${System.currentTimeMillis()}.${choice.format.ifBlank { "srt" }}")
					kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.writeText(text, Charsets.UTF_8) }
					ExternalSubtitle(Uri.fromFile(file), "${language.english} · ${choice.source}", language.iso3, enabled = true)
				}
				subtitlesForEngine = subtitlesForEngine.map { it.copy(enabled = false) } + sub
				startEngine(engineKind, position)
				showToast("${language.english} subtitles on")
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				showToast("Couldn’t get the subtitles: ${e.message ?: e.javaClass.simpleName}")
				if (wasPlaying) engine?.play()
			}
		}
	}

	/** The server's own subtitle plugins: the server saves the file next to the video, then we play it from there. */
	private suspend fun serverSubtitle(language: app.lumen.player.util.SubLanguage, subtitleId: String): ExternalSubtitle {
		val meta = metadata ?: throw java.io.IOException("Nothing playing")
		val currentSession = session ?: throw java.io.IOException("Not signed in")
		val before = meta.subtitles.map { it.index }.toSet()
		api.downloadRemoteSubtitle(currentSession, meta.itemId, subtitleId)
		repeat(8) {
			kotlinx.coroutines.delay(1_500L)
			val fresh = runCatching { api.item(currentSession, meta.itemId, meta.mediaSourceId) }.getOrNull() ?: return@repeat
			val added = fresh.subtitles.firstOrNull { it.isExternal && it.index !in before } ?: return@repeat
			val sourceId = fresh.mediaSourceId ?: meta.mediaSourceId ?: return@repeat
			val url = api.subtitleUrl(currentSession.baseUrl, meta.itemId, sourceId, added) ?: return@repeat
			metadata = fresh
			return ExternalSubtitle(Uri.parse(url), added.title.ifBlank { "${language.english} · server" }, added.language ?: language.iso3, enabled = true)
		}
		throw java.io.IOException("the server saved it, it shows up the next time you play this")
	}

	// Last episode: when the next one airs ------------------------------------------------------------

	/** About a minute before the end of the newest episode you have: when does the next one air? */
	private fun checkLastEpisode() {
		lastEpisodeChecked = true
		val meta = metadata ?: return
		val currentSession = session ?: return
		val seriesId = meta.seriesId ?: return
		if (!meta.isEpisode) return
		lifecycleScope.launch {
			val next = runCatching { api.nextEpisode(currentSession, seriesId, meta.itemId) }.getOrNull()
			// There's a next episode to watch: "Up next" takes care of it at the end.
			if (next != null && !next.locationType.equals("Virtual", ignoreCase = true)) return@launch
			val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
				val ids = runCatching { api.seriesIds(currentSession, seriesId) }.getOrNull()
				val maze = runCatching { app.lumen.player.util.TvMaze.next(ids?.first, ids?.second) }.getOrNull()
				val virtualAt = next?.let { Formatters.epochMs(it.premiereDate) }?.takeIf { it > System.currentTimeMillis() - 86_400_000L }
				when {
					maze?.airsAtMs != null -> maze
					next != null && virtualAt != null -> app.lumen.player.util.NextAiring(virtualAt, next.parentIndex, next.index, next.name, false)
					else -> app.lumen.player.util.NextAiring(null, null, null, null, ended = maze?.ended ?: ids?.third.equals("Ended", ignoreCase = true))
				}
			}
			if (!isFinishing && upNext.visibility != View.VISIBLE) showAirCard(info)
		}
	}

	private fun showAirCard(info: app.lumen.player.util.NextAiring) {
		val root = upNext.parent as? FrameLayout ?: return
		airCard?.let { root.removeView(it) }
		val headline = when {
			info.airsAtMs != null -> "Next episode airs ${Formatters.airDate(info.airsAtMs)}"
			info.ended -> "That was the final episode"
			else -> "You’re all caught up"
		}
		val detail = when {
			info.airsAtMs != null -> listOfNotNull(
				if (info.season != null && info.episode != null) "S${info.season} · E${info.episode}" else null,
				info.name,
			).joinToString("  ·  ").ifBlank { null }
			info.ended -> "The series has ended."
			else -> "No date for the next episode yet."
		}
		val card = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_hud)
			setPadding(Ui.dp(context, 22), Ui.dp(context, 14), Ui.dp(context, 24), Ui.dp(context, 16))
			elevation = Ui.dp(context, 12).toFloat()
			alpha = 0f
			translationX = Ui.dp(context, 40).toFloat()
		}
		card.addView(TextView(this).apply {
			text = "LAST EPISODE"
			letterSpacing = 0.16f
			typeface = Ui.bold(context)
			setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
			setTextColor(Ui.color(this@PlayerActivity, R.attr.lumenAccent))
		})
		card.addView(TextView(this).apply {
			text = headline
			typeface = Ui.bold(context)
			setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
			setTextColor(Ui.color(this@PlayerActivity, R.attr.lumenText))
			setPadding(0, Ui.dp(context, 4), 0, 0)
		})
		detail?.let { line ->
			card.addView(TextView(this).apply {
				text = line
				setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
				setTextColor(Ui.color(this@PlayerActivity, R.attr.lumenText2))
				setPadding(0, Ui.dp(context, 3), 0, 0)
			})
		}
		root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END).apply {
			setMargins(0, Ui.dp(this@PlayerActivity, 36), Ui.dp(this@PlayerActivity, 40), 0)
		})
		airCard = card
		card.animate().alpha(1f).translationX(0f).setDuration(450L).start()
		card.postDelayed({
			card.animate().alpha(0f).translationX(Ui.dp(this, 40).toFloat()).setDuration(400L).withEndAction {
				root.removeView(card)
				if (airCard === card) airCard = null
			}.start()
		}, 20_000L)
	}

	private fun showUpNext(next: app.lumen.player.jellyfin.JfItem) {
		val currentSession = session ?: return
		hideControls()
		upNext.visibility = View.VISIBLE
		findViewById<TextView>(R.id.up_next_title).text = listOfNotNull(next.episodeCode, next.name).joinToString("  —  ")
		Images.load(findViewById(R.id.up_next_image), api.thumbUrl(currentSession, next, 480))
		val actionsRow = findViewById<LinearLayout>(R.id.up_next_actions)
		actionsRow.removeAllViews()
		val playNow = Pills.create(actionsRow, "Play now", R.drawable.ic_play)
		playNow.setOnClickListener { playNext(next.id) }
		Pills.create(actionsRow, "Close").setOnClickListener { finishWithResult(completed = true) }
		playNow.requestFocus()
		upNextCountdown = 10
		upNextJob?.cancel()
		upNextJob = lifecycleScope.launch {
			while (upNextCountdown > 0) {
				playNow.text = "Play now ($upNextCountdown)"
				kotlinx.coroutines.delay(1_000L)
				upNextCountdown--
			}
			playNext(next.id)
		}
	}

	private fun playNext(itemId: String) {
		upNextJob?.cancel()
		upNext.visibility = View.GONE
		saveDelays()
		lifecycleScope.launch { startInternal(itemId, -1L, null) }
	}

	private fun showFatal(message: String) {
		loading.visibility = View.GONE
		errorMessage.text = message
		errorActions.removeAllViews()
		addErrorAction("Close") { finishWithResult(completed = false) }.requestFocus()
		errorCard.visibility = View.VISIBLE
	}

	// Stutter detection ------------------------------------------------------------------------

	override fun onEngineRebuffer() {
		val now = SystemClock.elapsedRealtime()
		if (!firstFrameShown || scrubbing || now - lastSeekAt < 4_000L) return
		stutter.rebuffer(now)
		checkStutter()
	}

	/** Dropped frames are sampled from the engine's counters in [tick] instead (works for VLC too). */
	override fun onEngineDroppedFrames(count: Int, elapsedMs: Long) = Unit

	/** Called once a second while playing. */
	private fun sampleStutter(current: PlayerEngine) {
		val now = SystemClock.elapsedRealtime()
		val settled = firstFrameShown && !switching && !scrubbing && now - lastSeekAt > 4_000L
		stutter.sample(now, current.frameCounters(), settled && current.isBuffering && current.isPlaying)
		if (settled && now - firstFrameAt > 8_000L) checkStutter()
	}

	/** Every seek goes through here so stutter detection ignores the catch-up right after it. */
	private fun seekEngine(target: Long) {
		lastSeekAt = SystemClock.elapsedRealtime()
		stutter.resetFrames()
		engine?.seekTo(target)
	}

	private fun checkStutter() {
		val now = SystemClock.elapsedRealtime()
		if (prefs.stutterDetection == 0 || stutterOffForItem || promptCard.visibility == View.VISIBLE) return
		if (now < stutterSuppressedUntil || panelType != null || errorCard.visibility == View.VISIBLE) return
		val kind = stutter.check(now, prefs.stutterDetection) ?: return
		stutter.reset()
		rememberCandidate = null
		goodPlaybackMs = 0L
		showStutterPrompt(kind)
	}

	private fun showStutterPrompt(kind: StutterMonitor.Kind) {
		val network = kind == StutterMonitor.Kind.NETWORK
		val suggestions = PlayerMode.suggestions(mode, network, triedModes + mode, canTranscode = isInternal)
		if (controlsVisible) hideControls()
		promptTitle.text = "Playback is stuttering"
		promptMessage.text = if (network) {
			"The video keeps stopping to buffer — the connection may be too slow for this file. Try another player:"
		} else {
			"Frames are being dropped — the decoder is struggling with this file. Try another player:"
		} + if (suggestions.isEmpty()) "\nYou’ve tried every player for this video. The lowest bitrate is usually the safest." else ""
		promptActions.removeAllViews()
		promptActions2.removeAllViews()
		val choices = suggestions.ifEmpty { listOf(PlayerMode.EXO_4).filter { isInternal && it != mode } }
		choices.forEach { option ->
			Pills.create(promptActions, option.label, R.drawable.ic_pl_switch).setOnClickListener { switchMode(option, fromPrompt = true) }
		}
		Pills.create(promptActions2, "Keep playing").setOnClickListener {
			hidePrompt()
			stutterSuppressedUntil = SystemClock.elapsedRealtime() + 120_000L
		}
		Pills.create(promptActions2, "Don’t ask for this video").setOnClickListener {
			hidePrompt()
			stutterOffForItem = true
		}
		Pills.create(promptActions2, "All players…").setOnClickListener {
			hidePrompt()
			openPanel(PanelType.PLAYER)
		}
		promptCard.visibility = View.VISIBLE
		(promptActions.getChildAt(0) ?: promptActions2.getChildAt(0))?.requestFocus()
	}

	private fun hidePrompt() {
		if (promptCard.visibility == View.VISIBLE) promptCard.visibility = View.GONE
	}

	// Engine ------------------------------------------------------------------------------------

	private fun startEngine(kind: EngineKind, startPositionMs: Long) {
		engine?.let {
			it.listener = null
			it.release()
		}
		engine = null
		videoContainer.removeAllViews()
		errorCard.visibility = View.GONE
		loading.visibility = View.VISIBLE
		firstFrameShown = false
		engineKind = kind
		if (!isInternal) mode = if (kind == EngineKind.VLC) PlayerMode.VLC_DIRECT else PlayerMode.EXO_DIRECT

		val newEngine = try {
			when (kind) {
				EngineKind.EXO -> ExoEngine(this, prefs, request.headers)
				EngineKind.VLC -> VlcEngine(this, prefs)
			}
		} catch (t: Throwable) {
			showError("Couldn't start the ${if (kind == EngineKind.EXO) "ExoPlayer" else "VLC"} engine: ${t.message}", startPositionMs)
			return
		}
		engine = newEngine
		engineLabel.text = newEngine.name
		newEngine.listener = this
		newEngine.attach(videoContainer)
		newEngine.setScaleMode(scaleMode)
		newEngine.load(request, subtitlesForEngine, startPositionMs)
		applySyncAndSpeed(newEngine)
	}

	private fun applySyncAndSpeed(target: PlayerEngine) {
		target.setSubtitleDelay(subtitleDelayMs)
		target.setAudioDelay(audioDelayMs)
		if (speed != 1f) target.setSpeed(speed)
	}

	override fun onEngineStateChanged() {
		val current = engine ?: return
		val playing = current.isPlaying
		if (reporter?.started == true && lastReportedPaused == playing) {
			lastReportedPaused = !playing
			reportProgress(if (playing) "unpause" else "pause")
		}
		btnPlay.setImageResource(if (playing) R.drawable.ic_pl_pause else R.drawable.ic_pl_play)
		btnPlay.contentDescription = getString(if (playing) R.string.pause else R.string.play)
		if (btnPlay.isFocused) focusLabel.text = btnPlay.contentDescription
		if (playing && !current.isBuffering && loading.visibility == View.VISIBLE && !firstFrameShown) {
			// Audio-only streams never render a video frame.
			loading.visibility = View.GONE
		}
		if (playing) bumpHideTimer() else handler.removeCallbacks(hideControlsRunnable)
	}

	override fun onEngineTracksChanged() {
		refreshBadges()
	}

	override fun onEngineEnded() {
		handler.post { onPlaybackFinished() }
	}

	override fun onEngineError(message: String, canFallback: Boolean) {
		// Handle outside the engine's listener callback, since we may release that engine.
		val source = engine
		handler.post { if (engine === source) handleEngineError(message, canFallback) }
	}

	private fun handleEngineError(message: String, canFallback: Boolean) {
		val position = engine?.positionMs?.takeIf { it > 0 }
			?: lastKnownPositionMs.takeIf { it > 0 }
			?: request.startPositionMs
		if (isInternal) {
			if (canFallback && mode.engine == PlayerMode.Engine.EXO && PlayerMode.VLC_DIRECT !in triedModes && !mode.isTranscode) {
				switchMode(PlayerMode.VLC_DIRECT)
				return
			}
			showError(message, position)
			return
		}
		if (canFallback && engineKind == EngineKind.EXO && prefs.engineMode == Prefs.EngineMode.AUTO) {
			startEngine(EngineKind.VLC, position)
			showToast("Switched to the VLC engine")
			return
		}
		showError(message, position)
	}

	override fun onEngineVideoFrameRate(fps: Float) {
		if (prefs.matchFrameRate) DisplayModeHelper.matchFrameRate(this, fps)
	}

	override fun onEngineFirstFrame() {
		if (firstFrameShown) return
		firstFrameShown = true
		firstFrameAt = SystemClock.elapsedRealtime()
		engine?.let { reporter?.start(it.positionMs) }
		loading.visibility = View.GONE
		metadata?.let { loadingTitle.text = it.title }
		engine?.let { applySyncAndSpeed(it) }
		refreshBadges()
		if (switching) {
			// Don't throw the controls (and focus) at the user after a switch.
			switching = false
			showToast("Now playing with ${mode.label}")
		} else {
			showControls(timeline)
		}
	}

	// Ticker ----------------------------------------------------------------------------------------

	private fun tick() {
		tickCount++
		val current = engine ?: return
		val position = current.positionMs
		val duration = current.durationMs
		if (position > 0) lastKnownPositionMs = position
		if (duration > 0) lastKnownDurationMs = duration

		if (firstFrameShown) {
			// While a new player is still opening it reports 0, which would make the bar jump.
			timeline.durationMs = duration
			timeline.positionMs = position
			timeline.bufferedMs = current.bufferedMs
		}
		elapsed.text = Formatters.time(position)
		remaining.text = if (duration > 0) "−" + Formatters.time(duration - position) else ""
		buffering.visibility =
			if (current.isBuffering && firstFrameShown && !scrubbing && errorCard.visibility != View.VISIBLE) View.VISIBLE else View.GONE

		if (controlsVisible) updateClock(position, duration)
		checkSegments(position)
		if (!lastEpisodeChecked && firstFrameShown && duration > 180_000L && position > 0 && duration - position in 1..70_000L) checkLastEpisode()
		if (tickCount % 2 == 0) sampleStutter(current)
		if (statsView.visibility == View.VISIBLE && tickCount % 2 == 0) updateStats()
		if (tickCount % 10 == 0) updateResult()
		if (isInternal) {
			if (reporter?.started == false && current.isPlaying) reporter?.start(position)
			if (tickCount % 20 == 0) reportProgress()
			val candidate = rememberCandidate
			if (candidate != null && current.isPlaying && prefs.rememberWorkingPlayer) {
				goodPlaybackMs += 500L
				if (goodPlaybackMs >= 180_000L) {
					val meta = metadata
					prefs.rememberPlayerMode(candidate.name, internalItemId, meta?.seriesId)
					rememberCandidate = null
					showToast("${candidate.label} works — I’ll use it for ${if (meta?.isEpisode == true) "this show" else "this video"}")
				}
			}
		}
	}

	private fun updateClock(position: Long, duration: Long) {
		val now = System.currentTimeMillis()
		clock.text = DateFormat.getTimeFormat(this).format(Date(now))
		if (duration > 0) {
			val left = ((duration - position) / speed).toLong().coerceAtLeast(0L)
			endsAt.text = "Ends at " + DateFormat.getTimeFormat(this).format(Date(now + left))
		} else {
			endsAt.text = ""
		}
	}

	// Skip intro / credits --------------------------------------------------------------------

	private fun checkSegments(position: Long) {
		val meta = metadata ?: return
		if (panelType != null || scrubbing) {
			// Re-evaluate once the panel closes / the seek lands.
			activeSegment = null
			hideSkip()
			return
		}
		val segment = meta.segments.firstOrNull {
			it.type in SKIPPABLE && position >= it.startMs && position < it.endMs - 1_000L
		}
		if (segment == activeSegment) return
		activeSegment = segment
		if (segment == null || segment in dismissedSegments) {
			hideSkip()
		} else {
			showSkip(segment)
		}
	}

	private fun segmentRunsToEnd(segment: Segment): Boolean {
		val duration = engine?.durationMs?.takeIf { it > 0 } ?: metadata?.runtimeMs ?: return false
		return segment.endMs >= duration - 20_000L
	}

	private fun showSkip(segment: Segment) {
		skipButton.text = when (segment.type) {
			"Intro" -> "Skip intro"
			"Recap" -> "Skip recap"
			"Preview" -> "Skip preview"
			"Commercial" -> "Skip ad"
			else -> if (segmentRunsToEnd(segment) && metadata?.isEpisode == true) "Next episode" else "Skip credits"
		}
		if (skipButton.visibility != View.VISIBLE) {
			skipButton.alpha = 0f
			skipButton.visibility = View.VISIBLE
			skipButton.animate().alpha(1f).setDuration(200L).start()
		}
		if (!controlsVisible && panelType == null) skipButton.requestFocus()
	}

	private fun hideSkip() {
		if (skipButton.visibility == View.VISIBLE) skipButton.visibility = View.GONE
	}

	private fun onSkipClicked() {
		val segment = activeSegment ?: return
		dismissedSegments += segment
		hideSkip()
		if (segment.type == "Outro" && segmentRunsToEnd(segment)) {
			if (isInternal) onPlaybackFinished() else finishWithResult(completed = true)
		} else {
			seekEngine(segment.endMs)
		}
	}

	// Keys --------------------------------------------------------------------------------------

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN) bumpHideTimer()
		val code = event.keyCode

		if (errorCard.visibility == View.VISIBLE || promptCard.visibility == View.VISIBLE || upNext.visibility == View.VISIBLE) {
			return super.dispatchKeyEvent(event)
		}

		if (panelType != null) {
			// Keep focus inside the panel: swallow LEFT/RIGHT that no row consumed.
			val handled = super.dispatchKeyEvent(event)
			if (!handled && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) return true
			return handled
		}

		if (event.action == KeyEvent.ACTION_DOWN) {
			when (code) {
				KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE -> {
					togglePlayPause()
					return true
				}
				KeyEvent.KEYCODE_MEDIA_PLAY -> {
					engine?.play()
					flashCenter(R.drawable.ic_pl_play)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_PAUSE -> {
					engine?.pause()
					flashCenter(R.drawable.ic_pl_pause)
					showControls(btnPlay)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
					if (!controlsVisible || seekOnly) showSeekBar()
					seekBy(30_000L)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_REWIND -> {
					if (!controlsVisible || seekOnly) showSeekBar()
					seekBy(-30_000L)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_NEXT -> {
					jumpChapter(1)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
					jumpChapter(-1)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_STOP -> {
					finishWithResult(completed = false)
					return true
				}
				KeyEvent.KEYCODE_CAPTIONS -> {
					openPanel(PanelType.SUBTITLES)
					return true
				}
				KeyEvent.KEYCODE_MENU -> {
					openPanel(PanelType.SETTINGS)
					return true
				}
				KeyEvent.KEYCODE_INFO -> {
					prefs.showStats = !prefs.showStats
					updateStats()
					return true
				}
			}

			if (controlsVisible && seekOnly) {
				// Only the timeline is up: OK / UP / DOWN bring up the buttons (OK also keeps the new spot).
				when (code) {
					KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
						if (scrubbing) commitScrub()
						showControls(btnPlay)
						return true
					}
					KeyEvent.KEYCODE_DPAD_UP -> {
						showControls(timeline)
						return true
					}
					KeyEvent.KEYCODE_DPAD_DOWN -> {
						showControls(btnPlay)
						return true
					}
				}
			}

			if (!controlsVisible) {
				when (code) {
					KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
						if (skipButton.visibility == View.VISIBLE && skipButton.isFocused) return super.dispatchKeyEvent(event)
						togglePlayPause()
						return true
					}
					KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
						// Just the timeline and the times, so you see the picture while you seek.
						showSeekBar()
						onScrub(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1, event.repeatCount)
						return true
					}
					KeyEvent.KEYCODE_DPAD_UP -> {
						showControls(timeline)
						return true
					}
					KeyEvent.KEYCODE_DPAD_DOWN -> {
						showControls(btnPlay)
						return true
					}
				}
			}
		}
		return super.dispatchKeyEvent(event)
	}

	private fun handleBack() {
		when {
			upNext.visibility == View.VISIBLE -> finishWithResult(completed = true)
			promptCard.visibility == View.VISIBLE -> {
				hidePrompt()
				stutterSuppressedUntil = SystemClock.elapsedRealtime() + 120_000L
			}
			errorCard.visibility == View.VISIBLE -> finishWithResult(completed = false)
			panelType != null -> closePanel()
			scrubbing -> cancelScrub()
			controlsVisible -> hideControls()
			skipButton.visibility == View.VISIBLE && skipButton.isFocused -> {
				activeSegment?.let { dismissedSegments += it }
				hideSkip()
			}
			else -> finishWithResult(completed = false)
		}
	}

	// Controls ----------------------------------------------------------------------------------

	/** The rows under the timeline (the buttons). */
	private val buttonRows: List<View>
		get() = (1 until bottomBar.childCount).map { bottomBar.getChildAt(it) }

	/** Seeking: only the timeline and the times, no buttons and no title bar. */
	private fun showSeekBar() {
		if (controlsVisible && !seekOnly) {
			timeline.requestFocus()
			bumpHideTimer()
			return
		}
		seekOnly = true
		buttonRows.forEach { it.visibility = View.GONE }
		if (!controlsVisible) {
			controlsVisible = true
			bottomBar.animate().cancel()
			bottomBar.visibility = View.VISIBLE
			bottomBar.animate().alpha(1f).setDuration(150L).start()
			engine?.let { updateClock(it.positionMs, it.durationMs) }
		}
		timeline.requestFocus()
		bumpHideTimer()
	}

	private fun showControls(focus: View? = null) {
		if (seekOnly) {
			// From "only the timeline" to the full controls.
			seekOnly = false
			buttonRows.forEach { it.visibility = View.VISIBLE }
			topBar.animate().cancel()
			topBar.visibility = View.VISIBLE
			topBar.animate().alpha(1f).setDuration(180L).start()
			refreshBadges()
		} else {
			buttonRows.forEach { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }
		}
		if (!controlsVisible) {
			controlsVisible = true
			listOf(topBar, bottomBar).forEach { bar ->
				bar.animate().cancel()
				bar.visibility = View.VISIBLE
				bar.animate().alpha(1f).setDuration(180L).start()
			}
			engine?.let { updateClock(it.positionMs, it.durationMs) }
			refreshBadges()
		}
		when {
			focus != null -> focus.requestFocus()
			bottomBar.findFocus() == null -> timeline.requestFocus()
		}
		bumpHideTimer()
	}

	private fun hideControls() {
		if (!controlsVisible) return
		controlsVisible = false
		seekOnly = false
		cancelScrub()
		listOf(topBar, bottomBar).forEach { bar ->
			bar.animate().cancel()
			bar.animate().alpha(0f).setDuration(220L).withEndAction {
				if (!controlsVisible) bar.visibility = View.INVISIBLE
			}.start()
		}
		if (skipButton.visibility == View.VISIBLE) skipButton.requestFocus()
	}

	private fun bumpHideTimer() {
		handler.removeCallbacks(hideControlsRunnable)
		if (controlsVisible) handler.postDelayed(hideControlsRunnable, 5_000L)
	}

	private fun togglePlayPause() {
		val current = engine ?: return
		if (current.isPlaying) {
			current.pause()
			flashCenter(R.drawable.ic_pl_pause)
			showControls(btnPlay)
		} else {
			current.play()
			flashCenter(R.drawable.ic_pl_play)
			bumpHideTimer()
		}
	}

	private fun flashCenter(icon: Int) {
		centerIcon.setImageResource(icon)
		centerIcon.animate().cancel()
		centerIcon.alpha = 1f
		centerIcon.scaleX = 0.9f
		centerIcon.scaleY = 0.9f
		centerIcon.animate().alpha(0f).scaleX(1.2f).scaleY(1.2f).setStartDelay(250L).setDuration(450L).start()
	}

	private fun seekBy(deltaMs: Long) {
		val current = engine ?: return
		val duration = current.durationMs
		var target = current.positionMs + deltaMs
		target = if (duration > 0) target.coerceIn(0L, duration - 1_000L) else target.coerceAtLeast(0L)
		seekEngine(target)
		timeline.positionMs = target
		if (!controlsVisible) showControls(timeline)
	}

	private fun jumpChapter(direction: Int) {
		val chapters = metadata?.chapters.orEmpty()
		val current = engine ?: return
		val position = current.positionMs
		if (chapters.size < 2) {
			seekBy(direction * 60_000L)
			return
		}
		val target = if (direction > 0) {
			chapters.firstOrNull { it.startMs > position + 1_000L }?.startMs
		} else {
			chapters.lastOrNull { it.startMs < position - 3_000L }?.startMs ?: 0L
		} ?: return
		seekEngine(target)
		showControls(timeline)
	}

	// Scrubbing (TimelineView.Listener) ----------------------------------------------------------

	override fun onScrub(direction: Int, repeatCount: Int) {
		val current = engine ?: return
		val duration = current.durationMs
		if (duration <= 0) return
		if (!scrubbing) {
			scrubbing = true
			scrubTargetMs = current.positionMs
			trickplay?.reset()
		}
		val base = prefs.seekStepSec * 1000L
		val step = when {
			repeatCount < 4 -> base
			repeatCount < 12 -> base * 3
			repeatCount < 30 -> base * 6
			else -> base * 12
		}
		scrubTargetMs = (scrubTargetMs + direction * step).coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
		timeline.scrubPositionMs = scrubTargetMs
		showPreview(scrubTargetMs)
		handler.removeCallbacks(commitScrubRunnable)
		handler.postDelayed(commitScrubRunnable, 1_100L)
	}

	override fun onScrubConfirm() {
		if (seekOnly) {
			if (scrubbing) commitScrub()
			showControls(btnPlay)
			return
		}
		if (scrubbing) commitScrub() else togglePlayPause()
	}

	private fun commitScrub() {
		if (!scrubbing) return
		handler.removeCallbacks(commitScrubRunnable)
		scrubbing = false
		seekEngine(scrubTargetMs)
		timeline.positionMs = scrubTargetMs
		timeline.scrubPositionMs = null
		handler.postDelayed({ reportProgress() }, 1_500L)
		preview.visibility = View.GONE
		bumpHideTimer()
	}

	private fun cancelScrub() {
		handler.removeCallbacks(commitScrubRunnable)
		if (!scrubbing) return
		scrubbing = false
		timeline.scrubPositionMs = null
		preview.visibility = View.GONE
	}

	private fun showPreview(positionMs: Long) {
		preview.visibility = View.VISIBLE
		previewTime.text = Formatters.time(positionMs)
		val chapter = metadata?.chapters?.lastOrNull { it.startMs <= positionMs }?.name
		if (!chapter.isNullOrBlank() && (metadata?.chapters?.size ?: 0) > 1) {
			previewChapter.text = chapter
			previewChapter.visibility = View.VISIBLE
		} else {
			previewChapter.visibility = View.GONE
		}
		val loader = trickplay
		if (loader != null) {
			previewImage.visibility = View.VISIBLE
			loader.load(positionMs) { bitmap ->
				if (scrubbing && bitmap != null) previewImage.setImageBitmap(bitmap)
			}
		} else {
			previewImage.visibility = View.GONE
		}
		positionPreview(positionMs)
	}

	private fun positionPreview(positionMs: Long) {
		if (preview.width == 0) {
			preview.post { positionPreview(positionMs) }
			return
		}
		val timelineLocation = IntArray(2)
		val rootLocation = IntArray(2)
		timeline.getLocationInWindow(timelineLocation)
		root.getLocationInWindow(rootLocation)
		val center = timelineLocation[0] - rootLocation[0] + timeline.xFor(positionMs)
		val margin = resources.getDimension(R.dimen.overscan_h)
		val maxX = (root.width - preview.width - margin).coerceAtLeast(margin)
		preview.translationX = (center - preview.width / 2f).coerceIn(margin, maxX)
	}

	// Side panel --------------------------------------------------------------------------------

	private fun openPanel(type: PanelType) {
		if (engine == null) return
		if (panelType == null) panelReturnFocus = currentFocus
		panelType = type
		hideSkip()
		cancelScrub()
		if (controlsVisible) {
			// Hide the bars so the picture and subtitles stay visible while adjusting.
			controlsVisible = false
			listOf(topBar, bottomBar).forEach { bar ->
				bar.animate().cancel()
				bar.alpha = 0f
				bar.visibility = View.INVISIBLE
			}
		}
		setControlsFocusable(false)
		buildPanel(type)
		if (panel.visibility != View.VISIBLE) {
			panel.visibility = View.VISIBLE
			panel.translationX = resources.getDimension(R.dimen.panel_width)
			panel.animate().translationX(0f).setDuration(220L).start()
		}
		panelScroll.scrollTo(0, 0)
		panelBuilder.initialFocus()?.requestFocus()
	}

	private fun closePanel(showBars: Boolean = true) {
		val type = panelType ?: return
		panelType = null
		panel.animate().translationX(resources.getDimension(R.dimen.panel_width)).setDuration(200L).withEndAction {
			if (panelType == null) panel.visibility = View.GONE
		}.start()
		setControlsFocusable(true)
		if (showBars) {
			val returnFocus = panelReturnFocus?.takeIf { isInside(it, bottomBar) && it.visibility == View.VISIBLE }
			showControls(returnFocus ?: buttonFor(type).takeIf { it.visibility == View.VISIBLE } ?: btnPlay)
		} else {
			// Park focus on the (hidden) timeline so the next key press behaves normally.
			timeline.requestFocus()
		}
		saveDelays()
	}

	private fun isInside(view: View, parent: ViewGroup): Boolean {
		var current: Any? = view
		while (current is View) {
			if (current === parent) return true
			current = current.parent
		}
		return false
	}

	private fun buttonFor(type: PanelType): View = findViewById(
		when (type) {
			PanelType.SUBTITLES -> R.id.btn_subtitles
			PanelType.AUDIO -> R.id.btn_audio
			PanelType.SYNC -> R.id.btn_sync
			PanelType.PLAYER -> R.id.btn_player
			PanelType.SETTINGS -> R.id.btn_settings
			PanelType.CHAPTERS -> R.id.btn_chapters
		}
	)

	private fun setControlsFocusable(focusable: Boolean) {
		val mode = if (focusable) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
		topBar.descendantFocusability = mode
		bottomBar.descendantFocusability = mode
		skipButton.isFocusable = focusable
	}

	/** Rebuilds the open panel (e.g. after a track change), keeping focus on the same row. */
	private fun refreshPanel(delayMs: Long = 250L) {
		handler.postDelayed({
			val type = panelType ?: return@postDelayed
			val focusedIndex = panelList.indexOfChild(currentFocus)
			buildPanel(type)
			val target = panelList.getChildAt(focusedIndex)?.takeIf { it.isFocusable } ?: panelBuilder.initialFocus()
			target?.requestFocus()
		}, delayMs)
	}

	/**
	 * "English", "Swedish (Forced)"… instead of "Track 1", "Track 2": uses the language the player
	 * found, VLC's "[Swedish]" part, or what Jellyfin knows about the file's subtitles (same order).
	 */
	private fun readableNames(
		tracks: List<app.lumen.player.engine.TrackOption>,
		server: List<app.lumen.player.jellyfin.ServerSubtitle>,
		externals: List<ExternalSubtitle> = emptyList(),
	): List<app.lumen.player.engine.TrackOption> {
		val embedded = server.filter { !it.isExternal }
		// VLC calls everything "Track 1, 2, 3…": the video's own subtitles come first (same order as on the
		// server), then the subtitle files it was handed, in the order they were added.
		val firstFile = if (engineKind == EngineKind.VLC && externals.isNotEmpty() && tracks.size >= externals.size) tracks.size - externals.size else Int.MAX_VALUE
		var next = 0
		val seen = mutableMapOf<String, Int>()
		return tracks.mapIndexed { index, track ->
			val file = if (index >= firstFile) externals.getOrNull(index - firstFile) else null
			val external = file != null || track.detail.contains("External")
			val fromServer = if (!external) embedded.getOrNull(next++) else null
			val bracket = VLC_LANGUAGE.find(track.title)?.groupValues?.get(1)
			val generic = GENERIC_TRACK.matches(track.title.trim()) || GENERIC_VLC.matches(track.title.trim())
			var name = when {
				bracket != null && !GENERIC_TRACK.matches(bracket.trim()) -> if (bracket.length <= 3) Formatters.language(bracket) ?: bracket else bracket
				!generic -> track.title
				file != null -> fileName(file)
				fromServer != null -> Formatters.language(fromServer.language) ?: fromServer.title.substringBefore(" - ").ifBlank { fromServer.title }
				else -> track.title
			}
			val extras = listOfNotNull(
				"Forced".takeIf { (fromServer?.isForced == true || file?.forced == true) && !track.detail.contains("Forced") },
				(fromServer?.title ?: file?.name)?.let { SDH.find(it)?.value?.uppercase(java.util.Locale.US) },
			)
			if (extras.isNotEmpty()) name += " (" + extras.joinToString(", ") + ")"
			// Two "English" tracks: number them so they can be told apart.
			val count = (seen[name] ?: 0) + 1
			seen[name] = count
			track.copy(title = if (count > 1) "$name $count" else name)
		}
	}

	/** A subtitle file's name: "Swedish · OpenSubtitles" for downloads, else its language. */
	private fun fileName(file: ExternalSubtitle): String {
		val given = file.name?.trim().orEmpty()
		if (given.contains(" · ")) return given
		return Formatters.language(file.language)
			?: given.substringBefore(" - ").takeIf { it.isNotBlank() && !GENERIC_TRACK.matches(it) }
			?: "Subtitles"
	}

	private fun buildPanel(type: PanelType) {
		val current = engine ?: return
		val b = panelBuilder
		b.clear()
		when (type) {
			PanelType.SUBTITLES -> {
				panelTitle.text = getString(R.string.subtitles)
				val tracks = readableNames(current.subtitleTracks(), metadata?.subtitles.orEmpty(), subtitlesForEngine)
				b.option("Off", selected = tracks.none { it.selected }) {
					current.selectSubtitleTrack(null)
					refreshPanel()
				}
				tracks.forEach { track ->
					b.option(track.title, track.detail.ifBlank { null }, track.selected, track.supported) {
						current.selectSubtitleTrack(track.id)
						refreshPanel()
					}
				}
				b.action("Download subtitles…", "OpenSubtitles · Addic7ed · your server") {
					closePanel(showBars = false)
					downloadSubtitles()
				}
				b.header("Timing")
				subtitleDelayStepper(b)
				b.action("Reset subtitle delay") {
					setSubtitleDelay(0L)
					refreshPanel(0L)
				}
				b.header("Appearance")
				subtitleAppearance(b)
			}

			PanelType.AUDIO -> {
				panelTitle.text = getString(R.string.audio)
				readableNames(current.audioTracks(), emptyList()).forEach { track ->
					b.option(track.title, track.detail.ifBlank { null }, track.selected, track.supported) {
						current.selectAudioTrack(track.id)
						refreshPanel()
					}
				}
				b.header("Timing")
				audioDelayStepper(b)
				b.action("Reset audio delay") {
					setAudioDelay(0L)
					refreshPanel(0L)
				}
				if (engineKind == EngineKind.EXO) {
					b.note("Dolby and DTS are sent to your receiver as bitstream when Android's Surround sound setting allows it; otherwise they are decoded to PCM.")
				}
			}

			PanelType.SYNC -> {
				panelTitle.text = "Sync"
				subtitleDelayStepper(b)
				audioDelayStepper(b)
				b.action("Reset both") {
					setSubtitleDelay(0L)
					setAudioDelay(0L)
					refreshPanel(0L)
				}
				b.note("Use ◀ ▶ to adjust in 50 ms steps; hold for bigger steps. Positive values make subtitles or audio come later." +
					if (prefs.rememberDelays) " Offsets are remembered for this video." else "")
			}

			PanelType.PLAYER -> {
				panelTitle.text = getString(R.string.player)
				availableModes().forEach { option ->
					val tried = option in triedModes && option != mode
					b.option(option.label, option.hint + if (tried) "  ·  tried" else "", option == mode) {
						if (option != mode) switchMode(option) else closePanel()
					}
				}
				b.header("Stutter")
				b.stepper("Ask to switch when it stutters", value = { listOf("Off", "Normal", "Sensitive")[prefs.stutterDetection.coerceIn(0, 2)] }) { d, _ ->
					prefs.stutterDetection = (prefs.stutterDetection + d + 3) % 3
				}
				if (isInternal) {
					val meta = metadata
					b.action(if (meta?.isEpisode == true) "Always use ${mode.label} for this show" else "Always use ${mode.label} for this video") {
						prefs.rememberPlayerMode(mode.name, internalItemId, meta?.seriesId)
						rememberCandidate = null
						showToast("Saved")
						closePanel()
					}
					b.action("Forget saved player") {
						prefs.forgetPlayerMode(internalItemId, meta?.seriesId)
						showToast("Back to your default player")
						closePanel()
					}
				}
				b.note("Direct plays the original file (best quality, HDR, surround passthrough). The Mbps options ask your server to convert the video to a lighter H.264 stream — use them when the file stutters over the network.")
			}

			PanelType.SETTINGS -> {
				panelTitle.text = getString(R.string.settings)
				b.stepper("Playback speed", value = { formatSpeed(speed) }) { direction, _ ->
					val index = (SPEEDS.indexOfFirst { it == speed }.takeIf { it >= 0 } ?: SPEEDS.indexOf(1f)) + direction
					speed = SPEEDS[index.coerceIn(0, SPEEDS.lastIndex)]
					engine?.setSpeed(speed)
				}
				b.stepper("Picture", value = { scaleMode.label }) { direction, _ ->
					val values = ScaleMode.entries
					scaleMode = values[(scaleMode.ordinal + direction + values.size) % values.size]
					prefs.pictureMode = scaleMode.name
					engine?.setScaleMode(scaleMode)
				}
				b.action("Player", mode.label) {
					openPanel(PanelType.PLAYER)
				}
				b.toggle("Stats overlay", isOn = { prefs.showStats }) {
					prefs.showStats = it
					updateStats()
				}
				b.toggle("Match frame rate", "Switch the TV to the video's refresh rate", isOn = { prefs.matchFrameRate }) {
					prefs.matchFrameRate = it
					if (!it) DisplayModeHelper.reset(this)
				}
			}

			PanelType.CHAPTERS -> {
				panelTitle.text = getString(R.string.chapters)
				val chapters = metadata?.chapters.orEmpty()
				val position = current.positionMs
				val active = chapters.lastOrNull { it.startMs <= position }
				chapters.forEachIndexed { index, chapter ->
					b.option(chapter.name.ifBlank { "Chapter ${index + 1}" }, Formatters.time(chapter.startMs), chapter == active) {
						seekEngine(chapter.startMs)
						closePanel()
					}
				}
			}
		}
	}

	private fun subtitleDelayStepper(b: PanelBuilder) {
		b.stepper("Subtitle delay", value = { Formatters.delay(subtitleDelayMs) }) { direction, repeat ->
			setSubtitleDelay(subtitleDelayMs + direction * (if (repeat > 6) 250L else 50L))
		}
	}

	private fun audioDelayStepper(b: PanelBuilder) {
		b.stepper("Audio delay", value = { Formatters.delay(audioDelayMs) }) { direction, repeat ->
			setAudioDelay(audioDelayMs + direction * (if (repeat > 6) 250L else 25L))
		}
	}

	private fun subtitleAppearance(b: PanelBuilder) {
		val current = engine ?: return
		b.stepper("Size", value = { "${(prefs.subtitleScale * 100).roundToInt()}%" }) { direction, _ ->
			prefs.subtitleScale = ((prefs.subtitleScale * 20).roundToInt() + direction).coerceIn(10, 50) / 20f
			current.applySubtitleStyle()
		}
		b.stepper("Position", value = { "${(prefs.subtitleBottom * 100).roundToInt()}%" }) { direction, _ ->
			prefs.subtitleBottom = ((prefs.subtitleBottom * 50).roundToInt() + direction).coerceIn(0, 20) / 50f
			current.applySubtitleStyle()
		}
		b.stepper("Style", value = { SUBTITLE_STYLES[prefs.subtitleStyle.coerceIn(0, 2)] }) { direction, _ ->
			prefs.subtitleStyle = (prefs.subtitleStyle + direction + 3) % 3
			current.applySubtitleStyle()
		}
		b.stepper("Colour", value = { if (prefs.subtitleColor == 1) "Yellow" else "White" }) { _, _ ->
			prefs.subtitleColor = if (prefs.subtitleColor == 1) 0 else 1
			current.applySubtitleStyle()
		}
		if (engineKind == EngineKind.VLC) b.note("VLC applies appearance changes the next time it starts.")
		else if (prefs.useLibass) b.note("Styled ASS/SSA subtitles keep their own look (rendered by libass).")
	}

	private fun setSubtitleDelay(ms: Long) {
		subtitleDelayMs = ms.coerceIn(-600_000L, 600_000L)
		engine?.setSubtitleDelay(subtitleDelayMs)
	}

	private fun setAudioDelay(ms: Long) {
		audioDelayMs = ms.coerceIn(-10_000L, 10_000L)
		engine?.setAudioDelay(audioDelayMs)
	}

	private fun saveDelays() {
		if (::request.isInitialized && prefs.rememberDelays) {
			prefs.saveDelays(request.itemKey, subtitleDelayMs, audioDelayMs)
		}
	}

	// Info overlays ------------------------------------------------------------------------------

	private fun refreshBadges() {
		val items = engine?.badges()?.items.orEmpty()
		badges.removeAllViews()
		val density = resources.displayMetrics.density
		items.forEach { label ->
			val view = TextView(this).apply {
				text = label
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
				typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
				setTextColor(Ui.color(this@PlayerActivity, R.attr.lumenText))
				background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_tag)
				setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
			}
			val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
			params.marginEnd = (8 * density).toInt()
			badges.addView(view, params)
		}
	}

	private fun updateStats() {
		if (!prefs.showStats) {
			statsView.visibility = View.GONE
			return
		}
		val current = engine ?: return
		val lines = current.stats().toMutableList()
		lines += "Sync" to "subs ${Formatters.delay(subtitleDelayMs)} · audio ${Formatters.delay(audioDelayMs)}"
		currentDisplayLine()?.let { lines += "Display" to it }
		metadata?.let { lines += "Jellyfin" to "linked · ${it.chapters.size} chapters · ${it.segments.size} segments" }
		val sensitivity = listOf("off", "normal", "sensitive")[prefs.stutterDetection.coerceIn(0, 2)]
		lines += "Stutter" to "$sensitivity · ${stutter.lastSummary.ifBlank { "measuring…" }}"
		statsView.text = lines.joinToString("\n") { (key, value) -> key.padEnd(14) + value }
		statsView.visibility = View.VISIBLE
	}

	@Suppress("DEPRECATION")
	private fun currentDisplayLine(): String? {
		val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
		val mode = display?.mode ?: return null
		return "${mode.physicalWidth}×${mode.physicalHeight} @ ${"%.3f".format(java.util.Locale.US, mode.refreshRate)} Hz"
	}

	private fun showToast(message: String) {
		toastView.text = message
		toastView.animate().cancel()
		toastView.animate().alpha(1f).setDuration(200L).start()
		handler.removeCallbacks(hideToastRunnable)
		handler.postDelayed(hideToastRunnable, 3_000L)
	}

	private fun showError(message: String, positionMs: Long) {
		loading.visibility = View.GONE
		buffering.visibility = View.GONE
		if (panelType != null) closePanel()
		hideControls()
		hideSkip()
		errorMessage.text = message
		errorActions.removeAllViews()
		val other = if (engineKind == EngineKind.EXO) EngineKind.VLC else EngineKind.EXO
		val otherAction = if (isInternal) {
			val alternative = PlayerMode.suggestions(mode, networkProblem = false, tried = triedModes + mode, canTranscode = true).firstOrNull()
				?: PlayerMode.EXO_10
			addErrorAction("Try ${alternative.label}") { switchMode(alternative) }
		} else {
			addErrorAction(if (other == EngineKind.VLC) "Try VLC engine" else "Try ExoPlayer") {
				startEngine(other, positionMs)
			}
		}
		addErrorAction("Retry") {
			if (isInternal) lifecycleScope.launch { startMode(mode, positionMs) } else startEngine(engineKind, positionMs)
		}
		addErrorAction("Close") { finishWithResult(completed = false) }
		errorCard.visibility = View.VISIBLE
		otherAction.requestFocus()
	}

	private fun addErrorAction(label: String, onClick: () -> Unit): View {
		val view = LayoutInflater.from(this).inflate(R.layout.item_pill, errorActions, false) as TextView
		view.text = label
		view.setOnClickListener { onClick() }
		errorActions.addView(view)
		return view
	}

	// Result back to Jellyfin -----------------------------------------------------------------

	private fun currentPositionForResult(): Long =
		engine?.positionMs?.takeIf { it > 0 } ?: lastKnownPositionMs.takeIf { it > 0 }
			?: if (::request.isInitialized) request.startPositionMs else 0L

	private fun currentDurationForResult(): Long =
		engine?.durationMs?.takeIf { it > 0 } ?: lastKnownDurationMs

	private fun updateResult() {
		setResult(RESULT_OK, IntentParser.buildResult(currentPositionForResult(), currentDurationForResult(), completed))
	}

	private fun finishWithResult(completed: Boolean) {
		if (finishing) return
		finishing = true
		if (completed) this.completed = true
		updateResult()
		saveDelays()
		upNextJob?.cancel()
		reporter?.stop(if (this.completed) currentDurationForResult().takeIf { it > 0 } ?: currentPositionForResult() else currentPositionForResult())
		reporter = null
		engine?.pause()
		finish()
	}

	private fun formatSpeed(value: Float): String =
		if (value % 1f == 0f) "${value.toInt()}×" else "${value}×"

}
