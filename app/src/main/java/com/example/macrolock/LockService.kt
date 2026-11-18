package com.example.macrolock

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import kotlin.math.abs

private class MaxScroll(ctx: Context, private val maxH: () -> Int) : ScrollView(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxH(), View.MeasureSpec.AT_MOST))
    }
}

class LockService : Service() {
    private lateinit var wm: WindowManager
    private var lockWm: WindowManager? = null
    private val h = Handler(Looper.getMainLooper())
    private var alive = true

    private var root: FrameLayout? = null
    private var rootShown = false
    private var marker: View? = null
    private val lockViews = mutableListOf<View>()
    private var lockBg: View? = null
    private var lockBgLp: WindowManager.LayoutParams? = null
    private var lockPass = false
    private var padView: View? = null
    private lateinit var icon: TextView
    private lateinit var panel: LinearLayout
    private lateinit var rootLp: WindowManager.LayoutParams

    private var aiView: View? = null
    private var aiText: TextView? = null
    private lateinit var aiLp: WindowManager.LayoutParams

    private var clicking = false
    private var picking = false
    private var cx = 0f
    private var cy = 0f
    private var interval = 1000L
    private var taps = 0
    private var lastTap = 0L
    private lateinit var statusTv: TextView
    private lateinit var intervalTv: TextView
    private lateinit var clickBtn: Button
    private lateinit var posBtn: Button

    private val NF = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    private val NL = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    private val NT = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    private val WRAP = WindowManager.LayoutParams.WRAP_CONTENT
    private val MATCH = WindowManager.LayoutParams.MATCH_PARENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun prefs() = getSharedPreferences("lock", Context.MODE_PRIVATE)

    private fun wlp(w: Int, hh: Int, flags: Int, fmt: Int = PixelFormat.TRANSLUCENT) =
        WindowManager.LayoutParams(w, hh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, fmt)

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private val clickLoop = object : Runnable {
        override fun run() {
            if (!clicking) return
            doClick()
            h.postDelayed(this, interval)
        }
    }

