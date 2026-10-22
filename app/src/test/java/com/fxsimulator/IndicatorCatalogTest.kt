package com.fxsimulator

import com.fxsimulator.domain.model.Candle
import com.fxsimulator.indicators.IndicatorCatalog
import com.fxsimulator.indicators.IndicatorConfig
import com.fxsimulator.indicators.IndicatorConfigCodec
import com.fxsimulator.indicators.IndicatorEngine
import com.fxsimulator.indicators.IndicatorInstance
import com.fxsimulator.indicators.IndicatorPane
import com.fxsimulator.indicators.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指标工具库回归测试。
 *
 * 三类：
 * 1. 目录完整性 —— 17 个指标每个都必须能在真实长度的数据上算出正确形状，
 *    不许有「注册了但算不出来」的指标；
 * 2. 指标数学 —— 用能手算的输入锁定口径（这类算错的后果是给出错误的交易信号）；
 * 3. 配置编解码 —— 用户配置存进 SQLite 一个字符串，必须完全容错。
 */
class IndicatorCatalogTest {

    private fun ramp(n: Int): List<Candle> = (0 until n).map { i ->
        val c = 100.0 + i
        Candle(
            timeMillis = 1_600_000_000_000L + i * 60_000L,
            open = c - 0.5, high = c + 1.0, low = c - 1.0, close = c,
            volume = 10L, spread = 0.2,
        )
    }

    private fun flat(n: Int, price: Double = 100.0): List<Candle> = (0 until n).map { i ->
        Candle(
            timeMillis = 1_600_000_000_000L + i * 60_000L,
            open = price, high = price, low = price, close = price,
            volume = 5L, spread = 0.2,
        )
    }

    private fun closesOf(v: List<Double>) = v.toDoubleArray()

    // ================================================================ 目录完整性

    @Test
    fun catalogHasAtLeastA14Indicators() {
        assertTrue("传统指标数量要够用", IndicatorCatalog.all.size >= 14)
    }

