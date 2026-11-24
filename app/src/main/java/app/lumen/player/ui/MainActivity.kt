package app.lumen.player.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lumen.player.R
import app.lumen.player.jellyfin.JfItem
import app.lumen.player.jellyfin.Session
import app.lumen.player.ui.pages.FavoritesPage
import app.lumen.player.ui.pages.HomePage
import app.lumen.player.ui.pages.LibraryPage
import app.lumen.player.ui.pages.LiServerPage
import app.lumen.player.ui.pages.Page
import app.lumen.player.ui.pages.SearchPage
import app.lumen.player.ui.pages.SettingsPage
import app.lumen.player.ui.pages.UpcomingPage
import app.lumen.player.util.Images
import app.lumen.player.util.Ui
import kotlinx.coroutines.launch

/** The app shell: Li'Server-style sidebar on the left, the selected page on the right. */
class MainActivity : BaseActivity() {

	lateinit var session: Session
		private set
	lateinit var cards: Cards
		private set

	private lateinit var sidebar: LinearLayout
	private lateinit var navList: LinearLayout
	private lateinit var navBottom: LinearLayout
	private lateinit var brandText: TextView
	private lateinit var content: FrameLayout
	private lateinit var backdrop: ImageView
	private lateinit var toastView: TextView

	private val navItems = mutableListOf<NavItem>()
	private var selectedNav: NavItem? = null
	private var page: Page? = null
	private val handler = Handler(Looper.getMainLooper())
	private var pendingBackdrop: String? = null
	private var shownBackdrop: String? = null
	private var sidebarExpanded = true
	/** Navigation as a bar across the top instead of the sidebar (Settings → Appearance). */
	private val topNav: Boolean get() = prefs.navPosition == 1
	private val glass: Boolean get() = app.lumen.player.util.Themes.isGlass
	private lateinit var updates: UpdateFlow

	private class NavItem(val view: View, val label: TextView, val key: String, val factory: (() -> Page)?, val action: (() -> Unit)?)

	/** Everything that can appear in the sidebar; the user's order / hidden list is applied on top. */
	private class NavSpec(
		val key: String,
		val icon: Int,
		val label: String,
		val subtitle: String? = null,
		val factory: (() -> Page)? = null,
		val action: (() -> Unit)? = null,
	)

	private var libraries: List<JfItem> = emptyList()
	private var navSpecs: List<NavSpec> = emptyList()
	/** Key of the sidebar item being moved with UP / DOWN (after "Move" in the hold-OK menu). */
	private var movingKey: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val current = session()
		if (current == null) {
			val next = if (prefs.accounts.isNotEmpty()) ProfilesActivity::class.java else LoginActivity::class.java
			startActivity(Intent(this, next))
			finish()
			return
		}
		session = current
		cards = Cards(this, prefs, api, session)
		setContentView(R.layout.activity_main)

		sidebar = findViewById(R.id.sidebar)
		navList = findViewById(R.id.nav_list)
		navBottom = findViewById(R.id.nav_bottom)
		brandText = findViewById(R.id.brand_text)
		content = findViewById(R.id.content)
		backdrop = findViewById(R.id.backdrop)
		toastView = findViewById(R.id.toast)
		brandText.text = getString(R.string.app_name)
		updates = UpdateFlow(this) { message -> toast(message) }
		applyShellStyle()

		buildNav(emptyList())
		val start = startItem()
		select(start, focusContent = false)
		start.view.requestFocus()
		applySidebarMode(focusInSidebar = true)

		lifecycleScope.launch {
			val views = runCatching { api.views(session) }.getOrDefault(emptyList())
			val selectedKey = selectedNav?.key
			val hadFocusInSidebar = sidebar.findFocus() != null
			buildNav(views.filter { it.collectionType in BROWSABLE })
			navItems.firstOrNull { it.key == selectedKey }?.let { item ->
				selectedNav = item
				item.view.isSelected = true
				if (hadFocusInSidebar) item.view.requestFocus()
			}
			if (app.lumen.player.BuildConfig.DEBUG) applyScreenshotExtras()
		}

