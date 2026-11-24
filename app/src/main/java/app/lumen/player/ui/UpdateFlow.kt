package app.lumen.player.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lumen.player.R
import app.lumen.player.util.Release
import app.lumen.player.util.Updater
import app.lumen.player.util.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The update dialogs: "update available" → download with progress → Android's install screen. */
class UpdateFlow(private val activity: BaseActivity, private val toast: (String) -> Unit) {
	private var job: Job? = null
	private var waitingForPermission: Release? = null

	/** [manual] = from Settings: always answers, ignores "skip this version" and the time limit. */
	fun check(manual: Boolean) {
		val prefs = activity.prefs
		if (!manual) {
			if (!prefs.autoUpdateCheck) return
			if (System.currentTimeMillis() - prefs.lastUpdateCheck < AUTO_INTERVAL_MS) return
		}
		if (job?.isActive == true) return
		if (manual) toast("Checking for updates…")
		job = activity.lifecycleScope.launch {
			val release = try {
				Updater.latest()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				if (manual) toast("Couldn't check for updates (${e.message ?: e.javaClass.simpleName})")
				return@launch
			}
			prefs.lastUpdateCheck = System.currentTimeMillis()
			if (release == null || !Updater.isNewer(release.tag)) {
				if (manual) toast("You have the latest version (${Updater.currentVersion})")
				return@launch
			}
			if (!manual && release.tag == prefs.skippedUpdate) return@launch
			offer(release)
		}
	}

	/** Call from onResume: continues after the user allowed "Install unknown apps". */
	fun onResume() {
		val release = waitingForPermission ?: return
		if (Updater.canInstall(activity)) {
			waitingForPermission = null
			download(release)
		}
	}

	private fun offer(release: Release) {
		if (activity.isFinishing) return
		val notes = release.notes.lines().filter { it.isNotBlank() }.take(8).joinToString("\n").take(500)
		val message = buildString {
			append("Version ${release.version} is ready (you have ${Updater.currentVersion}).")
			if (release.apkSize > 0) append("\nDownload: ${release.apkSize / (1024 * 1024)} MB")
			if (notes.isNotBlank()) append("\n\n").append(notes)
		}
		AlertDialog.Builder(activity)
			.setTitle("Update available")
			.setMessage(message)
			.setPositiveButton("Update now") { _, _ -> start(release) }
			.setNegativeButton("Later", null)
			.setNeutralButton("Skip this version") { _, _ -> activity.prefs.skippedUpdate = release.tag }
			.show()
			.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
	}

	private fun start(release: Release) {
		if (Updater.canInstall(activity)) {
			download(release)
			return
		}
		AlertDialog.Builder(activity)
			.setTitle("Allow updates")
			.setMessage(
				"Android needs your OK before Lolish's Project can install its own updates.\n\n" +
					"On the next screen turn on \"Allow from this source\" (or \"Unknown sources\") for Lolish's Project, then press Back. " +
					"The update continues automatically."
			)
			.setPositiveButton("Open settings") { _, _ -> openInstallPermission(release) }
			.setNeutralButton("Try anyway") { _, _ -> download(release) }
			.setNegativeButton("Cancel", null)
			.show()
	}

	private fun openInstallPermission(release: Release) {
		waitingForPermission = release
		val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
		try {
			activity.startActivity(intent)
		} catch (_: ActivityNotFoundException) {
			try {
				activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
				toast("Open \"Unknown sources\" and allow Lolish's Project")
			} catch (_: ActivityNotFoundException) {
				waitingForPermission = null
				AlertDialog.Builder(activity)
					.setTitle("Allow updates")
					.setMessage("Open the TV's Settings → Device Preferences → Security & restrictions → Unknown sources, turn on Lolish's Project, then choose Update again.")
					.setPositiveButton("OK", null)
					.show()
			}
		}
	}

	private fun download(release: Release) {
		val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
			max = 100
			isIndeterminate = release.apkSize <= 0
			progressTintList = Ui.colorList(activity, R.attr.lumenAccent)
		}
		val label = TextView(activity).apply {
			text = "Downloading ${release.version}…"
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			setTextColor(Ui.color(activity, R.attr.lumenText2))
		}
		val box = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			val pad = Ui.dp(activity, 24)
			setPadding(pad, Ui.dp(activity, 12), pad, Ui.dp(activity, 4))
			addView(label)
			addView(bar)
		}
		var downloadJob: Job? = null
		val dialog = AlertDialog.Builder(activity)
			.setTitle("Updating Lolish's Project")
			.setView(box)
			.setNegativeButton("Cancel") { _, _ -> downloadJob?.cancel() }
			.setCancelable(false)
			.show()
		downloadJob = activity.lifecycleScope.launch {
			try {
				val apk = Updater.download(activity, release) { percent ->
					bar.post {
						bar.progress = percent
						label.text = "Downloading ${release.version}…  $percent%"
					}
				}
				label.text = "Starting the installer…"
				withContext(Dispatchers.IO) { Updater.install(activity, apk) }
				dialog.dismiss()
				toast("Confirm the update on the next screen")
			} catch (e: CancellationException) {
				dialog.dismiss()
				throw e
			} catch (e: Exception) {
				dialog.dismiss()
				AlertDialog.Builder(activity)
					.setTitle("Update failed")
					.setMessage(e.message ?: e.javaClass.simpleName)
					.setPositiveButton("Try again") { _, _ -> download(release) }
					.setNegativeButton("Close", null)
					.show()
			}
		}
	}

	private companion object {
		const val AUTO_INTERVAL_MS = 3 * 60 * 60 * 1000L
	}
}
