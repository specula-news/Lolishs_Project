package app.lumen.player.engine

import android.content.Context
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

	init {
		val options = arrayListOf(
			"--audio-time-stretch",
			"--network-caching=3000",
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
		libVlc = LibVLC(context, options)
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
		val videoLayout = VLCVideoLayout(context)
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
		lastTimeMs = startPositionMs
		player.play()
	}

	private fun handleEvent(event: MediaPlayer.Event) {
		when (event.type) {
			MediaPlayer.Event.Playing -> {
				buffering = false
				listener?.onEngineStateChanged()
			}
			MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> listener?.onEngineStateChanged()
			MediaPlayer.Event.Buffering -> {
				buffering = event.buffering < 100f
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

	override fun setScaleMode(mode: ScaleMode) {
		player.videoScale = when (mode) {
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

	override fun release() {
		runCatching {
			player.setEventListener(null)
			player.stop()
			player.detachViews()
			player.release()
			libVlc.release()
		}.onFailure { Log.w("VlcEngine", "Release failed", it) }
	}
}
