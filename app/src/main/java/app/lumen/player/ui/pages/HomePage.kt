package app.lumen.player.ui.pages

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lumen.player.R
import app.lumen.player.jellyfin.ItemQuery
import app.lumen.player.jellyfin.JfItem
import app.lumen.player.ui.CardStyle
import app.lumen.player.ui.DetailActivity
import app.lumen.player.ui.MainActivity
import app.lumen.player.ui.Pills
import app.lumen.player.ui.PlayerActivity
import android.widget.FrameLayout
import android.widget.ImageView
import app.lumen.player.util.Images
import app.lumen.player.util.Formatters
import app.lumen.player.util.Ui
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Featured item + Continue watching, Next up, Latest per library, Upcoming and Favorites rows. */
class HomePage(host: MainActivity) : Page(host) {

	private data class RowData(val key: String, val title: String, val style: CardStyle, val items: List<JfItem>)

	private val scroll = ScrollView(host).apply {
		isVerticalScrollBarEnabled = false
		isFillViewport = true
		clipToPadding = false
	}
	private val column = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(Ui.dp(host, 44), Ui.dp(host, 30), 0, Ui.dp(host, 60))
		clipChildren = false
		clipToPadding = false
	}
	override val view: View = scroll

	private var hero: JfItem? = null
	private var heroButton: View? = null
	private val rowStrips = mutableMapOf<String, Pair<LinearLayout, List<JfItem>>>()
	private var loading: View? = null

	init {
		scroll.addView(column)
	}

	override fun onShow() {
		load(initial = true)
	}

	override fun onReturn() {
		if (rowStrips.isNotEmpty()) load(initial = false)
	}

	override fun focusFirst(): Boolean {
		val target = heroButton ?: rowStrips.values.firstOrNull()?.first?.getChildAt(0) ?: return false
		return target.requestFocus()
	}

	private fun load(initial: Boolean) {
		if (initial) {
			column.removeAllViews()
			loading = ProgressBar(host).apply {
				indeterminateTintList = Ui.colorList(host, R.attr.lumenAccent)
			}
			column.addView(loading, LinearLayout.LayoutParams(Ui.dp(host, 48), Ui.dp(host, 48)).apply {
				topMargin = Ui.dp(host, 200)
				leftMargin = Ui.dp(host, 400)
			})
		}
		host.lifecycleScope.launch {
			val rows = runCatching { fetchRows() }
			if (rows.isFailure) {
				if (initial) showError(rows.exceptionOrNull()?.message)
				return@launch
			}
			val data = rows.getOrThrow()
			if (initial || !sameShape(data)) build(data, initial) else rebind(data)
		}
	}

	private suspend fun fetchRows(): List<RowData> = coroutineScope {
		val api = host.api
		val session = host.session
		val prefs = host.prefs
		val views = async { runCatching { api.views(session) }.getOrDefault(emptyList()) }
		val resume = async { if (prefs.rowContinue) runCatching { api.resume(session) }.getOrDefault(emptyList()) else emptyList() }
		val nextUp = async { if (prefs.rowNextUp) runCatching { api.nextUp(session) }.getOrDefault(emptyList()) else emptyList() }
		val upcoming = async { if (prefs.rowUpcoming) runCatching { api.upcoming(session) }.getOrDefault(emptyList()) else emptyList() }
		val favorites = async {
			if (prefs.rowFavorites) runCatching {
				api.items(session, ItemQuery(includeTypes = listOf("Movie", "Series"), filters = listOf("IsFavorite"), sortBy = "DateCreated", descending = true, limit = 24)).items
			}.getOrDefault(emptyList()) else emptyList()
		}
		val libraries = views.await().filter { it.collectionType in setOf("movies", "tvshows", "mixed", "homevideos", null) }
		val latest = libraries.map { library ->
			async {
				val items = if (prefs.rowLatest) runCatching { api.latest(session, library.id) }.getOrDefault(emptyList()) else emptyList()
				RowData("latest:${library.id}", "Latest ${library.name}", CardStyle.POSTER, items)
			}
		}
		buildList {
			add(RowData("resume", "Continue watching", CardStyle.THUMB, resume.await()))
			add(RowData("nextup", "Next up", CardStyle.THUMB, nextUp.await()))
			latest.forEach { add(it.await()) }
			add(RowData("upcoming", "Upcoming", CardStyle.THUMB, upcoming.await()))
			add(RowData("favorites", "Favorites", CardStyle.POSTER, favorites.await()))
		}.filter { it.items.isNotEmpty() }
	}

	private fun sameShape(data: List<RowData>): Boolean =
		data.map { it.key to it.items.map(JfItem::id) } == rowStrips.entries.map { it.key to it.value.second.map(JfItem::id) } &&
			data.map { it.key } == rowStrips.keys.toList()

	private fun build(data: List<RowData>, initial: Boolean) {
		val focusedId = (host.currentFocus?.tag as? String)
		column.removeAllViews()
		rowStrips.clear()
		heroButton = null

		val featured = data.firstOrNull { it.key == "resume" }?.items?.firstOrNull()
			?: data.firstOrNull { it.key.startsWith("latest") }?.items?.firstOrNull { it.backdropTags.isNotEmpty() }
		hero = featured
		// Glass: the featured item is a big card at the start of the first row (reference design).
		val glassHero = app.lumen.player.util.Themes.isGlass && host.prefs.showHero && featured != null
		var skipKey: String? = null
		if (glassHero && featured != null) {
			val resumeRow = data.firstOrNull { it.key == "resume" }
			val rest = if (resumeRow != null && resumeRow.items.firstOrNull()?.id == featured.id) {
				skipKey = "resume"
				resumeRow.items.drop(1)
			} else emptyList()
			buildGlassHero(featured, rest, if (skipKey != null) "Continue Watching" else "New in your library")
		} else if (host.prefs.showHero && featured != null) buildHero(featured) else buildTitle()

		if (data.isEmpty()) {
			column.addView(TextView(host).apply {
				text = "Nothing here yet — add some media to your Jellyfin libraries."
				setTextColor(Ui.color(host, R.attr.lumenText2))
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
				setPadding(Ui.dp(host, 8), Ui.dp(host, 24), 0, 0)
			})
		}

		var refocus: View? = null
		data.filter { it.key != skipKey }.forEachIndexed { rowIndex, row ->
			val (rowView, strip) = host.cards.row(row.title, row.items.size)
			row.items.forEach { item ->
				val card = host.cards.create(strip, row.style)
				host.cards.bind(card, item, row.style)
				card.tag = "${row.key}|${item.id}"
				card.setOnClickListener { host.openItem(item) }
				Ui.focusScale(card, 1.07f) { focused -> if (focused) host.setBackdrop(item) }
				strip.addView(card)
				if (card.tag == focusedId) refocus = card
			}
			column.addView(rowView)
			if (initial && host.prefs.animations && rowIndex < 6) {
				// Rows rise in one after another on first load (hardware layer, no relayout).
				rowView.alpha = 0f
				rowView.translationY = Ui.dp(host, 24).toFloat()
				rowView.animate().alpha(1f).translationY(0f).setStartDelay(rowIndex * 70L).setDuration(280L)
					.setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).withLayer().start()
			}
			rowStrips[row.key] = strip to row.items
		}
		featured?.let { host.setBackdrop(it) }
		if (!initial) refocus?.requestFocus()
	}

	/** Same rows and items as before: just refresh progress bars and watched badges. */
	private fun rebind(data: List<RowData>) {
		data.forEach { row ->
			val strip = rowStrips[row.key]?.first ?: return@forEach
			row.items.forEachIndexed { index, item ->
				strip.getChildAt(index)?.let { host.cards.bind(it, item, row.style) }
			}
			rowStrips[row.key] = strip to row.items
		}
	}

	private fun buildTitle() {
		column.addView(TextView(host).apply {
			text = "Home"
			typeface = Ui.display(host)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
			setTextColor(Ui.color(host, R.attr.lumenText))
			setPadding(Ui.dp(host, 8), 0, 0, Ui.dp(host, 8))
		})
	}

	private fun buildHero(item: JfItem) {
		val box = LinearLayout(host).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(Ui.dp(host, 8), Ui.dp(host, 40), Ui.dp(host, 48), Ui.dp(host, 16))
		}
		val resume = item.userData?.hasResume == true
		box.addView(TextView(host).apply {
			text = if (resume) "CONTINUE WATCHING" else "NEW IN YOUR LIBRARY"
			typeface = Ui.bold(host)
			letterSpacing = 0.14f
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setTextColor(Ui.color(host, R.attr.lumenAccent))
		})
		box.addView(TextView(host).apply {
			text = if (item.type == "Episode") item.seriesName ?: item.name else item.name
			typeface = Ui.display(host)
			maxLines = 2
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
			setTextColor(Ui.color(host, R.attr.lumenText))
			setPadding(0, Ui.dp(host, 6), 0, Ui.dp(host, 4))
		})
		val meta = listOfNotNull(
			item.episodeCode?.let { "$it  ·  ${item.name}" },
			item.year?.toString(),
			item.officialRating,
			item.communityRating?.let { String.format(java.util.Locale.US, "★ %.1f", it) },
			item.runtimeMs.takeIf { it > 0 }?.let { Formatters.time(it) },
		).joinToString("   ·   ")
		box.addView(TextView(host).apply {
			text = meta
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
			setTextColor(Ui.color(host, R.attr.lumenText2))
		})
		item.overview?.let { overview ->
			box.addView(TextView(host).apply {
				text = overview
				maxLines = 3
				ellipsize = android.text.TextUtils.TruncateAt.END
				setLineSpacing(0f, 1.25f)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
				setTextColor(Ui.color(host, R.attr.lumenText))
				setPadding(0, Ui.dp(host, 12), 0, 0)
			}, LinearLayout.LayoutParams(Ui.dp(host, 720), ViewGroup.LayoutParams.WRAP_CONTENT))
		}
		val actions = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, Ui.dp(host, 22), 0, 0)
		}
		val play = Pills.create(actions, if (resume) "Resume ${Formatters.time(item.userData?.positionMs ?: 0)}" else "Play", R.drawable.ic_play)
		play.setOnClickListener { PlayerActivity.play(host, item.id, startMs = -1L) }
		val details = Pills.create(actions, "Details", R.drawable.ic_info)
		details.setOnClickListener { DetailActivity.start(host, item.id) }
		listOf(play, details).forEach { pill ->
			Ui.focusScale(pill, 1.05f) { focused -> if (focused) host.setBackdrop(item) }
		}
		box.addView(actions)
		column.addView(box)
		heroButton = play
	}

	/** Glass hero: one wide card with artwork, title, facts and Play / More info, then tall cards. */
	private fun buildGlassHero(item: JfItem, more: List<JfItem>, title: String) {
		val (rowView, strip) = host.cards.row(title, null)
		val height = Ui.dp(host, 320)
		val width = Ui.dp(host, 560)
		val card = FrameLayout(host).apply {
			background = ContextCompat.getDrawable(host, R.drawable.bg_card_image)
			clipToOutline = true
		}
		val art = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
		card.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		Images.load(art, host.api.backdropUrl(host.session, item, 1280))
		card.addView(View(host).apply { background = ContextCompat.getDrawable(host, R.drawable.scrim_hero_card) },
			FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		val box = LinearLayout(host).apply {
			orientation = LinearLayout.VERTICAL
			gravity = android.view.Gravity.BOTTOM
			setPadding(Ui.dp(host, 26), Ui.dp(host, 22), Ui.dp(host, 26), Ui.dp(host, 22))
		}
		box.addView(TextView(host).apply {
			text = when (item.type) { "Series", "Episode" -> "SERIES"; "Movie" -> "FILM"; else -> item.type?.uppercase().orEmpty() }
			typeface = Ui.bold(host)
			letterSpacing = 0.14f
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
			setTextColor(0xFFFFFFFF.toInt())
		})
		val name = if (item.type == "Episode") item.seriesName ?: item.name else item.name
		val logo = host.api.logoUrl(host.session, item, 600)
		if (logo != null) {
			val logoView = ImageView(host).apply {
				adjustViewBounds = true
				scaleType = ImageView.ScaleType.FIT_START
				contentDescription = name
			}
			box.addView(logoView, LinearLayout.LayoutParams(Ui.dp(host, 260), Ui.dp(host, 78)).apply { topMargin = Ui.dp(host, 6) })
			Images.load(logoView, logo)
		} else {
			box.addView(TextView(host).apply {
				text = name
				typeface = Ui.bold(host)
				maxLines = 2
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
				setTextColor(0xFFFFFFFF.toInt())
				setPadding(0, Ui.dp(host, 4), 0, 0)
			}, LinearLayout.LayoutParams(Ui.dp(host, 340), ViewGroup.LayoutParams.WRAP_CONTENT))
		}
		val facts = android.text.SpannableStringBuilder()
		item.communityRating?.let {
			val start = facts.length
			facts.append(String.format(java.util.Locale.US, "★ %.1f", it))
			facts.setSpan(android.text.style.ForegroundColorSpan(Ui.color(host, R.attr.lumenGreen)), start, facts.length, 0)
			facts.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, facts.length, 0)
		}
		listOfNotNull(
			item.episodeCode?.let { "$it · ${item.name}" },
			item.year?.toString(),
			item.childCount.takeIf { it > 0 && item.type == "Series" }?.let { "$it Seasons" },
			item.officialRating,
			item.runtimeMs.takeIf { it > 0 && item.type != "Series" }?.let { Formatters.time(it) },
		).forEach { if (facts.isNotEmpty()) facts.append("    "); facts.append(it) }
		box.addView(TextView(host).apply {
			text = facts
			maxLines = 1
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setTextColor(0xE6FFFFFF.toInt())
			setPadding(0, Ui.dp(host, 10), 0, 0)
		})
		if (item.genres.isNotEmpty()) {
			box.addView(TextView(host).apply {
				text = item.genres.take(3).joinToString("  •  ")
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
				setTextColor(0xB3FFFFFF.toInt())
				setPadding(0, Ui.dp(host, 4), 0, 0)
			})
		}
		item.overview?.let { overview ->
			box.addView(TextView(host).apply {
				text = overview
				maxLines = 3
				ellipsize = android.text.TextUtils.TruncateAt.END
				setLineSpacing(0f, 1.2f)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
				setTextColor(0xCCFFFFFF.toInt())
				setPadding(0, Ui.dp(host, 8), 0, 0)
			}, LinearLayout.LayoutParams(Ui.dp(host, 330), ViewGroup.LayoutParams.WRAP_CONTENT))
		}
		val actions = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, Ui.dp(host, 14), 0, 0)
		}
		val resume = item.userData?.hasResume == true
		val play = Pills.create(actions, if (resume) "Resume" else "Play", R.drawable.ic_play)
		play.setOnClickListener { PlayerActivity.play(host, item.id, startMs = -1L) }
		val info = Pills.create(actions, "More Info", R.drawable.ic_info)
		info.setOnClickListener { DetailActivity.start(host, item.id) }
		listOf(play, info).forEach { pill ->
			pill.layoutParams = (pill.layoutParams as LinearLayout.LayoutParams).apply { this.height = Ui.dp(host, 42) }
			pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			Ui.focusScale(pill, 1.05f) { focused -> if (focused) host.setBackdrop(item) }
		}
		box.addView(actions)
		card.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		strip.addView(card, LinearLayout.LayoutParams(width, height).apply { setMargins(Ui.dp(host, 7), Ui.dp(host, 7), Ui.dp(host, 7), Ui.dp(host, 7)) })
		heroButton = play
		// The rest of "Continue watching" as tall cards beside the hero.
		val tall = (height * 0.54f).toInt() to height
		more.forEach { next ->
			val tile = host.cards.create(strip, CardStyle.POSTER, tall)
			host.cards.bind(tile, next, CardStyle.POSTER)
			tile.tag = "resume|${next.id}"
			tile.setOnClickListener { host.openItem(next) }
			Ui.focusScale(tile, 1.05f) { focused -> if (focused) host.setBackdrop(next) }
			strip.addView(tile)
		}
		column.addView(rowView)
		if (host.prefs.animations) {
			card.alpha = 0f
			card.scaleX = 0.97f
			card.scaleY = 0.97f
			card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320L).withLayer().start()
		}
	}

	private fun showError(message: String?) {
		column.removeAllViews()
		buildTitle()
		column.addView(TextView(host).apply {
			text = "Can’t reach your Jellyfin server.\n${message ?: ""}"
			setTextColor(Ui.color(host, R.attr.lumenText2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			setPadding(Ui.dp(host, 8), Ui.dp(host, 16), 0, Ui.dp(host, 16))
		})
		val actions = LinearLayout(host)
		Pills.create(actions, "Retry", R.drawable.ic_refresh).setOnClickListener { load(initial = true) }
		column.addView(actions)
		heroButton = actions.getChildAt(0)
	}
}
