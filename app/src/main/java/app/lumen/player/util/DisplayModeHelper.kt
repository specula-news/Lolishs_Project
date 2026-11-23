package app.lumen.player.util

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the HDMI output refresh rate to match the video (e.g. 23.976 Hz for film), the same way
 * Kodi and the Jellyfin app do on the Shield. Only modes with the current resolution are considered,
 * so the TV never changes resolution - only refresh rate.
 */
object DisplayModeHelper {
	private const val TAG = "DisplayModeHelper"

	/** Returns true if a different display mode was requested. */
	fun matchFrameRate(activity: Activity, fps: Float): Boolean {
		if (fps < 10f || fps > 121f) return false
		val display = currentDisplay(activity) ?: return false
		val current = display.mode
		val candidates = display.supportedModes.filter {
			it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
		}

		var best: Display.Mode? = null
		var bestScore = Float.MAX_VALUE
		for (mode in candidates) {
			val ratio = mode.refreshRate / fps
			val multiple = ratio.roundToInt()
			if (multiple < 1 || multiple > 3) continue
			val error = abs(ratio - multiple) / multiple
			// 0.15% tolerance lets 24 Hz stand in for 23.976 fps when no exact mode exists.
			if (error > 0.0015f) continue
			val score = error * 1000f + (multiple - 1) * 0.5f
			if (score < bestScore) {
				bestScore = score
				best = mode
			}
		}

		val target = best ?: return false
		if (target.modeId == current.modeId) return false
		Log.i(TAG, "Switching display mode to ${target.refreshRate} Hz for $fps fps")
		val attrs = activity.window.attributes
		attrs.preferredDisplayModeId = target.modeId
		activity.window.attributes = attrs
		return true
	}

	fun reset(activity: Activity) {
		val attrs = activity.window.attributes
		if (attrs.preferredDisplayModeId != 0) {
			attrs.preferredDisplayModeId = 0
			activity.window.attributes = attrs
		}
	}

	@Suppress("DEPRECATION")
	private fun currentDisplay(activity: Activity): Display? =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
		else activity.windowManager.defaultDisplay
}
