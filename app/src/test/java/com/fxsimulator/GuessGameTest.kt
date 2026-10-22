package com.fxsimulator

import com.fxsimulator.domain.guess.CloseKind
import com.fxsimulator.domain.guess.GuessDealer
import com.fxsimulator.domain.guess.GuessDirection
import com.fxsimulator.domain.guess.GuessOutcome
import com.fxsimulator.domain.guess.GuessResult
import com.fxsimulator.domain.guess.GuessRule
import com.fxsimulator.domain.guess.GuessScore
import com.fxsimulator.domain.guess.GuessStats
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.SymbolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * 猜涨跌玩法的完整回归测试。
 *
 * 分三层：
 * 1. **规则层** —— 成交价、方向、点差成本、结算、复盘对照，逐条钉死；
 * 2. **出题层** —— 随机窗口不越界、答案不泄漏、同种子可复现；
 * 3. **行为层** —— 用随机游走模拟一个「赚了就跑、亏了死扛」的玩家，
 *    断言它必然产出「持有时间比 > 1、盈亏比 < 1」。
 *    这一条是这个玩法存在的理由：它必须真的能照出真实账户里的那个问题，
 *    而不是只在文案里声称能。
 */
class GuessGameTest {

    private val spec = SymbolSpec()

    private fun bar(
        i: Int,
        o: Double,
        h: Double,
        l: Double,
        c: Double,
        spread: Double = 0.20,
    ) = Candle(
        timeMillis = 1_600_000_000_000L + i * 60_000L,
        open = o,
        high = h,
        low = l,
        close = c,
        volume = 100L,
        spread = spread,
    )

    /** 造一段收盘价按 path 走的 K 线，上下各留 wick 的影线 */
    private fun series(
        path: List<Double>,
        wick: Double = 0.30,
        spread: Double = 0.20,
    ): List<Candle> = path.mapIndexed { i, c ->
        val o = if (i == 0) c else path[i - 1]
        bar(i, o, maxOf(o, c) + wick, minOf(o, c) - wick, c, spread)
    }

    /** 固定种子的随机游走，当作「市场」 */
    private fun randomWalk(n: Int, seed: Int, step: Double, start: Double): List<Candle> {
        val rnd = Random(seed)
        val out = ArrayList<Candle>(n)
        var prev = start
        for (i in 0 until n) {
            val o = prev
            val c = o + (rnd.nextDouble() - 0.5) * 2.0 * step
            val hi = maxOf(o, c) + rnd.nextDouble() * step * 0.4
            val lo = minOf(o, c) - rnd.nextDouble() * step * 0.4
            out.add(bar(i, o, hi, lo, c, 0.20))
            prev = c
        }
        return out
    }

    /** 直接构造一个战绩输入，脱离真实市场，专测记分逻辑 */
    private fun result(
        pct: Double,
        bars: Int,
        manual: Boolean = true,
        direction: GuessDirection = GuessDirection.UP,
    ): GuessResult {
        val sign = if (direction.isUp) 1.0 else -1.0
        return GuessResult(
            direction = direction,
            entryPrice = 2000.0,
            exitPrice = 2000.0 * (1.0 + pct * sign),
            outcome = when {
                pct > 0.0 -> GuessOutcome.WIN
                pct < 0.0 -> GuessOutcome.LOSS
                else -> GuessOutcome.FLAT
            },
            closeKind = if (manual) CloseKind.MANUAL else CloseKind.TIMEOUT,
            pct = pct,
            profit = pct * 10_000.0,
            barsHeld = bars,
            maxFavorable = 0.0,
            maxAdverse = 0.0,
            referenceStopAt = -1,
            referenceStopPrice = 0.0,
            referenceStopProfit = 0.0,
        )
    }

    // ==================== 规则层 ====================

    @Test
    fun unitIsPositiveOnNormalData() {
        val bars = series(List(60) { 2000.0 + (it % 5) * 0.4 })
        val u = GuessRule.unit(bars)
        assertTrue("unit 应为正数，实际 $u", u > 0.0 && u.isFinite())
    }

