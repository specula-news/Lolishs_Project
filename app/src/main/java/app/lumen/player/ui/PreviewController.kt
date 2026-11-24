package app.lumen.player.ui

import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.lumen.player.R
import app.lumen.player.jellyfin.JfItem
import app.lumen.player.util.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Streaming-app style previews. When a title stays focused for 3 seconds it starts playing from the
 * beginning: inside the Home banner, or for any card full screen behind the see-through menu, with its
 * name and description at the bottom. After 20 seconds it carries on in the real player (from where
 * the preview is). Any button stops it.
 */
class PreviewController(private val host: MainActivity, private val root: FrameLayout) {

	private enum class State { IDLE, WAITING, PLAYING }

	private val handler = Handler(Looper.getMainLooper())
	private var state = State.IDLE
	private var item: JfItem? = null
	/** The banner (video plays inside it) or the focused card's artwork (video plays in [panel]). */
	private var anchor: FrameLayout? = null
	private var inline = false
	private var insertIndex = 1
	private var player: ExoPlayer? = null
	private var playing: JfItem? = null
	private var resolveJob: Job? = null
	private var surface: TextureView? = null
	private var videoWidth = 16f
	private var videoHeight = 9f
	private var countdownLeft = 0

	// Full-screen preview for cards: the video fills the screen behind the (see-through) menu,
	// name and description at the bottom.
	private val panel = FrameLayout(host).apply {
		setBackgroundColor(0xFF000000.toInt())
		visibility = View.GONE
	}
	private var panelShown = false
	private val panelVideo = FrameLayout(host)
	private val panelKind = text(12f, bold = true).apply {
		letterSpacing = 0.2f
		setTextColor(Ui.color(host, R.attr.lumenAccent))
	}
	private val panelTitle = TextView(host).apply {
		typeface = Ui.display(host)
		maxLines = 2
		ellipsize = android.text.TextUtils.TruncateAt.END
		setTextColor(0xFFFFFFFF.toInt())
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
		setShadowLayer(12f, 0f, 2f, 0xAA000000.toInt())
	}
	private val panelFacts = text(14f, bold = true).apply { maxLines = 1; setPadding(0, Ui.dp(host, 8), 0, 0) }
	private val panelOverview = text(15.5f).apply {
		maxLines = 3
		ellipsize = android.text.TextUtils.TruncateAt.END
		setLineSpacing(0f, 1.2f)
		setTextColor(0xE6FFFFFF.toInt())
		setPadding(0, Ui.dp(host, 8), 0, 0)
	}
	private val countdown = text(14f, bold = true).apply {
		background = ContextCompat.getDrawable(host, R.drawable.bg_hud)
		setPadding(Ui.dp(host, 14), Ui.dp(host, 7), Ui.dp(host, 14), Ui.dp(host, 7))
		visibility = View.GONE
	}

