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
	private lateinit var updates: UpdateFlow

	private class NavItem(val view: View, val label: TextView, val key: String, val factory: (() -> Page)?, val action: (() -> Unit)?)

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

		buildNav(emptyList())
		select(navItems.first { it.key == "home" }, focusContent = false)
		navItems.first { it.key == "home" }.view.requestFocus()
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

	override fun onResume() {
		super.onResume()
		page?.onReturn()
		if (::updates.isInitialized) updates.onResume()
	}

	/** Keeps the saved profile's name and avatar in step with the server. */
	private fun refreshProfile() {
		val account = prefs.activeAccount ?: return
		lifecycleScope.launch {
			val (name, tag) = runCatching { api.me(session) }.getOrNull() ?: return@launch
			if (name != account.userName || tag != account.imageTag) {
				prefs.saveAccount(account.copy(userName = name, imageTag = tag))
			}
		}
	}

	// Sidebar -------------------------------------------------------------------------------------

	private fun buildNav(libraries: List<JfItem>) {
		navList.removeAllViews()
		navBottom.removeAllViews()
		navItems.clear()
		addPage(navList, "search", R.drawable.ic_search, "Search") { SearchPage(this) }
		addPage(navList, "home", R.drawable.ic_home, "Home") { HomePage(this) }
		libraries.forEach { library ->
			val icon = when (library.collectionType) {
				"movies" -> R.drawable.ic_movie
				"tvshows" -> R.drawable.ic_tv
				"boxsets" -> R.drawable.ic_library
				else -> R.drawable.ic_library
			}
			addPage(navList, "lib:${library.id}", icon, library.name) { LibraryPage(this, library) }
		}
		addPage(navList, "favorites", R.drawable.ic_favorite, "Favorites") { FavoritesPage(this) }
		addPage(navList, "upcoming", R.drawable.ic_event, "Upcoming Shows") { UpcomingPage(this) }
		addAction(navList, "liserver", R.drawable.ic_download, "Li'Server", subtitle = "Search & download") {
			startActivity(Intent(this, TorrentActivity::class.java))
		}
		addPage(navBottom, "settings", R.drawable.ic_settings, "Settings") { SettingsPage(this) }
		val profiles = prefs.accounts.size
		addAction(navBottom, "user", R.drawable.ic_person, prefs.userName ?: "Account",
			subtitle = if (profiles > 1) "Switch user ($profiles)" else "Switch / add user") {
			switchUser()
		}
		applySidebarMode(sidebar.findFocus() != null)
	}

	private fun addPage(parent: LinearLayout, key: String, icon: Int, label: String, subtitle: String? = null, factory: () -> Page) =
		addNavItem(parent, key, icon, label, subtitle, factory, null)

	private fun addAction(parent: LinearLayout, key: String, icon: Int, label: String, subtitle: String? = null, action: () -> Unit) =
		addNavItem(parent, key, icon, label, subtitle, null, action)

	private fun addNavItem(
		parent: LinearLayout,
		key: String,
		icon: Int,
		label: String,
		subtitle: String?,
		factory: (() -> Page)?,
		action: (() -> Unit)?,
	) {
		val row = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_nav)
			isFocusable = true
			isClickable = true
			setPadding(Ui.dp(context, 14), Ui.dp(context, 11), Ui.dp(context, 14), Ui.dp(context, 11))
		}
		val iconSize = Ui.dp(this, Ui.iconDp(prefs))
		val image = ImageView(this).apply {
			setImageResource(icon)
			imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.nav_text)
			duplicateParentStateEnabled(this)
		}
		row.addView(image, LinearLayout.LayoutParams(iconSize, iconSize))
		val texts = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			isDuplicateParentStateEnabled = true
		}
		val labelView = TextView(this).apply {
			text = label
			maxLines = 1
			typeface = Ui.bold(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
			setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.nav_text))
			isDuplicateParentStateEnabled = true
		}
		texts.addView(labelView)
		if (subtitle != null) {
			texts.addView(TextView(this).apply {
				text = subtitle
				maxLines = 1
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
				setTextColor(ContextCompat.getColor(this@MainActivity, R.color.lumen_text_3))
			})
		}
		row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
			marginStart = Ui.dp(this@MainActivity, 16)
		})
		val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		params.bottomMargin = Ui.dp(this, 4)
		parent.addView(row, params)

		val item = NavItem(row, labelView, key, factory, action)
		navItems += item
		row.setOnClickListener {
			if (item.factory != null) select(item, focusContent = true) else item.action?.invoke()
		}
		row.setOnFocusChangeListener { _, _ -> handler.post { applySidebarMode(sidebar.findFocus() != null) } }
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
		newPage.onShow()
		if (focusContent) handler.postDelayed({ newPage.focusFirst() }, 120L)
	}

	/** Expanded, icons-only, or "expand when focused" (Settings → Appearance). */
	private fun applySidebarMode(focusInSidebar: Boolean) {
		val expanded = when (prefs.sidebarMode) {
			0 -> true
			1 -> false
			else -> focusInSidebar
		}
		if (expanded != sidebarExpanded) {
			sidebarExpanded = expanded
			val iconPx = Ui.dp(this, Ui.iconDp(prefs))
			sidebar.layoutParams = sidebar.layoutParams.apply {
				width = if (expanded) Ui.dp(this@MainActivity, 250) else iconPx + Ui.dp(this@MainActivity, 14 * 2 + 14 * 2)
			}
		}
		brandText.visibility = if (expanded) View.VISIBLE else View.GONE
		navItems.forEach { nav ->
			(nav.label.parent as? View)?.visibility = if (expanded) View.VISIBLE else View.GONE
		}
	}

	// Keys: LEFT from the content goes back to the sidebar, RIGHT from the sidebar into the content.

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN) {
			val focused = currentFocus
			val inSidebar = focused != null && isInside(focused, sidebar)
			when (event.keyCode) {
				KeyEvent.KEYCODE_DPAD_RIGHT -> if (inSidebar) {
					if (page?.focusFirst() == true) return true
				}
				KeyEvent.KEYCODE_DPAD_LEFT -> if (!inSidebar && focused != null) {
					// Let the focused row use LEFT first (e.g. the size steppers in Settings).
					if (super.dispatchKeyEvent(event)) return true
					val next = focused.focusSearch(View.FOCUS_LEFT)
					if (next == null || isInside(next, sidebar)) {
						selectedNav?.view?.requestFocus()
						return true
					}
					// Not consumed: returning false lets Android move focus left as usual.
					return false
				}
			}
		}
		return super.dispatchKeyEvent(event)
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
		setBackdropUrl(item?.let { api.backdropUrl(session, it, 1280) })
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
	}
}
