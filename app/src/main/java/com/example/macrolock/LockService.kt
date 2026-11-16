package com.example.macrolock

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*

class LockService : Service() {
    private var wm: WindowManager? = null
    private var frame: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var taps = 0
    private var lastTap = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "lock"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "잠금", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("MacroLock 작동 중")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, n)
        handler.postDelayed({ showOverlay() }, 10_000)
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (frame != null) return
        val dp = resources.displayMetrics.density
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w

        val fr = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = (24 * dp).toInt()
            setPadding(pd, pd, pd, pd)
            setBackgroundColor(0xFF222222.toInt())
            visibility = View.GONE
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "비밀번호"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val ok = Button(this).apply { text = "해제" }
        val cancel = Button(this).apply { text = "취소" }
        panel.addView(input); panel.addView(ok); panel.addView(cancel)
        fr.addView(panel, FrameLayout.LayoutParams(
            (300 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        ok.setOnClickListener {
            if (Pw.check(this, input.text.toString())) unlock()
            else {
                input.setText("")
                Toast.makeText(this, "비밀번호가 틀렸습니다", Toast.LENGTH_SHORT).show()
            }
        }
        cancel.setOnClickListener {
            input.setText("")
            panel.visibility = View.GONE
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }

        val corner = 100 * dp
        fr.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN && panel.visibility != View.VISIBLE &&
                e.x > v.width - corner && e.y > v.height - corner) {
                val now = SystemClock.uptimeMillis()
                taps = if (now - lastTap < 2000) taps + 1 else 1
                lastTap = now
                if (taps >= 5) {
                    taps = 0
                    panel.visibility = View.VISIBLE
                    input.requestFocus()
                    imm.showSoftInput(input, 0)
                }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        w.addView(fr, lp)
        frame = fr
    }

    private fun unlock() {
        frame?.let { wm?.removeView(it) }
        frame = null
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        frame?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        frame = null
        super.onDestroy()
    }
}
