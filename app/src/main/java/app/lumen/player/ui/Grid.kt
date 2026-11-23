package app.lumen.player.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
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
