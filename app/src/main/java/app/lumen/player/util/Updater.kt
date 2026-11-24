package app.lumen.player.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import app.lumen.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A build published on GitHub Releases by the "Build APK" workflow. */
data class Release(val tag: String, val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

/**
 * Self-update from GitHub Releases: every push to main builds the APKs and publishes them as a
 * release (see .github/workflows/build.yml). The app downloads the APK for its own flavor and
 * hands it to Android's package installer, which asks the user to confirm.
 */
object Updater {
	const val REPO = "specula-news/Lumen"
	private const val USER_AGENT = "LolishProject-Updater"

	val currentVersion: String get() = BuildConfig.VERSION_NAME

	/** Release notes without the build-tool credit lines (Co-Authored-By, session links). */
	fun cleanNotes(body: String): String = body.lines()
		.filterNot { NOTE_NOISE.containsMatchIn(it) }
		.joinToString("\n").trim()

	private val NOTE_NOISE = Regex("(?i)^\\s*(co-authored-by|claude-session|generated with)\\b|claude\\.(ai|com)")

	/** The newest release that has an APK for this build, or null if there is none yet. */
	suspend fun latest(): Release? = withContext(Dispatchers.IO) {
		val connection = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
			connectTimeout = 10_000
			readTimeout = 15_000
			setRequestProperty("Accept", "application/vnd.github+json")
			setRequestProperty("User-Agent", USER_AGENT)
		}
		try {
			val code = connection.responseCode
			if (code == 404) return@withContext null
			if (code !in 200..299) throw IOException("GitHub answered HTTP $code")
			val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
			val tag = json.optString("tag_name")
			val assets = json.optJSONArray("assets") ?: return@withContext null
			val flavorTag = "-${BuildConfig.FLAVOR}-"
			for (i in 0 until assets.length()) {
				val asset = assets.optJSONObject(i) ?: continue
				val name = asset.optString("name")
				if (name.endsWith(".apk") && name.contains(flavorTag)) {
					return@withContext Release(
						tag = tag,
						version = tag.trimStart('v', 'V'),
						notes = cleanNotes(json.optString("body").takeIf { it != "null" }.orEmpty()),
						apkUrl = asset.optString("browser_download_url"),
						apkSize = asset.optLong("size", -1L),
					)
				}
			}
			null
		} finally {
			connection.disconnect()
		}
	}

	/** True when [candidate] (e.g. "v2.1.18") is newer than [current] (e.g. "2.1.17"). */
	fun isNewer(candidate: String, current: String = currentVersion): Boolean {
		val a = parts(candidate)
		val b = parts(current)
		for (i in 0 until maxOf(a.size, b.size)) {
			val x = a.getOrElse(i) { 0 }
			val y = b.getOrElse(i) { 0 }
			if (x != y) return x > y
		}
		return false
	}

	private fun parts(version: String): List<Int> =
		version.trim().trimStart('v', 'V').split('.', '-', '+').map { part -> part.takeWhile { it.isDigit() } }.mapNotNull { it.toIntOrNull() }

	/** Downloads the APK into the cache; [onProgress] gets 0..100 on the IO thread. */
	suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
		val dir = File(context.cacheDir, "updates").apply { mkdirs() }
		dir.listFiles()?.forEach { it.delete() }
		val file = File(dir, "update.apk")
		val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
			connectTimeout = 15_000
			readTimeout = 30_000
			instanceFollowRedirects = true
			setRequestProperty("Accept", "application/octet-stream")
			setRequestProperty("User-Agent", USER_AGENT)
		}
		try {
			val code = connection.responseCode
			if (code !in 200..299) throw IOException("Download failed (HTTP $code)")
			val total = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: release.apkSize
			var done = 0L
			var lastPercent = -1
			connection.inputStream.use { input ->
				file.outputStream().use { output ->
					val buffer = ByteArray(64 * 1024)
					while (true) {
						ensureActive()
						val read = input.read(buffer)
						if (read < 0) break
						output.write(buffer, 0, read)
						done += read
						if (total > 0) {
							val percent = (done * 100 / total).toInt().coerceIn(0, 100)
							if (percent != lastPercent) {
								lastPercent = percent
								onProgress(percent)
							}
						}
					}
				}
			}
			if (total > 0 && done != total) throw IOException("Download was cut off ($done of $total bytes)")
			file
		} catch (e: Exception) {
			file.delete()
			throw e
		} finally {
			connection.disconnect()
		}
	}

	/** Whether Android lets this app install APKs ("Install unknown apps"). */
	fun canInstall(context: Context): Boolean =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

	/** Hands the APK to the system installer; [UpdateReceiver] shows its confirmation screen. */
	fun install(context: Context, apk: File) {
		val installer = context.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
			setAppPackageName(context.packageName)
			setSize(apk.length())
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
		}
		val sessionId = installer.createSession(params)
		val session = installer.openSession(sessionId)
		try {
			apk.inputStream().use { input ->
				session.openWrite("lolish-project.apk", 0, apk.length()).use { output ->
					input.copyTo(output, 64 * 1024)
					session.fsync(output)
				}
			}
			val intent = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
			val flags = PendingIntent.FLAG_UPDATE_CURRENT or
				(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
			val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
			session.commit(pending.intentSender)
		} catch (e: Exception) {
			session.abandon()
			throw e
		} finally {
			session.close()
		}
	}
}

/** Receives the installer's answer: shows the confirmation screen, or reports a failure. */
class UpdateReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {
				val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
				} else {
					@Suppress("DEPRECATION")
					intent.getParcelableExtra(Intent.EXTRA_INTENT)
				}
				if (confirm != null) {
					confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					runCatching { context.startActivity(confirm) }.onFailure {
						Toast.makeText(context, "Couldn't open the installer: ${it.message}", Toast.LENGTH_LONG).show()
					}
				}
			}
			PackageInstaller.STATUS_SUCCESS -> Unit // Installed; the new version starts next time the app is opened.
			PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(context, "Update cancelled", Toast.LENGTH_SHORT).show()
			else -> {
				val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
				Toast.makeText(context, "Update failed: $message", Toast.LENGTH_LONG).show()
			}
		}
	}
}
