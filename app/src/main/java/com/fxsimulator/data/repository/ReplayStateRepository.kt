package com.fxsimulator.data.repository

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fxsimulator.data.db.GoldLabDatabase

/** 一个来源的存档条目 */
data class ReplayBookmark(
    val sourceKey: String,
    val sourceName: String?,
    val barIndex: Int,
    val speed: Int,
    val updatedAt: Long,
)

/**
 * 回放存档。
 *
 * **按来源分别记一份**：你有 134MB 的整段 CSV，也有按月拆分的文件。
 * 在整段里回放到第 80 万根、再切到 2025-01 那个小文件，两边的位置应该是各自的，
 * 而不是互相覆盖。
 */
class ReplayStateRepository(private val db: GoldLabDatabase) {

    /**
     * 读取某个来源的存档。
     *
     * 没有存档时返回 null，而**不是返回一个 0 索引的假存档** ——
     * 否则无法区分「从没存过」与「存在第 0 根」，
     * 界面就会对着一个从没用过的新文件显示「上次回放到开头」。
     */
    fun load(sourceKey: String): ReplayProgress? {
        db.readableDatabase.rawQuery(
            "SELECT source_name, bar_index, speed FROM replay_bookmarks WHERE source_key = ?",
            arrayOf(sourceKey),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ReplayProgress(
                sourceId = sourceKey,
                sourceName = c.getString(0),
                barIndex = c.getInt(1),
                speed = c.getInt(2),
            )
        }
    }

    fun save(sourceKey: String, name: String?, barIndex: Int, speed: Int) {
        val cv = ContentValues().apply {
            put("source_key", sourceKey)
            put("source_name", name)
            put("bar_index", barIndex)
            put("speed", speed)
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "replay_bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clear(sourceKey: String) {
        db.writableDatabase.delete("replay_bookmarks", "source_key = ?", arrayOf(sourceKey))
    }

    fun clearAll() {
        db.writableDatabase.delete("replay_bookmarks", null, null)
    }

    fun count(): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM replay_bookmarks", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** 全部存档，最近更新的在前 */
    fun listAll(limit: Int = 20): List<ReplayBookmark> {
        val out = ArrayList<ReplayBookmark>()
        db.readableDatabase.rawQuery(
            "SELECT source_key, source_name, bar_index, speed, updated_at " +
                "FROM replay_bookmarks ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ReplayBookmark(
                        sourceKey = c.getString(0),
                        sourceName = c.getString(1),
                        barIndex = c.getInt(2),
                        speed = c.getInt(3),
                        updatedAt = c.getLong(4),
                    ),
                )
            }
        }
        return out
    }
}
