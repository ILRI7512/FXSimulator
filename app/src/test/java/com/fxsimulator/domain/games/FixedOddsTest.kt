package com.fxsimulator.domain.games

import com.fxsimulator.domain.event.EventSide
import com.fxsimulator.domain.event.FixedOddsCycle
import com.fxsimulator.domain.event.FixedOddsOutcome
import com.fxsimulator.domain.event.FixedOddsPosition
import com.fxsimulator.domain.event.FixedOddsRules
import com.fxsimulator.domain.event.FixedOddsSettlement
import com.fxsimulator.domain.event.FixedOddsStatus
import com.fxsimulator.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定赔率制（方案 3.7 B 模式 / 币安口径）的验收。 */
class FixedOddsTest {

    private fun bars(n: Int, price: Double = 80_000.0): List<Candle> =
        (0 until n).map { i ->
            Candle(1_700_000_000_000L + i * 60_000L, price, price * 1.001, price * 0.999, price, 5L, 0.0)
        }

    private fun position(
        side: EventSide = EventSide.UP,
        stake: Double = 10.0,
        target: Double = 80_000.0,
        expireIndex: Int = 40,
        cycle: FixedOddsCycle = FixedOddsCycle.M30,
    ) = FixedOddsPosition(
        id = 1L,
        symbol = "BTCUSDT",
        cycle = cycle,
        side = side,
        stake = stake,
        payoutRate = FixedOddsRules.PAYOUT_RATE,
        openAtMillis = 1_700_000_000_000L,
        expireAtMillis = 1_700_000_000_000L + cycle.minutes * 60_000L,
        expireIndex = expireIndex,
        targetPrice = target,
    )

    // ───────────────────────── 赔率规则 ─────────────────────────

    @Test
    fun payout_rate_80_means_break_even_win_rate_55_56() {
        assertEquals(0.80, FixedOddsRules.PAYOUT_RATE, 1e-12)
        // 1/(1+0.8) = 0.5556 —— 与报告里的 55.56% 一致
        assertEquals(55.5555, FixedOddsRules.breakEvenWinRate * 100.0, 0.01)
    }

    @Test
    fun winning_pays_back_stake_times_one_point_eight() {
        val p = position(stake = 10.0)
        assertEquals(18.0, p.payoutIfWin, 1e-9)
        // 赢的净收益只有 8，不是 18
        val settled = FixedOddsSettlement.apply(p, FixedOddsOutcome.Settled(80_100.0))
        assertEquals(FixedOddsStatus.WON, settled.status)
        assertEquals(8.0, settled.realizedPnl!!, 1e-9)
        assertEquals(18.0, settled.refundOnSettle, 1e-9)
    }

    @Test
    fun losing_costs_exactly_the_stake() {
        val p = position(side = EventSide.UP, stake = 10.0)
        val settled = FixedOddsSettlement.apply(p, FixedOddsOutcome.Settled(79_900.0))
        assertEquals(FixedOddsStatus.LOST, settled.status)
        assertEquals(-10.0, settled.realizedPnl!!, 1e-9)
        assertEquals(0.0, settled.refundOnSettle, 1e-9)
    }

    @Test
    fun tie_refunds_the_stake() {
        // 与 A 模式最大的差别：相等不计输赢，退本金
        val p = position(side = EventSide.UP, stake = 10.0, target = 80_000.0)
        val settled = FixedOddsSettlement.apply(p, FixedOddsOutcome.Settled(80_000.0))
        assertEquals(FixedOddsStatus.REFUNDED, settled.status)
        assertEquals(0.0, settled.realizedPnl!!, 1e-9)
        assertEquals(10.0, settled.refundOnSettle, 1e-9)
    }

    @Test
    fun down_side_wins_when_settlement_is_lower() {
        val p = position(side = EventSide.DOWN, stake = 20.0)
        val settled = FixedOddsSettlement.apply(p, FixedOddsOutcome.Settled(79_000.0))
        assertEquals(FixedOddsStatus.WON, settled.status)
        assertEquals(16.0, settled.realizedPnl!!, 1e-9)
    }

    @Test
    fun settlement_waits_until_window_is_complete() {
        val p = position(cycle = FixedOddsCycle.M30, expireIndex = 40)
        // M30 → 窗口 5 根，只推进到 30 根不足以覆盖 index 40
        val early = FixedOddsSettlement.settle(p, bars(30))
        assertTrue("必须 Pending：$early", early is FixedOddsOutcome.Pending)
        val ok = FixedOddsSettlement.settle(p, bars(41))
        assertTrue(ok is FixedOddsOutcome.Settled)
    }

    // ───────────────────────── 金额限制 ─────────────────────────

    @Test
    fun stake_limits_follow_binance_numbers() {
        assertNotNull("低于 5 USDT 要拒绝", FixedOddsRules.validateStake(4.9, 10_000.0))
        assertNull(FixedOddsRules.validateStake(5.0, 10_000.0))
        // 权益 10000 → 5% = 500，但绝对上限 250，所以 250 生效
        assertEquals(250.0, FixedOddsRules.maxStake(10_000.0), 1e-9)
        assertNotNull("超过 250 要拒绝", FixedOddsRules.validateStake(300.0, 10_000.0))
        // 小权益时按 5%
        assertEquals(5.0, FixedOddsRules.maxStake(100.0), 1e-9)
    }

    // ───────────────────────── 引擎账目 ─────────────────────────

    @Test
    fun engine_deducts_stake_and_credits_payout() {
        val engine = GamesEngine(1_000.0)
        val all = bars(300)
        val err = engine.openFixed(
            symbol = "BTCUSDT", cycle = FixedOddsCycle.M30, side = EventSide.UP,
            stake = 10.0, nowMillis = all.last().timeMillis, expireIndex = 40,
            targetPrice = 80_000.0,
        )
        assertNull(err)
        assertEquals(990.0, engine.equity, 1e-9)

        // 窗口 36..40 均价 = 80000 → 持平 → 退本金
        engine.onBars(all.subList(0, 100))
        assertEquals(1_000.0, engine.equity, 1e-9)
        assertEquals(FixedOddsStatus.REFUNDED, engine.settledFixed.single().status)
    }

    @Test
    fun daily_loss_cap_blocks_further_bets() {
        // 权益 100 → 日上限 20；单笔上限 min(250, 5%) = 5。用 4 笔全输凑满 20。
        val engine = GamesEngine(100.0)
        val losing = bars(300, price = 79_000.0)   // 结算价 79000 < 目标 80000 → 押涨全输
        for (idx in listOf(40, 80, 120, 160)) {
            val err = engine.openFixed(
                "BTCUSDT", FixedOddsCycle.M30, EventSide.UP, 5.0,
                losing.last().timeMillis, idx, 80_000.0,
            )
            assertNull("第 $idx 根未达上限，应成功：$err", err)
        }
        engine.onBars(losing)
        assertEquals(4, engine.settledFixed.size)
        assertEquals(20.0, engine.dailyLoss, 1e-9)
        assertTrue("应触发日上限", engine.isDailyCapReached())

        val err = engine.openFixed(
            "BTCUSDT", FixedOddsCycle.M30, EventSide.UP, 5.0,
            losing.last().timeMillis, 200, 80_000.0,
        )
        assertNotNull("达到日上限后必须拒绝", err)
    }
}
