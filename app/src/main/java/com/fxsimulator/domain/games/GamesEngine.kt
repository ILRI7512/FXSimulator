package com.fxsimulator.domain.games

import com.fxsimulator.domain.event.EventCycle
import com.fxsimulator.domain.event.EventMarket
import com.fxsimulator.domain.event.EventPosition
import com.fxsimulator.domain.event.EventPricing
import com.fxsimulator.domain.event.EventRules
import com.fxsimulator.domain.event.EventSettlement
import com.fxsimulator.domain.event.EventSide
import com.fxsimulator.domain.event.EventSettlementOutcome
import com.fxsimulator.domain.event.EventStatus
import com.fxsimulator.domain.event.FixedOddsCycle
import com.fxsimulator.domain.event.FixedOddsOutcome
import com.fxsimulator.domain.event.FixedOddsPosition
import com.fxsimulator.domain.event.FixedOddsRules
import com.fxsimulator.domain.event.FixedOddsSettlement
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.options.OptionPosition
import com.fxsimulator.domain.options.OptionQuote
import com.fxsimulator.domain.options.OptionSettlement
import com.fxsimulator.domain.options.OptionStatus
import com.fxsimulator.domain.options.OptionTenor
import com.fxsimulator.domain.options.OptionType

/**
 * 玩法（事件合约 + 期权）的账本与结算。
 *
 * ## 为什么用独立权益，而不是交易账户的余额
 * 玩法是**零杠杆、无强平、现金结算**的产品，而交易账户是保证金账户（有浮盈、占用保证金、强平线）。
 * 把两者混在同一个 balance 上，会出现「玩法扣款把保证金吃穿 → 触发强平」这种荒谬联动。
 * 所以这里单独记一份 USDT 权益（初始 10,000），与交易账户互不影响。
 *
 * ## 结算时机
 * 只在 [onBars] 被调用时结算，且**必须等回放推进到结算窗口末尾**：
 * 数据不足就留在 OPEN，不提前判（方案 3.4）。
 */
class GamesEngine(initialEquity: Double = 10_000.0) {

    var equity: Double = initialEquity
        private set

    private var nextId = 1L

    val openEvents = ArrayList<EventPosition>()
    val openOptions = ArrayList<OptionPosition>()
    val settledEvents = ArrayList<EventPosition>()
    val settledOptions = ArrayList<OptionPosition>()

    /** 固定赔率制（B 模式）的持仓与已结算。 */
    val openFixed = ArrayList<FixedOddsPosition>()
    val settledFixed = ArrayList<FixedOddsPosition>()

    /** 当前回放日期（UTC），用于日亏损跨日重置。 */
    var dayKey: String = ""
        private set

    /** 当日已实现亏损（只累计亏损，盈利不抵扣）。 */
    var dailyLoss: Double = 0.0
        private set

    /** 累计已实现盈亏（两个玩法合计）。 */
    val realizedPnl: Double
        get() = settledEvents.sumOf { it.realizedPnl ?: 0.0 } +
            settledOptions.sumOf { it.realizedPnl ?: 0.0 } +
            settledFixed.sumOf { it.realizedPnl ?: 0.0 }

    /** 玩法下注上限：单笔 ≤ 权益 5%，单日 ≤ 权益 20%（方案 3.7 护栏）。 */
    /** 单笔上限：权益 5%，但不低于最小名义金额（否则亏到一定程度后再也下不了单）。 */
    fun maxSingleStake(): Double = maxOf(5.0, equity * 0.05)

    fun canAfford(amount: Double): Boolean = amount <= equity + 1e-9

