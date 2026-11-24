package app.lumen.player.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import app.lumen.player.R
import app.lumen.player.util.Ui

/** Simple TV-friendly text input and confirmation dialogs. */
object Dialogs {
	fun input(
		context: Context,
		title: String,
		initial: String?,
		hint: String = "",
		password: Boolean = false,
		onOk: (String) -> Unit,
	) {
		val field = EditText(context).apply {
			setText(initial.orEmpty())
			this.hint = hint
			setSingleLine()
			inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
			else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
			setTextColor(Ui.color(context, R.attr.lumenText))
			setHintTextColor(Ui.color(context, R.attr.lumenText3))
			background = ContextCompat.getDrawable(context, R.drawable.bg_edit)
			val pad = Ui.dp(context, 16)
			setPadding(pad, pad, pad, pad)
			setSelection(text.length)
		}
		val container = FrameLayout(context).apply {
			val pad = Ui.dp(context, 24)
			setPadding(pad, Ui.dp(context, 8), pad, 0)
			addView(field)
		}
		AlertDialog.Builder(context)
			.setTitle(title)
			.setView(container)
			.setPositiveButton("Save") { _, _ -> onOk(field.text.toString().trim()) }
			.setNegativeButton("Cancel", null)
			.show()
		field.requestFocus()
	}

	fun confirm(context: Context, title: String, message: String, ok: String, onOk: () -> Unit) {
		AlertDialog.Builder(context)
			.setTitle(title)
			.setMessage(message)
			.setPositiveButton(ok) { _, _ -> onOk() }
			.setNegativeButton("Cancel", null)
			.show()
	}
}
