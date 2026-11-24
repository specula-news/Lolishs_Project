package app.lumen.player.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.lumen.player.R
import app.lumen.player.util.Ui

/** Rounded Li'Server buttons with an optional icon. */
object Pills {
	fun create(parent: ViewGroup, text: String, icon: Int? = null, attach: Boolean = true): TextView {
		val context = parent.context
		val pill = LayoutInflater.from(context).inflate(R.layout.item_pill, parent, false) as TextView
		pill.text = text
		pill.typeface = Ui.bold(context)
		setIcon(pill, icon)
		Ui.focusScale(pill, 1.05f)
		if (attach) parent.addView(pill)
		return pill
	}

	/** A smaller pill for secondary buttons, so a row of them fits on screen. */
	fun compact(pill: TextView): TextView {
		val context = pill.context
		pill.layoutParams = pill.layoutParams.apply { height = Ui.dp(context, 36) }
		pill.setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
		pill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
		pill.compoundDrawablesRelative[0]?.let { icon ->
			val size = Ui.dp(context, 16)
			icon.setBounds(0, 0, size, size)
			pill.setCompoundDrawablesRelative(icon, null, null, null)
			pill.compoundDrawablePadding = Ui.dp(context, 8)
		}
		return pill
	}

	fun setIcon(pill: TextView, icon: Int?) {
		val context = pill.context
		if (icon == null) {
			pill.setCompoundDrawablesRelative(null, null, null, null)
			return
		}
		val size = Ui.dp(context, 18)
		val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply { setBounds(0, 0, size, size) }
		pill.setCompoundDrawablesRelative(drawable, null, null, null)
		pill.compoundDrawablePadding = Ui.dp(context, 9)
		pill.compoundDrawableTintList = ContextCompat.getColorStateList(context, R.color.pill_text)
	}
}
