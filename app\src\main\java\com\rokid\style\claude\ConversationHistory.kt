package com.rokid.style.claude

/**
 * Keeps a rolling window of conversation turns for multi-turn context.
 *
 * Each turn is a map matching the Claude API message format:
 *   { "role": "user"|"assistant", "content": "<text>" }
 *
 * When the list exceeds [maxHistory] pairs (user + assistant = 1 pair = 2 messages),
 * the oldest messages are dropped so the list stays within bounds.
 */
class ConversationHistory(private var maxHistory: Int = Prefs.DEFAULT_MAX_HISTORY) {

    private val messages: ArrayDeque<Map<String, String>> = ArrayDeque()

    /** Update the rolling-window size; trims immediately if needed. */
    fun updateMaxHistory(newMax: Int) {
        maxHistory = newMax
        trim()
    }

    /**
     * Append a user turn followed by the assistant reply.
     * If [assistantText] is blank the assistant message is still stored so the
     * API receives a properly alternating conversation.
     */
    fun addTurn(userText: String, assistantText: String) {
        messages.addLast(mapOf("role" to "user",      "content" to userText))
        messages.addLast(mapOf("role" to "assistant", "content" to assistantText))
        trim()
    }

    /**
     * Return a snapshot suitable for inclusion in the Claude API request body.
     * The list will contain at most [maxHistory] * 2 entries (maxHistory pairs).
     */
    fun toApiMessages(): List<Map<String, String>> = messages.toList()

    /** Remove all history. */
    fun clear() = messages.clear()

    /** Number of individual messages stored (pairs × 2). */
    val size: Int get() = messages.size

    // Drop oldest messages in multiples of 2 (keep turns whole)
    private fun trim() {
        val limit = maxHistory * 2   // each "history entry" = 1 user + 1 assistant
        while (messages.size > limit) {
            messages.removeFirst()   // remove user message
            if (messages.isNotEmpty()) messages.removeFirst()  // remove paired assistant message
        }
    }
}
