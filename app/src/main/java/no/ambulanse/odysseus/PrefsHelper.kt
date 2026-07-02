package no.ambulanse.odysseus

import android.content.Context
import android.util.Base64

/**
 * PrefsHelper
 * ---------------------------------------------------------------------
 * A small wrapper around SharedPreferences (Android's simple key/value
 * storage). It keeps ALL the app's saved settings in one place:
 *
 *   * the Odysseus URL,
 *   * whether the login screen is required,
 *   * the saved username / password (lightly obfuscated),
 *   * the "remember me" choice.
 *
 * It also builds the HTTP Basic Authentication header used by the
 * WebView when login is enabled.
 *
 * NOTE ON SECURITY: For this prototype the password is only lightly
 * obfuscated (XOR + Base64), NOT truly encrypted. That hides it from a
 * casual glance at the prefs file but is not strong security. For a
 * production app, use androidx.security:security-crypto (EncryptedShared
 * Preferences) or the Android Keystore instead.
 */
class PrefsHelper(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Odysseus URL ------------------------------------------------

    /** The saved URL, falling back to the default LAN address. */
    var url: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    // ---- Login toggle ------------------------------------------------

    /** Whether the login screen must be shown before the WebView. */
    var useLogin: Boolean
        get() = prefs.getBoolean(KEY_USE_LOGIN, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_LOGIN, value).apply()

    // ---- "Remember me" ----------------------------------------------

    var rememberMe: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    // ---- Terminal-friendly options ----------------------------------

    /**
     * Whether the terminal key bar (Esc, Tab, Ctrl, arrows, …) is shown.
     * Defaults ON, since the app's main purpose is running shell/agent
     * tasks in the Odysseus web terminal.
     */
    var showKeyBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_KEYS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_KEYS, value).apply()

    /**
     * Whether pull-to-refresh is active. Defaults OFF because a reload
     * would kill a live terminal session; a swipe down in the scrollback
     * must scroll, not reload.
     */
    var pullToRefresh: Boolean
        get() = prefs.getBoolean(KEY_PULL_REFRESH, false)
        set(value) = prefs.edit().putBoolean(KEY_PULL_REFRESH, value).apply()

    // ---- Credentials (lightly obfuscated) ---------------------------

    var username: String
        get() = deobfuscate(prefs.getString(KEY_USER, "") ?: "")
        set(value) = prefs.edit().putString(KEY_USER, obfuscate(value)).apply()

    var password: String
        get() = deobfuscate(prefs.getString(KEY_PASS, "") ?: "")
        set(value) = prefs.edit().putString(KEY_PASS, obfuscate(value)).apply()

    /** True if we have stored credentials to reuse. */
    fun hasSavedCredentials(): Boolean =
        username.isNotEmpty() && password.isNotEmpty()

    /** Forget the stored credentials (e.g. when "remember me" is off). */
    fun clearCredentials() {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    /**
     * Builds an HTTP Basic Authentication header value of the form
     * "Basic base64(username:password)". Returns null if either field
     * is empty.
     */
    fun basicAuthHeader(): String? {
        if (username.isEmpty() || password.isEmpty()) return null
        val raw = "$username:$password"
        val encoded = Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        return "Basic $encoded"
    }

    // ---- Simple obfuscation (XOR with a fixed key, then Base64) ------

    private fun obfuscate(plain: String): String {
        if (plain.isEmpty()) return ""
        val xored = xor(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(xored, Base64.NO_WRAP)
    }

    private fun deobfuscate(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            String(xor(bytes), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            "" // corrupted / not in the expected format
        }
    }

    private fun xor(data: ByteArray): ByteArray {
        val key = OBFUSCATION_KEY.toByteArray(Charsets.UTF_8)
        return ByteArray(data.size) { i ->
            (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
    }

    companion object {
        const val DEFAULT_URL = "http://192.168.68.84:7000"

        private const val PREFS_NAME = "odysseus_prefs"
        private const val KEY_URL = "url"
        private const val KEY_USE_LOGIN = "use_login"
        private const val KEY_REMEMBER = "remember_me"
        private const val KEY_SHOW_KEYS = "show_keys"
        private const val KEY_PULL_REFRESH = "pull_refresh"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"

        // Fixed key for the lightweight XOR obfuscation. Not a secret
        // in any real sense — see the security note above.
        private const val OBFUSCATION_KEY = "0dysseus-pr0t0type-key"
    }
}
