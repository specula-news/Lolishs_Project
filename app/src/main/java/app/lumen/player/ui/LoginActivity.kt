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
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.lumen.player.R
import app.lumen.player.jellyfin.ApiException
import app.lumen.player.jellyfin.JellyfinApi
import app.lumen.player.jellyfin.SignedIn
import app.lumen.player.util.Account
import app.lumen.player.util.Images
import app.lumen.player.util.Prefs
import app.lumen.player.util.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

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
			ServerPicker.show(this, base) { url ->
				prefs.serverUrl = url
				loadServer()
			}
		}
		// "Add server": scan a QR code and type the address on the phone (or use the remote).
		(findViewById<View>(R.id.btn_change_server).parent as? LinearLayout)?.let { column ->
			val add = Pills.create(column, "Add server")
			(add.layoutParams as? LinearLayout.LayoutParams)?.topMargin = Ui.dp(this, 10)
			add.setOnClickListener {
				ServerPicker.add(this) { url ->
					prefs.serverUrl = url
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
		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				// Back to "Who's watching?" when other profiles are saved.
				if (prefs.accounts.isNotEmpty()) startActivity(Intent(this@LoginActivity, ProfilesActivity::class.java))
				finish()
			}
		})
		loadServer()
	}

	private fun loadServer() {
		serverLine.text = "●  $base"
		status.text = "Connecting…"
		users.removeAllViews()
		// Users already signed in on this TV: one click, no password (works even before the server answers).
		val saved = prefs.accounts.filter { JellyfinApi.normalizeBaseUrl(it.serverUrl) == base }
		saved.forEach { account ->
			addTile(account.userName, account.imageUrl(), "Signed in") {
				prefs.activate(account)
				openMain()
			}
		}
		users.getChildAt(0)?.requestFocus()
		lifecycleScope.launch {
			try {
				serverName = api.publicServerName(base)
				serverLine.text = "●  $serverName  ·  $base"
				val list = runCatching { api.publicUsers(base) }.getOrDefault(emptyList())
				val savedIds = saved.map { it.userId }.toSet()
				val shownNames = saved.map { it.userName.lowercase() }.toMutableSet()
				list.filter { it.id !in savedIds }.forEach { user ->
					shownNames += user.name.lowercase()
					addTile(user.name, api.userImageUrl(base, user), null) {
						if (user.hasPassword) showPassword(user.name) else signIn(user.name, "")
					}
				}
				// Users who signed in here before but were removed or hidden from the server's login screen.
				prefs.recentUsers(base).filter { it.lowercase() !in shownNames }.forEach { name ->
					addTile(name, null, "Password") { showPassword(name) }
				}
				status.text = when {
					users.childCount == 0 -> "Use Quick Connect or type your username."
					saved.isNotEmpty() -> "Pick your profile — signed-in users open right away."
					else -> "Pick your profile, or use Quick Connect."
				}
				if (currentFocus == null || !isInside(currentFocus, users)) {
					(users.getChildAt(0) ?: findViewById<View>(R.id.btn_quick_connect)).requestFocus()
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// Maybe only the address was typed: look for the server on https / http and the usual ports.
				val found = runCatching { api.findServer(base) }.getOrNull()
				if (found != null && !found.equals(base, ignoreCase = true)) {
					prefs.serverUrl = found
					prefs.addServer(found)
					loadServer()
					return@launch
				}
				status.text = "Can’t reach $base (${e.message ?: e.javaClass.simpleName}).\nCheck that the server is running, or change the address."
				if (users.childCount == 0) findViewById<View>(R.id.btn_change_server).requestFocus()
			}
		}
	}

	private fun isInside(view: View?, parent: View): Boolean {
		var current: Any? = view
		while (current is View) {
			if (current === parent) return true
			current = current.parent
		}
		return false
	}

	private fun addTile(name: String, imageUrl: String?, badge: String?, onClick: () -> Unit) {
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
			text = name.take(1).uppercase()
			gravity = Gravity.CENTER
			typeface = Ui.display(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
			setTextColor(Ui.color(context, R.attr.lumenAccent))
		}
		frame.addView(letter, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
		if (imageUrl != null) {
			val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
			frame.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
			Images.load(image, imageUrl)
		}
		tile.addView(frame, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)))
		tile.addView(TextView(this).apply {
			text = name
			gravity = Gravity.CENTER
			maxLines = 1
			typeface = Ui.medium(context)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			setTextColor(ContextCompat.getColorStateList(context, R.color.row_text))
			isDuplicateParentStateEnabled = true
			setPadding(0, Ui.dp(context, 8), 0, 0)
		})
		if (badge != null) {
			tile.addView(TextView(this).apply {
				text = badge
				gravity = Gravity.CENTER
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
				setTextColor(Ui.color(context, if (badge == "Signed in") R.attr.lumenGreen else R.attr.lumenText3))
			})
		}
		Ui.focusScale(tile, 1.08f)
		tile.setOnClickListener { onClick() }
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
				onSignedIn(api.authenticateByName(base, name, pass, prefs.deviceIdForUser(base, name)), pass)
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
				// Quick Connect doesn't know the user yet, so each attempt gets its own device id.
				val deviceId = UUID.randomUUID().toString().replace("-", "")
				val (secret, value) = api.quickConnectInitiate(base, deviceId)
				code.text = value
				code.visibility = View.VISIBLE
				status.text = "On your phone or computer open Jellyfin → your profile → Quick Connect, and enter this code."
				var authorized = false
				while (isActive && !authorized) {
					delay(3_000L)
					authorized = api.quickConnectAuthorized(base, secret, deviceId)
				}
				onSignedIn(api.authenticateWithQuickConnect(base, secret, deviceId), null)
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

	/** [password] is kept encrypted so the app can sign in again by itself if the server drops the session. */
	private fun onSignedIn(result: SignedIn, password: String?) {
		val previous = prefs.accounts.firstOrNull { it.userId == result.session.userId && JellyfinApi.normalizeBaseUrl(it.serverUrl) == base }
		val encrypted = password?.let { app.lumen.player.util.SecureStore.encrypt(it) } ?: previous?.encryptedPassword
		prefs.rememberUser(base, result.userName)
		prefs.saveAccount(
			Account(
				serverUrl = base,
				serverName = serverName,
				userId = result.session.userId,
				userName = result.userName,
				token = result.session.token,
				deviceId = result.deviceId,
				imageTag = result.imageTag,
				encryptedPassword = encrypted,
			)
		)
		openMain()
	}

	private fun openMain() {
		startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
		finish()
	}
}