    /**
     * 开一张事件合约。返回错误文案；null 表示成功。
     *
     * @param price 买入价（UP 用 ask、DOWN 用 1 − bid，由调用方算好）
     */
    fun openEvent(
        symbol: String,
        cycle: EventCycle,
        side: EventSide,
        shares: Int,
        price: Double,
        nowMillis: Long,
        bars: List<Candle>,
        expireIndex: Int,
        targetPrice: Double,
    ): String? {
        if (isDailyCapReached()) return "已触发当日亏损上限（${"%.2f".format(dailyLossCap())} USDT），今天不能再下"
        EventRules.validateShares(shares, price)?.let { return it }
        val cost = shares * price * (1.0 + EventRules.FEE_RATE)
        if (!canAfford(cost)) return "权益不足（需 ${"%.2f".format(cost)} USDT）"
        if (cost > maxSingleStake() + 1e-9) {
            return "单笔不能超过权益的 5%（上限 ${"%.2f".format(maxSingleStake())} USDT）"
        }
        val pos = EventPosition(
            id = nextId++,
            symbol = symbol,
            cycle = cycle,
            side = side,
            shares = shares,
            price = price,
            feeRate = EventRules.FEE_RATE,
            openAtMillis = nowMillis,
            expireAtMillis = nowMillis + cycle.minutes * 60_000L,
            expireIndex = expireIndex,
            targetPrice = targetPrice,
        )
        equity -= pos.cost
        openEvents += pos
        return null
    }

    /** 开一张期权（买方）。返回错误文案；null 表示成功。 */
    fun openOption(
        symbol: String,
        quote: OptionQuote,
        tenor: OptionTenor,
        quantity: Double,
        nowMillis: Long,
        expireAtMillis: Long,
        expireIndex: Int,
    ): String? {
        if (isDailyCapReached()) return "已触发当日亏损上限（${"%.2f".format(dailyLossCap())} USDT），今天不能再下"
        if (quantity < 0.01) return "最少 0.01 张"
        val cost = quote.ask * quantity
        if (!canAfford(cost)) return "权益不足（需 ${"%.2f".format(cost)} USDT）"
        // 期权集中度：单合约权利金 ≤ 权益 10%（方案 7.3 护栏）
        if (cost > equity * 0.10 + 1e-9) {
            return "单合约不能超过权益的 10%（上限 ${"%.2f".format(equity * 0.10)} USDT）"
        }
        val pos = OptionPosition(
            id = nextId++,
            symbol = symbol,
            type = quote.type,
            strike = quote.strike,
            tenor = tenor,
            quantity = quantity,
            entryPremium = quote.ask,
            openAtMillis = nowMillis,
            expireAtMillis = expireAtMillis,
            expireIndex = expireIndex,
        )
        equity -= pos.cost
        openOptions += pos
        return null
    }

    /**
     * 推进行情并结算到期的仓位。
     *
     * @return 本次新增结算的仓位数量
     */
    fun onBars(bars: List<Candle>): Int {
        if (bars.isEmpty()) return 0
        // 跨日滚动必须发生在结算**之前**：否则第一次调用会把刚累计的亏损清零
        val utcDay = java.time.Instant.ofEpochMilli(bars.last().timeMillis)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        if (dayKey.isNotEmpty() && utcDay != dayKey) dailyLoss = 0.0
        dayKey = utcDay
        var settled = 0

        val events = ArrayList<EventPosition>(openEvents.size)
        for (p in openEvents) {
            val outcome = EventSettlement.settle(p, bars)
            if (outcome is EventSettlementOutcome.Settled) {
                val done = EventSettlement.apply(p, outcome)
                equity += if (done.won == true) done.payoutIfWin else 0.0
                done.realizedPnl?.let { if (it < 0.0) dailyLoss += -it }
                settledEvents += done
                settled++
            } else {
                events += p
            }
        }
        openEvents.clear()
        openEvents.addAll(events)

        val options = ArrayList<OptionPosition>(openOptions.size)
        for (p in openOptions) {
            val done = OptionSettlement.settle(p, bars)
            if (done != null) {
                equity += done.payout ?: 0.0
                done.realizedPnl?.let { if (it < 0.0) dailyLoss += -it }
                settledOptions += done
                settled++
            } else {
                options += p
            }
        }
        openOptions.clear()
        openOptions.addAll(options)

        // 固定赔率制结算：赢返还 stake×(1+r)，持平退本金，输归零
        val fixed = ArrayList<FixedOddsPosition>(openFixed.size)
        for (p in openFixed) {
            val outcome = FixedOddsSettlement.settle(p, bars)
            if (outcome is FixedOddsOutcome.Settled) {
                val done = FixedOddsSettlement.apply(p, outcome)
                equity += done.refundOnSettle
                val pnl = done.realizedPnl
                if (pnl != null && pnl < 0.0) dailyLoss += -pnl
                settledFixed += done
                settled++
            } else {
                fixed += p
            }
        }
        openFixed.clear()
        openFixed.addAll(fixed)

        return settled
    }

