package com.rokid.style.claude

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SpeechManager"

/** How long (ms) of silence triggers an automatic send. Mirrors iOS 1.8 s. */
private const val SILENCE_TIMEOUT_MS = 1800L

/**
 * Wraps Android's [SpeechRecognizer] with a 1.8-second silence timer.
 *
 * Mirrors SpeechManager.swift:
 *  - [startListening]  : begins a recognition session
 *  - [stopListening]   : cancels the current session
 *  - [onTranscript]    : called with the final transcript once silence fires
 *  - [onStateChanged]  : notifies the UI of the current [State]
 */
class SpeechManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    enum class State { IDLE, LISTENING, PROCESSING }

    var onTranscript:   ((String) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)?  = null
    var onError:        ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var silenceJob: Job?              = null
    private var currentPartial: String        = ""
    private var state: State                  = State.IDLE
        set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun startListening() {
        if (state == State.LISTENING) return
        currentPartial = ""
        createRecognizer()
        recognizer?.startListening(buildIntent())
        state = State.LISTENING
        Log.d(TAG, "startListening")
    }

    fun stopListening() {
        cancelSilenceTimer()
        recognizer?.stopListening()
        recognizer?.cancel()
        destroyRecognizer()
        state = State.IDLE
        Log.d(TAG, "stopListening")
    }

    fun release() {
        stopListening()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun createRecognizer() {
        destroyRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Speech recognition is not available on this device")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Give the recognizer plenty of time; silence timer is our own mechanism
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
    }

    // ---- Silence timer -------------------------------------------------------

    private fun resetSilenceTimer() {
        cancelSilenceTimer()
        silenceJob = scope.launch(Dispatchers.Main) {
            delay(SILENCE_TIMEOUT_MS)
            fireSilenceTimeout()
        }
    }

    private fun cancelSilenceTimer() {
        silenceJob?.cancel()
        silenceJob = null
    }

    private fun fireSilenceTimeout() {
        Log.d(TAG, "Silence timeout — transcript='$currentPartial'")
        val transcript = currentPartial.trim()
        stopListening()
        if (transcript.isNotEmpty()) {
            state = State.PROCESSING
            onTranscript?.invoke(transcript)
        } else {
            // Nothing heard — go back to listening automatically
            startListening()
        }
    }

    // ---- RecognitionListener -------------------------------------------------

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            cancelSilenceTimer()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Not used; could animate a volume indicator here
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech — starting silence timer")
            resetSilenceTimer()
        }

        override fun onError(error: Int) {
            val msg = speechErrorString(error)
            Log.w(TAG, "onError: $msg ($error)")

            cancelSilenceTimer()

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // No speech heard — restart silently
                    destroyRecognizer()
                    if (state == State.LISTENING) startListening()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Wait a moment then retry
                    destroyRecognizer()
                    scope.launch(Dispatchers.Main) {
                        delay(500)
                        if (state == State.LISTENING) startListening()
                    }
                }
                else -> {
                    destroyRecognizer()
                    state = State.IDLE
                    onError?.invoke(msg)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            cancelSilenceTimer()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull() ?: currentPartial
            Log.d(TAG, "onResults: '$best'")
            currentPartial = best

            // onEndOfSpeech should already have started the silence timer,
            // but if results arrive before timeout we fire immediately.
            val transcript = best.trim()
            destroyRecognizer()
            if (transcript.isNotEmpty()) {
                state = State.PROCESSING
                onTranscript?.invoke(transcript)
            } else {
                if (state == State.LISTENING) startListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            if (partial.isNotEmpty()) {
                currentPartial = partial
                Log.v(TAG, "Partial: '$partial'")
                // Reset silence timer whenever new speech is detected
                resetSilenceTimer()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---- Error string helper -------------------------------------------------

    private fun speechErrorString(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO              -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT             -> "Client side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK            -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH           -> "No match found"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER             -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "No speech input"
        else                                      -> "Unknown error ($code)"
    }
}