    @Test
    fun unitFallsBackWhenThereIsNotEnoughDataForAtr() {
        val bars = series(listOf(2000.0, 2001.0, 2000.5))
        val u = GuessRule.unit(bars)
        assertTrue("数据不足时也要给出可用的波动尺度，实际 $u", u > 0.0 && u.isFinite())
    }

    @Test
    fun unitIsZeroOnEmptyInput() {
        assertEquals(0.0, GuessRule.unit(emptyList()), 1e-12)
    }

    @Test
    fun buyingPaysTheSpreadAndSellingReceivesBid() {
        // 猜涨 = 做多，买入成交在 ASK；猜跌 = 做空，卖出成交在 BID
        assertEquals(2000.20, GuessRule.entryPrice(GuessDirection.UP, 2000.0, 0.20), 1e-9)
        assertEquals(2000.00, GuessRule.entryPrice(GuessDirection.DOWN, 2000.0, 0.20), 1e-9)
    }

    @Test
    fun closingLongGetsBidAndClosingShortPaysAsk() {
        assertEquals(2010.00, GuessRule.exitPrice(GuessDirection.UP, 2010.0, 0.20), 1e-9)
        assertEquals(2010.20, GuessRule.exitPrice(GuessDirection.DOWN, 2010.0, 0.20), 1e-9)
    }

    @Test
    fun pnlFollowsTheDirection() {
        // 1 手 = 100 盎司，价格动 10 美元 = 1000 美元
        assertEquals(1000.0, GuessRule.pnl(GuessDirection.UP, 2000.0, 2010.0, spec), 1e-6)
        assertEquals(-1000.0, GuessRule.pnl(GuessDirection.UP, 2010.0, 2000.0, spec), 1e-6)
        assertEquals(1000.0, GuessRule.pnl(GuessDirection.DOWN, 2010.0, 2000.0, spec), 1e-6)
        assertEquals(-1000.0, GuessRule.pnl(GuessDirection.DOWN, 2000.0, 2010.0, spec), 1e-6)
    }

    @Test
    fun floatingTracksTheCurrentQuote() {
        // 入场 2000.20，现在 BID 2005 → 平仓拿 2005 → 赚 4.8 点 = 480 美元
        val v = GuessRule.floating(GuessDirection.UP, 2000.20, 2005.0, 0.20, spec)
        assertEquals(480.0, v, 1e-6)
    }

    @Test
    fun guessingUpWinsWhenPriceRises() {
        val hidden = series(List(10) { 2010.0 + it })
        val r = GuessRule.settle(
            GuessDirection.UP, 2000.0, 1.0, hidden, 10, 0.20, spec, closedByHand = false,
        )
        assertNotNull(r)
        assertEquals(GuessOutcome.WIN, r!!.outcome)
        assertTrue(r.profit > 0.0)
    }

    @Test
    fun guessingDownWinsWhenPriceFalls() {
        val hidden = series(List(10) { 1990.0 - it })
        val r = GuessRule.settle(
            GuessDirection.DOWN, 2000.0, 1.0, hidden, 10, 0.20, spec, closedByHand = false,
        )!!
        assertEquals(GuessOutcome.WIN, r.outcome)
        assertTrue(r.profit > 0.0)
    }

    @Test
    fun aMarketThatDoesNotMoveStillLosesBecauseOfTheSpread() {
        // 价格一动没动，但买入付一次点差、平仓再付一次 —— 两个方向都必亏
        val hidden = series(List(10) { 2000.0 }, wick = 0.0)
        val up = GuessRule.settle(
            GuessDirection.UP, 2000.0, 1.0, hidden, 10, 0.20, spec, closedByHand = false,
        )!!
        val dn = GuessRule.settle(
            GuessDirection.DOWN, 2000.0, 1.0, hidden, 10, 0.20, spec, closedByHand = false,
        )!!
        assertEquals(-20.0, up.profit, 1e-6)
        assertEquals(-20.0, dn.profit, 1e-6)
    }

