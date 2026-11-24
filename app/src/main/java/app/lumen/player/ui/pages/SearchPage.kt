package app.lumen.player.ui.pages

import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import app.lumen.player.R
import app.lumen.player.jellyfin.ItemQuery
import app.lumen.player.ui.CardGridAdapter
import app.lumen.player.ui.CardStyle
import app.lumen.player.ui.MainActivity
import app.lumen.player.ui.TvRecyclerView
import app.lumen.player.util.Ui
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search movies, shows and episodes as you type. */
class SearchPage(host: MainActivity) : Page(host) {
	private val root = LinearLayout(host).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(Ui.dp(host, 26), Ui.dp(host, 24), Ui.dp(host, 24), 0)
	}
	override val view: View = root
	private val input = EditText(host)
	private val status = TextView(host)
	private val grid = TvRecyclerView(host)
	private val adapter = CardGridAdapter(host.cards, CardStyle.POSTER, { host.openItem(it) }, { host.setBackdrop(it) }, {})
	private var searchJob: Job? = null

	init {
		root.addView(host.cards.heading("Search", null, 40f))
		input.apply {
			hint = "Movies, shows, episodes…"
			setSingleLine()
			imeOptions = EditorInfo.IME_ACTION_SEARCH
			background = ContextCompat.getDrawable(host, R.drawable.bg_edit)
			setTextColor(Ui.color(host, R.attr.lumenText))
			setHintTextColor(Ui.color(host, R.attr.lumenText3))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
			typeface = Ui.medium(host)
			setPadding(Ui.dp(host, 20), 0, Ui.dp(host, 20), 0)
			val icon = ContextCompat.getDrawable(host, R.drawable.ic_search)?.mutate()?.apply {
				val size = Ui.dp(host, 24)
				setBounds(0, 0, size, size)
				setTint(Ui.color(host, R.attr.lumenAccent))
			}
			setCompoundDrawablesRelative(icon, null, null, null)
			compoundDrawablePadding = Ui.dp(host, 14)
		}
		root.addView(input, LinearLayout.LayoutParams(Ui.dp(host, 720), Ui.dp(host, 60)).apply { topMargin = Ui.dp(host, 16) })
		status.apply {
			setTextColor(Ui.color(host, R.attr.lumenText2))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			setPadding(Ui.dp(host, 6), Ui.dp(host, 14), 0, 0)
			text = "Type at least two letters. Press OK on the field to open the keyboard."
		}
		root.addView(status)
		grid.adapter = adapter
		grid.layoutManager = GridLayoutManager(host, 6)
		grid.setPadding(0, Ui.dp(host, 10), 0, Ui.dp(host, 60))
		root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

		input.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
			override fun afterTextChanged(s: Editable?) = search(s?.toString().orEmpty(), debounce = true)
		})
		input.setOnEditorActionListener { _, actionId, event ->
			val enter = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
				(event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
			if (enter) {
				search(input.text.toString(), debounce = false)
				hideKeyboard()
				grid.postDelayed({ grid.getChildAt(0)?.requestFocus() }, 400L)
			}
			enter
		}
	}

	override fun onShow() {
		grid.post {
			val width = host.cards.cardSize(CardStyle.POSTER).first
			(grid.layoutManager as GridLayoutManager).spanCount = CardGridAdapter.spanCount(host, grid.width, width)
		}
	}

	override fun focusFirst(): Boolean = input.requestFocus()

	private fun hideKeyboard() {
		val imm = host.getSystemService(InputMethodManager::class.java)
		imm?.hideSoftInputFromWindow(input.windowToken, 0)
	}

	private fun search(term: String, debounce: Boolean) {
		searchJob?.cancel()
		val query = term.trim()
		if (query.length < 2) {
			adapter.replace(emptyList(), 0)
			status.text = "Type at least two letters."
			return
		}
		searchJob = host.lifecycleScope.launch {
			if (debounce) delay(450L)
			status.text = "Searching…"
			val result = runCatching {
				host.api.items(host.session, ItemQuery(
					includeTypes = listOf("Movie", "Series", "Episode", "BoxSet"),
					searchTerm = query,
					sortBy = "SortName",
					limit = 120,
				))
			}
			result.onSuccess {
				adapter.replace(it.items, it.items.size)
				status.text = if (it.items.isEmpty()) "No results for “$query”." else "${it.total} results for “$query”"
			}.onFailure {
				status.text = "Search failed: ${it.message}"
			}
		}
	}
}
