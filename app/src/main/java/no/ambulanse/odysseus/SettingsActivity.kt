package no.ambulanse.odysseus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import no.ambulanse.odysseus.databinding.ActivitySettingsBinding

/**
 * SettingsActivity
 * ---------------------------------------------------------------------
 * Lets the user view and change the Odysseus URL, toggle whether a
 * login screen is required, enable agent mode, and enable shell access
 * (which requires agent mode). On Save it validates the URL, stores the
 * values, and returns to MainActivity (which reloads the WebView).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsHelper(this)

        // The toolbar's "X" navigation icon closes the screen.
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Populate the screen with the currently saved values.
        binding.currentUrlText.text =
            getString(R.string.settings_current_url, prefs.url)
        binding.urlInput.setText(prefs.url)
        binding.useLoginSwitch.isChecked = prefs.useLogin
        binding.showKeysSwitch.isChecked = prefs.showKeyBar
        binding.pullRefreshSwitch.isChecked = prefs.pullToRefresh
        binding.useAgentSwitch.isChecked = prefs.useAgent
        binding.useShellAccessSwitch.isChecked = prefs.useShellAccess

        // Shell access requires agent mode – disable when agent is off
        updateShellAccessState()

        // When agent mode is toggled, update shell access availability
        binding.useAgentSwitch.setOnCheckedChangeListener { _, _ ->
            updateShellAccessState()
        }

        binding.saveButton.setOnClickListener { save() }
    }

    private fun updateShellAccessState() {
        val agentOn = binding.useAgentSwitch.isChecked
        binding.useShellAccessSwitch.isEnabled = agentOn
        if (!agentOn) {
            binding.useShellAccessSwitch.isChecked = false
        }
        binding.shellAccessDescText.alpha = if (agentOn) 1.0f else 0.4f
    }

    private fun save() {
        val newUrl = binding.urlInput.text?.toString()?.trim().orEmpty()

        // Validate: must be a non-empty http:// or https:// address.
        if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
            binding.urlInputLayout.error = getString(R.string.settings_invalid_url)
            return
        }
        binding.urlInputLayout.error = null

        // Save the values.
        prefs.url = newUrl
        prefs.useLogin = binding.useLoginSwitch.isChecked
        prefs.showKeyBar = binding.showKeysSwitch.isChecked
        prefs.pullToRefresh = binding.pullRefreshSwitch.isChecked
        prefs.useAgent = binding.useAgentSwitch.isChecked
        prefs.useShellAccess = binding.useShellAccessSwitch.isChecked

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()

        // Just return to the MainActivity underneath. It detects the
        // changed settings in onResume() and reloads (re-running the
        // login check + page load with the new URL).
        finish()
    }

    companion object {
        /** Convenience factory for starting this screen. */
        fun intent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}
