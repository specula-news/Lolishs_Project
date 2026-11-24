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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.lumen.player.jellyfin.JfItem
import app.lumen.player.util.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Streaming-app style previews. When a title stays focused for a few seconds (the Home banner or a
 * wide card) its video starts playing in place of the artwork; after a while longer it grows to full
 * screen. Any button stops it again; OK in full screen keeps watching in the real player.
 */
class PreviewController(private val host: MainActivity, private val root: FrameLayout) {

	private enum class State { IDLE, WAITING, SMALL, FULL }

	private val handler = Handler(Looper.getMainLooper())
	private var state = State.IDLE
	private var item: JfItem? = null
	private var container: FrameLayout? = null
	private var insertIndex = 1
	private var player: ExoPlayer? = null
	private var playing: JfItem? = null
	private var resolveJob: Job? = null
	private var small: TextureView? = null
	private var videoWidth = 16f
	private var videoHeight = 9f

	private val full = FrameLayout(host).apply {
		setBackgroundColor(0xFF000000.toInt())
		visibility = View.GONE
	}
	private val fullTexture = TextureView(host)
	private val fullHint = TextView(host).apply {
		typeface = Ui.bold(host)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		setTextColor(0xFFFFFFFF.toInt())
		setShadowLayer(8f, 0f, 1f, 0xCC000000.toInt())
		setLineSpacing(0f, 1.25f)
	}

	init {
		full.addView(fullTexture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		full.addView(fullHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
			Gravity.BOTTOM or Gravity.START).apply { setMargins(Ui.dp(host, 56), 0, 0, Ui.dp(host, 44)) })
		root.addView(full, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		fullTexture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> transform(fullTexture, fill = false) }
	}

	private val mode: Int get() = host.prefs.previewMode

	/** [item] got focus; its artwork is [container] (the video goes in at [index], under the titles). */
	fun focus(item: JfItem, container: FrameLayout, index: Int) {
		if (state != State.IDLE && this.item?.id == item.id && this.container === container) return
		stop()
		if (mode == 0 || item.type !in PREVIEWABLE) return
		this.item = item
		this.container = container
		insertIndex = index
		state = State.WAITING
		handler.postDelayed(startSmall, SMALL_DELAY_MS)
	}

	/** Focus left [container]: stop if it is the one being previewed. */
	fun leave(container: FrameLayout) {
		if (this.container === container) stop()
	}

	/** Called for every key press. True when the key closed the full-screen preview. */
	fun onKey(event: KeyEvent): Boolean {
		when (state) {
			State.IDLE -> return false
			State.WAITING -> {
				// Still deciding: wait the full time again after the last button press.
				handler.removeCallbacks(startSmall)
				handler.postDelayed(startSmall, SMALL_DELAY_MS)
				return false
			}
			State.SMALL -> {
				// The button does what it normally does; the preview starts over for the focused title.
				val again = item
				val box = container
				val index = insertIndex
				stop()
				if (again != null && box != null) handler.post { if (box.hasFocus() || box.parent.let { (it as? View)?.hasFocus() == true }) focus(again, box, index) }
				return false
			}
			State.FULL -> {
				val watched = playing
				val position = player?.currentPosition ?: 0L
				stop()
				if (event.keyCode in OK_KEYS && watched != null) PlayerActivity.play(host, watched.id, startMs = position)
				return true
			}
		}
	}

	fun stop() {
		handler.removeCallbacks(startSmall)
		handler.removeCallbacks(goFull)
		resolveJob?.cancel()
		resolveJob = null
		player?.let {
			it.clearVideoSurface()
			it.release()
		}
		player = null
		playing = null
		small?.let { (it.parent as? ViewGroup)?.removeView(it) }
		small = null
		if (full.visibility == View.VISIBLE) {
			full.animate().cancel()
			full.animate().alpha(0f).setDuration(220L).withEndAction { full.visibility = View.GONE }.start()
		}
		state = State.IDLE
		item = null
		container = null
	}

	private val startSmall = Runnable {
		val target = item ?: return@Runnable
		val box = container ?: return@Runnable
		if (!box.isAttachedToWindow || !box.isShown) {
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
			start(playable, box)
		}
	}

	/** Movies and episodes play themselves; a show plays its next (or first) episode. */
	private suspend fun resolve(target: JfItem): JfItem? = when (target.type) {
		"Series" -> host.api.nextUp(host.session, 1, target.id).firstOrNull()
			?: host.api.episodes(host.session, target.id, null).firstOrNull()
		else -> target
	}

