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

        val parts = JSONArray()
            .put(JSONObject().put("inline_data",
                JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()

        if (code !in 200..299) {
            val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
            val hint = when (code) {
                400 -> "API 키가 올바른지 확인하세요."
                403 -> "API 키 권한을 확인하세요."
                404 -> "모델 이름이 없거나 종료되었습니다. 앱 첫 화면에서 모델 이름을 바꿔보세요."
                429 -> "사용 한도를 넘었습니다. 잠시 후 다시 시도하세요."
                else -> ""
            }
            return "오류 $code\n$hint\n$msg"
        }

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
