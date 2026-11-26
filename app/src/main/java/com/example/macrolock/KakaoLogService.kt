package com.example.macrolock

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import java.security.MessageDigest

private class Msg(val text: String, val time: Long, val sender: String, val bundle: Bundle?)

class KakaoLogService : NotificationListenerService() {
    private val kakao = "com.kakao.talk"
    private val deleted = "삭제된 메시지"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != kakao) return
        try { handle(sbn) } catch (_: Throwable) {}
    }

    private fun key(room: String, sender: String, t: Long, text: String): String =
        MessageDigest.getInstance("SHA-1").digest("$room|$sender|$t|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        val group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val room = (if (conv.isNotEmpty()) conv else title).ifEmpty { "(알 수 없음)" }
        val db = KakaoDb.get(this)

        val msgs = ArrayList<Msg>()
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr != null && arr.isNotEmpty()) {
            for (p in arr) {
                val b = p as? Bundle ?: continue
                val t = b.getCharSequence("text")?.toString()?.trim() ?: continue
                if (t.isEmpty()) continue
                val s = b.getCharSequence("sender")?.toString()?.trim() ?: ""
                msgs.add(Msg(t, b.getLong("time", 0L), s.ifEmpty { title.ifEmpty { room } }, b))
            }
        } else {
            var t = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            if (t.isEmpty()) return
            var s = title.ifEmpty { room }
            if (group && t.contains(" : ")) {
                s = t.substringBefore(" : ").trim().ifEmpty { s }
                t = t.substringAfter(" : ").trim()
            }
            msgs.add(Msg(t, 0L, s, null))
        }

        for ((i, m) in msgs.withIndex()) {
            val ts = if (m.time > 0) m.time else sbn.postTime
            val bucket = if (m.time > 0) ts else ts / 60000

            if (m.text.startsWith(deleted)) {
                // 새로 들어온 삭제 알림일 때만 처리 (같은 알림이 다시 올라와도 한 번만)
                val k = key(room, m.sender, bucket, deleted)
                if (db.insertHidden(k, room, m.sender, ts)) {
                    val hit = m.time > 0 && db.markDeletedAt(room, m.sender, m.time)
                    if (!hit) db.markLatestDeleted(room, m.sender)
                }
                continue
            }

            val k = key(room, m.sender, bucket, m.text)
            if (db.exists(k)) continue
            val img = grabImage(ex, m.bundle, i == msgs.size - 1, k)
            db.insert(k, room, m.sender, m.text, ts, img)
        }
    }

    // ---------- 사진 (알림에 들어 있을 때만) ----------
    private fun grabImage(ex: Bundle, b: Bundle?, isLast: Boolean, k: String): String? {
        var bmp: Bitmap? = null
        if (b != null) bmp = fromUri(b)
        if (bmp == null && isLast) bmp = fromExtras(ex)
        return bmp?.let { save(it, k) }
    }

    @Suppress("DEPRECATION")
    private fun fromUri(b: Bundle): Bitmap? {
        val raw = b.get("uri")
        val u: Uri = when (raw) {
            is Uri -> raw
            is String -> Uri.parse(raw)
            else -> return null
        }
        val type = b.getString("type") ?: ""
        if (type.isNotEmpty() && !type.startsWith("image")) return null
        return try {
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) { null }
    }

    @Suppress("DEPRECATION")
    private fun fromExtras(ex: Bundle): Bitmap? {
        val p = ex.get(Notification.EXTRA_PICTURE)
        if (p is Bitmap) return soft(p)
        if (Build.VERSION.SDK_INT >= 31) {
            val ic = ex.get("android.pictureIcon")
            if (ic is Icon) {
                val d = try { ic.loadDrawable(this) } catch (_: Throwable) { null } ?: return null
                return drawableBmp(d)
            }
        }
        return null
    }

    private fun soft(b: Bitmap): Bitmap? =
        if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b

    private fun drawableBmp(d: Drawable): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) return soft(d.bitmap)
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun save(src: Bitmap, k: String): String? = try {
        val dir = KakaoDb.get(this).imgDir()
        val f = File(dir, "$k.jpg")
        val scale = 1280f / maxOf(src.width, src.height)
        val b = if (scale < 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        else src
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        f.name
    } catch (_: Throwable) { null }
}
