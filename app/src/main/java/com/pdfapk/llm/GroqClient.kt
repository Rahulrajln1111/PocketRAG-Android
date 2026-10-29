package com.pdfapk.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Lightweight HTTP client for the Groq Cloud API (OpenAI-compatible).
 *
 * Supports streaming responses via SSE for real-time token output.
 *
 * Models available (as of June 2026):
 * - llama-3.3-70b-versatile            (best quality, recommended)
 * - llama-3.1-8b-instant               (fast, lightweight)
 * - meta-llama/llama-4-scout-17b-16e-instruct (new Llama 4)
 * - qwen/qwen3-32b                     (great for multilingual)
 */
class GroqClient(private val apiKey: String) {

    companion object {
        private const val TAG = "GroqClient"
        private const val BASE_URL = "https://api.groq.com/openai/v1"
        private const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
        private const val TIMEOUT_SECONDS = 120L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    data class ChatConfig(
        val model: String = DEFAULT_MODEL,
        val temperature: Double = 0.7,
        val maxTokens: Int = 4096,
        val stream: Boolean = true
    )

    /**
     * Generate a response via Groq API with streaming support.
     *
     * @param systemPrompt The system instruction
     * @param userPrompt The user's request / context
     * @param config Model configuration
     * @param onPartial Called with each incremental token
     * @return Result with the full response text on success
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        config: ChatConfig = ChatConfig(),
        onPartial: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = buildJsonPayload(systemPrompt, userPrompt, config)
            val requestBody = jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$BASE_URL/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            Log.i(TAG, "Sending request to Groq (model=${config.model}, stream=${config.stream})")

            if (config.stream) {
                handleStreamingResponse(client, request, onPartial)
            } else {
                handleNonStreamingResponse(client, request)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq API call failed", e)
            Result.failure(e)
        }
    }

    private fun buildJsonPayload(
        systemPrompt: String,
        userPrompt: String,
        config: ChatConfig
    ): JSONObject {
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userPrompt)
        })

        return JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("stream", config.stream)
        }
    }

    /**
     * Handle Server-Sent Events (SSE) streaming response.
     */
    private fun handleStreamingResponse(
        client: OkHttpClient,
        request: Request,
        onPartial: (String) -> Unit
    ): Result<String> {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "Groq API error: ${response.code} $errorBody")
            return Result.failure(Exception("HTTP ${response.code}: $errorBody"))
        }

        val body = response.body ?: return Result.failure(Exception("Empty response body"))
        val reader = BufferedReader(InputStreamReader(body.byteStream()))
        val fullText = StringBuilder()

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val currentLine = line ?: continue

            // SSE format: "data: {...}"
            if (!currentLine.startsWith("data: ")) continue

            val jsonStr = currentLine.removePrefix("data: ").trim()

            // End of stream signal
            if (jsonStr == "[DONE]") break

            try {
                val json = JSONObject(jsonStr)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                    val content = delta?.optString("content", "")
                    if (!content.isNullOrEmpty()) {
                        fullText.append(content)
                        onPartial(fullText.toString())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing SSE chunk: ${e.message}")
            }
        }

        reader.close()
        Log.i(TAG, "Groq response complete: ${fullText.length} chars")
        return Result.success(fullText.toString())
    }

    /**
     * Handle non-streaming response (full response at once).
     */
    private fun handleNonStreamingResponse(
        client: OkHttpClient,
        request: Request
    ): Result<String> {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            return Result.failure(Exception("HTTP ${response.code}: $errorBody"))
        }

        val jsonStr = response.body?.string() ?: ""
        val json = JSONObject(jsonStr)
        val choices = json.optJSONArray("choices")

        if (choices != null && choices.length() > 0) {
            val message = choices.getJSONObject(0)
                .optJSONObject("message")
            val content = message?.optString("content", "")
            if (!content.isNullOrEmpty()) {
                return Result.success(content)
            }
        }

        return Result.success("")
    }

    /**
     * Validate that the API key works by making a lightweight models list request.
     */
    suspend fun validateApiKey(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