		refreshProfile()
		handler.postDelayed({ if (!isFinishing) updates.check(manual = false) }, 4_000L)

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (sidebar.findFocus() == null) {
					selectedNav?.view?.requestFocus()
				} else {
					finish()
				}
			}
		})
	}

	/** Debug builds only: lets tools/screenshots open a page or a detail screen directly. */
	private fun applyScreenshotExtras() {
		intent.getStringExtra("shot_page")?.let { key ->
			navItems.firstOrNull { it.key == key || it.key == "lib:$key" }?.let { item ->
				select(item, focusContent = false)
				item.view.requestFocus()
				applySidebarMode(focusInSidebar = true)
			}
		}
		intent.getStringExtra("shot_detail")?.let { id -> DetailActivity.start(this, id) }
	}

	override fun onResume() {
		super.onResume()
		page?.onReturn()
		if (::updates.isInitialized) updates.onResume()
	}

	/**
	 * Keeps the saved profile's name and avatar in step with the server, and notices a sign-in the
	 * server no longer accepts (e.g. after a Jellyfin update): it signs in again by itself with the
	 * saved password, or asks for the password once.
	 */
	private fun refreshProfile() {
		val account = prefs.activeAccount ?: return
		lifecycleScope.launch {
			val me = try {
				api.me(session)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: app.lumen.player.jellyfin.ApiException) {
				if (e.code == 401) renewSession(account)
				return@launch
			} catch (e: Exception) {
				return@launch // offline / server restarting: keep the session, try next time
			}
			val (name, tag) = me
			if (name != account.userName || tag != account.imageTag) {
				prefs.saveAccount(account.copy(userName = name, imageTag = tag))
			}
		}
	}

	private suspend fun renewSession(account: app.lumen.player.util.Account) {
		val renewed = runCatching { api.renew(account) }.getOrNull()
		if (renewed != null) {
			prefs.saveAccount(renewed)
			toast("Signed in again as ${renewed.userName}")
			handler.postDelayed({ restart() }, 900L)
			return
		}
		// No saved password (Quick Connect) or it changed: ask once, the name is remembered.
		prefs.removeAccount(account)
		android.app.AlertDialog.Builder(this)
			.setTitle("Sign in again")
			.setMessage("Jellyfin no longer accepts the saved sign-in for ${account.userName} (this can happen after a server update). Sign in once more — it will be remembered.")
			.setCancelable(false)
			.setPositiveButton("Sign in") { _, _ -> addUser() }
			.show()
	}

	// Sidebar -------------------------------------------------------------------------------------

	private fun buildNav(libraries: List<JfItem>) {
		this.libraries = libraries
		navList.removeAllViews()
		navBottom.removeAllViews()
		navItems.clear()
		val specs = mutableListOf(
			NavSpec("settings", R.drawable.ic_settings, "Settings", factory = { SettingsPage(this) }),
			NavSpec("search", R.drawable.ic_search, "Search", factory = { SearchPage(this) }),
			NavSpec("home", R.drawable.ic_home, "Home", factory = { HomePage(this) }),
		)
		libraries.forEach { library ->
			val icon = when (library.collectionType) {
				"movies" -> R.drawable.ic_movie
				"tvshows" -> R.drawable.ic_tv
				else -> R.drawable.ic_library
			}
			specs += NavSpec("lib:${library.id}", icon, library.name, factory = { LibraryPage(this, library) })
		}
		specs += NavSpec("favorites", R.drawable.ic_favorite, "Favorites", factory = { FavoritesPage(this) })
		specs += NavSpec("upcoming", R.drawable.ic_event, "Upcoming Shows", factory = { UpcomingPage(this) })
		specs += if (prefs.liServerUseWebsite) {
			NavSpec("liserver", R.drawable.ic_download, "Li'Server", subtitle = "Website",
				action = { startActivity(Intent(this, TorrentActivity::class.java)) })
		} else {
			NavSpec("liserver", R.drawable.ic_download, "Li'Server", subtitle = "Search & download", factory = { LiServerPage(this) })
		}

		// The user's order (Settings first by default); items it doesn't know yet keep their default place at the end.
		val order = prefs.navOrder
		val sorted = specs.withIndex().sortedBy { (index, spec) ->
			val saved = order.indexOf(spec.key)
			if (saved >= 0) saved else order.size + index
		}.map { it.value }
		navSpecs = sorted
		val hidden = prefs.navHidden
		sorted.filter { it.key !in hidden || it.key in UNHIDEABLE }.forEach { addNavItem(navList, it) }

		val profiles = prefs.accounts.size
		addNavItem(navBottom, NavSpec("user", R.drawable.ic_person, prefs.userName ?: "Account",
			subtitle = if (profiles > 1) "Switch user ($profiles)" else "Switch / add user", action = { switchUser() }))
		applySidebarMode(sidebar.findFocus() != null)
	}

	/**
	 * Glass theme: everything sits on one frosted, rounded pane floating over the blurred artwork.
	 * Navigation on top: the sidebar is turned into a translucent bar across the top.
	 */
	private fun applyShellStyle() {
		val shell = findViewById<LinearLayout>(R.id.shell)
		brandText.typeface = Ui.display(this)
		if (glass) {
			// The room-tone gradient shows when no artwork is behind the pane.
			(shell.parent as View).background = ContextCompat.getDrawable(this, R.drawable.bg_glass_window)
			shell.background = ContextCompat.getDrawable(this, R.drawable.bg_glass_panel)
			(shell.layoutParams as FrameLayout.LayoutParams).setMargins(Ui.dp(this, 26), Ui.dp(this, 20), Ui.dp(this, 26), Ui.dp(this, 20))
			shell.clipToOutline = true
			backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
		}
		if (!topNav) return
		shell.orientation = LinearLayout.VERTICAL
		sidebar.orientation = LinearLayout.HORIZONTAL
		sidebar.gravity = Gravity.CENTER_VERTICAL
		sidebar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		sidebar.setPadding(Ui.dp(this, 26), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 12))
		// See-through bar: the theme's sidebar color, partly transparent over the artwork.
		if (!glass) sidebar.background = android.graphics.drawable.ColorDrawable(Ui.color(this, R.attr.lumenSidebar)).apply { alpha = 190 }
		findViewById<View>(R.id.brand).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		val scroll = findViewById<ScrollView>(R.id.nav_scroll)
		val index = sidebar.indexOfChild(scroll)
		scroll.removeView(navList)
		sidebar.removeView(scroll)
		navList.orientation = LinearLayout.HORIZONTAL
		navList.gravity = Gravity.CENTER_VERTICAL
		val strip = HorizontalScrollView(this).apply {
			isHorizontalScrollBarEnabled = false
			isFillViewport = false
			clipToPadding = false
			addView(navList)
		}
		sidebar.addView(strip, index, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
			marginStart = Ui.dp(this@MainActivity, 26)
			marginEnd = Ui.dp(this@MainActivity, 12)
		})
		navBottom.orientation = LinearLayout.HORIZONTAL
		navBottom.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		findViewById<View>(R.id.nav_divider).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1))
	}

	/** Sidebar icons follow "Icon size", a bit smaller in the compact sidebar. */
	private fun sidebarIconDp(): Int = (Ui.iconDp(prefs) - if (prefs.sidebarSize == 0) 2 else 0).coerceAtLeast(16)

	private fun startItem(): NavItem =
		navItems.firstOrNull { it.key == "home" } ?: navItems.first { it.factory != null }

	private fun addNavItem(parent: LinearLayout, spec: NavSpec) {
		val key = spec.key
		val icon = spec.icon
		val label = spec.label
		val subtitle = spec.subtitle
		val factory = spec.factory
		val action = spec.action
		val row = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_nav)
			isFocusable = true
			isClickable = true
			val vertical = when (prefs.sidebarSize) { 0 -> 6; 2 -> 13; else -> 10 } + if (topNav) 2 else 0
			val horizontal = if (topNav) 16 else 12
			setPadding(Ui.dp(context, horizontal), Ui.dp(context, vertical), Ui.dp(context, horizontal), Ui.dp(context, vertical))
		}
		// Glass: Search is a rounded search field at the top of the sidebar, like the reference design.
		val searchField = glass && !topNav && key == "search"
		if (searchField) row.background = ContextCompat.getDrawable(this, R.drawable.bg_search_pill)
		val iconSize = Ui.dp(this, sidebarIconDp())
		val image = ImageView(this).apply {
			setImageResource(icon)
			imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.nav_text)
			duplicateParentStateEnabled(this)
		}
		val avatarUrl = if (key == "user") prefs.activeAccount?.imageUrl(160) else null
		if (avatarUrl != null) {
			// The signed-in user's picture, round, instead of the person icon.
			image.imageTintList = null
			image.background = ContextCompat.getDrawable(this, R.drawable.bg_avatar)
			image.scaleType = ImageView.ScaleType.CENTER_CROP
			image.clipToOutline = true
			Images.load(image, avatarUrl)
		}
		val avatarSize = if (avatarUrl != null) iconSize + Ui.dp(this, 8) else iconSize
		row.addView(image, LinearLayout.LayoutParams(avatarSize, avatarSize))
		val texts = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			isDuplicateParentStateEnabled = true
		}
		val labelView = TextView(this).apply {
			text = if (searchField) "Search…" else label
			maxLines = 1
			typeface = Ui.bold(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, when (prefs.sidebarSize) { 0 -> 14f; 2 -> 18f; else -> 16f })
			setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.nav_text))
			isDuplicateParentStateEnabled = true
		}
		texts.addView(labelView)
		if (subtitle != null && !topNav && !searchField) {
			texts.addView(TextView(this).apply {
				text = subtitle
				maxLines = 1
				setTextSize(TypedValue.COMPLEX_UNIT_SP, if (prefs.sidebarSize == 0) 11f else 12f)
				setTextColor(Ui.color(this@MainActivity, R.attr.lumenText3))
			})
		}
		val textParams = if (topNav) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
		row.addView(texts, textParams.apply { marginStart = Ui.dp(this@MainActivity, if (topNav) 10 else 16) })
		val params = if (topNav) {
			LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				marginEnd = Ui.dp(this@MainActivity, 6)
			}
		} else {
			LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				bottomMargin = Ui.dp(this@MainActivity, if (searchField) 14 else if (prefs.sidebarSize == 0) 2 else 4)
			}
		}
		parent.addView(row, params)

		val item = NavItem(row, labelView, key, factory, action)
		navItems += item
		row.setOnClickListener {
			if (item.factory != null) select(item, focusContent = true) else item.action?.invoke()
		}
		row.setOnFocusChangeListener { _, _ -> handler.post { applySidebarMode(sidebar.findFocus() != null) } }
		if (parent === navList) {
			row.isLongClickable = true
			row.setOnLongClickListener {
				editNavItem(item)
				true
			}
		}
	}

	// Sidebar editing (hold OK on an item) -----------------------------------------------------------

	private fun editNavItem(item: NavItem) {
		val label = item.label.text.toString()
		val hidden = prefs.navHidden
		val hiddenSpecs = navSpecs.filter { it.key in hidden && it.key !in UNHIDEABLE }
		val options = mutableListOf<Pair<String, () -> Unit>>()
		options += (if (topNav) "Move left / right" else "Move up / down") to { startMove(item) }
		if (item.key !in UNHIDEABLE) options += "Hide \"$label\"" to { hideNavItem(item) }
		if (hiddenSpecs.isNotEmpty()) options += "Show hidden items (${hiddenSpecs.size})" to { showHiddenItems(hiddenSpecs) }
		options += "Reset sidebar" to {
			prefs.navOrder = emptyList()
			prefs.navHidden = emptySet()
			rebuildNav(focusKey = item.key)
			toast("Sidebar reset")
		}
		android.app.AlertDialog.Builder(this)
			.setTitle(label)
			.setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
			.setNegativeButton("Close", null)
			.show()
	}

	private fun startMove(item: NavItem) {
		movingKey = item.key
		item.view.isActivated = true
		item.view.requestFocus()
		toast("▲ ▼ to move \"${item.label.text}\" · OK when done")
	}

	private fun moveNavItem(direction: Int) {
		val item = navItems.firstOrNull { it.key == movingKey } ?: return
		val index = navList.indexOfChild(item.view)
		val target = index + direction
		if (index < 0 || target < 0 || target >= navList.childCount) return
		val params = item.view.layoutParams
		navList.removeView(item.view)
		navList.addView(item.view, target, params)
		item.view.requestFocus()
		saveNavOrder()
	}

	private fun finishMove() {
		navItems.firstOrNull { it.key == movingKey }?.view?.isActivated = false
		movingKey = null
		toast("Saved")
	}

	/** Visible order as shown, with hidden items kept where they were. */
	private fun saveNavOrder() {
		val visible = (0 until navList.childCount).mapNotNull { i -> navItems.firstOrNull { it.view === navList.getChildAt(i) }?.key }
		val hidden = navSpecs.map { it.key }.filter { it !in visible }
		prefs.navOrder = visible + hidden
	}

	private fun hideNavItem(item: NavItem) {
		saveNavOrder()
		prefs.navHidden = prefs.navHidden + item.key
		val index = navList.indexOfChild(item.view)
		val wasSelected = selectedNav === item
		rebuildNav(focusKey = null, focusIndex = index)
		if (wasSelected) select(startItem(), focusContent = false)
		toast("\"${item.label.text}\" hidden · hold OK on any item to bring it back")
	}

	private fun showHiddenItems(hiddenSpecs: List<NavSpec>) {
		android.app.AlertDialog.Builder(this)
			.setTitle("Show again")
			.setItems(hiddenSpecs.map { it.label }.toTypedArray()) { _, which ->
				val spec = hiddenSpecs[which]
				prefs.navHidden = prefs.navHidden - spec.key
				rebuildNav(focusKey = spec.key)
			}
			.setNegativeButton("Close", null)
			.show()
	}

	private fun rebuildNav(focusKey: String?, focusIndex: Int = -1) {
		val selectedKey = selectedNav?.key
		buildNav(libraries)
		navItems.firstOrNull { it.key == selectedKey }?.let { item ->
			selectedNav = item
			item.view.isSelected = true
		}
		val target = navItems.firstOrNull { it.key == focusKey }?.view
			?: navList.getChildAt(focusIndex.coerceAtMost(navList.childCount - 1).coerceAtLeast(0))
		target?.requestFocus()
	}

	private fun duplicateParentStateEnabled(view: View) {
		view.isDuplicateParentStateEnabled = true
	}

	private fun select(item: NavItem, focusContent: Boolean) {
		val factory = item.factory ?: return
		if (selectedNav == item && page != null) {
			if (focusContent) page?.focusFirst()
			return
		}
		selectedNav?.view?.isSelected = false
		item.view.isSelected = true
		selectedNav = item
		page?.onHide()
		content.removeAllViews()
		val newPage = factory()
		page = newPage
		content.addView(newPage.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		setBackdropUrl(null)
		// Page change: a short fade + rise on a hardware layer (no layout work while it runs).
		if (prefs.animations) {
			newPage.view.alpha = 0f
			newPage.view.translationY = Ui.dp(this, 18).toFloat()
			newPage.view.animate().alpha(1f).translationY(0f).setDuration(240L)
				.setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).withLayer().start()
		}
		newPage.onShow()
		if (focusContent) handler.postDelayed({ newPage.focusFirst() }, 120L)
	}

	/** Expanded, icons-only, or "expand when focused" (Settings → Appearance). */
	private fun applySidebarMode(focusInSidebar: Boolean) {
		if (topNav) return // the top bar always shows its labels
		val expanded = when (prefs.sidebarMode) {
			0 -> true
			1 -> false
			else -> focusInSidebar
		}
		if (expanded != sidebarExpanded) {
			sidebarExpanded = expanded
			val iconPx = Ui.dp(this, Ui.iconDp(prefs))
			sidebar.layoutParams = sidebar.layoutParams.apply {
				val full = when (prefs.sidebarSize) { 0 -> 214; 2 -> 280; else -> 250 }
				width = if (expanded) Ui.dp(this@MainActivity, full) else iconPx + Ui.dp(this@MainActivity, 12 * 2 + 14 * 2)
			}
		}
		brandText.visibility = if (expanded) View.VISIBLE else View.GONE
		navItems.forEach { nav ->
			(nav.label.parent as? View)?.visibility = if (expanded) View.VISIBLE else View.GONE
		}
	}

	// Keys: LEFT from the content goes back to the sidebar, RIGHT from the sidebar into the content.

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		val code = event.keyCode
		if (movingKey != null) {
			// Moving a navigation item: UP / DOWN (LEFT / RIGHT on the top bar) move it, OK or BACK finish.
			if (event.action == KeyEvent.ACTION_DOWN) {
				val back = if (topNav) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_UP
				val forward = if (topNav) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN
				when (code) {
					back -> moveNavItem(-1)
					forward -> moveNavItem(1)
					KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
					KeyEvent.KEYCODE_BACK -> finishMove()
				}
			}
			return true
		}
		if (event.action == KeyEvent.ACTION_DOWN) {
			val focused = currentFocus
			val inNav = focused != null && isInside(focused, sidebar)
			// Left sidebar: RIGHT goes into the page, LEFT back to the sidebar, UP / DOWN stay put.
			// Top bar: DOWN goes into the page, UP back to the bar, LEFT / RIGHT stay put.
			val toPage = if (topNav) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT
			val toNav = if (topNav) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT
			val cross = if (topNav) setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
			else setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
			when {
				code == toPage && inNav -> if (page?.focusFirst() == true) return true
				code == toNav && !inNav && focused != null -> {
					// Let the focused row use the key first (e.g. the size steppers in Settings).
					if (super.dispatchKeyEvent(event)) return true
					val next = focused.focusSearch(direction(code))
					if (next == null || isInside(next, sidebar)) {
						selectedNav?.view?.requestFocus()
						return true
					}
					return false
				}
				code in cross && focused != null -> {
					// Never fall out of the page into the navigation (or the other way) sideways.
					if (super.dispatchKeyEvent(event)) return true
					val next = focused.focusSearch(direction(code))
					if (next == null || isInside(next, sidebar) != inNav) return true
					return false
				}
			}
		}
		return super.dispatchKeyEvent(event)
	}

	private fun direction(keyCode: Int): Int = when (keyCode) {
		KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
		KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
		KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
		else -> View.FOCUS_RIGHT
	}

	private fun isInside(view: View, parent: View): Boolean {
		var current: Any? = view
		while (current is View) {
			if (current === parent) return true
			current = current.parent
		}
		return false
	}

	// Helpers for pages -----------------------------------------------------------------------

	fun openItem(item: JfItem) {
		DetailActivity.start(this, item.id)
	}

	fun setBackdrop(item: JfItem?) {
		// Glass blurs the artwork anyway, so a small image is plenty.
		setBackdropUrl(item?.let { api.backdropUrl(session, it, if (glass) 320 else 1280) })
	}

	/** Cross-fades the page backdrop, waiting a moment so fast scrolling doesn't thrash. */
	fun setBackdropUrl(url: String?) {
		pendingBackdrop = url
		handler.removeCallbacks(applyBackdrop)
		handler.postDelayed(applyBackdrop, 350L)
	}

	private val applyBackdrop = Runnable {
		val url = pendingBackdrop
		if (url == shownBackdrop) return@Runnable
		shownBackdrop = url
		if (url == null) {
			backdrop.animate().alpha(0f).setDuration(250L).start()
			return@Runnable
		}
		if (glass) {
			Images.fetch(url, transform = { app.lumen.player.util.Blur.soft(it) }) { bitmap ->
				if (pendingBackdrop != url || bitmap == null) return@fetch
				backdrop.animate().cancel()
				backdrop.alpha = 0f
				backdrop.setImageBitmap(bitmap)
				backdrop.animate().alpha(0.95f).setDuration(450L).start()
			}
			return@Runnable
		}
		Images.load(backdrop, url, fade = false) { bitmap ->
			backdrop.alpha = 0f
			if (bitmap != null) backdrop.animate().alpha(0.42f).setDuration(400L).start()
		}
	}

	fun toast(message: String) {
		toastView.text = message
		toastView.animate().cancel()
		toastView.animate().alpha(1f).setDuration(200L).start()
		handler.removeCallbacks(hideToast)
		handler.postDelayed(hideToast, 2_800L)
	}

	private val hideToast = Runnable { toastView.animate().alpha(0f).setDuration(250L).start() }

	fun drawable(id: Int): Drawable? = ContextCompat.getDrawable(this, id)

	/** Signs the current user out of this TV (removes the profile) and shows the remaining ones. */
	fun signOut() {
		prefs.activeAccount?.let { account ->
			AppScope.io.launch { api.logout(account.serverUrl, account.token, account.deviceId) }
			prefs.removeAccount(account)
		}
		prefs.unlink()
		val next = if (prefs.accounts.isNotEmpty()) ProfilesActivity::class.java else LoginActivity::class.java
		startActivity(Intent(this, next))
		finish()
	}

	/** "Who's watching?" */
	fun switchUser() {
		startActivity(Intent(this, ProfilesActivity::class.java))
		finish()
	}

	fun addUser() {
		startActivity(Intent(this, LoginActivity::class.java))
		finish()
	}

	/** Server address changed in Settings: sign in on the new server (saved profiles stay). */
	fun changeServer(url: String) {
		prefs.unlink()
		prefs.serverUrl = url
		startActivity(Intent(this, LoginActivity::class.java))
		finish()
	}

	fun checkForUpdates() = updates.check(manual = true)

	fun restart() {
		startActivity(Intent(this, MainActivity::class.java))
		finish()
		overridePendingTransition(0, 0)
	}

	private companion object {
		val BROWSABLE = setOf("movies", "tvshows", "boxsets", "homevideos", "mixed", "musicvideos", null)
		/** Always visible, so the sidebar can't lock you out of Settings or Home. */
		val UNHIDEABLE = setOf("settings", "home")
	}
}
