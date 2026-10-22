package com.fxsimulator.domain.guess

import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.SymbolSpecs
import com.fxsimulator.domain.money.PnlCalculator
import com.fxsimulator.engine.FillModel
import com.fxsimulator.indicators.Indicators
import kotlin.random.Random

/**
 * 猜涨跌 · 玩法定义
 *
 * ## 一局是什么
 * 1. 看 60 根 M1（1 小时）的历史，判断接下来 60 根会涨还是会跌；
 * 2. 猜涨 = 做多，猜跌 = 做空。方向按当前价立即成交；
 * 3. 接下来逐根揭晓，**你随时可以点「平仓」把当前的盈亏锁定**；
 * 4. 不点就继续走，走满 60 根按市价强制平仓。
 *
 * ## 为什么是「自己决定什么时候走」而不是预设止盈止损
 * 因为预设的止盈止损是机器在执行规则，而**手动平仓是人在做决定**。
 * 人在有浮盈时会想「先落袋」，在有浮亏时会想「再等等」——
 * 于是统计出来的结果必然是「赢的时候持有很短、亏的时候持有很久」。
 * 这正是真实账户里最常见的死法，也是本项目想让人亲眼看到的东西。
 * 所以战绩里必须同时记录【胜率】【盈亏比】【赢/亏时的平均持有根数】三项。
 *
 * ## 关于「没有技巧」这件事
 * 用内置的 2024-2026 真实 M1 数据做过对照实验：随机方向、跟最近 1/5/15/60 根、
 * 反向跟 5/60 根 —— 七种选向方式的胜率全部落在 46%~49%，
 * 差异全部在噪声范围内。也就是说，**短周期方向不可预测**。
 * 这不是缺陷，这就是这门课本身：期望不是靠猜出来的。
 */

/** 猜的方向 */
enum class GuessDirection(val label: String, val isUp: Boolean) {
    UP("涨", true),
    DOWN("跌", false);

    val trade: com.fxsimulator.domain.model.TradeDirection
        get() = if (isUp) com.fxsimulator.domain.model.TradeDirection.BUY
        else com.fxsimulator.domain.model.TradeDirection.SELL
}

/**
 * 题面。**只包含已经揭晓的部分**，答案（被遮住的未来）不在这里，
 * 因此界面层不可能提前泄露结果。
 *
 * @param unit 入场前的波动尺度（ATR）。用于界面提示「这一段行情有多大波动」，
 *             以及复盘时对照「如果当初设了固定止损会怎样」。
 * @param spread 入场时的真实点差，取自数据本身的点差列。
 *               用真实点差而不是一个固定值，是因为不同年份的点差差异很大
 *               （0.16 ~ 0.28），而它直接决定每一局的成本。
 */
data class GuessQuestion(
    val number: Int,
    val month: String,
    val revealed: List<Candle>,
    val unit: Double,
    val spread: Double,
    /**
     * 锚点（题面最后一根）在**整段数据**里的下标。
     *
     * 注意这**不是** revealed.lastIndex —— 那个恒等于 REVEAL_BARS-1，
     * 拿它去索引原数据会永远取到同一段未来。这个错误被单测抓到过。
     */
    val sourceIndex: Int,
    /**
     * 这段行情属于哪个品种。
     *
     * **猜之前就要让玩家看见** —— 连自己猜的是黄金还是欧元都不知道，"分析"无从谈起。
     * 刻意只显示品种、不显示月份：品种不影响可预测性，
     * 但"我知道那阵子在涨"会直接把题目毁掉。
     */
    val symbol: String = "XAUUSD",
    /**
     * 出题那一刻的合约规格，结算必须用它。
     *
     * 不能用"当前选中的品种"：玩家在揭晓途中切了品种，已经出的题不能因此换一套
     * 合约重算盈亏 —— 黄金 100 盎司与外汇 10 万货币单位差了三个数量级，
     * 那一下就能把战绩打成垃圾。
     */
    val spec: SymbolSpec = SymbolSpecs.of("XAUUSD"),
) {
    val anchor: Double get() = revealed.last().close
    val anchorTimeMillis: Long get() = revealed.last().timeMillis
}

