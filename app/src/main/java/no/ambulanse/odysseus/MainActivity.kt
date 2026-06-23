package no.ambulanse.odysseus

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import no.ambulanse.odysseus.databinding.ActivityMainBinding

/**
 * MainActivity
 * ---------------------------------------------------------------------
 * The single full-screen screen that hosts the WebView. It:
 *   1. Configures the WebView (JavaScript, DOM storage, mixed content,
 *      custom User-Agent).
 *   2. Optionally shows the login screen first (if the setting is on).
 *   3. Sends HTTP Basic Auth credentials to the Odysseus server.
 *   4. Supports pull-to-refresh and hardware/gesture back navigation.
 *   5. Shows a friendly error page if the server cannot be reached.
 *   6. Supports Agent Mode (?agent=true) with optional Shell Access
 *      (&shell=true) for executing commands on the server.
 */
class MainActivity : AppCompatActivity() {

    // ViewBinding gives type-safe access to the views in
    // activity_main.xml (binding.webView, binding.swipeRefresh, ...).
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsHelper

    // Credentials used to answer HTTP Basic Auth challenges (401).
    // Empty when login is disabled.
    private var authUser: String = ""
    private var authPass: String = ""

    // Track whether the internal chat scroll container (#chat-history)
    // is at the very top. Updated by JS via OdysseusBridge.
    private var chatIsAtTop = true

    // The settings that are currently reflected in the loaded page, so
    // onResume() can tell when the user changed them in Settings.
    private var appliedUrl: String? = null
    private var appliedUseLogin: Boolean? = null
    private var appliedUseAgent: Boolean? = null
    private var appliedUseShellAccess: Boolean? = null

