package com.fxsimulator.domain.games

import com.fxsimulator.domain.event.EventCycle
import com.fxsimulator.domain.event.EventSide
import com.fxsimulator.domain.event.EventStatus
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.options.OptionPricing
import com.fxsimulator.domain.options.OptionTenor
import com.fxsimulator.domain.options.OptionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 玩法账本：扣款、结算入账、护栏。 */
class GamesEngineTest {

    private fun bars(n: Int, price: Double = 80_000.0): List<Candle> =
        (0 until n).map { i ->
            Candle(1_700_000_000_000L + i * 60_000L, price, price * 1.001, price * 0.999, price, 5L, 0.0)
        }

    @Test
    fun opening_an_event_deducts_cost_from_equity() {
        val engine = GamesEngine(1_000.0)
        val bars = bars(200)
        val err = engine.openEvent(
            symbol = "BTCUSDT", cycle = EventCycle.M5, side = EventSide.UP,
            shares = 10, price = 0.50, nowMillis = bars.last().timeMillis,
            bars = bars, expireIndex = 199, targetPrice = bars.last().close,
        )
        assertNull(err)
        // 10 张 × 0.5 × 1.001 = 5.005
        assertEquals(1_000.0 - 5.005, engine.equity, 1e-9)
        assertEquals(1, engine.openEvents.size)
    }

    @Test
    fun insufficient_equity_and_per_bet_cap_are_enforced() {
        val engine = GamesEngine(100.0)
        val bars = bars(200)
        // 单笔上限 = 权益 5% = 5 USDT
        val tooBig = engine.openEvent(
            "BTCUSDT", EventCycle.M5, EventSide.UP, shares = 100, price = 0.50,
            nowMillis = bars.last().timeMillis, bars = bars, expireIndex = 199, targetPrice = 80_000.0,
        )
        assertNotNull("超过单笔上限应被拒绝", tooBig)
        assertEquals("失败不应扣款", 100.0, engine.equity, 1e-9)
    }

    @Test
    fun zero_based_risks_are_rejected() {
        val engine = GamesEngine(10.0)
        val bars = bars(200)
        val err = engine.openEvent(
            "BTCUSDT", EventCycle.M5, EventSide.UP, shares = 10, price = 0.50,
            nowMillis = bars.last().timeMillis, bars = bars, expireIndex = 199, targetPrice = 80_000.0,
        )
        assertNotNull("权益不足时必须拒绝", err)
    }

    @Test
    fun matured_event_is_settled_and_credited() {
        val engine = GamesEngine(1_000.0)
        val bars = bars(300)
        // 到期下标 10，窗口 6..10 都是 80000 → 等于目标价 → 判涨
        engine.openEvent(
            "BTCUSDT", EventCycle.M5, EventSide.UP, shares = 10, price = 0.50,
            nowMillis = bars.last().timeMillis, bars = bars, expireIndex = 10,
            targetPrice = 80_000.0,
        )
        val before = engine.equity
        val settledCount = engine.onBars(bars)
        assertEquals("应结算 1 张", 1, settledCount)
        assertEquals(EventStatus.SETTLED, engine.settledEvents.single().status)
        // 赢：返还 10 张 × 1.0
        assertEquals(before + 10.0, engine.equity, 1e-9)
        assertTrue(engine.settledEvents.single().realizedPnl!! > 0)
    }

    @Test
    fun immature_event_stays_open() {
        val engine = GamesEngine(1_000.0)
        val all = bars(300)
        // 到期下标 250，但只推进前 100 根 → 必须等
        engine.openEvent(
            "BTCUSDT", EventCycle.H4, EventSide.UP, shares = 10, price = 0.50,
            nowMillis = all.last().timeMillis, bars = all, expireIndex = 250,
            targetPrice = 80_000.0,
        )
        val settledCount = engine.onBars(all.subList(0, 100))
        assertEquals(0, settledCount)
        assertEquals(1, engine.openEvents.size)
        assertEquals(EventStatus.OPEN, engine.openEvents.single().status)
    }

    @Test
    fun option_purchase_and_settlement_are_consistent() {
        val engine = GamesEngine(10_000.0)
        val bars = bars(300)
        val quote = OptionPricing.quote(
            OptionType.CALL, 80_000.0, 80_000.0, 0.36, OptionTenor.H1, 5.0,
        )
        val err = engine.openOption(
            symbol = "BTCUSDT", quote = quote, tenor = OptionTenor.H1, quantity = 0.1,
            nowMillis = bars.last().timeMillis, expireAtMillis = bars.last().timeMillis + 3_600_000L,
            expireIndex = 120,
        )
        assertNull(err)
        assertEquals(10_000.0 - quote.ask * 0.1, engine.equity, 1e-9)

        // 结算价 = 80000（窗口内均价），行权价也是 80000 → 内在价值 0 → 归零
        engine.onBars(bars)
        val settled = engine.settledOptions.single()
        assertEquals(0.0, settled.payout ?: -1.0, 1e-9)
        assertEquals(-quote.ask * 0.1, settled.realizedPnl!!, 1e-9)
    }

    @Test
    fun concentration_cap_limits_option_size() {
        val engine = GamesEngine(1_000.0)
        val bars = bars(300)
        val quote = OptionPricing.quote(OptionType.CALL, 80_000.0, 80_000.0, 0.36, OptionTenor.D7, 5.0)
        val err = engine.openOption(
            "BTCUSDT", quote, OptionTenor.D7, quantity = 1.0,
            nowMillis = bars.last().timeMillis, expireAtMillis = bars.last().timeMillis + 60_000L,
            expireIndex = 10,
        )
        assertNotNull("超过权益 10% 的集中度应被拒绝", err)
    }
}
