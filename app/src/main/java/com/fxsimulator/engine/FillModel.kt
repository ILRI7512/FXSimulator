package com.fxsimulator.engine

import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.TradeDirection

/** 一根 K 线内的出场判定结果 */
enum class ExitKind {
    /** 未触发 */
    NONE,
    STOP_LOSS,
    TAKE_PROFIT,
    /** 同一根 K 线内止损与止盈都被触及，路径不可知 */
    AMBIGUOUS,
}

data class BarExit(
    val kind: ExitKind,
    val price: Double?,
    val note: String,
)

/**
 * 模拟成交模型。
 *
 * ## 报价约定
 * 行情数据是 **BID** 价。买入按 ASK = BID + 点差 成交，卖出按 BID 成交。
 * - 开仓：BUY 成交在 ASK，SELL 成交在 BID；
 * - 平仓：平掉 BUY 相当于卖出，成交在 BID；平掉 SELL 相当于买回，成交在 ASK。
 *
 * ## K 线内部路径的保守规则（重要）
 * 本项目只有 M1 的 OHLC，**没有 Tick 数据**，因此无法知道同一根 K 线内
 * 最高价与最低价谁先出现。当止损与止盈价格都落在该 K 线的 [low, high] 区间内时，
 * 路径不可知，本项目采用**保守规则：一律假设先触发止损**。
 * 该规则会系统性低估策略表现，但对练习与风控是安全的，且在代码与界面中都有说明。
 */
object FillModel {

    /** BID → ASK */
    fun ask(bid: Double, spread: Double): Double = bid + spread

    /** 开仓成交价 */
    fun entryPrice(direction: TradeDirection, bid: Double, spread: Double): Double {
        return if (direction.isBuy) ask(bid, spread) else bid
    }

    /** 平仓成交价（对持仓的反向操作） */
    fun exitPrice(direction: TradeDirection, bid: Double, spread: Double): Double {
        return if (direction.isBuy) bid else ask(bid, spread)
    }

    /**
     * 判定一根 K 线是否触发止损 / 止盈。
     *
     * @param direction 持仓方向
     * @param candle 当前 K 线
     * @param stopLoss 止损价（可为 null）
     * @param takeProfit 止盈价（可为 null）
     * @param spread 点差（用于把触发价换算为实际成交的 BID 侧价格）
     */
    fun evaluateBar(
        direction: TradeDirection,
        candle: Candle,
        stopLoss: Double?,
        takeProfit: Double?,
        spread: Double,
    ): BarExit {
        val slHit = stopLoss != null && isStopTouched(direction, candle, stopLoss)
        val tpHit = takeProfit != null && isTakeProfitTouched(direction, candle, takeProfit)

        if (slHit && tpHit) {
            // 保守规则：路径不可知时按最坏情况处理
            return BarExit(
                ExitKind.AMBIGUOUS,
                exitPrice(direction, stopLoss!!, spread),
                "同一根 K 线内止损与止盈均被触及；因只有 OHLC 无法确定盘中先后顺序，" +
                    "按保守规则视为**先触发止损**",
            )
        }
        if (slHit) {
            return BarExit(
                ExitKind.STOP_LOSS,
                exitPrice(direction, stopLoss!!, spread),
                "K 线触及止损价 " + fmt(stopLoss),
            )
        }
        if (tpHit) {
            return BarExit(
                ExitKind.TAKE_PROFIT,
                exitPrice(direction, takeProfit!!, spread),
                "K 线触及止盈价 " + fmt(takeProfit),
            )
        }
        return BarExit(ExitKind.NONE, null, "")
    }

    /** 多头止损被触及：最低价 <= 止损价 */
    private fun isStopTouched(direction: TradeDirection, c: Candle, sl: Double): Boolean {
        return if (direction.isBuy) c.low <= sl else c.high >= sl
    }

    /** 多头止盈被触及：最高价 >= 止盈价 */
    private fun isTakeProfitTouched(direction: TradeDirection, c: Candle, tp: Double): Boolean {
        return if (direction.isBuy) c.high >= tp else c.low <= tp
    }

    private fun fmt(v: Double): String = String.format("%.3f", v)
}
