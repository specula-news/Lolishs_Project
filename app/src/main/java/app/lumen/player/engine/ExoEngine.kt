package app.lumen.player.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.lumen.player.BuildConfig
import app.lumen.player.ExternalSubtitle
import app.lumen.player.PlayRequest
import app.lumen.player.util.Formatters
import app.lumen.player.util.Prefs
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView

/**
 * Primary engine. Uses the Shield's hardware decoders through MediaCodec (H.264, HEVC, VP9, AV1 where
 * available, HDR10/HLG/Dolby Vision output), bitstream passthrough for Dolby/DTS when the receiver
 * supports it, FFmpeg for any audio the hardware can't decode, and libass for styled subtitles.
 */
@OptIn(UnstableApi::class)
class ExoEngine(
	private val context: Context,
	private val prefs: Prefs,
	private val headers: Map<String, String>,
) : PlayerEngine {
	override val name = "ExoPlayer"
	override var listener: EngineListener? = null

	private val subtitleDelay = DelayHolder()
	private val audioDelay = DelayHolder()
	private val assHandler: AssHandler? = if (prefs.useLibass) AssHandler(AssRenderType.OVERLAY_OPEN_GL) else null
	private val trackSelector = DefaultTrackSelector(context)
	private val player: ExoPlayer
	private var playerView: PlayerView? = null

	private var videoDecoder: String? = null
	private var audioDecoder: String? = null
	private var unsupportedReported = false
	private var lastFrameRate = -1f
	private var readyOnce = false
	private var lastSeekAt = 0L

	init {
		val httpFactory = DefaultHttpDataSource.Factory()
			.setUserAgent("LumenPlayer/${BuildConfig.VERSION_NAME} (Linux; Android ${Build.VERSION.RELEASE})")
			.setConnectTimeoutMs(15_000)
			.setReadTimeoutMs(30_000)
			.setAllowCrossProtocolRedirects(true)
			.setDefaultRequestProperties(headers)
		val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

		val extractorsFactory = DefaultExtractorsFactory()
			.setConstantBitrateSeekingEnabled(true)
			.setConstantBitrateSeekingAlwaysEnabled(true)

		val mediaSourceFactory = if (assHandler != null) {
			val parserFactory = AssSubtitleParserFactory(assHandler)
			DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory.withAssMkvSupport(parserFactory, assHandler)).apply {
				setSubtitleParserFactory(parserFactory)
			}
		} else {
			DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
		}

		val defaultRenderers = LumenRenderersFactory(context, subtitleDelay, audioDelay).apply {
			// Hardware/MediaCodec first (keeps passthrough + HDR), FFmpeg when MediaCodec can't decode.
			setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
			setEnableDecoderFallback(true)
		}
		val renderersFactory: RenderersFactory =
			if (assHandler != null) AssDelayRenderersFactory(assHandler, defaultRenderers, subtitleDelay)
			else defaultRenderers

		trackSelector.setParameters(
			trackSelector.buildUponParameters().apply {
				setTunnelingEnabled(prefs.tunneling)
				setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
				prefs.preferredAudioLanguage.takeIf { it.isNotBlank() }?.let { setPreferredAudioLanguage(it) }
				when (prefs.subtitleMode) {
					1 -> prefs.preferredSubtitleLanguage.takeIf { it.isNotBlank() }?.let { setPreferredTextLanguage(it) }
					2 -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
				}
			}
		)

		val loadControl = DefaultLoadControl.Builder()
			.setBufferDurationsMs(30_000, 60_000, 2_500, 5_000)
			.build()

		player = ExoPlayer.Builder(context)
			.setRenderersFactory(renderersFactory)
			.setMediaSourceFactory(mediaSourceFactory)
			.setTrackSelector(trackSelector)
			.setLoadControl(loadControl)
			.setAudioAttributes(
				AudioAttributes.Builder()
					.setUsage(C.USAGE_MEDIA)
					.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
					.build(),
				true,
			)
			.setHandleAudioBecomingNoisy(true)
			.build()

		assHandler?.init(player)
		player.addListener(PlayerListener())
		player.addAnalyticsListener(object : AnalyticsListener {
			override fun onVideoDecoderInitialized(
				eventTime: AnalyticsListener.EventTime,
				decoderName: String,
				initializedTimestampMs: Long,
				initializationDurationMs: Long,
			) {
				videoDecoder = decoderName
			}

			override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
				listener?.onEngineDroppedFrames(droppedFrames, elapsedMs)
			}

			override fun onAudioDecoderInitialized(
				eventTime: AnalyticsListener.EventTime,
				decoderName: String,
				initializedTimestampMs: Long,
				initializationDurationMs: Long,
			) {
				audioDecoder = decoderName
			}
		})
	}

	override fun attach(container: ViewGroup) {
		val view = PlayerView(context).apply {
			useController = false
			setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
			setShutterBackgroundColor(Color.BLACK)
			setKeepContentOnPlayerReset(true)
			isFocusable = false
			isFocusableInTouchMode = false
			player = this@ExoEngine.player
		}
		container.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
		assHandler?.let { handler -> view.subtitleView?.addView(AssSubtitleView(context, handler)) }
		playerView = view
		applySubtitleStyle()
	}

	override fun load(request: PlayRequest, subtitles: List<ExternalSubtitle>, startPositionMs: Long) {
		val subtitleConfigurations = subtitles.mapIndexed { index, sub ->
			MediaItem.SubtitleConfiguration.Builder(sub.uri)
				.setId("external-$index")
				.setMimeType(sub.mimeType)
				.setLanguage(sub.language)
				.setLabel(sub.name ?: "External ${index + 1}")
				.setSelectionFlags(
					(if (sub.enabled) C.SELECTION_FLAG_DEFAULT else 0) or (if (sub.forced) C.SELECTION_FLAG_FORCED else 0)
				)
				.build()
		}
		val mediaItem = MediaItem.Builder()
			.setUri(request.uri)
			.setSubtitleConfigurations(subtitleConfigurations)
			.setMediaMetadata(MediaMetadata.Builder().setTitle(request.displayTitle).build())
			.build()
		unsupportedReported = false
		readyOnce = false
		lastSeekAt = SystemClock.elapsedRealtime()
		player.setMediaItem(mediaItem, startPositionMs)
		player.prepare()
		player.playWhenReady = true
	}

	override fun play() {
		if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
		player.play()
	}

	override fun pause() = player.pause()

	override val isPlaying: Boolean get() = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
	override val isBuffering: Boolean get() = player.playbackState == Player.STATE_BUFFERING
	override val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED

	override fun seekTo(positionMs: Long) {
		lastSeekAt = SystemClock.elapsedRealtime()
		player.seekTo(positionMs.coerceAtLeast(0L))
	}
	override val positionMs: Long get() = player.currentPosition
	override val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
	override val bufferedMs: Long get() = player.bufferedPosition

	// Tracks -------------------------------------------------------------------------------------

	override fun audioTracks(): List<TrackOption> = tracksOf(C.TRACK_TYPE_AUDIO) { format ->
		listOfNotNull(
			Formatters.audioCodec(format.sampleMimeType, format.codecs),
			Formatters.channels(format.channelCount),
			format.label?.takeIf { Formatters.language(format.language) != null },
			"Default".takeIf { format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
		).joinToString("  ·  ")
	}

	override fun subtitleTracks(): List<TrackOption> = tracksOf(C.TRACK_TYPE_TEXT) { format ->
		val mime = if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType
		listOfNotNull(
			Formatters.subtitleCodec(mime),
			"External".takeIf { format.id?.contains("external") == true },
			"Forced".takeIf { format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 },
			"Default".takeIf { format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
			format.label?.takeIf { Formatters.language(format.language) != null },
		).joinToString("  ·  ")
	}

	private fun tracksOf(type: Int, detail: (Format) -> String): List<TrackOption> {
		val result = mutableListOf<TrackOption>()
		player.currentTracks.groups.forEachIndexed { groupIndex, group ->
			if (group.type != type) return@forEachIndexed
			for (i in 0 until group.length) {
				val format = group.getTrackFormat(i)
				val title = Formatters.language(format.language) ?: format.label ?: "Track ${result.size + 1}"
				result += TrackOption(
					id = "$groupIndex:$i",
					title = title,
					detail = detail(format),
					selected = group.isTrackSelected(i),
					supported = group.isTrackSupported(i),
				)
			}
		}
		return result
	}

	private fun findGroup(id: String): Pair<Tracks.Group, Int>? {
		val parts = id.split(':')
		val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return null
		val trackIndex = parts.getOrNull(1)?.toIntOrNull() ?: return null
		val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return null
		return group to trackIndex
	}

	override fun selectAudioTrack(id: String) {
		val (group, index) = findGroup(id) ?: return
		player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
			.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
			.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
			.build()
	}

	override fun selectSubtitleTrack(id: String?) {
		val builder = player.trackSelectionParameters.buildUpon()
		if (id == null) {
			builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
		} else {
			val (group, index) = findGroup(id) ?: return
			builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
				.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
		}
		player.trackSelectionParameters = builder.build()
	}

	// Sync, speed, scale, style ------------------------------------------------------------------

	override fun setSubtitleDelay(ms: Long) {
		subtitleDelay.us = ms * 1000L
	}

	override fun setAudioDelay(ms: Long) {
		audioDelay.us = ms * 1000L
	}

	override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

	override fun setScaleMode(mode: ScaleMode) {
		playerView?.resizeMode = when (mode) {
			ScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
			ScaleMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
			ScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
		}
	}

	override fun applySubtitleStyle() {
		val view: SubtitleView = playerView?.subtitleView ?: return
		val foreground = if (prefs.subtitleColor == 1) 0xFFFFE14D.toInt() else Color.WHITE
		val typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
		val style = when (prefs.subtitleStyle) {
			1 -> CaptionStyleCompat(foreground, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, typeface)
			2 -> CaptionStyleCompat(foreground, 0xB3000000.toInt(), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, typeface)
			else -> CaptionStyleCompat(foreground, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, typeface)
		}
		view.setStyle(style)
		view.setApplyEmbeddedStyles(true)
		view.setApplyEmbeddedFontSizes(false)
		view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * prefs.subtitleScale)
		view.setBottomPaddingFraction(prefs.subtitleBottom)
	}

	// Info -----------------------------------------------------------------------------------------

	override fun badges(): MediaBadges {
		val items = mutableListOf<String>()
		player.videoFormat?.let { video ->
			Formatters.resolution(video.width, video.height)?.let(items::add)
			val transfer = video.colorInfo?.colorTransfer
			when {
				video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || video.codecs?.startsWith("dv") == true -> items += "Dolby Vision"
				transfer == C.COLOR_TRANSFER_ST2084 -> items += "HDR10"
				transfer == C.COLOR_TRANSFER_HLG -> items += "HLG"
			}
		}
		player.audioFormat?.let { audio ->
			val codec = Formatters.audioCodec(audio.sampleMimeType, audio.codecs)
			val channels = Formatters.channels(audio.channelCount)
			listOfNotNull(codec, channels).joinToString(" ").takeIf { it.isNotBlank() }?.let(items::add)
		}
		return MediaBadges(items)
	}

	override fun stats(): List<Pair<String, String>> {
		val out = mutableListOf<Pair<String, String>>()
		out += "Engine" to "ExoPlayer (Media3)"
		player.videoFormat?.let { v ->
			out += "Video" to listOfNotNull(
				Formatters.videoCodec(v.sampleMimeType),
				"${v.width}×${v.height}",
				Formatters.fps(v.frameRate),
				Formatters.bitrate(v.bitrate.takeIf { it > 0 } ?: v.averageBitrate),
			).joinToString("  ")
			out += "Video decoder" to describeDecoder(videoDecoder)
		}
		player.videoDecoderCounters?.let { counters ->
			out += "Frames" to "dropped ${counters.droppedBufferCount} · rendered ${counters.renderedOutputBufferCount}"
		}
		player.audioFormat?.let { a ->
			out += "Audio" to listOfNotNull(
				Formatters.audioCodec(a.sampleMimeType, a.codecs),
				Formatters.channels(a.channelCount),
				a.sampleRate.takeIf { it > 0 }?.let { "${it / 1000.0} kHz" },
				Formatters.language(a.language),
			).joinToString("  ")
			out += "Audio output" to describeAudioPath(audioDecoder)
		}
		out += "Buffer" to "${((player.bufferedPosition - player.currentPosition).coerceAtLeast(0L) / 1000L)} s ahead"
		out += "Speed" to "${player.playbackParameters.speed}×"
		return out
	}

	private fun describeDecoder(name: String?): String = when {
		name == null -> "—"
		name.contains("ffmpeg", ignoreCase = true) -> "$name (software)"
		name.startsWith("c2.android") || name.startsWith("OMX.google") -> "$name (software)"
		else -> "$name (hardware)"
	}

	private fun describeAudioPath(name: String?): String = when {
		name == null -> "—"
		name.contains(".raw", ignoreCase = true) -> "Passthrough / bitstream ($name)"
		name.contains("ffmpeg", ignoreCase = true) -> "Decoded by FFmpeg → PCM"
		else -> "Decoded → PCM ($name)"
	}

	override fun frameCounters(): FrameCounters? {
		val counters = player.videoDecoderCounters ?: return null
		counters.ensureUpdated()
		return FrameCounters(counters.droppedBufferCount.toLong(), counters.renderedOutputBufferCount.toLong())
	}

	override fun release() {
		player.release()
		playerView?.player = null
		runCatching { assHandler?.release() }
	}

	private inner class PlayerListener : Player.Listener {
		override fun onPlaybackStateChanged(playbackState: Int) {
			if (playbackState == Player.STATE_READY) readyOnce = true
			if (playbackState == Player.STATE_BUFFERING && readyOnce && player.playWhenReady &&
				SystemClock.elapsedRealtime() - lastSeekAt > 3_000L
			) {
				listener?.onEngineRebuffer()
			}
			if (playbackState == Player.STATE_ENDED) listener?.onEngineEnded()
			listener?.onEngineStateChanged()
		}

		override fun onIsPlayingChanged(isPlaying: Boolean) {
			listener?.onEngineStateChanged()
		}

		override fun onPlayerError(error: PlaybackException) {
			Log.e("ExoEngine", "Playback error ${error.errorCodeName}", error)
			val canFallback = error.errorCode in FALLBACK_ERRORS
			listener?.onEngineError(describeError(error), canFallback)
		}

		override fun onTracksChanged(tracks: Tracks) {
			if (!unsupportedReported && !tracks.isEmpty()) {
				val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
				val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
				val videoUnsupported = video.isNotEmpty() && video.none { it.isSupported() }
				val audioUnsupported = audio.isNotEmpty() && audio.none { it.isSupported() }
				if (videoUnsupported || audioUnsupported) {
					unsupportedReported = true
					val what = if (videoUnsupported) "video" else "audio"
					listener?.onEngineError("This $what format isn't supported by the hardware decoders.", true)
					return
				}
			}
			listener?.onEngineTracksChanged()
			checkFrameRate()
		}

		override fun onRenderedFirstFrame() {
			listener?.onEngineFirstFrame()
			checkFrameRate()
		}
	}

	private fun checkFrameRate() {
		val fps = player.videoFormat?.frameRate ?: return
		if (fps > 0f && fps != lastFrameRate) {
			lastFrameRate = fps
			listener?.onEngineVideoFrameRate(fps)
		}
	}

	private fun describeError(error: PlaybackException): String = when (error.errorCode) {
		PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
		PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the server. Check your network connection."
		PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the stream (${error.cause?.message ?: "HTTP error"})."
		PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file could not be found."
		PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This container format isn't supported by ExoPlayer."
		PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
		PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
		PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode this format in hardware."
		else -> "Playback failed (${error.errorCodeName})."
	}

	private companion object {
		val FALLBACK_ERRORS = setOf(
			PlaybackException.ERROR_CODE_UNSPECIFIED,
			PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
			PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
			PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
			PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
			PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
			PlaybackException.ERROR_CODE_DECODING_FAILED,
			PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
			PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
			PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
		)
	}
}
