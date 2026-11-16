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
import android.widget.LinearLayout
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
            setPadding(p, p * 3, p, p)
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

        root.addView(label("2. 비밀번호: " + if (Pw.isSet(this)) "설정됨 ✅" else "미설정 ❌"))
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "새 비밀번호 (4자 이상)"
        }
        root.addView(input)
        root.addView(btn("비밀번호 저장") {
            val s = input.text.toString()
            if (s.length < 4) toast("4자 이상 입력하세요")
            else { Pw.save(this, s); toast("저장됨"); build() }
        })

        root.addView(label("3. 게임 실행 → 매크로 시작 후 잠금\n(누르면 홈으로 나가고 10초 뒤 화면이 잠깁니다)\n해제: 화면 오른쪽 아래 구석을 2초 안에 5번 탭"))
        root.addView(btn("10초 뒤 잠금 시작") {
            if (!Settings.canDrawOverlays(this)) toast("권한을 먼저 허용하세요")
            else if (!Pw.isSet(this)) toast("비밀번호를 먼저 저장하세요")
            else {
                startForegroundService(Intent(this, LockService::class.java))
                toast("10초 뒤 잠깁니다. 게임을 실행하세요")
                moveTaskToBack(true)
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
