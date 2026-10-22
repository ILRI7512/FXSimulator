package com.fxsimulator.domain.games

import com.fxsimulator.domain.event.EventCycle
import com.fxsimulator.domain.event.EventPricing
import com.fxsimulator.domain.event.EventRules
import com.fxsimulator.domain.event.EventSettlement
import com.fxsimulator.domain.event.EventSettlementOutcome
import com.fxsimulator.domain.event.EventSide
import com.fxsimulator.domain.event.EventStatus
import com.fxsimulator.domain.event.EventPosition
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.options.OptionPricing
import com.fxsimulator.domain.options.OptionQuote
import com.fxsimulator.domain.options.OptionTenor
import com.fxsimulator.domain.options.OptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 事件合约与期权的数值验收（对照方案 3.2/3.3/4.1/4.2/4.3）。 */
class GamesDomainTest {

    private fun bars(closes: List<Double>, startMillis: Long = 1_700_000_000_000L): List<Candle> =
        closes.mapIndexed { i, c ->
            Candle(startMillis + i * 60_000L, c, c * 1.001, c * 0.999, c, 10L, 0.0)
        }

    // ───────────────────────── 事件合约：定价 ─────────────────────────

    @Test
    fun fair_probability_is_one_half_when_target_equals_spot() {
        // 目标价 = 现价 → ln(S/K)=0 → N(0)=0.5（方案明确的口径）
        val p = EventPricing.fairProbability(spot = 80_000.0, target = 80_000.0, annualVol = 0.36, secondsLeft = 300.0)
        assertEquals(0.5, p, 1e-9)
    }

    @Test
    fun fair_probability_rises_when_spot_is_above_target() {
        val p = EventPricing.fairProbability(80_100.0, 80_000.0, 0.36, 300.0)
        assertTrue("现价高于目标价时概率应大于 0.5：$p", p > 0.5)
        val q = EventPricing.fairProbability(79_900.0, 80_000.0, 0.36, 300.0)
        assertTrue("现价低于目标价时概率应小于 0.5：$q", q < 0.5)
    }

    private fun flatBars(n: Int, price: Double = 80_000.0): List<Candle> =
        (0 until n).map { i ->
            Candle(1_700_000_000_000L + i * 60_000L, price, price, price, price, 1L, 0.0)
        }

    @Test
    fun at_round_start_the_probability_is_about_half() {
        // 窗口 5 根全是未来根 → 均价期望 = 现价 = 目标价 → 0.5
        val bars = flatBars(100, 80_000.0)
        val p = EventPricing.settlementProbability(80_000.0, bars, bars.lastIndex + 5, 5, 0.36)
        assertEquals(0.5, p, 0.02)
    }

    @Test
    fun probability_gets_extreme_as_the_window_fills_up() {
        // 偏离要小：80,030 对 80,000（约 3.7 bp），早期还有 5 根未来根分摊不确定性
        val bars = flatBars(100, 80_030.0)
        val early = EventPricing.settlementProbability(80_000.0, bars, bars.lastIndex + 4, 5, 0.36)
        val late = EventPricing.settlementProbability(80_000.0, bars, bars.lastIndex, 5, 0.36)
        assertTrue("早期不应直接顶到 0.99：$early", early < 0.95)
        assertTrue("临近结算应更极端：$early -> $late", late > early)
        assertTrue("全部已知时应顶到 0.99：$late", late > 0.95)
    }

    @Test
    fun probability_flips_when_the_average_crosses_the_target() {
        val bars = flatBars(100, 80_000.0)
        val belowTarget = EventPricing.settlementProbability(80_050.0, bars, bars.lastIndex, 5, 0.36)
        val aboveTarget = EventPricing.settlementProbability(79_950.0, bars, bars.lastIndex, 5, 0.36)
        assertTrue("均价低于目标价应接近 0.01：$belowTarget", belowTarget < 0.05)
        assertTrue("均价高于目标价应接近 0.99：$aboveTarget", aboveTarget > 0.95)
        assertEquals("边界取 0.01/0.99", 0.01, EventRules.MIN_PRICE, 1e-12)
        assertEquals(0.99, EventRules.MAX_PRICE, 1e-12)
    }

