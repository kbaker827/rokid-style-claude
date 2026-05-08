package com.rokid.style.claude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val TAG           = "VoiceAssistantService"
private const val CHANNEL_ID    = "rokid_claude_channel"
private const val NOTIFICATION_ID = 1

/**
 * Foreground service that owns the main assistant loop.
 *
 * Lifecycle:
 *   startForeground  → create SpeechManager + TtsManager + ClaudeApiClient
 *   onTranscript     → stream Claude response → accumulate → TTS speak → restart listening
 *   onDestroy        → release all resources
 *
 * Mirrors the coordination in ClaudeViewModel.swift.
 */
class VoiceAssistantService : Service() {

    // ---------- public state (observed by MainActivity via Binder) ----------

    enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

    var onStateChanged:  ((AssistantState) -> Unit)? = null
    var onPartialText:   ((String) -> Unit)?         = null   // streamed Claude text
    var onStatusMessage: ((String) -> Unit)?         = null   // short human-readable status

    // -----------------------------------------------------------------------

    private val serviceScope  = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val claudeClient  = ClaudeApiClient()
    private lateinit var speechManager: SpeechManager
    private lateinit var ttsManager:    TtsManager
    private lateinit var history:       ConversationHistory

    private var claudeJob: Job? = null
    private var assistantState = AssistantState.IDLE
        set(v) { field = v; onStateChanged?.invoke(v) }

    // ---- Public API for bound clients --------------------------------------

    /** Clear the in-memory conversation history. */
    fun clearHistory() = history.clear()

    // ---- Binder ------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ---- Service lifecycle -------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        history = ConversationHistory(Prefs.maxHistory(this))

        ttsManager = TtsManager(this).apply {
            onReady = {
                Log.d(TAG, "TTS ready — starting speech recognition")
                startListening()
            }
            onError = { msg ->
                postStatus("TTS error: $msg")
                Log.e(TAG, "TTS error: $msg")
            }
        }

        speechManager = SpeechManager(this, serviceScope).apply {
            onTranscript = { transcript ->
                Log.d(TAG, "Transcript: $transcript")
                handleTranscript(transcript)
            }
            onStateChanged = { speechState ->
                when (speechState) {
                    SpeechManager.State.IDLE       -> { /* handled by assistant state */ }
                    SpeechManager.State.LISTENING  -> assistantState = AssistantState.LISTENING
                    SpeechManager.State.PROCESSING -> assistantState = AssistantState.THINKING
                }
            }
            onError = { msg ->
                postStatus("Speech error: $msg")
                Log.e(TAG, "Speech error: $msg")
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Claude listening…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        claudeJob?.cancel()
        speechManager.release()
        ttsManager.release()
        serviceScope.cancel()
    }

    // ---- Core loop ---------------------------------------------------------

    private fun startListening() {
        assistantState = AssistantState.LISTENING
        updateNotification("Claude listening…")
        speechManager.startListening()
    }

    private fun handleTranscript(transcript: String) {
        // Cancel any in-flight Claude request
        claudeJob?.cancel()

        assistantState = AssistantState.THINKING
        updateNotification("Thinking…")

        val apiKey = Prefs.apiKey(this)
        if (apiKey.isBlank()) {
            ttsManager.stop()
            serviceScope.launch {
                ttsManager.speak("Please set your Claude API key in the app settings.")
                startListening()
            }
            return
        }

        // Reload settings each turn so live changes take effect immediately
        val modelId      = Prefs.modelId(this)
        val systemPrompt = Prefs.systemPrompt(this)
        val maxTokens    = Prefs.maxTokens(this)
        history.updateMaxHistory(Prefs.maxHistory(this))

        // Build messages: history + new user turn
        val messages = history.toApiMessages().toMutableList() +
            listOf(mapOf("role" to "user", "content" to transcript))

        val responseBuilder = StringBuilder()

        claudeJob = serviceScope.launch {
            try {
                claudeClient
                    .streamResponse(apiKey, modelId, systemPrompt, messages, maxTokens)
                    .catch { e ->
                        Log.e(TAG, "Stream error: ${e.message}")
                        postStatus("Error: ${e.message}")
                        val errMsg = when (e) {
                            is ClaudeApiException ->
                                if (e.statusCode == 401)
                                    "Invalid API key. Please check your settings."
                                else
                                    "Claude API error ${e.statusCode}. Please try again."
                            else -> "Network error. Please check your connection."
                        }
                        speakAndReturnToListening(errMsg, transcript, "")
                    }
                    .collect { fragment ->
                        responseBuilder.append(fragment)
                        onPartialText?.invoke(responseBuilder.toString())
                    }

                val fullResponse = responseBuilder.toString()
                Log.d(TAG, "Full response: $fullResponse")

                if (fullResponse.isNotBlank()) {
                    history.addTurn(transcript, fullResponse)
                    speakAndReturnToListening(fullResponse, null, null)
                } else {
                    speakAndReturnToListening("I didn't get a response. Please try again.", transcript, "")
                }

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Unexpected error: ${e.message}")
                speakAndReturnToListening("Something went wrong. Please try again.", transcript, "")
            }
        }
    }

    /**
     * Speak [text], optionally record the turn in history, then return to listening.
     * If [userTurn] / [assistantTurn] are null the history is not modified.
     */
    private suspend fun speakAndReturnToListening(
        text: String,
        userTurn: String?,
        assistantTurn: String?
    ) {
        if (userTurn != null && assistantTurn != null) {
            history.addTurn(userTurn, assistantTurn)
        }
        assistantState = AssistantState.SPEAKING
        updateNotification("Speaking…")
        ttsManager.speak(text)
        startListening()
    }

    // ---- Notification helpers ----------------------------------------------

    private fun buildNotification(contentText: String): Notification {
        ensureNotificationChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceAssistantService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Rokid Claude Assistant")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Claude Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Rokid Claude voice assistant status" }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postStatus(msg: String) {
        onStatusMessage?.invoke(msg)
    }

    companion object {
        const val ACTION_STOP = "com.rokid.style.claude.ACTION_STOP"
    }
}