    @Test
    fun barsHeldIsClampedIntoTheAvailableWindow() {
        val hidden = series(List(10) { 2005.0 + it })
        val over = GuessRule.settle(
            GuessDirection.UP, 2000.0, 1.0, hidden, 999, 0.20, spec, closedByHand = false,
        )!!
        assertEquals(10, over.barsHeld)
        val under = GuessRule.settle(
            GuessDirection.UP, 2000.0, 1.0, hidden, 0, 0.20, spec, closedByHand = false,
        )!!
        assertEquals(1, under.barsHeld)
    }

    @Test
    fun closeKindTellsHowTheRoundEnded() {
        val hidden = series(List(10) { 2005.0 + it })
        val manual = GuessRule.settle(
            GuessDirection.UP, 2000.0, 1.0, hidden, 3, 0.20, spec, closedByHand = true,
        )!!
        assertEquals(CloseKind.MANUAL, manual.closeKind)
        val auto = GuessRule.settle(
            GuessDirection.UP, 2000.0, 1.0, hidden, 10, 0.20, spec, closedByHand = false,
        )!!
        assertEquals(CloseKind.TIMEOUT, auto.closeKind)
    }

    @Test
    fun settleRejectsAnEmptyFuture() {
        assertNull(
            GuessRule.settle(
                GuessDirection.UP, 2000.0, 1.0, emptyList(), 1, 0.20, spec, closedByHand = false,
            ),
        )
    }

    @Test
    fun resultCarriesTheBestAndWorstExcursion() {
        val hidden = listOf(
            bar(0, 2000.0, 2005.0, 1999.0, 2001.0),
            bar(1, 2001.0, 2012.0, 1990.0, 2002.0),
        )
        val r = GuessRule.settle(
            GuessDirection.UP, 2000.0, 1.0, hidden, 2, 0.20, spec, closedByHand = false,
        )!!
        // 入场 2000.20；最高 2012 → +11.8；最低 1990 → -10.2
        assertEquals(11.8, r.maxFavorable, 1e-6)
        assertEquals(-10.2, r.maxAdverse, 1e-6)
    }

    @Test
    fun pctIsMeasuredAgainstTheEntryPrice() {
        val hidden = series(List(10) { 2010.0 + it })
        val r = GuessRule.settle(
            GuessDirection.UP, 2000.0, 1.0, hidden, 10, 0.20, spec, closedByHand = false,
        )!!
        val expected = (2019.0 - 2000.20) / 2000.20
        assertEquals(expected, r.pct, 1e-9)
    }

    // ==================== 复盘对照 ====================

    @Test
    fun referenceStopFindsWhereAFixedStopWouldHaveFired() {
        // unit=2.0 → 参照止损在 2000.20 - 3.0 = 1997.20
        val hidden = listOf(
            bar(0, 2000.0, 2001.0, 1999.0, 2000.5),
            bar(1, 2000.5, 2001.0, 1996.0, 1997.0),
        )
        val r = GuessRule.settle(
            GuessDirection.UP, 2000.0, 2.0, hidden, 2, 0.20, spec, closedByHand = false,
        )!!
        assertEquals(2, r.referenceStopAt)
        assertTrue("参照止损的亏损应该是负数", r.referenceStopProfit < 0.0)
    }

    @Test
    fun referenceStopIsMinusOneWhenNeverTouched() {
        val hidden = series(List(10) { 2001.0 + it })
        val r = GuessRule.settle(
            GuessDirection.UP, 2000.0, 2.0, hidden, 5, 0.20, spec, closedByHand = false,
        )!!
        assertEquals(-1, r.referenceStopAt)
    }

    @Test
    fun referenceStopScansBeyondTheHoldingWindow() {
        // 玩家第 1 根就手动跑了，但复盘必须告诉他：止损本来会在第 2 根被打掉
        val hidden = listOf(
            bar(0, 2000.0, 2001.0, 1999.0, 2000.0),
            bar(1, 2000.0, 2001.0, 1995.0, 1996.0),
        )
        val r = GuessRule.settle(
            GuessDirection.UP, 2000.0, 2.0, hidden, 1, 0.20, spec, closedByHand = true,
        )!!
        assertEquals(1, r.barsHeld)
        assertEquals(2, r.referenceStopAt)
    }

