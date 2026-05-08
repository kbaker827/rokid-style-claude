package com.rokid.style.claude

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "TtsManager"

/**
 * Thin wrapper around [TextToSpeech].
 *
 * Mirrors AVSpeechSynthesizer from the iOS app:
 *  - [speak]  : queues text and speaks it; suspends until the utterance completes
 *  - [stop]   : interrupts current speech
 *  - [release]: frees TTS engine resources
 */
class TtsManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    /** Callback invoked when TTS engine initialisation completes (success or fail). */
    var onReady: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.w(TAG, "TTS: US English not supported, falling back to default locale")
                    tts?.language = Locale.getDefault()
                }
                isReady = true
                Log.d(TAG, "TTS engine ready")
                onReady?.invoke()
            } else {
                Log.e(TAG, "TTS initialisation failed with status $status")
                onError?.invoke("TTS engine failed to initialise (status=$status)")
            }
        }
    }

    /**
     * Speak [text] and suspend until the utterance finishes (or errors).
     * Safe to call from a coroutine; does nothing if the TTS engine is not ready.
     */
    suspend fun speak(text: String) {
        val engine = tts ?: return
        if (!isReady) return
        if (text.isBlank()) return

        // Split long text into chunks ≤ 4000 chars to avoid Android TTS limits
        val chunks = splitIntoChunks(text, maxLen = 3800)

        for (chunk in chunks) {
            speakChunk(engine, chunk)
        }
    }

    private suspend fun speakChunk(engine: TextToSpeech, chunk: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val utteranceId = UUID.randomUUID().toString()

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) cont.resume(Unit)
                }
                @Deprecated("Deprecated in API 21+")
                override fun onError(id: String?) {
                    if (id == utteranceId) cont.resume(Unit)  // resume so callers aren't stuck
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) cont.resume(Unit)
                }
            })

            val result = engine.speak(
                chunk,
                TextToSpeech.QUEUE_ADD,
                null,
                utteranceId
            )
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "engine.speak() returned ERROR")
                cont.resume(Unit)
            }

            cont.invokeOnCancellation {
                engine.stop()
            }
        }

    /** Stop any currently playing or queued speech immediately. */
    fun stop() {
        tts?.stop()
    }

    /** Release the TTS engine. Call from onDestroy. */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    val ready: Boolean get() = isReady

    // Split at sentence boundaries when possible, otherwise hard-cut at maxLen
    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxLen) {
            // Try to cut at last sentence-ending punctuation within maxLen
            val sub = remaining.substring(0, maxLen)
            val cutIdx = maxOf(
                sub.lastIndexOf('. '),
                sub.lastIndexOf('! '),
                sub.lastIndexOf('? ')
            )
            val end = if (cutIdx > maxLen / 2) cutIdx + 1 else maxLen
            result.add(remaining.substring(0, end).trim())
            remaining = remaining.substring(end).trim()
        }
        if (remaining.isNotEmpty()) result.add(remaining)
        return result
    }
}
