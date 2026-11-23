package app.lumen.player.engine

import android.view.ViewGroup
import app.lumen.player.ExternalSubtitle
import app.lumen.player.PlayRequest

enum class ScaleMode(val label: String) {
	FIT("Fit"),
	ZOOM("Zoom to fill"),
	STRETCH("Stretch"),
}

data class TrackOption(
	val id: String,
	val title: String,
	val detail: String,
	val selected: Boolean,
	val supported: Boolean = true,
)

/** A small badge shown in the player header, e.g. "4K", "Dolby Vision", "TrueHD 7.1". */
data class MediaBadges(val items: List<String>)

interface EngineListener {
	fun onEngineStateChanged()
	fun onEngineTracksChanged()
	fun onEngineEnded()
	fun onEngineError(message: String, canFallback: Boolean)
	fun onEngineVideoFrameRate(fps: Float)
	fun onEngineFirstFrame()
	/** Playback paused to buffer (not caused by a seek). */
	fun onEngineRebuffer()
	fun onEngineDroppedFrames(count: Int, elapsedMs: Long)
}

/** Common surface for the ExoPlayer and VLC backends so the UI doesn't care which one runs. */
/** Cumulative frame counts since the video decoder started. */
data class FrameCounters(val dropped: Long, val rendered: Long)

interface PlayerEngine {
	val name: String
	var listener: EngineListener?

	fun attach(container: ViewGroup)
	fun load(request: PlayRequest, subtitles: List<ExternalSubtitle>, startPositionMs: Long)

	fun play()
	fun pause()
	val isPlaying: Boolean
	val isBuffering: Boolean
	val isEnded: Boolean

	fun seekTo(positionMs: Long)
	val positionMs: Long
	val durationMs: Long
	val bufferedMs: Long

	fun audioTracks(): List<TrackOption>
	fun subtitleTracks(): List<TrackOption>
	fun selectAudioTrack(id: String)
	/** null turns subtitles off. */
	fun selectSubtitleTrack(id: String?)

	fun setSubtitleDelay(ms: Long)
	fun setAudioDelay(ms: Long)
	fun setSpeed(speed: Float)
	fun setScaleMode(mode: ScaleMode)
	fun applySubtitleStyle()

	fun badges(): MediaBadges
	fun stats(): List<Pair<String, String>>

	/** Running totals of dropped and shown video frames (for stutter detection), or null if unknown. */
	fun frameCounters(): FrameCounters? = null

	fun release()
}
