package com.fxsimulator

import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.CloseReason
import com.fxsimulator.domain.model.OrderStatus
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.TradeDirection
import com.fxsimulator.domain.risk.RiskEngine
import com.fxsimulator.engine.TradingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试 2、3、4、5、6、9、10、11：买卖、平仓、浮动/已实现盈亏、止损止盈、保证金不足 */
class TradingEngineTest {

    private val spec = SymbolSpec(
        contractSize = 100.0, minLot = 0.01, lotStep = 0.01, leverage = 500,
        digits = 3, spread = 0.20, commissionPerLot = 0.0,
    )

    private fun engine(balance: Double = 10000.0): TradingEngine =
        TradingEngine(spec, RiskEngine(minStopDistance = 0.10), balance)

    private fun candle(t: Long, o: Double, h: Double, l: Double, c: Double) =
        Candle(t, o, h, l, c, 0L, 0.20)

    @Test
    fun buyOrderCreatesPositionAndMargin() {
        val e = engine()
        val r = e.marketOrder(TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L)
        assertTrue(r.success)
        assertEquals(1, e.openPositions.size)
        assertEquals(2000.20, e.openPositions[0].openPrice, 1e-9)
        assertEquals(400.04, e.usedMargin(), 1e-6)
        assertEquals(10000.0, e.balance, 1e-9)
    }

    @Test
    fun sellOrderFillsAtBid() {
        val e = engine()
        val r = e.marketOrder(TradeDirection.SELL, 0.5, bid = 2000.0, timeMillis = 1000L)
        assertTrue(r.success)
        assertEquals(2000.0, e.openPositions[0].openPrice, 1e-9)
    }

    @Test
    fun closingSettlesRealizedProfitIntoBalance() {
        val e = engine()
        e.marketOrder(TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L)
        val id = e.openPositions[0].orderId
        val r = e.closePosition(id, bid = 2001.0, timeMillis = 2000L, reason = CloseReason.MANUAL)
        assertTrue(r.success)
        assertEquals(80.0, e.balance - 10000.0, 1e-6)
        assertEquals(0, e.openPositions.size)
        assertEquals(1, e.closedTrades.size)
        assertEquals(OrderStatus.CLOSED, e.closedTrades[0].status)
    }

    @Test
    fun floatingProfitFollowsLastBid() {
        val e = engine()
        e.marketOrder(TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L)
        e.updateBidOnly(2001.0)
        assertEquals(80.0, e.floatingPnl(), 1e-6)
        e.updateBidOnly(1999.0)
        assertEquals(-120.0, e.floatingPnl(), 1e-6)
        assertEquals(9880.0, e.account().equity, 1e-6)
    }

    @Test
    fun stopLossIsTriggeredAtStopPrice() {
        val e = engine()
        e.marketOrder(
            TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L,
            stopLoss = 1999.0, takeProfit = null,
        )
        val results = e.onCandle(candle(1060L, 2000.2, 2001.0, 1998.5, 1999.0))
        assertTrue(results.isNotEmpty())
        assertEquals(CloseReason.STOP_LOSS, e.closedTrades[0].closeReason)
        assertEquals(1999.0, e.closedTrades[0].closePrice!!, 1e-9)
        assertEquals(-120.0, e.closedTrades[0].netProfit, 1e-6)
    }

    @Test
    fun takeProfitIsTriggeredAtTargetPrice() {
        val e = engine()
        e.marketOrder(
            TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L,
            stopLoss = null, takeProfit = 2002.0,
        )
        e.onCandle(candle(1060L, 2000.2, 2003.0, 2000.5, 2002.5))
        assertEquals(CloseReason.TAKE_PROFIT, e.closedTrades[0].closeReason)
        assertEquals(2002.0, e.closedTrades[0].closePrice!!, 1e-9)
        assertEquals(180.0, e.closedTrades[0].netProfit, 1e-6)
    }

