package com.fxsimulator.data.repository

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fxsimulator.data.db.GoldLabDatabase
import com.fxsimulator.domain.guess.GuessStats

/** 猜涨跌的初始虚拟权益（美元）。独立于模拟账户，避免两种玩法互相干扰。 */
const val GUESS_INITIAL_EQUITY = 10_000.0

/**
 * 猜涨跌战绩的持久化。单行表。
 *
 * 存档的意义：这个玩法想让人看到的是**长期**行为，而不是某一局的手气。
 * 因此每答完一局立刻落盘 —— 中途退出、进程被杀、设备重启都不会丢掉已经玩出来的数据。
 */
class GuessStatsRepository(private val db: GoldLabDatabase) {

    /** 读取战绩。没有记录时返回一个干净的初始战绩，而不是空值。 */
    fun load(defaultEquity: Double = GUESS_INITIAL_EQUITY): GuessStats {
        db.readableDatabase.rawQuery(
            "SELECT total, correct, streak, best_streak, equity, initial_equity, " +
                "sum_win_pct, sum_loss_pct, wins, losses, sum_win_bars, sum_loss_bars, " +
                "manual_closes, timeout_closes FROM guess_stats WHERE id = 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) {
                return GuessStats(equity = defaultEquity, initialEquity = defaultEquity)
            }
            val initial = c.getDouble(5)
            return GuessStats(
                total = c.getInt(0),
                correct = c.getInt(1),
                streak = c.getInt(2),
                bestStreak = c.getInt(3),
                equity = c.getDouble(4),
                initialEquity = if (initial > 0.0) initial else defaultEquity,
                sumWinPct = c.getDouble(6),
                sumLossPct = c.getDouble(7),
                wins = c.getInt(8),
                losses = c.getInt(9),
                sumWinBars = c.getInt(10),
                sumLossBars = c.getInt(11),
                manualCloses = c.getInt(12),
                timeoutCloses = c.getInt(13),
            )
        }
    }

    fun save(stats: GuessStats) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("total", stats.total)
            put("correct", stats.correct)
            put("streak", stats.streak)
            put("best_streak", stats.bestStreak)
            put("equity", stats.equity)
            put("initial_equity", stats.initialEquity)
            put("sum_win_pct", stats.sumWinPct)
            put("sum_loss_pct", stats.sumLossPct)
            put("wins", stats.wins)
            put("losses", stats.losses)
            put("sum_win_bars", stats.sumWinBars)
            put("sum_loss_bars", stats.sumLossBars)
            put("manual_closes", stats.manualCloses)
            put("timeout_closes", stats.timeoutCloses)
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "guess_stats", null, cv, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 清空战绩，回到初始权益 */
    fun clear() {
        db.writableDatabase.delete("guess_stats", null, null)
    }
}