    // ==================== 出题层 ====================

    @Test
    fun dealerProducesTheRequestedWindow() {
        val bars = series(List(500) { 2000.0 + (it % 11) * 0.5 })
        val q = GuessDealer.question(bars, "2024-03", 7, Random(1))!!
        assertEquals(GuessDealer.REVEAL_BARS, q.revealed.size)
        assertEquals(7, q.number)
        assertEquals("2024-03", q.month)
        assertEquals(q.revealed.last().close, q.anchor, 1e-12)
    }

    @Test
    fun dealerReturnsNullWhenDataIsTooShort() {
        val bars = series(List(50) { 2000.0 + it * 0.1 })
        assertNull(GuessDealer.question(bars, "2024-03", 1, Random(1)))
    }

    @Test
    fun theAnswerNeverOverlapsTheQuestion() {
        val bars = series(List(500) { 2000.0 + (it % 13) * 0.7 })
        val q = GuessDealer.question(bars, "2024-03", 1, Random(3))!!
        val h = GuessDealer.hidden(bars, q)
        assertEquals(GuessDealer.HIDDEN_BARS, h.size)
        assertTrue("答案必须严格晚于题面", h.first().timeMillis > q.anchorTimeMillis)
        assertEquals(bars[q.sourceIndex + 1].timeMillis, h.first().timeMillis)
    }

    @Test
    fun produceTheSameQuestionForTheSameSeed() {
        val bars = series(List(500) { 2000.0 + (it % 17) * 0.3 })
        val a = GuessDealer.question(bars, "m", 1, Random(42))!!
        val b = GuessDealer.question(bars, "m", 1, Random(42))!!
        assertEquals(a.anchorTimeMillis, b.anchorTimeMillis)
        assertEquals(a.unit, b.unit, 1e-12)
    }

    @Test
    fun dealerFallsBackWhenTheDataCarriesNoSpread() {
        val bars = series(List(500) { 2000.0 + (it % 7) * 0.4 }, spread = 0.0)
        val q = GuessDealer.question(bars, "m", 1, Random(5))!!
        assertTrue("点差缺失时必须退回保守默认值，绝不能当成 0（那会白送玩家一次免费交易）", q.spread > 0.0)
    }

    /**
     * 真机上真实出现过的数据形态：MT5 的「点差」列是 200（点），
     * 而 Candle.spread 的文档写的是价格单位。解析器若没换算就直接塞进去，
     * 入场价会被抬高 200 美元 —— 初始权益 10000，一局就没了。
     * GuessDealer 必须把这种脏数据挡下来。
     */
    @Test
    fun aSpreadStoredInPointsIsRejectedInsteadOfBlowingUpTheEntryPrice() {
        // 至少要 GuessDealer.MIN_BARS(121) 根才出得了题
        val path = List(200) { 2044.0 + (it % 5) * 0.1 }

        val dirty = path.mapIndexed { i, c ->
            val o = if (i == 0) c else path[i - 1]
            bar(i, o, maxOf(o, c) + 0.2, minOf(o, c) - 0.2, c, spread = 200.0)
        }
        val q = GuessDealer.question(dirty, "m", 1, Random(11), fallbackSpread = 0.20)!!
        assertTrue("脏点差必须被挡下，实际 q.spread=" + q.spread, q.spread <= 1.0)
        assertEquals(0.20, q.spread, 1e-9)

        // 合法的真实点差（2024-2026 实测区间 0.16 ~ 0.28）要照常采用
        val clean = path.mapIndexed { i, c ->
            val o = if (i == 0) c else path[i - 1]
            bar(i, o, maxOf(o, c) + 0.2, minOf(o, c) - 0.2, c, spread = 0.28)
        }
        val q2 = GuessDealer.question(clean, "m", 1, Random(11), fallbackSpread = 0.20)!!
        assertEquals(0.28, q2.spread, 1e-9)
    }

