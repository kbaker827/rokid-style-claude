package com.rokid.style.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Streams responses from the Claude Messages API using SSE (Server-Sent Events).
 *
 * Mirrors ClaudeAPIClient.swift:
 *  - endpoint   : https://api.anthropic.com/v1/messages
 *  - auth header: x-api-key
 *  - streaming  : parses "data:" lines, extracts delta.text from
 *                 content_block_delta events
 *
 * [streamResponse] returns a cold [Flow<String>] that emits successive text
 * fragments as they arrive.  Collect it on whatever dispatcher you like;
 * the flow itself switches to [Dispatchers.IO] internally.
 */
class ClaudeApiClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Build and send the streaming request.
     *
     * @param apiKey        Anthropic API key
     * @param modelId       Claude model identifier (e.g. "claude-haiku-4-5")
     * @param systemPrompt  System-level instruction
     * @param messages      Full conversation history + new user turn,
     *                      each entry is {"role":"user"|"assistant","content":"…"}
     * @param maxTokens     Token budget for the response
     * @return              Flow that emits text fragments; throws on HTTP errors
     */
    fun streamResponse(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        messages: List<Map<String, String>>,
        maxTokens: Int
    ): Flow<String> = flow {

        // Build JSON body
        val messagesArray = JSONArray()
        for (msg in messages) {
            messagesArray.put(JSONObject(msg))
        }
        val body = JSONObject().apply {
            put("model",      modelId)
            put("max_tokens", maxTokens)
            put("system",     systemPrompt)
            put("messages",   messagesArray)
            put("stream",     true)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key",         apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type",      "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw ClaudeApiException(response.code, errorBody)
        }

        val responseBody = response.body
            ?: throw ClaudeApiException(0, "Empty response body")

        // Parse SSE stream line by line
        BufferedReader(InputStreamReader(responseBody.byteStream())).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()

                // SSE lines prefixed with "data: "
                if (!trimmed.startsWith("data:")) continue

                val data = trimmed.removePrefix("data:").trim()

                // Stream terminator
                if (data == "[DONE]") break

                // Parse the JSON event
                try {
                    val event = JSONObject(data)
                    val type = event.optString("type")

                    // We only care about content delta events
                    if (type == "content_block_delta") {
                        val delta = event.optJSONObject("delta")
                        if (delta != null && delta.optString("type") == "text_delta") {
                            val text = delta.optString("text", "")
                            if (text.isNotEmpty()) {
                                emit(text)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Malformed JSON line — skip silently
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** Thrown when the API returns a non-2xx HTTP status. */
class ClaudeApiException(val statusCode: Int, message: String) : Exception(message)
