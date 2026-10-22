package com.fxsimulator.data.repository

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fxsimulator.data.db.GoldLabDatabase
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.SymbolSpecs

/** 行情回放进度 */
data class ReplayProgress(
    val sourceId: String?,
    val sourceName: String?,
    val barIndex: Int,
    val speed: Int,
)

/**
 * 设置与默认模拟规格。全部为**模拟参数**，界面上必须说明不代表真实经纪商。
 */
class SettingsRepository(private val db: GoldLabDatabase) {

    fun getString(key: String, default: String): String {
        db.readableDatabase.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else default
        }
    }

    fun putString(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getDouble(key: String, default: Double): Double =
        getString(key, default.toString()).toDoubleOrNull() ?: default

    fun getInt(key: String, default: Int): Int =
        getString(key, default.toString()).toIntOrNull() ?: default

    fun getBool(key: String, default: Boolean): Boolean =
        getString(key, default.toString()).toBooleanStrictOrNull() ?: default

    fun putDouble(key: String, value: Double) = putString(key, value.toString())
    fun putInt(key: String, value: Int) = putString(key, value.toString())
    fun putBool(key: String, value: Boolean) = putString(key, value.toString())

    /**
     * 读取当前品种的规格。
     *
     * 合约大小、小数位、基础/报价货币现在按品种**内置**（见 [SymbolSpecs]），
     * 不再从偏好里读那些曾经可编辑的字段 —— 旧版本存下的 contractSize 对新增的
     * 外汇品种是错的（100 vs 100000），继续沿用只会造成"看起来正常、实际算错钱"。
     * 保留的只有品种选择与杠杆（上限 2000）。
     */
    fun loadSymbolSpec(): SymbolSpec {
        val symbol = getString(KEY_SYMBOL, "XAUUSD")
        val leverage = getInt(KEY_LEVERAGE, 2000).coerceIn(1, 2000)
        return SymbolSpecs.of(symbol).copy(leverage = leverage)
    }

    fun saveSymbolSpec(spec: SymbolSpec) {
        // 只存用户真正可选择的两项；合约参数属于内置表，存了反而会过期
        putString(KEY_SYMBOL, spec.symbol)
        putInt(KEY_LEVERAGE, spec.leverage.coerceIn(1, 2000))
    }

    fun loadInitialBalance(): Double = getDouble(KEY_INITIAL_BALANCE, 10000.0)

    fun loadDefaultLot(): Double = getDouble(KEY_DEFAULT_LOT, 0.10)

    fun loadPlaySpeed(): Int = getInt(KEY_PLAY_SPEED, 1)

    fun loadReplayProgress(): ReplayProgress = ReplayProgress(
        sourceId = getString(KEY_REPLAY_SOURCE_ID, "").ifEmpty { null },
        sourceName = getString(KEY_REPLAY_SOURCE_NAME, "").ifEmpty { null },
        barIndex = getInt(KEY_REPLAY_INDEX, 0),
        speed = getInt(KEY_PLAY_SPEED, 1),
    )

    fun saveReplayProgress(p: ReplayProgress) {
        putString(KEY_REPLAY_SOURCE_ID, p.sourceId ?: "")
        putString(KEY_REPLAY_SOURCE_NAME, p.sourceName ?: "")
        putInt(KEY_REPLAY_INDEX, p.barIndex)
        putInt(KEY_PLAY_SPEED, p.speed)
    }

    fun clearAll() {
        db.writableDatabase.delete("settings", null, null)
    }

    companion object {
        const val KEY_SYMBOL = "symbol"
        const val KEY_CONTRACT_SIZE = "contract_size"
        const val KEY_MIN_LOT = "min_lot"
        const val KEY_LOT_STEP = "lot_step"
        const val KEY_MAX_LOT = "max_lot"
        const val KEY_LEVERAGE = "leverage"
        const val KEY_DIGITS = "digits"
        const val KEY_SPREAD = "spread"
        const val KEY_COMMISSION = "commission_per_lot"
        const val KEY_SWAP = "swap_per_lot"
        const val KEY_INITIAL_BALANCE = "initial_balance"
        const val KEY_DEFAULT_LOT = "default_lot"
        const val KEY_PLAY_SPEED = "play_speed"
        const val KEY_REPLAY_SOURCE_ID = "replay_source_id"
        const val KEY_REPLAY_SOURCE_NAME = "replay_source_name"
        const val KEY_REPLAY_INDEX = "replay_index"
        const val KEY_ADS_TEST_MODE = "ads_test_mode"
        const val KEY_SHOW_EMA = "show_ema"
        const val KEY_SHOW_RSI = "show_rsi"
        const val KEY_SHOW_ATR = "show_atr"
        const val KEY_SHOW_SLTP = "show_sltp"
        const val KEY_HOLLOW_UP = "hollow_up_candles"
        const val KEY_INDICATORS = "indicator_config"
        const val KEY_RISK_MAX_DAILY_LOSS = "risk_max_daily_loss"
        const val KEY_RISK_MAX_CONSECUTIVE = "risk_max_consecutive"
        const val KEY_RISK_STOP_OUT = "risk_stop_out_level"
        /** 上次玩的是哪个内置月份（yyyy-MM）。启动时据此恢复，这是「存档」的身份键。 */
        const val KEY_LAST_MONTH = "last_bundled_month"
        const val KEY_TIMEFRAME = "chart_timeframe"

        /** 猜涨跌：本局手数 */
        const val KEY_GUESS_LOT = "guess_lot"

        /** 猜涨跌：每根 K 线的揭晓间隔（毫秒） */
        const val KEY_GUESS_TICK = "guess_tick_ms"
    }
}