/** 一局的结果 */
enum class GuessOutcome { WIN, LOSS, FLAT }

/** 平仓方式 */
enum class CloseKind(val label: String) {
    /** 玩家自己点的平仓 */
    MANUAL("手动平仓"),
    /** 走满 60 根自动平仓 */
    TIMEOUT("时间到 · 自动平仓"),
}

/**
 * 结算详情。字段刻意做全，因为「复盘」才是这个游戏的价值所在：
 * 光说一句「你亏了」没有意义，要能回答「你在哪一步做的决定」。
 */
data class GuessResult(
    val direction: GuessDirection,
    /** 实际成交的入场价（已含点差） */
    val entryPrice: Double,
    val exitPrice: Double,
    val outcome: GuessOutcome,
    val closeKind: CloseKind,
    /** 收益率，例如 -0.0042 表示 -0.42% */
    val pct: Double,
    /** 虚拟美元盈亏 */
    val profit: Double,
    /** 从入场到出场经过了多少根 M1 */
    val barsHeld: Int,
    /** 持有期间最大浮盈（价格单位，正数） */
    val maxFavorable: Double,
    /** 持有期间最大浮亏（价格单位，负数） */
    val maxAdverse: Double,
    /**
     * 复盘对照：如果入场时就挂一个 REFERENCE_STOP_FACTOR × unit 的固定止损，
     * 会在第几根被触发（1-based）。-1 表示到走完都没触发。
     * 这是为了回答那个最扎心的问题：「你本来可以只亏这么多。」
     */
    val referenceStopAt: Int,
    /** 参照止损挂在哪里（价格）。referenceStopAt < 0 时无意义。 */
    val referenceStopPrice: Double,
    /** 参照止损会亏多少（美元，负数）。referenceStopAt < 0 时无意义。 */
    val referenceStopProfit: Double,
) {
    val isWin: Boolean get() = outcome == GuessOutcome.WIN

    /** 是不是「赢一点就跑」：赢了但持有不超过 1/4 的窗口 */
    val ranEarly: Boolean get() = isWin && closeKind == CloseKind.MANUAL && barsHeld <= 15
}

/**
 * 累计战绩。这是游戏的「身份」：胜率、盈亏比、持有时间、权益全从这里出。
 *
 * 胜率与盈亏比是同一枚硬币的两面，界面上必须同时显示，
 * 否则玩家只会盯着胜率，然后奇怪账户为什么在缩水。
 * 持有时间则是行为层的证据 —— 它解释盈亏比为什么会是那个值。
 */
data class GuessStats(
    val total: Int = 0,
    val correct: Int = 0,
    /** 当前连对；猜错归零 */
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val equity: Double = 0.0,
    val initialEquity: Double = 0.0,
    val sumWinPct: Double = 0.0,
    val sumLossPct: Double = 0.0,
    val wins: Int = 0,
    val losses: Int = 0,
    /** 赢的那些局，持有根数之和 */
    val sumWinBars: Int = 0,
    /** 亏的那些局，持有根数之和 */
    val sumLossBars: Int = 0,
    val manualCloses: Int = 0,
    val timeoutCloses: Int = 0,
) {
    val winRate: Double get() = if (total == 0) 0.0 else correct.toDouble() / total

    /** 相对「抛硬币」的优势。长期应该贴着 0 —— 这正是课程本身。 */
    val edge: Double get() = winRate - 0.5

    val avgWinPct: Double get() = if (wins == 0) 0.0 else sumWinPct / wins

    val avgLossPct: Double get() = if (losses == 0) 0.0 else sumLossPct / losses

    /** 实际盈亏比。1.0 以下意味着「赢的没亏的多」。 */
    val payoff: Double get() =
        if (losses == 0 || avgLossPct <= 0.0) 0.0 else avgWinPct / avgLossPct

    /** 在当前盈亏比下，要打平需要多高的胜率 */
    val breakEvenWinRate: Double get() =
        if (payoff <= 0.0) 0.0 else 1.0 / (1.0 + payoff)

    val returnPct: Double get() =
        if (initialEquity <= 0.0) 0.0 else equity / initialEquity - 1.0

    /** 赢的时候平均持有几根 K 线 */
    val avgWinBars: Double get() = if (wins == 0) 0.0 else sumWinBars.toDouble() / wins

    /** 亏的时候平均持有几根 K 线 */
    val avgLossBars: Double get() = if (losses == 0) 0.0 else sumLossBars.toDouble() / losses

    /**
     * 持有时间比 = 亏损时持有时间 ÷ 盈利时持有时间。
     * > 1 说明「亏了死扛、赚了就跑」。这是处置效应的直接量化。
     * 样本不足时返回 0（界面按「数据不足」处理，不显示假数值）。
     */
    val holdRatio: Double get() =
        if (wins == 0 || losses == 0 || avgWinBars <= 0.0) 0.0 else avgLossBars / avgWinBars

    /** 已爆仓：权益归零或为负 */
    val busted: Boolean get() = total > 0 && equity <= 0.0

    /** 每局平均结果（美元）。负得多，说明这个游戏本身在被成本消耗。 */
    val avgProfit: Double get() = if (total == 0) 0.0 else (equity - initialEquity) / total
}

