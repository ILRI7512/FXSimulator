package com.fxsimulator

import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.CandleAggregator
import com.fxsimulator.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M1 → 高周期聚合的单元测试。
 *
 * 重点保证：聚合出来的开高低收只来自真实 M1 数据，
 * 不补造缺失的分钟，并且「增量合并」与「整段聚合」结果完全一致
 * （回放是增量走的，两者不一致就会出现图表与实际数据不符）。
 */
class CandleAggregatorTest {

    /** 2024-01-01 00:00:00 UTC */
    private val base = 1704067200000L

    private fun m1(minute: Int, o: Double, h: Double, l: Double, c: Double, v: Long = 1L) =
        Candle(base + minute * 60_000L, o, h, l, c, v, 0.2)

    @Test
    fun m1IsPassedThrough() {
        val bars = (0 until 10).map { m1(it, 100.0, 101.0, 99.0, 100.5) }
        val agg = CandleAggregator.aggregate(bars, Timeframe.M1)
        assertEquals(bars, agg)
    }

    @Test
    fun fiveM1BarsBecomeOneM5Bar() {
        val bars = listOf(
            m1(0, 100.0, 101.0, 99.0, 100.5),
            m1(1, 100.5, 103.0, 100.0, 102.0),
            m1(2, 102.0, 102.5, 98.0, 99.0),
            m1(3, 99.0, 100.0, 97.5, 98.5),
            m1(4, 98.5, 99.5, 98.0, 99.0),
        )
        val agg = CandleAggregator.aggregate(bars, Timeframe.M5)
        assertEquals(1, agg.size)
        val b = agg[0]
        assertEquals(base, b.timeMillis)
        assertEquals("开盘取第一根", 100.0, b.open, 1e-9)
        assertEquals("最高取最大", 103.0, b.high, 1e-9)
        assertEquals("最低取最小", 97.5, b.low, 1e-9)
        assertEquals("收盘取最后一根", 99.0, b.close, 1e-9)
        assertEquals("成交量求和", 5L, b.volume)
    }

    @Test
    fun bucketBoundarySplitsBars() {
        val bars = listOf(m1(4, 100.0, 101.0, 99.0, 100.5), m1(5, 101.0, 102.0, 100.0, 101.5))
        val agg = CandleAggregator.aggregate(bars, Timeframe.M5)
        assertEquals(2, agg.size)
        assertEquals(base, agg[0].timeMillis)
        assertEquals(base + 5 * 60_000L, agg[1].timeMillis)
    }

    @Test
    fun m15BucketsAlignToQuarterHour() {
        val bars = (0 until 15).map { m1(it, 100.0 + it, 101.0 + it, 99.0 + it, 100.5 + it) } +
            m1(15, 200.0, 201.0, 199.0, 200.5)
        val agg = CandleAggregator.aggregate(bars, Timeframe.M15)
        assertEquals(2, agg.size)
        assertEquals(base, agg[0].timeMillis)
        assertEquals(base + 15 * 60_000L, agg[1].timeMillis)
    }

    @Test
    fun h4BucketsAlignToFourHours() {
        val bars = (0 until 240).map { m1(it, 100.0, 101.0, 99.0, 100.5) } +
            m1(240, 200.0, 201.0, 199.0, 200.5)
        val agg = CandleAggregator.aggregate(bars, Timeframe.H4)
        assertEquals(2, agg.size)
        assertEquals(base, agg[0].timeMillis)
        assertEquals(base + 240 * 60_000L, agg[1].timeMillis)
    }

    @Test
    fun d1BucketsAlignToUtcMidnight() {
        val bars = (0 until 1440).map { m1(it, 100.0, 101.0, 99.0, 100.5) } +
            m1(1440, 200.0, 201.0, 199.0, 200.5)
        val agg = CandleAggregator.aggregate(bars, Timeframe.D1)
        assertEquals(2, agg.size)
        assertEquals(base, agg[0].timeMillis)
        assertEquals(base + 86_400_000L, agg[1].timeMillis)
    }

    /** 缺口不补：只有零散的 M1 时，不能凭空造出中间的 K 线 */
    @Test
    fun doesNotInventMissingMinutes() {
        val bars = listOf(
            m1(0, 100.0, 101.0, 99.0, 100.5),
            m1(2, 101.0, 102.0, 100.0, 101.5),
            m1(4, 102.0, 103.0, 101.0, 102.5),
        )
        val agg = CandleAggregator.aggregate(bars, Timeframe.M5)
        assertEquals(1, agg.size)
        assertEquals(103.0, agg[0].high, 1e-9)
        assertEquals(99.0, agg[0].low, 1e-9)
        assertEquals(3L, agg[0].volume)
    }

    /**
     * 关键一致性测试：回放是逐根 M1 增量合并的，
     * 结果必须与一次性聚合完全相同，否则图表会与真实数据不符。
     */
    @Test
    fun incrementalMergeMatchesBatchAggregate() {
        val bars = (0 until 37).map {
            m1(it, 100.0 + it, 101.5 + it, 98.5 + it, 100.5 + it, 2L)
        }
        for (tf in Timeframe.entries) {
            val batch = CandleAggregator.aggregate(bars, tf)

            var last: Candle? = null
            val incremental = ArrayList<Candle>()
            for (b in bars) {
                val merged = CandleAggregator.mergeInto(last, b, tf)
                if (last == null || last.timeMillis != merged.timeMillis) {
                    incremental.add(merged)
                } else {
                    incremental[incremental.size - 1] = merged
                }
                last = merged
            }

            assertEquals("周期 " + tf.label + " 的增量结果与批量聚合不一致", batch, incremental)
        }
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertEquals(0, CandleAggregator.aggregate(emptyList(), Timeframe.H1).size)
    }
}
