package com.fxsimulator

import com.fxsimulator.data.market.InMemoryBarSource
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.engine.MIN_BAR_INTERVAL_MILLIS
import com.fxsimulator.engine.ReplayEngine
import com.fxsimulator.engine.replayIntervalMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放引擎回归测试。
 *
 * 这里刻意使用 [Dispatchers.Unconfined]：它与 viewModelScope 的
 * Dispatchers.Main.immediate 具有同样的关键特性——**不需要派发，
 * 协程体会在 launch() 返回之前同步执行**。因此可以稳定复现
 * 「点了播放但行情不动」这一真实缺陷。
 */
class ReplayEngineTest {

    private fun candles(n: Int): List<Candle> = (0 until n).map {
        Candle(
            timeMillis = 1000L + it * 60_000L,
            open = 2000.0, high = 2001.0, low = 1999.0, close = 2000.5,
            volume = 1L, spread = 0.2,
        )
    }

    private fun source(n: Int = 400) = InMemoryBarSource(candles(n), "test")

    @Test
    fun playAdvancesBarsAndPauseStopsAdvancing() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val engine = ReplayEngine(scope, baseBarMillis = 10L)
        try {
            engine.load(source(), 0)
            assertEquals(0, engine.state.value.index)

            assertTrue("play() 应当确实启动了回放循环", engine.play())
            assertTrue("play() 后 playing 应为 true", engine.state.value.playing)

            Thread.sleep(200)
            val advanced = engine.state.value.index
            assertTrue("播放后行情没有推进（index 仍为 " + advanced + "）", advanced > 0)

            // 暂停后价格与时间都不得继续推进
            engine.pause()
            assertFalse("暂停后 playing 应为 false", engine.state.value.playing)
            val frozen = engine.state.value.index
            val frozenCandle = engine.state.value.candle
            Thread.sleep(150)
            assertEquals("暂停后行情仍在推进", frozen, engine.state.value.index)
            assertEquals("暂停后 K 线仍在变化", frozenCandle, engine.state.value.candle)

            // 恢复播放应接着暂停处继续
            assertTrue(engine.play())
            Thread.sleep(150)
            assertTrue("恢复播放后没有继续推进", engine.state.value.index > frozen)
        } finally {
            engine.pause()
            scope.cancel()
        }
    }

    /** 重复点播放不得启动第二个回放任务 */
    @Test
    fun repeatedPlayDoesNotStartSecondJob() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val engine = ReplayEngine(scope, baseBarMillis = 10L)
        try {
            engine.load(source(), 0)
            assertTrue(engine.play())
            assertFalse("已在运行时再次 play() 不应启动新任务", engine.play())
            assertFalse(engine.play())
            assertTrue(engine.state.value.playing)
        } finally {
            engine.pause()
            scope.cancel()
        }
    }

    /** 走到末尾应自动停止并置 finished */
    @Test
    fun replayStopsAtEndOfData() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val engine = ReplayEngine(scope, baseBarMillis = 1L)
        try {
            engine.load(source(5), 0)
            engine.play()
            Thread.sleep(300)
            assertEquals("应停在最后一根", 4, engine.state.value.index)
            assertTrue("到末尾应置 finished", engine.state.value.finished)
            assertFalse("到末尾后 playing 应为 false", engine.state.value.playing)
        } finally {
            engine.pause()
            scope.cancel()
        }
    }

    /** 单步前进：不播放也能推进一根，且不会把状态留在播放中 */
    @Test
    fun stepForwardAdvancesExactlyOneBar() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val engine = ReplayEngine(scope, baseBarMillis = 1000L)
        try {
            engine.load(source(), 0)
            engine.stepForward()
            assertEquals(1, engine.state.value.index)
            assertFalse(engine.state.value.playing)
            engine.stepForward()
            assertEquals(2, engine.state.value.index)
        } finally {
            engine.pause()
            scope.cancel()
        }
    }

    /**
     * 倍速对推进间隔的影响，用**纯函数**断言，而不是比较两个引擎在墙钟上推进了几根。
     *
     * 原因（2026-10-19 实测）：引擎把间隔夹在 [MIN_BAR_INTERVAL_MILLIS]，而本机
     * Windows 计时器粒度约 15.6ms，`delay(16)` 常被推到 ~31ms。旧断言
     * （`baseBarMillis = 40` 下要求 10x 比 1x 多推进 >10 根）在本机 **5/5 必失败**：
     * 实测 slow=8~9、fast=14~15，差值只有 6~7 根 —— 它既依赖机器与负载，也表达不出真实语义。
     */
    @Test
    fun intervalFollowsSpeedAndIsClampedToMinimum() {
        // 生产默认 baseBarMillis = 1000：标称倍率如约生效
        assertEquals(1000L, replayIntervalMillis(1000L, 1))
        assertEquals(500L, replayIntervalMillis(1000L, 2))
        assertEquals(200L, replayIntervalMillis(1000L, 5))
        assertEquals(100L, replayIntervalMillis(1000L, 10))

        // 低基线（旧测试用的 40ms）：被下限截断
        assertEquals(40L, replayIntervalMillis(40L, 1))
        assertEquals(20L, replayIntervalMillis(40L, 2))
        assertEquals(MIN_BAR_INTERVAL_MILLIS, replayIntervalMillis(40L, 5))
        assertEquals(MIN_BAR_INTERVAL_MILLIS, replayIntervalMillis(40L, 10))

        // 把「5x 与 10x 在低基线下完全等价」钉成断言：
        // 这是当前引擎的真实行为，不是缺陷猜测；要让 10x 名副其实必须下调下限。
        assertEquals(replayIntervalMillis(40L, 5), replayIntervalMillis(40L, 10))

        // 非法速度按 1 倍处理，任何情况下都不得出现 0 或负间隔
        assertEquals(40L, replayIntervalMillis(40L, 0))
        assertEquals(40L, replayIntervalMillis(40L, -1))
        assertEquals(MIN_BAR_INTERVAL_MILLIS, replayIntervalMillis(1L, 10))
    }

    /** 倍速档位被引擎正确接受，且回放确实在推进（不再对墙钟倍率做断言） */
    @Test
    fun speedIsClampedToSupportedStepsAndReplayAdvances() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val engine = ReplayEngine(scope, baseBarMillis = 16L)
        try {
            engine.load(source(), 0)
            engine.setSpeed(0)
            assertEquals("速度低于 1 应归一到 1", 1, engine.state.value.speed)
            engine.setSpeed(3)
            assertEquals("非档位值应向上取到最近的档位", 5, engine.state.value.speed)
            engine.setSpeed(7)
            assertEquals(10, engine.state.value.speed)
            engine.setSpeed(10)
            assertEquals(10, engine.state.value.speed)

            assertTrue("play 应当启动回放循环", engine.play())
            Thread.sleep(200)
            engine.pause()
            assertTrue(
                "回放应当至少推进一根，实际 index=" + engine.state.value.index,
                engine.state.value.index >= 1,
            )
            assertFalse("pause 之后不应仍在播放", engine.state.value.playing)
        } finally {
            engine.pause()
            scope.cancel()
        }
    }
}
