package app.lumen.player.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import app.lumen.player.util.Ui

/**
 * Buttons side by side that continue on a new line when they don't fit (instead of running off screen).
 * A child whose [LayoutParams.newLine] is set always starts a new line.
 */
class FlowRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

	/** Space between lines. */
	var lineSpacing: Int = Ui.dp(context, 14)

	class LayoutParams : ViewGroup.MarginLayoutParams {
		var newLine = false
		constructor(width: Int, height: Int) : super(width, height)
		constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
		constructor(source: ViewGroup.LayoutParams) : super(source)
	}

	override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams = LayoutParams(context, attrs)
	override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
		LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams = LayoutParams(p)
	override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val widthMode = MeasureSpec.getMode(widthMeasureSpec)
		val available = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
		var lineWidth = 0
		var lineHeight = 0
		var widest = 0
		var height = 0
		var lines = 0
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (child.visibility == View.GONE) continue
			val lp = child.layoutParams as LayoutParams
			measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
			val w = child.measuredWidth + lp.leftMargin + lp.rightMargin
			val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
			if (lineWidth > 0 && (lp.newLine || lineWidth + w > available)) {
				widest = maxOf(widest, lineWidth)
				height += lineHeight + lineSpacing
				lineWidth = 0
				lineHeight = 0
			}
			if (lineWidth == 0) lines++
			lineWidth += w
			lineHeight = maxOf(lineHeight, h)
		}
		widest = maxOf(widest, lineWidth)
		if (lines > 0) height += lineHeight
		val wantedWidth = widest + paddingLeft + paddingRight
		val finalWidth = when (widthMode) {
			MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
			MeasureSpec.AT_MOST -> minOf(wantedWidth, MeasureSpec.getSize(widthMeasureSpec))
			else -> wantedWidth
		}
		setMeasuredDimension(finalWidth, resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
		val available = r - l - paddingLeft - paddingRight
		var x = paddingLeft
		var y = paddingTop
		var lineHeight = 0
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (child.visibility == View.GONE) continue
			val lp = child.layoutParams as LayoutParams
			val w = child.measuredWidth + lp.leftMargin + lp.rightMargin
			val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
			if (x > paddingLeft && (lp.newLine || x - paddingLeft + w > available)) {
				x = paddingLeft
				y += lineHeight + lineSpacing
				lineHeight = 0
			}
			child.layout(x + lp.leftMargin, y + lp.topMargin, x + lp.leftMargin + child.measuredWidth, y + lp.topMargin + child.measuredHeight)
			x += w
			lineHeight = maxOf(lineHeight, h)
		}
	}
}
