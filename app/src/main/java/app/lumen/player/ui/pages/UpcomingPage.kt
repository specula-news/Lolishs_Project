package app.lumen.player.ui.pages

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import app.lumen.player.R
import app.lumen.player.ui.CardGridAdapter
import app.lumen.player.ui.CardStyle
import app.lumen.player.ui.MainActivity
import app.lumen.player.ui.TvRecyclerView
import app.lumen.player.util.Ui
import kotlinx.coroutines.launch

/** Episodes airing soon for shows in your library. */
class UpcomingPage(host: MainActivity) : Page(host) {
	private val root = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(Ui.dp(host, 26), Ui.dp(host, 24), Ui.dp(host, 24), 0)
	}
	override val view: View = root
	private val grid = TvRecyclerView(host)
	private val message = TextView(host)
	private val adapter = CardGridAdapter(host.cards, CardStyle.THUMB, { host.openItem(it) }, { host.setBackdrop(it) }, {})

	init {
		root.addView(host.cards.heading("Upcoming Shows", null, 40f))
		message.apply {
			setTextColor(Ui.color(host, R.attr.lumenText2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			setPadding(Ui.dp(host, 8), Ui.dp(host, 20), 0, 0)
			visibility = View.GONE
		}
		root.addView(message)
		grid.adapter = adapter
		grid.layoutManager = GridLayoutManager(host, 4)
		grid.setPadding(0, Ui.dp(host, 16), 0, Ui.dp(host, 60))
		root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
	}

	override fun onShow() {
		grid.post {
			val width = host.cards.cardSize(CardStyle.THUMB).first
			(grid.layoutManager as GridLayoutManager).spanCount = CardGridAdapter.spanCount(host, grid.width, width)
		}
		host.lifecycleScope.launch {
			val items = runCatching { host.api.upcoming(host.session, 100) }
			items.onSuccess {
				adapter.replace(it, it.size)
				message.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
				message.text = "No upcoming episodes. Jellyfin lists episodes here once their air dates are known."
			}.onFailure {
				message.visibility = View.VISIBLE
				message.text = "Couldn’t load upcoming episodes: ${it.message}"
			}
		}
	}

	override fun focusFirst(): Boolean = grid.getChildAt(0)?.requestFocus() == true
}
