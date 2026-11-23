package app.lumen.player.ui.pages

import android.view.View
import app.lumen.player.ui.MainActivity

/** A screen shown to the right of the sidebar. */
abstract class Page(protected val host: MainActivity) {
	abstract val view: View

	/** Called when the page is added. Start loading here. */
	open fun onShow() {}

	open fun onHide() {}

	/** Called when coming back from details or the player, to refresh progress/watched state. */
	open fun onReturn() {}

	/** Moves focus into the page; returns false if there's nothing focusable yet. */
	abstract fun focusFirst(): Boolean
}