    @Test
    fun quote_bid_ask_bracket_the_mid() {
        // 用"尚有未来根"的时点，中价才落在中间，价差才有意义（顶到 0.99 时卖价会被夹住）
        val bars = flatBars(100, 80_030.0)
        val q = EventPricing.quote(
            target = 80_000.0, bars = bars, expireIndex = bars.lastIndex + 4,
            cycleMinutes = 5, annualVol = 0.36, noise = 0.0,
        )
        assertTrue("买价应低于中价", q.bid < q.mid)
        assertTrue("卖价应高于中价", q.ask > q.mid)
        assertTrue("中价不得越界", q.mid in EventRules.MIN_PRICE..EventRules.MAX_PRICE)
    }
    @Test
    fun sentiment_follows_ar1_and_stays_bounded() {
        assertEquals(0.92 * 0.1, EventPricing.nextSentiment(0.1, 0.0), 1e-12)
        // 连续正冲击也必须被夹在 +0.15
        var m = 0.0
        repeat(50) { m = EventPricing.nextSentiment(m, 0.03) }
        assertEquals("情绪上限", EventPricing.MAX_SENTIMENT, m, 1e-9)
    }

    @Test
    fun annualized_volatility_is_positive_and_plausible() {
        // 每根 ±0.1% 交替 → 有明显波动
        val closes = (0 until 120).map { 80_000.0 * if (it % 2 == 0) 1.0 else 1.001 }
        val vol = EventPricing.annualizedVol(bars(closes), lookback = 60)
        assertTrue("年化波动率应显著大于 0：$vol", vol > 0.01)
        assertTrue("年化波动率不应离谱：$vol", vol < 20.0)
    }

    // ───────────────────────── 事件合约：规则与结算 ─────────────────────────

    @Test
    fun rules_enforce_minimum_shares_and_notional() {
        assertNotNull(EventRules.validateShares(5, 0.5))
        assertNotNull("名义金额不足也要拒绝", EventRules.validateShares(6, 0.2))
        assertNull(EventRules.validateShares(6, 0.9))
    }

    @Test
    fun settlement_waits_when_window_is_not_reached() {
        val pos = position(cycle = EventCycle.M5, expireIndex = 20, side = EventSide.UP, target = 100.0)
        val bars = bars((0 until 18).map { 100.0 })   // 只推进到 17 根
        val outcome = EventSettlement.settle(pos, bars)
        assertTrue("数据不足必须 Pending：$outcome", outcome is EventSettlementOutcome.Pending)
    }

