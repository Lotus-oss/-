package com.example.macrolock

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import java.security.MessageDigest

object Pw {
    private fun prefs(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256")
        .digest("macrolock:$s".toByteArray()).joinToString("") { "%02x".format(it) }
    fun isSet(c: Context) = prefs(c).contains("h")
    fun save(c: Context, p: String) = prefs(c).edit().putString("h", hash(p)).apply()
    fun check(c: Context, p: String) = prefs(c).getString("h", null) == hash(p)
}

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); build() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun build() {
        val dp = resources.displayMetrics.density
        val p = (20 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p * 2, p, p)
        }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 16f; setPadding(0, p / 2, 0, p / 2)
        }
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { a() }
        }

        val canDraw = Settings.canDrawOverlays(this)
        root.addView(label("1. 오버레이 권한: " + if (canDraw) "허용됨 ✅" else "필요 ❌"))
        if (!canDraw) root.addView(btn("권한 허용하러 가기") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        })

        val acc = ClickService.instance != null
        root.addView(label("2. 자동 클릭(접근성): " + if (acc) "켜짐 ✅" else "꺼짐 ❌"))
        if (!acc) {
            root.addView(label("※ 안 켜지면: 설정 → 앱 → MacroLock → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 시도"))
            root.addView(btn("접근성 설정 열기") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }

        root.addView(label("3. PIN(숫자 4자리 이상): " + if (Pw.isSet(this)) "설정됨 ✅" else "미설정 ❌"))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "새 PIN (숫자만)"
        }
        root.addView(input)
        root.addView(btn("PIN 저장") {
            val s = input.text.toString()
            if (s.length < 4 || !s.all { it.isDigit() }) toast("숫자 4자리 이상 입력하세요")
            else { Pw.save(this, s); toast("저장됨"); build() }
        })

        root.addView(label("4. 화면에 패널 띄우기\n패널에서 클릭 위치 지정, 간격 설정, 클릭 시작, 잠금을 모두 할 수 있습니다."))
        root.addView(btn("패널 열기") {
            if (!Settings.canDrawOverlays(this)) toast("오버레이 권한을 먼저 허용하세요")
            else {
                startForegroundService(Intent(this, LockService::class.java))
                moveTaskToBack(true)
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
