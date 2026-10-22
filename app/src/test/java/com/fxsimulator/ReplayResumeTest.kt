package com.fxsimulator

import com.fxsimulator.engine.ReplayResume
import com.fxsimulator.engine.SourceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.fxsimulator.engine.StartupMonth

/**
 * 回放存档「从第几根继续」的判定。
 *
 * 这段逻辑原本埋在 initialLoad 里，一个 != 写反了就会静默失效 ——
 * 不报错、不崩溃，只是每次重启都从第 0 根开始。
 * 所以它必须被测试盯住。
 */
class ReplayResumeTest {

    private val fileKey = "file:/storage/emulated/0/A/ADSH/XAUUSDm_M1.csv"

    // ---------------------------------------------------------------- 来源键

    @Test
    fun sourceKeyPrefersThePersistIdNotTheDisplayName() {
        // 关键：身份必须是 file:/… 这种持久化 id，不能是给用户看的显示名
        assertEquals(fileKey, SourceKeys.of(fileKey, isDemo = false))
    }

    @Test
    fun demoGetsItsOwnFixedKey() {
        assertEquals(SourceKeys.DEMO, SourceKeys.of(null, isDemo = true))
        // DEMO 即使拿到了 id 也应该是 demo，避免和真实文件混在一起
        assertEquals(SourceKeys.DEMO, SourceKeys.of(fileKey, isDemo = true))
    }

    @Test
    fun missingPersistIdFallsBackToUnknown() {
        // 兜底键不能匹配上任何已存档来源，否则会把别处的存档错认成自己的
        assertEquals(SourceKeys.UNKNOWN, SourceKeys.of(null, isDemo = false))
        assertEquals(SourceKeys.UNKNOWN, SourceKeys.of("   ", isDemo = false))
        assertFalse(SourceKeys.UNKNOWN == fileKey)
    }

    // ---------------------------------------------------------------- 恢复判定

    @Test
    fun resumesFromTheSavedIndexForTheSameSource() {
        val d = ReplayResume.decide(fileKey, 80_000, fileKey, 2_000_000)
        assertEquals(80_000, d.index)
        assertEquals(ReplayResume.Outcome.RESUMED, d.outcome)
        assertTrue(d.resumed)
    }

    @Test
    fun aDifferentSourceStartsFromTheBeginning() {
        val d = ReplayResume.decide("file:/other.csv", 80_000, fileKey, 2_000_000)
        assertEquals(0, d.index)
        assertEquals(ReplayResume.Outcome.SOURCE_CHANGED, d.outcome)
        assertFalse(d.resumed)
    }

    @Test
    fun noBookmarkStartsFromTheBeginning() {
        val d = ReplayResume.decide(null, 0, fileKey, 2_000_000)
        assertEquals(0, d.index)
        assertEquals(ReplayResume.Outcome.NO_SAVE, d.outcome)
        // 空字符串等同于没有存档，不能当成一个有效键
        assertEquals(ReplayResume.Outcome.NO_SAVE, ReplayResume.decide("  ", 500, fileKey, 100).outcome)
    }

    @Test
    fun anIndexPastTheEndIsClampedToTheLastBar() {
        // 文件被换成了更短的版本（或上次正好回放到末尾）
        val d = ReplayResume.decide(fileKey, 9_999_999, fileKey, 500)
        assertEquals(499, d.index)
        assertEquals(ReplayResume.Outcome.CLAMPED, d.outcome)
        assertTrue("夹取后仍算恢复成功，要告诉用户", d.resumed)
    }

    @Test
    fun anIndexExactlyAtTheEndIsAlsoClamped() {
        val d = ReplayResume.decide(fileKey, 500, fileKey, 500)
        assertEquals(499, d.index)
        assertEquals(ReplayResume.Outcome.CLAMPED, d.outcome)
    }

    @Test
    fun aNegativeIndexIsTreatedAsTheStart() {
        val d = ReplayResume.decide(fileKey, -5, fileKey, 500)
        assertEquals(0, d.index)
        assertEquals(ReplayResume.Outcome.RESUMED, d.outcome)
    }

