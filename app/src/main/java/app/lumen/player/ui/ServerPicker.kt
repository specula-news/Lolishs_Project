package app.lumen.player.ui

import android.app.AlertDialog
import androidx.lifecycle.lifecycleScope
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
		} + listOfNotNull("＋  Add a server…", if (servers.size > 1) "－  Remove a server…" else null)
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

	/** Type an address like "lolish.no-ip.com": http / https and the port are found automatically. */
	fun add(activity: BaseActivity, onPicked: (String) -> Unit) {
		Dialogs.input(activity, "Server address", "", "e.g. lolish.no-ip.com (http/https and port are found)") { value ->
			if (value.isBlank()) return@input
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
