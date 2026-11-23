package app.lumen.player.ui

import android.graphics.Typeface
import android.net.Uri
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
import app.lumen.player.engine.ScaleMode
import app.lumen.player.engine.VlcEngine
import app.lumen.player.jellyfin.ItemMetadata
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.Segment
import app.lumen.player.jellyfin.Session
import app.lumen.player.util.DisplayModeHelper
import app.lumen.player.util.Formatters
import app.lumen.player.util.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity(), EngineListener, TimelineView.Listener {

	private enum class EngineKind { EXO, VLC }
	private enum class PanelType { SUBTITLES, AUDIO, SYNC, SETTINGS, CHAPTERS }

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
	private var scaleMode = ScaleMode.FIT
	private var completed = false
	private var finishing = false
	private var firstFrameShown = false
	private var lastKnownPositionMs = 0L
	private var lastKnownDurationMs = 0L
	private var tickCount = 0

	private var controlsVisible = false
	private var panelType: PanelType? = null
	private var panelReturnFocus: View? = null
	private var scrubbing = false
	private var scrubTargetMs = 0L
	private var activeSegment: Segment? = null
	private val dismissedSegments = mutableSetOf<Segment>()

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

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_player)
		prefs = Prefs(this)
		api = JellyfinApi(prefs)
		hideSystemUi()
		bindViews()

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

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() = handleBack()
		})

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
		findViewById<View>(R.id.btn_settings).setOnClickListener { openPanel(PanelType.SETTINGS) }
		btnChapters.setOnClickListener { openPanel(PanelType.CHAPTERS) }
		findViewById<View>(R.id.btn_info).setOnClickListener {
			prefs.showStats = !prefs.showStats
			updateStats()
		}
		skipButton.setOnClickListener { onSkipClicked() }

		// Show the name of the focused button next to the row, like the Google TV player.
		val buttons = listOf(R.id.btn_play, R.id.btn_rewind, R.id.btn_forward, R.id.btn_subtitles, R.id.btn_audio,
			R.id.btn_sync, R.id.btn_chapters, R.id.btn_settings, R.id.btn_info)
		buttons.forEach { id ->
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
		btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
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
		handler.post { finishWithResult(completed = true) }
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
		loading.visibility = View.GONE
		engine?.let { applySyncAndSpeed(it) }
		refreshBadges()
		showControls(timeline)
	}

	// Ticker ----------------------------------------------------------------------------------------

	private fun tick() {
		tickCount++
		val current = engine ?: return
		val position = current.positionMs
		val duration = current.durationMs
		if (position > 0) lastKnownPositionMs = position
		if (duration > 0) lastKnownDurationMs = duration

		timeline.durationMs = duration
		timeline.positionMs = position
		timeline.bufferedMs = current.bufferedMs
		elapsed.text = Formatters.time(position)
		remaining.text = if (duration > 0) "−" + Formatters.time(duration - position) else ""
		buffering.visibility =
			if (current.isBuffering && firstFrameShown && !scrubbing && errorCard.visibility != View.VISIBLE) View.VISIBLE else View.GONE

		if (controlsVisible) updateClock(position, duration)
		checkSegments(position)
		if (statsView.visibility == View.VISIBLE && tickCount % 2 == 0) updateStats()
		if (tickCount % 10 == 0) updateResult()
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
			finishWithResult(completed = true)
		} else {
			engine?.seekTo(segment.endMs)
		}
	}

	// Keys --------------------------------------------------------------------------------------

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN) bumpHideTimer()
		val code = event.keyCode

		if (errorCard.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)

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
					flashCenter(R.drawable.ic_play)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_PAUSE -> {
					engine?.pause()
					flashCenter(R.drawable.ic_pause)
					showControls(btnPlay)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
					seekBy(30_000L)
					return true
				}
				KeyEvent.KEYCODE_MEDIA_REWIND -> {
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

			if (!controlsVisible) {
				when (code) {
					KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
						if (skipButton.visibility == View.VISIBLE && skipButton.isFocused) return super.dispatchKeyEvent(event)
						togglePlayPause()
						return true
					}
					KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
						showControls(timeline)
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

	private fun showControls(focus: View? = null) {
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
			flashCenter(R.drawable.ic_pause)
			showControls(btnPlay)
		} else {
			current.play()
			flashCenter(R.drawable.ic_play)
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
		current.seekTo(target)
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
		current.seekTo(target)
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
		if (scrubbing) commitScrub() else togglePlayPause()
	}

	private fun commitScrub() {
		if (!scrubbing) return
		handler.removeCallbacks(commitScrubRunnable)
		scrubbing = false
		engine?.seekTo(scrubTargetMs)
		timeline.positionMs = scrubTargetMs
		timeline.scrubPositionMs = null
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

	private fun closePanel() {
		val type = panelType ?: return
		panelType = null
		panel.animate().translationX(resources.getDimension(R.dimen.panel_width)).setDuration(200L).withEndAction {
			if (panelType == null) panel.visibility = View.GONE
		}.start()
		setControlsFocusable(true)
		val returnFocus = panelReturnFocus?.takeIf { isInside(it, bottomBar) && it.visibility == View.VISIBLE }
		showControls(returnFocus ?: buttonFor(type).takeIf { it.visibility == View.VISIBLE } ?: btnPlay)
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

	private fun buildPanel(type: PanelType) {
		val current = engine ?: return
		val b = panelBuilder
		b.clear()
		when (type) {
			PanelType.SUBTITLES -> {
				panelTitle.text = getString(R.string.subtitles)
				val tracks = current.subtitleTracks()
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
				current.audioTracks().forEach { track ->
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
					engine?.setScaleMode(scaleMode)
				}
				val other = if (engineKind == EngineKind.EXO) "VLC" else "ExoPlayer"
				b.action("Switch to $other engine", "Now playing with ${current.name}") {
					val position = current.positionMs
					closePanel()
					startEngine(if (engineKind == EngineKind.EXO) EngineKind.VLC else EngineKind.EXO, position)
					showToast("Switched to the $other engine")
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
						current.seekTo(chapter.startMs)
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
				setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.lumen_text))
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
		val otherAction = addErrorAction(if (other == EngineKind.VLC) "Try VLC engine" else "Try ExoPlayer") {
			startEngine(other, positionMs)
		}
		addErrorAction("Retry") { startEngine(engineKind, positionMs) }
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
		engine?.positionMs?.takeIf { it > 0 } ?: lastKnownPositionMs.takeIf { it > 0 } ?: request.startPositionMs

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
		engine?.pause()
		finish()
	}

	private fun formatSpeed(value: Float): String =
		if (value % 1f == 0f) "${value.toInt()}×" else "${value}×"

	private companion object {
		val SKIPPABLE = setOf("Intro", "Outro", "Recap", "Preview", "Commercial")
		val SPEEDS = listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
		val SUBTITLE_STYLES = listOf("Outline", "Drop shadow", "Background box")
		val SUBTITLE_INDEX = Regex("/Subtitles/(\\d+)/", RegexOption.IGNORE_CASE)
	}
}
