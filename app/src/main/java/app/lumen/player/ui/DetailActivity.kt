package app.lumen.player.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.lumen.player.R
import app.lumen.player.engine.PlayerMode
import app.lumen.player.jellyfin.ItemQuery
import app.lumen.player.jellyfin.JfItem
import app.lumen.player.jellyfin.Session
import app.lumen.player.util.Formatters
import app.lumen.player.util.Images
import app.lumen.player.util.Ui
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Details for a movie, show, season, episode or collection, with Play and the player picker. */
class DetailActivity : BaseActivity() {

	companion object {
		private const val EXTRA_ID = "item_id"

		fun start(context: Context, itemId: String) {
			context.startActivity(Intent(context, DetailActivity::class.java).putExtra(EXTRA_ID, itemId))
		}
	}

	private lateinit var session: Session
	private lateinit var cards: Cards
	private lateinit var itemId: String

	private lateinit var backdrop: ImageView
	private lateinit var logo: ImageView
	private lateinit var titleView: TextView
	private lateinit var subtitleView: TextView
	private lateinit var meta: TextView
	private lateinit var genres: TextView
	private lateinit var overview: TextView
	private lateinit var mediaInfo: TextView
	private lateinit var actions: FlowRow
	private lateinit var sections: LinearLayout
	private lateinit var loading: View
	private lateinit var scroll: ScrollView

