package app.lumen.player.util

import android.app.Activity
import app.lumen.player.R

/**
 * The look of the app. AMBER is the original Li'Server-amber design, GLASS the frosted
 * "floating pane over the artwork" design, PURPLE the colors of the Li'Server website.
 */
object Themes {
	const val AMBER = 0
	const val GLASS = 1
	const val PURPLE = 2
	val NAMES = listOf("Lolish amber", "Glass", "Li'Server purple")

	@Volatile
	var current = AMBER
		private set

	val isGlass: Boolean get() = current == GLASS

	/** Call before super.onCreate / setContentView. */
	fun apply(activity: Activity, prefs: Prefs, player: Boolean = false) {
		current = prefs.theme.coerceIn(0, 2)
		activity.setTheme(
			when (current) {
				GLASS -> if (player) R.style.Theme_Glass_Player else R.style.Theme_Glass
				PURPLE -> if (player) R.style.Theme_Purple_Player else R.style.Theme_Purple
				else -> if (player) R.style.Theme_Lumen_Player else R.style.Theme_Lumen
			}
		)
	}
}
