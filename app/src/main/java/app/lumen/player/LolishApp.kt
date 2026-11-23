package app.lumen.player

import android.app.Application
import android.os.Build
import java.io.File

/** Saves the details of any crash so the next launch can show them on screen. */
class LolishApp : Application() {
	override fun onCreate() {
		super.onCreate()
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			runCatching {
				val text = buildString {
					append("Lolish's Project ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})\n")
					append("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
					append("Thread: ${thread.name}\n\n")
					append(error.stackTraceToString())
				}
				File(filesDir, CRASH_FILE).writeText(text)
			}
			previous?.uncaughtException(thread, error)
		}
	}

	companion object {
		const val CRASH_FILE = "last_crash.txt"
	}
}
