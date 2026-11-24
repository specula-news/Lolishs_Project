package app.lumen.player.ui.pages

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import app.lumen.player.liserver.LiHistory
import app.lumen.player.liserver.LiResult
import app.lumen.player.liserver.LiServerApi
import app.lumen.player.liserver.LiServerException
import app.lumen.player.ui.Dialogs
import app.lumen.player.ui.MainActivity
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
 * Li'Server made for the remote, laid out like the website: Search (quality chips, private
 * trackers, sort order, Best Choice), Downloads (downloading / seeding / history with pause,
 * resume, force, seed longer, stop seeding, delete) and Trending. Uses Li'Server's own API and
 * stays signed in; the full website is one button away.
 */
class LiServerPage(host: MainActivity) : Page(host) {
	private enum class Tab { SEARCH, DOWNLOADS, TRENDING }
	private enum class DlTab { DOWNLOADING, SEEDING, HISTORY }
	private enum class ResultSort(val label: String) { BEST("Best"), SEEDS("Seeds"), SIZE("Size"), NEWEST("Newest"), NAME("Name") }
	private enum class DlSort(val label: String) { PROGRESS("Progress"), SPEED("Speed"), NAME("Name") }

	private val li = LiServerApi(host.prefs)
	private val root = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(Ui.dp(host, 36), Ui.dp(host, 22), Ui.dp(host, 28), 0)
	}
	override val view: View = root
	private val status = TextView(host)
	private val speeds = TextView(host)
	private val tabsRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
	private val body = FrameLayout(host)
	private val tabPills = mutableMapOf<Tab, TextView>()
	private var tab = Tab.SEARCH
	private var signedInAs: String? = null
	private var pollJob: Job? = null
	private var loadJob: Job? = null

	// State that survives switching tabs (like the website's saved search).
	private var lastQuery = ""
	private var privateTrackers = false
	private val qualityTags = linkedSetOf<String>()
	private var resultSort = ResultSort.BEST
	private var results: List<LiResult> = emptyList()
	private var dlTab = DlTab.DOWNLOADING
	private var dlSort = DlSort.PROGRESS
	private var lastDownloads: List<LiDownload> = emptyList()
	private var shotApplied = false
	private var shotQuery: String? = null

	private fun c(attr: Int) = Ui.color(host, attr)

	init {
		val header = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
		header.addView(host.cards.heading("Li'Server", null, 30f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		speeds.apply {
			setTextColor(c(R.attr.lumenText2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			typeface = Typeface.MONOSPACE
			setPadding(0, 0, Ui.dp(host, 14), 0)
		}
		header.addView(speeds)
		status.apply {
			setTextColor(c(R.attr.lumenGreen))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			background = ContextCompat.getDrawable(host, R.drawable.bg_chip_green)
			setPadding(Ui.dp(host, 10), Ui.dp(host, 4), Ui.dp(host, 10), Ui.dp(host, 4))
			text = "Connecting…"
		}
		header.addView(status)
		root.addView(header)
		root.addView(tabsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			topMargin = Ui.dp(host, 12)
		})
		root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(host, 10) })
	}

	override fun onShow() = connect()

	override fun onHide() {
		pollJob?.cancel()
		loadJob?.cancel()
	}

	override fun onReturn() {
		if (signedInAs != null) startPolling()
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

	// Small building blocks -------------------------------------------------------------------

	/** A compact pill (40 dp) — the website's chips and buttons. */
	private fun chip(parent: ViewGroup, text: String, icon: Int? = null): TextView {
		val pill = Pills.create(parent, text, icon)
		pill.layoutParams = (pill.layoutParams as LinearLayout.LayoutParams).apply {
			height = Ui.dp(host, 38)
			marginEnd = Ui.dp(host, 8)
		}
		pill.setPadding(Ui.dp(host, 16), 0, Ui.dp(host, 16), 0)
		pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
		return pill
	}

	private fun label(text: String, sizeSp: Float = 13f, attr: Int = R.attr.lumenText2) = TextView(host).apply {
		this.text = text
		setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
		setTextColor(c(attr))
	}

	private fun hRow() = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

	private fun scrollList(): Pair<ScrollView, LinearLayout> {
		val scroll = ScrollView(host).apply {
			isVerticalScrollBarEnabled = false
			clipToPadding = false
			setPadding(0, 0, 0, Ui.dp(host, 50))
		}
		val list = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		scroll.addView(list)
		return scroll to list
	}

	/**
	 * One compact list row: title, a colored facts line, optional progress bar and a right-hand value.
	 * Returns the row plus its parts so they can be updated in place.
	 */
	private class Row(val view: LinearLayout, val title: TextView, val facts: TextView, val value: TextView, val bar: ProgressBar?)

	private fun row(list: LinearLayout, title: String, withBar: Boolean, onClick: () -> Unit): Row {
		val view = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			background = ContextCompat.getDrawable(host, R.drawable.bg_row)
			isFocusable = true
			isClickable = true
			minimumHeight = Ui.dp(host, 44)
			setPadding(Ui.dp(host, 12), Ui.dp(host, 6), Ui.dp(host, 12), Ui.dp(host, 6))
			setOnClickListener { onClick() }
		}
		val texts = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
		val titleView = TextView(host).apply {
			text = title
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			typeface = Ui.medium(host)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
			setTextColor(ContextCompat.getColorStateList(host, R.color.row_text))
			isDuplicateParentStateEnabled = true
		}
		val factsView = TextView(host).apply {
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			setTextColor(c(R.attr.lumenText2))
			setPadding(0, Ui.dp(host, 1), 0, 0)
		}
		texts.addView(titleView)
		texts.addView(factsView)
		var bar: ProgressBar? = null
		if (withBar) {
			bar = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
				max = 1000
				progressTintList = Ui.colorList(host, R.attr.lumenAccent)
				progressBackgroundTintList = Ui.colorList(host, R.attr.lumenTrack)
			}
			texts.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, 4)).apply { topMargin = Ui.dp(host, 5) })
		}
		view.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
		val valueView = TextView(host).apply {
			typeface = Ui.bold(host)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setTextColor(ContextCompat.getColorStateList(host, R.color.row_accent))
			isDuplicateParentStateEnabled = true
			setPadding(Ui.dp(host, 12), 0, 0, 0)
		}
		view.addView(valueView)
		list.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			bottomMargin = Ui.dp(host, 2)
		})
		return Row(view, titleView, factsView, valueView, bar)
	}

	/** Facts line builder with the website's colored pieces. */
	private inner class Facts {
		val text = SpannableStringBuilder()
		fun add(part: String?, attr: Int? = null, bold: Boolean = false): Facts {
			if (part.isNullOrBlank()) return this
			if (text.isNotEmpty()) text.append("  ·  ")
			val start = text.length
			text.append(part)
			attr?.let { text.setSpan(ForegroundColorSpan(c(it)), start, text.length, 0) }
			if (bold) text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, 0)
			return this
		}
	}

	private fun hideKeyboard(view: View) {
		host.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
	}

	private fun field(hint: String, value: String, password: Boolean) = EditText(host).apply {
		this.hint = hint
		setText(value)
		setSingleLine()
		inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
		imeOptions = if (password) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
		background = ContextCompat.getDrawable(host, R.drawable.bg_edit)
		setTextColor(c(R.attr.lumenText))
		setHintTextColor(c(R.attr.lumenText3))
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		setPadding(Ui.dp(host, 16), 0, Ui.dp(host, 16), 0)
	}

	private fun setBody(content: View) {
		body.removeAllViews()
		body.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		if (!host.prefs.animations) return
		content.alpha = 0f
		content.translationY = Ui.dp(host, 10).toFloat()
		content.animate().alpha(1f).translationY(0f).setDuration(200L).withLayer().start()
	}

	private fun stagger(view: View, index: Int) {
		if (!host.prefs.animations || index > 9) return
		view.alpha = 0f
		view.translationX = Ui.dp(host, 14).toFloat()
		view.animate().alpha(1f).translationX(0f).setStartDelay(index * 28L).setDuration(180L).withLayer().start()
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
		if (app.lumen.player.BuildConfig.DEBUG && !shotApplied) {
			shotApplied = true
			when (host.intent.getStringExtra("shot_li_tab")) {
				"downloads" -> tab = Tab.DOWNLOADS
				"trending" -> tab = Tab.TRENDING
			}
			when (host.intent.getStringExtra("shot_li_dl")) {
				"seeding" -> dlTab = DlTab.SEEDING
				"history" -> dlTab = DlTab.HISTORY
			}
			shotQuery = host.intent.getStringExtra("shot_li_query")
		}
		status.text = "●  $user"
		buildTabs()
		selectTab(tab, focus = false)
		startPolling()
	}

	private fun buildTabs() {
		tabsRow.removeAllViews()
		tabPills.clear()
		tabPills[Tab.SEARCH] = chip(tabsRow, "Search", R.drawable.ic_search).apply { setOnClickListener { selectTab(Tab.SEARCH, true) } }
		tabPills[Tab.DOWNLOADS] = chip(tabsRow, "Downloads", R.drawable.ic_download).apply { setOnClickListener { selectTab(Tab.DOWNLOADS, true) } }
		tabPills[Tab.TRENDING] = chip(tabsRow, "Trending", R.drawable.ic_star).apply { setOnClickListener { selectTab(Tab.TRENDING, true) } }
		tabsRow.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
		chip(tabsRow, "Website", R.drawable.ic_link).setOnClickListener {
			host.startActivity(Intent(host, TorrentActivity::class.java))
		}
		chip(tabsRow, "Sign out", R.drawable.ic_logout).setOnClickListener {
			Dialogs.confirm(host, "Sign out of Li'Server?", "The saved Li'Server password is removed from this TV.", "Sign out") {
				host.lifecycleScope.launch {
					li.logout()
					host.prefs.liServerPasswordEncrypted = null
					signedInAs = null
					pollJob?.cancel()
					showLogin("Signed out.")
				}
			}
		}
	}

	private fun selectTab(newTab: Tab, focus: Boolean) {
		tab = newTab
		tabPills.forEach { (key, pill) -> pill.isSelected = key == newTab }
		when (newTab) {
			Tab.SEARCH -> showSearch(focus)
			Tab.DOWNLOADS -> showDownloads(focus)
			Tab.TRENDING -> showTrending()
		}
	}

	private fun showMessage(text: String, vararg actions: Pair<String, () -> Unit>) {
		val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		box.addView(label(text, 16f).apply { setLineSpacing(0f, 1.2f) })
		if (actions.isNotEmpty()) {
			val row = hRow().apply { setPadding(0, Ui.dp(host, 16), 0, 0) }
			actions.forEach { (name, action) -> chip(row, name).setOnClickListener { action() } }
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
		form.addView(label(message?.let { "$it\n" }.orEmpty() + "Sign in once — the login is saved encrypted on this TV and used again automatically.", 15f))
		val user = field("Username", host.prefs.liServerUser.orEmpty(), password = false)
		val pass = field("Password", "", password = true)
		form.addView(user, LinearLayout.LayoutParams(Ui.dp(host, 420), Ui.dp(host, 50)).apply { topMargin = Ui.dp(host, 16) })
		form.addView(pass, LinearLayout.LayoutParams(Ui.dp(host, 420), Ui.dp(host, 50)).apply { topMargin = Ui.dp(host, 10) })
		val error = label("", 14f, R.attr.lumenRed).apply { setPadding(0, Ui.dp(host, 8), 0, 0) }
		form.addView(error)
		val buttons = hRow().apply { setPadding(0, Ui.dp(host, 12), 0, 0) }
		val signIn = chip(buttons, "Sign in", R.drawable.ic_check)
		chip(buttons, "Open website", R.drawable.ic_link).setOnClickListener {
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

	// Search ---------------------------------------------------------------------------------

	private var resultList: LinearLayout? = null
	private var resultInfo: TextView? = null

	private fun showSearch(focus: Boolean) {
		val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		val bar = hRow()
		val input = field("Search for a title, then add S01 or a quality tag…", lastQuery, password = false).apply {
			imeOptions = EditorInfo.IME_ACTION_SEARCH
		}
		bar.addView(input, LinearLayout.LayoutParams(0, Ui.dp(host, 46), 1f).apply { marginEnd = Ui.dp(host, 10) })
		val go = chip(bar, "Search", R.drawable.ic_search)
		val priv = chip(bar, privateLabel())
		priv.isSelected = privateTrackers
		val sort = chip(bar, "Sort: ${resultSort.label}", R.drawable.ic_sort)
		box.addView(bar)

		// The website's quality chips: they're added to the search words.
		val tags = hRow().apply { setPadding(0, Ui.dp(host, 10), 0, 0) }
		tags.addView(label("Quality", 12f, R.attr.lumenText3).apply { setPadding(Ui.dp(host, 4), 0, Ui.dp(host, 10), 0) })
		QUALITY.forEach { tag ->
			val pill = chip(tags, tag)
			pill.isSelected = tag in qualityTags
			pill.setOnClickListener {
				if (!qualityTags.add(tag)) qualityTags.remove(tag)
				pill.isSelected = tag in qualityTags
			}
		}
		box.addView(HorizontalScrollView(host).apply {
			isHorizontalScrollBarEnabled = false
			clipToPadding = false
			addView(tags)
		})
		val info = label(
			if (results.isEmpty()) "Press OK on the field to type. ★ Best Choice = 1080p x265 / AV1 with seeders." else resultSummary(),
			12.5f,
		).apply { setPadding(Ui.dp(host, 4), Ui.dp(host, 10), 0, Ui.dp(host, 6)) }
		box.addView(info)
		val (scroll, list) = scrollList()
		box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		resultList = list
		resultInfo = info
		setBody(box)
		renderResults()

		fun doSearch() {
			val words = input.text.toString().trim()
			if (words.length < 2) {
				info.text = "Type at least two letters."
				return
			}
			hideKeyboard(input)
			lastQuery = words
			val query = (listOf(words) + qualityTags.filter { !words.contains(it, ignoreCase = true) }).joinToString(" ")
			info.text = "Searching ${if (privateTrackers) "public and private" else "public"} trackers for “$query”…"
			list.removeAllViews()
			loadJob?.cancel()
			loadJob = host.lifecycleScope.launch {
				try {
					results = li.search(query, privateTrackers)
					info.text = if (results.isEmpty()) "Nothing found for “$query”." else resultSummary()
					renderResults()
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
			priv.text = privateLabel()
			priv.isSelected = privateTrackers
		}
		sort.setOnClickListener {
			resultSort = ResultSort.entries[(resultSort.ordinal + 1) % ResultSort.entries.size]
			sort.text = "Sort: ${resultSort.label}"
			renderResults()
		}
		input.setOnEditorActionListener { _, actionId, event ->
			val enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
				(event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
			if (enter) doSearch()
			enter
		}
		if (focus) input.requestFocus()
		shotQuery?.let { q ->
			shotQuery = null
			input.setText(q)
			doSearch()
		}
	}

	private fun privateLabel() = if (privateTrackers) "Private trackers ✓" else "Private trackers"

	private fun resultSummary(): String {
		val best = results.count { it.isBest }
		return "${results.size} results for “$lastQuery”" + if (best > 0) "  ·  $best Best Choice" else ""
	}

	private fun sorted(list: List<LiResult>): List<LiResult> = when (resultSort) {
		ResultSort.BEST -> list.sortedWith(compareByDescending<LiResult> { it.isBest }.thenByDescending { it.seeds })
		ResultSort.SEEDS -> list.sortedByDescending { it.seeds }
		ResultSort.SIZE -> list.sortedByDescending { sizeBytes(it.sizeText) }
		ResultSort.NEWEST -> list.sortedByDescending { it.date }
		ResultSort.NAME -> list.sortedBy { it.name.lowercase() }
	}

	private fun sizeBytes(text: String): Double {
		val m = Regex("([\\d.]+)\\s*(KB|MB|GB|TB|KiB|MiB|GiB|TiB)", RegexOption.IGNORE_CASE).find(text) ?: return 0.0
		val v = m.groupValues[1].toDoubleOrNull() ?: return 0.0
		return when (m.groupValues[2].uppercase().take(1)) { "K" -> v; "M" -> v * 1e3; "G" -> v * 1e6; else -> v * 1e9 }
	}

	private fun renderResults() {
		val list = resultList ?: return
		list.removeAllViews()
		sorted(results).take(200).forEachIndexed { index, result ->
			val r = row(list, result.name, withBar = false) { download(result.name, result.magnet, result.isPrivate, result.seeds) }
			val seedsAttr = when {
				result.seeds >= 10 -> R.attr.lumenGreen
				result.seeds > 0 -> R.attr.lumenAmber
				else -> R.attr.lumenRed
			}
			val facts = Facts()
			if (result.isBest) facts.add("★ Best Choice", R.attr.lumenGreen, bold = true)
			facts.add(result.sizeText)
				.add("${result.seeds} seeds", seedsAttr, bold = true)
				.add(ageText(result.date))
				.add(result.tracker.takeIf { it.isNotBlank() })
			if (result.isPrivate) facts.add("🔒 Private", R.attr.lumenAmber)
			r.facts.text = facts.text
			r.value.text = "Download"
			stagger(r.view, index)
		}
	}

	private fun ageText(epochSec: Long): String? {
		if (epochSec <= 0) return null
		val days = (System.currentTimeMillis() / 1000 - epochSec) / 86_400
		return when {
			days < 1 -> "today"
			days < 2 -> "yesterday"
			days < 60 -> "$days days"
			days < 730 -> "${days / 30} mo"
			else -> "${days / 365} yr"
		}
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
							startPolling()
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
	private val dlPills = mutableMapOf<DlTab, TextView>()
	private val rows = mutableMapOf<String, Row>()

	private fun showDownloads(focus: Boolean) {
		val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
		val bar = hRow()
		dlPills.clear()
		DlTab.entries.forEach { t ->
			val pill = chip(bar, dlLabel(t, lastDownloads))
			pill.isSelected = t == dlTab
			pill.setOnClickListener {
				dlTab = t
				dlPills.forEach { (key, p) -> p.isSelected = key == t }
				downloadList?.tag = null
				renderCurrentDownloads(animate = true)
			}
			dlPills[t] = pill
		}
		bar.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
		val sort = chip(bar, "Sort: ${dlSort.label}", R.drawable.ic_sort)
		sort.setOnClickListener {
			dlSort = DlSort.entries[(dlSort.ordinal + 1) % DlSort.entries.size]
			sort.text = "Sort: ${dlSort.label}"
			downloadList?.tag = null
			renderCurrentDownloads(animate = false)
		}
		box.addView(bar)
		val (scroll, list) = scrollList()
		scroll.setPadding(0, Ui.dp(host, 8), 0, Ui.dp(host, 50))
		box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		downloadList = list
		list.tag = null
		setBody(box)
		renderCurrentDownloads(animate = true)
		if (focus) dlPills[dlTab]?.requestFocus()
		startPolling()
	}

	private fun dlLabel(t: DlTab, items: List<LiDownload>): String = when (t) {
		DlTab.DOWNLOADING -> "Downloading  ${items.count { !it.isSeeding }}"
		DlTab.SEEDING -> "Seeding  ${items.count { it.isSeeding }}"
		DlTab.HISTORY -> "History"
	}

	/** Polls the download list every 2 s (header speeds, tab counts, the Downloads list). */
	private fun startPolling() {
		pollJob?.cancel()
		pollJob = host.lifecycleScope.launch {
			while (isActive && signedInAs != null) {
				val items = runCatching { li.downloads() }.getOrNull()
				if (items != null) onDownloads(items)
				delay(if (tab == Tab.DOWNLOADS) 2_000L else 5_000L)
			}
		}
	}

	private fun onDownloads(items: List<LiDownload>) {
		lastDownloads = items
		val down = items.filter { !it.isSeeding }.sumOf { it.speedMb }
		val up = items.sumOf { it.uploadMb }
		speeds.text = String.format(java.util.Locale.US, "↓ %.2f MB/s   ↑ %.2f MB/s", down, up)
		val active = items.count { !it.isSeeding }
		tabPills[Tab.DOWNLOADS]?.text = if (active == 0) "Downloads" else "Downloads  $active"
		dlPills.forEach { (t, pill) -> pill.text = dlLabel(t, items) }
		if (tab == Tab.DOWNLOADS && dlTab != DlTab.HISTORY) renderCurrentDownloads(animate = false)
	}

	private fun renderCurrentDownloads(animate: Boolean) {
		val list = downloadList ?: return
		if (dlTab == DlTab.HISTORY) {
			showHistory(list)
			return
		}
		val items = lastDownloads.filter { if (dlTab == DlTab.SEEDING) it.isSeeding else !it.isSeeding }.let { all ->
			when (dlSort) {
				DlSort.PROGRESS -> all.sortedByDescending { it.progress }
				DlSort.SPEED -> all.sortedByDescending { if (dlTab == DlTab.SEEDING) it.uploadMb else it.speedMb }
				DlSort.NAME -> all.sortedBy { it.name.lowercase() }
			}
		}
		// Same torrents in the same order: update in place, so focus and scrolling stay put.
		val signature = "${dlTab.name}|" + items.joinToString("|") { it.hash }
		if (list.tag == signature) {
			items.forEach { item -> rows[item.hash]?.let { bindDownload(it, item) } }
			return
		}
		val focusedHash = rows.entries.firstOrNull { it.value.view.isFocused }?.key
		list.tag = signature
		list.removeAllViews()
		rows.clear()
		if (items.isEmpty()) {
			list.addView(label(
				if (dlTab == DlTab.SEEDING) "Nothing is seeding. Finished downloads stay here while they seed."
				else "Nothing is downloading. Search for a title and it shows up here.",
				15f,
			).apply { setPadding(Ui.dp(host, 6), Ui.dp(host, 10), 0, 0) })
			return
		}
		items.forEachIndexed { index, item ->
			val r = row(list, item.name, withBar = !item.isSeeding) { downloadActions(item.hash) }
			rows[item.hash] = r
			bindDownload(r, item)
			if (item.hash == focusedHash) r.view.requestFocus()
			if (animate) stagger(r.view, index)
		}
	}

	private fun bindDownload(r: Row, item: LiDownload) {
		val percent = item.progress.coerceIn(0.0, 100.0)
		val facts = Facts()
		when {
			item.error != null -> facts.add("Error: ${item.error}", R.attr.lumenRed, bold = true)
			item.isSeeding -> facts.add("Seeding", R.attr.lumenGreen, bold = true)
			item.isPaused -> facts.add("Paused", R.attr.lumenText3, bold = true)
			item.waiting -> facts.add("Waiting for peers", R.attr.lumenAmber, bold = true)
			else -> facts.add(item.state.ifBlank { "Queued" }, R.attr.lumenAmber, bold = true)
		}
		facts.add(item.sizeText)
		if (!item.isSeeding && item.speedMb > 0.0) facts.add("↓ ${item.speed}")
		if (item.uploadMb > 0.0) facts.add("↑ ${item.uploadSpeed}")
		if (item.isSeeding && item.seedHours != null && item.seedGoal != null) {
			facts.add(String.format(java.util.Locale.US, "seeded %.1f of %.0f h", item.seedHours, item.seedGoal))
		}
		if (item.isPrivate) facts.add("🔒", R.attr.lumenAmber)
		r.facts.text = facts.text
		r.value.text = if (item.isSeeding) "✓" else "${percent.roundToInt()}%"
		r.bar?.progress = (percent * 10).roundToInt()
	}

	private fun downloadActions(hash: String) {
		val item = lastDownloads.firstOrNull { it.hash == hash } ?: return
		val options = mutableListOf<Pair<String, () -> Unit>>()
		if (item.isSeeding) {
			options += "Seed longer…" to { seedLonger(item) }
			options += "Stop seeding now" to {
				Dialogs.confirm(host, "Stop seeding?", "${item.name}\n\nIt moves into your library. Private trackers may count stopping early as a hit-and-run.", "Stop seeding") {
					act { li.stopSeeding(hash); "Seeding stopped — it moves to the library in a moment" }
				}
			}
		} else {
			options += (if (item.isPaused) "Resume" else "Pause") to {
				act { li.control(hash, if (item.isPaused) "resume" else "pause"); if (item.isPaused) "Resumed" else "Paused" }
			}
			options += "Force start" to { act { li.control(hash, "force"); "Started" } }
		}
		options += "Search for other copies" to {
			lastQuery = item.name.replace(Regex("[._]"), " ").substringBefore(" 1080p").substringBefore(" 2160p").substringBefore(" 720p").trim()
			results = emptyList()
			selectTab(Tab.SEARCH, focus = true)
		}
		options += "Delete (and its files)" to {
			Dialogs.confirm(host, "Delete this download?", "${item.name}\n\nIt is removed and its files are deleted from the disk. This cannot be undone.", "Delete") {
				act { li.control(hash, "delete"); "Deleted" }
			}
		}
		AlertDialog.Builder(host)
			.setTitle(item.name)
			.setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
			.setNegativeButton("Close", null)
			.show()
	}

	private fun seedLonger(item: LiDownload) {
		val hours = listOf(6, 12, 24, 48, 72)
		val now = item.seedHours?.let { String.format(java.util.Locale.US, "Seeded %.1f of %.0f h so far. ", it, item.seedGoal ?: 0.0) }.orEmpty()
		AlertDialog.Builder(host)
			.setTitle("Seed longer — ${now}add:")
			.setItems(hours.map { "$it more hours" }.toTypedArray()) { _, which ->
				act { li.seedLonger(item.hash, hours[which]); "It seeds ${hours[which]} hours longer" }
			}
			.setNegativeButton("Close", null)
			.show()
	}

	private fun act(block: suspend () -> String) {
		host.lifecycleScope.launch {
			try {
				host.toast(block())
				downloadList?.tag = null
				startPolling()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				host.toast(e.message ?: "Didn't work")
			}
		}
	}

	private fun showHistory(list: LinearLayout) {
		list.tag = "history"
		list.removeAllViews()
		rows.clear()
		list.addView(label("Loading history…", 14f))
		loadJob?.cancel()
		loadJob = host.lifecycleScope.launch {
			val history: List<LiHistory> = try {
				li.history()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				list.removeAllViews()
				list.addView(label(e.message ?: "Couldn't load the history", 14f, R.attr.lumenRed))
				return@launch
			}
			list.removeAllViews()
			if (history.isEmpty()) {
				list.addView(label("History is empty. Torrents you add are listed here.", 15f))
				return@launch
			}
			history.take(300).forEachIndexed { index, entry ->
				val r = row(list, entry.name, withBar = false) {
					lastQuery = entry.name.replace(Regex("[._]"), " ").trim()
					results = emptyList()
					selectTab(Tab.SEARCH, focus = true)
				}
				r.facts.text = Facts().add(entry.date).add(entry.path.replace('\\', '/').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }).text
				r.value.text = "Search again"
				stagger(r.view, index)
			}
		}
	}

	// Trending -------------------------------------------------------------------------------

	private fun showTrending() {
		val (scroll, list) = scrollList()
		setBody(scroll)
		list.addView(label("Loading what airs today and what's trending…", 15f))
		loadJob?.cancel()
		loadJob = host.lifecycleScope.launch {
			val (aired, movies) = try {
				li.discover()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				list.removeAllViews()
				list.addView(label(e.message ?: "Couldn't load trending titles.", 15f, R.attr.lumenRed))
				return@launch
			}
			list.removeAllViews()
			addDiscoverRow(list, "Airing today", aired)
			addDiscoverRow(list, "Trending movies", movies)
			if (aired.isEmpty() && movies.isEmpty()) list.addView(label("Nothing to show right now.", 15f))
			firstFocusable(list)?.requestFocus()
		}
	}

	private fun addDiscoverRow(list: LinearLayout, title: String, items: List<LiDiscover>) {
		if (items.isEmpty()) return
		list.addView(host.cards.heading(title, items.size, 20f))
		val strip = LinearLayout(host).apply {
			orientation = LinearLayout.HORIZONTAL
			clipChildren = false
			clipToPadding = false
			setPadding(Ui.dp(host, 2), Ui.dp(host, 8), Ui.dp(host, 48), Ui.dp(host, 12))
		}
		list.addView(HorizontalScrollView(host).apply {
			isHorizontalScrollBarEnabled = false
			clipChildren = false
			clipToPadding = false
			addView(strip)
		})
		items.forEachIndexed { index, item ->
			val tile = discoverTile(item)
			strip.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				marginEnd = Ui.dp(host, 10)
			})
			if (index < 8 && host.prefs.animations) {
				tile.alpha = 0f
				tile.scaleX = 0.92f
				tile.scaleY = 0.92f
				tile.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(index * 40L).setDuration(220L).withLayer().start()
			}
		}
	}

	private fun discoverTile(item: LiDiscover): View {
		val width = Ui.dp(host, 128)
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
		val badge = when {
			item.inLibrary -> "In library"
			item.directMagnet != null && !item.isEpisode -> "1-click"
			else -> null
		}
		if (badge != null) {
			frame.addView(TextView(host).apply {
				text = badge
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
				setTextColor(c(R.attr.lumenGreen))
				background = ContextCompat.getDrawable(host, R.drawable.bg_chip_green)
				setPadding(Ui.dp(host, 7), Ui.dp(host, 2), Ui.dp(host, 7), Ui.dp(host, 2))
			}, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
				setMargins(Ui.dp(host, 6), Ui.dp(host, 6), 0, 0)
			})
		}
		tile.addView(frame, LinearLayout.LayoutParams(width, (width * 1.5f).toInt()))
		tile.addView(TextView(host).apply {
			text = item.title
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			typeface = Ui.medium(host)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setTextColor(ContextCompat.getColorStateList(host, R.color.row_text))
			isDuplicateParentStateEnabled = true
			setPadding(Ui.dp(host, 2), Ui.dp(host, 5), 0, 0)
		}, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
		tile.addView(TextView(host).apply {
			text = item.subtitle
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
			setTextColor(c(R.attr.lumenText3))
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
				body.post {
					val input = firstFocusable(body) as? EditText
					input?.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
				}
			}
		}
		return tile
	}

	private companion object {
		/** The website's quality chips. */
		val QUALITY = listOf("1080p", "x265", "HEVC", "ELiTE", "MeGusta", "BONE")
	}
}