	init {
		panel.addView(panelVideo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		// Dark at the top (menu) and the bottom (name and description), clear in the middle.
		panel.addView(View(host).apply {
			background = android.graphics.drawable.GradientDrawable(
				android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
				intArrayOf(0x99000000.toInt(), 0x00000000),
			)
		}, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, 150), Gravity.TOP))
		panel.addView(View(host).apply {
			background = android.graphics.drawable.GradientDrawable(
				android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
				intArrayOf(0xE6000000.toInt(), 0x66000000, 0x00000000),
			)
		}, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, 300), Gravity.BOTTOM))
		val words = LinearLayout(host).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.BOTTOM
			setPadding(Ui.dp(host, 56), 0, Ui.dp(host, 40), Ui.dp(host, 40))
		}
		words.addView(panelKind)
		words.addView(panelTitle, LinearLayout.LayoutParams(Ui.dp(host, 680), ViewGroup.LayoutParams.WRAP_CONTENT))
		words.addView(panelFacts)
		words.addView(panelOverview, LinearLayout.LayoutParams(Ui.dp(host, 620), ViewGroup.LayoutParams.WRAP_CONTENT))
		panel.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
		// Under the menu (which turns see-through while a preview plays), over the backdrop.
		val shell = host.findViewById<View>(R.id.shell)
		val index = root.indexOfChild(shell).takeIf { it >= 0 } ?: root.childCount
		root.addView(panel, index, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
	}

	private fun text(sizeSp: Float, bold: Boolean = false) = TextView(host).apply {
		if (bold) typeface = Ui.bold(host)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
		setTextColor(0xF2FFFFFF.toInt())
	}

	private val mode: Int get() = host.prefs.previewMode

	/**
	 * [item] got focus. [inline]: [anchor] is the banner and the video goes into it at [index];
	 * otherwise [anchor] is a card and the video shows in the big preview window.
	 */
	fun focus(item: JfItem, anchor: FrameLayout, index: Int, inline: Boolean) {
		if (state != State.IDLE && this.item?.id == item.id && this.anchor === anchor) return
		stop()
		if (mode == 0 || item.type !in PREVIEWABLE) return
		this.item = item
		this.anchor = anchor
		this.inline = inline
		insertIndex = index
		state = State.WAITING
		handler.postDelayed(begin, START_DELAY_MS)
	}

	/** Focus left [anchor]: stop if it is the one being previewed. */
	fun leave(anchor: FrameLayout) {
		if (this.anchor === anchor) stop()
	}

	/** True while [anchor] is waiting for or showing a preview (the banner doesn't turn then). */
	fun isBusy(anchor: View): Boolean = state != State.IDLE && this.anchor === anchor

	/** Called for every key press. True when the key was only used to close the preview. */
	fun onKey(event: KeyEvent): Boolean {
		when (state) {
			State.IDLE -> return false
			State.WAITING -> {
				// Still waiting: count the 3 seconds again from this press.
				handler.removeCallbacks(begin)
				handler.postDelayed(begin, START_DELAY_MS)
				return false
			}
			State.PLAYING -> {
				val again = item
				val box = anchor
				val index = insertIndex
				val wasInline = inline
				stop()
				if (event.keyCode == KeyEvent.KEYCODE_BACK) return true // BACK only closes the preview
				// The button does what it normally does; the preview starts over if the title keeps focus.
				if (again != null && box != null) {
					handler.post { if (box.hasFocus() || (box.parent as? View)?.hasFocus() == true) focus(again, box, index, wasInline) }
				}
				return false
			}
		}
	}

	fun stop() {
		handler.removeCallbacks(begin)
		handler.removeCallbacks(handOff)
		handler.removeCallbacks(tick)
		resolveJob?.cancel()
		resolveJob = null
		player?.let {
			it.clearVideoSurface()
			it.release()
		}
		player = null
		playing = null
		surface?.let { (it.parent as? ViewGroup)?.removeView(it) }
		surface = null
		(countdown.parent as? ViewGroup)?.removeView(countdown)
		if (panel.visibility == View.VISIBLE) {
			panel.animate().cancel()
			panel.animate().alpha(0f).setDuration(200L).withEndAction { panel.visibility = View.GONE }.start()
		}
		if (panelShown) {
			panelShown = false
			host.setPreviewing(false)
		}
		state = State.IDLE
		item = null
		anchor = null
	}

	private val begin = Runnable {
		val target = item ?: return@Runnable
		val box = anchor ?: return@Runnable
		if (!box.isAttachedToWindow || !box.isShown || (!inline && !holdsFocus(box))) {
			stop()
			return@Runnable
		}
		resolveJob = host.lifecycleScope.launch {
			val playable = try {
				resolve(target)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				null
			}
			if (playable == null || item?.id != target.id) {
				if (item?.id == target.id) stop()
				return@launch
			}
			start(target, playable, box)
		}
	}

	/** Movies and episodes play themselves; a show (or season) plays its next (or first) episode. */
	private suspend fun resolve(target: JfItem): JfItem? = when (target.type) {
		"Series" -> host.api.nextUp(host.session, 1, target.id).firstOrNull()
			?: host.api.episodes(host.session, target.id, null).firstOrNull()
		"Season" -> target.seriesId?.let { series -> host.api.episodes(host.session, series, target.id).firstOrNull() }
		else -> target
	}

	/** The card (or the card the artwork belongs to) still has focus. */
	private fun holdsFocus(view: View): Boolean {
		var v: View? = view
		repeat(3) {
			val current = v ?: return false
			if (current.isFocused || current.hasFocus()) return true
			v = current.parent as? View
		}
		return false
	}

	private fun start(shown: JfItem, playable: JfItem, box: FrameLayout) {
		val view = TextureView(host).apply { alpha = 0f }
		view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop(view) }
		if (inline) {
			box.addView(view, insertIndex.coerceAtMost(box.childCount), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
			box.addView(countdown, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.dp(host, 18), Ui.dp(host, 20), 0) })
		} else {
			showPanel(shown)
			panelVideo.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
			panel.addView(countdown, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, Ui.dp(host, 44), Ui.dp(host, 44)) })
		}
		countdown.visibility = View.GONE
		surface = view
		val exo = ExoPlayer.Builder(host).build()
		player = exo
		playing = playable
		exo.setVideoTextureView(view)
		exo.volume = if (host.prefs.previewSound) VOLUME else 0f
		exo.addListener(object : Player.Listener {
			override fun onRenderedFirstFrame() {
				if (state != State.PLAYING) return
				surface?.animate()?.alpha(1f)?.setDuration(450L)?.start()
				if (mode == 2) {
					handler.removeCallbacks(handOff)
					handler.postDelayed(handOff, HAND_OFF_MS)
					countdownLeft = COUNTDOWN_S
					handler.removeCallbacks(tick)
					handler.postDelayed(tick, HAND_OFF_MS - COUNTDOWN_S * 1000L)
				}
			}

			override fun onVideoSizeChanged(videoSize: VideoSize) {
				if (videoSize.width > 0 && videoSize.height > 0) {
					videoWidth = videoSize.width * videoSize.pixelWidthHeightRatio
					videoHeight = videoSize.height.toFloat()
				}
				surface?.let { crop(it) }
			}

			override fun onPlayerError(error: PlaybackException) = stop()

			override fun onPlaybackStateChanged(playbackState: Int) {
				if (playbackState == Player.STATE_ENDED) stop()
			}
		})
		val url = host.api.directStreamUrl(host.session, playable.id, null, UUID.randomUUID().toString())
		exo.setMediaItem(MediaItem.fromUri(url))
		exo.prepare()
		// From the beginning (or where you left off), so it can simply carry on in the player.
		val user = playable.userData
		exo.seekTo(if (user != null && user.hasResume) user.positionMs else 0L)
		exo.playWhenReady = true
		state = State.PLAYING
	}

	/** The preview fills the screen; the page fades away and the menu turns see-through over it. */
	private fun showPanel(shown: JfItem) {
		panelKind.text = FeatureText.kind(shown)
		panelTitle.text = FeatureText.title(shown)
		panelTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (panelTitle.text.length > 26) 30f else 38f)
		panelFacts.text = FeatureText.facts(host, shown)
		panelOverview.text = shown.overview.orEmpty()
		panelOverview.visibility = if (shown.overview.isNullOrBlank()) View.GONE else View.VISIBLE
		panel.animate().cancel()
		panel.alpha = 0f
		panel.visibility = View.VISIBLE
		panel.animate().alpha(1f).setDuration(350L).start()
		panelShown = true
		host.setPreviewing(true)
	}

	/** "Playing in 5…" during the last seconds before the real player takes over. */
	private val tick: Runnable = object : Runnable {
		override fun run() {
			if (state != State.PLAYING || countdownLeft <= 0) return
			countdown.text = "Playing in $countdownLeft…"
			countdown.visibility = View.VISIBLE
			countdownLeft--
			handler.postDelayed(this, 1_000L)
		}
	}

	/** After 20 seconds the title carries on in the real player, right where the preview is. */
	private val handOff = Runnable {
		val watched = playing ?: return@Runnable
		val position = player?.currentPosition ?: 0L
		stop()
		PlayerActivity.play(host, watched.id, startMs = position)
	}

	/** Fills the window, cropping what doesn't fit. */
	private fun crop(view: TextureView) {
		val w = view.width.toFloat()
		val h = view.height.toFloat()
		if (w <= 0f || h <= 0f || videoHeight <= 0f) return
		val video = videoWidth / videoHeight
		val box = w / h
		var sx = 1f
		var sy = 1f
		if (video > box) sx = video / box else sy = box / video
		view.setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
	}

	private companion object {
		const val START_DELAY_MS = 3_000L
		const val HAND_OFF_MS = 20_000L
		const val COUNTDOWN_S = 5
		const val VOLUME = 0.7f
		val PREVIEWABLE = setOf("Movie", "Episode", "Series", "Video", "MusicVideo")
	}
}
