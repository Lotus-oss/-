package com.example.macrolock

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 시계 + 날짜 + 날씨. 화면에 붙어 있을 때만 갱신됩니다 */
class InfoText(ctx: Context, size: Float = 28f) : TextView(ctx) {
    private val hd = Handler(Looper.getMainLooper())
    private var weather = ""
    private val tf = SimpleDateFormat("a h:mm", Locale.KOREA)
    private val df = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            text = tf.format(now) + "\n" + df.format(now) +
                (if (weather.isNotEmpty()) "\n$weather" else "")
            hd.postDelayed(this, 1000)
        }
    }

    private val wk = object : Runnable {
        override fun run() {
            Weather.get(context) { w -> hd.post { weather = w } }
            hd.postDelayed(this, 10 * 60_000L)
        }
    }

    init {
        setTextColor(Color.WHITE)
        textSize = size
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 1f, Color.BLACK)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hd.post(tick)
        hd.post(wk)
    }

    override fun onDetachedFromWindow() {
        hd.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
