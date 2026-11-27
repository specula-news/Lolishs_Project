package app.lumen.player.ui

import android.app.AlertDialog
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.lumen.player.R
import app.lumen.player.util.PhoneInput
import app.lumen.player.util.Ui
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The saved Jellyfin servers: switch between them, add one (just the address is enough) or remove one. */
object ServerPicker {

	fun show(activity: BaseActivity, current: String?, onPicked: (String) -> Unit) {
		val servers = activity.prefs.servers
		val now = current?.let { JellyfinApi.normalizeBaseUrl(it) }
		val labels = servers.map { url ->
			val name = if (url.equals(Prefs.DEFAULT_SERVER, ignoreCase = true)) "Lolish  ·  " else ""
			val mark = if (now != null && url.equals(now, ignoreCase = true)) "   ✓" else ""
			"$name${url.substringAfter("://")}$mark"
		} + listOfNotNull("＋  Add a server (type it on your phone)…", if (servers.size > 1) "－  Remove a server…" else null)
		AlertDialog.Builder(activity)
			.setTitle("Jellyfin servers")
			.setItems(labels.toTypedArray()) { _, which ->
				when {
					which < servers.size -> onPicked(servers[which])
					which == servers.size -> add(activity, onPicked)
					else -> remove(activity, current, onPicked)
				}
			}
			.setNegativeButton("Close", null)
			.show()
	}

	/**
	 * Add a server the easy way: scan the QR code with the phone and type the address there (the TV
	 * serves a small page on the home network), or type it with the remote. Just the address is
	 * enough, e.g. "lolish.no-ip.com": https / http and the port are found automatically.
	 */
	fun add(activity: BaseActivity, onPicked: (String) -> Unit) {
		var dialog: AlertDialog? = null
		val phone = PhoneInput("Add a Jellyfin server", "e.g. lolish.no-ip.com") { text ->
			dialog?.dismiss()
			find(activity, text, onPicked)
		}
		val url = phone.start()
		if (url == null) {
			// No home network address (or no free port): the remote it is.
			typeWithRemote(activity, onPicked)
			return
		}
		val size = Ui.dp(activity, 220)
		val box = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			setPadding(Ui.dp(activity, 24), Ui.dp(activity, 8), Ui.dp(activity, 24), 0)
		}
		box.addView(ImageView(activity).apply {
			setImageBitmap(PhoneInput.qr(url, size))
			setBackgroundColor(0xFFFFFFFF.toInt())
			val pad = Ui.dp(activity, 8)
			setPadding(pad, pad, pad, pad)
		}, LinearLayout.LayoutParams(size + Ui.dp(activity, 16), size + Ui.dp(activity, 16)))
		box.addView(TextView(activity).apply {
			this.text = "Scan with your phone's camera, type the server address and press Send to TV."
			gravity = Gravity.CENTER
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
			setTextColor(Ui.color(activity, R.attr.lumenText))
			setPadding(0, Ui.dp(activity, 16), 0, Ui.dp(activity, 6))
		})
		box.addView(TextView(activity).apply {
			this.text = "The phone has to be on the same Wi-Fi. No camera app? Open $url"
			gravity = Gravity.CENTER
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
			setTextColor(Ui.color(activity, R.attr.lumenText3))
		})
		dialog = AlertDialog.Builder(activity)
			.setTitle("Add a server")
			.setView(box)
			.setNeutralButton("Type with the remote") { _, _ -> typeWithRemote(activity, onPicked) }
			.setNegativeButton("Close", null)
			.setOnDismissListener { phone.stop() }
			.show()
	}

	private fun typeWithRemote(activity: BaseActivity, onPicked: (String) -> Unit) {
		Dialogs.input(activity, "Server address", "", "e.g. lolish.no-ip.com (http/https and port are found)") { value ->
			if (value.isNotBlank()) find(activity, value, onPicked)
		}
	}

	/** Looks for the server behind what was typed and adds it. */
	private fun find(activity: BaseActivity, value: String, onPicked: (String) -> Unit) {
		val waiting = AlertDialog.Builder(activity)
			.setTitle("Looking for the server…")
			.setMessage("Trying https and http on the usual ports for $value")
			.show()
		activity.lifecycleScope.launch {
			try {
				val url = activity.api.findServer(value)
				waiting.dismiss()
				activity.prefs.addServer(url)
				onPicked(url)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				waiting.dismiss()
				AlertDialog.Builder(activity)
					.setTitle("Server not found")
					.setMessage(e.message ?: "No answer")
					.setPositiveButton("Try again") { _, _ -> add(activity, onPicked) }
					.setNegativeButton("Close", null)
					.show()
			}
		}
	}

	private fun remove(activity: BaseActivity, current: String?, onPicked: (String) -> Unit) {
		val removable = activity.prefs.servers.filter { !it.equals(Prefs.DEFAULT_SERVER, ignoreCase = true) }
		AlertDialog.Builder(activity)
			.setTitle("Remove a server")
			.setItems(removable.map { it.substringAfter("://") }.toTypedArray()) { _, which ->
				activity.prefs.removeServer(removable[which])
				show(activity, current, onPicked)
			}
			.setNegativeButton("Close", null)
			.show()
	}
}