/**
 * 记分。纯函数，不依赖任何 Android 设施，因此可以完整单测。
 */
object GuessScore {

    fun initial(equity: Double): GuessStats =
        GuessStats(equity = equity, initialEquity = equity)

    /** 把一局结果并进战绩 */
    fun apply(stats: GuessStats, result: GuessResult): GuessStats {
        val win = result.outcome == GuessOutcome.WIN
        val flat = result.outcome == GuessOutcome.FLAT
        val absPct = if (result.pct < 0) -result.pct else result.pct
        return stats.copy(
            total = stats.total + 1,
            correct = stats.correct + (if (win) 1 else 0),
            streak = if (win) stats.streak + 1 else 0,
            bestStreak = if (win) maxOf(stats.bestStreak, stats.streak + 1) else stats.bestStreak,
            equity = stats.equity + result.profit,
            sumWinPct = if (win) stats.sumWinPct + absPct else stats.sumWinPct,
            sumLossPct = if (!win && !flat) stats.sumLossPct + absPct else stats.sumLossPct,
            wins = stats.wins + (if (win) 1 else 0),
            losses = stats.losses + (if (!win && !flat) 1 else 0),
            sumWinBars = if (win) stats.sumWinBars + result.barsHeld else stats.sumWinBars,
            sumLossBars = if (!win && !flat) stats.sumLossBars + result.barsHeld else stats.sumLossBars,
            manualCloses = stats.manualCloses +
                (if (result.closeKind == CloseKind.MANUAL) 1 else 0),
            timeoutCloses = stats.timeoutCloses +
                (if (result.closeKind == CloseKind.TIMEOUT) 1 else 0),
        )
    }

    /** 重置战绩（保留初始权益） */
    fun reset(stats: GuessStats): GuessStats = initial(stats.initialEquity)
}

/**
 * 判定规则。全部是纯函数 —— 出题、浮动盈亏、结算、复盘对照，都可以脱离 Android 单测。
 */
object GuessRule {

    /** 算 unit 用的 ATR 周期 */
    const val ATR_PERIOD = 14

    /**
     * 猜涨跌的默认手数（虚拟）。
     *
     * ⚠️ **同一个手数在不同品种上的风险差很多**，因为合约规模不同：
     * 黄金 1 手 = 100 盎司、白银 1 手 = 5000 金衡盎司、外汇 1 手 = 10 万货币单位。
     * 所以界面必须把「这一局 1 个 ATR 的波动等于多少美元」摆出来，
     * 否则玩家无从判断自己押了多大 —— 也不要指望靠手数本身去比较风险。
     */
    const val DEFAULT_VOLUME = 1.0

    /** 复盘对照用的止损倍数 */
    const val REFERENCE_STOP_FACTOR = 1.5

    /** 一局最多持有多少根（与 [GuessDealer.HIDDEN_BARS] 一致） */
    const val MAX_HOLD_BARS = GuessDealer.HIDDEN_BARS

