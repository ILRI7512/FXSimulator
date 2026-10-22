package com.fxsimulator.domain.event

import com.fxsimulator.domain.model.Candle

/** 事件合约周期。方案 3.2：1m/5m/15m/1h/4h（1m 为新手教学档）。 */
enum class EventCycle(val minutes: Int, val label: String) {
    M5(5, "5 分钟"),
    M15(15, "15 分钟"),
    H1(60, "1 小时"),
    H4(240, "4 小时"),
}

enum class EventSide { UP, DOWN }

enum class EventStatus { OPEN, SETTLED }

/** 事件合约的固定规则（方案 3.2），集中一处避免各页各写一套。 */
object EventRules {
    const val MIN_SHARES = 6
    const val MIN_NOTIONAL = 5.0
    const val MIN_PRICE = 0.01
    const val MAX_PRICE = 0.99

    /** 开/平各 0.1%，到期结算免费。 */
    const val FEE_RATE = 0.001

    /** 半价差（方案：spread = 0.01）。 */
    const val HALF_SPREAD = 0.005

    /**
     * 结算窗口 = **整个合约周期的 1m 根数**（真 TWAP）。
     *
     * 早先版本用 min(周期, 5) 近似（模仿 Chainlink 的短窗口），
     * 但那样 1 小时与 4 小时的合约都只看最后 5 分钟，
     * 玩家能轻易猜到结果、也无法体现"整个周期的时间加权"。
     * 现在 5m → 5 根、1h → 60 根、4h → 240 根，按时间等权平均。
     */
    fun settlementBars(cycle: EventCycle): Int = cycle.minutes

    /** 校验张数；返回 null 表示合法。 */
    fun validateShares(shares: Int, price: Double): String? = when {
        shares < MIN_SHARES -> "至少 $MIN_SHARES 张"
        shares * price < MIN_NOTIONAL -> "名义金额至少 $MIN_NOTIONAL USDT"
        else -> null
    }
}

/**
 * 一次事件合约的盘口快照：目标价与到期时间在开仓时固定。
 *
 * 方案 3.2：目标价 = 周期开始时刻的标记价；结算价 = 到期窗口内最后 N 根 1m 收盘均价。
 */
data class EventMarket(
    val symbol: String,
    val cycle: EventCycle,
    val openAtMillis: Long,
    val expireAtMillis: Long,
    val targetPrice: Double,
    /** 到期时刻在 1m 序列里的下标（调用方按时间对齐后给出） */
    val expireIndex: Int,
)

/**
 * 一张事件合约持仓。
 *
 * 金额口径（方案 3.2）：
 * - 开仓成本 = 张数 × 份额价 × (1 + 费率)
 * - 正确赔付 = 张数 × 1 USDT；错误 = 0
 * - 盈亏平衡胜率 = 份额价 × (1 + 费率)
 */
data class EventPosition(
    val id: Long,
    val symbol: String,
    val cycle: EventCycle,
    val side: EventSide,
    val shares: Int,
    val price: Double,
    val feeRate: Double,
    val openAtMillis: Long,
    val expireAtMillis: Long,
    val expireIndex: Int,
    val targetPrice: Double,
    val status: EventStatus = EventStatus.OPEN,
    val settlementPrice: Double? = null,
    val settledAtMillis: Long? = null,
) {
    init {
        require(shares >= EventRules.MIN_SHARES) { "份额不能少于 ${EventRules.MIN_SHARES} 张：$shares" }
        require(price in EventRules.MIN_PRICE..EventRules.MAX_PRICE) {
            "份额价必须在 ${EventRules.MIN_PRICE}..${EventRules.MAX_PRICE}：$price"
        }
        require(targetPrice > 0.0) { "目标价必须为正：$targetPrice" }
    }

    val cost: Double get() = shares * price * (1.0 + feeRate)
    val notional: Double get() = shares * price
    val breakEven: Double get() = price * (1.0 + feeRate)
    val payoutIfWin: Double get() = shares * 1.0
    val isSettled: Boolean get() = status == EventStatus.SETTLED

    /** 结算后是否赢（未结算为 null）。含等于判涨（方案 3.2）。 */
    val won: Boolean?
        get() {
            if (status != EventStatus.SETTLED) return null
            val s = settlementPrice ?: return null
            val wentUp = s >= targetPrice
            return if (side == EventSide.UP) wentUp else !wentUp
        }

    val realizedPnl: Double?
        get() = when (status) {
            EventStatus.OPEN -> null
            EventStatus.SETTLED -> (if (won == true) payoutIfWin else 0.0) - cost
        }

    /** 结算价相对目标价的基点差（正=涨），结算卡上必须显示。 */
    val bpDiff: Double?
        get() {
            val s = settlementPrice ?: return null
            if (targetPrice <= 0.0) return null
            return (s - targetPrice) / targetPrice * 10_000.0
        }
}

/** 结算结果。方案 3.4：数据不足必须等，不能提前判。 */
sealed interface EventSettlementOutcome {
    data class Pending(val needBars: Int, val haveBars: Int) : EventSettlementOutcome
    data class Settled(val settlementPrice: Double, val atMillis: Long) : EventSettlementOutcome
}

/**
 * 结算器。
 *
 * **只使用 [EventPosition.expireIndex] 及之前的 K 线** —— 这是「不读未来」的硬边界：
 * 回放没走到窗口末尾就返回 [EventSettlementOutcome.Pending]，绝不提前结算。
 */
object EventSettlement {

    fun settle(position: EventPosition, bars: List<Candle>): EventSettlementOutcome {
        val n = EventRules.settlementBars(position.cycle)
        val lastIndex = position.expireIndex
        val firstIndex = lastIndex - n + 1
        if (firstIndex < 0) return EventSettlementOutcome.Pending(n, lastIndex + 1)
        if (lastIndex >= bars.size) {
            return EventSettlementOutcome.Pending(n, bars.size - firstIndex)
        }
        var sum = 0.0
        var count = 0
        for (i in firstIndex..lastIndex) {
            val c = bars[i].close
            if (c.isFinite() && c > 0.0) { sum += c; count++ }
        }
        if (count == 0) return EventSettlementOutcome.Pending(n, 0)
        return EventSettlementOutcome.Settled(
            settlementPrice = sum / count,
            atMillis = bars[lastIndex].timeMillis,
        )
    }

    /** 用结算结果生成结算后的持仓（不改原对象）。 */
    fun apply(position: EventPosition, outcome: EventSettlementOutcome): EventPosition =
        when (outcome) {
            is EventSettlementOutcome.Pending -> position
            is EventSettlementOutcome.Settled -> position.copy(
                status = EventStatus.SETTLED,
                settlementPrice = outcome.settlementPrice,
                settledAtMillis = outcome.atMillis,
            )
        }
}
