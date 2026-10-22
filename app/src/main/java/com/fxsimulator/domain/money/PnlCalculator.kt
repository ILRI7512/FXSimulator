package com.fxsimulator.domain.money

import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.TradeDirection

/**
 * 盈亏计算器。
 *
 * 毛盈亏 = (平仓价 - 开仓价) × 合约大小 × 手数 × 方向系数 × **报价货币折算到 USD 的系数**
 * 其中方向系数：BUY = +1，SELL = -1。
 *
 * 最后那个系数是这次新加的：公式算出来的金额天然是**报价货币**（USDJPY 赚的是日元），
 * 必须折算成美元才能进账户。USD 计价的品种（EURUSD、XAUUSD）系数恒为 1，行为不变。
 *
 * 所有金额均为**虚拟美元**，不涉及真实资金。
 * 合约规格来自 [SymbolSpec]，按品种内置，不硬编码任何单一经纪商参数。
 */
object PnlCalculator {

    /**
     * 计算毛盈亏（未扣佣金与隔夜费）。
     * @param openPrice 开仓成交价
     * @param closePrice 平仓成交价（同时用作报价货币折算的汇率）
     */
    fun grossProfit(
        spec: SymbolSpec,
        direction: TradeDirection,
        volume: Double,
        openPrice: Double,
        closePrice: Double,
    ): Double {
        val priceDelta = closePrice - openPrice
        val inQuoteCurrency = priceDelta * spec.contractSize * volume * direction.sign
        return inQuoteCurrency * spec.usdPerQuoteUnit(closePrice)
    }

    /**
     * 单边佣金合计。开仓 + 平仓各收一次，
     * 因此一笔完整交易的佣金 = 每手单边佣金 × 手数 × 2。
     */
    fun commission(spec: SymbolSpec, volume: Double): Double {
        return spec.commissionPerLot * volume * 2.0
    }

    /**
     * 隔夜费。第一版未实现逐日结算，nights 默认 0。
     */
    fun swap(spec: SymbolSpec, volume: Double, nights: Int): Double {
        if (nights <= 0) return 0.0
        return -spec.swapPerLotPerNight * volume * nights
    }

    /** 净盈亏 = 毛盈亏 - 佣金 + 隔夜费 */
    fun netProfit(gross: Double, commission: Double, swap: Double): Double {
        return gross - commission + swap
    }

    /**
     * 浮动盈亏：以当前报价的**平仓价**计算。
     * 对 BUY 而言平仓价是 BID；对 SELL 而言平仓价是 ASK。
     * 调用方需先把点差算好再传入。
     */
    fun floating(
        spec: SymbolSpec,
        direction: TradeDirection,
        volume: Double,
        openPrice: Double,
        currentClosePrice: Double,
    ): Double = grossProfit(spec, direction, volume, openPrice, currentClosePrice)

    /** 把 USD 金额格式化为带符号字符串 */
    fun formatMoney(value: Double, digits: Int = 2): String {
        val sign = if (value > 0) "+" else if (value < 0) "-" else ""
        val abs = Math.abs(value)
        val f = Math.pow(10.0, digits.toDouble())
        val rounded = Math.round(abs * f) / f
        return sign + String.format("%,.2f", rounded)
    }
}
