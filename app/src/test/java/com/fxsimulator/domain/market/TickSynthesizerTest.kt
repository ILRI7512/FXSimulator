package com.fxsimulator.domain.market

import com.fxsimulator.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1 分钟 → 12 个 5 秒 tick 的切分验收。 */
class TickSynthesizerTest {

    private fun bar(
        open: Double = 80_000.0, high: Double = 80_200.0, low: Double = 79_800.0,
        close: Double = 80_100.0, volume: Long = 1200L, t: Long = 1_700_000_000_000L,
    ) = Candle(t, open, high, low, close, volume, 0.0)

    @Test
    fun splits_one_minute_into_twelve_five_second_ticks() {
        val ticks = TickSynthesizer.split(bar())
        assertEquals("应切成 12 份", 12, ticks.size)
        for (i in 1 until ticks.size) {
            assertEquals(
                "每份间隔 5 秒",
                5_000L, ticks[i].timeMillis - ticks[i - 1].timeMillis,
            )
        }
        // 时间戳是每个 tick 的**起点**：0s, 5s … 55s；最后一根覆盖 55–60s
        assertEquals("末个 tick 起点 = 55 秒", 55_000L, ticks.last().timeMillis - ticks.first().timeMillis)
    }

    @Test
    fun first_open_and_last_close_match_the_source_bar() {
        val src = bar(open = 80_000.0, close = 80_150.0)
        val ticks = TickSynthesizer.split(src)
        assertEquals("首 tick 开盘 = 原开盘", src.open, ticks.first().open, 1e-9)
        assertEquals("末 tick 收盘 = 原收盘", src.close, ticks.last().close, 1e-9)
    }

    @Test
    fun every_tick_stays_inside_the_bar_range() {
        repeat(20) { k ->
            val src = bar(t = 1_700_000_000_000L + k * 60_000L)
            val ticks = TickSynthesizer.split(src)
            for ((i, c) in ticks.withIndex()) {
                assertTrue("第 $i 根 high 越界：${c.high} > ${src.high}", c.high <= src.high + 1e-9)
                assertTrue("第 $i 根 low 越界：${c.low} < ${src.low}", c.low >= src.low - 1e-9)
                assertTrue("第 $i 根 OHLC 不自洽", c.isValid())
            }
        }
    }

    @Test
    fun splitting_is_deterministic_for_the_same_bar() {
        val src = bar()
        val a = TickSynthesizer.split(src)
        val b = TickSynthesizer.split(src)
        assertEquals("同一根 K 线必须切出完全相同的结果", a.map { it.close }, b.map { it.close })
    }

    @Test
    fun different_bars_produce_different_paths() {
        val a = TickSynthesizer.split(bar(t = 1_700_000_000_000L))
        val b = TickSynthesizer.split(bar(t = 1_700_000_060_000L))
        assertTrue("不同时间戳应得到不同盘中路径", a.map { it.close } != b.map { it.close })
    }

    @Test
    fun flat_bar_produces_flat_ticks_without_inventing_volatility() {
        val flat = bar(open = 80_000.0, high = 80_000.0, low = 80_000.0, close = 80_000.0)
        val ticks = TickSynthesizer.split(flat)
        assertTrue("平坦 K 线不应编造波动", ticks.all { it.high == it.low && it.close == 80_000.0 })
    }

    @Test
    fun volume_is_conserved() {
        val src = bar(volume = 1207L)
        val ticks = TickSynthesizer.split(src)
        assertEquals("成交量之和必须等于原值", 1207L, ticks.sumOf { it.volume })
    }

    @Test
    fun single_tick_returns_the_bar_unchanged() {
        val src = bar()
        val one = TickSynthesizer.split(src, ticks = 1)
        assertEquals(1, one.size)
        assertEquals(src, one.first())
    }
}
