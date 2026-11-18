package com.example.macrolock

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    private const val GROQ_VISION_DEFAULT = "meta-llama/llama-4-scout-17b-16e-instruct"

    // 키가 gsk_ 로 시작하면 Groq, 아니면 기존 Gemini 방식으로 호출
    fun ask(bmp: Bitmap, prompt: String, key: String, model: String, search: Boolean): String =
        if (key.startsWith("gsk_")) askGroq(bmp, prompt, key, model)
        else askGemini(bmp, prompt, key, model, search)

    private fun toBase64(bmp: Bitmap): String {
        val maxSide = 1280
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to text
    }

    private fun errorText(code: Int, text: String): String {
        val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
        val hint = when (code) {
            400 -> "API 키·모델 이름이 올바른지, 이 모델이 이미지를 지원하는지 확인하세요."
            401, 403 -> "API 키가 올바른지 확인하세요."
            404 -> "모델 이름이 없거나 종료되었습니다. 앱 첫 화면에서 모델 이름을 바꿔보세요."
            413 -> "이미지가 너무 큽니다."
            429 -> "사용 한도를 넘었습니다. 잠시 후 다시 시도하세요."
            503 -> "서버가 바쁩니다. 잠시 후 다시 시도하세요."
            else -> ""
        }
        return "오류 $code\n$hint\n$msg"
    }

    private fun askGroq(bmp: Bitmap, prompt: String, key: String, modelIn: String): String {
        // 예전 Gemini 모델 이름이 남아 있으면 Groq 비전 모델로 대체
        val model = if (modelIn.isBlank() || modelIn.startsWith("gemini")) GROQ_VISION_DEFAULT else modelIn
        val b64 = toBase64(bmp)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)))

        val (code, text) = post(
            "https://api.groq.com/openai/v1/chat/completions",
            mapOf("Authorization" to "Bearer $key"),
            body
        )
        if (code !in 200..299) return errorText(code, text)

        return try {
            val s = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            clean(s.trim()).ifEmpty { "응답이 비어 있습니다" }
        } catch (e: Exception) {
            "응답을 읽지 못했습니다"
        }
    }

    private fun askGemini(bmp: Bitmap, prompt: String, key: String, model: String, search: Boolean): String {
        val b64 = toBase64(bmp)
        val parts = JSONArray()
            .put(JSONObject().put("inline_data",
                JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val (code, text) = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to key),
            body
        )
        if (code !in 200..299) return errorText(code, text)

        return try {
            val ps = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
            clean(sb.toString().trim()).ifEmpty { "응답이 비어 있습니다" }
        } catch (e: Exception) {
            "응답을 읽지 못했습니다 (안전 필터로 막혔을 수 있습니다)"
        }
    }

    private fun clean(s: String) = s
        .replace("**", "")
        .replace("### ", "")
        .replace("## ", "")
        .replace(Regex("(?m)^(\\s*)\\* "), "\$1• ")
}
