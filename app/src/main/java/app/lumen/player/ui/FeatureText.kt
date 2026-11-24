package app.lumen.player.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import app.lumen.player.R
import app.lumen.player.jellyfin.JfItem
import app.lumen.player.util.Ui
import java.util.Locale

/** The words shown on the Home banner and the preview window. */
object FeatureText {
	fun kind(item: JfItem): String = when {
		item.userData?.hasResume == true -> "CONTINUE WATCHING"
		item.type == "Series" -> "SERIES"
		item.type == "Movie" -> "FILM"
		item.type == "Episode" -> "EPISODE"
		else -> item.type.uppercase(Locale.getDefault())
	}

	fun title(item: JfItem): String = if (item.type == "Episode") item.seriesName ?: item.name else item.name

	/** "Series  •  Drama  •  2024  •  TV-14  •  ★ 8.1" (the rating in green). */
	fun facts(context: Context, item: JfItem): CharSequence {
		val facts = SpannableStringBuilder()
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
			facts.append(String.format(Locale.US, "★ %.1f", it))
			facts.setSpan(ForegroundColorSpan(Ui.color(context, R.attr.lumenGreen)), start, facts.length, 0)
		}
		return facts
	}
}