    @Test
    fun everyAnchorProducedHasRoomOnBothSides() {
        val bars = series(List(200) { 2000.0 + (it % 9) * 0.4 })
        for (seed in 1..200) {
            val q = GuessDealer.question(bars, "m", 1, Random(seed))!!
            assertTrue("锚点前面要有足量题面", q.sourceIndex >= GuessDealer.REVEAL_BARS - 1)
            assertTrue(
                "锚点后面要有足量答案",
                q.sourceIndex + GuessDealer.HIDDEN_BARS <= bars.size,
            )
        }
    }

    // ==================== 记分层 ====================

    @Test
    fun countsWinsAndLosses() {
        var s = GuessScore.initial(10_000.0)
        s = GuessScore.apply(s, result(0.01, 5))
        s = GuessScore.apply(s, result(-0.02, 20))
        s = GuessScore.apply(s, result(0.03, 8))
        assertEquals(3, s.total)
        assertEquals(2, s.correct)
        assertEquals(2, s.wins)
        assertEquals(1, s.losses)
        assertEquals(2.0 / 3.0, s.winRate, 1e-9)
    }

    @Test
    fun equityAccumulatesEveryRound() {
        var s = GuessScore.initial(10_000.0)
        s = GuessScore.apply(s, result(0.01, 5))
        s = GuessScore.apply(s, result(-0.02, 20))
        assertEquals(10_000.0 + 100.0 - 200.0, s.equity, 1e-6)
        assertEquals(-1.0 / 100.0, s.returnPct, 1e-12)
    }

    @Test
    fun streakGrowsResetsAndKeepsTheBest() {
        var s = GuessScore.initial(10_000.0)
        repeat(3) { s = GuessScore.apply(s, result(0.01, 5)) }
        assertEquals(3, s.streak)
        assertEquals(3, s.bestStreak)
        s = GuessScore.apply(s, result(-0.01, 5))
        assertEquals(0, s.streak)
        assertEquals(3, s.bestStreak)
        s = GuessScore.apply(s, result(0.01, 5))
        assertEquals(1, s.streak)
        assertEquals(3, s.bestStreak)
    }

    @Test
    fun payoffAndBreakEvenFollowFromAverageWinAndLoss() {
        var s = GuessScore.initial(10_000.0)
        // 赢 +1%、亏 -2% → 盈亏比 0.5 → 打平要 66.7% 胜率
        s = GuessScore.apply(s, result(0.01, 5))
        s = GuessScore.apply(s, result(-0.02, 5))
        assertEquals(0.01, s.avgWinPct, 1e-12)
        assertEquals(0.02, s.avgLossPct, 1e-12)
        assertEquals(0.5, s.payoff, 1e-12)
        assertEquals(2.0 / 3.0, s.breakEvenWinRate, 1e-9)
    }

    @Test
    fun holdRatioSeparatesWinnersFromLosers() {
        var s = GuessScore.initial(10_000.0)
        repeat(5) { s = GuessScore.apply(s, result(0.005, 4)) }
        repeat(5) { s = GuessScore.apply(s, result(-0.01, 40)) }
        assertEquals(4.0, s.avgWinBars, 1e-9)
        assertEquals(40.0, s.avgLossBars, 1e-9)
        assertEquals(10.0, s.holdRatio, 1e-9)
    }

    @Test
    fun holdRatioIsZeroRatherThanAGuessWhenOneSideIsMissing() {
        var s = GuessScore.initial(10_000.0)
        s = GuessScore.apply(s, result(0.01, 3))
        assertEquals(0.0, s.holdRatio, 1e-12)
    }

    @Test
    fun countsHowTheRoundWasClosed() {
        var s = GuessScore.initial(10_000.0)
        s = GuessScore.apply(s, result(0.01, 5, manual = true))
        s = GuessScore.apply(s, result(-0.01, 60, manual = false))
        assertEquals(1, s.manualCloses)
        assertEquals(1, s.timeoutCloses)
    }

    @Test
    fun bustedWhenEquityIsGone() {
        var s = GuessScore.initial(1_000.0)
        assertEquals(false, s.busted)
        s = GuessScore.apply(s, result(-0.20, 60))
        assertTrue(s.busted)
    }

