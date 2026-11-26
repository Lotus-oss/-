package com.example.macrolock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView

/** 지금부터 8시간 뒤까지: 시간 · 아이콘 · 강수확률 · 기온 그래프 (가로 스크롤) */
class HourlyView(ctx: Context, private val compact: Boolean = false) : HorizontalScrollView(ctx) {
    private val hd = Handler(Looper.getMainLooper())

    private fun dpf(v: Float) = v * resources.displayMetrics.density
    private fun spf(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private val totalH: Float get() = dpf(if (compact) 124f else 168f)

    private val chart = Chart(ctx)

    private inner class Chart(c: Context) : View(c) {
        private var hours: List<Weather.Hour> = emptyList()
        private val colW = dpf(if (compact) 58f else 64f)

        private fun textPaint(sz: Float, col: Int, bold: Boolean = false) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = spf(sz)
                color = col
                isFakeBoldText = bold
                setShadowLayer(dpf(2f), 0f, 1f, Color.BLACK)
            }

        private val timeP = textPaint(if (compact) 10f else 12f, 0xDDFFFFFF.toInt())
        private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = spf(if (compact) 20f else 26f)
        }
        private val popP = textPaint(if (compact) 10f else 11f, 0xFF9CC9FF.toInt())
        private val tempP = textPaint(if (compact) 12f else 14f, Color.WHITE, true)
        private val msgP = textPaint(12f, 0xCCFFFFFF.toInt())
        private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(2f)
            color = 0xFFE8B84A.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        fun update(list: List<Weather.Hour>) {
            hours = list
            requestLayout()
            invalidate()
        }

        override fun onMeasure(w: Int, h: Int) {
            val cols = if (hours.isEmpty()) 5 else hours.size
            setMeasuredDimension((colW * cols).toInt(), totalH.toInt())
        }

        override fun onDraw(cv: Canvas) {
            if (hours.isEmpty()) {
                cv.drawText("날씨를 불러오는 중…", width / 2f, totalH / 2f, msgP)
                cv.drawText("(앱에서 위치를 설정하세요)", width / 2f, totalH / 2f + dpf(18f), msgP)
                return
            }
            val tY: Float
            val iY: Float
            val pY: Float
            val gT: Float
            val gB: Float
            if (compact) {
                tY = dpf(14f); iY = dpf(44f); pY = dpf(64f); gT = dpf(88f); gB = dpf(112f)
            } else {
                tY = dpf(18f); iY = dpf(58f); pY = dpf(84f); gT = dpf(112f); gB = dpf(152f)
            }
            val minT = hours.minOf { it.temp }
            val maxT = hours.maxOf { it.temp }
            val span = maxOf(maxT - minT, 1)
            val xs = FloatArray(hours.size)
            val ys = FloatArray(hours.size)
            val path = Path()
            for (i in hours.indices) {
                val h = hours[i]
                val x = colW * (i + 0.5f)
                xs[i] = x
                ys[i] = gB - (h.temp - minT).toFloat() / span * (gB - gT)
                val label = if (i == 0) "지금" else
                    (if (h.hour < 12) "오전 " else "오후 ") + (if (h.hour % 12 == 0) 12 else h.hour % 12) + "시"
                cv.drawText(label, x, tY, timeP)
                cv.drawText(h.emoji, x, iY, iconP)
                cv.drawText(if (h.pop >= 0) "☔${h.pop}%" else "–", x, pY, popP)
                if (i == 0) path.moveTo(x, ys[i]) else path.lineTo(x, ys[i])
            }
            cv.drawPath(path, lineP)
            for (i in hours.indices) {
                cv.drawCircle(xs[i], ys[i], dpf(if (i == 0) 5f else 3.5f), dotP)
                cv.drawText("${hours[i].temp}°", xs[i], ys[i] - dpf(9f), tempP)
            }
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            Weather.get(context) { r -> post { chart.update(r?.hours ?: emptyList()) } }
            hd.postDelayed(this, 10 * 60_000L)
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(chart, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, totalH.toInt()))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hd.post(refresh)
    }

    override fun onDetachedFromWindow() {
        hd.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
