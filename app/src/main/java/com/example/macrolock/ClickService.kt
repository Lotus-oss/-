package com.example.macrolock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent

class ClickService : AccessibilityService() {
    companion object { var instance: ClickService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun click(x: Float, y: Float, done: (() -> Unit)? = null) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 40)).build()
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done?.invoke() }
            override fun onCancelled(d: GestureDescription?) { done?.invoke() }
        }
        val ok = dispatchGesture(g, cb, null)
        if (!ok) done?.invoke()
    }

    fun screenshot(cb: (Bitmap?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            cb(null, "화면 캡처는 안드로이드 11 이상에서만 가능합니다")
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        r.hardwareBuffer.close()
                        if (bmp == null) cb(null, "캡처 변환에 실패했습니다") else cb(bmp, null)
                    } catch (e: Exception) {
                        cb(null, "캡처 오류: ${e.message}")
                    }
                }
                override fun onFailure(code: Int) {
                    cb(null, "캡처 실패 (코드 $code). 잠시 후 다시 눌러보세요")
                }
            })
    }
}
