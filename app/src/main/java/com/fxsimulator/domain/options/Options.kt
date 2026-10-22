package com.fxsimulator.domain.options

import com.fxsimulator.domain.model.Candle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** 期权期限。方案 4.2 结论：最短 1 小时，更短交给事件合约。 */
enum class OptionTenor(val minutes: Int, val label: String) {
    H1(60, "1 小时"),
    H4(240, "4 小时"),
    D1(1_440, "1 天"),
    D3(4_320, "3 天"),
    D7(10_080, "7 天"),
    D30(43_200, "30 天"),
    ;

    /** 行权价间距（现价的百分比）。方案 4.3。 */
    val strikeSpacingPercent: Double
        get() = when (this) {
            H1, H4 -> 0.0025
            D1, D3 -> 0.0075
            D7 -> 0.015
            D30 -> 0.03
        }
}

enum class OptionType { CALL, PUT }

enum class OptionStatus { OPEN, SETTLED }

/** 一张期权的报价与希腊值（方案 4.4）。 */
data class OptionQuote(
    val type: OptionType,
    val strike: Double,
    val premium: Double,
    /** 理论价（未加价差） */
    val theoretical: Double,
    /** 买卖价差绝对值 */
    val spread: Double,
    /** 标的价格变动 1 单位，权利金变动多少 */
    val delta: Double,
    /** delta 的变化率 */
    val gamma: Double,
    /** 每天的时间价值损耗（负数） */
    val thetaDaily: Double,
    /** 波动率升 1 个百分点，权利金变动多少 */
    val vegaPerPct: Double,
    /** 隐含波动率（这里 = 实测年化波动率 × skew） */
    val iv: Double,
) {
    val ask: Double get() = premium + spread / 2.0
    val bid: Double get() = (premium - spread / 2.0).coerceAtLeast(0.0)

    /** 是否实值（按当前标的价格）。 */
    fun isInTheMoney(spot: Double): Boolean =
        if (type == OptionType.CALL) spot > strike else spot < strike
}

/**
 * 期权定价与希腊值。
 *
 * 方案 4.2：欧式、现金结算、只做买方；σ 取**实测年化波动率**再乘 skew
 * （虚值看跌略高、虚值看涨略低，±15%）。
 *
 * 本实现是标准 Black-Scholes（连续复利、无分红）。它不是"真实市场价"，
 * 但在单机游戏里足够自洽，且能用希腊值向玩家解释"时间价值为什么会衰减"。
 */
object OptionPricing {

    const val RISK_FREE = 0.05

    /** 1 张 = 1 枚。 */
    const val CONTRACT_UNIT = 1.0

    /** 最小下单 0.01 张。 */
    const val MIN_QUANTITY = 0.01

    /** 行权费系数（方案 4.1）。 */
    const val SETTLEMENT_FEE_RATE = 0.00015
    const val EXERCISE_FEE_CAP_OF_INTRINSIC = 0.10

    /** 虚值方向的波动率倾斜：虚值看跌 +15%，虚值看涨 −15%（线性过渡到平值 1.0）。 */
    fun skew(type: OptionType, spot: Double, strike: Double): Double {
        if (spot <= 0.0) return 1.0
        val moneyness = (strike - spot) / spot          // >0 表示行权价更高
        val raw = when (type) {
            OptionType.CALL -> -moneyness * 15.0          // 虚值看涨（K 高）降低
            OptionType.PUT -> moneyness * 15.0            // 虚值看跌（K 低）降低
        }
        // raw 在 ±15% 范围内夹住
        return (1.0 + raw.coerceIn(-0.15, 0.15))
    }

    /**
     * Black-Scholes 权利金 + 希腊值。
     *
     * @param annualVol 年化波动率（如 0.36 表示 36%）
     */
    fun quote(
        type: OptionType,
        spot: Double,
        strike: Double,
        annualVol: Double,
        tenor: OptionTenor,
        tickSize: Double,
        skewFactor: Double = 1.0,
    ): OptionQuote {
        val years = tenor.minutes / (365.0 * 24.0 * 60.0)
        return quoteYears(type, spot, strike, annualVol * skewFactor, years, tickSize)
    }

    /**
     * 按任意年限定价（不经过 [OptionTenor]）。
     *
     * 存在的理由：真实合约的到期日往往不是整 1h/1d（例如 66 天），
     * 只有暴露这一个入口，才能拿真实报价（价格/希腊值）反过来校验实现。
     */
    fun quoteYears(
        type: OptionType,
        spot: Double,
        strike: Double,
        annualVol: Double,
        years: Double,
        tickSize: Double,
        riskFree: Double = RISK_FREE,
    ): OptionQuote {
        require(spot > 0.0) { "标的价格必须为正：$spot" }
        require(strike > 0.0) { "行权价必须为正：$strike" }
        require(years > 0.0) { "剩余年限必须为正：$years" }
        val t = years
        val sigma = annualVol.coerceAtLeast(1e-6)
        val r = riskFree

        val volT = sigma * sqrt(t)
        val d1 = (ln(spot / strike) + (r + 0.5 * sigma * sigma) * t) / volT
        val d2 = d1 - volT
        val nd1 = normalCdf(d1)
        val nd2 = normalCdf(d2)
        val pdf1 = normalPdf(d1)
        val discount = exp(-r * t)

        val price = when (type) {
            OptionType.CALL -> spot * nd1 - strike * discount * nd2
            OptionType.PUT -> strike * discount * normalCdf(-d2) - spot * normalCdf(-d1)
        }.coerceAtLeast(0.0)

        val delta = when (type) {
            OptionType.CALL -> nd1
            OptionType.PUT -> nd1 - 1.0
        }
        val gamma = pdf1 / (spot * volT)
        val thetaAnnual = when (type) {
            OptionType.CALL ->
                -(spot * pdf1 * sigma) / (2.0 * sqrt(t)) - r * strike * discount * nd2
            OptionType.PUT ->
                -(spot * pdf1 * sigma) / (2.0 * sqrt(t)) + r * strike * discount * normalCdf(-d2)
        }
        val vegaPerPct = spot * pdf1 * sqrt(t) / 100.0

        val spread = maxOf(2.0 * tickSize, price * 0.01)
        return OptionQuote(
            type = type,
            strike = strike,
            premium = price,
            theoretical = price,
            spread = spread,
            delta = delta,
            gamma = gamma,
            thetaDaily = thetaAnnual / 365.0,
            vegaPerPct = vegaPerPct,
            iv = sigma,
        )
    }