    private fun setPassThrough(on: Boolean) {
        val v = lockBg ?: return
        val lp = lockBgLp ?: return
        lp.flags = if (on) lp.flags or NT else lp.flags and NT.inv()
        try { lockWm?.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun doClick() {
        val svc = ClickService.instance ?: return
        if (lockBg == null || lockPass) { svc.click(cx, cy); return }
        setPassThrough(true)
        h.postDelayed({
            svc.click(cx, cy) { h.post { setPassThrough(false) } }
            h.postDelayed({ setPassThrough(false) }, 400)
        }, 40)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "lock"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "잠금", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, ch)
            .setContentTitle("클릭 락 작동 중")
            .setSmallIcon(android.R.drawable.ic_lock_lock).build())
        if (root != null) return START_NOT_STICKY
        alive = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cx = prefs().getFloat("cx", 0f)
        cy = prefs().getFloat("cy", 0f)
        interval = prefs().getLong("iv", 1000L)
        rootLp = wlp(WRAP, WRAP, NF or NL).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(100)
        }
        buildRoot()
        wm.addView(root, rootLp)
        rootShown = true
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        h.postDelayed({
            if (!alive) return@postDelayed
            aiView?.let {
                val sw = screenSize().first
                aiLp.width = minOf((sw * 0.92).toInt(), dp(560))
                try { wm.updateViewLayout(it, aiLp) } catch (_: Exception) {}
                fixPosition(aiLp, it)
            }
            root?.let { if (rootShown) fixPosition(rootLp, it) }
        }, 300)
    }

    // ---------- 공통 UI ----------
    private fun btn(label: String, a: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        setOnClickListener { a() }
    }

    private fun smallBtn(label: String, a: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        minWidth = 0; minimumWidth = dp(48)
        minHeight = 0; minimumHeight = dp(44)
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { a() }
    }

    private fun row2(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** handle을 끌면 target 창이 이동. 상태바 아래/화면 안으로만 움직임 */
    private fun drag(
        handle: View, target: View, lp: WindowManager.LayoutParams,
        full: Boolean = false, onTap: (() -> Unit)? = null
    ) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        var offX = 0; var offY = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    val loc = IntArray(2)
                    target.getLocationOnScreen(loc)
                    offX = loc[0] - lp.x; offY = loc[1] - lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) moved = true
                    if (moved) {
                        val (sw, sh) = screenSize()
                        val keepX = if (full) target.width else dp(80)
                        val keepY = if (full) target.height else dp(100)
                        val minX = -offX
                        val maxX = maxOf(minX, sw - offX - keepX)
                        val minY = statusBarH() - offY
                        val maxY = maxOf(minY, sh - offY - keepY)
                        lp.x = (sx + dx.toInt()).coerceIn(minX, maxX)
                        lp.y = (sy + dy.toInt()).coerceIn(minY, maxY)
                        try { wm.updateViewLayout(target, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
    }

    /** 펼치기/회전 뒤에 창 전체가 화면 안에 들어오도록 위치 보정 */
    private fun fixPosition(lp: WindowManager.LayoutParams, v: View) {
        v.postDelayed({
            try {
                if (!alive || !v.isAttachedToWindow) return@postDelayed
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val offX = loc[0] - lp.x; val offY = loc[1] - lp.y
                val (sw, sh) = screenSize()
                val minX = -offX
                val maxX = maxOf(minX, sw - offX - v.width)
                val minY = statusBarH() - offY
                val maxY = maxOf(minY, sh - offY - v.height)
                val nx = lp.x.coerceIn(minX, maxX)
                val ny = lp.y.coerceIn(minY, maxY)
                if (nx != lp.x || ny != lp.y) {
                    lp.x = nx; lp.y = ny
                    wm.updateViewLayout(v, lp)
                }
            } catch (_: Exception) {}
        }, 150)
    }

    private fun refreshStatus() {
        statusTv.text = if (cx == 0f && cy == 0f) "클릭 위치: 미지정"
        else "클릭 위치: (${cx.toInt()}, ${cy.toInt()})"
        intervalTv.text = "간격 ${"%.1f".format(interval / 1000.0)}초"
    }

    private fun setInterval(v: Long) {
        interval = v.coerceIn(200L, 10000L)
        prefs().edit().putLong("iv", interval).apply()
        refreshStatus()
    }

    private fun step() = if (interval < 2000) 100L else 500L

    private fun setExpanded(open: Boolean) {
        icon.visibility = if (open) View.GONE else View.VISIBLE
        panel.visibility = if (open) View.VISIBLE else View.GONE
        try { wm.updateViewLayout(root, rootLp) } catch (_: Exception) {}
        root?.let { fixPosition(rootLp, it) }
    }

    private fun buildRoot() {
        val fr = FrameLayout(this)

        icon = TextView(this).apply {
            text = "🎯"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xEE1E2330.toInt()); setStroke(dp(2), 0xFF22C55E.toInt())
            }
        }
        fr.addView(icon, FrameLayout.LayoutParams(dp(52), dp(52)))
        drag(icon, fr, rootLp) { setExpanded(true) }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(0xEE1E2330.toInt()); cornerRadius = dp(18).toFloat()
            }
            visibility = View.GONE
        }

        // 제목줄: 크게 잡고 끌 수 있고, 접기/종료 버튼은 항상 보임
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "☰ 여기를 잡고 이동"
            setTextColor(Color.WHITE); textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(smallBtn("▼") { setExpanded(false) })
        header.addView(smallBtn("✕") { stopSelf() })

        statusTv = TextView(this).apply {
            setTextColor(0xFFAAB0C0.toInt()); textSize = 12f
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        intervalTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        val ivRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        ivRow.addView(smallBtn("−") { setInterval(interval - step()) })
        ivRow.addView(intervalTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        ivRow.addView(smallBtn("+") { setInterval(interval + step()) })

        clickBtn = btn("▶ 클릭 시작") { toggleClick() }
        posBtn = btn("⌖ 위치 지정") { togglePick() }

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(statusTv)
        content.addView(ivRow)
        content.addView(row2(btn("🔍 검색") { askAi(true) }, btn("📝 풀이") { askAi(false) }))
        content.addView(row2(clickBtn, posBtn))
        content.addView(row2(btn("🌙 암전 잠금") { lock(true) }, btn("🔒 완전 잠금") { lock(false) }))
        refreshStatus()

        val sc = MaxScroll(this) { (screenSize().second * 0.7).toInt() }.apply { addView(content) }
        panel.addView(header)
        panel.addView(sc)
        fr.addView(panel, FrameLayout.LayoutParams(dp(250), WRAP))

        drag(title, fr, rootLp)
        drag(panel, fr, rootLp)
        root = fr
    }

    // ---------- AI ----------
    private fun prompt(search: Boolean) = if (search)
        "이 화면에 보이는 내용을 한국어로 설명하고, 필요하면 웹 검색으로 정보를 찾아서 핵심만 간단히 정리해줘. 마크다운 서식 없이 일반 텍스트로 답해줘."
    else
        "이 화면에 있는 문제를 풀어줘. 한국어로 정답을 맨 위에 먼저 쓰고, 그 아래에 간단한 풀이 과정을 써줘. 객관식이면 정답 번호를 먼저 말해줘. 마크다운 서식 없이 일반 텍스트로 답해줘."

    private fun askAi(search: Boolean) {
        val key = prefs().getString("gkey", "") ?: ""
        if (key.isBlank()) { toast("앱 첫 화면에서 Gemini API 키를 저장하세요"); return }
        val svc = ClickService.instance
        if (svc == null) { toast("접근성 서비스를 먼저 켜세요 (앱 첫 화면)"); return }
        val model = prefs().getString("gmodel", "gemini-3.6-flash") ?: "gemini-3.6-flash"

        closeAi()
        setExpanded(false)
        root?.visibility = View.INVISIBLE
        h.postDelayed({
            svc.screenshot { bmp, err ->
                root?.visibility = View.VISIBLE
                if (bmp == null) {
                    showAi(err ?: "캡처에 실패했습니다")
                    return@screenshot
                }
                showAi("분석 중... ⏳")
                Thread {
                    val result = try {
                        Gemini.ask(bmp, prompt(search), key, model, search)
                    } catch (e: Exception) {
                        "오류: ${e.message}"
                    }
                    h.post { showAi(result) }
                }.start()
            }
        }, 250)
    }

    private fun showAi(msg: String) {
        if (!alive) return
        if (aiView == null) buildAi()
        aiText?.text = msg
    }

    private fun buildAi() {
        val (sw, _) = screenSize()
        val w = minOf((sw * 0.92).toInt(), dp(560))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF2151A26.toInt()); cornerRadius = dp(18).toFloat()
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "🤖 AI 답변 (여기를 잡고 이동)"
            setTextColor(Color.WHITE); textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(0, dp(8), dp(8), dp(8))
        }
        bar.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(smallBtn("복사") { copyAi() })
        bar.addView(smallBtn("닫기") { closeAi() })

        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(0, dp(8), 0, 0)
        }
        val sc = MaxScroll(this) { (screenSize().second * 0.5).toInt() }.apply { addView(tv) }
        box.addView(bar)
        box.addView(sc)

        aiLp = wlp(w, WRAP, NF or NL).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - w) / 2
            y = dp(80)
        }
        drag(title, box, aiLp)
        drag(box, box, aiLp)
        wm.addView(box, aiLp)
        aiView = box
        aiText = tv
        fixPosition(aiLp, box)
    }

    private fun copyAi() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ai", aiText?.text ?: ""))
        toast("복사됨")
    }

    private fun closeAi() {
        aiView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        aiView = null
        aiText = null
    }

    // ---------- 클릭 ----------
    private fun toggleClick() {
        if (clicking) { stopClick(); return }
        if (ClickService.instance == null) { toast("접근성 서비스를 먼저 켜세요 (앱 첫 화면)"); return }
        if (cx == 0f && cy == 0f) { toast("먼저 클릭 위치를 지정하세요"); return }
        clicking = true
        clickBtn.text = "■ 클릭 정지"
        h.post(clickLoop)
    }

    private fun stopClick() {
        clicking = false
        h.removeCallbacks(clickLoop)
        if (::clickBtn.isInitialized) clickBtn.text = "▶ 클릭 시작"
    }

    private fun togglePick() {
        if (!picking) {
            stopClick()
            picking = true
            posBtn.text = "✔ 위치 확정"
            showMarker()
        } else {
            marker?.let { m ->
                val loc = IntArray(2)
                m.getLocationOnScreen(loc)
                cx = loc[0] + m.width / 2f
                cy = loc[1] + m.height / 2f
                prefs().edit().putFloat("cx", cx).putFloat("cy", cy).apply()
                try { wm.removeView(m) } catch (_: Exception) {}
            }
            marker = null
            picking = false
            posBtn.text = "⌖ 위치 지정"
            refreshStatus()
        }
    }

    private fun showMarker() {
        val size = dp(64)
        val m = TextView(this).apply {
            text = "＋"; textSize = 28f; gravity = Gravity.CENTER
            setTextColor(Color.RED)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FF0000); setStroke(dp(3), Color.RED)
            }
        }
        val (sw, sh) = screenSize()
        val lp = wlp(size, size, NF or NL).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (cx > 0f) (cx - size / 2).toInt() else sw / 2 - size / 2
            y = if (cy > 0f) (cy - size / 2).toInt() else sh / 2 - size / 2
        }
        drag(m, m, lp, full = true)
        wm.addView(m, lp)
        marker = m
    }

    // ---------- 잠금 ----------
    private fun lock(pass: Boolean) {
        if (!Pw.isSet(this)) { toast("앱 첫 화면에서 PIN을 먼저 저장하세요"); return }
        if (picking) togglePick()
        closeAi()
        if (rootShown) { try { wm.removeView(root) } catch (_: Exception) {}; rootShown = false }
        showLock(pass)
    }

    private fun showLock(pass: Boolean) {
        val svc = ClickService.instance
        val mgr = if (svc != null) svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager else wm
        val type = if (svc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        lockWm = mgr
        lockPass = pass

        val base = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or NL or NF
        val dim = pass && svc == null

        val bg = View(this).apply { setBackgroundColor(Color.BLACK) }
        if (!pass) bg.setOnTouchListener { _, _ -> true }
        val bgLp = WindowManager.LayoutParams(
            MATCH, MATCH, type,
            if (pass) base or NT else base,
            if (dim) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
        )
        if (dim) bgLp.alpha = 0.79f
        if (Build.VERSION.SDK_INT >= 28)
            bgLp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        mgr.addView(bg, bgLp)
        lockViews.add(bg)
        lockBg = bg
        lockBgLp = bgLp

        val hint = TextView(this).apply {
            text = "🔒 3번 탭"; textSize = 20f
            setTextColor(Color.WHITE); alpha = 0.35f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                val now = SystemClock.uptimeMillis()
                taps = if (now - lastTap < 2000) taps + 1 else 1
                lastTap = now
  
