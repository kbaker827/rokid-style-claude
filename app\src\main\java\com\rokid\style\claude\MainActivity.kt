package com.rokid.style.claude

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rokid.style.claude.databinding.ActivityMainBinding

/**
 * Single-screen host activity.
 *
 * Because Rokid glasses have no display this screen is informational only —
 * the real work happens in [VoiceAssistantService].  The UI shows:
 *   - Current assistant state (Listening / Thinking / Speaking)
 *   - Streamed partial response text
 *   - Status / error messages
 *   - A button to open Settings
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var service: VoiceAssistantService? = null
    private var bound = false

    // ---------- Permission launchers ----------------------------------------

    private val audioPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                checkNotificationPermissionThenStart()
            } else {
                Toast.makeText(this,
                    "RECORD_AUDIO permission is required for voice recognition.",
                    Toast.LENGTH_LONG).show()
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Proceed regardless — notification is a nice-to-have
            startAssistantService()
        }

    // ---------- ServiceConnection -------------------------------------------

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as VoiceAssistantService.LocalBinder).getService().also { svc ->
                bound = true
                attachServiceCallbacks(svc)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    // ---------- Activity lifecycle ------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnClearHistory.setOnClickListener {
            service?.clearHistory()
            Toast.makeText(this, "Conversation history cleared.", Toast.LENGTH_SHORT).show()
        }

        // Warn if API key is missing
        if (Prefs.apiKey(this).isBlank()) {
            showStatus("⚠ No API key set — tap Settings to add your Claude API key.")
        }

        requestAudioPermissionThenStart()
    }

    override fun onResume() {
        super.onResume()
        // Re-bind in case service is already running
        val intent = Intent(this, VoiceAssistantService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        if (bound) {
            detachServiceCallbacks()
            unbindService(connection)
            bound = false
        }
    }

    // ---------- Permissions & service start ---------------------------------

    private fun requestAudioPermissionThenStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> {
                checkNotificationPermissionThenStart()
            }
            else -> audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> startAssistantService()
                else -> notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startAssistantService()
        }
    }

    private fun startAssistantService() {
        val intent = Intent(this, VoiceAssistantService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    // ---------- Service callbacks -------------------------------------------

    private fun attachServiceCallbacks(svc: VoiceAssistantService) {
        svc.onStateChanged = { state ->
            runOnUiThread { updateStateUi(state) }
        }
        svc.onPartialText = { text ->
            runOnUiThread { binding.tvResponseText.text = text }
        }
        svc.onStatusMessage = { msg ->
            runOnUiThread { showStatus(msg) }
        }
    }

    private fun detachServiceCallbacks() {
        service?.onStateChanged  = null
        service?.onPartialText   = null
        service?.onStatusMessage = null
    }

    // ---------- UI helpers --------------------------------------------------

    private fun updateStateUi(state: VoiceAssistantService.AssistantState) {
        val (label, pulse) = when (state) {
            VoiceAssistantService.AssistantState.IDLE      -> "Idle"      to false
            VoiceAssistantService.AssistantState.LISTENING -> "Listening…" to true
            VoiceAssistantService.AssistantState.THINKING  -> "Thinking…"  to false
            VoiceAssistantService.AssistantState.SPEAKING  -> "Speaking…"  to false
        }
        binding.tvState.text = label
        binding.viewPulse.visibility = if (pulse) View.VISIBLE else View.INVISIBLE
    }

    private fun showStatus(msg: String) {
        binding.tvStatus.text = msg
        binding.tvStatus.visibility = View.VISIBLE
    }
}

// Extension so MainActivity can clear the history without reaching into internals
fun VoiceAssistantService.clearHistory() {
    // Exposed via a package-private field — see VoiceAssistantService
    this.javaClass.getDeclaredField("history").also {
        it.isAccessible = true
        (it.get(this) as ConversationHistory).clear()
    }
}
