package app.lumen.player.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.lumen.player.R
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.JfItem
import app.lumen.player.jellyfin.Person
import app.lumen.player.jellyfin.Session
import app.lumen.player.util.Images
import app.lumen.player.util.Prefs
import app.lumen.player.util.Ui
import java.util.Locale

enum class CardStyle { POSTER, THUMB }

/** Creates and binds poster / landscape cards and the horizontal rows that hold them. */
class Cards(
	private val context: Context,
	private val prefs: Prefs,
	private val api: JellyfinApi,
	private val session: Session,
) {
	private val inflater = LayoutInflater.from(context)

	fun cardSize(style: CardStyle): Pair<Int, Int> {
		val width = Ui.dp(context, if (style == CardStyle.POSTER) Ui.posterWidthDp(prefs) else Ui.thumbWidthDp(prefs))
		val height = if (style == CardStyle.POSTER) (width * 1.5f).toInt() else (width * 9f / 16f).toInt()
		return width to height
	}

	fun create(parent: ViewGroup, style: CardStyle): View {
		val view = inflater.inflate(R.layout.item_card, parent, false)
		val (width, height) = cardSize(style)
		val frame = view.findViewById<FrameLayout>(R.id.card_frame)
		frame.layoutParams = frame.layoutParams.apply {
			this.width = width
			this.height = height
		}
		frame.clipToOutline = true
		listOf(R.id.card_title, R.id.card_subtitle).forEach { id ->
			val text = view.findViewById<TextView>(id)
			text.layoutParams = text.layoutParams.apply { this.width = width }
			text.visibility = if (prefs.showCardTitles) View.VISIBLE else View.GONE
		}
		Ui.focusScale(view, 1.07f)
		return view
	}

	fun bind(view: View, item: JfItem, style: CardStyle) {
		val (width, _) = cardSize(style)
		val image = view.findViewById<ImageView>(R.id.card_image)
		val fallback = view.findViewById<TextView>(R.id.card_fallback)
		val badge = view.findViewById<TextView>(R.id.card_badge)
		val rating = view.findViewById<TextView>(R.id.card_rating)
		val progress = view.findViewById<ProgressBar>(R.id.card_progress)

		fallback.text = item.seriesName?.takeIf { item.type == "Episode" } ?: item.name
		val url = if (style == CardStyle.POSTER) api.posterUrl(session, item, width.coerceAtMost(500))
		else api.thumbUrl(session, item, width.coerceAtMost(720))
		Images.load(image, url)

		view.findViewById<TextView>(R.id.card_title).text = titleFor(item, style)
		view.findViewById<TextView>(R.id.card_subtitle).text = subtitleFor(item, style)

		val user = item.userData
		when {
			user?.played == true && item.type != "Series" -> showBadge(badge, "✓")
			item.type == "Series" && (user?.unplayedCount ?: 0) > 0 -> showBadge(badge, "${user?.unplayedCount}")
			else -> badge.visibility = View.GONE
		}

		val score = item.communityRating
		if (prefs.showRatings && score != null && score > 0 && style == CardStyle.POSTER) {
			rating.text = String.format(Locale.US, "★ %.1f", score)
			rating.visibility = View.VISIBLE
		} else {
			rating.visibility = View.GONE
		}

		val percent = user?.playedPercent ?: 0.0
		if (user != null && user.hasResume && percent > 0) {
			progress.progress = (percent * 10).toInt().coerceIn(0, 1000)
			progress.visibility = View.VISIBLE
		} else {
			progress.visibility = View.GONE
		}
		view.contentDescription = item.name
	}

	private fun showBadge(badge: TextView, text: String) {
		badge.text = text
		badge.visibility = View.VISIBLE
	}

	private fun titleFor(item: JfItem, style: CardStyle): String = when {
		item.type == "Episode" && style == CardStyle.POSTER -> item.seriesName ?: item.name
		item.type == "Episode" -> item.name
		else -> item.name
	}

	private fun subtitleFor(item: JfItem, style: CardStyle): String = when (item.type) {
		"Episode" -> listOfNotNull(item.episodeCode, item.seriesName.takeIf { style == CardStyle.THUMB })
			.joinToString("  ·  ")
		"Series" -> listOfNotNull(item.year?.toString(), item.status?.takeIf { it == "Continuing" }?.let { "Ongoing" }).joinToString("  ·  ")
		"Season" -> if (item.childCount > 0) "${item.childCount} episodes" else ""
		else -> listOfNotNull(item.year?.toString(), item.officialRating).joinToString("  ·  ")
	}

	fun createPerson(parent: ViewGroup, person: Person): View {
		val view = inflater.inflate(R.layout.item_person, parent, false)
		view.findViewById<View>(R.id.person_frame).clipToOutline = true
		Images.load(view.findViewById(R.id.person_image), api.personUrl(session, person))
		view.findViewById<TextView>(R.id.person_name).text = person.name
		view.findViewById<TextView>(R.id.person_role).text = person.role ?: person.type ?: ""
		Ui.focusScale(view, 1.07f)
		return view
	}

	/**
	 * A titled horizontal row: "Continue watching 4" in the Li'Server heading style, cards below.
	 * Returns the row and its card container.
	 */
	fun row(title: String, count: Int?): Pair<View, LinearLayout> {
		val row = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, Ui.dp(context, 18), 0, 0)
		}
		row.addView(heading(title, count))
		val scroller = HorizontalScrollView(context).apply {
			isHorizontalScrollBarEnabled = false
			clipToPadding = false
			clipChildren = false
			isFillViewport = false
			overScrollMode = View.OVER_SCROLL_NEVER
		}
		val strip = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			clipChildren = false
			clipToPadding = false
			setPadding(Ui.dp(context, 2), Ui.dp(context, 8), Ui.dp(context, 48), Ui.dp(context, 8))
		}
		scroller.addView(strip)
		row.addView(scroller)
		return row to strip
	}

	fun heading(title: String, count: Int?, sizeSp: Float = 24f): View {
		val line = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = android.view.Gravity.BOTTOM
			setPadding(Ui.dp(context, 8), 0, 0, Ui.dp(context, 2))
		}
		val dot = View(context).apply {
			background = ContextCompat.getDrawable(context, R.drawable.dot_green)
		}
		line.addView(dot, LinearLayout.LayoutParams(Ui.dp(context, 8), Ui.dp(context, 8)).apply {
			marginEnd = Ui.dp(context, 12)
			bottomMargin = Ui.dp(context, 11)
		})
		line.addView(TextView(context).apply {
			text = title
			typeface = Ui.display(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
			setTextColor(ContextCompat.getColor(context, R.color.lumen_text))
		})
		if (count != null && count > 0) {
			line.addView(TextView(context).apply {
				text = "  $count"
				typeface = Typeface.create(Ui.display(context), Typeface.NORMAL)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * 0.85f)
				setTextColor(ContextCompat.getColor(context, R.color.lumen_text_3))
			})
		}
		return line
	}
}
