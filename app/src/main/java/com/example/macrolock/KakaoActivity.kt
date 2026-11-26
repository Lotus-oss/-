package com.example.macrolock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KakaoActivity : Activity() {
    private val GOLD = 0xFFE8B84A.toInt()
    private val NAVY = 0xFF0B1220.toInt()
    private val CREAM = 0xFFF3E7C9.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // true면 이 페이지는 화면 캡처·최근 앱 미리보기에서 가려집니다. 캡처하고 싶으면 false
    private val SECURE_PAGE = true

    private var unlocked = false
    private var onlyDeleted = false
    private var query = ""
    private var limit = 100
    private var sv: ScrollView? = null
    private val timeFmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun shape(color: Int, r: Int, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r.toFloat()
        if (stroke > 0) setStroke(stroke, sc)
    }

    private fun pageBg() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF0B1220.toInt(), 0xFF16213A.toInt(), 0xFF3A2E14.toInt()))

    private fun tv(t: String, sp: Float = 16f, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            text = t; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun btn(t: String, a: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(NAVY); typeface = Typeface.DEFAULT_BOLD
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), shape(GOLD, d(12)), null)
        setOnClickListener { a() }
    }

    private fun chip(t: String, active: Boolean, a: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        setTextColor(if (active) NAVY else CREAM)
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF),
            if (active) shape(GOLD, d(18)) else shape(0x33FFFFFF, d(18), d(1), 0x66E8B84A), null)
        setOnClickListener { a() }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(14), d(10), d(14), d(12))
        background = shape(0xB30B1220.toInt(), d(16), d(1), 0x55E8B84A)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = d(10) }
    }

    private fun notifEnabled() =
        (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "")
            .contains(packageName)

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setMessage(msg)
            .setPositiveButton("지우기") { _, _ -> ok() }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = NAVY
        window.navigationBarColor = NAVY
        if (SECURE_PAGE) window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        if (unlocked) showContent() else showPin()
    }

    override fun onStop() {
        super.onStop()
        unlocked = false
    }

    // ---------- PIN ----------
    private fun showPin() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(d(24), d(24), d(24), d(24))
            background = pageBg()
        }
        if (!Pw.isSet(this)) {
            root.addView(tv("PIN이 아직 없습니다.\n설정 페이지에서 먼저 PIN을 저장하세요.", 18f).apply {
                gravity = Gravity.CENTER; setPadding(0, 0, 0, d(16))
            })
            root.addView(btn("← 설정으로") { finish() })
            setContentView(root)
            return
        }
        root.addView(tv("🔒 카톡 보관함", 24f, true).apply { gravity = Gravity.CENTER })
        val disp = tv("PIN 입력", 26f).apply {
            gravity = Gravity.CENTER; setPadding(0, d(12), 0, d(12))
        }
        root.addView(disp)
        val pin = StringBuilder()
        fun refresh() { disp.text = if (pin.isEmpty()) "PIN 입력" else "●".repeat(pin.length) }
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"),
            listOf("7", "8", "9"), listOf("지움", "0", "확인"))
        for (r in rows) {
            val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) {
                lr.addView(btn(k) {
                    when (k) {
                        "지움" -> { if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1); refresh() }
                        "확인" -> {
                            if (Pw.check(this, pin.toString())) { unlocked = true; showContent() }
                            else { pin.clear(); refresh(); toast("PIN이 틀렸습니다") }
                        }
                        else -> { if (pin.length < 12) pin.append(k); refresh() }
                    }
                }.apply {
                    textSize = 20f
                    layoutParams = LinearLayout.LayoutParams(0, d(60), 1f).apply {
                        setMargins(d(3), d(3), d(3), d(3))
                    }
                })
            }
            pad.addView(lr)
        }
        root.addView(pad, LinearLayout.LayoutParams(d(300), WRAP))
        root.addView(btn("← 설정으로") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(d(300), WRAP).apply { topMargin = d(12) }
        })
        setContentView(root)
    }

    // ---------- 목록 ----------
    private fun showContent() {
        val db = KakaoDb.get(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(16), d(16), d(24))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(btn("← 설정") { finish() })
        head.addView(tv("  💬 카톡 보관함", 20f, true))
        page.addView(head)
        page.addView(tv("", 6f))

        val st = card()
        if (notifEnabled()) {
            st.addView(tv("저장 중 ✅  ·  저장 ${db.count()}개  ·  삭제 표시 ${db.countDeleted()}개", 15f, false, CREAM))
        } else {
            st.addView(tv("알림 접근이 꺼져 있어 저장되지 않습니다 ❌", 15f, false, CREAM))
            st.addView(btn("알림 접근 설정 열기") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        }
        page.addView(st)

        val fl = card()
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(chip("전체", !onlyDeleted) { onlyDeleted = false; limit = 100; showContent() })
        chips.addView(tv("  ", 10f))
        chips.addView(chip("삭제된 것만", onlyDeleted) { onlyDeleted = true; limit = 100; showContent() })
        fl.addView(chips)
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "검색 (이름·내용)"
            setText(query)
            setTextColor(Color.WHITE); setHintTextColor(0xFF8A93A6.toInt())
            setPadding(d(12), d(10), d(12), d(10))
            background = shape(0x33FFFFFF, d(10), d(1), 0x66E8B84A)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(8) }
        }
        fl.addView(et)
        fl.addView(btn("검색") { query = et.text.toString().trim(); limit = 100; showContent() })
        fl.addView(btn("전체 지우기") {
            confirm("저장된 메시지와 사진을 모두 지울까요?") { db.removeAll(); showContent() }
        })
        page.addView(fl)

        val rows = db.query(onlyDeleted, query, limit)
        if (rows.isEmpty()) {
            page.addView(tv("저장된 메시지가 없습니다.\n알림 접근을 켜고 카톡 메시지를 받으면 여기에 쌓입니다.", 15f, false, CREAM))
        }
        for (r in rows) page.addView(rowView(r, db))
        if (rows.size >= limit) {
            page.addView(btn("더 보기") {
                val y = sv?.scrollY ?: 0
                limit += 100
                showContent()
                sv?.post { sv?.scrollTo(0, y) }
            })
        }

        val scroll = ScrollView(this).apply {
            background = pageBg()
            isFillViewport = true
            addView(page)
        }
        sv = scroll
        setContentView(scroll)
    }

    private fun rowView(r: KakaoDb.Row, db: KakaoDb): LinearLayout {
        val c = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val who = if (r.sender == r.room) r.room else "${r.room} · ${r.sender}"
        head.addView(tv("$who   ${timeFmt.format(Date(r.ts))}", 12f, false, 0xFFC9BC9A.toInt()),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(tv("✕", 16f, true, 0xFFC9BC9A.toInt()).apply {
            setPadding(d(12), d(4), d(4), d(4))
            setOnClickListener { confirm("이 메시지를 지울까요?") { db.remove(r.id); showContent() } }
        })
        c.addView(head)
        if (r.del) c.addView(tv("🗑 삭제된 것으로 보임", 12f, true, 0xFFFF8A80.toInt()))
        c.addView(tv(r.body, 16f).apply {
            setTextIsSelectable(true); setPadding(0, d(4), 0, 0)
        })
        val name = r.img
        if (name != null) {
            val bmp = loadImg(name, 720)
            if (bmp != null) {
                c.addView(ImageView(this).apply {
                    adjustViewBounds = true
                    maxHeight = d(320)
                    scaleType = ImageView.ScaleType.FIT_START
                    setImageBitmap(bmp)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = d(8) }
                    setOnClickListener { showImage(name) }
                })
            }
        }
        return c
    }

    private fun loadImg(name: String, maxSide: Int): Bitmap? {
        val f = File(KakaoDb.get(this).imgDir(), name)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / s > maxSide * 2 || o.outHeight / s > maxSide * 2) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun showImage(name: String) {
        val bmp = loadImg(name, 2048) ?: return
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(bmp)
        }
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setView(iv)
            .setPositiveButton("닫기", null)
            .show()
    }
}
