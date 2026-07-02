package no.ambulanse.odysseus

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import no.ambulanse.odysseus.databinding.ActivityMainBinding

/**
 * MainActivity
 * ---------------------------------------------------------------------
 * Full-screen host for the Odysseus web app. Beyond a plain WebView it
 * is tuned for running the service's built-in **web terminal** on a
 * phone, which normally fails because a soft keyboard cannot produce
 * Ctrl / Tab / Esc / arrow keys. To fix that it adds:
 *
 *   * a hardened WebView (WebSockets, clipboard bridge, granted web
 *     permissions, autoplay for the terminal bell),
 *   * a scrollable key bar that injects real KeyboardEvents into the
 *     terminal (see TerminalKeys),
 *   * a "show keyboard" action and adjustResize so the keyboard never
 *     covers the terminal,
 *   * pull-to-refresh that only triggers at the very top, so swiping in
 *     the scrollback scrolls instead of reloading (a reload would kill
 *     the shell session).
 *
 * It still supports the optional login (HTTP Basic Auth), a branded
 * error page, back-navigation and rotation.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsHelper

    // HTTP Basic Auth credentials (empty when login is disabled).
    private var authUser: String = ""
    private var authPass: String = ""

    // Settings currently reflected on screen, so onResume() can detect
    // changes made in the Settings screen.
    private var appliedUrl: String? = null
    private var appliedUseLogin: Boolean? = null

    // Sticky modifier state for the terminal key bar.
    private var ctrlActive = false
    private var altActive = false
    private var ctrlButton: Button? = null
    private var altButton: Button? = null

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            authUser = result.data?.getStringExtra(LoginActivity.EXTRA_USERNAME) ?: ""
            authPass = result.data?.getStringExtra(LoginActivity.EXTRA_PASSWORD) ?: ""
            loadOdysseus()
        } else {
            finish() // backed out of login -> close app
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsHelper(this)

        setSupportActionBar(binding.toolbar)
        configureWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        buildKeyBar()
        applyTerminalPrefs()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
            appliedUrl = prefs.url
            appliedUseLogin = prefs.useLogin
        } else {
            startUpFlow()
        }
    }

    // ---- Startup / URL loading --------------------------------------

    private fun startUpFlow() {
        appliedUrl = prefs.url
        appliedUseLogin = prefs.useLogin
        if (prefs.useLogin) {
            if (prefs.rememberMe && prefs.hasSavedCredentials()) {
                authUser = prefs.username
                authPass = prefs.password
                loadOdysseus()
            } else {
                loginLauncher.launch(LoginActivity.intent(this))
            }
        } else {
            loadOdysseus()
        }
    }

    private fun loadOdysseus() {
        val url = prefs.url
        val header = if (authUser.isNotEmpty()) {
            mapOf("Authorization" to basicAuth(authUser, authPass))
        } else {
            emptyMap()
        }
        binding.webView.loadUrl(url, header)
    }

    private fun basicAuth(user: String, pass: String): String {
        val raw = "$user:$pass"
        val encoded = android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        return "Basic $encoded"
    }

    // ---- WebView configuration --------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val web = binding.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Allow http/https to mix on the local network (and ws://).
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = USER_AGENT
            // A terminal is full-bleed; don't scale it to "overview".
            loadWithOverviewMode = false
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            // The terminal may open popups / play the bell sound.
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Let taps focus the page so the soft keyboard appears.
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        // Bridge so the web terminal's copy/paste reaches the Android
        // clipboard (navigator.clipboard is blocked over plain http).
        web.addJavascriptInterface(ClipboardBridge(), JS_BRIDGE)

        web.webViewClient = OdysseusWebViewClient()

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) android.view.View.VISIBLE
                    else android.view.View.GONE
            }

            // Grant web permissions the terminal may request (e.g.
            // clipboard). Safe here because we load our own service.
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    // ---- Pull-to-refresh (terminal-safe) ----------------------------

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.odysseus_primary)
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        // Only allow the refresh gesture when the WebView is scrolled to
        // the very top; otherwise a swipe scrolls the terminal scrollback.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.webView.canScrollVertically(-1)
        }
    }

    // ---- Back navigation --------------------------------------------

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ---- Terminal key bar -------------------------------------------

    /** Build the row of special keys from TerminalKeys.BAR. */
    private fun buildKeyBar() {
        val bar = binding.keyBar
        bar.removeAllViews()
        ctrlButton = null
        altButton = null
        for (k in TerminalKeys.BAR) {
            val button = makeKeyButton(k)
            bar.addView(button)
            when (k.modifier) {
                KeyModifier.CTRL -> ctrlButton = button
                KeyModifier.ALT -> altButton = button
                KeyModifier.NONE -> Unit
            }
        }
        updateModifierVisuals()
    }

    private fun makeKeyButton(k: TermKey): Button {
        val button = Button(this)
        button.text = k.label
        button.isAllCaps = false
        button.setTextColor(Color.WHITE)
        button.setPadding(dp(12), 0, dp(12), 0)
        button.minWidth = dp(44)
        button.minimumWidth = dp(44)
        button.background = keyBackground(active = false)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
        ).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
        button.layoutParams = lp
        button.setOnClickListener { onKeyPressed(k) }
        return button
    }

    private fun onKeyPressed(k: TermKey) {
        when (k.modifier) {
            KeyModifier.CTRL -> { ctrlActive = !ctrlActive; updateModifierVisuals() }
            KeyModifier.ALT -> { altActive = !altActive; updateModifierVisuals() }
            KeyModifier.NONE -> {
                val js = TerminalKeys.jsCall(k, ctrlActive, altActive)
                binding.webView.evaluateJavascript(js, null)
                // Modifiers act on the next key only, then release.
                if (ctrlActive || altActive) {
                    ctrlActive = false
                    altActive = false
                    updateModifierVisuals()
                }
            }
        }
    }

    private fun updateModifierVisuals() {
        ctrlButton?.background = keyBackground(active = ctrlActive)
        altButton?.background = keyBackground(active = altActive)
    }

    private fun keyBackground(active: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (active) 0xFF2A6FB0.toInt() else 0xFF14304F.toInt())
        }

    /** Show/hide the key bar and enable/disable pull-to-refresh. */
    private fun applyTerminalPrefs() {
        binding.keyBarScroll.visibility =
            if (prefs.showKeyBar) android.view.View.VISIBLE else android.view.View.GONE
        binding.swipeRefresh.isEnabled = prefs.pullToRefresh
    }

    private fun toggleKeyboard() {
        binding.webView.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.toggleSoftInput(
            InputMethodManager.SHOW_FORCED,
            InputMethodManager.HIDE_IMPLICIT_ONLY
        )
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    // ---- Toolbar menu -----------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_keys)?.isChecked = prefs.showKeyBar
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_keyboard -> { toggleKeyboard(); true }
            R.id.action_keys -> {
                prefs.showKeyBar = !prefs.showKeyBar
                applyTerminalPrefs()
                invalidateOptionsMenu()
                true
            }
            R.id.action_reload -> { binding.webView.reload(); true }
            R.id.action_settings -> { startActivity(SettingsActivity.intent(this)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---- Lifecycle ---------------------------------------------------

    override fun onResume() {
        super.onResume()
        // Re-apply terminal options (they may have changed in Settings).
        applyTerminalPrefs()
        val changed = appliedUrl != null &&
            (appliedUrl != prefs.url || appliedUseLogin != prefs.useLogin)
        if (changed) startUpFlow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    // ---- Clipboard bridge exposed to JavaScript ---------------------

    /**
     * Exposed to the page as `OdysseusAndroid`. Over plain http the
     * standard navigator.clipboard API is unavailable (not a secure
     * context), so the injected shim routes copy/paste through here.
     */
    private inner class ClipboardBridge {
        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("odysseus", text))
            }
        }

        @JavascriptInterface
        fun paste(): String = try {
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip
            if (clip == null || clip.itemCount == 0) ""
            else clip.getItemAt(0).coerceToText(this@MainActivity).toString()
        } catch (e: Exception) {
            "" // clipboard read can fail off the main thread; best-effort
        }
    }

    // ---- WebViewClient ----------------------------------------------

    private inner class OdysseusWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            view.loadUrl(request.url.toString())
            return true
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView, handler: HttpAuthHandler, host: String, realm: String
        ) {
            if (authUser.isNotEmpty()) handler.proceed(authUser, authPass)
            else handler.cancel()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            binding.swipeRefresh.isRefreshing = false
            // Install the key-injection helper and clipboard shim.
            view?.evaluateJavascript(TerminalKeys.BOOTSTRAP_JS, null)
            view?.evaluateJavascript(CLIPBOARD_SHIM_JS, null)
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) {
                binding.swipeRefresh.isRefreshing = false
                showErrorPage(view, prefs.url)
            }
        }
    }

    private fun showErrorPage(view: WebView, failedUrl: String) {
        val html = """
            <!DOCTYPE html>
            <html><head><meta name="viewport"
              content="width=device-width, initial-scale=1">
            <style>
              body{font-family:sans-serif;background:#fff;color:#003366;
                   text-align:center;padding:48px 24px;}
              h1{font-size:22px;margin-bottom:8px;}
              p{color:#555;font-size:15px;}
              code{background:#eee;padding:2px 6px;border-radius:4px;}
              button{margin-top:24px;background:#003366;color:#fff;
                     border:none;padding:12px 24px;border-radius:8px;
                     font-size:16px;}
            </style></head>
            <body>
              <h1>${getString(R.string.error_title)}</h1>
              <p>Could not connect to<br><code>$failedUrl</code></p>
              <p>Check that the Odysseus service is running and that
                 you are on the same network (Tailscale).</p>
              <button onclick="window.location.href='$failedUrl'">Retry</button>
            </body></html>
        """.trimIndent()
        view.loadDataWithBaseURL(failedUrl, html, "text/html", "UTF-8", failedUrl)
    }

    companion object {
        private const val USER_AGENT = "OdysseusApp/1.0 (Android; Mobile)"
        private const val JS_BRIDGE = "OdysseusAndroid"

        /** Route navigator.clipboard through the Android bridge. */
        private const val CLIPBOARD_SHIM_JS = """
            (function(){
              try {
                if (!window.OdysseusAndroid) return;
                var c = navigator.clipboard || {};
                try { navigator.clipboard = c; } catch(_) {}
                try { c.writeText = function(t){ OdysseusAndroid.copy(String(t)); return Promise.resolve(); }; } catch(_) {}
                try { c.readText  = function(){ return Promise.resolve(OdysseusAndroid.paste()); }; } catch(_) {}
              } catch(e) {}
            })();
        """
    }
}
