package app.lumen.player.ui.pages

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lumen.player.R
import app.lumen.player.liserver.LiDiscover
import app.lumen.player.liserver.LiDownload
import app.lumen.player.liserver.LiResult
import app.lumen.player.liserver.LiServerApi
import app.lumen.player.liserver.LiServerException
import app.lumen.player.ui.MainActivity
import app.lumen.player.ui.PanelBuilder
import app.lumen.player.ui.Pills
import app.lumen.player.ui.TorrentActivity
import app.lumen.player.util.Images
import app.lumen.player.util.SecureStore
import app.lumen.player.util.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URL
import kotlin.math.roundToInt

/**
 * Li'Server made for the remote: search, one-click download into the right library folder,
 * the download list with pause / resume / delete, and what's airing / trending. Talks to
 * Li'Server's own API and stays signed in (the website is one button away for everything else).
 */
class LiServerPage(host: MainActivity) : Page(host) {
	private enum class Tab { SEARCH, DOWNLOADS, TRENDING }

	private val li = LiServerApi(host.prefs)
	private val root = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(Ui.dp(host, 44), Ui.dp(host, 30), Ui.dp(host, 32), 0)
	}
	override val view: View = root
	private val status = TextView(host)
	private val tabsRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
	private val body = FrameLayout(host)
	private val tabPills = mutableMapOf<Tab, TextView>()
	private var tab = Tab.SEARCH
	private var signedInAs: String? = null
	private var pollJob: Job? = null
	private var loadJob: Job? = null

	// Search tab state survives switching tabs.
	private var lastQuery = ""
	private var privateTrackers = false
	private var results: List<LiResult> = emptyList()

	init {
		val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
		header.addView(host.cards.heading("Li'Server", null, 40f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		status.apply {
			setTextColor(ContextCompat.getColor(host, R.color.lumen_green))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			background = ContextCompat.getDrawable(host, R.drawable.bg_chip_green)
			setPadding(Ui.dp(host, 12), Ui.dp(host, 6), Ui.dp(host, 12), Ui.dp(host, 6))
			text = "Connecting…"
		}
		header.addView(status)
		root.addView(header)
		root.addView(tabsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			topMargin = Ui.dp(host, 18)
		})
		root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(host, 14) })
	}

	override fun onShow() = connect()

	override fun onHide() {
		pollJob?.cancel()
		loadJob?.cancel()
	}

	override fun onReturn() {
		if (signedInAs != null && tab == Tab.DOWNLOADS) startPolling()
	}

	override fun focusFirst(): Boolean =
		(tabPills[tab] ?: body.findFocus() ?: firstFocusable(body))?.requestFocus() == true

	private fun firstFocusable(group: ViewGroup): View? {
		for (i in 0 until group.childCount) {
			val child = group.getChildAt(i)
			if (child.isFocusable && child.visibility == View.VISIBLE) return child
			if (child is ViewGroup) firstFocusable(child)?.let { return it }
		}
		return null
	}

	// Connection / sign-in -------------------------------------------------------------------

	private fun connect() {
		loadJob?.cancel()
		tabsRow.removeAllViews()
		showMessage("Connecting to ${host.prefs.liServerUrl}…")
		loadJob = host.lifecycleScope.launch {
			try {
				val user = li.ensureSession()
				if (user == null) showLogin(null) else onSignedIn(user)
			} catch (e: CancellationException) {
				throw e
			} catch (e: LiServerException) {
				when {
					e.untrustedCertificate -> showCertificatePrompt()
					e.needsLogin || e.code == 403 -> showLogin(e.message)
					else -> showError(e.message ?: "Li'Server didn't answer")
				}
			} catch (e: Exception) {
				showError("Li'Server didn't answer (${e.message ?: e.javaClass.simpleName})")
			}
		}
	}

	private fun onSignedIn(user: String) {
		signedInAs = user
		status.text = "●  $user"
		buildTabs()
		selectTab(tab, focus = false)
	}

	private fun buildTabs() {
		tabsRow.removeAllViews()
		tabPills.clear()
		tabPills[Tab.SEARCH] = Pills.create(tabsRow, "Search", R.drawable.ic_search).apply { setOnClickListener { selectTab(Tab.SEARCH, true) } }
		tabPills[Tab.DOWNLOADS] = Pills.create(tabsRow, "Downloads", R.drawable.ic_download).apply { setOnClickListener { selectTab(Tab.DOWNLOADS, true) } }
		tabPills[Tab.TRENDING] = Pills.create(tabsRow, "Trending", R.drawable.ic_star).apply { setOnClickListener { selectTab(Tab.TRENDING, true) } }
		Pills.create(tabsRow, "Website", R.drawable.ic_link).setOnClickListener {
			host.startActivity(Intent(host, TorrentActivity::class.java))
		}
		Pills.create(tabsRow, "Sign out", R.drawable.ic_logout).setOnClickListener {
			app.lumen.player.ui.Dialogs.confirm(host, "Sign out of Li'Server?", "The saved Li'Server password is removed from this TV.", "Sign out") {
				host.lifecycleScope.launch {
					li.logout()
					host.prefs.liServerPasswordEncrypted = null
					signedInAs = null
					showLogin("Signed out.")
				}
			}
		}
	}

	private fun selectTab(newTab: Tab, focus: Boolean) {
		tab = newTab
		tabPills.forEach { (key, pill) -> pill.isSelected = key == newTab }
		pollJob?.cancel()
		when (newTab) {
			Tab.SEARCH -> showSearch(focus)
			Tab.DOWNLOADS -> showDownloads()
			Tab.TRENDING -> showTrending()
		}
	}

	private fun showMessage(text: String, vararg actions: Pair<String, () -> Unit>) {
		val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		box.addView(TextView(host).apply {
			this.text = text
			setTextColor(ContextCompat.getColor(host, R.color.lumen_text_2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
			setLineSpacing(0f, 1.2f)
		})
		if (actions.isNotEmpty()) {
			val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(host, 18), 0, 0) }
			actions.forEach { (label, action) -> Pills.create(row, label).setOnClickListener { action() } }
			box.addView(row)
		}
		setBody(box)
	}

	private fun showError(message: String) {
		status.text = "●  offline"
		showMessage(
			"$message\n\nCheck that Li'Server is running, or change its address in Settings → Li'Server.",
			"Try again" to { connect() },
			"Open website" to { host.startActivity(Intent(host, TorrentActivity::class.java)) },
		)
		firstFocusable(body)?.requestFocus()
	}

	private fun showCertificatePrompt() {
		val hostName = runCatching { URL(host.prefs.liServerUrl).host }.getOrNull().orEmpty()
		status.text = "●  certificate"
		showMessage(
			"Li'Server uses its own security certificate, which Android doesn't know.\nTrust $hostName on this TV?",
			"Trust and continue" to {
				host.prefs.trustedCertHosts = host.prefs.trustedCertHosts + hostName
				connect()
			},
			"Cancel" to { showError("Not connected.") },
		)
		firstFocusable(body)?.requestFocus()
	}

	private fun showLogin(message: String?) {
		status.text = "●  signed out"
		tabsRow.removeAllViews()
		val form = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		form.addView(TextView(host).apply {
			text = message?.let { "$it\n" }.orEmpty() + "Sign in once — the login is saved encrypted on this TV and used again automatically."
			setTextColor(ContextCompat.getColor(host, R.color.lumen_text_2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		})
		val user = field("Username", host.prefs.liServerUser.orEmpty(), password = false)
		val pass = field("Password", "", password = true)
		form.addView(user, LinearLayout.LayoutParams(Ui.dp(host, 460), Ui.dp(host, 56)).apply { topMargin = Ui.dp(host, 18) })
		form.addView(pass, LinearLayout.LayoutParams(Ui.dp(host, 460), Ui.dp(host, 56)).apply { topMargin = Ui.dp(host, 12) })
		val error = TextView(host).apply {
			setTextColor(ContextCompat.getColor(host, R.color.lumen_red))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			setPadding(0, Ui.dp(host, 10), 0, 0)
		}
		form.addView(error)
		val buttons = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(host, 14), 0, 0) }
		val signIn = Pills.create(buttons, "Sign in", R.drawable.ic_check)
		Pills.create(buttons, "Open website", R.drawable.ic_link).setOnClickListener {
			host.startActivity(Intent(host, TorrentActivity::class.java))
		}
		form.addView(buttons)
		signIn.setOnClickListener {
			val name = user.text.toString().trim()
			val password = pass.text.toString()
			if (name.isEmpty() || password.isEmpty()) {
				error.text = "Enter both username and password."
				return@setOnClickListener
			}
			error.text = ""
			signIn.isEnabled = false
			host.lifecycleScope.launch {
				try {
					val who = li.login(name, password)
					host.prefs.liServerPasswordEncrypted = SecureStore.encrypt(password)
					host.prefs.liServerAutoLogin = true
					onSignedIn(who)
					tabPills[tab]?.requestFocus()
				} catch (e: CancellationException) {
					throw e
				} catch (e: LiServerException) {
					if (e.untrustedCertificate) showCertificatePrompt() else error.text = e.message
				} catch (e: Exception) {
					error.text = e.message ?: e.javaClass.simpleName
				} finally {
					signIn.isEnabled = true
				}
			}
		}
		pass.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				hideKeyboard(pass)
				signIn.requestFocus()
				true
			} else false
		}
		setBody(form)
		(if (user.text.isEmpty()) user else pass).requestFocus()
	}

	private fun field(hint: String, value: String, password: Boolean) = EditText(host).apply {
		this.hint = hint
		setText(value)
		setSingleLine()
		inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
		imeOptions = if (password) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
		background = ContextCompat.getDrawable(host, R.drawable.bg_edit)
		setTextColor(ContextCompat.getColor(host, R.color.lumen_text))
		setHintTextColor(ContextCompat.getColor(host, R.color.lumen_text_3))
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
		setPadding(Ui.dp(host, 18), 0, Ui.dp(host, 18), 0)
	}

	private fun hideKeyboard(view: View) {
		host.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
	}

	private fun setBody(content: View) {
		body.removeAllViews()
		body.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		content.alpha = 0f
		content.translationY = Ui.dp(host, 12).toFloat()
		content.animate().alpha(1f).translationY(0f).setDuration(220L).withLayer().start()
	}

	/** A vertical list whose last row doesn't let DOWN escape to the sidebar. */
	private fun scrollList(): Pair<ScrollView, LinearLayout> {
		val scroll = ScrollView(host).apply {
			isVerticalScrollBarEnabled = false
			clipToPadding = false
			setPadding(0, 0, 0, Ui.dp(host, 60))
		}
		val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		scroll.addView(list)
		return scroll to list
	}

	// Search ---------------------------------------------------------------------------------

	private fun showSearch(focus: Boolean) {
		val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		val bar = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
		val input = field("Title, then S01 or 1080p to narrow it down…", lastQuery, password = false).apply {
			imeOptions = EditorInfo.IME_ACTION_SEARCH
		}
		bar.addView(input, LinearLayout.LayoutParams(Ui.dp(host, 560), Ui.dp(host, 56)).apply { marginEnd = Ui.dp(host, 14) })
		val go = Pills.create(bar, "Search", R.drawable.ic_search)
		val priv = Pills.create(bar, if (privateTrackers) "Private trackers: on" else "Private trackers: off")
		priv.isSelected = privateTrackers
		box.addView(bar)
		val info = TextView(host).apply {
			setTextColor(ContextCompat.getColor(host, R.color.lumen_text_2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setPadding(Ui.dp(host, 4), Ui.dp(host, 12), 0, Ui.dp(host, 6))
			text = if (results.isEmpty()) "Press OK on the field to type. “Best” = 1080p x265 with seeders." else "${results.size} results for “$lastQuery”"
		}
		box.addView(info)
		val (scroll, list) = scrollList()
		box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		setBody(box)
		renderResults(list)

		fun doSearch() {
			val query = input.text.toString().trim()
			if (query.length < 2) {
				info.text = "Type at least two letters."
				return
			}
			hideKeyboard(input)
			lastQuery = query
			info.text = "Searching ${if (privateTrackers) "public and private" else "public"} trackers for “$query”…"
			list.removeAllViews()
			loadJob?.cancel()
			loadJob = host.lifecycleScope.launch {
				try {
					results = sortResults(li.search(query, privateTrackers))
					info.text = if (results.isEmpty()) "Nothing found for “$query”." else "${results.size} results for “$query” — best quality first"
					renderResults(list)
					list.getChildAt(0)?.requestFocus()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					info.text = e.message ?: "Search failed"
				}
			}
		}
		go.setOnClickListener { doSearch() }
		priv.setOnClickListener {
			privateTrackers = !privateTrackers
			priv.text = if (privateTrackers) "Private trackers: on" else "Private trackers: off"
			priv.isSelected = privateTrackers
		}
		input.setOnEditorActionListener { _, actionId, event ->
			val enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
				(event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
			if (enter) doSearch()
			enter
		}
		if (focus) input.requestFocus()
	}

	/** Best Choice first, then by seeders — what you usually want on the TV. */
	private fun sortResults(list: List<LiResult>): List<LiResult> =
		list.sortedWith(compareByDescending<LiResult> { it.isBest }.thenByDescending { it.seeds })

	private fun renderResults(list: LinearLayout) {
		list.removeAllViews()
		val builder = PanelBuilder(list)
		results.take(150).forEachIndexed { index, result ->
			val detail = listOfNotNull(
				result.sizeText.takeIf { it.isNotBlank() },
				"${result.seeds} seeds",
				ageText(result.date),
				result.tracker.takeIf { it.isNotBlank() },
			).joinToString("  ·  ")
			val row = builder.action(result.name, detail, if (result.isBest) "★ Best" else null) { download(result.name, result.magnet, result.isPrivate, result.seeds) }
			if (index < 8) stagger(row, index)
		}
	}

	private fun ageText(epochSec: Long): String? {
		if (epochSec <= 0) return null
		val days = (System.currentTimeMillis() / 1000 - epochSec) / 86_400
		return when {
			days < 1 -> "today"
			days < 2 -> "yesterday"
			days < 60 -> "$days days ago"
			days < 730 -> "${days / 30} months ago"
			else -> "${days / 365} years ago"
		}
	}

	/** Rows slide in one after another — cheap (hardware layer, first rows only). */
	private fun stagger(view: View, index: Int) {
		view.alpha = 0f
		view.translationX = Ui.dp(host, 18).toFloat()
		view.animate().alpha(1f).translationX(0f).setStartDelay(index * 35L).setDuration(200L).withLayer().start()
	}

	// Download -------------------------------------------------------------------------------

	private fun download(name: String, magnet: String, isPrivate: Boolean, seeds: Int) {
		val waiting = AlertDialog.Builder(host).setTitle("Download").setMessage("Finding the right folder…").show()
		host.lifecycleScope.launch {
			val plan = try {
				li.plan(name)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				waiting.dismiss()
				host.toast(e.message ?: "Couldn't plan the download")
				return@launch
			}
			waiting.dismiss()
			val where = if (plan.crumbs.isEmpty()) "Li'Server decides" else plan.crumbs.joinToString("  ›  ")
			val message = buildString {
				append(name)
				append("\n\nSaving to:  ").append(where)
				plan.notes.forEach { append("\n• ").append(it) }
				if (seeds == 0) append("\n\n0 seeds — it may take very long or never finish.")
			}
			AlertDialog.Builder(host)
				.setTitle("Download this?")
				.setMessage(message)
				.setPositiveButton("Download") { _, _ ->
					host.lifecycleScope.launch {
						try {
							val folder = li.add(name, magnet, isPrivate, plan)
							host.toast("Added — saving to ${folder.replace('\\', '/').trimEnd('/').substringAfterLast('/')}")
						} catch (e: CancellationException) {
							throw e
						} catch (e: Exception) {
							host.toast(e.message ?: "Couldn't add the download")
						}
					}
				}
				.setNegativeButton("Cancel", null)
				.show()
				.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
		}
	}

	// Downloads ------------------------------------------------------------------------------

	private var downloadList: LinearLayout? = null

	private fun showDownloads() {
		val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		val (scroll, list) = scrollList()
		box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		downloadList = list
		list.addView(TextView(host).apply {
			text = "Loading…"
			setTextColor(ContextCompat.getColor(host, R.color.lumen_text_2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		})
		setBody(box)
		startPolling()
	}

	private fun startPolling() {
		pollJob?.cancel()
		pollJob = host.lifecycleScope.launch {
			var first = true
			while (isActive && tab == Tab.DOWNLOADS) {
				val items = runCatching { li.downloads() }.getOrNull()
				if (items != null) renderDownloads(items, first)
				first = false
				delay(2_000L)
			}
		}
	}

	private fun renderDownloads(items: List<LiDownload>, first: Boolean) {
		val list = downloadList ?: return
		val active = items.filterNot { it.isSeeding }.sortedByDescending { it.progress }
		val seeding = items.filter { it.isSeeding }
		tabPills[Tab.DOWNLOADS]?.text = if (active.isEmpty()) "Downloads" else "Downloads (${active.size})"
		// Rebuild only when the set of torrents changes; otherwise update the rows in place (keeps focus).
		val signature = (active + seeding).joinToString("|") { it.hash }
		if (list.tag == signature) {
			(active + seeding).forEach { item -> list.findViewWithTag<View>("dl:${item.hash}")?.let { bindDownload(it, item) } }
			return
		}
		val focusedHash = (list.findFocus()?.tag as? String)?.removePrefix("dl:")
		list.tag = signature
		list.removeAllViews()
		val builder = PanelBuilder(list)
		if (items.isEmpty()) {
			builder.note("Nothing is downloading. Search for something and it shows up here.")
			return
		}
		if (active.isNotEmpty()) builder.header("Downloading  ${active.size}")
		active.forEachIndexed { index, item -> addDownloadRow(builder, item, focusedHash, if (first) index else -1) }
		if (seeding.isNotEmpty()) builder.header("Seeding  ${seeding.size}")
		seeding.forEach { item -> addDownloadRow(builder, item, focusedHash, -1) }
	}

	private fun addDownloadRow(builder: PanelBuilder, item: LiDownload, focusedHash: String?, animateIndex: Int) {
		val row = builder.action(item.name, "", "") { downloadActions(item.hash) }
		row.tag = "dl:${item.hash}"
		val texts = row.findViewById<TextView>(R.id.row_detail).parent as LinearLayout
		val bar = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
			max = 1000
			progressTintList = ContextCompat.getColorStateList(host, R.color.lumen_accent)
			progressBackgroundTintList = ContextCompat.getColorStateList(host, R.color.lumen_track)
			tag = "bar"
		}
		texts.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, 6)).apply { topMargin = Ui.dp(host, 6) })
		bindDownload(row, item)
		if (item.hash == focusedHash) row.requestFocus()
		if (animateIndex in 0..7) stagger(row, animateIndex)
	}

	private fun bindDownload(row: View, item: LiDownload) {
		val percent = item.progress.coerceIn(0.0, 100.0)
		val state = when {
			item.error != null -> "Error: ${item.error}"
			item.waiting -> "Waiting for peers"
			else -> item.state.ifBlank { "Queued" }
		}
		val detail = listOfNotNull(
			state,
			item.sizeText.takeIf { it.isNotBlank() },
			item.speed.takeIf { it.isNotBlank() && !it.startsWith("0") && !item.isSeeding }?.let { "↓ $it" },
			item.uploadSpeed.takeIf { it.isNotBlank() && !it.startsWith("0") }?.let { "↑ $it" },
		).joinToString("  ·  ")
		row.findViewById<TextView>(R.id.row_detail).apply {
			text = detail
			visibility = View.VISIBLE
		}
		row.findViewById<TextView>(R.id.row_value).apply {
			text = if (item.isSeeding) "✓" else "${percent.roundToInt()}%"
			visibility = View.VISIBLE
		}
		row.findViewWithTag<ProgressBar>("bar")?.apply {
			visibility = if (item.isSeeding) View.GONE else View.VISIBLE
			progress = (percent * 10).roundToInt()
		}
	}

	private fun downloadActions(hash: String) {
		val list = downloadList
		val row = list?.findViewWithTag<View>("dl:$hash")
		val name = row?.findViewById<TextView>(R.id.row_title)?.text?.toString().orEmpty()
		val paused = row?.findViewById<TextView>(R.id.row_detail)?.text?.contains("Paused", ignoreCase = true) == true
		val options = mutableListOf<Pair<String, () -> Unit>>()
		options += (if (paused) "Resume" else "Pause") to { act(hash, if (paused) "resume" else "pause") }
		options += "Force start" to { act(hash, "force") }
		options += "Delete (and its files)" to {
			app.lumen.player.ui.Dialogs.confirm(host, "Delete this download?", "$name\n\nIt is removed and its files are deleted from the disk. This cannot be undone.", "Delete") {
				act(hash, "delete")
			}
		}
		AlertDialog.Builder(host)
			.setTitle(name)
			.setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
			.setNegativeButton("Close", null)
			.show()
	}

	private fun act(hash: String, action: String) {
		host.lifecycleScope.launch {
			try {
				li.control(hash, action)
				host.toast(when (action) { "delete" -> "Deleted"; "pause" -> "Paused"; "resume" -> "Resumed"; else -> "Started" })
				downloadList?.tag = null
				if (tab == Tab.DOWNLOADS) startPolling()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				host.toast(e.message ?: "Didn't work")
			}
		}
	}

	// Trending -------------------------------------------------------------------------------

	private fun showTrending() {
		val (scroll, list) = scrollList()
		setBody(scroll)
		list.addView(TextView(host).apply {
			text = "Loading what airs today and what's trending…"
			setTextColor(ContextCompat.getColor(host, R.color.lumen_text_2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		})
		loadJob?.cancel()
		loadJob = host.lifecycleScope.launch {
			val (aired, movies) = try {
				li.discover()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				list.removeAllViews()
				PanelBuilder(list).note(e.message ?: "Couldn't load trending titles.")
				return@launch
			}
			list.removeAllViews()
			addDiscoverRow(list, "Airing today", aired)
			addDiscoverRow(list, "Trending movies — OK downloads the best copy", movies)
			if (aired.isEmpty() && movies.isEmpty()) PanelBuilder(list).note("Nothing to show right now.")
			firstFocusable(list)?.requestFocus()
		}
	}

	private fun addDiscoverRow(list: LinearLayout, title: String, items: List<LiDiscover>) {
		if (items.isEmpty()) return
		list.addView(host.cards.heading(title, items.size, 22f))
		val strip = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			clipChildren = false
			clipToPadding = false
			setPadding(Ui.dp(host, 2), Ui.dp(host, 10), Ui.dp(host, 48), Ui.dp(host, 16))
		}
		val scroller = HorizontalScrollView(host).apply {
			isHorizontalScrollBarEnabled = false
			clipChildren = false
			clipToPadding = false
			addView(strip)
		}
		list.addView(scroller)
		items.forEachIndexed { index, item ->
			val tile = discoverTile(item)
			strip.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				marginEnd = Ui.dp(host, 12)
			})
			if (index < 8) {
				tile.alpha = 0f
				tile.scaleX = 0.92f
				tile.scaleY = 0.92f
				tile.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(index * 40L).setDuration(220L).withLayer().start()
			}
		}
	}

	private fun discoverTile(item: LiDiscover): View {
		val width = Ui.dp(host, 150)
		val tile = LinearLayout(host).apply {
			orientation = LinearLayout.VERTICAL
			isFocusable = true
			isClickable = true
			setPadding(Ui.dp(host, 4), Ui.dp(host, 4), Ui.dp(host, 4), Ui.dp(host, 4))
			background = ContextCompat.getDrawable(host, R.drawable.bg_row)
		}
		val frame = FrameLayout(host).apply {
			background = ContextCompat.getDrawable(host, R.drawable.bg_card_image)
			clipToOutline = true
		}
		val image = ImageView(host).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
		frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		item.image?.let { Images.load(image, it) }
		if (item.inLibrary) {
			frame.addView(TextView(host).apply {
				text = "In library"
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
				setTextColor(ContextCompat.getColor(host, R.color.lumen_green))
				background = ContextCompat.getDrawable(host, R.drawable.bg_chip_green)
				setPadding(Ui.dp(host, 8), Ui.dp(host, 3), Ui.dp(host, 8), Ui.dp(host, 3))
			}, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
				setMargins(Ui.dp(host, 6), Ui.dp(host, 6), 0, 0)
			})
		}
		tile.addView(frame, LinearLayout.LayoutParams(width, (width * 1.5f).toInt()))
		tile.addView(TextView(host).apply {
			text = item.title
			maxLines = 1
			ellipsize = android.text.TextUtils.TruncateAt.END
			typeface = Ui.medium(host)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setTextColor(ContextCompat.getColorStateList(host, R.color.row_text))
			isDuplicateParentStateEnabled = true
			setPadding(Ui.dp(host, 2), Ui.dp(host, 6), 0, 0)
		}, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
		tile.addView(TextView(host).apply {
			text = item.subtitle
			maxLines = 1
			ellipsize = android.text.TextUtils.TruncateAt.END
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			setTextColor(ContextCompat.getColor(host, R.color.lumen_text_3))
			setPadding(Ui.dp(host, 2), 0, 0, 0)
		}, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
		Ui.focusScale(tile, 1.06f)
		tile.setOnClickListener {
			val directName = item.directName
			val directMagnet = item.directMagnet
			if (!item.isEpisode && directName != null && directMagnet != null) {
				download(directName, directMagnet, isPrivate = false, seeds = 1)
			} else {
				lastQuery = item.query ?: item.title
				results = emptyList()
				selectTab(Tab.SEARCH, focus = false)
				// Run the search right away.
				body.post {
					val input = firstFocusable(body) as? EditText
					input?.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
				}
			}
		}
		return tile
	}
}
