package app.lumen.player.ui

import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.lumen.player.R
import app.lumen.player.util.Ui
import java.util.Locale

/** Builds the TV-style list rows used by the player side panel and the settings screen. */
class PanelBuilder(private val container: LinearLayout) {
	private val context = container.context
	private val inflater = LayoutInflater.from(context)
	private val density = context.resources.displayMetrics.density

	fun clear() = container.removeAllViews()

	fun header(text: String) {
		val view = TextView(context).apply {
			this.text = text.uppercase(Locale.getDefault())
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			letterSpacing = 0.12f
			typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
			setTextColor(Ui.color(context, R.attr.lumenText3))
			setPadding((14 * density).toInt(), if (container.childCount == 0) 0 else (18 * density).toInt(), 0, (8 * density).toInt())
		}
		container.addView(view)
	}

	fun note(text: String) {
		val view = TextView(context).apply {
			this.text = text
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setTextColor(Ui.color(context, R.attr.lumenText2))
			setLineSpacing(0f, 1.25f)
			setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
		}
		container.addView(view)
	}

	/** Radio-style choice with a check mark when selected. */
	fun option(title: String, detail: String? = null, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit): View {
		val row = row(title, detail)
		row.findViewById<ImageView>(R.id.row_check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
		row.isEnabled = enabled
		row.alpha = if (enabled) 1f else 0.5f
		row.tag = if (selected) TAG_SELECTED else null
		row.setOnClickListener { onClick() }
		return row
	}

	fun action(title: String, detail: String? = null, value: String? = null, onClick: () -> Unit): View {
		val row = row(title, detail)
		value?.let { setValue(row, it) }
		row.setOnClickListener { onClick() }
		return row
	}

	/**
	 * A value adjusted with LEFT / RIGHT on the remote (centre also steps forward).
	 * [onStep] receives the direction (-1 / +1) and the key repeat count for acceleration.
	 */
	fun stepper(title: String, detail: String? = null, value: () -> String, onStep: (Int, Int) -> Unit): View {
		val row = row(title, detail)
		row.findViewById<View>(R.id.row_left).visibility = View.VISIBLE
		row.findViewById<View>(R.id.row_right).visibility = View.VISIBLE
		setValue(row, value())
		row.setOnKeyListener { _, keyCode, event ->
			if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
			val direction = when (keyCode) {
				KeyEvent.KEYCODE_DPAD_LEFT -> -1
				KeyEvent.KEYCODE_DPAD_RIGHT -> 1
				else -> return@setOnKeyListener false
			}
			onStep(direction, event.repeatCount)
			setValue(row, value())
			true
		}
		row.setOnClickListener {
			onStep(1, 0)
			setValue(row, value())
		}
		return row
	}

	fun toggle(title: String, detail: String? = null, isOn: () -> Boolean, onChange: (Boolean) -> Unit): View {
		val row = row(title, detail)
		setValue(row, if (isOn()) "On" else "Off")
		row.setOnClickListener {
			onChange(!isOn())
			setValue(row, if (isOn()) "On" else "Off")
		}
		return row
	}

	/** The row to focus when the panel opens: the selected option, else the first row. */
	fun initialFocus(): View? {
		for (i in 0 until container.childCount) {
			val child = container.getChildAt(i)
			if (child.tag == TAG_SELECTED && child.isFocusable) return child
		}
		for (i in 0 until container.childCount) {
			val child = container.getChildAt(i)
			if (child.isFocusable && child.isEnabled) return child
		}
		return null
	}

	private fun row(title: String, detail: String?): View {
		val row = inflater.inflate(R.layout.item_panel_row, container, false)
		row.findViewById<TextView>(R.id.row_title).text = title
		val detailView = row.findViewById<TextView>(R.id.row_detail)
		if (!detail.isNullOrBlank()) {
			detailView.text = detail
			detailView.visibility = View.VISIBLE
		}
		container.addView(row)
		return row
	}

	private fun setValue(row: View, value: String) {
		row.findViewById<TextView>(R.id.row_value).apply {
			text = value
			visibility = View.VISIBLE
		}
	}

	private companion object {
		const val TAG_SELECTED = "selected"
	}
}