    /** 行权价阶梯：平值上下各 [count] 档，间距按期限百分比（方案 4.3）。 */
    fun strikes(spot: Double, tenor: OptionTenor, count: Int = 8): List<Double> {
        require(spot > 0.0) { "标的价格必须为正" }
        val step = spot * tenor.strikeSpacingPercent
        if (step <= 0.0) return listOf(spot)
        return (-count..count).map { i -> spot + i * step }.filter { it > 0.0 }.sorted()
    }

    /** 结算用均价窗口：min(30, 期限分钟数) 根 1m。 */
    fun settlementBars(tenor: OptionTenor): Int = minOf(30, tenor.minutes)

    /**
     * 结算价 = 到期前 N 根 1m 收盘均价；数据不足返回 null（不得提前结算）。
     *
     * @param expireIndex 到期时刻在序列中的下标
     */
    fun settlementPrice(bars: List<Candle>, tenor: OptionTenor, expireIndex: Int): Double? {
        val n = settlementBars(tenor)
        val first = expireIndex - n + 1
        if (first < 0 || expireIndex >= bars.size) return null
        var sum = 0.0
        var count = 0
        for (i in first..expireIndex) {
            val c = bars[i].close
            if (c.isFinite() && c > 0.0) { sum += c; count++ }
        }
        return if (count == 0) null else sum / count
    }

    /** 到期内在价值（每张，USDT）。欧式现金结算：只取正的部分。 */
    fun intrinsic(type: OptionType, strike: Double, settlement: Double): Double =
        when (type) {
            OptionType.CALL -> (settlement - strike).coerceAtLeast(0.0)
            OptionType.PUT -> (strike - settlement).coerceAtLeast(0.0)
        }

    /** 行权费 = min(结算价值 × 0.015%, 实值额 × 10%)（方案 4.1）。 */
    fun exerciseFee(settlementValue: Double, intrinsicValue: Double): Double =
        minOf(settlementValue * SETTLEMENT_FEE_RATE, intrinsicValue * EXERCISE_FEE_CAP_OF_INTRINSIC)
            .coerceAtLeast(0.0)

    /** 标准正态 CDF（Abramowitz-Stegun 7.1.26）。 */
    fun normalCdf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
            0.254829592) * t * exp(-ax * ax)
        return 0.5 * (1.0 + sign * y)
    }

    /** 标准正态 PDF。 */
    fun normalPdf(x: Double): Double = exp(-0.5 * x * x) / sqrt(2.0 * Math.PI)
}

/**
 * 一张期权持仓（买方）。
 *
 * 最大亏损 = 权利金；无保证金、无强平（方案 4.1）。
 */
data class OptionPosition(
    val id: Long,
    val symbol: String,
    val type: OptionType,
    val strike: Double,
    val tenor: OptionTenor,
    val quantity: Double,
    /** 开仓权利金（每张） */
    val entryPremium: Double,
    val openAtMillis: Long,
    val expireAtMillis: Long,
    val expireIndex: Int,
    val status: OptionStatus = OptionStatus.OPEN,
    val settlementPrice: Double? = null,
    val exerciseFee: Double = 0.0,
) {
    init {
        require(quantity >= OptionPricing.MIN_QUANTITY) { "数量不能少于 ${OptionPricing.MIN_QUANTITY} 张" }
        require(entryPremium >= 0.0) { "权利金不能为负" }
    }

    /** 开仓成本（每张权利金 × 张数）。 */
    val cost: Double get() = entryPremium * quantity

    val isSettled: Boolean get() = status == OptionStatus.SETTLED

    /** 到期兑付（每张内在价值 × 张数 − 行权费）；未结算返回 null。 */
    val payout: Double?
        get() {
            val s = settlementPrice ?: return null
            val perContract = OptionPricing.intrinsic(type, strike, s)
            return (perContract * quantity - exerciseFee).coerceAtLeast(0.0)
        }

    val realizedPnl: Double?
        get() = payout?.let { it - cost }
}

/** 期权结算器。 */
object OptionSettlement {

    /** 结算；数据不足返回 null（调用方必须等待回放推进）。 */
    fun settle(position: OptionPosition, bars: List<Candle>): OptionPosition? {
        val price = OptionPricing.settlementPrice(bars, position.tenor, position.expireIndex)
            ?: return null
        val intrinsicPerContract = OptionPricing.intrinsic(position.type, position.strike, price)
        val intrinsicTotal = intrinsicPerContract * position.quantity
        val settlementValue = price * position.quantity
        return position.copy(
            status = OptionStatus.SETTLED,
            settlementPrice = price,
            exerciseFee = OptionPricing.exerciseFee(settlementValue, intrinsicTotal),
        )
    }
}
