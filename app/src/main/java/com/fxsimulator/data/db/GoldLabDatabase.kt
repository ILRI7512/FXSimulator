package com.fxsimulator.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite 持久化。
 *
 * 第一版使用原生 SQLiteOpenHelper 而不是 Room：依赖更少、构建更快，
 * 同时完全满足「保存模拟账户 / 行情位置 / 持仓 / 订单 / 设置」的需求。
 *
 * 关键交易操作（开仓、平仓、账户变更）都在事务中完成，保证
 * 应用异常退出、进程被杀、设备重启后数据一致。
 */
class GoldLabDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE account (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                balance REAL NOT NULL,
                initial_balance REAL NOT NULL,
                last_bid REAL NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE trades (
                order_id INTEGER PRIMARY KEY,
                symbol TEXT NOT NULL,
                direction TEXT NOT NULL,
                open_time INTEGER NOT NULL,
                close_time INTEGER,
                open_price REAL NOT NULL,
                close_price REAL,
                volume REAL NOT NULL,
                stop_loss REAL,
                take_profit REAL,
                gross_profit REAL NOT NULL DEFAULT 0,
                commission REAL NOT NULL DEFAULT 0,
                swap REAL NOT NULL DEFAULT 0,
                net_profit REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                close_reason TEXT,
                fill_note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_trades_status ON trades(status)")
        db.execSQL("CREATE INDEX idx_trades_close_time ON trades(close_time)")
        db.execSQL(
            """
            CREATE TABLE replay_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                source_id TEXT,
                source_name TEXT,
                bar_index INTEGER NOT NULL DEFAULT 0,
                speed INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE ad_state (
                day_key TEXT PRIMARY KEY,
                watched INTEGER NOT NULL DEFAULT 0,
                granted REAL NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(CREATE_REPLAY_BOOKMARKS)
        db.execSQL(
            """
            CREATE TABLE daily_results (
                day_key TEXT PRIMARY KEY,
                realized REAL NOT NULL DEFAULT 0,
                trades INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(CREATE_GUESS_STATS)
    }

    /**
     * 迁移**只允许做可加性变更**（建新表、加新列、回填数据）。
     *
     * 旧版本这里把 account / trades / settings / ad_state / daily_results
     * 全部 DROP 再重建 —— 只要版本号加一，用户升级一次就会丢光交易历史和全部设置。
     * 那是数据损坏，不是迁移。绝不再那么做。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 → v2：新增按来源分别记录的存档表。老表一律不动。
            db.execSQL(CREATE_REPLAY_BOOKMARKS)
        }
        if (oldVersion < 3) {
            // v2 → v3：新增「猜涨跌」战绩表。同样只加表，不动任何老数据。
            db.execSQL(CREATE_GUESS_STATS)
        }
    }

    /**
     * **降级保护**。
     *
     * 量化版（v4/v5）曾在库里建过 `strategies` / `backtest_runs` 两张表。
     * 现在版本号回到 3，装过量化版的用户会触发降级 ——
     * SQLiteOpenHelper 默认在 onDowngrade 里抛异常，那会让 App 一启动就崩。
     *
     * 这里改成"什么都不做"：多出来的两张空表不影响旧功能，不删也不读。
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 有意留空：保留多余的表，不做任何破坏性操作。
    }

    companion object {
        const val DB_NAME = "goldlab.db"

        /** v3：猜涨跌战绩表。（量化版的 v4/v5 已移除） */
        const val DB_VERSION = 3

        /**
         * 「猜涨跌」的战绩。单行表（id 恒为 1）。
         *
         * 除了常规的胜负，这里刻意存了 **sum_win_bars / sum_loss_bars** ——
         * 「赢的时候平均持有几根、亏的时候平均持有几根」。
         * 那两个数字是这个玩法最重要的产出：它把「赚了就跑、亏了死扛」量化出来。
         */
        private const val CREATE_GUESS_STATS = """
            CREATE TABLE IF NOT EXISTS guess_stats (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                total INTEGER NOT NULL DEFAULT 0,
                correct INTEGER NOT NULL DEFAULT 0,
                streak INTEGER NOT NULL DEFAULT 0,
                best_streak INTEGER NOT NULL DEFAULT 0,
                equity REAL NOT NULL DEFAULT 0,
                initial_equity REAL NOT NULL DEFAULT 0,
                sum_win_pct REAL NOT NULL DEFAULT 0,
                sum_loss_pct REAL NOT NULL DEFAULT 0,
                wins INTEGER NOT NULL DEFAULT 0,
                losses INTEGER NOT NULL DEFAULT 0,
                sum_win_bars INTEGER NOT NULL DEFAULT 0,
                sum_loss_bars INTEGER NOT NULL DEFAULT 0,
                manual_closes INTEGER NOT NULL DEFAULT 0,
                timeout_closes INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_REPLAY_BOOKMARKS = """
            CREATE TABLE IF NOT EXISTS replay_bookmarks (
                source_key TEXT PRIMARY KEY,
                source_name TEXT,
                bar_index INTEGER NOT NULL DEFAULT 0,
                speed INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL
            )
        """
    }
}
