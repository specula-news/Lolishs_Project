package app.lumen.player.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.lumen.player.LolishApp
import app.lumen.player.util.Prefs
import java.io.File

/**
 * Plain launcher screen with no custom theme or fonts. Normally it just opens the app; if the app
 * crashed last time it shows the error so it can be photographed and fixed.
 */
class LaunchActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val crash = File(filesDir, LolishApp.CRASH_FILE)
		if (!crash.exists()) {
			openApp()
			return
		}
		val report = runCatching { crash.readText() }.getOrDefault("(could not read the crash report)")
		val density = resources.displayMetrics.density
		val pad = (40 * density).toInt()

		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(Color.rgb(19, 17, 16))
			setPadding(pad, pad / 2, pad, pad / 2)
		}
		root.addView(TextView(this).apply {
			text = "Lolish's Project stopped last time"
			setTextColor(Color.rgb(240, 166, 58))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
			typeface = Typeface.DEFAULT_BOLD
		})
		root.addView(TextView(this).apply {
			text = "Take a photo of this screen and send it to Claude so it can be fixed. Then press Continue."
			setTextColor(Color.rgb(179, 168, 149))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			setPadding(0, (6 * density).toInt(), 0, (12 * density).toInt())
		})
		val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
		val proceed = Button(this).apply {
			text = "Continue"
			setOnClickListener {
				crash.delete()
				openApp()
			}
		}
		val keep = Button(this).apply {
			text = "Close"
			setOnClickListener { finish() }
		}
		buttons.addView(proceed)
		buttons.addView(keep)
		root.addView(buttons)
		val scroll = ScrollView(this)
		scroll.addView(TextView(this).apply {
			text = report.lines().take(70).joinToString("\n")
			setTextColor(Color.rgb(245, 237, 221))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
			typeface = Typeface.MONOSPACE
			setPadding(0, (12 * density).toInt(), 0, 0)
		})
		root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		setContentView(root)
		proceed.requestFocus()
	}

	private fun openApp() {
		val prefs = runCatching { Prefs(this) }.getOrNull()
		val accounts = prefs?.let { runCatching { it.accounts }.getOrDefault(emptyList()) }.orEmpty()
		val next = when {
			accounts.size > 1 && prefs?.askWhoIsWatching == true -> ProfilesActivity::class.java
			accounts.isEmpty() && prefs?.isLinked != true -> LoginActivity::class.java
			else -> MainActivity::class.java
		}
		startActivity(Intent(this, next).apply {
			// Debug builds: pass the screenshot script's extras on (see tools/screenshots).
			if (app.lumen.player.BuildConfig.DEBUG) intent.extras?.let { putExtras(it) }
		})
		finish()
	}
}