	private fun start(playable: JfItem, box: FrameLayout) {
		val view = TextureView(host).apply { alpha = 0f }
		box.addView(view, insertIndex.coerceAtMost(box.childCount), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> transform(view, fill = true) }
		small = view
		val exo = ExoPlayer.Builder(host).build()
		player = exo
		playing = playable
		exo.setVideoTextureView(view)
		exo.volume = if (host.prefs.previewSound) SMALL_VOLUME else 0f
		exo.addListener(object : Player.Listener {
			override fun onRenderedFirstFrame() {
				if (state != State.SMALL) return
				small?.animate()?.alpha(1f)?.setDuration(450L)?.start()
				if (mode == 2) {
					handler.removeCallbacks(goFull)
					handler.postDelayed(goFull, FULL_DELAY_MS)
				}
			}

			override fun onVideoSizeChanged(videoSize: VideoSize) {
				if (videoSize.width > 0 && videoSize.height > 0) {
					videoWidth = videoSize.width * videoSize.pixelWidthHeightRatio
					videoHeight = videoSize.height.toFloat()
				}
				small?.let { transform(it, fill = true) }
				transform(fullTexture, fill = false)
			}

			override fun onPlayerError(error: PlaybackException) = stop()

			override fun onPlaybackStateChanged(playbackState: Int) {
				if (playbackState == Player.STATE_ENDED) stop()
			}
		})
		val url = host.api.directStreamUrl(host.session, playable.id, null, UUID.randomUUID().toString())
		exo.setMediaItem(MediaItem.fromUri(url))
		exo.prepare()
		exo.seekTo(startPosition(playable))
		exo.playWhenReady = true
		state = State.SMALL
	}

	/** Where you left off, otherwise a little way in (past logos and intros). */
	private fun startPosition(playable: JfItem): Long {
		val user = playable.userData
		if (user != null && user.hasResume) return user.positionMs
		val runtime = playable.runtimeMs
		return if (runtime > 0) (runtime * 0.12).toLong().coerceIn(30_000L, 8 * 60_000L) else 60_000L
	}

	/** Grows from the small window to the whole screen. */
	private val goFull = Runnable {
		val exo = player ?: return@Runnable
		val box = container
		if (state != State.SMALL || box == null || !box.isShown) return@Runnable
		state = State.FULL
		val from = Rect()
		val screen = Rect()
		box.getGlobalVisibleRect(from)
		root.getGlobalVisibleRect(screen)
		full.animate().cancel()
		full.visibility = View.VISIBLE
		full.pivotX = 0f
		full.pivotY = 0f
		if (screen.width() > 0 && screen.height() > 0 && from.width() > 0) {
			full.scaleX = from.width() / screen.width().toFloat()
			full.scaleY = from.height() / screen.height().toFloat()
			full.translationX = (from.left - screen.left).toFloat()
			full.translationY = (from.top - screen.top).toFloat()
		}
		full.alpha = 0f
		exo.setVideoTextureView(fullTexture)
		exo.volume = if (host.prefs.previewSound) 1f else 0f
		transform(fullTexture, fill = false)
		full.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
			.setDuration(520L).setInterpolator(DecelerateInterpolator(1.6f)).start()
		val title = playing?.let { if (it.type == "Episode") listOfNotNull(it.seriesName, it.episodeCode).joinToString("  ·  ") else it.name }
		fullHint.text = listOfNotNull(title, "OK: keep watching   ·   any other button: back").joinToString("\n")
		fullHint.alpha = 1f
		fullHint.animate().alpha(0f).setStartDelay(5_000L).setDuration(700L).start()
		small?.alpha = 0f
	}

	/** Fills the small window (cropping) or fits the whole picture on the screen. */
	private fun transform(view: TextureView, fill: Boolean) {
		val w = view.width.toFloat()
		val h = view.height.toFloat()
		if (w <= 0f || h <= 0f || videoHeight <= 0f) return
		val video = videoWidth / videoHeight
		val box = w / h
		var sx = 1f
		var sy = 1f
		if (fill) {
			if (video > box) sx = video / box else sy = box / video
		} else {
			if (video > box) sy = box / video else sx = video / box
		}
		view.setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
	}

	private companion object {
		const val SMALL_DELAY_MS = 5_000L
		const val FULL_DELAY_MS = 25_000L
		const val SMALL_VOLUME = 0.55f
		val PREVIEWABLE = setOf("Movie", "Episode", "Series", "Video", "MusicVideo")
		val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
	}
}
