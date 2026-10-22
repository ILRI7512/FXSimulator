package com.fxsimulator.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fxsimulator.data.db.GoldLabDatabase
import com.fxsimulator.domain.model.CloseReason
import com.fxsimulator.domain.model.OrderStatus
import com.fxsimulator.domain.model.TradeDirection
import com.fxsimulator.domain.model.TradeRecord

/** 账户与持仓的持久化快照 */
data class PersistedAccount(
    val balance: Double,
    val initialBalance: Double,
    val lastBid: Double,
    val exists: Boolean,
)

/**
 * 模拟账户 / 持仓 / 交易历史的持久化。
 *
 * 关键点：**每次打开应用都不会无条件重置账户**——只有数据库中不存在账户记录时才初始化。
 * 开仓与平仓写入放在事务里，保证「余额 + 持仓 + 历史」三者一致。
 */
class AccountRepository(private val db: GoldLabDatabase) {

    /** 读取账户；不存在时返回 exists=false，由调用方决定初始化 */
    fun loadAccount(): PersistedAccount {
        db.readableDatabase.rawQuery(
            "SELECT balance, initial_balance, last_bid FROM account WHERE id = 1", null,
        ).use { c ->
            return if (c.moveToFirst()) {
                PersistedAccount(c.getDouble(0), c.getDouble(1), c.getDouble(2), true)
            } else {
                PersistedAccount(0.0, 0.0, 0.0, false)
            }
        }
    }

    fun saveAccount(balance: Double, initialBalance: Double, lastBid: Double) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("balance", balance)
            put("initial_balance", initialBalance)
            put("last_bid", lastBid)
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict("account", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 在事务中执行账户相关的一组写操作 */
    fun <T> inTransaction(block: (SQLiteDatabase) -> T): T {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            val r = block(d)
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    fun upsertTrade(record: TradeRecord) {
        db.writableDatabase.insertWithOnConflict(
            "trades", null, toValues(record), SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun upsertTrade(database: SQLiteDatabase, record: TradeRecord) {
        database.insertWithOnConflict("trades", null, toValues(record), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadOpenPositions(): List<TradeRecord> = query("status = ?", arrayOf(OrderStatus.OPEN.name))

    fun loadHistory(limit: Int = 500): List<TradeRecord> {
        val out = ArrayList<TradeRecord>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM trades WHERE status != ? ORDER BY COALESCE(close_time, open_time) DESC LIMIT ?",
            arrayOf(OrderStatus.OPEN.name, limit.toString()),
        ).use { c -> while (c.moveToNext()) out.add(fromCursor(c)) }
        return out
    }

    fun loadAllClosed(): List<TradeRecord> = query("status = ?", arrayOf(OrderStatus.CLOSED.name))

    private fun query(where: String, args: Array<String>): List<TradeRecord> {
        val out = ArrayList<TradeRecord>()
        db.readableDatabase.rawQuery("SELECT * FROM trades WHERE $where", args).use { c ->
            while (c.moveToNext()) out.add(fromCursor(c))
        }
        return out
    }

    fun clearHistory() {
        db.writableDatabase.delete("trades", "status != ?", arrayOf(OrderStatus.OPEN.name))
    }

    fun clearAllTrades() {
        db.writableDatabase.delete("trades", null, null)
    }

    fun deleteOpenPosition(orderId: Long) {
        db.writableDatabase.delete("trades", "order_id = ?", arrayOf(orderId.toString()))
    }

    /**
     * 删除「数据库里标记为 OPEN、但内存中已不存在」的持仓，防止崩溃后残留幽灵持仓。
     * 在事务中调用。
     */
    fun deleteStaleOpen(database: SQLiteDatabase, keepOrderIds: List<Long>) {
        if (keepOrderIds.isEmpty()) {
            database.delete("trades", "status = ?", arrayOf(OrderStatus.OPEN.name))
            return
        }
        val placeholders = keepOrderIds.joinToString(",") { "?" }
        database.delete(
            "trades",
            "status = ? AND order_id NOT IN (" + placeholders + ")",
            arrayOf(OrderStatus.OPEN.name) + keepOrderIds.map { it.toString() },
        )
    }

    private fun toValues(t: TradeRecord): ContentValues = ContentValues().apply {
        put("order_id", t.orderId)
        put("symbol", t.symbol)
        put("direction", t.direction.name)
        put("open_time", t.openTimeMillis)
        if (t.closeTimeMillis != null) put("close_time", t.closeTimeMillis) else putNull("close_time")
        put("open_price", t.openPrice)
        if (t.closePrice != null) put("close_price", t.closePrice) else putNull("close_price")
        put("volume", t.volume)
        if (t.stopLoss != null) put("stop_loss", t.stopLoss) else putNull("stop_loss")
        if (t.takeProfit != null) put("take_profit", t.takeProfit) else putNull("take_profit")
        put("gross_profit", t.grossProfit)
        put("commission", t.commission)
        put("swap", t.swap)
        put("net_profit", t.netProfit)
        put("status", t.status.name)
        put("close_reason", t.closeReason?.name)
        put("fill_note", t.fillNote)
    }

    private fun fromCursor(c: Cursor): TradeRecord {
        fun d(name: String): Double = if (c.isNull(c.getColumnIndexOrThrow(name))) 0.0
        else c.getDouble(c.getColumnIndexOrThrow(name))
        fun dn(name: String): Double? = if (c.isNull(c.getColumnIndexOrThrow(name))) null
        else c.getDouble(c.getColumnIndexOrThrow(name))

        val dir = TradeDirection.valueOf(c.getString(c.getColumnIndexOrThrow("direction")))
        val status = OrderStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status")))
        val crIdx = c.getColumnIndexOrThrow("close_reason")
        val closeReason = if (c.isNull(crIdx)) null else CloseReason.valueOf(c.getString(crIdx))

        return TradeRecord(
            orderId = c.getLong(c.getColumnIndexOrThrow("order_id")),
            symbol = c.getString(c.getColumnIndexOrThrow("symbol")),
            direction = dir,
            openTimeMillis = c.getLong(c.getColumnIndexOrThrow("open_time")),
            closeTimeMillis = if (c.isNull(c.getColumnIndexOrThrow("close_time"))) null
            else c.getLong(c.getColumnIndexOrThrow("close_time")),
            openPrice = d("open_price"),
            closePrice = dn("close_price"),
            volume = d("volume"),
            stopLoss = dn("stop_loss"),
            takeProfit = dn("take_profit"),
            grossProfit = d("gross_profit"),
            commission = d("commission"),
            swap = d("swap"),
            netProfit = d("net_profit"),
            status = status,
            closeReason = closeReason,
            fillNote = c.getString(c.getColumnIndexOrThrow("fill_note")) ?: "",
        )
    }
}
