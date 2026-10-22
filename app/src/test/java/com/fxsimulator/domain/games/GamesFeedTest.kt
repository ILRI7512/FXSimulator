package com.fxsimulator.domain.games

import com.fxsimulator.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多月份连续回放（按月增量加载）的验收。 */
class GamesFeedTest {

    private fun month(prefix: Int, n: Int = 5): List<Candle> =
        (0 until n).map { i ->
            val t = prefix * 1_000_000L + i * 60_000L
            Candle(t, 100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i, 1L, 0.0)
        }

    private val months = listOf("2025-01", "2025-02", "2025-03", "2025-04")

    @Test
    fun pick_months_keeps_at_most_one_year_and_prefers_latest() {
        val fifteen = (1..15).map { "2025-%02d".format(it) }
        val picked = GamesFeed.pickMonths(fifteen, minMonths = 12)
        assertEquals("只取最近 12 个月", 12, picked.size)
        assertEquals("应是最新的月份", "2025-15", picked.last())
        assertEquals("不包含最老的两个", "2025-04", picked.first())
        // 不足 12 个月时全取
        assertEquals(3, GamesFeed.pickMonths(listOf("2025-01", "2025-02", "2025-03")).size)
        assertTrue(GamesFeed.pickMonths(emptyList()).isEmpty())
    }

    @Test
    fun loading_is_incremental_and_appends_in_order() {
        val feed = GamesFeed(months) { m -> month(months.indexOf(m) + 1) }
        assertEquals("初始应为空", 0, feed.bars.size)

        assertTrue(feed.loadNext())
        assertEquals(5, feed.bars.size)
        assertEquals(1, feed.loadedCount)

        assertTrue(feed.loadNext())
        assertEquals("第二个月应追加", 10, feed.bars.size)
        // 时间必须严格递增
        for (i in 1 until feed.bars.size) {
            assertTrue("时间必须递增（第 $i 根）", feed.bars[i].timeMillis > feed.bars[i - 1].timeMillis)
        }
    }

    @Test
    fun duplicate_timestamps_across_month_boundary_are_dropped() {
        // 第二个月的第一个时间戳与第一个月的最后一个相同 → 必须丢弃重复
        val first = listOf(Candle(1_000L, 1.0, 1.0, 1.0, 1.0, 1L, 0.0), Candle(2_000L, 1.0, 1.0, 1.0, 1.0, 1L, 0.0))
        val second = listOf(Candle(2_000L, 9.0, 9.0, 9.0, 9.0, 1L, 0.0), Candle(3_000L, 2.0, 2.0, 2.0, 2.0, 1L, 0.0))
        val feed = GamesFeed(listOf("A", "B")) { m -> if (m == "A") first else second }
        feed.loadNext()
        feed.loadNext()
        assertEquals("重复时间戳应被丢弃", 3, feed.bars.size)
        assertEquals("保留的是先出现的那根", 1.0, feed.bars[1].close, 1e-9)
    }

    @Test
    fun stops_when_months_are_exhausted() {
        val feed = GamesFeed(listOf("A")) { month(1) }
        assertTrue(feed.loadNext())
        assertFalse("没有更多月份", feed.loadNext())
        assertFalse(feed.hasMoreMonths)
        assertEquals(1, feed.loadedCount)
    }

    @Test
    fun load_at_least_reaches_one_year_when_available() {
        val twelve = (1..12).map { "2025-%02d".format(it) }
        val feed = GamesFeed(twelve) { m -> month(twelve.indexOf(m) + 1) }
        val loaded = feed.loadAtLeast(GamesFeed.MIN_MONTHS_FOR_A_YEAR)
        assertEquals(12, loaded)
        assertEquals(60, feed.bars.size)
        assertFalse(feed.hasMoreMonths)
    }

    @Test
    fun empty_month_does_not_break_the_feed() {
        val feed = GamesFeed(listOf("A", "B", "C")) { m ->
            if (m == "B") emptyList() else month(if (m == "A") 1 else 3)
        }
        feed.loadNext()
        val afterB = feed.loadNext()
        assertFalse("空月份不应追加数据", afterB)
        assertEquals("但月份计数仍然前进", 2, feed.loadedCount)
        assertTrue("后续月份仍可加载", feed.loadNext())
        assertEquals(10, feed.bars.size)
    }
    @Test
    fun months_from_start_month_keeps_at_most_one_year() {
        val fifteen = (1..15).map { "2025-%02d".format(it) }

        // 15 个月里从第 10 个月起只剩 6 个 —— "最多 12" 是上界，不是保底
        val fromTen = GamesFeed.monthsFrom(fifteen, "2025-10")
        assertEquals("从 2025-10 起实际剩 6 个月", 6, fromTen.size)
        assertEquals("2025-10", fromTen.first())
        assertEquals("2025-15", fromTen.last())

        val fromFirst = GamesFeed.monthsFrom(fifteen, "2025-01")
        assertEquals(12, fromFirst.size)
        assertEquals("2025-01", fromFirst.first())

        val fromLast = GamesFeed.monthsFrom(fifteen, "2025-15")
        assertEquals("从最后一个月起只剩 1 个", 1, fromLast.size)

        // 起点早于所有可用月份 = "从最早开始"，所以取的是最开始那 12 个
        val tooEarly = GamesFeed.monthsFrom(fifteen, "1999-01")
        assertEquals(12, tooEarly.size)
        assertEquals("2025-01", tooEarly.first())
        assertEquals("2025-12", tooEarly.last())

        assertEquals(12, GamesFeed.monthsFrom(fifteen, null).size)
    }

    @Test
    fun feed_honours_the_start_month() {
        val all = listOf("2025-01", "2025-02", "2025-03")
        val months = GamesFeed.monthsFrom(all, "2025-02")
        assertEquals(listOf("2025-02", "2025-03"), months)

        val feed = GamesFeed(months) { m -> month(all.indexOf(m) + 1) }
        feed.loadNext()
        // month(2) 的首根时间戳 = 2 * 1_000_000
        assertEquals(2_000_000L, feed.bars.first().timeMillis)
        assertEquals("起点的月份应被跳过", 2, feed.months.size)
    }
}