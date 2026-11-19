package com.example.macrolock

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    private val SOLVERS = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")

    private const val READ_PROMPT =
        "이미지에 있는 문제를 수식까지 포함해 글자 그대로 정확하게 옮겨 적어줘. " +
        "풀지는 말고 문제 문장, 조건, 보기만 적어줘. 서식 없이 일반 텍스트로."

    private const val SOLVE_PROMPT =
        "다음 수학 문제를 풀어줘. 핵심 풀이만 간결하게 최대 10줄로 쓰고, " +
        "마지막 줄에 '정답: ...' 형태로 답을 써줘. 객관식이면 보기 번호도 써줘. " +
        "서식 없이 일반 텍스트로.\n\n문제:\n"

    private class Out(val text: String, val finish: String, val tokens: Int)

    private fun post(body: JSONObject, key: String): Pair<Int, String> {
        val c = URL("https://api.groq.com/openai/v1/chat/completions")
            .openConnection() as HttpURLConnection
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
        return Pair(code, text)
    }

    private fun imageUrl(bmp: Bitmap): String {
        val maxSide = 2048
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun vision(url: String, text: String, key: String, model: String): Pair<Int, String> {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", url)))
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)))
        val lean = model.contains("qwen3")
        if (lean) body.put("reasoning_effort", "none")
        var r = post(body, key)
        if (r.first == 400 && lean && r.second.contains("reasoning")) {
            body.remove("reasoning_effort")
            r = post(body, key)
        }
        return r
    }

    private fun errMsg(code: Int, text: String): String {
        val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (_: Exception) { text.take(200) }
        val hint = when {
            code == 401 -> "API 키를 확인하세요."
            code == 400 || code == 404 -> "모델 이름이 없거나 종료되었을 수 있습니다. 앱 첫 화면에서 모델 이름을 확인하세요."
            code == 413 -> "한 번에 보내는 토큰이 한도를 넘었습니다. 1분 뒤 다시 시도하세요."
            code == 429 && (msg.contains("per day") || msg.contains("TPD")) ->
                "오늘 사용량을 모두 썼습니다. 안내된 시간 뒤에 다시 사용할 수 있습니다."
            code == 429 -> "분당 한도를 넘었습니다. 1분 뒤 다시 시도하세요."
            else -> ""
        }
        return "오류 $code\n$hint\n$msg"
    }

    private fun readOut(text: String): Out {
        val root = JSONObject(text)
        val ch = root.getJSONArray("choices").getJSONObject(0)
        val c = ch.getJSONObject("message").optString("content")
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .trim()
        val used = root.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
        return Out(clean(c), ch.optString("finish_reason"), used)
    }

    fun ask(bmp: Bitmap, prompt: String, key: String, model: String, search: Boolean): String {
        val url = imageUrl(bmp)
        return if (search) lookup(url, prompt, key, model) else solve(url, key, model)
    }

    private fun lookup(url: String, prompt: String, key: String, model: String): String {
        val (code, text) = vision(url, "$prompt 최대 8줄 이내로 간결하게.", key, model)
        if (code !in 200..299) return errMsg(code, text)
        return try {
            val o = readOut(text)
            if (o.text.isEmpty()) return "응답이 비어 있습니다"
            var res = o.text
            if (o.finish == "length") res += "\n\n(출력 한도로 잘렸습니다)"
            if (o.tokens > 0) res += "\n\n[이번 사용 토큰 ${o.tokens}]"
            res
        } catch (e: Exception) {
            "응답을 읽지 못했습니다"
        }
    }

    private fun solve(url: String, key: String, model: String): String {
        val (c1, t1) = vision(url, READ_PROMPT, key, model)
        if (c1 !in 200..299) return errMsg(c1, t1)
        val r1 = try { readOut(t1) } catch (e: Exception) { return "문제를 읽지 못했습니다" }
        if (r1.text.isBlank()) return "문제를 읽지 못했습니다. 화면을 확인하고 다시 시도하세요."
        val head = "[읽은 문제]\n${r1.text}\n\n"

        var lastErr = ""
        for (m in SOLVERS) {
            val body = JSONObject()
                .put("model", m)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", SOLVE_PROMPT + r1.text)))
                .put("reasoning_effort", "medium")
                .put("max_completion_tokens", 5000)
            val (c2, t2) = post(body, key)
            if (c2 in 200..299) {
                val r2 = try { readOut(t2) } catch (e: Exception) {
                    lastErr = "풀이를 읽지 못했습니다"
                    continue
                }
                var res = if (r2.text.isEmpty()) head + "풀이가 비어 있습니다. 다시 눌러보세요."
                else head + "[풀이 · ${m.substringAfter('/')}]\n" + r2.text
                if (r2.finish == "length") res += "\n\n(생각이 길어져 잘렸습니다. 다시 눌러보세요)"
                res += "\n\n[이번 사용 토큰 ${r1.tokens + r2.tokens}]"
                return res
            }
            lastErr = errMsg(c2, t2)
            if (c2 != 429 && c2 != 413) break
        }
        return head + lastErr
    }

    private fun clean(s: String) = s
        .replace("**", "")
        .replace("### ", "")
        .replace("## ", "")
        .replace(Regex("(?m)^(\\s*)\\* "), "\$1• ")
}
