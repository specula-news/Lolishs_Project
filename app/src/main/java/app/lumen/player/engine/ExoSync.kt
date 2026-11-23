package app.lumen.player.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.NoSampleRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.peerless2012.ass.media.AssHandler

/**
 * Media3 has no built-in subtitle / audio offset, so these small wrappers add one.
 *
 * Subtitles: the text renderer is asked to render at (position - delay), so a positive delay shows
 * subtitles later. Audio: the audio clock (which drives video) is shifted by the delay, so a positive
 * delay makes audio play later than the picture.
 */
class DelayHolder {
	@Volatile
	var us: Long = 0L
}

@OptIn(UnstableApi::class)
class DelayedTextRenderer(renderer: Renderer, private val delay: DelayHolder) : ForwardingRenderer(renderer) {
	override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
		try {
			super.render(positionUs - delay.us, elapsedRealtimeUs)
		} catch (e: IllegalStateException) {
			// A delay change can momentarily move the subtitle clock backwards; skip that frame.
			Log.w("DelayedTextRenderer", "Ignoring subtitle render glitch", e)
		}
	}
}

@OptIn(UnstableApi::class)
class DelayedAudioSink(sink: AudioSink, private val delay: DelayHolder) : ForwardingAudioSink(sink) {
	override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
		val position = super.getCurrentPositionUs(sourceEnded)
		if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
		return (position + delay.us).coerceAtLeast(0L)
	}
}

/** Default renderers + FFmpeg extension, with delayed text renderers and audio sink. */
@OptIn(UnstableApi::class)
class LumenRenderersFactory(
	context: Context,
	private val subtitleDelay: DelayHolder,
	private val audioDelay: DelayHolder,
) : DefaultRenderersFactory(context) {

	override fun buildTextRenderers(
		context: Context,
		output: TextOutput,
		outputLooper: Looper,
		extensionRendererMode: Int,
		out: ArrayList<Renderer>,
	) {
		val first = out.size
		super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
		for (i in first until out.size) {
			out[i] = DelayedTextRenderer(out[i], subtitleDelay)
		}
	}

	override fun buildAudioSink(
		context: Context,
		enableFloatOutput: Boolean,
		enableAudioOutputPlaybackParams: Boolean,
	): AudioSink? {
		val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams) ?: return null
		return DelayedAudioSink(sink, audioDelay)
	}
}

/**
 * Drives libass (ASS/SSA rendered by ass-media) with the subtitle delay applied. This replaces
 * ass-media's own AssRenderersFactory, whose renderer feeds the unshifted video time.
 */
@OptIn(UnstableApi::class)
class DelayedAssRenderer(
	private val assHandler: AssHandler,
	private val delay: DelayHolder,
) : NoSampleRenderer() {
	override fun getName(): String = "DelayedAssRenderer"

	private companion object {
		/** Media3's MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US (package-private). */
		const val RENDERER_OFFSET_US = 1_000_000_000_000L
	}

	override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
		assHandler.videoTime = positionUs - RENDERER_OFFSET_US - delay.us
	}
}

@OptIn(UnstableApi::class)
class AssDelayRenderersFactory(
	private val assHandler: AssHandler,
	private val base: RenderersFactory,
	private val delay: DelayHolder,
) : RenderersFactory {
	override fun createRenderers(
		eventHandler: Handler,
		videoRendererEventListener: VideoRendererEventListener,
		audioRendererEventListener: AudioRendererEventListener,
		textRendererOutput: TextOutput,
		metadataRendererOutput: MetadataOutput,
	): Array<Renderer> {
		val renderers = base.createRenderers(
			eventHandler,
			videoRendererEventListener,
			audioRendererEventListener,
			textRendererOutput,
			metadataRendererOutput,
		)
		return (renderers.toMutableList() + DelayedAssRenderer(assHandler, delay)).toTypedArray()
	}

	override fun createSecondaryRenderer(
		renderer: Renderer,
		eventHandler: Handler,
		videoRendererEventListener: VideoRendererEventListener,
		audioRendererEventListener: AudioRendererEventListener,
		textRendererOutput: TextOutput,
		metadataRendererOutput: MetadataOutput,
	): Renderer? = base.createSecondaryRenderer(
		renderer,
		eventHandler,
		videoRendererEventListener,
		audioRendererEventListener,
		textRendererOutput,
		metadataRendererOutput,
	)
}
