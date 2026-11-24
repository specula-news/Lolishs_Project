package app.lumen.player.ui.pages

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import app.lumen.player.R
import app.lumen.player.jellyfin.ItemQuery
import app.lumen.player.jellyfin.JfItem
import app.lumen.player.ui.CardGridAdapter
import app.lumen.player.ui.CardStyle
import app.lumen.player.ui.MainActivity
import app.lumen.player.ui.Pills
import app.lumen.player.ui.TvRecyclerView
import app.lumen.player.util.Ui
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A library (Movies, Shows, Collections…) as a poster grid with sort and filter. */
open class LibraryPage(
	host: MainActivity,
	private val library: JfItem?,
	private val titleOverride: String? = null,
	private val fixedFilters: List<String> = emptyList(),
	private val fixedTypes: List<String>? = null,
) : Page(host) {

	private enum class Sort(val label: String, val field: String, val descending: Boolean) {
		NAME("A–Z", "SortName", false),
		ADDED("Recently added", "DateCreated", true),
		RELEASED("Release date", "PremiereDate,ProductionYear", true),
		RATING("Rating", "CommunityRating", true),
		RUNTIME("Runtime", "Runtime", false),
		RANDOM("Random", "Random", false),
	}

	private enum class Filter(val label: String, val value: String?) {
		ALL("All", null),
		UNWATCHED("Unwatched", "IsUnplayed"),
		FAVORITES("Favorites", "IsFavorite"),
		IN_PROGRESS("In progress", "IsResumable"),
	}

	private val root = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(Ui.dp(host, 44), Ui.dp(host, 30), Ui.dp(host, 24), 0)
	}
	override val view: View = root

	private val countView = TextView(host)
	private val toolbar = LinearLayout(host).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		setPadding(0, Ui.dp(host, 16), 0, Ui.dp(host, 8))
	}
	private val grid = TvRecyclerView(host)
	private val emptyView = TextView(host)
	private val adapter: CardGridAdapter
	private var sort = Sort.NAME
	private var filter = Filter.ALL
	private var loadJob: Job? = null
	private var loadingMore = false

	private val types: List<String> = fixedTypes ?: when (library?.collectionType) {
		"movies" -> listOf("Movie")
		"tvshows" -> listOf("Series")
		"boxsets" -> listOf("BoxSet")
		"homevideos" -> listOf("Video")
		"musicvideos" -> listOf("MusicVideo")
		else -> listOf("Movie", "Series")
	}

	init {
		val header = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.BOTTOM
		}
		header.addView(TextView(host).apply {
			text = titleOverride ?: library?.name ?: "Library"
			typeface = Ui.display(host)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
			setTextColor(Ui.color(host, R.attr.lumenText))
		})
		countView.apply {
			typeface = Ui.display(host)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
			setTextColor(Ui.color(host, R.attr.lumenText3))
			setPadding(Ui.dp(host, 14), 0, 0, Ui.dp(host, 3))
		}
		header.addView(countView)
		root.addView(header)

		if (fixedFilters.isEmpty()) {
			val sortPill = Pills.create(toolbar, "Sort: ${sort.label}", R.drawable.ic_sort)
			sortPill.setOnClickListener {
				sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]
				sortPill.text = "Sort: ${sort.label}"
				reload()
			}
			val filterPill = Pills.create(toolbar, "Show: ${filter.label}", R.drawable.ic_filter)
			(filterPill.layoutParams as ViewGroup.MarginLayoutParams).marginStart = Ui.dp(host, 12)
			filterPill.setOnClickListener {
				filter = Filter.entries[(filter.ordinal + 1) % Filter.entries.size]
				filterPill.text = "Show: ${filter.label}"
				reload()
			}
			root.addView(toolbar)
		}

		emptyView.apply {
			visibility = View.GONE
			setTextColor(Ui.color(host, R.attr.lumenText2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			setPadding(Ui.dp(host, 8), Ui.dp(host, 24), 0, 0)
		}
		root.addView(emptyView)

		adapter = CardGridAdapter(
			cards = host.cards,
			style = CardStyle.POSTER,
			onClick = { host.openItem(it) },
			onFocus = { host.setBackdrop(it) },
			onNeedMore = { loadMore() },
		)
		grid.adapter = adapter
		grid.setPadding(0, Ui.dp(host, 6), 0, Ui.dp(host, 60))
		grid.layoutManager = GridLayoutManager(host, 6)
		root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
	}

	override fun onShow() {
		grid.post {
			val cardWidth = host.cards.cardSize(CardStyle.POSTER).first
			val span = CardGridAdapter.spanCount(host, grid.width - grid.paddingLeft - grid.paddingRight, cardWidth)
			(grid.layoutManager as GridLayoutManager).spanCount = span
		}
		reload()
	}

	override fun onReturn() {
		// Refresh watched badges / progress of what's on screen.
		if (adapter.items.isEmpty()) return
		host.lifecycleScope.launch {
			val page = runCatching { host.api.items(host.session, query(0, adapter.items.size.coerceAtMost(300))) }.getOrNull() ?: return@launch
			if (page.items.map(JfItem::id) == adapter.items.take(page.items.size).map(JfItem::id)) {
				page.items.forEachIndexed { index, item -> adapter.items[index] = item }
				adapter.notifyItemRangeChanged(0, page.items.size)
			}
		}
	}

	override fun focusFirst(): Boolean {
		if (toolbar.childCount > 0 && toolbar.getChildAt(0).requestFocus()) return true
		return grid.getChildAt(0)?.requestFocus() == true
	}

	private fun query(start: Int, limit: Int) = ItemQuery(
		parentId = library?.id,
		includeTypes = types,
		sortBy = sort.field,
		descending = sort.descending,
		filters = fixedFilters + listOfNotNull(filter.value),
		startIndex = start,
		limit = limit,
	)

	private fun reload() {
		loadJob?.cancel()
		loadingMore = false
		loadJob = host.lifecycleScope.launch {
			val page = runCatching { host.api.items(host.session, query(0, 120)) }
			page.onSuccess {
				adapter.replace(it.items, it.total)
				countView.text = if (it.total > 0) "${it.total}" else ""
				emptyView.visibility = if (it.items.isEmpty()) View.VISIBLE else View.GONE
				emptyView.text = "Nothing to show with these filters."
				grid.scrollToPosition(0)
			}.onFailure {
				emptyView.visibility = View.VISIBLE
				emptyView.text = "Couldn’t load the library: ${it.message}"
			}
		}
	}

	private fun loadMore() {
		if (loadingMore || adapter.items.size >= adapter.total) return
		loadingMore = true
		host.lifecycleScope.launch {
			val page = runCatching { host.api.items(host.session, query(adapter.items.size, 120)) }.getOrNull()
			if (page != null) adapter.append(page.items)
			loadingMore = false
		}
	}
}
