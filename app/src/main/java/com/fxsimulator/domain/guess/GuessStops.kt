package com.fxsimulator.domain.guess

import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.SymbolSpec

/**
 * 这一轮是怎么结束的。
 *
 * 原来的玩法只有「手动平仓 / 时间到」两种；加了止损止盈之后，
 * 一轮的结束方式才真正开始决定结果 —— 所以要单独建模、单独统计。
 */
enum class ExitKind(val label: String) {
    STOP_LOSS("止损"),
    TAKE_PROFIT("止盈"),
    TIMEOUT("时间到"),
    MANUAL("手动平仓"),
}

/** 一轮的结算结果 */
data class StopSettle(
    val entryPrice: Double,
    val exitPrice: Double,
    val barsHeld: Int,
    val kind: ExitKind,
    val profit: Double,
)

/**
 * 带止损止盈的逐根判定。
 *
 * ## 两个必须写死的口径
 * 1. **只看 BID 的 high/low**：蜡烛图的报价就是 BID，与 [GuessRule.referenceStop]
 *    保持一致。否则止损线会整体偏移一个点差，回测和实战永远差一点点。
 * 2. **同一根 K 线里止损与止盈都被打穿时，按止损算**。M1 分不清先后，
 *    宁可让玩家觉得吃亏，也不能给出一个可能是假的好消息。
 *
 * 距离单位是「点」（10^-digits），由调用方从 ATR 推出预填值 ——
 * 不同品种一个「点」的经济含义差很远，让玩家自己填数字时必须配一个合理默认。
 */
object GuessStops {

    fun settle(
        direction: GuessDirection,
        anchor: Double,
        hidden: List<Candle>,
        spread: Double,
        spec: SymbolSpec,
        volume: Double,
        slPoints: Int,
        tpPoints: Int,
        maxBars: Int = GuessDealer.HIDDEN_BARS,
    ): StopSettle? {
        if (hidden.isEmpty()) return null
        val entry = GuessRule.entryPrice(direction, anchor, spread)
        val point = spec.point
        val sign = direction.trade.sign
        val slDist = if (slPoints > 0) slPoints * point else 0.0
        val tpDist = if (tpPoints > 0) tpPoints * point else 0.0
        val slLevel = entry - sign * slDist
        val tpLevel = entry + sign * tpDist
        val last = minOf(hidden.size, maxBars)

        for (i in 0 until last) {
            val c = hidden[i]
            val hitSl = slDist > 0.0 &&
                (if (direction.isUp) c.low <= slLevel else c.high >= slLevel)
            val hitTp = tpDist > 0.0 &&
                (if (direction.isUp) c.high >= tpLevel else c.low <= tpLevel)
            if (hitSl) {
                val exit = GuessRule.exitPrice(direction, slLevel, spread)
                return StopSettle(
                    entry, exit, i + 1, ExitKind.STOP_LOSS,
                    GuessRule.pnl(direction, entry, exit, spec, volume),
                )
            }
            if (hitTp) {
                val exit = GuessRule.exitPrice(direction, tpLevel, spread)
                return StopSettle(
                    entry, exit, i + 1, ExitKind.TAKE_PROFIT,
                    GuessRule.pnl(direction, entry, exit, spec, volume),
                )
            }
        }
        val exit = GuessRule.exitPrice(direction, hidden[last - 1].close, spread)
        return StopSettle(
            entry, exit, last, ExitKind.TIMEOUT,
            GuessRule.pnl(direction, entry, exit, spec, volume),
        )
    }
}

/**
 * 一轮跑完（或爆仓）的评价。
 *
 * 评分刻意**不只看赚了多少**：存活、胜率、以及「有没有设止损」三项各占一块。
 * 如果只按收益评级，那这个玩法就在奖励运气，和它想教的东西正好相反。
 */
data class GuessRunSummary(
    val rounds: Int,
    val maxRounds: Int,
    val startEquity: Double,
    val endEquity: Double,
    val busted: Boolean,
    val wins: Int,
    val losses: Int,
    val stopLossRounds: Int,
    val stopLossHit: Int,
    val grade: String,
    val comment: String,
) {
    val returnPct: Double get() = if (startEquity <= 0.0) 0.0 else endEquity / startEquity - 1.0

    val winRate: Double get() = if (rounds <= 0) 0.0 else wins.toDouble() / rounds

    /** 纪律分：有多少比例的交易是设了止损才入场的 */
    val discipline: Double get() = if (rounds <= 0) 0.0 else stopLossRounds.toDouble() / rounds
}

/** 评级。纯函数，可单测。 */
object GuessGrade {

    /** 一轮最多几局 */
    const val MAX_ROUNDS = 30

    fun evaluate(
        startEquity: Double,
        endEquity: Double,
        busted: Boolean,
        rounds: Int,
        wins: Int,
        losses: Int,
        stopLossRounds: Int,
        stopLossHit: Int,
    ): GuessRunSummary {
        val ret = if (startEquity <= 0.0) 0.0 else endEquity / startEquity - 1.0
        val winRate = if (rounds <= 0) 0.0 else wins.toDouble() / rounds
        val discipline = if (rounds <= 0) 0.0 else stopLossRounds.toDouble() / rounds

        var score = 0
        if (!busted) score += 40
        score += when {
            ret >= 0.20 -> 30
            ret >= 0.05 -> 22
            ret >= 0.0 -> 14
            ret >= -0.10 -> 6
            else -> 0
        }
        score += when {
            winRate >= 0.55 -> 15
            winRate >= 0.45 -> 10
            else -> 4
        }
        score += when {
            discipline >= 0.8 -> 15
            discipline >= 0.5 -> 8
            else -> 0
        }

        val grade = when {
            score >= 85 -> "S"
            score >= 70 -> "A"
            score >= 55 -> "B"
            score >= 40 -> "C"
            else -> "D"
        }
        val comment = when {
            busted ->
                "第 " + rounds + " 轮爆仓。打掉你的不是判断，是手数 —— " +
                    "一次错误就吃掉了全部权益。"
            ret >= 0.20 ->
                "活满 " + rounds + " 轮还赚了两成以上：判断与仓位同时在线。"
            discipline < 0.3 ->
                "活下来了，但几乎没有一局设止损。这次是运气，不是方法。"
            winRate >= 0.55 ->
                "胜率过半而仓位克制 —— 这种组合长期才有意义。"
            ret < 0.0 ->
                "存活但权益缩水。问题多半在「亏的比赢的大」，去复盘盈亏比。"
            else ->
                "中规中矩地走完全程：稳住仓位比偶尔押中更重要。"
        }
        return GuessRunSummary(
            rounds = rounds,
            maxRounds = MAX_ROUNDS,
            startEquity = startEquity,
            endEquity = endEquity,
            busted = busted,
            wins = wins,
            losses = losses,
            stopLossRounds = stopLossRounds,
            stopLossHit = stopLossHit,
            grade = grade,
            comment = comment,
        )
    }
}
