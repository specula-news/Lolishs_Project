package app.lumen.player.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lumen.player.R
import app.lumen.player.jellyfin.ApiException
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.PublicUser
import app.lumen.player.jellyfin.Session
import app.lumen.player.util.Images
import app.lumen.player.util.Prefs
import app.lumen.player.util.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Sign in to the (pre-filled) Jellyfin server: pick a user, Quick Connect, or type a username. */
class LoginActivity : BaseActivity() {
	private lateinit var users: LinearLayout
	private lateinit var passwordGroup: View
	private lateinit var username: EditText
	private lateinit var password: EditText
	private lateinit var code: TextView
	private lateinit var status: TextView
	private lateinit var serverLine: TextView
	private var job: Job? = null
	private var serverName = "Jellyfin"

	private val base: String get() = JellyfinApi.normalizeBaseUrl(prefs.serverUrl ?: Prefs.DEFAULT_SERVER)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_login)
		users = findViewById(R.id.users)
		passwordGroup = findViewById(R.id.password_group)
		username = findViewById(R.id.username)
		password = findViewById(R.id.password)
		code = findViewById(R.id.quick_connect_code)
		status = findViewById(R.id.status)
		serverLine = findViewById(R.id.server_line)

		findViewById<View>(R.id.btn_quick_connect).setOnClickListener { startQuickConnect() }
		findViewById<View>(R.id.btn_manual).setOnClickListener { showPassword(null) }
		findViewById<View>(R.id.btn_sign_in).setOnClickListener { signIn() }
		findViewById<View>(R.id.btn_change_server).setOnClickListener {
			Dialogs.input(this, "Jellyfin server address", base, Prefs.DEFAULT_SERVER) { value ->
				if (value.isNotBlank()) {
					prefs.serverUrl = JellyfinApi.normalizeBaseUrl(value)
					loadServer()
				}
			}
		}
		password.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				signIn()
				true
			} else false
		}
		loadServer()
	}

	private fun loadServer() {
		serverLine.text = "●  $base"
		status.text = "Connecting…"
		users.removeAllViews()
		lifecycleScope.launch {
			try {
				serverName = api.publicServerName(base)
				serverLine.text = "●  $serverName  ·  $base"
				val list = runCatching { api.publicUsers(base) }.getOrDefault(emptyList())
				list.forEach { addUser(it) }
				status.text = if (list.isEmpty()) "Use Quick Connect or type your username." else "Pick your profile, or use Quick Connect."
				(users.getChildAt(0) ?: findViewById<View>(R.id.btn_quick_connect)).requestFocus()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				status.text = "Can’t reach $base (${e.message ?: e.javaClass.simpleName}).\nCheck that the server is running, or change the address."
				findViewById<View>(R.id.btn_change_server).requestFocus()
			}
		}
	}

	private fun addUser(user: PublicUser) {
		val tile = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			isFocusable = true
			isClickable = true
			setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6))
		}
		val frame = FrameLayout(this).apply {
			background = ContextCompat.getDrawable(context, R.drawable.bg_person)
			foreground = ContextCompat.getDrawable(context, R.drawable.person_focus)
			isDuplicateParentStateEnabled = true
			clipToOutline = true
		}
		val letter = TextView(this).apply {
			text = user.name.take(1).uppercase()
			gravity = Gravity.CENTER
			typeface = Ui.display(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
			setTextColor(ContextCompat.getColor(context, R.color.lumen_accent))
		}
		frame.addView(letter, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
		val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
		frame.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
		Images.load(image, api.userImageUrl(base, user))
		tile.addView(frame, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)))
		tile.addView(TextView(this).apply {
			text = user.name
			gravity = Gravity.CENTER
			typeface = Ui.medium(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			setTextColor(ContextCompat.getColorStateList(context, R.color.row_text))
			isDuplicateParentStateEnabled = true
			setPadding(0, Ui.dp(context, 8), 0, 0)
		})
		Ui.focusScale(tile, 1.08f)
		tile.setOnClickListener {
			if (user.hasPassword) showPassword(user.name) else signIn(user.name, "")
		}
		users.addView(tile)
	}

	private fun showPassword(name: String?) {
		job?.cancel()
		code.visibility = View.GONE
		passwordGroup.visibility = View.VISIBLE
		username.setText(name.orEmpty())
		if (name == null) username.requestFocus() else password.requestFocus()
		status.text = if (name == null) "Type your Jellyfin username and password." else "Password for $name"
	}

	private fun signIn(name: String = username.text.toString().trim(), pass: String = password.text.toString()) {
		if (name.isEmpty()) {
			status.text = "Enter your username."
			return
		}
		job?.cancel()
		status.text = "Signing in…"
		job = lifecycleScope.launch {
			try {
				onSignedIn(api.authenticateByName(base, name, pass))
			} catch (e: CancellationException) {
				throw e
			} catch (e: ApiException) {
				status.text = if (e.code == 401) "Wrong username or password." else "Sign-in failed: ${e.message}"
			} catch (e: Exception) {
				status.text = "Can’t reach the server (${e.message ?: e.javaClass.simpleName})."
			}
		}
	}

	private fun startQuickConnect() {
		job?.cancel()
		passwordGroup.visibility = View.GONE
		status.text = "Starting Quick Connect…"
		job = lifecycleScope.launch {
			try {
				val (secret, value) = api.quickConnectInitiate(base)
				code.text = value
				code.visibility = View.VISIBLE
				status.text = "On your phone or computer open Jellyfin → your profile → Quick Connect, and enter this code."
				var authorized = false
				while (isActive && !authorized) {
					delay(3_000L)
					authorized = api.quickConnectAuthorized(base, secret)
				}
				onSignedIn(api.authenticateWithQuickConnect(base, secret))
			} catch (e: CancellationException) {
				throw e
			} catch (e: ApiException) {
				code.visibility = View.GONE
				status.text = if (e.code == 401 || e.code == 403) {
					"Quick Connect is turned off on this server (Dashboard → General). Pick a user or type your username."
				} else "Quick Connect failed: ${e.message}"
			} catch (e: Exception) {
				code.visibility = View.GONE
				status.text = "Can’t reach the server (${e.message ?: e.javaClass.simpleName})."
			}
		}
	}

	private fun onSignedIn(session: Session) {
		prefs.serverUrl = base
		prefs.serverName = serverName
		prefs.accessToken = session.token
		prefs.userId = session.userId
		startActivity(Intent(this, MainActivity::class.java))
		finish()
	}
}
