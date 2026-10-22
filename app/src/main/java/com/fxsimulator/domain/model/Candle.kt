package com.fxsimulator.domain.model

/**
 * 单根 K 线（第一版为 M1）。
 *
 * 重要说明：本项目只有 OHLC 数据，**没有真实 Tick 数据**。
 * 因此同一根 K 线内部「最高价与最低价谁先出现」是不可知的，
 * 任何依赖盘中路径的判断都必须采用保守假设（见 FillModel）。
 *
 * @param timeMillis 该 K 线起始时间（毫秒）。数据源为经纪商服务器时间，
 *                   本项目按 UTC 直接换算为 epoch，不做时区偏移推断。
 */
data class Candle(
    val timeMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    /** 成交量：MT5 导出为 Tick 量；无数据时为 0 */
    val volume: Long = 0L,
    /** 点差（价格为单位的原始值，可能为 0 表示源数据未提供） */
    val spread: Double = 0.0,
) {
    /** OHLC 自洽性校验：用于数据导入时的有效性检查 */
    fun isValid(): Boolean {
        if (timeMillis <= 0L) return false
        if (!open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()) return false
        if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) return false
        if (high < low) return false
        if (high < open || high < close) return false
        if (low > open || low > close) return false
        return true
    }
}
