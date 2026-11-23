package app.lumen.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat
import app.lumen.player.R

/**
 * D-pad friendly seek bar: rounded track, buffered range, gradient progress, chapter gaps and
 * highlighted intro/credits segments. Grows and shows a thumb when focused.
 */
class TimelineView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	interface Listener {
		fun onScrub(direction: Int, repeatCount: Int)
		fun onScrubConfirm()
	}

	var listener: Listener? = null

	var durationMs = 0L
		set(value) { field = value; invalidate() }
	var positionMs = 0L
		set(value) { field = value; invalidate() }
	var bufferedMs = 0L
		set(value) { field = value; invalidate() }
	/** When non-null, the thumb shows this scrub target instead of the play position. */
	var scrubPositionMs: Long? = null
		set(value) { field = value; invalidate() }
	var chapterMarksMs: List<Long> = emptyList()
		set(value) { field = value; invalidate() }
	var segmentsMs: List<Pair<Long, Long>> = emptyList()
		set(value) { field = value; invalidate() }

	private val density = resources.displayMetrics.density
	private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.lumen_track) }
	private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.lumen_buffer) }
	private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.lumen_segment) }
	private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
	private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.lumen_focus) }
	private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = 0x5500A4DC
		style = Paint.Style.FILL
	}
	private val rect = RectF()
	private val accent = ContextCompat.getColor(context, R.color.lumen_accent)
	private val accent2 = ContextCompat.getColor(context, R.color.lumen_accent_2)

	init {
		isFocusable = true
		isFocusableInTouchMode = true
		minimumHeight = (28 * density).toInt()
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		progressPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, accent2, accent, Shader.TileMode.CLAMP)
	}

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
		invalidate()
	}

	private fun fraction(ms: Long): Float =
		if (durationMs <= 0) 0f else (ms.toFloat() / durationMs).coerceIn(0f, 1f)

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val focused = isFocused
		val trackHeight = (if (focused) 8f else 4f) * density
		val thumbRadius = 9f * density
		val left = paddingLeft + thumbRadius
		val right = width - paddingRight - thumbRadius
		val span = (right - left).coerceAtLeast(1f)
		val cy = height / 2f
		val top = cy - trackHeight / 2f
		val bottom = cy + trackHeight / 2f
		val radius = trackHeight / 2f

		rect.set(left, top, right, bottom)
		canvas.drawRoundRect(rect, radius, radius, trackPaint)

		// Intro / credits segments
		for ((start, end) in segmentsMs) {
			rect.set(left + span * fraction(start), top, left + span * fraction(end), bottom)
			canvas.drawRect(rect, segmentPaint)
		}

		rect.set(left, top, left + span * fraction(bufferedMs), bottom)
		canvas.drawRoundRect(rect, radius, radius, bufferPaint)

		val shown = scrubPositionMs ?: positionMs
		rect.set(left, top, left + span * fraction(positionMs), bottom)
		canvas.drawRoundRect(rect, radius, radius, progressPaint)

		// Chapter gaps
		val gap = 2f * density
		for (mark in chapterMarksMs) {
			if (mark <= 0 || mark >= durationMs) continue
			val x = left + span * fraction(mark)
			rect.set(x - gap / 2f, top - 1f, x + gap / 2f, bottom + 1f)
			canvas.drawRect(rect, gapPaint)
		}

		if (focused) {
			val x = left + span * fraction(shown)
			if (scrubPositionMs != null) canvas.drawCircle(x, cy, thumbRadius * 1.9f, thumbRingPaint)
			canvas.drawCircle(x, cy, thumbRadius, thumbPaint)
		}
	}

	/** X position (relative to this view) of the given time; used to place the preview bubble. */
	fun xFor(ms: Long): Float {
		val thumbRadius = 9f * density
		val left = paddingLeft + thumbRadius
		val right = width - paddingRight - thumbRadius
		return left + (right - left) * fraction(ms)
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
		when (keyCode) {
			KeyEvent.KEYCODE_DPAD_LEFT -> {
				listener?.onScrub(-1, event.repeatCount)
				return true
			}
			KeyEvent.KEYCODE_DPAD_RIGHT -> {
				listener?.onScrub(1, event.repeatCount)
				return true
			}
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
				listener?.onScrubConfirm()
				return true
			}
		}
		return super.onKeyDown(keyCode, event)
	}
}
