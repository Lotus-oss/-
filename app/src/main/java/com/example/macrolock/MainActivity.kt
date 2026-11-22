package com.example.macrolock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

    private fun pickApps(sp: SharedPreferences) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) { toast("앱 목록을 불러오지 못했습니다"); return }
        val sel = (sp.getStringSet("al_pkgs", emptySet()) ?: emptySet()).toMutableSet()
        val names = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in sel }
        AlertDialog.Builder(this)
            .setTitle("잠글 앱 선택")
            .setMultiChoiceItems(names, checked) { _, i, c ->
                if (c) sel.add(apps[i].first) else sel.remove(apps[i].first)
            }
            .setPositiveButton("저장") { _, _ ->
                sp.edit().putStringSet("al_pkgs", HashSet(sel)).apply()
                build()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun build() {
        val dp = resources.displayMetrics.density
        val p = (20 * dp).toInt()
        val sp = getSharedPreferences("lock", Context.MODE_PRIVATE)
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
        root.addView(label("2. 자동 클릭·화면 캡처·앱 잠금(접근성): " + if (acc) "켜짐 ✅" else "꺼짐 ❌"))
        if (!acc) {
            root.addView(label("※ 안 켜지면: 설정 → 앱 → 이 앱 → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 시도"))
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

        val savedKey = sp.getString("gkey", "") ?: ""
        root.addView(label("4. AI API 키: " +
            if (savedKey.isNotBlank()) "저장됨 ✅ (끝 4자리 ${savedKey.takeLast(4)})" else "미설정 ❌"))
        val keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "API 키 붙여넣기 (비우면 기존 키 유지)"
        }
        root.addView(keyInput)
        root.addView(label("모델 이름 (오류 나면 바꿔보세요)"))
        val modelInput = EditText(this).apply {
            setText(sp.getString("gmodel", "qwen/qwen3.8-27b"))
        }
        root.addView(modelInput)
        root.addView(btn("AI 설정 저장") {
            val k = keyInput.text.toString().trim()
            val m = modelInput.text.toString().trim().ifEmpty { "qwen/qwen3.8-27b" }
            val e = sp.edit().putString("gmodel", m)
            if (k.isNotEmpty()) e.putString("gkey", k)
            e.apply()
            toast("저장됨")
            build()
        })

        val lockedCount = (sp.getStringSet("al_pkgs", emptySet()) ?: emptySet()).size
        root.addView(label("5. 앱 잠금 (선택한 앱을 열 때 PIN 요구)"))
        root.addView(Switch(this).apply {
            text = "앱 잠금 사용"; textSize = 16f
            isChecked = sp.getBoolean("al_on", false)
            setOnCheckedChangeListener { _, on -> sp.edit().putBoolean("al_on", on).apply() }
        })
        root.addView(btn("잠글 앱 선택 (${lockedCount}개)") { pickApps(sp) })
        if (!acc) root.addView(label("※ 앱 잠금은 접근성이 켜져 있어야 작동합니다"))

        root.addView(label("6. 화면에 아이콘 띄우기\n아이콘을 누르면 AI 검색·풀이, 클릭 위치 지정, 잠금을 할 수 있습니다."))
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
