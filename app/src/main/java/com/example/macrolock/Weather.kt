package com.example.macrolock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

object Weather {
    class Hour(val hour: Int, val emoji: String, val pop: Int, val temp: Int)
    class Report(val current: String, val hours: List<Hour>, val source: String)

    @Volatile private var cache: Report? = null
    @Volatile private var cacheAt = 0L
    @Volatile private var busy = false
    private val KST = TimeZone.getTimeZone("Asia/Seoul")

    private fun sp(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)
    private fun invalidate() { cache = null; cacheAt = 0L }

    private fun http(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "MacroLockApp/1.0 (personal use)")
        val t = if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        t
    } catch (_: Exception) { null }

    // ---------- 설정 ----------
    fun hasKey(ctx: Context) = !sp(ctx).getString("kkey", "").isNullOrBlank()

    fun saveKey(ctx: Context, k: String) {
        sp(ctx).edit().putString("kkey", k.trim()).apply()
        invalidate()
    }

    private fun store(ctx: Context, lat: Double, lon: Double, name: String, full: String) {
        sp(ctx).edit()
            .putString("wlat", lat.toString())
            .putString("wlon", lon.toString())
            .putString("wname", name)
            .putString("wfull", full)
            .apply()
        invalidate()
    }

    /** 동네·주소로 위치 찾기. 성공하면 찾은 전체 주소를 돌려줌 */
    fun setPlace(ctx: Context, query: String, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var res: String? = null
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                var arr = JSONArray(http("https://nominatim.openstreetmap.org/search?q=$q" +
                    "&format=jsonv2&limit=1&accept-language=ko&countrycodes=kr") ?: "[]")
                if (arr.length() == 0) {
                    arr = JSONArray(http("https://nominatim.openstreetmap.org/search?q=$q" +
                        "&format=jsonv2&limit=1&accept-language=ko") ?: "[]")
                }
                if (arr.length() > 0) {
                    val r = arr.getJSONObject(0)
                    val full = r.optString("display_name", query)
                    val nm = r.optString("name", "").ifEmpty { full.substringBefore(",").trim() }
                    store(app, r.getDouble("lat"), r.getDouble("lon"), nm, full)
                    res = full
                } else {
                    val g = JSONObject(http("https://geocoding-api.open-meteo.com/v1/search?name=$q" +
                        "&count=1&language=ko") ?: "{}").optJSONArray("results")?.optJSONObject(0)
                    if (g != null) {
                        val nm = g.optString("name", query)
                        val full = listOf(nm, g.optString("admin2", ""), g.optString("admin1", ""))
                            .filter { it.isNotEmpty() }.joinToString(", ")
                        store(app, g.getDouble("latitude"), g.getDouble("longitude"), nm, full)
                        res = full
                    }
                }
            } catch (_: Exception) {}
            done(res)
        }.start()
    }

    /** 이전 버전 호환용 */
    fun setCity(ctx: Context, name: String, done: (Boolean) -> Unit) =
        setPlace(ctx, name) { done(it != null) }

    /** GPS 좌표로 위치 저장. 동 이름을 찾아서 돌려줌 */
    fun setByCoords(ctx: Context, lat: Double, lon: Double, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var nm = "내 위치"
            var full = "내 위치 ($lat, $lon)"
            try {
                val j = JSONObject(http("https://nominatim.openstreetmap.org/reverse?format=jsonv2" +
                    "&lat=$lat&lon=$lon&zoom=16&accept-language=ko") ?: "{}")
                val a = j.optJSONObject("address")
                if (a != null) {
                    nm = listOf("suburb", "quarter", "neighbourhood", "village", "town", "city_district", "city")
                        .map { a.optString(it, "") }.firstOrNull { it.isNotEmpty() } ?: nm
                }
                full = j.optString("display_name", full)
            } catch (_: Exception) {}
            store(app, lat, lon, nm, full)
            done(full)
        }.start()
    }

    // ---------- 조회 ----------
    fun get(ctx: Context, cb: (Report?) -> Unit) {
        val app = ctx.applicationContext
        if (sp(app).getString("wlat", null) == null) { cb(null); return }
        val now = System.currentTimeMillis()
        val c = cache
        if (c != null && now - cacheAt < 20 * 60_000L) { cb(c); return }
        if (c != null) cb(c)
        if (busy) return
        busy = true
        Thread {
            try {
                val r = load(app).first
                if (r != null) { cache = r; cacheAt = System.currentTimeMillis(); cb(r) }
            } finally { busy = false }
        }.start()
    }

    /** 앱에서 '연결 테스트'용 */
    fun test(ctx: Context, cb: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            invalidate()
            val p = sp(app)
            val lat = p.getString("wlat", null)?.toDoubleOrNull()
            val lon = p.getString("wlon", null)?.toDoubleOrNull()
            if (lat == null || lon == null) { cb("먼저 위치를 설정하세요"); return@Thread }
            val name = p.getString("wname", "") ?: ""
            val key = p.getString("kkey", "") ?: ""
            if (key.isBlank()) {
                val r = openMeteo(lat, lon, name)
                cb(if (r.first != null) "인증키가 없어 Open-Meteo를 씁니다\n${r.first!!.current}" else r.second)
            } else if (!inKorea(lat, lon)) {
                cb("위치가 한국이 아니라 기상청을 쓸 수 없습니다")
            } else {
                val r = kma(lat, lon, name, key)
                cb(if (r.first != null) "기상청 연결 성공 ✅\n${r.first!!.current}" else r.second)
            }
        }.start()
    }

    private fun load(ctx: Context): Pair<Report?, String> {
        val p = sp(ctx)
        val lat = p.getString("wlat", null)?.toDoubleOrNull()
        val lon = p.getString("wlon", null)?.toDoubleOrNull()
        if (lat == null || lon == null) return Pair(null, "위치가 설정되지 않았습니다")
        val name = p.getString("wname", "") ?: ""
        val key = p.getString("kkey", "") ?: ""
        if (key.isNotBlank() && inKorea(lat, lon)) {
            val r = kma(lat, lon, name, key)
            if (r.first != null) return r
        }
        return openMeteo(lat, lon, name)
    }

    private fun inKorea(lat: Double, lon: Double) = lat in 33.0..38.9 && lon in 124.5..132.0

    // ---------- 기상청 ----------
    private fun grid(lat: Double, lon: Double): Pair<Int, Int> {
        val re = 6371.00877 / 5.0
        val d = Math.PI / 180.0
        val s1 = 30.0 * d
        val s2 = 60.0 * d
        val olon = 126.0 * d
        val olat = 38.0 * d
        var sn = Math.tan(Math.PI * 0.25 + s2 * 0.5) / Math.tan(Math.PI * 0.25 + s1 * 0.5)
        sn = Math.log(Math.cos(s1) / Math.cos(s2)) / Math.log(sn)
        var sf = Math.tan(Math.PI * 0.25 + s1 * 0.5)
        sf = Math.pow(sf, sn) * Math.cos(s1) / sn
        var ro = Math.tan(Math.PI * 0.25 + olat * 0.5)
        ro = re * sf / Math.pow(ro, sn)
        var ra = Math.tan(Math.PI * 0.25 + lat * d * 0.5)
        ra = re * sf / Math.pow(ra, sn)
        var th = lon * d - olon
        if (th > Math.PI) th -= 2.0 * Math.PI
        if (th < -Math.PI) th += 2.0 * Math.PI
        th *= sn
        val x = Math.floor(ra * Math.sin(th) + 43.0 + 0.5).toInt()
        val y = Math.floor(ro - ra * Math.cos(th) + 136.0 + 0.5).toInt()
        return Pair(x, y)
    }

    private fun kma(lat: Double, lon: Double, name: String, rawKey: String): Pair<Report?, String> {
        val key = URLEncoder.encode(
            if (rawKey.contains('%')) URLDecoder.decode(rawKey, "UTF-8") else rawKey, "UTF-8")
        val (nx, ny) = grid(lat, lon)
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = KST }
        val hourFmt = SimpleDateFormat("yyyyMMddHH", Locale.US).apply { timeZone = KST }
        val base = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0"

        // 단기예보 발표시각: 02,05,08,11,14,17,20,23시 (발표 10분 뒤부터 조회 가능)
        val b = Calendar.getInstance(KST).apply { add(Calendar.MINUTE, -15) }
        val times = intArrayOf(2, 5, 8, 11, 14, 17, 20, 23)
        var bh = times.lastOrNull { it <= b.get(Calendar.HOUR_OF_DAY) }
        if (bh == null) { b.add(Calendar.DAY_OF_MONTH, -1); bh = 23 }
        val baseDate = dayFmt.format(b.time)
        val baseTime = "%02d00".format(bh)

        val t = http("$base/getVilageFcst?serviceKey=$key&pageNo=1&numOfRows=1000&dataType=JSON" +
            "&base_date=$baseDate&base_time=$baseTime&nx=$nx&ny=$ny")
            ?: return Pair(null, "기상청 서버에 연결하지 못했습니다")
        val items: JSONArray = try {
            val resp = JSONObject(t).getJSONObject("response")
            val hd = resp.getJSONObject("header")
            if (hd.optString("resultCode") != "00")
                return Pair(null, "기상청 오류 ${hd.optString("resultCode")}: ${hd.optString("resultMsg")}")
            resp.getJSONObject("body").getJSONObject("items").getJSONArray("item")
        } catch (e: Exception) {
            return Pair(null, "기상청 응답을 읽지 못했습니다. 인증키를 확인하세요.\n" + t.take(150))
        }

        val slots = HashMap<String, HashMap<String, String>>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val k = o.getString("fcstDate") + o.getString("fcstTime")
            slots.getOrPut(k) { HashMap() }[o.getString("category")] = o.getString("fcstValue")
        }
        val nowKey = hourFmt.format(Calendar.getInstance(KST).time) + "00"

        val list = ArrayList<Hour>()
        var firstSky: String? = null
        var firstPty: String? = null
        for (k in slots.keys.sorted()) {
            if (k < nowKey) continue
            val m = slots[k] ?: continue
            val temp = m["TMP"]?.toDoubleOrNull()?.roundToInt() ?: continue
            val h = k.substring(8, 10).toInt()
            if (list.isEmpty()) { firstSky = m["SKY"]; firstPty = m["PTY"] }
            list.add(Hour(h, kmaEmoji(m["SKY"], m["PTY"], h), m["POP"]?.toIntOrNull() ?: -1, temp))
            if (list.size >= 9) break
        }
        if (list.isEmpty()) return Pair(null, "기상청 예보 데이터가 비어 있습니다")

        // 현재 기온은 초단기실황(관측값)으로 보정
        var curTemp = list[0].temp
        var curEmoji = list[0].emoji
        var curText = kmaText(firstSky, firstPty)
        try {
            val c3 = Calendar.getInstance(KST).apply { add(Calendar.MINUTE, -45) }
            val r3 = http("$base/getUltraSrtNcst?serviceKey=$key&pageNo=1&numOfRows=20&dataType=JSON" +
                "&base_date=${dayFmt.format(c3.time)}&base_time=${"%02d00".format(c3.get(Calendar.HOUR_OF_DAY))}" +
                "&nx=$nx&ny=$ny")
            if (r3 != null) {
                val arr = JSONObject(r3).getJSONObject("response").getJSONObject("body")
                    .getJSONObject("items").getJSONArray("item")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val v = o.getString("obsrValue")
                    when (o.getString("category")) {
                        "T1H" -> curTemp = v.toDouble().roundToInt()
                        "PTY" -> if (v != "0") {
                            curEmoji = kmaEmoji(null, v, c3.get(Calendar.HOUR_OF_DAY))
                            curText = kmaText(null, v)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val cur = "$curEmoji $curText ${curTemp}°C · $name"
        return Pair(Report(cur, list, "기상청"), "")
    }

    private fun kmaEmoji(sky: String?, pty: String?, hour: Int): String {
        val night = hour >= 18 || hour < 6
        when (pty) {
            "1", "5" -> return "🌧"
            "2", "6" -> return "🌨"
            "3", "7" -> return "❄️"
            "4" -> return "🌦"
        }
        return when (sky) {
            "1" -> if (night) "🌙" else "☀️"
            "3" -> if (night) "☁️" else "⛅"
            else -> "☁️"
        }
    }

    private fun kmaText(sky: String?, pty: String?): String {
        when (pty) {
            "1", "5" -> return "비"
            "2", "6" -> return "비/눈"
            "3", "7" -> return "눈"
            "4" -> return "소나기"
        }
        return when (sky) {
            "1" -> "맑음"
            "3" -> "구름많음"
            else -> "흐림"
        }
    }

    // ---------- Open-Meteo ----------
    private fun openMeteo(lat: Double, lon: Double, name: String): Pair<Report?, String> {
        val t = http("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
            "&forecast_days=2&timezone=auto") ?: return Pair(null, "날씨 서버에 연결하지 못했습니다")
        return try {
            val j = JSONObject(t)
            val cur = j.getJSONObject("current")
            val cutoff = cur.getString("time").substring(0, 13) + ":00"
            val hr = j.getJSONObject("hourly")
            val times = hr.getJSONArray("time")
            val temps = hr.getJSONArray("temperature_2m")
            val pops = hr.optJSONArray("precipitation_probability")
            val codes = hr.getJSONArray("weather_code")
            val days = hr.optJSONArray("is_day")
            val list = ArrayList<Hour>()
            for (i in 0 until times.length()) {
                val ts = times.getString(i)
                if (ts < cutoff) continue
                val night = days != null && days.optInt(i, 1) == 0
                val pop = if (pops != null && !pops.isNull(i)) pops.getInt(i) else -1
                list.add(Hour(ts.substring(11, 13).toInt(), wmoEmoji(codes.getInt(i), night),
                    pop, temps.getDouble(i).roundToInt()))
                if (list.size >= 9) break
            }
            val code = cur.getInt("weather_code")
            val night = cur.optInt("is_day", 1) == 0
            val text = "${wmoEmoji(code, night)} ${wmoText(code)} " +
                "${cur.getDouble("temperature_2m").roundToInt()}°C · $name"
            Pair(Report(text, list, "Open-Meteo"), "")
        } catch (e: Exception) {
            Pair(null, "날씨 데이터를 읽지 못했습니다")
        }
    }

    private fun wmoEmoji(c: Int, night: Boolean) = when (c) {
        0 -> if (night) "🌙" else "☀️"
        1 -> if (night) "🌙" else "🌤"
        2 -> if (night) "☁️" else "⛅"
        3 -> "☁️"
        45, 48 -> "🌫"
        in 51..57 -> "🌦"
        in 61..67 -> "🌧"
        in 71..77 -> "❄️"
        in 80..82 -> "🌦"
        85, 86 -> "🌨"
        95, 96, 99 -> "⛈"
        else -> "🌡"
    }

    private fun wmoText(c: Int) = when (c) {
        0 -> "맑음"
        1 -> "대체로 맑음"
        2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        in 51..57 -> "이슬비"
        in 61..67 -> "비"
        in 71..77 -> "눈"
        in 80..82 -> "소나기"
        85, 86 -> "눈 소나기"
        95, 96, 99 -> "뇌우"
        else -> ""
    }
}
