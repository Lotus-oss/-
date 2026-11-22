package com.example.macrolock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
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
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var unlockedPkg: String? = null
    private var currentPkg: String? = null
    private val lastActive = HashMap<String, Long>()
    private val grace = 10_000L

    private val system = setOf(
        "com.android.systemui", "android",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller"
    )

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            unlockedPkg = null
            lastActive.clear()
            val p = currentPkg
            if (p != null && p in lockedSet()) show(p)
        }
    }

    init {
        svc.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    fun destroy() {
        try { svc.unregisterReceiver(screenOff) } catch (_: Exception) {}
        hide()
    }

    private fun prefs() = svc.getSharedPreferences("lock", Context.MODE_PRIVATE)

    private fun lockedSet(): Set<String> {
        val p = prefs()
        if (!p.getBoolean("al_on", false)) return emptySet()
        return p.getStringSet("al_pkgs", emptySet()) ?: emptySet()
    }

    private fun ignored(pkg: String): Boolean {
        if (pkg == svc.packageName || pkg in system) return true
        return try {
            val imm = svc.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == pkg }
        } catch (_: Exception) { false }
    }

    fun onEvent(raw: CharSequence?) {
        val pkg = raw?.toString() ?: return
        if (pkg.isEmpty() || ignored(pkg)) return
        val now = SystemClock.uptimeMillis()
        currentPkg = pkg

        val prev = unlockedPkg
        if (prev != null && prev != pkg) {
            lastActive[prev] = now
            unlockedPkg = null
        }
        if (pkg !in lockedSet()) { hide(); return }
        if (unlockedPkg == pkg) return
        if (overlayPkg == pkg) return
        if (now - (lastActive[pkg] ?: 0L) < grace) { unlockedPkg = pkg; return }
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
