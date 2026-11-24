package app.lumen.player.ui.pages

import android.util.TypedValue
import android.view.KeyEvent
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

	private val scroll = app.lumen.player.ui.TvScrollView(host).apply {
		isFillViewport = true
	}
	private val column = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		// Room for the clock in the corner when the menu is on the left.
		setPadding(Ui.dp(host, 44), Ui.dp(host, if (host.clockOverlay) 50 else 30), 0, Ui.dp(host, 60))
		clipChildren = false
		clipToPadding = false
	}
	override val view: View = scroll

	private var heroButton: View? = null

	/** The banner's views; its content changes as you move between the featured titles. */
	private class Feature(
		val card: FrameLayout,
		val art: ImageView,
		val text: LinearLayout,
		val kind: TextView,
		val logo: ImageView,
		val title: TextView,
		val facts: TextView,
		val overview: TextView,
		val play: TextView,
		val info: TextView,
		val note: TextView,
		val dots: LinearLayout,
	)

	private var feature: Feature? = null
	private var featured: List<JfItem> = emptyList()
	private var featuredIndex = 0
	private val currentFeatured: JfItem? get() = featured.getOrNull(featuredIndex)
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

		val keepId = currentFeatured?.id
		featured = pickFeatured(data)
		featuredIndex = featured.indexOfFirst { it.id == keepId }.coerceAtLeast(0)
		feature = null
		if (host.prefs.showHero && featured.isNotEmpty()) {
			buildFeature()
			showFeature(featuredIndex, animate = false)
		} else buildTitle()

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
				val wide = row.style == CardStyle.THUMB
				val frame = card.findViewById<FrameLayout>(R.id.card_frame)
				Ui.focusScale(card, 1.07f) { focused ->
					if (focused) {
						host.setBackdrop(item)
						// Wide cards play a preview in place of the artwork after a moment.
						if (wide) host.preview.focus(item, frame, 2)
					} else if (wide) host.preview.leave(frame)
				}
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
		currentFeatured?.let { host.setBackdrop(it) }
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
	 * Up to 10 of the newest movies and shows (taking turns between the libraries), for the banner.
	 * Falls back to what you're watching when the libraries have no artwork.
	 */
	private fun pickFeatured(data: List<RowData>): List<JfItem> {
		val lists = data.filter { it.key.startsWith("latest") }.map { row ->
			row.items.filter { it.backdropTags.isNotEmpty() || it.parentBackdropTags.isNotEmpty() }
		}
		val picked = mutableListOf<JfItem>()
		var i = 0
		while (picked.size < 10 && lists.any { it.size > i }) {
			lists.forEach { list -> list.getOrNull(i)?.let { item -> if (picked.size < 10 && picked.none { it.id == item.id }) picked += item } }
			i++
		}
		if (picked.size < 10) {
			data.firstOrNull { it.key == "resume" }?.items.orEmpty()
				.filter { item -> (item.backdropTags.isNotEmpty() || item.parentBackdropTags.isNotEmpty()) && picked.none { it.id == item.id } }
				.take(10 - picked.size).forEach { picked += it }
		}
		return picked
	}

	/** Big streaming-app style banner: artwork, title (or logo), facts, overview, Play and More info. */
	private fun buildFeature() {
		val metrics = host.resources.displayMetrics
		val screenDp = metrics.heightPixels / metrics.density
		val height = Ui.dp(host, (screenDp * 0.6f).toInt().coerceIn(260, 470))
		val card = FrameLayout(host).apply {
			background = ContextCompat.getDrawable(host, R.drawable.bg_feature)
			clipToOutline = true
		}
		val art = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
		card.addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		// (a preview video goes in here, above the artwork and under the shade)
		card.addView(View(host).apply { background = ContextCompat.getDrawable(host, R.drawable.scrim_feature) },
			FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

		// Title, facts and overview sit above the buttons; the buttons are pinned so they are never cut off.
		val text = LinearLayout(host).apply {
			orientation = LinearLayout.VERTICAL
			gravity = android.view.Gravity.BOTTOM
		}
		val kind = TextView(host).apply {
			typeface = Ui.bold(host)
			letterSpacing = 0.2f
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			setTextColor(Ui.color(host, R.attr.lumenAccent))
		}
		text.addView(kind)
		val logo = ImageView(host).apply {
			adjustViewBounds = true
			scaleType = ImageView.ScaleType.FIT_START
			visibility = View.GONE
		}
		text.addView(logo, LinearLayout.LayoutParams(Ui.dp(host, 320), Ui.dp(host, 84)).apply { topMargin = Ui.dp(host, 6) })
		val title = TextView(host).apply {
			typeface = Ui.display(host)
			maxLines = 2
			ellipsize = android.text.TextUtils.TruncateAt.END
			setTextColor(0xFFFFFFFF.toInt())
			setLineSpacing(0f, 0.95f)
			setPadding(0, Ui.dp(host, 2), 0, 0)
		}
		text.addView(title, LinearLayout.LayoutParams(Ui.dp(host, 540), ViewGroup.LayoutParams.WRAP_CONTENT))
		val facts = TextView(host).apply {
			typeface = Ui.bold(host)
			maxLines = 1
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setTextColor(0xF2FFFFFF.toInt())
			setPadding(0, Ui.dp(host, 10), 0, 0)
		}
		text.addView(facts)
		val overview = TextView(host).apply {
			maxLines = 3
			ellipsize = android.text.TextUtils.TruncateAt.END
			setLineSpacing(0f, 1.2f)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			setTextColor(0xE6FFFFFF.toInt())
			setPadding(0, Ui.dp(host, 8), 0, 0)
		}
		text.addView(overview, LinearLayout.LayoutParams(Ui.dp(host, 460), ViewGroup.LayoutParams.WRAP_CONTENT))
		val buttonsArea = Ui.dp(host, 46 + 26 + 14)
		card.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
			setMargins(Ui.dp(host, 40), Ui.dp(host, 18), Ui.dp(host, 24), buttonsArea)
		})

		val actions = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			clipChildren = false
			clipToPadding = false
		}
		val play = Pills.create(actions, "Play", R.drawable.ic_play)
		play.background = ContextCompat.getDrawable(host, R.drawable.bg_pill_primary)
		ContextCompat.getColorStateList(host, R.color.pill_primary_text)?.let {
			play.setTextColor(it)
			play.compoundDrawableTintList = it
		}
		play.setOnClickListener { currentFeatured?.let { playFeatured(it) } }
		val info = Pills.create(actions, "More info", R.drawable.ic_info)
		info.setOnClickListener { currentFeatured?.let { DetailActivity.start(host, it.id) } }
		listOf(play, info).forEach { pill ->
			pill.layoutParams = (pill.layoutParams as LinearLayout.LayoutParams).apply { this.height = Ui.dp(host, 46) }
			Ui.focusScale(pill, 1.06f) { focused ->
				val item = currentFeatured
				if (focused && item != null) {
					host.setBackdrop(item)
					host.preview.focus(item, card, 1)
				} else {
					// Moving between Play and More info keeps the preview; leaving the banner stops it.
					card.post { if (!card.hasFocus()) host.preview.leave(card) }
				}
			}
		}
		card.addView(actions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
			android.view.Gravity.BOTTOM or android.view.Gravity.START).apply { setMargins(Ui.dp(host, 40), 0, 0, Ui.dp(host, 26)) })

		val note = TextView(host).apply {
			typeface = Ui.bold(host)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setTextColor(0xFFFFFFFF.toInt())
			background = ContextCompat.getDrawable(host, R.drawable.bg_hud)
			setPadding(Ui.dp(host, 14), Ui.dp(host, 7), Ui.dp(host, 14), Ui.dp(host, 7))
		}
		card.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
			android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { setMargins(0, 0, Ui.dp(host, 22), Ui.dp(host, 30)) })

		// One dot per featured title; LEFT on Play / RIGHT on More info switch between them.
		val dots = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.CENTER
		}
		featured.forEach { _ ->
			dots.addView(View(host).apply { background = ContextCompat.getDrawable(host, R.drawable.dot_white) },
				LinearLayout.LayoutParams(Ui.dp(host, 7), Ui.dp(host, 7)).apply { setMargins(Ui.dp(host, 4), 0, Ui.dp(host, 4), 0) })
		}
		if (featured.size > 1) {
			card.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dp(host, 12) })
		}

		column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
			marginEnd = Ui.dp(host, 44)
			bottomMargin = Ui.dp(host, 4)
		})
		feature = Feature(card, art, text, kind, logo, title, facts, overview, play, info, note, dots)
		heroButton = play
		if (host.prefs.animations) {
			card.alpha = 0f
			card.animate().alpha(1f).setDuration(320L).withLayer().start()
		}
	}

	/** Puts featured title [index] in the banner (cross-fading the artwork). */
	private fun showFeature(index: Int, animate: Boolean = true) {
		val v = feature ?: return
		val item = featured.getOrNull(index) ?: return
		featuredIndex = index
		val resume = item.userData?.hasResume == true

		crossfade(v.art, host.api.backdropUrl(host.session, item, 1280) ?: host.api.thumbUrl(host.session, item, 1280))
		v.kind.text = when {
			resume -> "CONTINUE WATCHING"
			item.type == "Series" -> "SERIES"
			item.type == "Movie" -> "FILM"
			item.type == "Episode" -> "EPISODE"
			else -> item.type.uppercase(java.util.Locale.getDefault())
		}
		val name = if (item.type == "Episode") item.seriesName ?: item.name else item.name
		val logo = host.api.logoUrl(host.session, item, 700)
		if (logo != null) {
			v.logo.visibility = View.VISIBLE
			v.logo.contentDescription = name
			Images.load(v.logo, logo)
			v.title.visibility = View.GONE
		} else {
			v.logo.visibility = View.GONE
			v.title.visibility = View.VISIBLE
			v.title.text = name
			v.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (name.length > 20) 34f else 44f)
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
		v.facts.text = facts
		v.overview.text = item.overview.orEmpty()
		v.overview.visibility = if (item.overview.isNullOrBlank()) View.GONE else View.VISIBLE
		v.overview.maxLines = 3
		fitText(v)
		v.play.text = if (resume) "Resume" else "Play"
		val note = featureNote(item)
		v.note.text = note
		v.note.visibility = if (note == null) View.GONE else View.VISIBLE
		for (i in 0 until v.dots.childCount) {
			v.dots.getChildAt(i).alpha = if (i == index) 1f else 0.35f
			v.dots.getChildAt(i).scaleX = if (i == index) 1.3f else 1f
			v.dots.getChildAt(i).scaleY = if (i == index) 1.3f else 1f
		}
		if (animate && host.prefs.animations) {
			v.text.alpha = 0f
			v.text.translationX = Ui.dp(host, 14).toFloat()
			v.text.animate().alpha(1f).translationX(0f).setDuration(220L).withLayer().start()
		}
		if (v.card.hasFocus()) {
			host.setBackdrop(item)
			host.preview.focus(item, v.card, 1)
		}
	}

	/** Shortens the overview until title, facts and overview fit above the buttons. */
	private fun fitText(v: Feature) {
		v.text.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
			override fun onPreDraw(): Boolean {
				val layout = v.overview.layout
				val clipped = v.overview.visibility == View.VISIBLE && layout != null && layout.lineCount > 0 &&
					v.overview.height < layout.getLineBottom(layout.lineCount - 1) + v.overview.totalPaddingTop + v.overview.totalPaddingBottom - 1
				val first = (0 until v.text.childCount).map { v.text.getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE }
				val overflow = clipped || (first != null && first.top < 0)
				if (overflow && v.overview.visibility == View.VISIBLE) {
					if (v.overview.maxLines > 1) v.overview.maxLines -= 1 else v.overview.visibility = View.GONE
					return false // measure again before drawing
				}
				v.text.viewTreeObserver.removeOnPreDrawListener(this)
				return true
			}
		})
	}

	/** Swaps the banner artwork without flashing to black. */
	private fun crossfade(view: ImageView, url: String?) {
		view.setTag(R.id.tag_image_url, url)
		if (url == null) {
			view.setImageDrawable(null)
			return
		}
		Images.fetch(url) { bitmap ->
			if (view.getTag(R.id.tag_image_url) != url || bitmap == null) return@fetch
			val next = android.graphics.drawable.BitmapDrawable(host.resources, bitmap)
			val current = view.drawable
			val previous = if (current is android.graphics.drawable.TransitionDrawable) current.getDrawable(current.numberOfLayers - 1) else current
			if (previous == null) {
				view.setImageDrawable(next)
				view.alpha = 0f
				view.animate().alpha(1f).setDuration(300L).start()
			} else {
				val blend = android.graphics.drawable.TransitionDrawable(arrayOf(previous, next)).apply { isCrossFadeEnabled = true }
				view.setImageDrawable(blend)
				blend.startTransition(380)
			}
		}
	}

	/** LEFT on Play / RIGHT on More info move between the featured titles. */
	override fun onKey(event: KeyEvent): Boolean {
		val v = feature ?: return false
		val count = featured.size
		if (count < 2) return false
		val focused = host.currentFocus
		return when {
			event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focused === v.info -> {
				showFeature((featuredIndex + 1) % count)
				true
			}
			event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focused === v.play && (featuredIndex > 0 || host.topNavigation) -> {
				showFeature((featuredIndex - 1 + count) % count)
				true
			}
			else -> false
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
		host.preview.stop()
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
