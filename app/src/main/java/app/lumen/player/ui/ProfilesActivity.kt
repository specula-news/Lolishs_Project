package app.lumen.player.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.lumen.player.R
import app.lumen.player.util.Account
import app.lumen.player.util.Images
import app.lumen.player.util.Ui
import kotlinx.coroutines.launch

/** "Who's watching?" — pick, add or remove the Jellyfin users saved on this TV. */
class ProfilesActivity : BaseActivity() {
	private lateinit var row: LinearLayout
	private lateinit var hint: TextView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER
			setBackgroundColor(Ui.color(context, R.attr.lumenBg))
			setPadding(Ui.dp(context, 64), Ui.dp(context, 40), Ui.dp(context, 64), Ui.dp(context, 40))
		}
		root.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)))
		root.addView(TextView(this).apply {
			text = "Who's watching?"
			typeface = Ui.display(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
			setTextColor(Ui.color(context, R.attr.lumenAccent))
			setPadding(0, Ui.dp(context, 16), 0, Ui.dp(context, 28))
		})
		row = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER
			clipChildren = false
			clipToPadding = false
			setPadding(Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12))
		}
		val scroller = HorizontalScrollView(this).apply {
			isHorizontalScrollBarEnabled = false
			clipChildren = false
			clipToPadding = false
			addView(row)
		}
		root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		hint = TextView(this).apply {
			text = "Hold OK on a profile to remove it from this TV"
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setTextColor(Ui.color(context, R.attr.lumenText3))
			setPadding(0, Ui.dp(context, 28), 0, 0)
		}
		root.addView(hint)
		setContentView(root)
		build()
	}

	private fun build() {
		row.removeAllViews()
		val accounts = prefs.accounts
		if (accounts.isEmpty()) {
			startActivity(Intent(this, LoginActivity::class.java))
			finish()
			return
		}
		val manyServers = accounts.map { it.serverUrl.trimEnd('/') }.distinct().size > 1
		var focus: View? = null
		accounts.forEach { account ->
			val tile = tile(account.userName, account.userName.take(1).uppercase(), account.imageUrl(), if (manyServers) account.serverName else null)
			tile.setOnClickListener { open(account) }
			tile.setOnLongClickListener {
				confirmRemove(account)
				true
			}
			if (account.key == prefs.lastAccountKey) focus = tile
		}
		val add = tile("Add user", "+", null, null)
		if (prefs.animations) {
			for (i in 0 until row.childCount) {
				val child = row.getChildAt(i)
				child.alpha = 0f
				child.scaleX = 0.85f
				child.scaleY = 0.85f
				child.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(80L + i * 60L).setDuration(260L)
					.setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).withLayer().start()
			}
		}
		add.setOnClickListener {
			startActivity(Intent(this, LoginActivity::class.java))
			finish()
		}
		(focus ?: row.getChildAt(0))?.requestFocus()
	}

	private fun tile(name: String, letter: String, imageUrl: String?, detail: String?): View {
		val tile = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			isFocusable = true
			isClickable = true
			isLongClickable = true
			setPadding(Ui.dp(context, 18), Ui.dp(context, 8), Ui.dp(context, 18), Ui.dp(context, 8))
		}
		val frame = FrameLayout(this).apply {
			background = ContextCompat.getDrawable(context, R.drawable.bg_person)
			foreground = ContextCompat.getDrawable(context, R.drawable.person_focus)
			isDuplicateParentStateEnabled = true
			clipToOutline = true
		}
		frame.addView(TextView(this).apply {
			text = letter
			gravity = Gravity.CENTER
			typeface = Ui.display(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
			setTextColor(Ui.color(context, R.attr.lumenAccent))
		}, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		if (imageUrl != null) {
			val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
			frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
			Images.load(image, imageUrl)
		}
		tile.addView(frame, LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 132)))
		tile.addView(TextView(this).apply {
			text = name
			gravity = Gravity.CENTER
			maxLines = 1
			typeface = Ui.medium(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
			setTextColor(ContextCompat.getColorStateList(context, R.color.row_text))
			isDuplicateParentStateEnabled = true
			setPadding(0, Ui.dp(context, 12), 0, 0)
		})
		if (detail != null) {
			tile.addView(TextView(this).apply {
				text = detail
				gravity = Gravity.CENTER
				maxLines = 1
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
				setTextColor(Ui.color(context, R.attr.lumenText3))
			})
		}
		Ui.focusScale(tile, 1.08f)
		row.addView(tile)
		return tile
	}

	private fun open(account: Account) {
		prefs.activate(account)
		startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
		finish()
	}

	private fun confirmRemove(account: Account) {
		Dialogs.confirm(this, "Remove ${account.userName}?", "${account.userName} will be signed out on this TV.", "Remove") {
			AppScope.io.launch { api.logout(account.serverUrl, account.token, account.deviceId) }
			prefs.removeAccount(account)
			build()
		}
	}
}
