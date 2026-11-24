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
	/** Streaming-app style previews of the focused title (see PreviewController). */
	lateinit var preview: PreviewController
		private set
	private var clock: TextView? = null
	/** Last focused view inside the page, to put focus back if a refresh drops it. */
	private var lastContentFocus: java.lang.ref.WeakReference<View>? = null

	/** Where pages are shown (the preview window lines up with it). */
	val contentArea: View get() = content

	private var shellAlpha = -1
	private var sidebarAlpha = -1

	/**
	 * A card preview plays full screen behind the menu: the page fades out and the menu turns
	 * see-through, so the video gets the whole screen. Any button brings everything back.
	 */
	fun setPreviewing(on: Boolean) {
		content.animate().cancel()
		content.animate().alpha(if (on) 0f else 1f).setDuration(if (on) 350L else 180L).start()
		findViewById<View>(R.id.shell).background?.mutate()?.let { d ->
			if (on) {
				if (shellAlpha < 0) shellAlpha = d.alpha
				d.alpha = 0
			} else if (shellAlpha >= 0) {
				d.alpha = shellAlpha
				shellAlpha = -1
			}
		}
		sidebar.background?.mutate()?.let { d ->
			if (on) {
				if (sidebarAlpha < 0) sidebarAlpha = d.alpha
				d.alpha = (sidebarAlpha * 0.35f).toInt()
			} else if (sidebarAlpha >= 0) {
				d.alpha = sidebarAlpha
				sidebarAlpha = -1
			}
		}
		findViewById<View>(R.id.nav_divider).alpha = if (on) 0f else 1f
		downloadMini?.view?.alpha = if (on) 0f else 1f
	}

	/** True when the buttons are on top (pages use this for LEFT / RIGHT at the edges). */
	val topNavigation: Boolean get() = topNav
	/** The clock floats in the top right corner (menu on the left); pages keep that corner free. */
	val clockOverlay: Boolean get() = prefs.showClock && !topNav

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
		window.setBackgroundDrawable(null) // the root view paints the background; skip one full-screen layer
		val rootFrame = findViewById<View>(R.id.shell).parent as FrameLayout
		setupClock(rootFrame)
		preview = PreviewController(this, rootFrame)
		window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
			if (now != null && isInside(now, content)) lastContentFocus = java.lang.ref.WeakReference(now)
		}

		buildNav(emptyList())
		// After a theme / navigation change the app reopens on the page you were on.
		val reopen = intent.getStringExtra(EXTRA_OPEN_PAGE)?.let { key -> navItems.firstOrNull { it.key == key } }
		val start = reopen ?: startItem()
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
				// BACK: other pages → Home; on Home → up to the menu; in the menu → "Exit?".
				val home = navItems.firstOrNull { it.key == "home" }
				when {
					home != null && selectedNav !== home -> {
						select(home, focusContent = false)
						home.view.requestFocus()
						applySidebarMode(focusInSidebar = true)
					}
					sidebar.findFocus() == null -> {
						(page?.view as? ScrollView)?.smoothScrollTo(0, 0)
						selectedNav?.view?.requestFocus()
					}
					else -> Dialogs.confirm(this@MainActivity, "Exit Lolish's Project?", "You can open it again from the home screen.", "Exit") { finish() }
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
		clock?.let { handler.removeCallbacks(clockTick); clockTick.run() }
	}

	override fun onPause() {
		if (::preview.isInitialized) preview.stop()
		handler.removeCallbacks(clockTick)
		super.onPause()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		// A dialog opened: no preview behind it.
		if (!hasFocus && ::preview.isInitialized) preview.stop()
	}

	/** Clock in the top right corner of the menus (the player has none). */
	private fun setupClock(rootFrame: FrameLayout) {
		if (!prefs.showClock) return
		val view = TextView(this).apply {
			typeface = Ui.bold(context)
			setTextColor(Ui.color(context, R.attr.lumenText))
			maxLines = 1
		}
		if (topNav) {
			// On the bar, just left of the profile picture.
			view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
			sidebar.addView(view, sidebar.indexOfChild(navBottom), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				marginEnd = Ui.dp(this@MainActivity, 16)
			})
		} else {
			view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			view.background = ContextCompat.getDrawable(this, R.drawable.bg_hud)
			view.setPadding(Ui.dp(this, 14), Ui.dp(this, 5), Ui.dp(this, 14), Ui.dp(this, 5))
			rootFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
				topMargin = Ui.dp(this@MainActivity, if (glass) 30 else 14)
				marginEnd = Ui.dp(this@MainActivity, if (glass) 44 else 22)
			})
		}
		clock = view
		clockTick.run()
	}

	private val clockTick: Runnable = object : Runnable {
		override fun run() {
			clock?.text = android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(java.util.Date())
			handler.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 200L)
		}
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

		// Icons: every library gets its own (by name and kind); a picked icon (hold OK) wins.
		val custom = prefs.navIcons
		val auto = NavIcons.auto(libraries)
		val withIcons = specs.map { spec ->
			val icon = NavIcons.drawable(custom[spec.key]) ?: NavIcons.drawable(auto[spec.key]) ?: spec.icon
			NavSpec(spec.key, icon, spec.label, spec.subtitle, spec.factory, spec.action)
		}

		// The user's order (Settings first by default); items it doesn't know yet keep their default place at the end.
		val order = prefs.navOrder
		val sorted = withIcons.withIndex().sortedBy { (index, spec) ->
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
		brandText.layoutParams = (brandText.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
		androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(brandText, 12, 22, 1, TypedValue.COMPLEX_UNIT_SP)
		if (glass) {
			// The room-tone gradient shows when no artwork is behind the pane.
			(shell.parent as View).background = ContextCompat.getDrawable(this, R.drawable.bg_glass_window)
			shell.background = ContextCompat.getDrawable(this, R.drawable.bg_glass_panel)
			(shell.layoutParams as FrameLayout.LayoutParams).setMargins(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
			// No clipToOutline here: clipping the whole UI to a rounded shape costs every frame. The
			// content just stays clear of the rounded corners.
			content.setPadding(0, Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12))
			backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
		}
		if (!topNav) {
			// Slimmer sidebar: less room around the items and between the menu and the artwork.
			sidebar.setPadding(Ui.dp(this, 8), sidebar.paddingTop, Ui.dp(this, 8), sidebar.paddingBottom)
			return
		}
		shell.orientation = LinearLayout.VERTICAL
		sidebar.orientation = LinearLayout.HORIZONTAL
		sidebar.gravity = Gravity.CENTER_VERTICAL
		sidebar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		sidebar.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 6))
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
			marginStart = Ui.dp(this@MainActivity, 18)
			marginEnd = Ui.dp(this@MainActivity, 12)
		})
		navBottom.orientation = LinearLayout.HORIZONTAL
		navBottom.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		findViewById<View>(R.id.nav_divider).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1))
		content.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
		// Logo only: the bar needs the room for its buttons.
		brandText.visibility = View.GONE
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
			val vertical = when (prefs.sidebarSize) { 0 -> 6; 2 -> 12; else -> 9 }
			val horizontal = if (topNav) 14 else 10
			setPadding(Ui.dp(context, horizontal), Ui.dp(context, vertical), Ui.dp(context, horizontal), Ui.dp(context, vertical))
		}
		// Glass: Search is a rounded search field at the top of the sidebar, like the reference design.
		val searchField = glass && !topNav && key == "search"
		if (searchField) row.background = ContextCompat.getDrawable(this, R.drawable.bg_search_pill)
		// Top bar: Settings, Search and the profile are round icons, everything else is a text link.
		val iconOnly = topNav && key in TOP_ICON_ONLY
		// Top bar links show their icon too (small), so a picked icon ("Change icon") is visible there.
		val textOnly = false
		if (topNav) {
			val vertical = row.paddingTop
			val horizontal = Ui.dp(this, if (iconOnly) 8 else 12)
			row.setPadding(horizontal, vertical, horizontal, vertical)
		}
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
		if (!textOnly) row.addView(image, LinearLayout.LayoutParams(avatarSize, avatarSize))
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
		if (iconOnly) row.contentDescription = label
		if (!iconOnly) row.addView(texts, textParams.apply { marginStart = Ui.dp(this@MainActivity, if (topNav) 8 else 12) })
		val params = if (topNav) {
			LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				marginEnd = Ui.dp(this@MainActivity, 4)
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
		options += "Change icon" to { pickIcon(item) }
		if (hiddenSpecs.isNotEmpty()) options += "Show hidden items (${hiddenSpecs.size})" to { showHiddenItems(hiddenSpecs) }
		options += "Reset sidebar" to {
			prefs.navOrder = emptyList()
			prefs.navHidden = emptySet()
			prefs.navIcons = emptyMap()
			rebuildNav(focusKey = item.key)
			toast("Sidebar reset")
		}
		android.app.AlertDialog.Builder(this)
			.setTitle(label)
			.setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
			.setNegativeButton("Close", null)
			.show()
	}

	/** Grid of icons to choose from (saved for this profile). */
	private fun pickIcon(item: NavItem) {
		val grid = android.widget.GridLayout(this).apply {
			columnCount = 6
			setPadding(Ui.dp(context, 18), Ui.dp(context, 12), Ui.dp(context, 18), Ui.dp(context, 4))
		}
		var dialog: android.app.AlertDialog? = null
		NavIcons.ALL.forEach { (name, res) ->
			val cell = FrameLayout(this).apply {
				background = ContextCompat.getDrawable(context, R.drawable.bg_pill)
				isFocusable = true
				isClickable = true
				contentDescription = name
				setOnClickListener {
					prefs.navIcons = prefs.navIcons + (item.key to name)
					dialog?.dismiss()
					rebuildNav(focusKey = item.key)
				}
			}
			cell.addView(ImageView(this).apply {
				setImageResource(res)
				imageTintList = ContextCompat.getColorStateList(context, R.color.pill_text)
				isDuplicateParentStateEnabled = true
			}, FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.CENTER))
			grid.addView(cell, android.widget.GridLayout.LayoutParams().apply {
				width = Ui.dp(this@MainActivity, 58)
				height = Ui.dp(this@MainActivity, 58)
				setMargins(Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6))
			})
		}
		dialog = android.app.AlertDialog.Builder(this)
			.setTitle("Icon for \"${item.label.text}\"")
			.setView(ScrollView(this).apply { addView(grid) })
			.setNeutralButton("Automatic") { _, _ ->
				prefs.navIcons = prefs.navIcons - item.key
				rebuildNav(focusKey = item.key)
			}
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
		if (::preview.isInitialized) preview.stop()
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
				val full = when (prefs.sidebarSize) { 0 -> 188; 2 -> 244; else -> 214 }
				width = if (expanded) Ui.dp(this@MainActivity, full) else iconPx + Ui.dp(this@MainActivity, 10 * 2 + 8 * 2 + 6)
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
		if (event.action == KeyEvent.ACTION_DOWN && ::preview.isInitialized && preview.onKey(event)) return true
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
			if (focused == null && code in DPAD_KEYS && page != null) {
				// Focus was dropped (a list refreshed under it): put it back instead of letting Android
				// pick the first thing on screen, which is the menu.
				val last = lastContentFocus?.get()
				if (last != null && last.isAttachedToWindow && last.isShown && last.requestFocus()) return true
				if (page?.focusFirst() == true) return true
			}
			val inNav = focused != null && isInside(focused, sidebar)
			if (focused != null && !inNav && page?.onKey(event) == true) return true
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
		handler.postDelayed(applyBackdrop, if (glass) 500L else 350L)
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
				val next = android.graphics.drawable.BitmapDrawable(resources, bitmap)
				val current = backdrop.drawable
				val previous = if (current is android.graphics.drawable.TransitionDrawable) current.getDrawable(current.numberOfLayers - 1) else current
				if (previous == null || backdrop.alpha < 0.05f) {
					backdrop.animate().cancel()
					backdrop.setImageDrawable(next)
					backdrop.alpha = 0f
					backdrop.animate().alpha(0.95f).setDuration(300L).start()
				} else {
					// Blend the two tiny blurred images (no fade to black, no full-screen alpha animation).
					val blend = android.graphics.drawable.TransitionDrawable(arrayOf(previous, next)).apply { isCrossFadeEnabled = true }
					backdrop.setImageDrawable(blend)
					blend.startTransition(320)
				}
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

	// Small download window (Li'Server) ---------------------------------------------------------------

	private class DownloadMini(val view: LinearLayout, val title: TextView, val line: TextView, val bar: android.widget.ProgressBar)
	private var downloadMini: DownloadMini? = null
	/** Downloads whose window you hid (OK on it); a new download brings it back. */
	private var miniHidden: Set<String> = emptySet()

	/** Shows how a Li'Server download is going while you're on another page; empty hides it. */
	fun showDownloadMini(items: List<app.lumen.player.liserver.LiDownload>) {
		val active = items.filter { !it.isSeeding }
		val hashes = active.map { it.hash }.toSet()
		if (active.isEmpty()) miniHidden = emptySet()
		if (active.isEmpty() || hashes.all { it in miniHidden }) {
			downloadMini?.view?.let { v -> if (v.isFocused) content.requestFocus(); v.visibility = View.GONE }
			return
		}
		val mini = downloadMini ?: buildDownloadMini().also { downloadMini = it }
		val first = active.maxByOrNull { it.speedMb } ?: active.first()
		mini.title.text = if (active.size == 1) first.name else "${active.size} downloading  ·  ${first.name}"
		mini.line.text = String.format(java.util.Locale.US, "%.0f%%   ↓ %.2f MB/s   ·   OK to hide", first.progress.coerceIn(0.0, 100.0), active.sumOf { it.speedMb })
		mini.bar.progress = (first.progress.coerceIn(0.0, 100.0) * 10).toInt()
		mini.view.visibility = View.VISIBLE
		miniShown = hashes
	}

	private fun buildDownloadMini(): DownloadMini {
		val root = findViewById<LinearLayout>(R.id.shell).parent as FrameLayout
		val title = TextView(this).apply {
			maxLines = 1
			ellipsize = android.text.TextUtils.TruncateAt.END
			typeface = Ui.bold(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.pill_text))
			isDuplicateParentStateEnabled = true
		}
		val line = TextView(this).apply {
			maxLines = 1
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
			typeface = android.graphics.Typeface.MONOSPACE
			setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.pill_text))
			isDuplicateParentStateEnabled = true
		}
		val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
			max = 1000
			progressTintList = Ui.colorList(this@MainActivity, R.attr.lumenGreen)
			progressBackgroundTintList = Ui.colorList(this@MainActivity, R.attr.lumenTrack)
		}
		val view = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill)
			setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 10))
			isFocusable = true
			isClickable = true
			elevation = Ui.dp(context, 10).toFloat()
			addView(title)
			addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 3) })
			addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 4)).apply { topMargin = Ui.dp(context, 6) })
		}
		Ui.focusScale(view, 1.04f)
		view.setOnClickListener {
			// Hide it; it comes back with the next download.
			miniHidden = miniShown
			if (view.isFocused) content.requestFocus()
			view.visibility = View.GONE
			toast("Download window hidden")
		}
		root.addView(view, FrameLayout.LayoutParams(Ui.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
			setMargins(0, 0, Ui.dp(this@MainActivity, 26), Ui.dp(this@MainActivity, 22))
		})
		return DownloadMini(view, title, line, bar)
	}

	/** The downloads the window is showing now. */
	private var miniShown: Set<String> = emptySet()

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

	fun showOlderVersions() = updates.olderVersions()

	/** Rebuilds the screen (new theme / layout); [openPage] / [focusRow] bring you back where you were. */
	fun restart(openPage: String? = null, focusRow: Int = -1) {
		startActivity(Intent(this, MainActivity::class.java).apply {
			openPage?.let { putExtra(EXTRA_OPEN_PAGE, it) }
			if (focusRow >= 0) putExtra(EXTRA_FOCUS_ROW, focusRow)
		})
		finish()
		overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
	}

	companion object {
		const val EXTRA_OPEN_PAGE = "open_page"
		const val EXTRA_FOCUS_ROW = "focus_row"
		private val DPAD_KEYS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
		private val BROWSABLE = setOf("movies", "tvshows", "boxsets", "homevideos", "mixed", "musicvideos", null)
		/** Always visible, so the sidebar can't lock you out of Settings or Home. */
		private val UNHIDEABLE = setOf("settings", "home")
		/** Shown as round icons on the top bar (the rest are text links). */
		private val TOP_ICON_ONLY = setOf("settings", "search", "user")
	}
}
