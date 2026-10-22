package com.fxsimulator.domain.model

/**
 * 模拟账户快照。
 *
 * Balance（余额）= 已实现盈亏结算后的资金
 * Equity（净值） = Balance + 持仓浮动盈亏
 * Margin（占用保证金）= 所有持仓保证金之和
 * FreeMargin（可用保证金）= Equity - Margin
 */
data class Account(
    val balance: Double = 10000.0,
    val floatingPnl: Double = 0.0,
    val margin: Double = 0.0,
) {
    val equity: Double get() = balance + floatingPnl
    val freeMargin: Double get() = equity - margin
    /** 保证金水平（%）。无持仓时返回 Double.MAX_VALUE 表示不适用 */
    val marginLevel: Double get() = if (margin <= 0.0) Double.MAX_VALUE else equity / margin * 100.0
}