    /**
     * 测试 19：同一根 K 线内止损与止盈都被触及时，M1 OHLC 无法确定盘中先后顺序，
     * 必须采用**保守规则：先触发止损**，不得假设先触发止盈。
     */
    @Test
    fun ambiguousBarUsesConservativeStopLossFirstRule() {
        val e = engine()
        e.marketOrder(
            TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L,
            stopLoss = 1999.0, takeProfit = 2002.0,
        )
        val results = e.onCandle(candle(1060L, 2000.2, 2003.0, 1998.0, 2001.0))
        assertTrue(results.isNotEmpty())
        val closed = e.closedTrades[0]
        assertEquals(CloseReason.STOP_LOSS, closed.closeReason)
        assertEquals(1999.0, closed.closePrice!!, 1e-9)
        assertEquals(-120.0, closed.netProfit, 1e-6)
        assertTrue(closed.fillNote.contains("先触发止损"))
    }

    @Test
    fun insufficientMarginRejectsOrder() {
        val e = engine(balance = 100.0)
        val r = e.marketOrder(TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L)
        assertFalse(r.success)
        assertEquals(0, e.openPositions.size)
        assertEquals(OrderStatus.REJECTED, e.history[0].status)
        assertTrue(r.message.contains("保证金"))
    }

    @Test
    fun wrongStopLossDirectionRejectsOrder() {
        val e = engine()
        val r = e.marketOrder(
            TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L, stopLoss = 2005.0,
        )
        assertFalse(r.success)
        assertTrue(r.message.contains("止损"))
    }

    @Test
    fun wrongTakeProfitDirectionRejectsOrder() {
        val e = engine()
        // 卖出方向的止盈必须低于开仓价；给一个高于开仓价的止盈应当被拒绝
        val r = e.marketOrder(
            TradeDirection.SELL, 1.0, bid = 2000.0, timeMillis = 1000L, takeProfit = 2005.0,
        )
        assertFalse(r.success)
        assertTrue(r.message.contains("止盈"))
    }

    @Test
    fun invalidLotIsRejected() {
        val e = engine()
        val r = e.marketOrder(TradeDirection.BUY, 0.015, bid = 2000.0, timeMillis = 1000L)
        assertFalse(r.success)
    }

    @Test
    fun modifyStopLossAndTakeProfit() {
        val e = engine()
        e.marketOrder(TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L)
        val id = e.openPositions[0].orderId
        assertFalse(e.modifyPosition(id, stopLoss = 2005.0, takeProfit = null).success)
        val ok = e.modifyPosition(id, stopLoss = 1990.0, takeProfit = 2020.0)
        assertTrue(ok.success)
        assertEquals(1990.0, e.openPositions[0].stopLoss!!, 1e-9)
        assertNotNull(e.openPositions[0].takeProfit)
    }

    @Test
    fun closeAllClosesEveryPosition() {
        val e = engine()
        e.marketOrder(TradeDirection.BUY, 0.5, bid = 2000.0, timeMillis = 1000L)
        e.marketOrder(TradeDirection.SELL, 0.5, bid = 2000.0, timeMillis = 1000L)
        assertEquals(2, e.openPositions.size)
        val rs = e.closeAll(bid = 2000.0, timeMillis = 2000L)
        assertEquals(2, rs.size)
        assertEquals(0, e.openPositions.size)
        assertEquals(2, e.closedTrades.size)
    }

    /** 测试 20 的一部分：广告奖励只增加余额，不产生交易记录 */
    @Test
    fun rewardOnlyIncreasesBalance() {
        val e = engine()
        e.creditReward(200.0)
        assertEquals(10200.0, e.balance, 1e-9)
        assertEquals(0, e.closedTrades.size)
        assertEquals(0, e.history.size)
    }

    @Test
    fun marginCallForcesAllPositionsClosed() {
        val e = engine()
        e.marketOrder(TradeDirection.BUY, 1.0, bid = 2000.0, timeMillis = 1000L)
        val crashed = candle(1060L, 2000.0, 2000.0, 1000.0, 1000.0)
        val rs = e.onCandle(crashed)
        assertTrue(rs.isNotEmpty())
        assertEquals(0, e.openPositions.size)
        assertEquals(CloseReason.MARGIN_CALL, e.closedTrades.last().closeReason)
    }
}
