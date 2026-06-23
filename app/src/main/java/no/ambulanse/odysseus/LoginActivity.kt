package no.ambulanse.odysseus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import no.ambulanse.odysseus.databinding.ActivityLoginBinding

/**
 * LoginActivity
 * ---------------------------------------------------------------------
 * Optional sign-in screen, shown by MainActivity only when the
 * "Require login" setting is on. It collects a username and password
 * and a "Remember me" choice.
 *
 * On a successful login it:
 *   * optionally saves the credentials (if "Remember me" is ticked),
 *   * returns the credentials to MainActivity, which then sends them
 *     to the Odysseus server as HTTP Basic Auth.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: PrefsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsHelper(this)

        // Pre-fill the form if we previously remembered the user.
        if (prefs.rememberMe && prefs.hasSavedCredentials()) {
            binding.usernameInput.setText(prefs.username)
            binding.passwordInput.setText(prefs.password)
            binding.rememberMeCheck.isChecked = true
        }

        binding.loginButton.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val user = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val pass = binding.passwordInput.text?.toString().orEmpty()
        val remember = binding.rememberMeCheck.isChecked

        // Basic validation: both fields required. (There is no real
        // auth server to check against here; the credentials are simply
        // forwarded to Odysseus as Basic Auth.)
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.login_empty_fields, Toast.LENGTH_SHORT).show()
            return
        }

        // Persist or clear credentials based on the checkbox.
        prefs.rememberMe = remember
        if (remember) {
            prefs.username = user
            prefs.password = pass
        } else {
            prefs.clearCredentials()
        }

        // Hand the credentials back to MainActivity.
        val data = Intent().apply {
            putExtra(EXTRA_USERNAME, user)
            putExtra(EXTRA_PASSWORD, pass)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    companion object {
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"

        /** Convenience factory for starting this screen. */
        fun intent(context: Context): Intent =
            Intent(context, LoginActivity::class.java)
    }
}