    @Test
    fun indexAtTheVeryStartCountsAsResumed() {
        // 存在第 0 根也是「有存档」，不应报成 SOURCE_CHANGED 或 NO_SAVE
        val d = ReplayResume.decide(fileKey, 0, fileKey, 500)
        assertEquals(0, d.index)
        assertEquals(ReplayResume.Outcome.RESUMED, d.outcome)
    }

    @Test
    fun anEmptySourceNeverResumes() {
        assertEquals(ReplayResume.Outcome.NO_SAVE, ReplayResume.decide(fileKey, 100, fileKey, 0).outcome)
        assertEquals(ReplayResume.Outcome.NO_SAVE, ReplayResume.decide(fileKey, 100, fileKey, -1).outcome)
    }

    @Test
    fun aSingleBarSourceIsHandled() {
        val d = ReplayResume.decide(fileKey, 5, fileKey, 1)
        assertEquals(0, d.index)
        assertEquals(ReplayResume.Outcome.CLAMPED, d.outcome)
    }

    @Test
    fun everyReturnedIndexIsWithinTheSource() {
        val sizes = listOf(0, 1, 2, 500)
        val indexes = listOf(-1, 0, 1, 250, 499, 500, 100000)
        for (size in sizes) {
            for (idx in indexes) {
                val d = ReplayResume.decide(fileKey, idx, fileKey, size)
                if (size <= 0) {
                    assertEquals(0, d.index)
                } else {
                    assertTrue(
                        "size=" + size + " idx=" + idx + " 越界了",
                        d.index in 0 until size,
                    )
                }
            }
        }
    }
    // ------------------------------------------------------------ 内置月份的存档
    //
    // 下面两条是「存档看起来坏了」那次问题的回归测试。
    // 真机上表现为：不管玩到第几根，重启后都回到开头；切走再切回来也一样。
    // 原因是两个地方各写各的来源键 ——
    //   initialLoad 用 SourceKeys.of(lastId, isDemo)（只会得到 unknown）
    //   useBundledMonth 用 "bundled:" + month
    // 两边对不上，存档永远读不出来。现在键只允许由 SourceKeys.bundled 生成。

    @Test
    fun bundledKeyRoundTrips() {
        for (m in listOf("2024-01", "2025-06", "2026-09")) {
            val key = SourceKeys.bundled(m)
            assertEquals("bundled:" + m, key)
            assertEquals("月份必须能从键还原出来", m, SourceKeys.bundledMonth(key))
        }
    }

    @Test
    fun bundledKeyIsNotNullForOtherSources() {
        // 其它来源的键绝不能被误认成内置月份，否则会读到别的数据源的存档
        assertNull(SourceKeys.bundledMonth(SourceKeys.DEMO))
        assertNull(SourceKeys.bundledMonth(SourceKeys.UNKNOWN))
        assertNull(SourceKeys.bundledMonth("file:/tmp/x.csv"))
        assertNull(SourceKeys.bundledMonth("uri:content://x"))
        assertNull(SourceKeys.bundledMonth(null))
        assertNull(SourceKeys.bundledMonth("bundled:"))
    }

    @Test
    fun startupRestoresTheMonthYouWerePlaying() {
        val available = listOf("2024-01", "2024-02", "2024-03")
        assertEquals("2024-03", StartupMonth.pick("2024-03", available))
        assertEquals("2024-01", StartupMonth.pick("2024-01", available))
    }

    @Test
    fun startupFallsBackWhenTheSavedMonthIsGone() {
        val available = listOf("2024-01", "2024-02")
        // 存的月份已经不在包里了（换版本 / 裁数据）→ 退回第一个，而不是随机或最后一个
        assertEquals("2024-01", StartupMonth.pick("2099-12", available))
        assertEquals("2024-01", StartupMonth.pick("", available))
        assertEquals("2024-01", StartupMonth.pick(null, available))
        assertEquals("2024-01", StartupMonth.pick("   ", available))
        // 一个都没有时返回空串，由调用方去报错，不要造一个不存在的月份
        assertEquals("", StartupMonth.pick("2024-01", emptyList()))
    }
}
