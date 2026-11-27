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
		ADDED("Newest added", "DateCreated,SortName", true),
		RELEASED("Newest release", "PremiereDate,ProductionYear,SortName", true),
		NAME("A–Z", "SortName", false),
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
		setPadding(Ui.dp(host, 26), Ui.dp(host, 24), Ui.dp(host, 24), 0)
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
	/** Sort and filter are remembered per library (and per user); newest first by default. */
	private val memoryKey = library?.id ?: titleOverride ?: "all"
	private var sort = host.prefs.librarySort(memoryKey)?.let { name -> Sort.entries.firstOrNull { it.name == name } } ?: Sort.ADDED
	private var filter = host.prefs.libraryFilter(memoryKey)?.let { name -> Filter.entries.firstOrNull { it.name == name } } ?: Filter.ALL
	private var loadJob: Job? = null
	private var loadingMore = false
	/** The A–Z rail: null = everything, "#" = numbers and signs, else a letter. Works together with sort and Show. */
	private var letter: String? = null
	private val letterViews = mutableMapOf<String?, TextView>()

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
				host.prefs.setLibrarySort(memoryKey, sort.name)
				sortPill.text = "Sort: ${sort.label}"
				reload()
			}
			val filterPill = Pills.create(toolbar, "Show: ${filter.label}", R.drawable.ic_filter)
			(filterPill.layoutParams as ViewGroup.MarginLayoutParams).marginStart = Ui.dp(host, 12)
			filterPill.setOnClickListener {
				filter = Filter.entries[(filter.ordinal + 1) % Filter.entries.size]
				host.prefs.setLibraryFilter(memoryKey, filter.name)
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
			onFocusChange = { item, card, focused ->
				val frame = card.findViewById<android.widget.FrameLayout>(R.id.card_frame)
				if (focused) host.preview.focus(item, frame, 2, inline = false) else host.preview.leave(frame)
			},
		)
		grid.adapter = adapter
		grid.setPadding(0, Ui.dp(host, 6), 0, Ui.dp(host, 60))
		grid.layoutManager = GridLayoutManager(host, 6)
		// The grid, with the A–Z rail beside it (LEFT / RIGHT from the edge column goes to it and back).
		val body = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			clipChildren = false
			clipToPadding = false
		}
		body.addView(grid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
		// Settings › Appearance › A–Z letters: on the left (default), on the right, or off.
		val side = host.prefs.letterRail
		if (fixedFilters.isEmpty() && side != 2) {
			val params = LinearLayout.LayoutParams(Ui.dp(host, 48), ViewGroup.LayoutParams.MATCH_PARENT).apply {
				if (side == 0) marginEnd = Ui.dp(host, 12) else marginStart = Ui.dp(host, 8)
				topMargin = Ui.dp(host, 4)
				bottomMargin = Ui.dp(host, 24)
			}
			if (side == 0) body.addView(buildLetterRail(), 0, params) else body.addView(buildLetterRail(), params)
		}
		root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
	}

	/** A slim A–Z rail: OK on a letter shows only titles starting with it (with the current sort and Show). */
	private fun buildLetterRail(): View {
		val column = LinearLayout(host).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			setPadding(0, Ui.dp(host, 8), 0, Ui.dp(host, 8))
			clipChildren = false
			clipToPadding = false
		}
		(listOf<String?>(null, "#") + ('A'..'Z').map { it.toString() }).forEach { key ->
			val cell = TextView(host).apply {
				this.text = key ?: "All"
				gravity = Gravity.CENTER
				typeface = Ui.bold(host)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key == null) 11f else 15f)
				setTextColor(androidx.core.content.ContextCompat.getColorStateList(host, R.color.pill_text))
				background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_letter)
				isFocusable = true
				isClickable = true
				contentDescription = if (key == null) "All titles" else "Titles starting with $key"
			}
			cell.setOnClickListener { pickLetter(key) }
			Ui.focusScale(cell, 1.18f)
			column.addView(cell, LinearLayout.LayoutParams(Ui.dp(host, 34), Ui.dp(host, 30)).apply { bottomMargin = Ui.dp(host, 3) })
			letterViews[key] = cell
		}
		markLetter()
		return android.widget.ScrollView(host).apply {
			isVerticalScrollBarEnabled = false
			isFillViewport = false
			clipToPadding = false
			overScrollMode = View.OVER_SCROLL_NEVER
			background = android.graphics.drawable.GradientDrawable().apply {
				setColor(Ui.color(host, R.attr.lumenPillIdle))
				alpha = 170
				cornerRadius = Ui.dp(host, 24).toFloat()
			}
			addView(column, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		}
	}

	private fun pickLetter(key: String?) {
		if (letter == key) return
		letter = key
		markLetter()
		reload()
	}

	private fun markLetter() {
		letterViews.forEach { (key, view) -> view.isSelected = key == letter }
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
		nameStartsWith = letter?.takeIf { it != "#" },
		nameLessThan = if (letter == "#") "A" else null,
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
				countView.text = when {
					letter != null && it.total > 0 -> "${it.total}  ·  $letter"
					it.total > 0 -> "${it.total}"
					else -> letter ?: ""
				}
				emptyView.visibility = if (it.items.isEmpty()) View.VISIBLE else View.GONE
				emptyView.text = if (letter != null) "No titles starting with ${if (letter == "#") "a number" else letter} here." else "Nothing to show with these filters."
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
