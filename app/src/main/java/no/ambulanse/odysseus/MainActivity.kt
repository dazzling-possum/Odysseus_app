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

    // The settings that are currently reflected in the loaded page, so
    // onResume() can tell when the user changed them in Settings.
    private var appliedUrl: String? = null
    private var appliedUseLogin: Boolean? = null

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
        } else {
            startUpFlow()
        }
    }

    /** Decide whether to show login first, or load the page directly. */
    private fun startUpFlow() {
        appliedUrl = prefs.url
        appliedUseLogin = prefs.useLogin
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

    /** Pull down on the page to reload it. */
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.odysseus_primary)
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

    /** Load the saved Odysseus URL, attaching auth headers if present. */
    private fun loadOdysseus() {
        val url = prefs.url
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

    // ---- Toolbar menu (Reload / Settings) ---------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                binding.webView.reload(); true
            }
            R.id.action_settings -> {
                startActivity(SettingsActivity.intent(this)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Re-run the startup flow if the URL or login setting changed. */
    override fun onResume() {
        super.onResume()
        val changed = appliedUrl != null &&
            (appliedUrl != prefs.url || appliedUseLogin != prefs.useLogin)
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
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String
        ) {
            // This is the proper way HTTP Basic Auth works in a WebView:
            // when the server responds with 401, supply the credentials.
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
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            // Only show the error page for the main page, not for a
            // failed image or script inside an otherwise-loaded page.
            if (request.isForMainFrame) {
                binding.swipeRefresh.isRefreshing = false
                showErrorPage(view, prefs.url)
            }
        }
    }

    /** Render a simple, branded error page directly in the WebView. */
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
    }
}
