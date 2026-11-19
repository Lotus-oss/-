package com.example.macrolock

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    private const val SOLVE_PROMPT =
        "이미지에 있는 문제를 먼저 수식까지 포함해 글자 그대로 정확하게 옮겨 적어줘. " +
        "그다음 단계별로 차근차근 풀이하고, 계산이 끝나면 조건에 맞는지 한 번 검산해줘. " +
        "마지막 줄에 '정답: ...' 형태로 최종 답을 써줘. 객관식이면 보기 번호도 함께 써줘. " +
        "마크다운 서식 없이 일반 텍스트로 답해줘."

    fun ask(bmp: Bitmap, prompt: String, key: String, model: String, search: Boolean): String {
        val maxSide = 2048
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 92, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$b64"

        val textPrompt = if (search) prompt else SOLVE_PROMPT
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", textPrompt))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", dataUrl)))
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)))
        if (false) {
            body.put("reasoning_effort", "medium")
            body.put("max_completion_tokens", 5000)
        }

        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 90000
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
                413 -> "한 번에 보내는 토큰이 한도를 넘었습니다. 1분 뒤 다시 시도하세요."
                429 -> "사용 한도를 넘었습니다. 잠시 후 다시 시도하세요."
                else -> ""
            }
            return "오류 $code\n$hint\n$msg"
        }

        return try {
            val ch = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            val out = ch.getJSONObject("message").optString("content")
                .replace(Regex("(?s)<think>.*?</think>"), "")
                .trim()
            var res = clean(out)
            if (ch.optString("finish_reason") == "length") {
                res += if (res.isEmpty())
                    "생각이 길어져 답이 나오기 전에 잘렸습니다. 1분 뒤 다시 눌러보세요."
                else "\n\n(답변이 도중에 잘렸습니다)"
            }
            res.ifEmpty { "응답이 비어 있습니다" }
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
