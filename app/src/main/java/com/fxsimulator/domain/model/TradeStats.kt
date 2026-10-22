package com.fxsimulator.domain.model

/** 交易统计结果，全部由真实模拟成交记录计算得出 */
data class TradeStats(
    val totalTrades: Int = 0,
    val winTrades: Int = 0,
    val lossTrades: Int = 0,
    val breakEvenTrades: Int = 0,
    val grossProfit: Double = 0.0,
    val grossLoss: Double = 0.0,
    val netProfit: Double = 0.0,
    val totalCommission: Double = 0.0,
    val totalSwap: Double = 0.0,
    val averageWin: Double = 0.0,
    val averageLoss: Double = 0.0,
    /** 盈亏比 = 平均盈利 / |平均亏损| */
    val profitLossRatio: Double = 0.0,
    /** 盈利因子 = 总盈利 / |总亏损| */
    val profitFactor: Double = 0.0,
    val winRate: Double = 0.0,
    val maxDrawdown: Double = 0.0,
    val maxDrawdownPercent: Double = 0.0,
    val maxConsecutiveLosses: Int = 0,
    /** 净值曲线采样点（用于绘制） */
    val equityCurve: List<CurvePoint> = emptyList(),
)

data class CurvePoint(
    val timeMillis: Long,
    val equity: Double,
)

/** 单日模拟盈亏汇总，用于风控 */
data class DailyResult(
    val dayKey: String,
    val realizedPnl: Double,
    val trades: Int,
)
