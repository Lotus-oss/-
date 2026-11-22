package com.example.macrolock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
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
    private val GOLD = 0xFFE8B84A.toInt()
    private val NAVY = 0xFF0B1220.toInt()
    private val CREAM = 0xFFF3E7C9.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = NAVY
        window.navigationBarColor = NAVY
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); build() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun decodeRes(id: Int): Bitmap? = try {
        BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })
    } catch (_: Throwable) { null }

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
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
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
        val density = resources.displayMetrics.density
        fun d(v: Int) = (v * density).toInt()
        val sp = getSharedPreferences("lock", Context.MODE_PRIVATE)

        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(12), d(16), d(14))
            background = GradientDrawable().apply {
                setColor(0xB30B1220.toInt()); setStroke(d(1), 0x55E8B84A); cornerRadius = d(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = d(12) }
        }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(CREAM); setPadding(0, d(6), 0, d(6))
        }
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextColor(NAVY); typeface = Typeface.DEFAULT_BOLD
            background = RippleDrawable(
                ColorStateList.valueOf(0x55FFFFFF),
                GradientDrawable().apply { setColor(GOLD); cornerRadius = d(12).toFloat() }, null)
            setOnClickListener { a() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(6) }
        }
        fun field(hintText: String, inputT: Int) = EditText(this).apply {
            inputType = inputT
            hint = hintText
            setTextColor(Color.WHITE); setHintTextColor(0xFF8A93A6.toInt())
            setPadding(d(12), d(10), d(12), d(10))
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF); setStroke(d(1), 0x66E8B84A); cornerRadius = d(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(6) }
        }

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 상단 배너
        val title = TextView(this).apply {
            text = applicationInfo.loadLabel(packageManager).toString()
            textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val bannerId = resources.getIdentifier("lock_bg1", "drawable", packageName)
        val bannerBmp = if (bannerId != 0) decodeRes(bannerId) else null
        if (bannerBmp != null) {
            val banner = FrameLayout(this)
            banner.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bannerBmp)
                setColorFilter(0x66000000, PorterDuff.Mode.SRC_ATOP)
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            title.setPadding(d(20), 0, d(20), d(14))
            banner.addView(title, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
            page.addView(banner, LinearLayout.LayoutParams(MATCH, d(170)))
        } else {
            title.setPadding(d(20), d(40), d(20), d(10))
            page.addView(title)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(16), d(16), d(24))
        }
        page.addView(body)

        // 1. 오버레이
        val canDraw = Settings.canDrawOverlays(this)
        card().also { c ->
            c.addView(label("1. 오버레이 권한: " + if (canDraw) "허용됨 ✅" else "필요 ❌"))
            if (!canDraw) c.addView(btn("권한 허용하러 가기") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            })
            body.addView(c)
        }

        // 2. 접근성
        val acc = ClickService.instance != null
        card().also { c ->
            c.addView(label("2. 자동 클릭·화면 캡처·앱 잠금(접근성): " + if (acc) "켜짐 ✅" else "꺼짐 ❌"))
            if (!acc) {
                c.addView(label("※ 안 켜지면: 설정 → 앱 → 이 앱 → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 시도"))
                c.addView(btn("접근성 설정 열기") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                })
            }
            body.addView(c)
        }

        // 3. PIN
        card().also { c ->
            c.addView(label("3. PIN(숫자 4자리 이상): " + if (Pw.isSet(this)) "설정됨 ✅" else "미설정 ❌"))
            val input = field("새 PIN (숫자만)",
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            c.addView(input)
            c.addView(btn("PIN 저장") {
                val s = input.text.toString()
                if (s.length < 4 || !s.all { it.isDigit() }) toast("숫자 4자리 이상 입력하세요")
                else { Pw.save(this, s); toast("저장됨"); build() }
            })
            body.addView(c)
        }

        // 4. AI
        val savedKey = sp.getString("gkey", "") ?: ""
        card().also { c ->
            c.addView(label("4. AI API 키: " +
                if (savedKey.isNotBlank()) "저장됨 ✅ (끝 4자리 ${savedKey.takeLast(4)})" else "미설정 ❌"))
            val keyInput = field("API 키 붙여넣기 (비우면 기존 키 유지)",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            c.addView(keyInput)
            c.addView(label("모델 이름 (오류 나면 바꿔보세요)"))
            val modelInput = field("모델 이름", InputType.TYPE_CLASS_TEXT).apply {
                setText(sp.getString("gmodel", "qwen/qwen3.8-27b"))
            }
            c.addView(modelInput)
            c.addView(btn("AI 설정 저장") {
                val k = keyInput.text.toString().trim()
                val m = modelInput.text.toString().trim().ifEmpty { "qwen/qwen3.8-27b" }
                val e = sp.edit().putString("gmodel", m)
                if (k.isNotEmpty()) e.putString("gkey", k)
                e.apply()
                toast("저장됨")
                build()
            })
            body.addView(c)
        }

        // 5. 앱 잠금
        val lockedCount = (sp.getStringSet("al_pkgs", emptySet()) ?: emptySet()).size
        card().also { c ->
            c.addView(label("5. 앱 잠금 (선택한 앱을 열 때 PIN 요구)"))
            c.addView(Switch(this).apply {
                text = "앱 잠금 사용"; textSize = 16f; setTextColor(CREAM)
                isChecked = sp.getBoolean("al_on", false)
                setOnCheckedChangeListener { _, on -> sp.edit().putBoolean("al_on", on).apply() }
            })
            c.addView(btn("잠글 앱 선택 (${lockedCount}개)") { pickApps(sp) })
            if (!acc) c.addView(label("※ 앱 잠금은 접근성이 켜져 있어야 작동합니다"))
            body.addView(c)
        }

        // 6. 패널
        card().also { c ->
            c.addView(label("6. 화면에 아이콘 띄우기\n아이콘을 누르면 AI 검색·풀이, 클릭 위치 지정, 잠금을 할 수 있습니다."))
            c.addView(btn("패널 열기") {
                if (!Settings.canDrawOverlays(this)) toast("오버레이 권한을 먼저 허용하세요")
                else {
                    startForegroundService(Intent(this, LockService::class.java))
                    moveTaskToBack(true)
                }
            })
            body.addView(c)
        }

        setContentView(ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0B1220.toInt(), 0xFF16213A.toInt(), 0xFF3A2E14.toInt()))
            isFillViewport = true
            addView(page)
        })
    }
}
