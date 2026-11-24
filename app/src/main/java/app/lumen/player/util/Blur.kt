package app.lumen.player.util

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cheap frosted-glass blur that works on every Android version (no RenderEffect on Android 11):
 * shrink the artwork to a few dozen pixels, box-blur it, and let the ImageView's bilinear
 * scaling stretch it back up. Costs well under a millisecond.
 */
object Blur {
	fun soft(source: Bitmap, width: Int = 72, radius: Int = 2, passes: Int = 3): Bitmap {
		val w = width.coerceAtMost(source.width).coerceAtLeast(8)
		val h = max(8, (source.height * w / source.width.toFloat()).roundToInt())
		val small = Bitmap.createScaledBitmap(source, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
		val pixels = IntArray(w * h)
		small.getPixels(pixels, 0, w, 0, 0, w, h)
		val buffer = IntArray(w * h)
		repeat(passes) {
			boxBlur(pixels, buffer, w, h, radius, horizontal = true)
			boxBlur(buffer, pixels, w, h, radius, horizontal = false)
		}
		small.setPixels(pixels, 0, w, 0, 0, w, h)
		return small
	}

	private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
		val outer = if (horizontal) h else w
		val inner = if (horizontal) w else h
		for (o in 0 until outer) {
			for (i in 0 until inner) {
				var a = 0; var red = 0; var g = 0; var b = 0; var n = 0
				for (k in -r..r) {
					val j = (i + k).coerceIn(0, inner - 1)
					val c = if (horizontal) src[o * w + j] else src[j * w + o]
					a += c ushr 24; red += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
				}
				val index = if (horizontal) o * w + i else i * w + o
				dst[index] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
			}
		}
	}
}