	private var item: JfItem? = null
	private var playTarget: JfItem? = null
	private var chosenMode: PlayerMode? = null
	private var selectedSeasonId: String? = null
	private var loadedOnce = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val current = session()
		val id = intent.getStringExtra(EXTRA_ID)
		if (current == null || id == null) {
			finish()
			return
		}
		session = current
		itemId = id
		cards = Cards(this, prefs, api, session)
		setContentView(R.layout.activity_detail)
		backdrop = findViewById(R.id.backdrop)
		logo = findViewById(R.id.logo)
		titleView = findViewById(R.id.title)
		titleView.typeface = app.lumen.player.util.Ui.display(this) // serif in Lolish amber, bold sans in Glass / purple
		subtitleView = findViewById(R.id.subtitle)
		meta = findViewById(R.id.meta)
		genres = findViewById(R.id.genres)
		overview = findViewById(R.id.overview)
		mediaInfo = findViewById(R.id.media_info)
		actions = findViewById(R.id.actions)
		sections = findViewById(R.id.sections)
		loading = findViewById(R.id.loading)
		scroll = findViewById(R.id.scroll)
		load(firstTime = true)
	}

	override fun onResume() {
		super.onResume()
		if (loadedOnce) load(firstTime = false)
	}

	private fun load(firstTime: Boolean) {
		lifecycleScope.launch {
			val result = runCatching { api.itemDetails(session, itemId) }
			loading.visibility = View.GONE
			val loaded = result.getOrElse {
				titleView.text = "Couldn’t load this item"
				overview.text = it.message
				return@launch
			}
			item = loaded
			loadedOnce = true
			val focusedTag = currentFocus?.tag
			bindHeader(loaded)
			bindActions(loaded)
			if (firstTime || sections.childCount == 0) bindSections(loaded) else refreshEpisodes(loaded)
			if (firstTime) actions.getChildAt(0)?.requestFocus()
			else findTagged(focusedTag)?.requestFocus()
		}
	}

	private fun findTagged(tag: Any?): View? {
		if (tag == null) return null
		return actions.findViewWithTag(tag) ?: sections.findViewWithTag(tag)
	}

	// Header --------------------------------------------------------------------------------------

	private fun bindHeader(item: JfItem) {
		val backdropUrl = api.backdropUrl(session, item, 1920)
		if (backdropUrl != null) {
			Images.load(backdrop, backdropUrl, fade = false) { bitmap ->
				if (bitmap != null) backdrop.animate().alpha(0.55f).setDuration(400L).start()
			}
		}

		val displayTitle = when (item.type) {
			"Episode" -> item.seriesName ?: item.name
			"Season" -> item.seriesName ?: item.name
			else -> item.name
		}
		titleView.text = displayTitle
		val logoUrl = api.logoUrl(session, item)
		if (logoUrl != null) {
			Images.load(logo, logoUrl) { bitmap ->
				val hasLogo = bitmap != null
				logo.visibility = if (hasLogo) View.VISIBLE else View.GONE
				titleView.visibility = if (hasLogo) View.GONE else View.VISIBLE
			}
		}

		val sub = when (item.type) {
			"Episode" -> listOfNotNull(item.episodeCode, item.name).joinToString("  —  ")
			"Season" -> item.name
			else -> item.tagline
		}
		subtitleView.text = sub
		subtitleView.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE

		val endsAt = item.runtimeMs.takeIf { it > 0 && item.isPlayable }?.let { runtime ->
			val left = runtime - (item.userData?.positionMs ?: 0L)
			"Ends at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + left))
		}
		meta.text = listOfNotNull(
			item.year?.toString(),
			item.communityRating?.let { String.format(Locale.US, "★ %.1f", it) },
			item.criticRating?.let { "🍅 $it%" },
			item.officialRating,
			item.runtimeMs.takeIf { it > 0 }?.let { Formatters.time(it) },
			item.childCount.takeIf { it > 0 && item.type == "Series" }?.let { "$it seasons" },
			endsAt,
		).joinToString("    ·    ")
		genres.text = item.genres.take(4).joinToString("  ·  ")
		overview.text = item.overview ?: ""
		val media = item.media
		if (media != null && (media.video != null || media.audio != null)) {
			mediaInfo.text = listOfNotNull(media.video, media.audio, media.subtitles.takeIf { it > 0 }?.let { "$it subtitles" })
				.joinToString("   ·   ")
			mediaInfo.visibility = View.VISIBLE
		} else {
			mediaInfo.visibility = View.GONE
		}
	}

	// Buttons -------------------------------------------------------------------------------------

	private fun bindActions(item: JfItem) {
		actions.removeAllViews()
		when {
			item.isPlayable -> {
				playTarget = item
				addPlayButtons(item)
			}
			item.type == "Series" || item.type == "Season" -> {
				lifecycleScope.launch {
					val seriesId = item.seriesId?.takeIf { item.type == "Season" } ?: item.id
					val next = runCatching { api.nextUp(session, 1, seriesId).firstOrNull() }.getOrNull()
						?: runCatching { api.episodes(session, seriesId, item.id.takeIf { item.type == "Season" }).firstOrNull() }.getOrNull()
					playTarget = next
					val hadFocus = actions.findFocus() != null || currentFocus == null
					actions.removeAllViews()
					if (next != null) addPlayButtons(next, label = next.episodeCode)
					addCommonButtons(item)
					if (hadFocus) actions.getChildAt(0)?.requestFocus()
				}
				return
			}
		}
		addCommonButtons(item)
	}

	private fun addPlayButtons(target: JfItem, label: String? = null) {
		val resumeMs = target.userData?.takeIf { it.hasResume }?.positionMs ?: 0L
		val text = when {
			resumeMs > 0 -> "Resume ${label ?: ""} ${Formatters.time(resumeMs)}".replace("  ", " ")
			label != null -> "Play $label"
			else -> "Play"
		}
		val play = Pills.create(actions, text.trim(), R.drawable.ic_play)
		play.tag = "play"
		play.setOnClickListener { PlayerActivity.play(this, target.id, -1L, chosenMode) }
		if (resumeMs > 0) {
			val restart = Pills.create(actions, "Start over", R.drawable.ic_replay)
			restart.tag = "restart"
			restart.setOnClickListener { PlayerActivity.play(this, target.id, 0L, chosenMode) }
		}
		// Second line: smaller buttons for the less used choices.
		val playerPill = Pills.compact(Pills.create(actions, playerLabel(target), R.drawable.ic_swap))
		(playerPill.layoutParams as? FlowRow.LayoutParams)?.newLine = true
		playerPill.tag = "player"
		playerPill.setOnClickListener {
			val modes = listOf<PlayerMode?>(null) + PlayerMode.entries
			chosenMode = modes[(modes.indexOf(chosenMode) + 1) % modes.size]
			playerPill.text = playerLabel(target)
		}
	}

	private fun playerLabel(target: JfItem): String {
		chosenMode?.let { return "Player: ${it.label}" }
		val remembered = PlayerMode.fromName(prefs.rememberedPlayerMode(target.id, target.seriesId))
		val default = PlayerMode.fromName(prefs.defaultPlayerMode) ?: PlayerMode.EXO_DIRECT
		return if (remembered != null) "Player: ${remembered.label} ✓" else "Player: Auto (${default.label})"
	}

	private fun addCommonButtons(item: JfItem) {
		val played = item.userData?.played == true
		// Without play buttons (e.g. a show still loading) these are the only line, at full size.
		val small = actions.childCount > 0
		fun pill(text: String, icon: Int) = Pills.create(actions, text, icon).also { if (small) Pills.compact(it) }
		val watched = pill(if (played) "Watched" else "Mark watched", R.drawable.ic_check_circle)
		watched.tag = "watched"
		watched.isSelected = played
		watched.setOnClickListener {
			lifecycleScope.launch {
				val now = !(this@DetailActivity.item?.userData?.played ?: false)
				runCatching { api.setPlayed(session, item.id, now) }
					.onSuccess { load(firstTime = false) }
			}
		}
		val favorite = item.userData?.favorite == true
		val heart = pill(if (favorite) "Favorite" else "Favorite +", if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
		heart.tag = "favorite"
		heart.isSelected = favorite
		heart.setOnClickListener {
			lifecycleScope.launch {
				val now = !(this@DetailActivity.item?.userData?.favorite ?: false)
				runCatching { api.setFavorite(session, item.id, now) }
					.onSuccess { load(firstTime = false) }
			}
		}
		// Follow a show: its new episodes come first on Home ("New episodes for you").
		val followId = if (item.type == "Series") item.id else item.seriesId?.takeIf { item.type == "Season" || item.type == "Episode" }
		if (followId != null) {
			fun trackLabel(): String = when {
				!prefs.isTracked(followId) -> "Track"
				followId !in prefs.trackedSeries -> "Tracking (auto)"
				else -> "Tracking"
			}
			val track = pill(trackLabel(), if (prefs.isTracked(followId)) R.drawable.ic_check_circle else R.drawable.ic_event)
			track.tag = "track"
			track.isSelected = prefs.isTracked(followId)
			track.setOnClickListener {
				val now = !prefs.isTracked(followId)
				prefs.setTracked(followId, now)
				track.text = trackLabel()
				track.isSelected = now
				Pills.setIcon(track, if (now) R.drawable.ic_check_circle else R.drawable.ic_event)
				if (small) Pills.compact(track)
				android.widget.Toast.makeText(
					this,
					if (now) "New episodes of this show will show up first on Home" else "Not tracking this show any more",
					android.widget.Toast.LENGTH_SHORT,
				).show()
			}
		}
		if (item.type == "Episode" && item.seriesId != null) {
			val series = pill("Series", R.drawable.ic_tv)
			series.tag = "series"
			series.setOnClickListener { start(this, item.seriesId) }
		}
	}

	// Rows below -----------------------------------------------------------------------------------

	private fun bindSections(item: JfItem) {
		sections.removeAllViews()
		lifecycleScope.launch {
			when (item.type) {
				// Shows, seasons and episodes all get the season picker, so any season is one press away.
				"Series" -> showSeasons(item.id, playTarget?.seasonId)
				"Season" -> item.seriesId?.let { showSeasons(it, item.id) }
				"Episode" -> item.seriesId?.let { showSeasons(it, item.seasonId) }
				"BoxSet" -> {
					val inside = runCatching {
						api.items(session, ItemQuery(parentId = item.id, sortBy = "PremiereDate,ProductionYear", recursive = false, limit = 200)).items
					}.getOrDefault(emptyList())
					addCardRow("In this collection", inside, CardStyle.POSTER)
				}
			}
			val cast = item.people.filter { it.type == "Actor" || it.type == "GuestStar" }.take(24)
			if (cast.isNotEmpty()) {
				val (row, strip) = cards.row("Cast", null)
				cast.forEach { person -> strip.addView(cards.createPerson(strip, person)) }
				addSection(row)
			}
			if (item.type == "Movie" || item.type == "Series") {
				val similar = runCatching { api.similar(session, item.id) }.getOrDefault(emptyList())
				addCardRow("More like this", similar, CardStyle.POSTER)
			}
		}
	}

	/** Rows glide in as they load (hardware layer; off with Settings → Appearance → Animations). */
	private fun addSection(row: View) {
		sections.addView(row)
		if (!prefs.animations) return
		row.alpha = 0f
		row.translationY = Ui.dp(this, 20).toFloat()
		row.animate().alpha(1f).translationY(0f).setDuration(260L)
			.setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).withLayer().start()
	}

	private suspend fun showSeasons(seriesId: String, preferredSeasonId: String?) {
		val seasons = runCatching { api.seasons(session, seriesId) }.getOrDefault(emptyList())
		if (seasons.isEmpty()) {
			selectedSeasonId = preferredSeasonId
			addEpisodeRow(seriesId, preferredSeasonId)
			return
		}
		selectedSeasonId = seasons.firstOrNull { it.id == preferredSeasonId }?.id ?: seasons.first().id
		addSeasonRow(seriesId, seasons)
		addEpisodeRow(seriesId, selectedSeasonId)
	}

	private var seasonStrip: LinearLayout? = null
	private var episodeStrip: LinearLayout? = null
	private var episodeSeriesId: String? = null

	private fun addSeasonRow(seriesId: String, seasons: List<JfItem>) {
		val (row, strip) = cards.row("Seasons", seasons.size)
		seasons.forEach { season ->
			val pill = Pills.create(strip, season.name)
			pill.tag = "season:${season.id}"
			pill.isSelected = season.id == selectedSeasonId
			(pill.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = Ui.dp(this, 10)
			pill.setOnClickListener {
				selectedSeasonId = season.id
				for (i in 0 until strip.childCount) strip.getChildAt(i).isSelected = strip.getChildAt(i) == pill
				lifecycleScope.launch { fillEpisodes(seriesId, season.id) }
			}
		}
		seasonStrip = strip
		// Bring the selected season into view.
		strip.post {
			val selected = (0 until strip.childCount).map { strip.getChildAt(it) }.firstOrNull { it.isSelected }
			if (selected != null) (strip.parent as? View)?.scrollTo((selected.left - Ui.dp(this, 24)).coerceAtLeast(0), 0)
		}
		addSection(row)
	}

	private suspend fun addEpisodeRow(seriesId: String, seasonId: String?, title: String = "Episodes") {
		val (row, strip) = cards.row(title, null)
		episodeStrip = strip
		episodeSeriesId = seriesId
		addSection(row)
		fillEpisodes(seriesId, seasonId)
	}

	private suspend fun fillEpisodes(seriesId: String, seasonId: String?) {
		val strip = episodeStrip ?: return
		val episodes = runCatching { api.episodes(session, seriesId, seasonId) }.getOrDefault(emptyList())
		strip.removeAllViews()
		var focusTarget: View? = null
		episodes.forEach { episode ->
			val card = cards.create(strip, CardStyle.THUMB)
			cards.bind(card, episode, CardStyle.THUMB)
			card.tag = "ep:${episode.id}"
			card.setOnClickListener { PlayerActivity.play(this, episode.id, -1L, chosenMode) }
			card.setOnLongClickListener {
				start(this, episode.id)
				true
			}
			strip.addView(card)
			if (episode.id == playTarget?.id) focusTarget = card
		}
		focusTarget?.let { target ->
			strip.post { (strip.parent as? View)?.scrollTo(target.left - Ui.dp(this, 24), 0) }
		}
	}

	/** After returning from the player: refresh the episode row's progress / watched badges. */
	private fun refreshEpisodes(item: JfItem) {
		val seriesId = episodeSeriesId ?: return
		lifecycleScope.launch {
			val strip = episodeStrip ?: return@launch
			val episodes = runCatching { api.episodes(session, seriesId, selectedSeasonId) }.getOrDefault(emptyList())
			if (episodes.size == strip.childCount) {
				episodes.forEachIndexed { index, episode -> cards.bind(strip.getChildAt(index), episode, CardStyle.THUMB) }
			} else {
				fillEpisodes(seriesId, selectedSeasonId)
			}
		}
	}

	private fun addCardRow(title: String, items: List<JfItem>, style: CardStyle) {
		if (items.isEmpty()) return
		val (row, strip) = cards.row(title, items.size)
		items.forEach { entry ->
			val card = cards.create(strip, style)
			cards.bind(card, entry, style)
			card.tag = "card:${entry.id}"
			card.setOnClickListener { start(this, entry.id) }
			strip.addView(card)
		}
		addSection(row)
	}
}
