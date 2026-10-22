package com.fxsimulator.data.repository

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fxsimulator.data.ads.AdsRewardPolicy
import com.fxsimulator.data.db.GoldLabDatabase

/** 某一天的广告状态 */
data class AdDayState(
    val dayKey: String,
    val watched: Int = 0,
    val granted: Double = 0.0,
) {
    val interstitialFree: Boolean get() = AdsRewardPolicy.isInterstitialFree(watched)
    val remainingRewardCap: Double get() = maxOf(0.0, AdsRewardPolicy.DAILY_REWARD_CAP - granted)
}

/**
 * 广告每日状态的本地持久化。
 * 奖励只写这里与账户余额，**绝不写入 trades 表**，因此不会计入交易利润与统计。
 */
class AdStateRepository(private val db: GoldLabDatabase) {

    fun load(dayKey: String): AdDayState {
        db.readableDatabase.rawQuery(
            "SELECT watched, granted FROM ad_state WHERE day_key = ?", arrayOf(dayKey),
        ).use { c ->
            return if (c.moveToFirst()) AdDayState(dayKey, c.getInt(0), c.getDouble(1))
            else AdDayState(dayKey)
        }
    }

    /**
     * 记录一次完整观看并返回实际发放的奖励金额。
     * 达到每日上限时返回 0（不发奖）。
     */
    fun recordCompletedView(dayKey: String, timestamp: Long): AdDayState {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            val cur = load(dayKey)
            val reward = AdsRewardPolicy.nextReward(cur.watched, cur.granted)
            val next = AdDayState(dayKey, cur.watched + 1, cur.granted + reward)
            val cv = ContentValues().apply {
                put("day_key", dayKey)
                put("watched", next.watched)
                put("granted", next.granted)
                put("updated_at", timestamp)
            }
            d.insertWithOnConflict("ad_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            d.setTransactionSuccessful()
            return next
        } finally {
            d.endTransaction()
        }
    }

    fun reset(dayKey: String) {
        db.writableDatabase.delete("ad_state", "day_key = ?", arrayOf(dayKey))
    }

    fun resetAll() {
        db.writableDatabase.delete("ad_state", null, null)
    }
}
