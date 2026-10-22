package com.fxsimulator.domain.model

/** 交易方向 */
enum class TradeDirection {
    BUY,
    SELL;

    val isBuy: Boolean get() = this == BUY
    val sign: Double get() = if (this == BUY) 1.0 else -1.0
    val label: String get() = if (this == BUY) "买入" else "卖出"
}

/** 订单状态 */
enum class OrderStatus {
    /** 持仓中 */
    OPEN,
    /** 已平仓 */
    CLOSED,
    /** 被拒绝（保证金不足、参数非法等） */
    REJECTED,
}

/** 平仓原因 */
enum class CloseReason {
    MANUAL,
    STOP_LOSS,
    TAKE_PROFIT,
    /** 触发模拟爆仓强制平仓 */
    MARGIN_CALL,
    RESET,
}

/**
 * 一笔模拟交易记录。开仓时写入 OPEN 记录，平仓后更新为 CLOSED。
 * REJECTED 记录只会出现在订单日志中，不占用资金。
 */
data class TradeRecord(
    val orderId: Long = 0L,
    val symbol: String,
    val direction: TradeDirection,
    val openTimeMillis: Long,
    val closeTimeMillis: Long? = null,
    val openPrice: Double,
    val closePrice: Double? = null,
    val volume: Double,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val grossProfit: Double = 0.0,
    val commission: Double = 0.0,
    val swap: Double = 0.0,
    val netProfit: Double = 0.0,
    val status: OrderStatus = OrderStatus.OPEN,
    val closeReason: CloseReason? = null,
    /** 说明本次模拟成交所用的规则，便于复盘 */
    val fillNote: String = "",
) {
    /** 持仓中即视为持仓对象 */
    val isOpen: Boolean get() = status == OrderStatus.OPEN
    val isWin: Boolean get() = status == OrderStatus.CLOSED && netProfit > 0.0
    val isLoss: Boolean get() = status == OrderStatus.CLOSED && netProfit < 0.0
}
