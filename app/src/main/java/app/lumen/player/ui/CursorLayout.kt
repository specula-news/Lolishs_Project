package app.lumen.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import app.lumen.player.R

/**
 * Turns the remote into a mouse for web pages: arrows move a pointer, OK clicks, and pushing
 * against an edge scrolls whatever is under the pointer.
 */
class CursorLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

	var cursorEnabled = true
		set(value) {
			field = value
			invalidate()
		}

	private val density = resources.displayMetrics.density
	private var cursorX = -1f
	private var cursorY = -1f
	private var downTime = 0L
	private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 3f * density
		color = ContextCompat.getColor(context, R.color.lumen_accent)
	}
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = 0x55F0A63A
	}
	private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = ContextCompat.getColor(context, R.color.lumen_text)
	}

	init {
		setWillNotDraw(false)
		isFocusable = true
		descendantFocusability = FOCUS_BEFORE_DESCENDANTS
	}

	private val target: View? get() = if (childCount > 0) getChildAt(0) else null

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (cursorX < 0) {
			cursorX = w / 2f
			cursorY = h / 2f
		}
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (!cursorEnabled) return super.dispatchKeyEvent(event)
		val dx = when (event.keyCode) {
			KeyEvent.KEYCODE_DPAD_LEFT -> -1
			KeyEvent.KEYCODE_DPAD_RIGHT -> 1
			else -> 0
		}
		val dy = when (event.keyCode) {
			KeyEvent.KEYCODE_DPAD_UP -> -1
			KeyEvent.KEYCODE_DPAD_DOWN -> 1
			else -> 0
		}
		if (dx != 0 || dy != 0) {
			if (event.action == KeyEvent.ACTION_DOWN) move(dx, dy, event.repeatCount)
			return true
		}
		when (event.keyCode) {
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
				if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) touch(MotionEvent.ACTION_DOWN)
				if (event.action == KeyEvent.ACTION_UP) touch(MotionEvent.ACTION_UP)
				return true
			}
		}
		return super.dispatchKeyEvent(event)
	}

	private fun move(dx: Int, dy: Int, repeat: Int) {
		val step = (10f + (repeat.coerceAtMost(20) * 2.4f)) * density
		val margin = 6f * density
		val atLeft = dx < 0 && cursorX <= margin
		val atRight = dx > 0 && cursorX >= width - margin
		val atTop = dy < 0 && cursorY <= margin
		val atBottom = dy > 0 && cursorY >= height - margin
		if (atTop || atBottom || atLeft || atRight) {
			// Push against the edge: scroll the page instead of moving the pointer.
			scroll(if (atLeft || atRight) -dx.toFloat() else 0f, if (atTop || atBottom) -dy.toFloat() else 0f)
			return
		}
		cursorX = (cursorX + dx * step).coerceIn(margin, width - margin)
		cursorY = (cursorY + dy * step).coerceIn(margin, height - margin)
		hover()
		invalidate()
	}

	private fun pointerEvent(action: Int, hScroll: Float = 0f, vScroll: Float = 0f): MotionEvent {
		val now = SystemClock.uptimeMillis()
		val properties = arrayOf(MotionEvent.PointerProperties().apply {
			id = 0
			toolType = MotionEvent.TOOL_TYPE_MOUSE
		})
		val coords = arrayOf(MotionEvent.PointerCoords().apply {
			x = cursorX
			y = cursorY
			pressure = 1f
			size = 1f
			setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
			setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
		})
		return MotionEvent.obtain(now, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
	}

	private fun hover() {
		val view = target ?: return
		val event = pointerEvent(MotionEvent.ACTION_HOVER_MOVE)
		view.dispatchGenericMotionEvent(event)
		event.recycle()
	}

	private fun scroll(h: Float, v: Float) {
		val view = target ?: return
		val event = pointerEvent(MotionEvent.ACTION_SCROLL, h * 2f, v * 2f)
		view.dispatchGenericMotionEvent(event)
		event.recycle()
	}

	private fun touch(action: Int) {
		val view = target ?: return
		val now = SystemClock.uptimeMillis()
		if (action == MotionEvent.ACTION_DOWN) downTime = now
		val event = MotionEvent.obtain(downTime, now, action, cursorX, cursorY, 0)
		event.source = InputDevice.SOURCE_TOUCHSCREEN
		view.dispatchTouchEvent(event)
		event.recycle()
		invalidate()
	}

	override fun dispatchDraw(canvas: Canvas) {
		super.dispatchDraw(canvas)
		if (!cursorEnabled || cursorX < 0) return
		val radius = 14f * density
		canvas.drawCircle(cursorX, cursorY, radius, fill)
		canvas.drawCircle(cursorX, cursorY, radius, ring)
		canvas.drawCircle(cursorX, cursorY, 3.5f * density, dot)
	}
}