    /**
     * 当前轮次（目标价在轮次开始就固定，之后行情在它两侧波动）。
     *
     * 这个状态是"报价会随时间和价格变化"的前提：目标价恒等于现价的话，
     * 概率永远是 0.5，也就永远看不到 0.01/0.99 的极端值。
     */
    private val rounds = HashMap<String, EventMarket>()

    /**
     * 取（必要时新建）某个「品种+周期」的当前轮次。
     *
     * 轮次到期后自动开新轮：目标价改为当时收盘价，结算下标顺延。
     */
    fun roundFor(symbol: String, cycle: EventCycle, bars: List<Candle>): EventMarket {
        val last = bars.last()
        val key = symbol + "|" + cycle.name
        val existing = rounds[key]
        if (existing != null && last.timeMillis < existing.expireAtMillis) return existing

        val fresh = EventMarket(
            symbol = symbol,
            cycle = cycle,
            openAtMillis = last.timeMillis,
            expireAtMillis = last.timeMillis + cycle.minutes * 60_000L,
            targetPrice = last.close,
            expireIndex = bars.size - 1 + cycle.minutes,
        )
        rounds[key] = fresh
        return fresh
    }

    /** 报价：由现价 / 目标价 / 剩余时间共同决定（物理概率，见 EventPricing）。 */
    fun eventQuote(
        market: EventMarket,
        bars: List<Candle>,
        noise: Double = 0.0,
        sentiment: Double = 0.0,
        formingBars: Int = 0,
        spotOverride: Double? = null,
    ): EventPricing.Quote {
        val last = bars.last()
        val vol = EventPricing.annualizedVol(bars)
        val secondsLeft = ((market.expireAtMillis - last.timeMillis) / 1000.0).coerceAtLeast(0.0)
        // 报价 = 「剩余窗口均价 ≥ 目标价」的概率（时间加权），不是现价对目标价的瞬时比较
        return EventPricing.quote(
            target = market.targetPrice,
            bars = bars,
            expireIndex = market.expireIndex,
            cycleMinutes = market.cycle.minutes,
            annualVol = vol,
            noise = noise,
            sentiment = sentiment,
            formingBars = formingBars,
            spotOverride = spotOverride,
        )
    }

    /** 固定赔率单笔上限（权益 5% 与 250 取小）。 */
    fun maxFixedStake(): Double = FixedOddsRules.maxStake(equity)

    /** 日亏损上限（权益 20%）。 */
    fun dailyLossCap(): Double = equity * FixedOddsRules.DAILY_LOSS_CAP_RATIO

    /** 是否已触发日亏损上限（当日只能看不能下）。 */
    fun isDailyCapReached(): Boolean = dailyLoss >= dailyLossCap() - 1e-9

    /**
     * 开一张固定赔率合约（B 模式）。
     *
     * 与 A 模式的差别：付一笔本金，赢返还 stake×(1+r)，**持平退本金**，输归零，无手续费。
     */
    fun openFixed(
        symbol: String,
        cycle: FixedOddsCycle,
        side: EventSide,
        stake: Double,
        nowMillis: Long,
        expireIndex: Int,
        targetPrice: Double,
    ): String? {
        if (isDailyCapReached()) {
            return "已触发当日亏损上限（${"%.2f".format(dailyLossCap())} USDT），今天不能再下"
        }
        FixedOddsRules.validateStake(stake, equity)?.let { return it }
        val pos = FixedOddsPosition(
            id = nextId++,
            symbol = symbol,
            cycle = cycle,
            side = side,
            stake = stake,
            payoutRate = FixedOddsRules.PAYOUT_RATE,
            openAtMillis = nowMillis,
            expireAtMillis = nowMillis + cycle.minutes * 60_000L,
            expireIndex = expireIndex,
            targetPrice = targetPrice,
        )
        equity -= stake
        openFixed += pos
        return null
    }

    fun reset(initialEquity: Double = 10_000.0) {
        equity = initialEquity
        openEvents.clear()
        openOptions.clear()
        settledEvents.clear()
        settledOptions.clear()
        nextId = 1L
    }

    /** 期权定价用的年化波动率（与事件合约同一口径）。 */
    fun realizedVol(bars: List<Candle>): Double = EventPricing.annualizedVol(bars)
}
