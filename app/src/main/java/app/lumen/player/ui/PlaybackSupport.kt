package app.lumen.player.ui

import android.util.Log
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
 * Notices stutter: repeated buffering (connection too slow for the file) or bursts of dropped
 * frames (decoder struggling).
 */
class StutterMonitor {
	enum class Kind { NETWORK, DECODING }

	private val rebuffers = ArrayDeque<Long>()
	private val drops = ArrayDeque<Long>()

	fun rebuffer(now: Long) = rebuffers.addLast(now)

	/** ExoPlayer reports drops in batches (about 50 frames) with the time they took. */
	fun dropped(now: Long, count: Int, elapsedMs: Long, sensitivity: Int) {
		val window = if (sensitivity >= 2) 120_000L else 30_000L
		if (count >= 20 && elapsedMs in 1..window) drops.addLast(now)
	}

	fun reset() {
		rebuffers.clear()
		drops.clear()
	}

	fun check(now: Long, sensitivity: Int): Kind? {
		while (rebuffers.isNotEmpty() && now - rebuffers.first() > 120_000L) rebuffers.removeFirst()
		while (drops.isNotEmpty() && now - drops.first() > 60_000L) drops.removeFirst()
		val rebufferLimit = if (sensitivity >= 2) 1 else 2
		return when {
			rebuffers.size >= rebufferLimit -> Kind.NETWORK
			drops.isNotEmpty() -> Kind.DECODING
			else -> null
		}
	}
}
