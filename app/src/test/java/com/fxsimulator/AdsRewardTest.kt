package com.fxsimulator

import com.fxsimulator.data.ads.AdsRewardPolicy
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.TradeDirection
import com.fxsimulator.domain.risk.RiskEngine
import com.fxsimulator.engine.TradingEngine
import com.fxsimulator.stats.PerformanceAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试 19、20：广告奖励上限，以及广告奖励不计入交易利润 */
class AdsRewardTest {

    @Test
    fun singleRewardIsTwoHundred() {
        assertEquals(200.0, AdsRewardPolicy.nextReward(0, 0.0), 1e-9)
        assertEquals(200.0, AdsRewardPolicy.nextReward(5, 1000.0), 1e-9)
    }

    @Test
    fun twentiethViewGrantsBonus() {
        val r = AdsRewardPolicy.nextReward(19, 19 * 200.0)
        assertEquals(AdsRewardPolicy.REWARD_PER_AD + AdsRewardPolicy.BONUS_AMOUNT, r, 1e-9)
    }

    @Test
    fun noRewardAfterDailyCap() {
        assertEquals(0.0, AdsRewardPolicy.nextReward(20, 4000.0), 1e-9)
        assertEquals(0.0, AdsRewardPolicy.nextReward(30, 4000.0), 1e-9)
    }

    @Test
    fun rewardIsTruncatedAtDailyCap() {
        // 逐条观看奖励受 4000 USD 上限约束
        assertEquals(100.0, AdsRewardPolicy.nextReward(5, 3900.0), 1e-9)
    }

    /**
     * 里程碑额外奖励是独立奖励，不计入每日 4000 USD 的逐条观看上限，
     * 否则 20 × 200 = 4000 会让「满 20 条额外奖励」永远拿不到。
     */
    @Test
    fun milestoneBonusIsGrantedOnTopOfTheViewCap() {
        assertEquals(1100.0, AdsRewardPolicy.nextReward(19, 3900.0), 1e-9)
    }

    @Test
    fun interstitialFreeUnlocksAtTenViews() {
        assertFalse(AdsRewardPolicy.isInterstitialFree(9))
        assertTrue(AdsRewardPolicy.isInterstitialFree(10))
        assertTrue(AdsRewardPolicy.isInterstitialFree(25))
    }

    /**
     * 测试 20：广告奖励只增加余额，不产生任何 TradeRecord，
     * 因此交易统计中的净利润完全不受影响。
     */
    @Test
    fun adRewardIsNotCountedAsTradingProfit() {
        val spec = SymbolSpec(contractSize = 100.0, spread = 0.20)
        val e = TradingEngine(spec, RiskEngine(), 10000.0)

        e.marketOrder(TradeDirection.BUY, 1.0, 2000.0, 1000L)
        e.closePosition(e.openPositions[0].orderId, 2001.0, 2000L)
        val tradeProfit = PerformanceAnalyzer.analyze(e.closedTrades, 10000.0).netProfit
        assertEquals(80.0, tradeProfit, 1e-6)

        e.creditReward(200.0)
        val after = PerformanceAnalyzer.analyze(e.closedTrades, e.initialBalance)

        assertEquals(10280.0, e.balance, 1e-6)
        assertEquals(tradeProfit, after.netProfit, 1e-9)
        assertEquals(1, after.totalTrades)
        assertEquals(1, e.closedTrades.size)
    }
}
