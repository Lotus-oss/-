
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

class LockService : Service() {
    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var panel: View? = null
    private var marker: View? = null
    private var lockView: View? = null
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
    private val WRAP = WindowManager.LayoutParams.WRAP_CONTENT
    private lateinit var panelLp: WindowManager.LayoutParams

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun prefs() = getSharedPreferences("lock", Context.MODE_PRIVATE)

    private fun wlp(w: Int, hh: Int, flags: Int, fmt: Int = PixelFormat.TRANSLUCENT) =
        WindowManager.LayoutParams(w, hh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, fmt)

    private val clickLoop = object : Runnable {
        override fun run() {
            if (!clicking) return
            ClickService.instance?.click(cx, cy)
            h.postDelayed(this, interval)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "lock"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "잠금", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, ch)
            .setContentTitle("MacroLock 작동 중")
            .setSmallIcon(android.R.drawable.ic_lock_lock).build())
        if (panel != null || lockView != null) return START_NOT_STICKY
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cx = prefs().getFloat("cx", 0f)
        cy = prefs().getFloat("cy", 0f)
        interval = prefs().getLong("iv", 1000L)
        panelLp = wlp(WRAP, WRAP, NF or NL).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(100)
        }
        showPanel()
        return START_NOT_STICKY
    }

    private fun btn(label: String, a: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setOnClickListener { a() }
    }

    private fun drag(handle: View, target: View, lp: WindowManager.LayoutParams) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt()
                    lp.y = sy + (e.rawY - ty).toInt()
                    try { wm.updateViewLayout(target, lp) } catch (_: Exception) {}
                }
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
        interval = v.coerceIn(100L, 10000L)
        prefs().edit().putLong("iv", interval).apply()
        refreshStatus()
    }

    private fun step() = if (interval < 2000) 100L else 500L

    private fun showPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(0xEE1E2330.toInt()); cornerRadius = dp(18).toFloat()
            }
        }
        val title = TextView(this).apply {
            text = "☰ 매크로 락 (여기를 끌어 이동)"
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(0, 0, 0, dp(8))
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

        clickBtn = btn(if (clicking) "■ 클릭 정지" else "▶ 클릭 시작") { toggleClick() }
        posBtn = btn(if (picking) "✔ 위치 확정" else "⌖ 클릭 위치 지정") { togglePick() }

        box.addView(title)
        box.addView(statusTv)
        box.addView(row)
        box.addView(clickBtn)
        box.addView(posBtn)
        box.addView(btn("🔒 화면 잠금") { lock() })
        box.addView(btn("✕ 종료") { stopSelf() })
        refreshStatus()

        drag(title, box, panelLp)
        wm.addView(box, panelLp)
        panel = box
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

    private fun lock() {
        if (!Pw.isSet(this)) { toast("앱 첫 화면에서 PIN을 먼저 저장하세요"); return }
        if (picking) togglePick()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        showLock()
    }

    private fun showLock() {
        val fr = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        fr.setOnTouchListener { _, _ -> true }

        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xFF222222.toInt())
            visibility = View.GONE
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
            setOnClickListener { pin.clear(); refresh(); pad.visibility = View.GONE }
        })
        fr.addView(pad, FrameLayout.LayoutParams(dp(320), WRAP, Gravity.CENTER))

        val hint = TextView(this).apply {
            text = "🔒 3번 탭"; textSize = 20f
            setTextColor(Color.WHITE); alpha = 0.35f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                val now = SystemClock.uptimeMillis()
                taps = if (now - lastTap < 2000) taps + 1 else 1
                lastTap = now
                if (taps >= 3) { taps = 0; pad.visibility = View.VISIBLE }
            }
        }
        fr.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, dp(48), dp(48)) })

        val lp = wlp(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or NL or NF,
            PixelFormat.OPAQUE
        )
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(fr, lp)
        lockView = fr
    }

    private fun unlock() {
        lockView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        lockView = null
        showPanel()
    }

    override fun onDestroy() {
        clicking = false
        h.removeCallbacksAndMessages(null)
        for (v in listOf(panel, marker, lockView))
            v?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null; marker = null; lockView = null
        super.onDestroy()
    }
}
