package com.rokid.style.claude

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences.
 * All settings used by the app are accessed through this object.
 */
object Prefs {

    private const val PREF_FILE = "rokid_claude_prefs"

    // Keys
    private const val KEY_API_KEY       = "api_key"
    private const val KEY_MODEL_ID      = "model_id"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_MAX_TOKENS    = "max_tokens"
    private const val KEY_MAX_HISTORY   = "max_history"

    // Defaults
    const val DEFAULT_MODEL_ID      = "claude-haiku-4-5"
    const val DEFAULT_MAX_TOKENS    = 512
    const val DEFAULT_MAX_HISTORY   = 10
    val DEFAULT_SYSTEM_PROMPT =
        "You are a helpful AI assistant on Rokid AR glasses. " +
        "Keep responses concise — 1-3 sentences."

    /** Available Claude models shown in the settings spinner. */
    val AVAILABLE_MODELS = listOf(
        "claude-haiku-4-5",
        "claude-sonnet-4-5",
        "claude-opus-4-5"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // --------------- getters ---------------

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun modelId(context: Context): String =
        prefs(context).getString(KEY_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID

    fun systemPrompt(context: Context): String =
        prefs(context).getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT

    fun maxTokens(context: Context): Int =
        prefs(context).getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)

    fun maxHistory(context: Context): Int =
        prefs(context).getInt(KEY_MAX_HISTORY, DEFAULT_MAX_HISTORY)

    // --------------- setters ---------------

    fun setApiKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_API_KEY, value).apply()

    fun setModelId(context: Context, value: String) =
        prefs(context).edit().putString(KEY_MODEL_ID, value).apply()

    fun setSystemPrompt(context: Context, value: String) =
        prefs(context).edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    fun setMaxTokens(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_MAX_TOKENS, value).apply()

    fun setMaxHistory(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_MAX_HISTORY, value).apply()
}