    @Test
    fun catalogIdsAreUnique() {
        val ids = IndicatorCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyIndicatorComputesWithCorrectShape() {
        val bars = ramp(300)
        for (def in IndicatorCatalog.all) {
            val inst = IndicatorInstance(def.id, def.defaultParams())
            val r = IndicatorEngine.evaluate(inst, bars)
            assertTrue("指标 " + def.id + " 算不出来", r != null)
            assertEquals(
                "指标 " + def.id + " 输出条数与定义不符",
                def.outputs.size, r!!.outputs.size,
            )
            for (o in r.outputs) {
                assertEquals("指标 " + def.id + " 输出长度与 K 线不符", bars.size, o.size)
            }
        }
    }

    @Test
    fun everyIndicatorSurvivesShortAndFlatInput() {
        for (def in IndicatorCatalog.all) {
            for (bars in listOf(emptyList(), ramp(1), ramp(3), flat(50), flat(300))) {
                val inst = IndicatorInstance(def.id, def.defaultParams())
                // 不能抛异常；形状不对时允许返回 null，但绝不允许崩
                val r = IndicatorEngine.evaluate(inst, bars)
                if (r != null && bars.isNotEmpty()) {
                    assertEquals(bars.size, r.outputs.first().size)
                }
            }
        }
    }

    @Test
    fun catalogHasBothMainAndSubIndicators() {
        assertTrue(IndicatorCatalog.mainDefs().isNotEmpty())
        assertTrue(IndicatorCatalog.subDefs().isNotEmpty())
        assertTrue(IndicatorCatalog.mainDefs().all { it.pane == IndicatorPane.MAIN })
    }

    @Test
    fun unknownIndicatorEvaluatesToNull() {
        assertNull(IndicatorEngine.evaluate(IndicatorInstance("no_such_indicator"), ramp(50)))
        assertNull(IndicatorCatalog.byId("no_such_indicator"))
    }

    // ================================================================ 指标数学

    @Test
    fun smaMatchesHandComputedValues() {
        val out = Indicators.sma(closesOf(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 3)
        assertTrue(out[0].isNaN())
        assertTrue(out[1].isNaN())
        assertEquals(2.0, out[2], 1e-9)
        assertEquals(3.0, out[3], 1e-9)
        assertEquals(4.0, out[4], 1e-9)
    }

    @Test
    fun wmaWeightsTheMostRecentValueHighest() {
        // (1*1 + 2*2 + 3*3) / 6
        val out = Indicators.wma(closesOf(listOf(1.0, 2.0, 3.0)), 3)
        assertEquals(14.0 / 6.0, out[2], 1e-9)
        // 加权平均必然落在最小与最大之间，且不小于简单平均（递增序列）
        assertTrue(out[2] > Indicators.sma(closesOf(listOf(1.0, 2.0, 3.0)), 3)[2])
    }

    @Test
    fun bollingerBandsAreSymmetricAroundTheMiddle() {
        val vals = closesOf((1..40).map { it.toDouble() })
        val (up, mid, low) = Indicators.bollinger(vals, 20, 2.0)
        val i = 39
        assertTrue(up[i].isFinite() && low[i].isFinite())
        assertEquals(mid[i] - low[i], up[i] - mid[i], 1e-9)
        assertEquals(Indicators.sma(vals, 20)[i], mid[i], 1e-9)
    }

    @Test
    fun macdOfAConstantSeriesIsZeroAfterWarmup() {
        val vals = DoubleArray(200) { 100.0 }
        val (dif, dea, hist) = Indicators.macd(vals, 12, 26, 9)
        assertEquals(0.0, dif[199], 1e-9)
        assertEquals(0.0, dea[199], 1e-9)
        assertEquals(0.0, hist[199], 1e-9)
    }

    @Test
    fun macdHistogramIsDifMinusDea() {
        val vals = closesOf(ramp(200).map { it.close })
        val (dif, dea, hist) = Indicators.macd(vals, 12, 26, 9)
        for (i in 60 until 200) {
            if (!dif[i].isFinite() || !dea[i].isFinite()) continue
            assertEquals(dif[i] - dea[i], hist[i], 1e-9)
        }
    }

    @Test
    fun macdHandlesAProceduralSeriesWithLeadingGap() {
        // DIF 本身带前置缺口，EMA 必须能从中段开始累积，否则 MACD 永远是 NaN
        val vals = closesOf(ramp(120).map { it.close })
        val (dif, _, _) = Indicators.macd(vals, 12, 26, 9)
        assertTrue("DIF 末尾必须有值", dif[119].isFinite())
    }

    @Test
    fun rsiIs100WhenThereAreOnlyGains() {
        val vals = closesOf((1..40).map { it.toDouble() })
        val out = Indicators.rsi(vals, 14)
        assertEquals(100.0, out[39], 1e-9)
    }

    @Test
    fun rsiIsZeroWhenThereAreOnlyLosses() {
        val vals = closesOf((1..40).map { 100.0 - it })
        val out = Indicators.rsi(vals, 14)
        assertEquals(0.0, out[39], 1e-9)
    }

    @Test
    fun williamsRStaysInsideItsRange() {
        val out = Indicators.williamsR(ramp(100), 14)
        for (i in 20 until 100) {
            assertTrue("W%R 必须在 -100..0", out[i] in -100.0..0.0)
        }
        // 一路上涨 ⇒ 收在区间上沿附近
        assertTrue(out[99] > -50.0)
    }

    @Test
    fun obvAccumulatesVolumeByDirection() {
        val c = listOf(1.0, 2.0, 1.0, 3.0).mapIndexed { i, p ->
            Candle(
                timeMillis = 1000L + i, open = p, high = p, low = p, close = p,
                volume = (i + 1) * 10L, spread = 0.0,
            )
        }
        val out = Indicators.obv(c)
        assertEquals(0.0, out[0], 1e-9)
        assertEquals(20.0, out[1], 1e-9)
        assertEquals(-10.0, out[2], 1e-9)
        assertEquals(30.0, out[3], 1e-9)
    }

    @Test
    fun momentumAndRocMatchHandComputedValues() {
        val vals = closesOf(listOf(1.0, 2.0, 3.0, 10.0))
        assertEquals(2.0, Indicators.momentum(vals, 2)[2], 1e-9)
        assertEquals(8.0, Indicators.momentum(vals, 2)[3], 1e-9)
        assertEquals(100.0, Indicators.roc(closesOf(listOf(10.0, 20.0)), 1)[1], 1e-9)
    }

    @Test
    fun cciOfAConstantPriceIsZeroNotNaN() {
        // 平均绝对偏差为 0：必须返回 0，不能除零得到 Inf/NaN
        val out = Indicators.cci(flat(60), 20)
        assertEquals(0.0, out[59], 1e-9)
    }

    @Test
    fun kdjRsvMatchesTheHandComputedValue() {
        // 该 ramp 的 high = close+1、low = close-1。
        // 周期 9 的窗口里 hn = close+1，ln = (close-9)-1 = close-10，
        // 于是 RSV = (close - ln) / (hn - ln) * 100 = 9/10*100 = 90，恒定。
        // K/D 的递推不动点就是 RSV，所以 200 根后应收敛到 90（而不是 100）。
        val (k, d, j) = Indicators.kdj(ramp(200), 9, 3, 3)
        assertEquals(90.0, k[199], 0.5)
        assertEquals(90.0, d[199], 0.5)
        assertEquals(3 * k[199] - 2 * d[199], j[199], 1e-9)
    }

    @Test
    fun adxStaysInsideItsRangeAndIsFiniteAfterWarmup() {
        val (adx, pdi, mdi) = Indicators.adx(ramp(200), 14)
        assertTrue(adx[199].isFinite())
        assertTrue(adx[199] in 0.0..100.0)
        assertTrue(pdi[199] in 0.0..100.0)
        assertTrue(mdi[199] in 0.0..100.0)
    }

    @Test
    fun donchianBandsBracketTheWindow() {
        val bars = ramp(60)
        val (up, mid, low) = Indicators.donchian(bars, 20)
        val i = 59
        assertTrue(low[i] <= bars[i].close && bars[i].close <= up[i])
        assertEquals((up[i] + low[i]) / 2.0, mid[i], 1e-9)
    }

    @Test
    fun parabolicSarProducesAValueForEveryBar() {
        val out = Indicators.parabolicSar(ramp(100), 0.02, 0.2)
        for (i in 0 until 100) assertTrue("第 " + i + " 根 SAR 无值", out[i].isFinite())
    }

    @Test
    fun emaFromSkipsLeadingGapsButKeepsStateAfterwards() {
        val vals = doubleArrayOf(Double.NaN, Double.NaN, 10.0, 11.0, 12.0, 13.0)
        val out = Indicators.emaFrom(vals, 2)
        assertTrue(out[0].isNaN())
        assertTrue(out[1].isNaN())
        assertTrue("预热完成后必须有值", out[5].isFinite())
    }

    // ================================================================ 配置编解码

    @Test
    fun configRoundTripsThroughText() {
        val cfg = IndicatorConfig(
            listOf(
                IndicatorInstance("ema", mapOf("period" to 20.0)),
                IndicatorInstance("macd", mapOf("fast" to 12.0, "slow" to 26.0, "signal" to 9.0)),
            ),
        )
        val decoded = IndicatorConfigCodec.decode(IndicatorConfigCodec.encode(cfg))
        assertEquals(2, decoded.items.size)
        assertEquals(20.0, decoded.items[0].params["period"]!!, 1e-9)
        assertEquals(26.0, decoded.items[1].params["slow"]!!, 1e-9)
    }

    @Test
    fun blankTextYieldsTheDefaultConfig() {
        // 首次启动：必须是 EMA20/50 + RSI14 + ATR14，保持既有观感
        assertEquals(4, IndicatorConfigCodec.decode(null).items.size)
        assertEquals(4, IndicatorConfigCodec.decode("").items.size)
        assertEquals(4, IndicatorConfigCodec.decode("   ").items.size)
    }

    @Test
    fun emptyConfigHasItsOwnSentinel() {
        // 必须能区分「用户清空了指标」与「从未配置过」
        val encoded = IndicatorConfigCodec.encode(IndicatorConfig(emptyList()))
        assertEquals(IndicatorConfigCodec.EMPTY_SENTINEL, encoded)
        assertTrue(IndicatorConfigCodec.decode(encoded).items.isEmpty())
    }

    @Test
    fun decodeDropsUnknownIndicatorsAndKeepsTheRest() {
        val cfg = IndicatorConfigCodec.decode("not_a_real_indicator:period=5|ema:period=30")
        assertEquals(1, cfg.items.size)
        assertEquals("ema", cfg.items[0].defId)
        assertEquals(30.0, cfg.items[0].params["period"]!!, 1e-9)
    }

    @Test
    fun decodeFillsMissingParamsWithDefaults() {
        val cfg = IndicatorConfigCodec.decode("macd:fast=5")
        assertEquals(1, cfg.items.size)
        assertEquals(5.0, cfg.items[0].params["fast"]!!, 1e-9)
        assertEquals(26.0, cfg.items[0].params["slow"]!!, 1e-9)
        assertEquals(9.0, cfg.items[0].params["signal"]!!, 1e-9)
    }

    @Test
    fun decodeClampsOutOfRangeParams() {
        val cfg = IndicatorConfigCodec.decode("ema:period=99999")
        assertEquals(400.0, cfg.items[0].params["period"]!!, 1e-9)
    }

    @Test
    fun decodeIgnoresGarbageButFallsBackWhenNothingParses() {
        // 一个能解析 + 一段垃圾：保留能解析的
        val mixed = IndicatorConfigCodec.decode("ema:period=30|garbage::==|also_bad")
        assertEquals(1, mixed.items.size)
        // 全垃圾：回落默认，绝不让图表变空
        assertEquals(4, IndicatorConfigCodec.decode("garbage::==|also_bad").items.size)
    }

    @Test
    fun decodeRoundsIntegerParams() {
        val cfg = IndicatorConfigCodec.decode("ema:period=20.7")
        assertEquals(21.0, cfg.items[0].params["period"]!!, 1e-9)
    }

    // ================================================================ 配置操作

    @Test
    fun configAddRemoveMoveAndReplace() {
        var c = IndicatorConfig(emptyList())
        c = c.withAdded(IndicatorInstance("ema", mapOf("period" to 20.0)))
        c = c.withAdded(IndicatorInstance("rsi", mapOf("period" to 14.0)))
        c = c.withAdded(IndicatorInstance("atr", mapOf("period" to 14.0)))
        assertEquals(3, c.items.size)

        c = c.withMoved(2, -1)
        assertEquals("atr", c.items[1].defId)

        c = c.withReplacedAt(0, IndicatorInstance("ema", mapOf("period" to 50.0)))
        assertEquals(50.0, c.items[0].params["period"]!!, 1e-9)

        c = c.withRemovedAt(1)
        assertEquals(2, c.items.size)
    }

    @Test
    fun configOperationsAreSafeOnBadIndices() {
        val c = IndicatorConfig(listOf(IndicatorInstance("ema", mapOf("period" to 20.0))))
        assertEquals(1, c.withRemovedAt(99).items.size)
        assertEquals(1, c.withRemovedAt(-1).items.size)
        assertEquals("ema", c.withMoved(0, 5).items[0].defId)
        assertEquals("ema", c.withReplacedAt(9, IndicatorInstance("rsi")).items[0].defId)
    }

    @Test
    fun addingAnUnknownIndicatorIsDroppedByNormalization() {
        val c = IndicatorConfig(emptyList()).withAdded(IndicatorInstance("nope"))
        assertTrue(c.items.isEmpty())
    }

    @Test
    fun theSameIndicatorCanBeAddedTwiceWithDifferentParams() {
        // EMA20 与 EMA50 同时叠加是最常见的用法，不能被去重
        val c = IndicatorConfig(emptyList())
            .withAdded(IndicatorInstance("ema", mapOf("period" to 20.0)))
            .withAdded(IndicatorInstance("ema", mapOf("period" to 50.0)))
        assertEquals(2, c.items.size)
        assertFalse(c.items[0].params["period"] == c.items[1].params["period"])
    }
}
