package com.example.macrolock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
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
        val density = svc.resources.displayMetrics.density
        fun d(v: Int) = (v * density).toInt()

        val appName = try {
            svc.packageManager.getApplicationLabel(
                svc.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

        val root = FrameLayout(svc).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20), d(20), d(20), d(20))
            setBackgroundColor(0xFF222222.toInt())
        }
        val title = TextView(svc).apply {
            text = "🔒 $appName"
            textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, 0, 0, d(8))
        }
        val disp = TextView(svc).apply {
            text = "PIN 입력"
            textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, 0, 0, d(12))
        }
        val pin = StringBuilder()
        fun refresh() { disp.text = if (pin.isEmpty()) "PIN 입력" else "●".repeat(pin.length) }
        fun key(t: String, a: () -> Unit) = Button(svc).apply {
            text = t; textSize = 20f
            setOnClickListener { a() }
            layoutParams = LinearLayout.LayoutParams(0, d(60), 1f)
        }

        box.addView(title)
        // 시계 + 날씨 + 시간별 예보
        box.addView(InfoText(svc, 18f), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (svc.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
            box.addView(HourlyView(svc, true), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(disp)
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
            box.addView(lr)
        }
        box.addView(Button(svc).apply {
            text = "홈으로"
            setOnClickListener {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        })
        root.addView(box, FrameLayout.LayoutParams(d(320), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

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
}
