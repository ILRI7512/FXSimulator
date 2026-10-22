package com.fxsimulator

import com.fxsimulator.domain.money.MarginCalculator
import com.fxsimulator.domain.money.PnlCalculator
import com.fxsimulator.domain.model.Account
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.TradeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试 1、4-8、12：初始账户、买卖盈亏、佣金、点差、保证金 */
class AccountAndPnlTest {

    private val spec = SymbolSpec(
        contractSize = 100.0, minLot = 0.01, lotStep = 0.01, leverage = 500,
        digits = 3, spread = 0.20, commissionPerLot = 0.0,
    )

    @Test
    fun initialAccountHasEqualBalanceAndEquity() {
        val a = Account(balance = 10000.0)
        assertEquals(10000.0, a.balance, 1e-9)
        assertEquals(10000.0, a.equity, 1e-9)
        assertEquals(0.0, a.floatingPnl, 1e-9)
        assertEquals(10000.0, a.freeMargin, 1e-9)
    }

    @Test
    fun equityEqualsBalancePlusFloating() {
        val a = Account(balance = 10000.0, floatingPnl = -250.0, margin = 400.0)
        assertEquals(9750.0, a.equity, 1e-9)
        assertEquals(9350.0, a.freeMargin, 1e-9)
    }

    @Test
    fun buyProfitCalculation() {
        val p = PnlCalculator.grossProfit(spec, TradeDirection.BUY, 1.0, 2000.10, 2001.10)
        assertEquals(100.0, p, 1e-9)
    }

    @Test
    fun sellProfitCalculation() {
        val p = PnlCalculator.grossProfit(spec, TradeDirection.SELL, 1.0, 2001.10, 2000.10)
        assertEquals(100.0, p, 1e-9)
    }

    @Test
    fun lossScalesWithVolume() {
        val p = PnlCalculator.grossProfit(spec, TradeDirection.BUY, 0.10, 2000.0, 1999.0)
        assertEquals(-10.0, p, 1e-9)
    }

    @Test
    fun commissionIsChargedBothSides() {
        val s = spec.copy(commissionPerLot = 3.5)
        assertEquals(7.0, PnlCalculator.commission(s, 1.0), 1e-9)
        assertEquals(0.7, PnlCalculator.commission(s, 0.1), 1e-9)
    }

    @Test
    fun spreadIsIncludedInFillPrice() {
        assertEquals(2000.20, com.fxsimulator.engine.FillModel.ask(2000.0, 0.20), 1e-9)
        assertEquals(
            2000.20,
            com.fxsimulator.engine.FillModel.entryPrice(TradeDirection.BUY, 2000.0, 0.20), 1e-9,
        )
        assertEquals(
            2000.0,
            com.fxsimulator.engine.FillModel.entryPrice(TradeDirection.SELL, 2000.0, 0.20), 1e-9,
        )
        assertEquals(
            2000.0,
            com.fxsimulator.engine.FillModel.exitPrice(TradeDirection.BUY, 2000.0, 0.20), 1e-9,
        )
        assertEquals(
            2000.20,
            com.fxsimulator.engine.FillModel.exitPrice(TradeDirection.SELL, 2000.0, 0.20), 1e-9,
        )
    }

    @Test
    fun netProfitEqualsGrossMinusCommission() {
        val gross = PnlCalculator.grossProfit(spec, TradeDirection.BUY, 1.0, 2000.0, 2002.0)
        val comm = PnlCalculator.commission(spec.copy(commissionPerLot = 2.0), 1.0)
        val net = PnlCalculator.netProfit(gross, comm, 0.0)
        assertEquals(200.0, gross, 1e-9)
        assertEquals(4.0, comm, 1e-9)
        assertEquals(196.0, net, 1e-9)
    }

    @Test
    fun marginEstimationAndOpenCheck() {
        val m = MarginCalculator.requiredMargin(spec, 1.0, 2000.0)
        assertEquals(400.0, m, 1e-9)
        assertTrue(MarginCalculator.canOpen(10000.0, 0.0, m))
        // 可用保证金 300 < 需要 400 → 不允许开仓
        assertFalse(MarginCalculator.canOpen(300.0, 0.0, m))
    }

    @Test
    fun lotIsNormalizedToStep() {
        assertEquals(0.10, spec.normalizeLot(0.104), 1e-9)
        assertEquals(0.01, spec.normalizeLot(0.0), 1e-9)
        assertTrue(spec.isValidLot(0.01))
        assertFalse(spec.isValidLot(0.015))
    }
}
