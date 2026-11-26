package app.lumen.player.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import app.lumen.player.ExternalSubtitle
import app.lumen.player.PlayRequest
import app.lumen.player.util.Prefs
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * Fallback engine built on libVLC: plays practically anything FFmpeg knows (AVI/DivX, WMV/VC-1,
 * RMVB, old MPEG-4 ASP, odd audio codecs) with libass subtitles and native subtitle/audio delay.
 */
class VlcEngine(
	private val context: Context,
	private val prefs: Prefs,
) : PlayerEngine {
	override val name = "VLC"
	override var listener: EngineListener? = null

	private val libVlc: LibVLC
	private val player: MediaPlayer
	private var layout: VLCVideoLayout? = null

	private var buffering = true
	private var ended = false
	private var lengthMs = 0L
	private var lastTimeMs = 0L
	private var reportedFrameRate = false
	private var subtitlesOffApplied = true
	private var playingOnce = false
	private var inRebuffer = false
	private var lastSeekAt = 0L

	init {
		val options = arrayListOf(
			"--audio-time-stretch",
			"--network-caching=2000",
			"--file-caching=1500",
			"--http-reconnect",
			"--freetype-rel-fontsize=${fontSizeFor(prefs.subtitleScale)}",
			"--freetype-color=${if (prefs.subtitleColor == 1) 16769357 else 16777215}",
			"--freetype-outline-thickness=4",
			"-v",
		)
		prefs.preferredAudioLanguage.takeIf { it.isNotBlank() }?.let { options += "--audio-language=$it" }
		if (prefs.subtitleMode == 1) {
			prefs.preferredSubtitleLanguage.takeIf { it.isNotBlank() }?.let { options += "--sub-language=$it" }
		}
		libVlc = sharedLibVlc(context, options)
		player = MediaPlayer(libVlc)
		player.setAudioDigitalOutputEnabled(prefs.vlcPassthrough)
		player.setEventListener(object : MediaPlayer.EventListener {
			override fun onEvent(event: MediaPlayer.Event?) {
				if (event != null) handleEvent(event)
			}
		})
	}

	/** VLC's relative font size: smaller number = bigger text (16 is the default). */
	private fun fontSizeFor(scale: Float): Int = (16f / scale.coerceIn(0.5f, 3f)).toInt().coerceIn(6, 30)

	override fun attach(container: ViewGroup) {
		val videoLayout = VLCVideoLayout(context).apply {
			// Never let the video surface steal D-pad focus from the menus.
			isFocusable = false
			descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
		}
		container.addView(videoLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
		player.attachViews(videoLayout, null, true, false)
		player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
		layout = videoLayout
	}

	override fun load(request: PlayRequest, subtitles: List<ExternalSubtitle>, startPositionMs: Long) {
		val media = Media(libVlc, request.uri)
		media.setHWDecoderEnabled(true, false)
		if (startPositionMs > 0) {
			media.addOption(String.format(Locale.US, ":start-time=%.3f", startPositionMs / 1000.0))
		}
		request.headers["User-Agent"]?.let { media.addOption(":http-user-agent=$it") }
		player.media = media
		media.release()

		subtitles.forEach { sub ->
			runCatching { player.addSlave(IMedia.Slave.Type.Subtitle, sub.uri, sub.enabled) }
				.onFailure { Log.w("VlcEngine", "Could not add subtitle ${sub.uri}", it) }
		}
		subtitlesOffApplied = prefs.subtitleMode != 2

		ended = false
		buffering = true
		playingOnce = false
		lastSeekAt = SystemClock.elapsedRealtime()
		lastTimeMs = startPositionMs
		player.play()
	}

	private fun handleEvent(event: MediaPlayer.Event) {
		when (event.type) {
			MediaPlayer.Event.Playing -> {
				buffering = false
				playingOnce = true
				inRebuffer = false
				listener?.onEngineStateChanged()
			}
			MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> listener?.onEngineStateChanged()
			MediaPlayer.Event.Buffering -> {
				buffering = event.buffering < 100f
				if (!buffering) {
					inRebuffer = false
				} else if (playingOnce && !inRebuffer &&
					SystemClock.elapsedRealtime() - lastSeekAt > 3_000L
				) {
					inRebuffer = true
					listener?.onEngineRebuffer()
				}
				listener?.onEngineStateChanged()
			}
			MediaPlayer.Event.TimeChanged -> {
				lastTimeMs = event.timeChanged
			}
			MediaPlayer.Event.LengthChanged -> {
				lengthMs = event.lengthChanged
			}
			MediaPlayer.Event.EndReached -> {
				ended = true
				listener?.onEngineEnded()
				listener?.onEngineStateChanged()
			}
			MediaPlayer.Event.EncounteredError -> {
				listener?.onEngineError("VLC couldn't play this file.", false)
			}
			MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
				if (!subtitlesOffApplied && player.spuTracksCount > 0) {
					// "Start with subtitles off": undo VLC's automatic subtitle pick once.
					subtitlesOffApplied = true
					player.setSpuTrack(-1)
				}
				listener?.onEngineTracksChanged()
			}
			MediaPlayer.Event.Vout -> {
				listener?.onEngineFirstFrame()
				reportFrameRate()
				if (scaleMode == ScaleMode.AUTO) applyScale()
			}
		}
	}

	private fun reportFrameRate() {
		if (reportedFrameRate) return
		val track = runCatching { player.currentVideoTrack }.getOrNull() ?: return
		if (track.frameRateDen > 0 && track.frameRateNum > 0) {
			reportedFrameRate = true
			listener?.onEngineVideoFrameRate(track.frameRateNum.toFloat() / track.frameRateDen)
		}
	}

	override fun play() {
		if (ended) {
			ended = false
			player.stop()
			player.play()
			return
		}
		player.play()
	}

	override fun pause() = player.pause()

	override val isPlaying: Boolean get() = player.isPlaying
	override val isBuffering: Boolean get() = buffering && !ended
	override val isEnded: Boolean get() = ended

	override fun seekTo(positionMs: Long) {
		if (ended) return
		lastSeekAt = SystemClock.elapsedRealtime()
		player.setTime(positionMs.coerceAtLeast(0L), false)
		lastTimeMs = positionMs
	}

	override val positionMs: Long get() = player.time.takeIf { it >= 0 } ?: lastTimeMs
	override val durationMs: Long get() = player.length.takeIf { it > 0 } ?: lengthMs
	override val bufferedMs: Long get() = positionMs

	override fun audioTracks(): List<TrackOption> {
		val current = player.audioTrack
		return player.audioTracks.orEmpty().filter { it.id != -1 }.map { track ->
			TrackOption(id = track.id.toString(), title = track.name ?: "Track ${track.id}", detail = "", selected = track.id == current)
		}
	}

	override fun subtitleTracks(): List<TrackOption> {
		val current = player.spuTrack
		return player.spuTracks.orEmpty().filter { it.id != -1 }.map { track ->
			TrackOption(id = track.id.toString(), title = track.name ?: "Track ${track.id}", detail = "", selected = track.id == current)
		}
	}

	override fun selectAudioTrack(id: String) {
		id.toIntOrNull()?.let { player.setAudioTrack(it) }
	}

	override fun selectSubtitleTrack(id: String?) {
		player.setSpuTrack(id?.toIntOrNull() ?: -1)
	}

	// libVLC delays are in microseconds; positive = later.
	override fun setSubtitleDelay(ms: Long) {
		player.setSpuDelay(ms * 1000L)
	}

	override fun setAudioDelay(ms: Long) {
		player.setAudioDelay(ms * 1000L)
	}

	override fun setSpeed(speed: Float) {
		player.rate = speed
	}

	private var scaleMode = ScaleMode.AUTO

	override fun setScaleMode(mode: ScaleMode) {
		scaleMode = mode
		applyScale()
	}

	/** Auto: fill the sides when the picture is only a little narrower than the screen (see ScaleMode). */
	private fun applyScale() {
		player.videoScale = when (scaleMode) {
			ScaleMode.AUTO -> {
				val track = runCatching { player.currentVideoTrack }.getOrNull()
				val sar = if (track != null && track.sarNum > 0 && track.sarDen > 0) track.sarNum.toFloat() / track.sarDen else 1f
				val aspect = if (track != null && track.width > 0 && track.height > 0) track.width * sar / track.height else 0f
				val view = layout
				val screen = if (view != null && view.width > 0 && view.height > 0) view.width.toFloat() / view.height else 16f / 9f
				if (ScaleMode.autoFills(aspect, screen)) MediaPlayer.ScaleType.SURFACE_FIT_SCREEN else MediaPlayer.ScaleType.SURFACE_BEST_FIT
			}
			ScaleMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
			ScaleMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
			ScaleMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
		}
	}

	// VLC subtitle styling is fixed when the engine starts (see init).
	override fun applySubtitleStyle() = Unit

	override fun badges(): MediaBadges {
		val items = mutableListOf<String>()
		runCatching { player.currentVideoTrack }.getOrNull()?.let { video ->
			app.lumen.player.util.Formatters.resolution(video.width, video.height)?.let(items::add)
		}
		items += "VLC"
		return MediaBadges(items)
	}

	override fun stats(): List<Pair<String, String>> {
		val out = mutableListOf<Pair<String, String>>()
		out += "Engine" to "libVLC (fallback)"
		runCatching { player.currentVideoTrack }.getOrNull()?.let { v ->
			val fps = if (v.frameRateDen > 0) String.format(Locale.US, "%.3f fps", v.frameRateNum.toDouble() / v.frameRateDen) else null
			out += "Video" to listOfNotNull(v.codec, "${v.width}×${v.height}", fps).joinToString("  ")
		}
		val audio = audioTracks().firstOrNull { it.selected }
		if (audio != null) out += "Audio" to audio.title
		out += "Passthrough" to if (prefs.vlcPassthrough) "On (if receiver supports it)" else "Off"
		out += "Speed" to "${player.rate}×"
		return out
	}

	/** VLC's media statistics: lost (late/dropped) and displayed pictures. Read by reflection to stay version-proof. */
	override fun frameCounters(): FrameCounters? {
		val media = player.media ?: return null
		return try {
			val stats: Any = media.stats ?: return null
			val lost = stats.javaClass.getField("lostPictures").getInt(stats)
			val shown = stats.javaClass.getField("displayedPictures").getInt(stats)
			FrameCounters(lost.toLong(), shown.toLong())
		} catch (_: Throwable) {
			null
		} finally {
			media.release()
		}
	}

	override fun release() {
		// Detach the surface right away (main thread), then stop + free VLC in the background:
		// stop() can block for seconds on a network stream, which made switching players feel stuck.
		runCatching {
			player.setEventListener(null)
			player.detachViews()
		}.onFailure { Log.w("VlcEngine", "Detach failed", it) }
		val vlcPlayer = player
		val instance = libVlc
		Thread({
			runCatching {
				vlcPlayer.stop()
				vlcPlayer.release()
				// The LibVLC instance is shared between engines; only free one that was replaced.
				synchronized(VlcEngine) { if (instance !== shared) instance.release() }
			}.onFailure { Log.w("VlcEngine", "Release failed", it) }
		}, "vlc-release").start()
	}

	companion object {
		@Volatile private var shared: LibVLC? = null
		private var sharedOptions: List<String> = emptyList()

		/** Creating LibVLC loads every module (slow), so keep one instance while the options stay the same. */
		private fun sharedLibVlc(context: Context, options: ArrayList<String>): LibVLC = synchronized(VlcEngine) {
			val current = shared
			if (current != null && sharedOptions == options) return current
			val created = LibVLC(context.applicationContext, options)
			shared = created
			sharedOptions = ArrayList(options)
			// The previous instance is released by its engine's release thread once its player is gone.
			created
		}
	}
}