    /**
     * 入场前的波动尺度。
     * 优先用 ATR；数据太少或 ATR 失效时退化为「最近若干根的平均全幅」，
     * 保证任何情况下都能出题。
     */
    fun unit(revealed: List<Candle>): Double {
        if (revealed.isEmpty()) return 0.0
        val atr = Indicators.lastValid(Indicators.atr(revealed, ATR_PERIOD))
        if (atr != null && atr > 0.0 && atr.isFinite()) return atr
        val tail = revealed.takeLast(ATR_PERIOD)
        var sum = 0.0
        for (c in tail) sum += c.high - c.low
        val avg = sum / tail.size
        return if (avg > 0.0 && avg.isFinite()) avg else 1.0
    }

    /** 猜涨按 ASK 买入，猜跌按 BID 卖出 */
    fun entryPrice(direction: GuessDirection, anchor: Double, spread: Double): Double =
        FillModel.entryPrice(direction.trade, anchor, spread)

    /** 按某个 BID 报价平仓能拿到的成交价 */
    fun exitPrice(direction: GuessDirection, bid: Double, spread: Double): Double =
        FillModel.exitPrice(direction.trade, bid, spread)

    /** 一笔完整交易的美元盈亏（已扣佣金） */
    fun pnl(
        direction: GuessDirection,
        entry: Double,
        exit: Double,
        spec: SymbolSpec,
        volume: Double = DEFAULT_VOLUME,
    ): Double {
        val gross = PnlCalculator.grossProfit(spec, direction.trade, volume, entry, exit)
        return gross - PnlCalculator.commission(spec, volume)
    }

    /**
     * 持仓中的浮动盈亏。
     * 界面每揭晓一根就调一次，因此这里必须便宜、无副作用。
     *
     * @param bid 当前 BID 报价（通常是刚揭晓那根的收盘价）
     */
    fun floating(
        direction: GuessDirection,
        entry: Double,
        bid: Double,
        spread: Double,
        spec: SymbolSpec,
        volume: Double = DEFAULT_VOLUME,
    ): Double = pnl(direction, entry, exitPrice(direction, bid, spread), spec, volume)

    /**
     * 结算一局。
     *
     * @param anchor 入场时的 BID 报价（题面最后一根 K 线的收盘价）
     * @param hidden 被遮住的未来 K 线，必须非空
     * @param barsHeld 已经持有了几根（1..hidden.size）。
     *                 手动平仓时 = 已揭晓的根数；时间到 = hidden.size。
     * @param closedByHand true = 玩家点的平仓，false = 走满时间
     *
     * @return null 表示参数非法（没有任何可结算的 K 线）
     */
    fun settle(
        direction: GuessDirection,
        anchor: Double,
        unit: Double,
        hidden: List<Candle>,
        barsHeld: Int,
        spread: Double,
        spec: SymbolSpec,
        closedByHand: Boolean,
        volume: Double = DEFAULT_VOLUME,
    ): GuessResult? {
        if (hidden.isEmpty()) return null
        val held = barsHeld.coerceIn(1, hidden.size)
        val entry = entryPrice(direction, anchor, spread)

        var maxFav = 0.0
        var maxAdv = 0.0
        for (i in 0 until held) {
            val c = hidden[i]
            val hi = (c.high - entry) * direction.trade.sign
            val lo = (c.low - entry) * direction.trade.sign
            if (hi > maxFav) maxFav = hi
            if (lo < maxAdv) maxAdv = lo
        }

        val exit = exitPrice(direction, hidden[held - 1].close, spread)
        val profit = pnl(direction, entry, exit, spec, volume)
        val pct = if (entry > 0.0) (exit - entry) * direction.trade.sign / entry else 0.0
        val outcome = when {
            profit > 0.0 -> GuessOutcome.WIN
            profit < 0.0 -> GuessOutcome.LOSS
            else -> GuessOutcome.FLAT
        }

        val ref = referenceStop(direction, entry, unit, hidden, spread, spec, volume)

        return GuessResult(
            direction = direction,
            entryPrice = entry,
            exitPrice = exit,
            outcome = outcome,
            closeKind = if (closedByHand) CloseKind.MANUAL else CloseKind.TIMEOUT,
            pct = pct,
            profit = profit,
            barsHeld = held,
            maxFavorable = maxFav,
            maxAdverse = maxAdv,
            referenceStopAt = ref?.at ?: -1,
            referenceStopPrice = ref?.price ?: 0.0,
            referenceStopProfit = ref?.profit ?: 0.0,
        )
    }