    @Test
    fun settlement_uses_last_n_closes_and_equal_counts_as_up() {
        // 5 分钟 → N=5，窗口取 index 5..9，均价恰好等于目标价 → 判涨
        val closes = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0)
        val pos = position(cycle = EventCycle.M5, expireIndex = 9, side = EventSide.UP, target = 100.0)
        val outcome = EventSettlement.settle(pos, bars(closes))
        assertTrue(outcome is EventSettlementOutcome.Settled)
        val settled = EventSettlement.apply(pos, outcome)
        assertEquals(EventStatus.SETTLED, settled.status)
        assertEquals(true, settled.won)
        assertEquals(0.0, settled.bpDiff!!, 1e-9)
        // 张数 100、价 0.43、费 0.1% → 成本 43.043，赢回收 100
        assertEquals(100.0 * 0.43 * 1.001, settled.cost, 1e-9)
        assertEquals(100.0 - 100.0 * 0.43 * 1.001, settled.realizedPnl!!, 1e-9)
    }

    @Test
    fun down_side_wins_when_settlement_is_below_target() {
        val closes = (0 until 10).map { 99.0 }
        val pos = position(cycle = EventCycle.M5, expireIndex = 9, side = EventSide.DOWN, target = 100.0)
        val settled = EventSettlement.apply(pos, EventSettlement.settle(pos, bars(closes)))
        assertEquals(true, settled.won)
        assertTrue("基点差应为负", settled.bpDiff!! < 0)
    }

    @Test
    fun equal_settlement_makes_down_lose() {
        val closes = (0 until 10).map { 100.0 }
        val pos = position(cycle = EventCycle.M5, expireIndex = 9, side = EventSide.DOWN, target = 100.0)
        val settled = EventSettlement.apply(pos, EventSettlement.settle(pos, bars(closes)))
        assertEquals("相等判涨 → 押跌输", false, settled.won)
        assertTrue(settled.realizedPnl!! < 0)
    }

    private fun position(
        cycle: EventCycle,
        expireIndex: Int,
        side: EventSide,
        target: Double,
    ) = EventPosition(
        id = 1L,
        symbol = "BTCUSDT",
        cycle = cycle,
        side = side,
        shares = 100,
        price = 0.43,
        feeRate = EventRules.FEE_RATE,
        openAtMillis = 1_700_000_000_000L,
        expireAtMillis = 1_700_000_000_000L + cycle.minutes * 60_000L,
        expireIndex = expireIndex,
        targetPrice = target,
    )

    // ───────────────────────── 期权 ─────────────────────────

    @Test
    fun atm_call_is_roughly_0_point_4_sigma_sqrt_t() {
        // 平值看涨 ≈ 0.4 × S × σ × √T（经典近似），用它交叉验证实现没写反
        val spot = 80_000.0
        val sigma = 0.36
        val t = 1.0 / 365.0
        val q = OptionPricing.quote(OptionType.CALL, spot, spot, sigma, OptionTenor.D1, tickSize = 5.0)
        val approx = 0.4 * spot * sigma * Math.sqrt(t)
        assertEquals("平值权利金应接近近似式", approx, q.premium, approx * 0.05)
    }

    @Test
    fun call_put_parity_holds() {
        // C − P = S − K·e^{−rT}
        val s = 80_000.0
        val k = 82_000.0
        val sigma = 0.36
        val tenor = OptionTenor.D7
        val c = OptionPricing.quote(OptionType.CALL, s, k, sigma, tenor, 5.0).premium
        val p = OptionPricing.quote(OptionType.PUT, s, k, sigma, tenor, 5.0).premium
        val t = tenor.minutes / (365.0 * 24.0 * 60.0)
        val expected = s - k * Math.exp(-OptionPricing.RISK_FREE * t)
        assertEquals("看涨看跌平价关系", expected, c - p, 1.0)
    }

    @Test
    fun greeks_have_expected_signs() {
        val call = OptionPricing.quote(OptionType.CALL, 80_000.0, 80_000.0, 0.36, OptionTenor.D1, 5.0)
        assertTrue("看涨 delta 应在 (0,1)：${call.delta}", call.delta in 0.01..0.99)
        assertTrue("gamma 应为正：${call.gamma}", call.gamma > 0.0)
        assertTrue("多头 theta 应为负（时间价值衰减）：${call.thetaDaily}", call.thetaDaily < 0.0)
        assertTrue("vega 应为正：${call.vegaPerPct}", call.vegaPerPct > 0.0)

        val put = OptionPricing.quote(OptionType.PUT, 80_000.0, 80_000.0, 0.36, OptionTenor.D1, 5.0)
        assertTrue("看跌 delta 应在 (-1,0)：${put.delta}", put.delta in -0.99..-0.01)
    }

    @Test
    fun strike_ladder_matches_spec_spacing_and_count() {
        val spot = 80_000.0
        val strikes = OptionPricing.strikes(spot, OptionTenor.H1, count = 8)
        assertEquals("平值 ±8 共 17 档", 17, strikes.size)
        val step = spot * 0.0025
        assertEquals("间距应为现价 0.25%", step, strikes[1] - strikes[0], 1e-6)
        // 期限越长间距越大
        val long = OptionPricing.strikes(spot, OptionTenor.D30, count = 8)
        assertTrue("30 天间距应大于 1 小时", (long[1] - long[0]) > (strikes[1] - strikes[0]))
    }

    @Test
    fun deep_out_of_the_money_option_is_almost_worthless() {
        val q = OptionPricing.quote(OptionType.CALL, 80_000.0, 200_000.0, 0.36, OptionTenor.H1, 5.0)
        assertTrue("远虚值应接近 0：${q.premium}", q.premium < 1.0)
        assertEquals("内在价值为 0", 0.0, OptionPricing.intrinsic(OptionType.CALL, 200_000.0, 80_000.0), 1e-9)
    }

    @Test
    fun settlement_price_uses_trailing_average_and_needs_enough_bars() {
        val closes = (0 until 40).map { 80_000.0 + it }
        val bars = bars(closes)
        assertNull("数据不足应返回 null", OptionPricing.settlementPrice(bars, OptionTenor.D1, 10))
        val price = OptionPricing.settlementPrice(bars, OptionTenor.D1, 39)
        assertNotNull(price)
        // D1 → N=30，窗口 10..39
        val expected = closes.subList(10, 40).average()
        assertEquals(expected, price!!, 1e-9)
    }

    @Test
    fun exercise_fee_is_the_smaller_of_two_rules() {
        // min(结算价值 × 0.015%, 实值额 × 10%)：哪种更小就收哪种
        val byRate = OptionPricing.exerciseFee(settlementValue = 80_000.0, intrinsicValue = 1_000_000.0)
        assertEquals("费率项更小：80000×0.015%=12", 12.0, byRate, 1e-9)
        val byIntrinsic = OptionPricing.exerciseFee(settlementValue = 80_000.0, intrinsicValue = 100.0)
        assertEquals("实值 10% 更小：100×10%=10", 10.0, byIntrinsic, 1e-9)
    }

    @Test
    fun quote_spread_is_at_least_two_ticks() {
        val q: OptionQuote = OptionPricing.quote(OptionType.CALL, 80_000.0, 80_000.0, 0.36, OptionTenor.H1, tickSize = 5.0)
        assertTrue("价差至少 2 tick：${q.spread}", q.spread >= 10.0 - 1e-9)
        assertTrue("买价不低于 0", q.bid >= 0.0)
    }

    // ───────────── 外部基准校验（用研究报告里的实测数字反查实现） ─────────────

    @Test
    fun atm_premium_ratios_match_the_reported_reference_table() {
        // 报告 4.3：BTC σ=35.6%、r=5% 时，平值权利金占现价的比例
        val cases = listOf(
            OptionTenor.H1 to 0.00152,
            OptionTenor.D1 to 0.00749,
            OptionTenor.D30 to 0.04265,
        )
        for ((tenor, expectedRatio) in cases) {
            val spot = 80_000.0
            val q = OptionPricing.quote(OptionType.CALL, spot, spot, 0.356, tenor, tickSize = 5.0)
            val ratio = q.premium / spot
            assertEquals(
                "${tenor.label} 平值权利金占比应与报告一致",
                expectedRatio, ratio, expectedRatio * 0.08,
            )
        }
    }

    @Test
    fun reproduces_a_real_binance_contract_quote() {
        // 报告 4.1 实测：BTC-261225-85000-C
        //   指数价 82213.37，IV 0.361，r 0.053，到期 2026-12-25（距 2026-10-20 约 66 天）
        //   标记价 4151.895，delta 0.469，theta −38.52/日，vega 138.77
        val years = 66.0 / 365.0
        val q = OptionPricing.quoteYears(
            type = OptionType.CALL,
            spot = 82_213.37,
            strike = 85_000.0,
            annualVol = 0.361,
            years = years,
            tickSize = 5.0,
            riskFree = 0.053,
        )
        assertEquals("权利金", 4_151.895, q.premium, 4_151.895 * 0.02)
        assertEquals("delta", 0.469, q.delta, 0.01)
        // theta 的日计口径各家不同（365 vs 365.25、是否含折现项），这里留 20% 余量；
        // 价格 / delta / vega 都对得很紧，说明模型本身没问题。
        assertEquals("theta（每日）", -38.52, q.thetaDaily, 8.0)
        assertEquals("vega（每 1% 波动率）", 138.77, q.vegaPerPct, 5.0)
    }
    // ───────────── 真 TWAP：结算窗口 = 整个周期 ─────────────

    @Test
    fun settlement_window_is_the_whole_cycle_not_just_five_bars() {
        assertEquals("5 分钟 → 5 根", 5, EventRules.settlementBars(EventCycle.M5))
        assertEquals("15 分钟 → 15 根", 15, EventRules.settlementBars(EventCycle.M15))
        assertEquals("1 小时 → 60 根", 60, EventRules.settlementBars(EventCycle.H1))
        assertEquals("4 小时 → 240 根", 240, EventRules.settlementBars(EventCycle.H4))
    }

    @Test
    fun twap_settlement_averages_a_full_hour_of_bars() {
        // 前 40 根 100、后 60 根 200；1 小时窗口恰好覆盖后 60 根 → 结算价应为 200
        val closes = (0 until 100).map { if (it < 40) 100.0 else 200.0 }
        val bars = bars(closes)
        val pos = EventPosition(
            id = 1L, symbol = "BTCUSDT", cycle = EventCycle.H1, side = EventSide.UP,
            shares = 10, price = 0.5, feeRate = EventRules.FEE_RATE,
            openAtMillis = 1L, expireAtMillis = 2L,
            expireIndex = 99, targetPrice = 150.0,
        )
        val outcome = EventSettlement.settle(pos, bars)
        assertTrue(outcome is EventSettlementOutcome.Settled)
        val price = (outcome as EventSettlementOutcome.Settled).settlementPrice
        assertEquals("应是后 60 根的均价", 200.0, price, 1e-9)
        // 若仍按"最后 5 根"算，结果也是 200，所以再用一个能区分的样例
        val mixed = (0 until 100).map { if (it < 60) 300.0 else 100.0 }
        val pos2 = pos.copy(expireIndex = 99)
        val out2 = EventSettlement.settle(pos2, bars(mixed))
        val p2 = (out2 as EventSettlementOutcome.Settled).settlementPrice
        // 窗口 40..99 = 前 20 根 300 + 后 40 根 100 → (6000+4000)/60 = 166.67
        assertEquals("60 根窗口的等权均价", 10000.0 / 60.0, p2, 1e-9)
    }

    @Test
    fun twap_probability_uses_the_same_window() {
        // 1 小时窗口里已走完 30 根、均值远高于目标价 → 概率应显著大于 0.5
        val bars = flatBars(40, 80_500.0)
        val p = EventPricing.settlementProbability(
            target = 80_000.0, bars = bars, expireIndex = bars.lastIndex + 29, cycleMinutes = 60, annualVol = 0.36,
        )
        assertTrue("已知部分已高于目标价，概率应 > 0.5：$p", p > 0.5)
    }
    @Test
    fun forming_bar_and_tick_price_are_used_by_the_quote() {
        // 最后一根 1m 尚未收完：它不算"已知"，现价取 tick 传入
        val bars = flatBars(100, 80_000.0)
        val base = EventPricing.settlementProbability(
            80_000.0, bars, bars.lastIndex + 3, 5, 0.36, formingBars = 0,
        )
        val withTick = EventPricing.settlementProbability(
            80_000.0, bars, bars.lastIndex + 3, 5, 0.36,
            formingBars = 1, spotOverride = 80_400.0,
        )
        assertTrue("tick 价高于目标价，概率应显著上升：$base -> $withTick", withTick > base + 0.1)
    }
}