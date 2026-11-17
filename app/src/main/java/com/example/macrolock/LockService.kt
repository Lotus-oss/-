package com.example.macrolock

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.abs

class LockService : Service() {
    private lateinit var wm: WindowManager
    private var lockWm: WindowManager? = null
    private val h = Handler(Looper.getMainLooper())

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

    private fun btn(label: String, a: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        setOnClickListener { a() }
    }

    private fun drag(v: View, target: View, lp: WindowManager.LayoutParams, onTap: (() -> Unit)? = null) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) moved = true
                    if (moved) {
                        lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt()
                        try { wm.updateViewLayout(target, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
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
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xEE1E2330.toInt()); cornerRadius = dp(18).toFloat()
            }
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            text = "☰ 끌어서 이동"
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(0, 0, 0, dp(6))
        }
        statusTv = TextView(this).apply { setTextColor(0xFFAAB0C0.toInt()); textSize = 12f }
        intervalTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(btn("−") { setInterval(interval - step()) })
        row.addView(intervalTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(btn("+") { setInterval(interval + step()) })

        clickBtn = btn("▶ 클릭 시작") { toggleClick() }
        posBtn = btn("⌖ 클릭 위치 지정") { togglePick() }

        panel.addView(title)
        panel.addView(statusTv)
        panel.addView(row)
        panel.addView(clickBtn)
        panel.addView(posBtn)
        panel.addView(btn("🌙 암전 잠금\n(다른 클릭앱 통과)") { lock(true) })
        panel.addView(btn("🔒 완전 잠금\n(모든 터치 차단)") { lock(false) })
        panel.addView(btn("▼ 접기") { setExpanded(false) })
        panel.addView(btn("✕ 종료") { stopSelf() })
        refreshStatus()
        fr.addView(panel, FrameLayout.LayoutParams(dp(190), WRAP))
        drag(title, fr, rootLp)
        root = fr
    }

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
            posBtn.text = "⌖ 클릭 위치 지정"
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
        val dm = resources.displayMetrics
        val lp = wlp(size, size, NF or NL).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (cx > 0f) (cx - size / 2).toInt() else dm.widthPixels / 2 - size / 2
            y = if (cy > 0f) (cy - size / 2).toInt() else dm.heightPixels / 2 - size / 2
        }
        drag(m, m, lp)
        wm.addView(m, lp)
        marker = m
    }

    private fun lock(pass: Boolean) {
        if (!Pw.isSet(this)) { toast("앱 첫 화면에서 PIN을 먼저 저장하세요"); return }
        if (picking) togglePick()
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
                if (taps >= 3) { taps = 0; showPad(mgr, type) }
            }
        }
        val hLp = WindowManager.LayoutParams(WRAP, WRAP, type, NF or NL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(48); y = dp(48) }
        mgr.addView(hint, hLp)
        lockViews.add(hint)
    }

    private fun showPad(mgr: WindowManager, type: Int) {
        if (padView != null) return
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xFF222222.toInt())
        }
        val pin = StringBuilder()
        val disp = TextView(this).apply {
            textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            text = "PIN 입력"; setPadding(0, 0, 0, dp(12))
        }
        fun refresh() { disp.text = if (pin.isEmpty()) "PIN 입력" else "●".repeat(pin.length) }
        fun key(label: String, a: () -> Unit) = Button(this).apply {
            text = label; textSize = 20f; setOnClickListener { a() }
            layoutParams = LinearLayout.LayoutParams(0, dp(60), 1f)
        }
        pad.addView(disp)
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"),
            listOf("7", "8", "9"), listOf("지움", "0", "확인"))
        for (r in rows) {
            val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) lr.addView(key(k) {
                when (k) {
                    "지움" -> { if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1); refresh() }
                    "확인" -> {
                        if (Pw.check(this, pin.toString())) unlock()
                        else { pin.clear(); refresh(); toast("PIN이 틀렸습니다") }
                    }
                    else -> { if (pin.length < 12) pin.append(k); refresh() }
                }
            })
            pad.addView(lr)
        }
        pad.addView(Button(this).apply {
            text = "취소"
            setOnClickListener {
                try { lockWm?.removeView(pad) } catch (_: Exception) {}
                lockViews.remove(pad)
                padView = null
            }
        })
        val lp = WindowManager.LayoutParams(dp(320), WRAP, type, NF or NL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.CENTER }
        mgr.addView(pad, lp)
        padView = pad
        lockViews.add(pad)
    }

    private fun removeLockViews() {
        for (v in lockViews) try { lockWm?.removeView(v) } catch (_: Exception) {}
        lockViews.clear()
        lockBg = null; lockBgLp = null; padView = null
    }

    private fun unlock() {
        removeLockViews()
        if (!rootShown && root != null) {
            wm.addView(root, rootLp)
            rootShown = true
            setExpanded(false)
        }
    }

    override fun onDestroy() {
        clicking = false
        h.removeCallbacksAndMessages(null)
        marker?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        removeLockViews()
        if (rootShown) { try { wm.removeView(root) } catch (_: Exception) {} }
        marker = null; rootShown = false
        super.onDestroy()
    }
}
