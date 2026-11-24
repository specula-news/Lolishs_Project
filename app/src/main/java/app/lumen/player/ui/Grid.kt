package app.lumen.player.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.lumen.player.jellyfin.JfItem

/**
 * RecyclerView that keeps D-pad focus inside the grid while it scrolls (stock RecyclerView lets
 * focus jump to the sidebar when the next row isn't laid out yet).
 */
class TvRecyclerView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

	init {
		isFocusable = false
		descendantFocusability = FOCUS_AFTER_DESCENDANTS
		itemAnimator = null
		clipToPadding = false
	}

	override fun focusSearch(focused: View, direction: Int): View? {
		val result = super.focusSearch(focused, direction)
		if (direction == View.FOCUS_DOWN || direction == View.FOCUS_UP) {
			val inside = result != null && isDescendant(result)
			if (!inside) {
				val delta = if (direction == View.FOCUS_DOWN) 1 else -1
				if (canScrollVertically(delta)) {
					smoothScrollBy(0, delta * (focused.height + 24))
					return focused
				}
			}
		}
		return result
	}

	private fun isDescendant(view: View): Boolean {
		var current: Any? = view.parent
		while (current is View) {
			if (current === this) return true
			current = current.parent
		}
		return false
	}
}

/** Paged grid of cards with "load more" when you get near the end. */
class CardGridAdapter(
	private val cards: Cards,
	private val style: CardStyle,
	private val onClick: (JfItem) -> Unit,
	private val onFocus: (JfItem) -> Unit,
	private val onNeedMore: () -> Unit,
) : RecyclerView.Adapter<CardGridAdapter.Holder>() {

	class Holder(view: View) : RecyclerView.ViewHolder(view)

	val items = mutableListOf<JfItem>()
	var total = 0

	fun replace(newItems: List<JfItem>, newTotal: Int) {
		items.clear()
		items.addAll(newItems)
		total = newTotal
		notifyDataSetChanged()
	}

	fun append(more: List<JfItem>) {
		val start = items.size
		items.addAll(more)
		notifyItemRangeInserted(start, more.size)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(cards.create(parent, style))

	override fun getItemCount(): Int = items.size

	override fun onBindViewHolder(holder: Holder, position: Int) {
		val item = items[position]
		cards.bind(holder.itemView, item, style)
		holder.itemView.setOnClickListener { onClick(item) }
		app.lumen.player.util.Ui.focusScale(holder.itemView, 1.07f) { focused -> if (focused) onFocus(item) }
		if (position >= items.size - 24 && items.size < total) onNeedMore()
	}

	companion object {
		fun spanCount(context: Context, availableWidthPx: Int, cardWidthPx: Int): Int {
			val cell = cardWidthPx + (14 * context.resources.displayMetrics.density).toInt()
			return (availableWidthPx / cell).coerceIn(2, 12)
		}

		fun layoutManager(context: Context, span: Int) = GridLayoutManager(context, span)
	}
}

/**
 * Vertical page for the remote: UP / DOWN move focus row by row and the page glides so the focused
 * row sits near the top. Holding the button down can't lose track (the stock ScrollView then took
 * focus itself and the next press landed in the menu), and DOWN at the end stays put.
 */
class TvScrollView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

	private val onFocus = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
		if (newFocus != null && newFocus !== this && contains(newFocus)) reveal(newFocus)
	}

	init {
		isFocusable = false
		descendantFocusability = FOCUS_AFTER_DESCENDANTS
		isVerticalScrollBarEnabled = false
		clipToPadding = false
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		viewTreeObserver.addOnGlobalFocusChangeListener(onFocus)
	}

	override fun onDetachedFromWindow() {
		viewTreeObserver.removeOnGlobalFocusChangeListener(onFocus)
		super.onDetachedFromWindow()
	}

	// All scrolling is done by reveal(), smoothly and from where the focused view really is.
	override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

	override fun executeKeyEvent(event: KeyEvent): Boolean {
		val code = event.keyCode
		if (code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_UP) return super.executeKeyEvent(event)
		if (event.action != KeyEvent.ACTION_DOWN) return false
		val direction = if (code == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP
		val current = findFocus()?.takeIf { it !== this }
		val next = FocusFinder.getInstance().findNextFocus(this, current, direction)
		if (next != null && next !== current) {
			next.requestFocus(direction)
			return true
		}
		if (direction == View.FOCUS_DOWN) {
			smoothScrollTo(0, maxScroll())
			return true // the end of the page: stay here, never fall into the menu
		}
		smoothScrollTo(0, 0)
		return false // the top: the app may move up to the top bar
	}

	/**
	 * Scrolls so [view] is comfortably on screen: anything that fits on the first screen (like the
	 * Home banner) shows from the very top, further down the focused row sits near the top.
	 */
	fun reveal(view: View) {
		val rect = Rect()
		view.getDrawingRect(rect)
		offsetDescendantRectToMyCoords(view, rect)
		val visible = height - paddingTop - paddingBottom
		val target = if (rect.bottom <= visible) 0 else rect.top - (visible * 0.22f).toInt()
		smoothScrollTo(0, target.coerceIn(0, maxScroll()))
	}

	/** Scrolls so [view]'s top sits at [fraction] of the screen height (e.g. below a preview window). */
	fun revealAt(view: View, fraction: Float) {
		val rect = Rect()
		view.getDrawingRect(rect)
		offsetDescendantRectToMyCoords(view, rect)
		val visible = height - paddingTop - paddingBottom
		smoothScrollTo(0, (rect.top - (visible * fraction).toInt()).coerceIn(0, maxScroll()))
	}

	private fun maxScroll(): Int = maxOf(0, (getChildAt(0)?.height ?: 0) - (height - paddingTop - paddingBottom))

	private fun contains(view: View): Boolean {
		var current: Any? = view.parent
		while (current is View) {
			if (current === this) return true
			current = current.parent
		}
		return false
	}
}
