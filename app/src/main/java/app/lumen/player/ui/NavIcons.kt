package app.lumen.player.ui

import app.lumen.player.R
import app.lumen.player.jellyfin.JfItem

/**
 * Icons for the navigation. Every library gets its own icon, picked from its name (Anime, Kids,
 * Documentaries, 4K…) and kind, never the same one twice. Hold OK on an item to choose another.
 */
object NavIcons {
	/** Name → drawable. Names are what gets saved, so they must never change. */
	val ALL: LinkedHashMap<String, Int> = linkedMapOf(
		"movie" to R.drawable.ic_movie,
		"tv" to R.drawable.ic_tv,
		"reel" to R.drawable.ic_cat_reel,
		"tv_retro" to R.drawable.ic_cat_tv_retro,
		"play_box" to R.drawable.ic_cat_play_box,
		"ticket" to R.drawable.ic_cat_ticket,
		"popcorn" to R.drawable.ic_cat_popcorn,
		"library" to R.drawable.ic_library,
		"stack" to R.drawable.ic_cat_stack,
		"balloon" to R.drawable.ic_cat_balloon,
		"sparkle" to R.drawable.ic_cat_sparkle,
		"palette" to R.drawable.ic_cat_palette,
		"globe" to R.drawable.ic_cat_globe,
		"leaf" to R.drawable.ic_cat_leaf,
		"music" to R.drawable.ic_cat_music,
		"mic" to R.drawable.ic_cat_mic,
		"uhd" to R.drawable.ic_cat_uhd,
		"ball" to R.drawable.ic_cat_ball,
		"smile" to R.drawable.ic_cat_smile,
		"ghost" to R.drawable.ic_cat_ghost,
		"bolt" to R.drawable.ic_cat_bolt,
		"heart" to R.drawable.ic_cat_heart,
		"rocket" to R.drawable.ic_cat_rocket,
		"crown" to R.drawable.ic_cat_crown,
		"tree" to R.drawable.ic_cat_tree,
		"camera" to R.drawable.ic_cat_camera,
		"book" to R.drawable.ic_cat_book,
		"photo" to R.drawable.ic_cat_photo,
		"flag" to R.drawable.ic_cat_flag,
		"star" to R.drawable.ic_star,
		"favorite" to R.drawable.ic_favorite,
		"event" to R.drawable.ic_event,
		"download" to R.drawable.ic_download,
		"home" to R.drawable.ic_home,
		"search" to R.drawable.ic_search,
		"settings" to R.drawable.ic_settings,
	)

	/** Words in a library's name (English and Swedish) → the icons that fit, best first. */
	private val KEYWORDS: List<Pair<Regex, List<String>>> = listOf(
		Regex("anime|manga") to listOf("sparkle"),
		Regex("kid|barn|child|junior|famil") to listOf("balloon", "smile"),
		Regex("animat|cartoon|tecknad|pixar|disney") to listOf("palette", "balloon"),
		Regex("docu|dokument|natur|nature|planet|history|historia") to listOf("globe", "leaf"),
		Regex("music|musik|concert|konsert") to listOf("music", "mic"),
		Regex("stand.?up|podcast|talk") to listOf("mic"),
		Regex("4k|uhd|hdr|remux|2160") to listOf("uhd"),
		Regex("sport|fotboll|football|soccer|hockey|nba|nfl|ufc|f1") to listOf("ball"),
		Regex("comed|komedi|humor") to listOf("smile"),
		Regex("horror|skräck|skrack") to listOf("ghost"),
		Regex("action|thriller") to listOf("bolt"),
		Regex("romance|romantik|romcom|k-?drama|asian|asiat|korean|korea") to listOf("heart"),
		Regex("sci.?fi|space|rymd|fantasy") to listOf("rocket"),
		Regex("classic|klassiker|oscar|best") to listOf("crown"),
		Regex("christmas|xmas|jul") to listOf("tree"),
		Regex("home ?video|hemvideo|family video|camera|kamera") to listOf("camera"),
		Regex("book|bok|ljudbok|audiobook") to listOf("book"),
		Regex("photo|foto|bilder|pictures") to listOf("photo"),
		Regex("collection|samling|box ?set") to listOf("stack"),
		Regex("swed|svensk|nordic|nordisk|scandi|norsk|dansk") to listOf("flag"),
		Regex("new|nytt|latest|recent") to listOf("star"),
	)

	private val BY_TYPE: Map<String?, List<String>> = mapOf(
		"movies" to listOf("movie", "reel", "ticket", "popcorn", "play_box", "crown"),
		"tvshows" to listOf("tv", "tv_retro", "play_box", "stack"),
		"homevideos" to listOf("camera", "photo"),
		"music" to listOf("music", "mic"),
		"musicvideos" to listOf("music", "mic"),
		"boxsets" to listOf("stack"),
		"books" to listOf("book"),
		"photos" to listOf("photo"),
	)

	/** Icons of the fixed items, so a library never looks like Home or Favorites. */
	private val RESERVED = setOf("home", "search", "settings", "favorite", "event", "download")

	/** A distinct icon name for every library, in sidebar order. */
	fun auto(libraries: List<JfItem>): Map<String, String> {
		val used = RESERVED.toMutableSet()
		val result = mutableMapOf<String, String>()
		libraries.forEach { library ->
			val name = library.name.lowercase()
			val wanted = KEYWORDS.filter { (regex, _) -> regex.containsMatchIn(name) }.flatMap { it.second } +
				(BY_TYPE[library.collectionType] ?: listOf("library", "play_box", "stack"))
			val pick = wanted.firstOrNull { it !in used }
				?: ALL.keys.firstOrNull { it !in used }
				?: wanted.first()
			used += pick
			result["lib:${library.id}"] = pick
		}
		return result
	}

	fun drawable(name: String?): Int? = name?.let { ALL[it] }
}