    /**
     * 复盘对照：如果入场时就挂了一个固定止损，会在第几根被触发、亏多少。
     * 扫描**全部**未来（而不是只扫描已经持有的部分），因为要回答的是
     * 「如果我一开始就打算止损，会怎样」。
     *
     * @return 触发位置/价位/盈亏，没触发返回 null
     */
    private fun referenceStop(
        direction: GuessDirection,
        entry: Double,
        unit: Double,
        hidden: List<Candle>,
        spread: Double,
        spec: SymbolSpec,
        volume: Double,
    ): RefStop? {
        if (!(unit > 0.0)) return null
        val level = if (direction.isUp) entry - REFERENCE_STOP_FACTOR * unit
        else entry + REFERENCE_STOP_FACTOR * unit
        for (i in hidden.indices) {
            val c = hidden[i]
            val touched = if (direction.isUp) c.low <= level else c.high >= level
            if (touched) {
                val exit = exitPrice(direction, level, spread)
                return RefStop(i + 1, level, pnl(direction, entry, exit, spec, volume))
            }
        }
        return null
    }
}

/** 参照止损的扫描结果 */
private data class RefStop(val at: Int, val price: Double, val profit: Double)

/**
 * 出题器。从一段行情里随机截取「已揭晓的一段 + 被遮住的一段」。
 *
 * 随机源由调用方传入，因此测试里可以用固定种子复现同一道题。
 */
object GuessDealer {

    /** 题面显示多少根 K 线（1 小时 M1） */
    const val REVEAL_BARS = 60

    /** 遮住多少根作为答案（又 1 小时 M1） */
    const val HIDDEN_BARS = 60

    /** 至少需要多少根数据才能出题 */
    const val MIN_BARS = REVEAL_BARS + HIDDEN_BARS + 1

    /**
     * 出一道题。
     *
     * 锚点索引的取值范围保证前后都够：[REVEAL_BARS]-1 .. size-[HIDDEN_BARS]-1。
     *
     * @return null 表示数据不够出题
     */
    fun question(
        bars: List<Candle>,
        month: String,
        number: Int,
        random: Random,
        fallbackSpread: Double = 0.20,
        symbol: String = "XAUUSD",
        spec: SymbolSpec = SymbolSpecs.of("XAUUSD"),
    ): GuessQuestion? {
        if (bars.size < MIN_BARS) return null
        val from = REVEAL_BARS - 1
        val until = bars.size - HIDDEN_BARS
        if (until <= from) return null
        val anchorIdx = random.nextInt(from, until)
        val revealed = bars.subList(anchorIdx - REVEAL_BARS + 1, anchorIdx + 1).toList()
        val rawSpread = revealed.last().spread
        val anchorPrice = revealed.last().close
        // 第二道防线：点差必须是「价格单位」且量级合理。
        //
        // 解析器一旦给出以**点**为单位的原始值（200 而不是 0.200），入场价会被抬高
        // 整整 200 美元 —— 初始权益 10000，一局就打成负数。这个坑真实存在过。
        // 所以只接受 0 < spread ≤ 价格的 1%，超出一律退回保守默认值，绝不假装点差为 0。
        val usable = rawSpread > 0.0 && rawSpread.isFinite() && rawSpread <= anchorPrice * 0.01
        val spread = if (usable) rawSpread else {
            if (fallbackSpread > 0.0 && fallbackSpread.isFinite()) fallbackSpread else 0.20
        }
        return GuessQuestion(
            number = number,
            month = month,
            revealed = revealed,
            unit = GuessRule.unit(revealed),
            spread = spread,
            sourceIndex = anchorIdx,
            symbol = symbol,
            spec = spec,
        )
    }

    /** 取出某道题的答案（被遮住的未来）。只有入场之后才按需调用。 */
    fun hidden(bars: List<Candle>, question: GuessQuestion): List<Candle> {
        val start = question.sourceIndex + 1
        if (start < 0 || start >= bars.size) return emptyList()
        val end = minOf(bars.size, start + HIDDEN_BARS)
        return bars.subList(start, end).toList()
    }
}