    // Launcher for the optional LoginActivity. When it returns OK we
    // pull the entered credentials and load the page with them.
    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            authUser = result.data?.getStringExtra(LoginActivity.EXTRA_USERNAME) ?: ""
            authPass = result.data?.getStringExtra(LoginActivity.EXTRA_PASSWORD) ?: ""
            loadOdysseus()
        } else {
            // User backed out of login without signing in -> close app.
            finish()
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

        // Restore WebView state across rotation instead of reloading.
        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
            // Record what is on screen so a later Settings change is
            // detected correctly in onResume().
            appliedUrl = prefs.url
            appliedUseLogin = prefs.useLogin
            appliedUseAgent = prefs.useAgent
            appliedUseShellAccess = prefs.useShellAccess
        } else {
            startUpFlow()
        }
    }

    /** Decide whether to show login first, or load the page directly. */
    private fun startUpFlow() {
        appliedUrl = prefs.url
        appliedUseLogin = prefs.useLogin
        appliedUseAgent = prefs.useAgent
        appliedUseShellAccess = prefs.useShellAccess
        if (prefs.useLogin) {
            // If "remember me" saved valid credentials, reuse them.
            if (prefs.rememberMe && prefs.hasSavedCredentials()) {
                authUser = prefs.username
                authPass = prefs.password
                loadOdysseus()
            } else {
                loginLauncher.launch(LoginActivity.intent(this))
            }
        } else {
            // Login disabled: load the saved URL directly.
            loadOdysseus()
        }
    }

    /** Apply all WebView settings needed for the Odysseus web app. */
    private fun configureWebView() {
        val web = binding.webView
        web.settings.apply {
            javaScriptEnabled = true                 // chat UI needs JS

        // Bridge so the chat page can tell us when the user has scrolled
        // the internal #chat-history container.
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun setChatScrollTop(scrollTop: Int) {
                chatIsAtTop = scrollTop <= 0
            }
        }, "OdysseusBridge")
            domStorageEnabled = true                 // localStorage/session
            // Allow a HTTPS page to load HTTP sub-resources and vice
            // versa (useful on a mixed local network).
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Identify this app to the server.
            userAgentString = USER_AGENT
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
        }

        web.webViewClient = OdysseusWebViewClient()

        // Update the progress bar as pages load.
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) android.view.View.VISIBLE
                    else android.view.View.GONE
            }
        }
    }

    /** Pull down on the page to reload it – only when at the top. */
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.odysseus_primary)
        // Kun tillat pull-to-refresh når WebView er scrollet helt til toppen
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            !chatIsAtTop
        }
        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }
    }

    /**
     * Back button / gesture: if the WebView has history, go back one
     * page; otherwise let the system close the app.
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed() // default = exit
                }
            }
        })
    }

    /** Load the Odysseus URL with auth headers, using buildUrl() which
     *  appends ?agent=true and &shell=true as configured. */
    private fun loadOdysseus() {
        val url = prefs.buildUrl()
        val header = if (authUser.isNotEmpty()) {
            // Send Basic Auth on the very first request as well, so
            // servers that expect the header up-front are satisfied.
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

    // ---- Toolbar menu (Reload / Agent Mode / Shell Access / Settings)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_agent_mode)?.isChecked = prefs.useAgent
        menu.findItem(R.id.action_shell_access)?.isChecked = prefs.useShellAccess
        // Shell access is only meaningful with agent mode
        menu.findItem(R.id.action_shell_access)?.isEnabled = prefs.useAgent
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                binding.webView.reload(); true
            }
            R.id.action_agent_mode -> {
                prefs.useAgent = !prefs.useAgent
                item.isChecked = prefs.useAgent
                // Turn off shell access if agent mode is turned off
                if (!prefs.useAgent) {
                    prefs.useShellAccess = false
                }
                invalidateOptionsMenu()
                loadOdysseus()
                true
            }
            R.id.action_shell_access -> {
                if (prefs.useAgent) {
                    prefs.useShellAccess = !prefs.useShellAccess
                    item.isChecked = prefs.useShellAccess
                    loadOdysseus()
                }
                true
            }
            R.id.action_settings -> {
                startActivity(SettingsActivity.intent(this)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Re-run the startup flow if the URL, login, agent, or shell
     *  access setting changed. */
    override fun onResume() {
        super.onResume()
        val changed = appliedUrl != null &&
            (appliedUrl != prefs.url ||
             appliedUseLogin != prefs.useLogin ||
             appliedUseAgent != prefs.useAgent ||
             appliedUseShellAccess != prefs.useShellAccess)
        if (changed) {
            startUpFlow()
        }
    }

    // ---- Save/restore WebView across rotation -----------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    /**
     * Custom WebViewClient that:
     *   * answers HTTP Basic Auth challenges with the stored creds,
     *   * stops the pull-to-refresh spinner when a page finishes,
     *   * shows a built-in error page if the main page fails to load.
     */
    private inner class OdysseusWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            // Keep all navigation inside the WebView.
            view.loadUrl(request.url.toString())
            return true
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView, handler: HttpAuthHandler,
            host: String, realm: String
        ) {
            if (authUser.isNotEmpty()) {
                handler.proceed(authUser, authPass)
            } else {
                handler.cancel()
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            binding.swipeRefresh.isRefreshing = false
        }

        override fun onReceivedError(
            view: WebView?, request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            // Show an embedded error page when the main page fails
            // (e.g. server unreachable). Sub-resource errors are
            // silently ignored so they don't replace the page.
            if (request?.isForMainFrame == true) {
                val desc = error?.description?.toString()
                    ?: getString(R.string.error_title)
                val html = ERROR_HTML
                    .replace("{{TITLE}}", getString(R.string.error_title))
                    .replace("{{DESC}}", desc)
                view?.loadDataWithBaseURL(null, html,
                    "text/html", "UTF-8", null)
            }
        }
    }

    companion object {
        /** Custom User-Agent sent with every request. */
        const val USER_AGENT = "Odysseus-Android-App/1.0"

        /** Minimal error page shown in the WebView on failure. */
        const val ERROR_HTML = """
<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0,user-scalable=no'>
<style>body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;
height:100vh;margin:0;padding:24px;background:#f5f5f5;color:#333;text-align:center}
h2{color:#c62828}h2:before{content:'⚠️ '}p{max-width:320px;line-height:1.5;color:#555}
small{color:#999;margin-top:12px}</style></head>
<body><h2>{{TITLE}}</h2><p>{{DESC}}</p>
<small>Odysseus Android App — pull down to retry</small></body></html>"""
    }
}
