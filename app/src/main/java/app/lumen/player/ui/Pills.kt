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

	fun setIcon(pill: TextView, icon: Int?) {
		val context = pill.context
		if (icon == null) {
			pill.setCompoundDrawablesRelative(null, null, null, null)
			return
		}
		val size = Ui.dp(context, 20)
		val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply { setBounds(0, 0, size, size) }
		pill.setCompoundDrawablesRelative(drawable, null, null, null)
		pill.compoundDrawablePadding = Ui.dp(context, 10)
		pill.compoundDrawableTintList = ContextCompat.getColorStateList(context, R.color.pill_text)
	}
}
