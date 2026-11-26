package com.example.macrolock

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

object Weather {
    @Volatile private var cache = ""
    @Volatile private var cacheAt = 0L
    @Volatile private var busy = false

    private fun sp(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)

    private fun http(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val t = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        t
    } catch (_: Exception) { null }

    /** 도시 이름으로 좌표를 찾아 저장 */
    fun setCity(ctx: Context, name: String, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var ok = false
            val t = http("https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(name, "UTF-8") + "&count=1&language=ko")
            try {
                val r = JSONObject(t ?: "").getJSONArray("results").getJSONObject(0)
                sp(app).edit()
                    .putString("wname", r.optString("name", name))
                    .putString("wlat", r.getDouble("latitude").toString())
                    .putString("wlon", r.getDouble("longitude").toString())
                    .apply()
                cache = ""
                cacheAt = 0L
                ok = true
            } catch (_: Exception) {}
            done(ok)
        }.start()
    }

    private fun label(c: Int) = when (c) {
        0 -> "☀️ 맑음"
        1 -> "🌤 대체로 맑음"
        2 -> "⛅ 구름 조금"
        3 -> "☁️ 흐림"
        45, 48 -> "🌫 안개"
        in 51..57 -> "🌦 이슬비"
        in 61..67 -> "🌧 비"
        in 71..77 -> "❄️ 눈"
        in 80..82 -> "🌦 소나기"
        85, 86 -> "🌨 눈 소나기"
        95, 96, 99 -> "⛈ 뇌우"
        else -> "🌡"
    }

    /** 날씨 문구를 돌려줌. 도시가 없으면 빈 문자열 */
    fun get(ctx: Context, cb: (String) -> Unit) {
        val app = ctx.applicationContext
        val p = sp(app)
        val lat = p.getString("wlat", null)
        val lon = p.getString("wlon", null)
        if (lat == null || lon == null) { cb(""); return }
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cacheAt < 30 * 60_000L) { cb(cache); return }
        if (cache.isNotEmpty()) cb(cache)
        if (busy) return
        busy = true
        Thread {
            try {
                val t = http("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code&timezone=auto")
                if (t != null) {
                    val cur = JSONObject(t).getJSONObject("current")
                    val temp = cur.getDouble("temperature_2m").roundToInt()
                    val code = cur.getInt("weather_code")
                    cache = "${label(code)} ${temp}°C · ${p.getString("wname", "")}"
                    cacheAt = System.currentTimeMillis()
                    cb(cache)
                }
            } catch (_: Exception) {
            } finally { busy = false }
        }.start()
    }
}
