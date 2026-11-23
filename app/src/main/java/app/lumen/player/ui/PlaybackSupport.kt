package app.lumen.player.ui

import android.util.Log
import app.lumen.player.engine.FrameCounters
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Background scope that outlives the player screen, so "stopped" reports still reach the server. */
object AppScope {
	val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Tells Jellyfin what is playing, so resume points, watched status and the dashboard stay correct. */
class PlaybackReporter(
	private val api: JellyfinApi,
	private val session: Session,
	val itemId: String,
	private val mediaSourceId: String?,
	val playSessionId: String,
	private val playMethod: String,
) {
	var started = false
		private set
	private var stopped = false

	private fun body(positionMs: Long, paused: Boolean, event: String? = null): JSONObject =
		JSONObject()
			.put("ItemId", itemId)
			.put("MediaSourceId", mediaSourceId ?: itemId)
			.put("PlaySessionId", playSessionId)
			.put("PositionTicks", positionMs.coerceAtLeast(0L) * 10_000L)
			.put("IsPaused", paused)
			.put("IsMuted", false)
			.put("CanSeek", true)
			.put("PlayMethod", playMethod)
			.apply { event?.let { put("EventName", it) } }

	private fun send(kind: String, info: JSONObject) {
		AppScope.io.launch {
			runCatching { api.reportPlayback(session, kind, info) }
				.onFailure { Log.w("PlaybackReporter", "Report $kind failed: ${it.message}") }
		}
	}

	fun start(positionMs: Long) {
		if (started || stopped) return
		started = true
		send("start", body(positionMs, false))
	}

	fun progress(positionMs: Long, paused: Boolean, event: String = "timeupdate") {
		if (!started || stopped) return
		send("progress", body(positionMs, paused, event))
	}

	fun stop(positionMs: Long) {
		if (stopped) return
		stopped = true
		if (!started) send("start", body(positionMs, true))
		send("stop", body(positionMs, false))
	}
}

/**
 * Notices stutter from what the engine actually measures, sampled about once a second:
 *  - network: the video stops to buffer again and again, or one stall lasts long;
 *  - decoding: video frames are dropped (ExoPlayer decoder counters / VLC "lost pictures").
 * "Normal" waits for clear problems; "Sensitive" reacts to small hiccups.
 */
class StutterMonitor {
	enum class Kind { NETWORK, DECODING }

	private class Sample(val time: Long, val dropped: Long, val rendered: Long)

	private val rebuffers = ArrayDeque<Long>()
	private val samples = ArrayDeque<Sample>()
	private var stallStartedAt = 0L

	/** For the stats overlay. */
	var lastSummary = ""
		private set

	fun rebuffer(now: Long) = rebuffers.addLast(now)

	/** [buffering] = the player is waiting for data after playback had started. */
	fun sample(now: Long, counters: FrameCounters?, buffering: Boolean) {
		if (buffering) {
			if (stallStartedAt == 0L) stallStartedAt = now
		} else {
			stallStartedAt = 0L
		}
		if (counters == null) return
		val last = samples.lastOrNull()
		// A new decoder (track change, engine switch) starts counting from zero again.
		if (last != null && (counters.dropped < last.dropped || counters.rendered < last.rendered)) samples.clear()
		samples.addLast(Sample(now, counters.dropped, counters.rendered))
		while (samples.size > 2 && now - samples.first().time > 30_000L) samples.removeFirst()
	}

	/** Forget frame history (after a seek the decoder legitimately catches up). */
	fun resetFrames() = samples.clear()

	fun reset() {
		rebuffers.clear()
		samples.clear()
		stallStartedAt = 0L
	}

	/** (dropped, shown) during the last [windowMs]. */
	private fun window(now: Long, windowMs: Long): Pair<Long, Long> {
		val last = samples.lastOrNull() ?: return 0L to 0L
		val first = samples.firstOrNull { now - it.time <= windowMs } ?: return 0L to 0L
		return (last.dropped - first.dropped) to (last.rendered - first.rendered)
	}

	fun check(now: Long, sensitivity: Int): Kind? {
		while (rebuffers.isNotEmpty() && now - rebuffers.first() > 120_000L) rebuffers.removeFirst()
		val stallMs = if (stallStartedAt > 0L) now - stallStartedAt else 0L
		val (dropped, shown) = window(now, 10_000L)
		val total = dropped + shown
		val percent = if (total > 0) dropped * 100.0 / total else 0.0
		lastSummary = "${dropped} dropped / 10 s (${"%.1f".format(java.util.Locale.US, percent)}%) · ${rebuffers.size} stalls / 2 min"
		val sensitive = sensitivity >= 2
		val network = if (sensitive) rebuffers.size >= 1 || stallMs >= 2_500L else rebuffers.size >= 2 || stallMs >= 6_000L
		val decoding = if (sensitive) dropped >= 5 else dropped >= 15 && percent >= 3.0
		return when {
			network -> Kind.NETWORK
			decoding -> Kind.DECODING
			else -> null
		}
	}
}
