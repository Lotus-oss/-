package com.example.macrolock

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class KakaoDb private constructor(private val appCtx: Context) :
    SQLiteOpenHelper(appCtx, "kakao.db", null, 1) {

    class Row(
        val id: Long, val room: String, val sender: String, val body: String,
        val ts: Long, val img: String?, val del: Boolean
    )

    companion object {
        @Volatile private var inst: KakaoDb? = null
        fun get(c: Context): KakaoDb = inst ?: synchronized(this) {
            inst ?: KakaoDb(c.applicationContext).also { inst = it }
        }
    }

    fun imgDir(): File = File(appCtx.filesDir, "kakao_img").apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT UNIQUE, " +
            "room TEXT, sender TEXT, body TEXT, ts INTEGER, img TEXT, del INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_ts ON msgs(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun exists(k: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM msgs WHERE k=? LIMIT 1", arrayOf(k))
            .use { it.moveToFirst() }

    fun insert(k: String, room: String, sender: String, body: String, ts: Long, img: String?): Boolean {
        val v = ContentValues().apply {
            put("k", k); put("room", room); put("sender", sender)
            put("body", body); put("ts", ts); put("img", img); put("del", 0)
        }
        return writableDatabase.insertWithOnConflict("msgs", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** "삭제된 메시지입니다" 알림을 한 번만 처리하기 위한 숨김 기록 */
    fun insertHidden(k: String, room: String, sender: String, ts: Long): Boolean {
        val v = ContentValues().apply {
            put("k", k); put("room", room); put("sender", sender)
            put("body", ""); put("ts", ts); put("del", 2)
        }
        return writableDatabase.insertWithOnConflict("msgs", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun markDeletedAt(room: String, sender: String, ts: Long): Boolean {
        val v = ContentValues().apply { put("del", 1) }
        return writableDatabase.update("msgs", v,
            "room=? AND sender=? AND del=0 AND ABS(ts - CAST(? AS INTEGER)) <= 2000",
            arrayOf(room, sender, ts.toString())) > 0
    }

    fun markLatestDeleted(room: String, sender: String) {
        val db = writableDatabase
        val since = (System.currentTimeMillis() - 25L * 3600 * 1000).toString()
        val id = db.rawQuery(
            "SELECT id FROM msgs WHERE room=? AND sender=? AND del=0 AND ts > CAST(? AS INTEGER) " +
                "ORDER BY ts DESC LIMIT 1", arrayOf(room, sender, since)
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        if (id > 0) db.update("msgs", ContentValues().apply { put("del", 1) }, "id=?", arrayOf(id.toString()))
    }

    fun query(onlyDeleted: Boolean, q: String, limit: Int): List<Row> {
        val sb = StringBuilder("SELECT id, room, sender, body, ts, img, del FROM msgs WHERE ")
        val args = ArrayList<String>()
        sb.append(if (onlyDeleted) "del=1" else "del<2")
        if (q.isNotEmpty()) {
            sb.append(" AND (body LIKE ? OR room LIKE ? OR sender LIKE ?)")
            val l = "%$q%"
            args.add(l); args.add(l); args.add(l)
        }
        sb.append(" ORDER BY ts DESC LIMIT $limit")
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(Row(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    c.getString(3) ?: "", c.getLong(4), c.getString(5), c.getInt(6) == 1))
            }
        }
        return out
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM msgs WHERE del<2", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun countDeleted(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM msgs WHERE del=1", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 지우기: 같은 알림이 다시 와도 되살아나지 않게 기록(키)은 남기고 내용만 비웁니다 */
    fun remove(id: Long) {
        val db = writableDatabase
        val img = db.rawQuery("SELECT img FROM msgs WHERE id=?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (img != null) File(imgDir(), img).delete()
        val v = ContentValues().apply { put("del", 2); put("body", ""); putNull("img") }
        db.update("msgs", v, "id=?", arrayOf(id.toString()))
    }

    fun removeAll() {
        imgDir().listFiles()?.forEach { it.delete() }
        val v = ContentValues().apply { put("del", 2); put("body", ""); putNull("img") }
        writableDatabase.update("msgs", v, "del<2", null)
    }
    }
