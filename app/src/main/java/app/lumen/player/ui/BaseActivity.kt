package app.lumen.player.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.Session
import app.lumen.player.util.Images
import app.lumen.player.util.Prefs

/** Applies the user's text size and gives every screen the prefs + API client. */
abstract class BaseActivity : ComponentActivity() {
	lateinit var prefs: Prefs
		private set
	lateinit var api: JellyfinApi
		private set

	override fun attachBaseContext(newBase: Context) {
		val scaled = runCatching {
			val scale = Prefs(newBase).textScale.coerceIn(80, 140) / 100f
			if (scale == 1f) return@runCatching newBase
			val config = Configuration(newBase.resources.configuration)
			config.fontScale = config.fontScale * scale
			newBase.createConfigurationContext(config)
		}.getOrDefault(newBase)
		super.attachBaseContext(scaled)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		prefs = Prefs(this)
		api = JellyfinApi(prefs)
		Images.init(this)
	}

	protected fun session(): Session? = api.session()
}
