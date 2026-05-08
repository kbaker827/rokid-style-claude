package com.rokid.style.claude

import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rokid.style.claude.databinding.ActivitySettingsBinding

/**
 * Settings screen — mirrors the iOS settings stored in UserDefaults.
 *
 * Fields:
 *  - API Key       (EditText, password-style masking)
 *  - Model         (Spinner: haiku / sonnet / opus)
 *  - System Prompt (EditText, multiline)
 *  - Max Tokens    (EditText, numeric)
 *  - Max History   (EditText, numeric)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.title_settings)
        }

        setupModelSpinner()
        loadCurrentValues()
        setupSaveButton()
        setupResetPromptButton()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ---- Setup helpers -----------------------------------------------------

    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Prefs.AVAILABLE_MODELS
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerModel.adapter = adapter
    }

    private fun loadCurrentValues() {
        binding.etApiKey.setText(Prefs.apiKey(this))
        binding.etSystemPrompt.setText(Prefs.systemPrompt(this))
        binding.etMaxTokens.setText(Prefs.maxTokens(this).toString())
        binding.etMaxHistory.setText(Prefs.maxHistory(this).toString())

        val modelIndex = Prefs.AVAILABLE_MODELS.indexOf(Prefs.modelId(this))
        binding.spinnerModel.setSelection(if (modelIndex >= 0) modelIndex else 0)
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            if (saveSettings()) {
                Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun setupResetPromptButton() {
        binding.btnResetPrompt.setOnClickListener {
            binding.etSystemPrompt.setText(Prefs.DEFAULT_SYSTEM_PROMPT)
        }
    }

    // ---- Validation & save -------------------------------------------------

    private fun saveSettings(): Boolean {
        val apiKey = binding.etApiKey.text.toString().trim()
        val systemPrompt = binding.etSystemPrompt.text.toString().trim()
        val maxTokensStr = binding.etMaxTokens.text.toString().trim()
        val maxHistoryStr = binding.etMaxHistory.text.toString().trim()
        val modelId = Prefs.AVAILABLE_MODELS.getOrElse(binding.spinnerModel.selectedItemPosition) {
            Prefs.DEFAULT_MODEL_ID
        }

        val maxTokens = maxTokensStr.toIntOrNull()
        if (maxTokens == null || maxTokens < 1 || maxTokens > 8192) {
            binding.etMaxTokens.error = "Enter a number between 1 and 8192"
            return false
        }

        val maxHistory = maxHistoryStr.toIntOrNull()
        if (maxHistory == null || maxHistory < 1 || maxHistory > 50) {
            binding.etMaxHistory.error = "Enter a number between 1 and 50"
            return false
        }

        Prefs.setApiKey(this, apiKey)
        Prefs.setModelId(this, modelId)
        Prefs.setSystemPrompt(this, systemPrompt.ifEmpty { Prefs.DEFAULT_SYSTEM_PROMPT })
        Prefs.setMaxTokens(this, maxTokens)
        Prefs.setMaxHistory(this, maxHistory)

        return true
    }
}