    @Test
    fun resetKeepsOnlyTheInitialEquity() {
        var s = GuessScore.initial(10_000.0)
        repeat(4) { s = GuessScore.apply(s, result(0.01, 5)) }
        val r = GuessScore.reset(s)
        assertEquals(0, r.total)
        assertEquals(10_000.0, r.equity, 1e-9)
        assertEquals(10_000.0, r.initialEquity, 1e-9)
    }

    // ==================== 行为层（这个玩法存在的理由） ====================

    /**
     * 模拟一个最典型的失败玩家：浮盈到一点点就跑，浮亏就一路扛到时间结束。
     *
     * 这个策略在真实账户里出现的频率极高。用它跑 400 局，断言两件事：
     * 亏损时的持有时间必然显著长于盈利时（holdRatio > 1.5），
     * 而且盈亏比必然小于 1。
     *
     * 如果哪天这个断言挂了，说明玩法或统计口径被改坏了 ——
     * 界面上的「持有时间比」就变成了骗人的数字。
     */
    @Test
    fun aCutWinnersShortRideLosersPlayerReproducesTheRealProblem() {
        val bars = randomWalk(6000, seed = 20261016, step = 0.45, start = 2000.0)
        var stats = GuessScore.initial(10_000.0)
        val rnd = Random(7)
        var n = 0
        while (n < 400) {
            val q = GuessDealer.question(bars, "test", n + 1, rnd) ?: break
            val hidden = GuessDealer.hidden(bars, q)
            if (hidden.isEmpty()) break
            val dir = if (rnd.nextBoolean()) GuessDirection.UP else GuessDirection.DOWN
            val entry = GuessRule.entryPrice(dir, q.anchor, q.spread)

            // 赚够 5 美元立刻跑；否则一直扛到走满
            var held = hidden.size
            for (i in hidden.indices) {
                val f = GuessRule.floating(dir, entry, hidden[i].close, q.spread, spec)
                if (f >= 5.0) {
                    held = i + 1
                    break
                }
            }
            val r = GuessRule.settle(
                dir, q.anchor, q.unit, hidden, held, q.spread, spec,
                closedByHand = held < hidden.size,
            ) ?: break
            stats = GuessScore.apply(stats, r)
            n++
        }

        assertTrue("样本太少，无法判断：${stats.total}", stats.total >= 300)
        assertTrue("必须同时有赢和亏两类样本", stats.wins > 0 && stats.losses > 0)
        assertTrue(
            "「赚了就跑、亏了死扛」应该让亏损时持有时间明显更长，实际 holdRatio=${stats.holdRatio}",
            stats.holdRatio > 1.5,
        )
        assertTrue(
            "这种玩法的实际盈亏比应该小于 1，实际 payoff=${stats.payoff}",
            stats.payoff < 1.0,
        )
    }

    /**
     * 对照组：随机方向、走满时间。
     *
     * 胜率必须贴着 50%（随机游走上没有任何可利用的方向性），
     * 而每局平均结果必须为负 —— 负的那部分就是点差。
     * 这两条一起说明了这个玩法的底层事实：**期望不由猜测决定**。
     */
    @Test
    fun coinFlipDirectionHasNoEdgeButStillPaysTheSpread() {
        val bars = randomWalk(6000, seed = 99, step = 0.50, start = 2000.0)
        var stats = GuessScore.initial(10_000.0)
        val rnd = Random(3)
        for (n in 0 until 300) {
            val q = GuessDealer.question(bars, "t", n + 1, rnd) ?: break
            val hidden = GuessDealer.hidden(bars, q)
            if (hidden.isEmpty()) break
            val dir = if (rnd.nextBoolean()) GuessDirection.UP else GuessDirection.DOWN
            val r = GuessRule.settle(
                dir, q.anchor, q.unit, hidden, hidden.size, q.spread, spec,
                closedByHand = false,
            ) ?: break
            stats = GuessScore.apply(stats, r)
        }
        assertEquals(300, stats.total)
        assertTrue("随机方向的胜率应该贴着 50%，实际 ${stats.winRate}", abs(stats.edge) < 0.10)
        assertTrue("每一局都在付点差，长期均值必须为负：${stats.avgProfit}", stats.avgProfit < 0.0)
    }
}

