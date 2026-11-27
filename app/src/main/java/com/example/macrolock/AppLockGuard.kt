package com.example.macrolock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class AppLockGuard(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var unlockedPkg: String? = null
    private val lastActive = HashMap<String, Long>()

    // 나갔다 돌아왔을 때 PIN을 면제해 주는 시간(ms). 0 = 항상 PIN. 예: 10_000L = 10초
    private val grace = 0L
    // 맨 앞 앱을 확인하는 간격(ms)
    private val pollMs = 500L

    private val system = setOf(
        "com.android.systemui", "android",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller"
    )

    private fun d(v: Int) = (v * svc.resources.displayMetrics.density).toInt()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { unlockedPkg = null; lastActive.clear() }
                else -> reconcile()
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            try {
                val pm = svc.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isInteractive && (lockedSet().isNotEmpty() || overlay != null)) reconcile()
            } catch (_: Exception) {}
            h.postDelayed(this, pollMs)
        }
    }

    init {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        svc.registerReceiver(receiver, f)
        h.postDelayed(poll, pollMs)
    }

    fun destroy() {
        h.removeCallbacks(poll)
        try { svc.unregisterReceiver(receiver) } catch (_: Exception) {}
        hide()
    }

    private fun prefs() = svc.getSharedPreferences("lock", Context.MODE_PRIVATE)

    private fun lockedSet(): Set<String> {
        val p = prefs()
        if (!p.getBoolean("al_on", false)) return emptySet()
        return p.getStringSet("al_pkgs", emptySet()) ?: emptySet()
    }

    private fun isSystem(pkg: String): Boolean {
        if (pkg in system) return true
        return try {
            val imm = svc.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == pkg }
        } catch (_: Exception) { false }
    }

    /** 지금 실제로 맨 앞(활성)인 창의 앱 이름. 앱 이름만 확인하고 화면 내용은 읽지 않음 */
    private fun activePkg(): String? {
        return try {
            val n = svc.rootInActiveWindow ?: return null
            val p = n.packageName?.toString()
            @Suppress("DEPRECATION") n.recycle()
            p
        } catch (_: Exception) { null }
    }

    /** 창 상태 변경 이벤트 */
    fun onEvent(raw: CharSequence?) {
        val pkg = raw?.toString() ?: return
        if (pkg.isEmpty() || pkg == svc.packageName || isSystem(pkg)) return
        decide(pkg, false)
    }

    /** 실제 맨 앞 앱을 확인해서 상태를 맞춤 */
    fun reconcile() {
        val pkg = activePkg() ?: return
        if (pkg.isEmpty() || isSystem(pkg)) return
        decide(pkg, true)
    }

    private fun decide(pkg: String, fromActive: Boolean) {
        val now = SystemClock.uptimeMillis()
        val set = lockedSet()

        if (pkg !in set) {
            // 이벤트로만 들어온 경우: 잠긴 앱이 아직 맨 앞이면 팝업 등 잡음이므로 무시
            if (!fromActive) {
                val act = activePkg()
                if (act != null && !isSystem(act) && act in set) return
            }
            val prev = unlockedPkg
            if (prev != null) { lastActive[prev] = now; unlockedPkg = null }
            hide()
            return
        }

        val prev = unlockedPkg
        if (prev != null && prev != pkg) { lastActive[prev] = now; unlockedPkg = null }
        if (unlockedPkg == pkg) return
        if (overlayPkg == pkg && overlay?.isAttachedToWindow == true) return
        if (grace > 0 && now - (lastActive[pkg] ?: 0L) < grace) { unlockedPkg = pkg; return }
        show(pkg)
    }

    private fun hide() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        overlayPkg = null
    }

    private fun show(pkg: String) {
        hide()
        val appName = try {
            svc.packageManager.getApplicationLabel(
                svc.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

        val root = LockRoot(pkg, appName)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        )
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try {
            wm.addView(root, lp)
            overlay = root
            overlayPkg = pkg
        } catch (_: Exception) {}
    }

    /** 검은 배경 + 시계·날씨(위) + PIN 상자. 화면이 회전하면 자동으로 다시 배치 */
    private inner class LockRoot(
        private val pkg: String,
        private val appName: String
    ) : FrameLayout(svc) {
        private val pin = StringBuilder()
        private var lastLand: Boolean? = null
        private var lastH = 0

        init {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (w <= 0 || h <= 0) return
            val land = w > h
            if (lastLand == land && childCount > 0 && abs(h - lastH) < d(40)) return
            lastLand = land
            lastH = h
            post { rebuild(w, h, land) }
        }

        private fun rebuild(w: Int, h: Int, land: Boolean) {
            removeAllViews()

            // ---- 어두운 배경 위: 시계 · 날씨 ----
            if (land) {
                addView(InfoText(svc, 14f, true).apply { alpha = 0.85f },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = d(8) })
            } else {
                val info = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    alpha = 0.9f
                }
                info.addView(InfoText(svc, 26f))
                if (h >= d(760)) {
                    info.addView(HourlyView(svc, true).apply {
                        background = GradientDrawable().apply {
                            setColor(0x33FFFFFF); cornerRadius = d(14).toFloat()
                        }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = d(8) })
                }
                addView(info, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP).apply { topMargin = d(40); leftMargin = d(12); rightMargin = d(12) })
            }

            // ---- PIN 상자 ----
            val title = TextView(svc).apply {
                text = "🔒 $appName"
                textSize = if (land) 18f else 20f
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(0, 0, 0, d(6))
            }
            val disp = TextView(svc).apply {
                textSize = if (land) 22f else 26f
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(0, 0, 0, d(if (land) 6 else 12))
            }
            fun refresh() { disp.text = if (pin.isEmpty()) "PIN 입력" else "●".repeat(pin.length) }
            refresh()

            val keyH = if (land) ((h - d(56)) / 4).coerceIn(d(36), d(56)) else d(60)
            fun key(t: String, a: () -> Unit) = Button(svc).apply {
                text = t
                textSize = if (land) 18f else 20f
                minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { a() }
                layoutParams = LinearLayout.LayoutParams(0, keyH, 1f)
            }

            val pad = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"),
                listOf("7", "8", "9"), listOf("지움", "0", "확인"))
            for (r in rows) {
                val lr = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in r) lr.addView(key(k) {
                    when (k) {
                        "지움" -> { if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1); refresh() }
                        "확인" -> {
                            if (Pw.check(svc, pin.toString())) {
                                unlockedPkg = pkg
                                hide()
                            } else {
                                pin.clear(); refresh()
                                Toast.makeText(svc, "PIN이 틀렸습니다", Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> { if (pin.length < 12) pin.append(k); refresh() }
                    }
                })
                pad.addView(lr)
            }

            val home = Button(svc).apply {
                text = "홈으로"
                if (land) { minHeight = 0; minimumHeight = d(40) }
                setOnClickListener {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
            }

            if (land) {
                // 가로: 왼쪽(앱 이름·PIN 표시·홈으로) + 오른쪽(숫자패드)
                val box = LinearLayout(svc).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(d(16), d(12), d(16), d(12))
                    setBackgroundColor(0xFF222222.toInt())
                }
                val left = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(d(8), 0, d(16), 0)
                }
                left.addView(title)
                left.addView(disp)
                left.addView(home, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                box.addView(left, LinearLayout.LayoutParams(d(200), LinearLayout.LayoutParams.WRAP_CONTENT))
                box.addView(pad, LinearLayout.LayoutParams(d(260), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(box, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER))
            } else {
                // 세로: 아래쪽에 한 줄로
                val box = LinearLayout(svc).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(d(20), d(20), d(20), d(20))
                    setBackgroundColor(0xFF222222.toInt())
                }
                box.addView(title)
                box.addView(disp)
                box.addView(pad)
                box.addView(home)
                addView(box, FrameLayout.LayoutParams(
                    d(320), FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = d(32) })
            }
        }
    }
}
