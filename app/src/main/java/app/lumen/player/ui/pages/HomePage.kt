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
				RowData("latest:${library.id}", "Latest ${library.name}", if (prefs.homeWideCards) CardStyle.THUMB else CardStyle.POSTER, items)
			}
		}
		val wide = prefs.homeWideCards
		fun style(default: CardStyle) = if (wide) CardStyle.THUMB else default
		buildList {
			add(RowData("resume", "Continue watching", CardStyle.THUMB, resume.await()))
			add(RowData("nextup", "Next up", CardStyle.THUMB, nextUp.await()))
			latest.forEach { add(it.await()) }
			add(RowData("upcoming", "Upcoming", CardStyle.THUMB, upcoming.await()))
			add(RowData("favorites", "Favorites", style(CardStyle.POSTER), favorites.await()))
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

		val featured = pickFeatured(data)
		hero = featured
		if (host.prefs.showHero && featured != null) buildFeature(featured) else buildTitle()

		if (data.isEmpty()) {
			column.addView(TextView(host).apply {
				text = "Nothing here yet — add some media to your Jellyfin libraries."
				setTextColor(Ui.color(host, R.attr.lumenText2))
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
				setPadding(Ui.dp(host, 8), Ui.dp(host, 24), 0, 0)
			})
		}

		var refocus: View? = null
		data.forEachIndexed { rowIndex, row ->
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

	/**
	 * Today's featured title: one of the newest unwatched movies / shows with artwork (changes daily),
	 * otherwise what you're in the middle of.
	 */
	private fun pickFeatured(data: List<RowData>): JfItem? {
		val fresh = data.filter { it.key.startsWith("latest") }.flatMap { it.items }
			.filter { it.backdropTags.isNotEmpty() && it.userData?.played != true }
			.distinctBy { it.id }.take(10)
		if (fresh.isNotEmpty()) {
			val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
			return fresh[day % fresh.size]
		}
		return data.firstOrNull { it.key == "resume" }?.items?.firstOrNull()
			?: data.flatMap { it.items }.firstOrNull { it.backdropTags.isNotEmpty() || it.parentBackdropTags.isNotEmpty() }
	}

	/** Big streaming-app style banner: artwork, title (or logo), facts, overview, Play and More info. */
	private fun buildFeature(item: JfItem) {
		val metrics = host.resources.displayMetrics
		val screenDp = metrics.heightPixels / metrics.density
		val height = Ui.dp(host, (screenDp * 0.56f).toInt().coerceIn(250, 440))
		val card = FrameLayout(host).apply {
			background = ContextCompat.getDrawable(host, R.drawable.bg_feature)
			clipToOutline = true
		}
		val art = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
		card.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		Images.load(art, host.api.backdropUrl(host.session, item, 1280) ?: host.api.thumbUrl(host.session, item, 1280))
		card.addView(View(host).apply { background = ContextCompat.getDrawable(host, R.drawable.scrim_feature) },
			FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

		val box = LinearLayout(host).apply {
			orientation = LinearLayout.VERTICAL
			gravity = android.view.Gravity.BOTTOM
			setPadding(Ui.dp(host, 40), Ui.dp(host, 26), Ui.dp(host, 24), Ui.dp(host, 30))
			clipChildren = false
			clipToPadding = false
		}
		val resume = item.userData?.hasResume == true
		box.addView(TextView(host).apply {
			text = when {
				resume -> "CONTINUE WATCHING"
				item.type == "Series" -> "SERIES"
				item.type == "Movie" -> "FILM"
				item.type == "Episode" -> "EPISODE"
				else -> item.type?.uppercase(java.util.Locale.getDefault()).orEmpty()
			}
			typeface = Ui.bold(host)
			letterSpacing = 0.2f
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			setTextColor(Ui.color(host, R.attr.lumenAccent))
		})
		val name = if (item.type == "Episode") item.seriesName ?: item.name else item.name
		val logo = host.api.logoUrl(host.session, item, 700)
		if (logo != null) {
			val logoView = ImageView(host).apply {
				adjustViewBounds = true
				scaleType = ImageView.ScaleType.FIT_START
				contentDescription = name
			}
			box.addView(logoView, LinearLayout.LayoutParams(Ui.dp(host, 340), Ui.dp(host, 100)).apply { topMargin = Ui.dp(host, 8) })
			Images.load(logoView, logo)
		} else {
			box.addView(TextView(host).apply {
				text = name
				typeface = Ui.display(host)
				maxLines = 2
				ellipsize = android.text.TextUtils.TruncateAt.END
				setTextSize(TypedValue.COMPLEX_UNIT_SP, if (name.length > 20) 36f else 46f)
				setTextColor(0xFFFFFFFF.toInt())
				setLineSpacing(0f, 0.95f)
				setPadding(0, Ui.dp(host, 4), 0, 0)
			}, LinearLayout.LayoutParams(Ui.dp(host, 520), ViewGroup.LayoutParams.WRAP_CONTENT))
		}

		// "Series  •  Drama  •  2024  •  TV-14  •  ★ 8.1"
		val facts = android.text.SpannableStringBuilder()
		fun add(part: String?) {
			if (part.isNullOrBlank()) return
			if (facts.isNotEmpty()) facts.append("  •  ")
			facts.append(part)
		}
		add(item.episodeCode?.let { "$it · ${item.name}" } ?: when (item.type) { "Series" -> "Series"; "Movie" -> "Film"; else -> null })
		item.genres.take(2).forEach { add(it) }
		add(item.year?.toString())
		add(item.officialRating)
		add(item.childCount.takeIf { it > 0 && item.type == "Series" }?.let { if (it == 1) "1 season" else "$it seasons" })
		item.communityRating?.let {
			if (facts.isNotEmpty()) facts.append("  •  ")
			val start = facts.length
			facts.append(String.format(java.util.Locale.US, "★ %.1f", it))
			facts.setSpan(android.text.style.ForegroundColorSpan(Ui.color(host, R.attr.lumenGreen)), start, facts.length, 0)
		}
		box.addView(TextView(host).apply {
			text = facts
			typeface = Ui.bold(host)
			maxLines = 1
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setTextColor(0xF2FFFFFF.toInt())
			setPadding(0, Ui.dp(host, 12), 0, 0)
		})
		item.overview?.let { overview ->
			box.addView(TextView(host).apply {
				text = overview
				maxLines = 3
				ellipsize = android.text.TextUtils.TruncateAt.END
				setLineSpacing(0f, 1.22f)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
				setTextColor(0xE6FFFFFF.toInt())
				setPadding(0, Ui.dp(host, 10), 0, 0)
			}, LinearLayout.LayoutParams(Ui.dp(host, 450), ViewGroup.LayoutParams.WRAP_CONTENT))
		}

		val actions = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, Ui.dp(host, 18), 0, 0)
			clipChildren = false
			clipToPadding = false
		}
		val play = Pills.create(actions, if (resume) "Resume" else "Play", R.drawable.ic_play)
		play.background = ContextCompat.getDrawable(host, R.drawable.bg_pill_primary)
		ContextCompat.getColorStateList(host, R.color.pill_primary_text)?.let {
			play.setTextColor(it)
			play.compoundDrawableTintList = it
		}
		play.setOnClickListener { playFeatured(item) }
		val info = Pills.create(actions, "More info", R.drawable.ic_info)
		info.setOnClickListener { DetailActivity.start(host, item.id) }
		listOf(play, info).forEach { pill -> Ui.focusScale(pill, 1.06f) { focused -> if (focused) host.setBackdrop(item) } }
		box.addView(actions)
		card.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

		featureNote(item)?.let { note ->
			card.addView(TextView(host).apply {
				text = note
				typeface = Ui.bold(host)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
				setTextColor(0xFFFFFFFF.toInt())
				background = ContextCompat.getDrawable(host, R.drawable.bg_hud)
				setPadding(Ui.dp(host, 14), Ui.dp(host, 7), Ui.dp(host, 14), Ui.dp(host, 7))
			}, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { setMargins(0, 0, Ui.dp(host, 22), Ui.dp(host, 24)) })
		}

		column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
			marginEnd = Ui.dp(host, 44)
			bottomMargin = Ui.dp(host, 4)
		})
		heroButton = play
		if (host.prefs.animations) {
			card.alpha = 0f
			card.animate().alpha(1f).setDuration(320L).withLayer().start()
		}
	}

	/** Small note in the banner's corner: time left, unwatched episodes. */
	private fun featureNote(item: JfItem): String? {
		val user = item.userData
		if (user != null && user.hasResume && item.runtimeMs > 0) {
			val leftMin = ((item.runtimeMs - user.positionMs) / 60_000L).coerceAtLeast(1)
			return if (leftMin >= 60) "${leftMin / 60} h ${leftMin % 60} min left" else "$leftMin min left"
		}
		val unplayed = user?.unplayedCount ?: 0
		if (item.type == "Series" && unplayed > 0) return if (unplayed == 1) "1 new episode" else "$unplayed unwatched episodes"
		return null
	}

	/** Play: resume / start a movie or episode; for a show, its next episode. */
	private fun playFeatured(item: JfItem) {
		if (item.type != "Series") {
			PlayerActivity.play(host, item.id, startMs = -1L)
			return
		}
		host.lifecycleScope.launch {
			val next = runCatching {
				host.api.nextUp(host.session, 1, item.id).firstOrNull()
					?: host.api.episodes(host.session, item.id, null).firstOrNull()
			}.getOrNull()
			if (next != null) PlayerActivity.play(host, next.id, startMs = -1L) else DetailActivity.start(host, item.id)
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
