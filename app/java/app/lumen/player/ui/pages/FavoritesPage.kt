package app.lumen.player.ui.pages

import app.lumen.player.ui.MainActivity

/** Everything you've hearted, as one grid. */
class FavoritesPage(host: MainActivity) : LibraryPage(
	host = host,
	library = null,
	titleOverride = "Favorites",
	fixedFilters = listOf("IsFavorite"),
	fixedTypes = listOf("Movie", "Series", "Episode", "BoxSet"),
)
