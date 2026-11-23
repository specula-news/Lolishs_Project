package app.lumen.player.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import app.lumen.player.R
import app.lumen.player.util.SecureStore
import org.json.JSONObject

/**
 * Li'Server (torrent search & downloads) inside the app. The remote drives an on-screen pointer,
 * cookies are kept so you stay signed in, and a saved (encrypted) login can sign in automatically.
 */
class TorrentActivity : BaseActivity() {
	private lateinit var web: WebView
	private lateinit var cursor: CursorLayout
	private lateinit var progress: ProgressBar
	private lateinit var hint: View
	private lateinit var hintText: TextView
	private lateinit var error: View
	private lateinit var errorText: TextView
	private val handler = Handler(Looper.getMainLooper())
	private var autoLoginAttempts = 0
	private var askedToSave = false
	private val homeHost: String? get() = Uri.parse(prefs.liServerUrl).host

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_torrent)
		web = findViewById(R.id.web)
		cursor = findViewById(R.id.cursor)
		progress = findViewById(R.id.progress)
		hint = findViewById(R.id.hint)
		hintText = findViewById(R.id.hint_text)
		error = findViewById(R.id.error)
		errorText = findViewById(R.id.error_text)

		CookieManager.getInstance().setAcceptCookie(true)
		CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

		web.settings.apply {
			javaScriptEnabled = true
			domStorageEnabled = true
			loadWithOverviewMode = true
			useWideViewPort = true
			mediaPlaybackRequiresUserGesture = false
			builtInZoomControls = false
			displayZoomControls = false
			setSupportMultipleWindows(false)
			userAgentString = userAgentString + " LolishTV"
		}
		web.setBackgroundColor(getColor(R.color.lumen_bg))
		web.addJavascriptInterface(Bridge(), "LolishBridge")
		web.webViewClient = Client()
		web.webChromeClient = object : WebChromeClient() {
			override fun onProgressChanged(view: WebView, newProgress: Int) {
				progress.progress = newProgress
				progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
			}
		}
		web.setDownloadListener { _, _, _, _, _ ->
			showHint("Downloads go to your server — check Li’Server’s Downloads page", 4_000L)
		}

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				if (web.canGoBack()) web.goBack() else finish()
			}
		})

		updateHint()
		cursor.requestFocus()
		web.loadUrl(prefs.liServerUrl)
	}

	override fun onPause() {
		super.onPause()
		CookieManager.getInstance().flush()
	}

	override fun onDestroy() {
		handler.removeCallbacksAndMessages(null)
		web.destroy()
		super.onDestroy()
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
			cursor.cursorEnabled = !cursor.cursorEnabled
			if (!cursor.cursorEnabled) web.requestFocus() else cursor.requestFocus()
			updateHint()
			return true
		}
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
			web.reload()
			return true
		}
		return super.dispatchKeyEvent(event)
	}

	private fun updateHint() {
		showHint(
			if (cursor.cursorEnabled) "Pointer: arrows move · OK clicks · push an edge to scroll · Menu = focus mode"
			else "Focus mode: arrows jump between links · Menu = pointer mode",
			6_000L,
		)
	}

	private fun showHint(text: String, durationMs: Long) {
		hintText.text = text
		hint.animate().cancel()
		hint.alpha = 1f
		hint.visibility = View.VISIBLE
		handler.removeCallbacksAndMessages(HINT_TOKEN)
		handler.postAtTime({ hint.animate().alpha(0f).setDuration(400L).start() }, HINT_TOKEN, android.os.SystemClock.uptimeMillis() + durationMs)
	}

	private fun applyZoom() {
		val zoom = prefs.liServerZoom.coerceIn(50, 150) / 100.0
		web.evaluateJavascript("document.documentElement.style.zoom='$zoom';", null)
	}

	private fun installLoginHelpers() {
		web.evaluateJavascript(CAPTURE_SCRIPT, null)
		val user = prefs.liServerUser
		val pass = SecureStore.decrypt(prefs.liServerPasswordEncrypted)
		if (!prefs.liServerAutoLogin || user.isNullOrBlank() || pass.isNullOrBlank() || autoLoginAttempts >= 2) return
		val script = AUTO_LOGIN_SCRIPT
			.replace("__USER__", JSONObject.quote(user))
			.replace("__PASS__", JSONObject.quote(pass))
		web.evaluateJavascript(script) { result ->
			if (result?.contains("submitted") == true) {
				autoLoginAttempts++
				showHint("Signing in to Li’Server…", 2_500L)
			}
		}
	}

	private fun showError(message: String) {
		error.visibility = View.VISIBLE
		errorText.text = message
		val actions = findViewById<LinearLayout>(R.id.error_actions)
		actions.removeAllViews()
		val retry = Pills.create(actions, "Retry", R.drawable.ic_refresh)
		retry.setOnClickListener {
			error.visibility = View.GONE
			cursor.cursorEnabled = true
			cursor.requestFocus()
			web.reload()
		}
		Pills.create(actions, "Close").setOnClickListener { finish() }
		cursor.cursorEnabled = false
		retry.requestFocus()
	}

	private inner class Client : WebViewClient() {
		override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
			error.visibility = View.GONE
		}

		override fun onPageFinished(view: WebView, url: String?) {
			applyZoom()
			if (Uri.parse(url ?: "").host == homeHost) installLoginHelpers()
			CookieManager.getInstance().flush()
		}

		override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
			// Single-page apps change routes without reloading; keep zoom and helpers in place.
			applyZoom()
			if (Uri.parse(url ?: "").host == homeHost) web.evaluateJavascript(CAPTURE_SCRIPT, null)
		}

		override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
			if (request.isForMainFrame) showError("${error.description} (${request.url.host})")
		}

		@SuppressLint("WebViewClientOnReceivedSslError")
		override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
			val host = Uri.parse(error.url).host
			when {
				host != null && host in prefs.trustedCertHosts -> handler.proceed()
				host != null && host == homeHost -> {
					AlertDialog.Builder(this@TorrentActivity)
						.setTitle("Trust Li’Server’s certificate?")
						.setMessage("The certificate for $host isn’t trusted by Android (often a self-signed certificate on a home server). Only continue if this is your server.")
						.setPositiveButton("Trust this server") { _, _ ->
							prefs.trustedCertHosts = prefs.trustedCertHosts + host
							handler.proceed()
						}
						.setNegativeButton("Cancel") { _, _ -> handler.cancel() }
						.setOnCancelListener { handler.cancel() }
						.show()
				}
				else -> handler.cancel()
			}
		}
	}

	/** Called from the page when you submit a login form, so we can offer to remember it. */
	inner class Bridge {
		@JavascriptInterface
		fun onCredentials(user: String, pass: String) {
			runOnUiThread {
				if (askedToSave || pass.isBlank()) return@runOnUiThread
				if (Uri.parse(web.url ?: "").host != homeHost) return@runOnUiThread
				val saved = SecureStore.decrypt(prefs.liServerPasswordEncrypted)
				if (user == prefs.liServerUser && pass == saved) return@runOnUiThread
				askedToSave = true
				AlertDialog.Builder(this@TorrentActivity)
					.setTitle("Save Li’Server login?")
					.setMessage("Lolish’s Project can sign you in automatically next time. The password is encrypted on this device.")
					.setPositiveButton("Save") { _, _ ->
						prefs.liServerUser = user
						prefs.liServerPasswordEncrypted = SecureStore.encrypt(pass)
						prefs.liServerAutoLogin = true
						showHint("Login saved", 2_000L)
					}
					.setNegativeButton("Not now", null)
					.show()
			}
		}
	}

	private companion object {
		val HINT_TOKEN = Any()

		/** Watches login forms and reports the entered username/password to the app. */
		const val CAPTURE_SCRIPT = """
(function(){
  if (window.__lolishCapture) return; window.__lolishCapture = true;
  function grab(){
    var pw = document.querySelector('input[type=password]');
    if (!pw || !pw.value) return;
    var inputs = [].slice.call(document.querySelectorAll('input')).filter(function(i){
      return ['password','hidden','checkbox','submit','button','radio'].indexOf(i.type) < 0; });
    var user = inputs.length ? inputs[0].value : '';
    try { LolishBridge.onCredentials(user, pw.value); } catch (e) {}
  }
  document.addEventListener('submit', grab, true);
  document.addEventListener('click', function(e){
    var t = e.target; if (t && t.closest && t.closest('button,input[type=submit]')) grab(); }, true);
  document.addEventListener('keydown', function(e){ if (e.key === 'Enter') grab(); }, true);
})();
"""

		/** Fills a login form (works with React/Vue inputs) and submits it. */
		const val AUTO_LOGIN_SCRIPT = """
(function(u, p){
  var pw = document.querySelector('input[type=password]');
  if (!pw) return 'nologin';
  var inputs = [].slice.call(document.querySelectorAll('input')).filter(function(i){
    return ['password','hidden','checkbox','submit','button','radio'].indexOf(i.type) < 0; });
  function setValue(el, v){
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(el, v);
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
  }
  if (inputs.length) setValue(inputs[0], u);
  setValue(pw, p);
  var remember = document.querySelector('input[type=checkbox]');
  if (remember && !remember.checked) remember.click();
  var form = pw.form;
  var button = (form && form.querySelector('button[type=submit],input[type=submit]')) ||
    document.querySelector('button[type=submit]') ||
    [].slice.call(document.querySelectorAll('button')).filter(function(b){ return /sign|log/i.test(b.textContent); })[0];
  setTimeout(function(){
    if (button) button.click();
    else if (form) { if (form.requestSubmit) form.requestSubmit(); else form.submit(); }
  }, 200);
  return 'submitted';
})(__USER__, __PASS__);
"""
	}
}
