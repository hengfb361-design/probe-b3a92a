package com.example.aiphone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The Groq key lives in the phone, not in the APK source. */
object Prefs {
    private const val FILE = "ai_phone"
    private const val KEY = "groq_key"

    fun key(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun setKey(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }
}

/** Groq chat completions with model failover: 429 (rate limit), 413, 404
    (retired model) or 5xx moves on to the next model instead of failing. */
object GroqClient {
    private const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODELS_URL = "https://api.groq.com/openai/v1/models"
    private val PREFERRED = listOf("meta-llama/llama-4-scout-17b-16e-instruct", "openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b")
    private val VISION_MODELS = setOf("meta-llama/llama-4-scout-17b-16e-instruct", "qwen/qwen3.8-27b", "qwen/qwen3.6-27b")

    data class Reply(val ok: Boolean, val text: String, val model: String)

    private class Raw(val code: Int, val body: String)

    fun chat(context: Context, messages: List<Pair<String, String>>, log: (String) -> Unit, visionImage: String? = null): Reply {
        val key = Prefs.key(context)
        if (key.isEmpty()) {
            return Reply(false, "គ្មាន Groq API key — បញ្ចូលក្នុង app សិន (console.groq.com)", "")
        }
        val allModels = listModels(key)
        val models = if (visionImage != null) {
            val vision = allModels.filter { it.lowercase() in VISION_MODELS }
            if (vision.isNotEmpty()) vision else allModels
        } else allModels
        var last = "មិនអាចហៅ Groq បាន"
        for (index in models.indices) {
            val model = models[index]
            val raw = call(key, model, messages, visionImage)
            if (raw.code == 200) {
                val text = content(raw.body)
                if (text.isNotEmpty()) return Reply(true, text, model)
                last = "model ឆ្លើយទទេ"
            } else {
                last = errorOf(raw)
            }
            val next = models.getOrNull(index + 1)
            if (!retryable(raw.code) || next == null) break
            log("🔴 " + model + " (" + raw.code + ") → ប្តូរ " + next)
        }
        return Reply(false, last, "")
    }

    private fun retryable(code: Int): Boolean =
        code == 429 || code == 413 || code == 404 || code == 500 || code == 502 || code == 503

    private fun listModels(key: String): List<String> {
        val raw = request(MODELS_URL, key, null)
        if (raw.code != 200) return PREFERRED
        val ids = mutableListOf<String>()
        try {
            val data = JSONObject(raw.body).optJSONArray("data") ?: return PREFERRED
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id") ?: continue
                val low = id.lowercase()
                if (low.contains("whisper") || low.contains("tts") || low.contains("guard")) continue
                if (low.contains("embed") || low.contains("orpheus")) continue
                ids.add(id)
            }
        } catch (e: Exception) {
            return PREFERRED
        }
        if (ids.isEmpty()) return PREFERRED
        return ids.sortedWith(compareBy({ rank(it) }, { it })).take(4)
    }

    private fun rank(id: String): Int {
        val at = PREFERRED.indexOf(id)
        return if (at < 0) PREFERRED.size else at
    }

    private fun call(key: String, model: String, messages: List<Pair<String, String>>, visionImage: String? = null): Raw {
        val lastUser = messages.indexOfLast { it.first == "user" }
        val array = JSONArray()
        for ((i, pair) in messages.withIndex()) {
            if (i == lastUser && visionImage != null && pair.first == "user") {
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", pair.second))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + visionImage)))
                array.put(JSONObject().put("role", pair.first).put("content", content))
            } else {
                array.put(JSONObject().put("role", pair.first).put("content", pair.second))
            }
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", array)
            .put("temperature", 0.3)
            .put("max_tokens", 1200)
        val low = model.lowercase()
        if (low.contains("gpt-oss") || low.contains("qwen3")) {
            payload.put("reasoning_effort", "low")
        }
        return request(CHAT_URL, key, payload.toString())
    }

    private fun content(body: String): String = try {
        JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")?.trim() ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun errorOf(raw: Raw): String = try {
        JSONObject(raw.body).optJSONObject("error")?.optString("message") ?: ("HTTP " + raw.code)
    } catch (e: Exception) {
        "HTTP " + raw.code + " " + raw.body.take(120)
    }

    private fun request(url: String, key: String, payload: String?): Raw {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.requestMethod = if (payload == null) "GET" else "POST"
                conn.setRequestProperty("Authorization", "Bearer " + key)
                conn.setRequestProperty("Content-Type", "application/json")
                if (payload != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(payload.toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Raw(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Raw(0, e.message ?: "ភ្ជាប់មិនបាន")
        }
    }
}
