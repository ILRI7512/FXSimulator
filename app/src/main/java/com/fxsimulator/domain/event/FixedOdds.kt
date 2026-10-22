package com.fxsimulator.domain.event

import com.fxsimulator.domain.model.Candle

/**
 * 固定赔率制事件合约（币安口径，方案 3.7 B 模式）。
 *
 * ## 与概率定价制的数学差别（必须说清，否则玩家会误以为一样）
 * | | A 概率定价 | B 固定赔率 |
 * |---|---|---|
 * | 玩家付出 | 张数 × 份额价 | 一笔本金 |
 * | 正确赔付 | 张数 × 1 | 本金 × (1 + 支付率) |
 * | 平衡胜率 | 买入价（可能 43%） | **1/(1+r) = 55.56%** |
 * | 持平 | 判涨（含等于） | **退本金** |
 * | 提前平仓 | 支持 | 不支持 |
 * | 手续费 | 0.1%/边 | 无 |
 *
 * 固定赔率天生是负期望（要 55.56% 胜率才打平），所以玩法设计上必须给玩家
 * 一个能"读到偏差"的窗口 —— 本实现用的是**结算窗口均价**：
 * 结算价是到期前 min(周期,5) 根 1m 的均价，趋势行情里它会滞后于最新价，
 * 玩家顺着动量判断时胜率可以超过 55.56%。这不是编出来的 edge，而是结算口径本身带来的。
 */
enum class FixedOddsCycle(val minutes: Int, val label: String) {
    M10(10, "10 分钟"),
    M30(30, "30 分钟"),
    H1(60, "1 小时"),
    H4(240, "4 小时"),
    D1(1_440, "1 天"),
}

enum class FixedOddsStatus { OPEN, WON, LOST, REFUNDED }

object FixedOddsRules {
    /** 支付率（方案：80%，波动时可在 75%–85% 间浮动；这里固定 80%）。 */
    const val PAYOUT_RATE = 0.80

    /** 盈亏平衡胜率 = 1/(1+r) = 55.56%。 */
    val breakEvenWinRate: Double get() = 1.0 / (1.0 + PAYOUT_RATE)

    /** 单笔下限 / 上限（真实口径）。 */
    const val MIN_STAKE = 5.0
    const val MAX_STAKE_ABSOLUTE = 250.0

    /** 单笔不超过权益的 5%。 */
    const val MAX_STAKE_OF_EQUITY = 0.05

    /** 日亏损上限：权益的 20%；触发后当日只能看不能下。 */
    const val DAILY_LOSS_CAP_RATIO = 0.20

    /** 结算窗口 = min(周期分钟数, 5) 根 1m 收盘均价（与 A 模式同口径）。 */
    fun settlementBars(cycle: FixedOddsCycle): Int = minOf(cycle.minutes, 5)

    /**
     * 单笔上限 = min(250, max(最低本金, 权益 5%))。
     *
     * 取 max 的原因：亏损后权益变小，纯 5% 会掉到 5 USDT 之下，
     * 于是玩家连最小本金都下不了 —— 那不是风控，是把人锁死。
     */
    fun maxStake(equity: Double): Double =
        minOf(MAX_STAKE_ABSOLUTE, maxOf(MIN_STAKE, equity * MAX_STAKE_OF_EQUITY))

    /** 校验本金；返回 null 表示合法。 */
    fun validateStake(stake: Double, equity: Double): String? = when {
        stake < MIN_STAKE -> "单笔至少 $MIN_STAKE USDT"
        stake > maxStake(equity) + 1e-9 ->
            "单笔上限 ${"%.2f".format(maxStake(equity))} USDT（权益 5% 与 250 取小）"
        stake > equity + 1e-9 -> "权益不足"
        else -> null
    }
}

/**
 * 一张固定赔率合约。
 *
 * 输赢规则：
 * - 结算价 > 目标价：押涨赢
 * - 结算价 < 目标价：押跌赢
 * - **相等：退还本金**（不赚不亏，这是与 A 模式最大的差别）
 */
data class FixedOddsPosition(
    val id: Long,
    val symbol: String,
    val cycle: FixedOddsCycle,
    val side: EventSide,
    val stake: Double,
    val payoutRate: Double,
    val openAtMillis: Long,
    val expireAtMillis: Long,
    val expireIndex: Int,
    val targetPrice: Double,
    val status: FixedOddsStatus = FixedOddsStatus.OPEN,
    val settlementPrice: Double? = null,
) {
    init {
        require(stake >= FixedOddsRules.MIN_STAKE) { "本金不能少于 ${FixedOddsRules.MIN_STAKE}：$stake" }
        require(targetPrice > 0.0) { "目标价必须为正" }
        require(payoutRate > 0.0) { "支付率必须为正" }
    }

    /** 正确时的总返还（含本金）。 */
    val payoutIfWin: Double get() = stake * (1.0 + payoutRate)

    val isSettled: Boolean get() = status != FixedOddsStatus.OPEN

    /** 已实现盈亏；未结算为 null。 */
    val realizedPnl: Double?
        get() = when (status) {
            FixedOddsStatus.OPEN -> null
            FixedOddsStatus.WON -> stake * payoutRate
            FixedOddsStatus.LOST -> -stake
            FixedOddsStatus.REFUNDED -> 0.0
        }

    /** 结算时应该返还给账户的金额。 */
    val refundOnSettle: Double
        get() = when (status) {
            FixedOddsStatus.OPEN -> 0.0
            FixedOddsStatus.WON -> payoutIfWin
            FixedOddsStatus.LOST -> 0.0
            FixedOddsStatus.REFUNDED -> stake
        }

    val won: Boolean get() = status == FixedOddsStatus.WON
}

sealed interface FixedOddsOutcome {
    data class Pending(val needBars: Int, val haveBars: Int) : FixedOddsOutcome
    data class Settled(val settlementPrice: Double) : FixedOddsOutcome
}

object FixedOddsSettlement {

    /** 结算；数据不足返回 Pending（禁止提前结算）。 */
    fun settle(position: FixedOddsPosition, bars: List<Candle>): FixedOddsOutcome {
        val n = FixedOddsRules.settlementBars(position.cycle)
        val last = position.expireIndex
        val first = last - n + 1
        if (first < 0) return FixedOddsOutcome.Pending(n, last + 1)
        if (last >= bars.size) return FixedOddsOutcome.Pending(n, bars.size - first)
        var sum = 0.0
        var count = 0
        for (i in first..last) {
            val c = bars[i].close
            if (c.isFinite() && c > 0.0) { sum += c; count++ }
        }
        if (count == 0) return FixedOddsOutcome.Pending(n, 0)
        return FixedOddsOutcome.Settled(sum / count)
    }

    fun apply(position: FixedOddsPosition, outcome: FixedOddsOutcome): FixedOddsPosition {
        if (outcome !is FixedOddsOutcome.Settled) return position
        val s = outcome.settlementPrice
        val status = when {
            kotlin.math.abs(s - position.targetPrice) < 1e-9 -> FixedOddsStatus.REFUNDED
            s > position.targetPrice -> if (position.side == EventSide.UP) FixedOddsStatus.WON else FixedOddsStatus.LOST
            else -> if (position.side == EventSide.DOWN) FixedOddsStatus.WON else FixedOddsStatus.LOST
        }
        return position.copy(status = status, settlementPrice = s)
    }
}
