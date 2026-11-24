package app.lumen.player.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import app.lumen.player.R

/** Layout + typography helpers shared by all screens. */
object Ui {
	private var display: Typeface? = null
	private var medium: Typeface? = null
	private var bold: Typeface? = null

	fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
	fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

	/** Heading face: Fraunces Black Italic in the amber theme, clean bold sans in Glass / purple. */
	fun display(context: Context): Typeface {
		if (Themes.current != Themes.AMBER) return bold(context)
		return display ?: (runCatching { ResourcesCompat.getFont(context, R.font.fraunces_black_italic) }.getOrNull()
			?: Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)).also { display = it }
	}

	/** A palette color of the current theme (see res/values/attrs.xml). */
	fun color(context: Context, attr: Int): Int {
		val value = TypedValue()
		if (!context.theme.resolveAttribute(attr, value, true)) return 0xFFFF00FF.toInt()
		return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(context, value.resourceId) else value.data
	}

	fun colorList(context: Context, attr: Int): ColorStateList = ColorStateList.valueOf(color(context, attr))

	fun medium(context: Context): Typeface =
		medium ?: (runCatching { ResourcesCompat.getFont(context, R.font.dmsans_medium) }.getOrNull()
			?: Typeface.create("sans-serif-medium", Typeface.NORMAL)).also { medium = it }

	fun bold(context: Context): Typeface =
		bold ?: (runCatching { ResourcesCompat.getFont(context, R.font.dmsans_bold) }.getOrNull()
			?: Typeface.DEFAULT_BOLD).also { bold = it }

	fun TextView.displayFont(): TextView = apply { typeface = display(context) }

	/** Poster card width in dp for the chosen size. */
	fun posterWidthDp(prefs: Prefs): Int = when (prefs.posterSize) {
		0 -> 112
		2 -> 176
		3 -> 214
		else -> 142
	}

	/** Landscape (16:9) card width in dp. */
	fun thumbWidthDp(prefs: Prefs): Int = when (prefs.thumbSize) {
		0 -> 220
		2 -> 340
		else -> 272
	}

	/** Sidebar / button icon size in dp. */
	fun iconDp(prefs: Prefs): Int = when (prefs.iconSize) {
		0 -> 17
		2 -> 24
		3 -> 30
		else -> 20
	}

	/** Round player control button size in dp. */
	fun playerButtonDp(prefs: Prefs): Int = when (prefs.iconSize) {
		0 -> 38
		2 -> 50
		3 -> 60
		else -> 44
	}

	/** Grow a little when focused, like the Li'Server cards. */
	fun focusScale(view: View, scale: Float = 1.06f, onFocus: ((Boolean) -> Unit)? = null) {
		view.setOnFocusChangeListener { v, hasFocus ->
			v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f).setDuration(140L).start()
			v.elevation = if (hasFocus) dp(v.context, 8).toFloat() else 0f
			onFocus?.invoke(hasFocus)
		}
	}
}
