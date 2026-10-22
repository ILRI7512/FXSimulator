package com.fxsimulator

import com.fxsimulator.domain.model.Candle
import com.fxsimulator.indicators.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试 14、15、16：EMA / RSI / ATR */
class IndicatorsTest {

    private fun rising(n: Int): DoubleArray = DoubleArray(n) { 100.0 + it }

    @Test
    fun emaReturnsNoValueWhenDataInsufficient() {
        val out = Indicators.ema(DoubleArray(3) { 1.0 }, 20)
        assertEquals(3, out.size)
        for (v in out) assertFalse(Indicators.isValid(v))
    }

    @Test
    fun emaSeedIsSimpleAverageOfFirstPeriod() {
        val values = DoubleArray(5) { (it + 1).toDouble() }
        val out = Indicators.ema(values, 3)
        assertEquals(2.0, out[2], 1e-9)
        assertEquals(3.0, out[3], 1e-9)
        assertEquals(4.0, out[4], 1e-9)
    }

    @Test
    fun emaOfConstantSeriesEqualsConstant() {
        val out = Indicators.ema(DoubleArray(50) { 1234.5 }, 20)
        assertEquals(1234.5, out[19], 1e-9)
        assertEquals(1234.5, out[49], 1e-9)
    }

    @Test
    fun rsiIs100WhenAlwaysRising() {
        val out = Indicators.rsi(rising(30), 14)
        assertEquals(100.0, out[14], 1e-9)
        assertEquals(100.0, out[29], 1e-9)
    }

    @Test
    fun rsiIsZeroWhenAlwaysFalling() {
        val values = DoubleArray(30) { 100.0 - it }
        val out = Indicators.rsi(values, 14)
        assertEquals(0.0, out[14], 1e-9)
        assertEquals(0.0, out[29], 1e-9)
    }

    @Test
    fun rsiInvalidWhenDataInsufficient() {
        val out = Indicators.rsi(rising(10), 14)
        for (v in out) assertFalse(Indicators.isValid(v))
    }

    private fun candles(n: Int, range: Double = 1.0): List<Candle> =
        (0 until n).map {
            val base = 2000.0 + it * 0.5
            Candle(
                timeMillis = 1000L + it * 60_000L,
                open = base,
                high = base + range,
                low = base - range,
                close = base + 0.2,
            )
        }

    @Test
    fun atrEqualsAverageTrueRange() {
        val cs = candles(20, range = 1.0)
        val atr = Indicators.atr(cs, 14)
        assertTrue(Indicators.isValid(atr[13]))
        assertEquals(2.0, atr[13], 0.05)
    }

    @Test
    fun atrInvalidWhenDataInsufficient() {
        val atr = Indicators.atr(candles(5), 14)
        for (v in atr) assertFalse(Indicators.isValid(v))
    }

    @Test
    fun lastValidReturnsTrailingValue() {
        val arr = doubleArrayOf(Double.NaN, 1.0, 2.0, Double.NaN)
        assertEquals(2.0, Indicators.lastValid(arr)!!, 1e-9)
        assertEquals(null, Indicators.lastValid(doubleArrayOf(Double.NaN)))
    }
}
