package com.example.macrolock

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    fun ask(bmp: Bitmap, prompt: String, key: String, model: String, search: Boolean): String {
        val maxSide = 1280
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$b64"

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", dataUrl)))
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)))

        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()

        if (code !in 200..299) {
            val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
            val hint = when (code) {
                401 -> "API 키를 확인하세요."
                400, 404 -> "모델 이름이 없거나 종료되었을 수 있습니다. 앱 첫 화면에서 모델 이름을 확인하세요."
                413 -> "이미지가 너무 큽니다."
                429 -> "사용 한도를 넘었습니다. 잠시 후 다시 시도하세요."
                else -> ""
            }
            return "오류 $code\n$hint\n$msg"
        }

        return try {
            val m = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val out = m.optString("content")
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .trim()
            clean(out).ifEmpty { "응답이 비어 있습니다" }
        } catch (e: Exception) {
            "응답을 읽지 못했습니다"
        }
    }

    private fun clean(s: String) = s
        .replace("**", "")
        .replace("### ", "")
        .replace("## ", "")
        .replace(Regex("(?m)^(\\s*)\\* "), "\$1• ")
}
