package com.fxsimulator

import com.fxsimulator.domain.model.Candle
import com.fxsimulator.ui.chart.ChartMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表几何回归测试。
 *
 * 这些数学决定了 MT5 式交互是否正确：十字光标指向哪根、捏合缩放锚点是否稳定、
 * 拖动方向对不对、价格轴是否把持仓价位纳进来。UI 层只是调用者，
 * 所以这里能不依赖 Android 框架直接验证。
 */
class ChartMathTest {

    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double) = Candle(
        timeMillis = 1_600_000_000_000L + i * 60_000L,
        open = o,
        high = h,
        low = l,
        close = c,
        volume = 1L,
        spread = 0.2,
    )

    /** n 根匀速上行的合成 K 线：high = base + 1，low = base - 1 */
    private fun series(n: Int): List<Candle> = (0 until n).map {
        val base = 2000.0 + it * 0.1
        bar(it, base, base + 1.0, base - 1.0, base + 0.4)
    }

    // ---------------------------------------------------------------- 可见根数

    @Test
    fun visibleCountScalesWithZoom() {
        assertEquals(60, ChartMath.visibleCount(1000, 1f))
        assertEquals(120, ChartMath.visibleCount(1000, 2f))
    }

    @Test
    fun visibleCountHasFloorAndCeiling() {
        // 缩到最小也保留 15 根，避免出现「一根 K 线占满屏」
        assertEquals(15, ChartMath.visibleCount(1000, 0.01f))
        // 放到最大也不可能超过数据总量
        assertEquals(1000, ChartMath.visibleCount(1000, 100f))
    }

    @Test
    fun clampZoomBounds() {
        assertEquals(ChartMath.MAX_ZOOM, ChartMath.clampZoom(999f), 1e-6f)
        assertEquals(ChartMath.MIN_ZOOM, ChartMath.clampZoom(0.001f), 1e-6f)
        assertEquals(1.5f, ChartMath.clampZoom(1.5f), 1e-6f)
    }

    // ---------------------------------------------------------------- 可见窗口

    @Test
    fun windowAnchorsToLatestByDefault() {
        val w = ChartMath.window(total = 500, zoom = 1f, offsetBars = 0f)
        assertEquals(440, w.first)
        assertEquals(499, w.last)
        assertEquals(60, ChartMath.count(w))
    }

    @Test
    fun windowScrollsBackWithOffset() {
        val w = ChartMath.window(total = 500, zoom = 1f, offsetBars = 100f)
        assertEquals(399, w.last)
        assertEquals(60, ChartMath.count(w))
    }

    @Test
    fun windowClampsExcessiveOffset() {
        val w = ChartMath.window(total = 500, zoom = 1f, offsetBars = 99999f)
        assertEquals(0, w.first)
        assertEquals(59, w.last)
    }

    @Test
    fun windowHandlesSeriesShorterThanMinimumVisible() {
        val w = ChartMath.window(total = 10, zoom = 1f, offsetBars = 0f)
        assertEquals(0, w.first)
        assertEquals(9, w.last)
        assertEquals(10, ChartMath.count(w))
    }

    @Test
    fun windowOfEmptySeriesIsEmpty() {
        assertEquals(0, ChartMath.count(ChartMath.window(total = 0, zoom = 1f, offsetBars = 0f)))
    }

    @Test
    fun clampOffsetBounds() {
        assertEquals(0f, ChartMath.clampOffset(-50f, 500, 1f), 1e-6f)
        assertEquals(440f, ChartMath.clampOffset(1e6f, 500, 1f), 1e-6f)
        assertEquals(123f, ChartMath.clampOffset(123f, 500, 1f), 1e-6f)
    }

    // ---------------------------------------------------------------- 价格范围

    @Test
    fun priceRangeCoversVisibleBarsWithPadding() {
        val bars = series(500)
        val w = ChartMath.window(500, 1f, 0f)
        val (minP, maxP) = ChartMath.priceRange(bars, w)
        val low = bars.subList(w.first, w.last + 1).minOf { it.low }
        val high = bars.subList(w.first, w.last + 1).maxOf { it.high }
        assertTrue("下沿要在最低点之下", minP < low)
        assertTrue("上沿要在最高点之上", maxP > high)
        // 留白为区间的 8%
        assertEquals((high - low) * 0.08, low - minP, 1e-6)
        assertEquals((high - low) * 0.08, maxP - high, 1e-6)
    }

    @Test
    fun priceRangeIncludesOverlaySeriesAndPositionLevels() {
        val bars = series(100)
        val w = ChartMath.window(100, 1f, 0f)
        val ma = MutableList(100) { Double.NaN }
        ma[99] = 2500.0
        val levels = listOf(1800.0, 2600.0)
        val (minP, maxP) = ChartMath.priceRange(bars, w, extraSeries = listOf(ma), levels = levels)
        // 叠加线与持仓价位都要被框进去，否则会画到图外
        assertTrue(minP < 1800.0)
        assertTrue(maxP > 2600.0)
    }

    @Test
    fun priceRangeIgnoresSeriesThatAreNotPassedIn() {
        // 未启用的指标序列根本不传进来，自然不该影响价格轴
        val bars = series(100)
        val w = ChartMath.window(100, 1f, 0f)
        val ma = MutableList(100) { 9000.0 }
        val without = ChartMath.priceRange(bars, w, extraSeries = emptyList())
        assertTrue("没传进来的序列不能拉高上沿", without.second < 9000.0)
        // 对照：传进来就必须被纳入
        val with = ChartMath.priceRange(bars, w, extraSeries = listOf(ma))
        assertTrue(with.second > 9000.0)
    }

    // ---------------------------------------------------------------- 多序列区间合并

    @Test
    fun mergeRangesUnionsTheInputs() {
        val m = ChartMath.mergeRanges(listOf(10.0 to 20.0, 5.0 to 8.0, 15.0 to 40.0))
        assertEquals(5.0, m.first, 1e-9)
        assertEquals(40.0, m.second, 1e-9)
    }

    @Test
    fun mergeRangesSkipsInvalidEntries() {
        // NaN 区间、上界小于下界的区间都不能污染结果
        val m = ChartMath.mergeRanges(
            listOf(Double.NaN to Double.NaN, 30.0 to 10.0, 10.0 to 20.0),
        )
        assertEquals(10.0, m.first, 1e-9)
        assertEquals(20.0, m.second, 1e-9)
    }

    @Test
    fun mergeRangesFallsBackWhenNothingIsUsable() {
        assertEquals(0.0 to 1.0, ChartMath.mergeRanges(emptyList()))
        assertEquals(0.0 to 1.0, ChartMath.mergeRanges(listOf(Double.NaN to Double.NaN)))
    }

    @Test
    fun priceRangeHandlesCompletelyFlatSeries() {
        val flat = List(50) { bar(it, 2000.0, 2000.0, 2000.0, 2000.0) }
        val (minP, maxP) = ChartMath.priceRange(flat, ChartMath.window(50, 1f, 0f))
        assertTrue("水平行情也要给出非零区间，否则除零", maxP > minP)
        assertEquals(2000.0, (minP + maxP) / 2.0, 1e-9)
    }

    // ---------------------------------------------------------------- 像素换算

    @Test
    fun xAtCentersBarsInsideTheirSlots() {
        assertEquals(5f, ChartMath.xAt(0, 600f, 60), 1e-3f)
        assertEquals(595f, ChartMath.xAt(59, 600f, 60), 1e-3f)
    }

    @Test
    fun indexAtXInvertsXAt() {
        for (i in 0 until 60) {
            val x = ChartMath.xAt(i, 600f, 60)
            assertEquals("第 " + i + " 根反查失败", i, ChartMath.indexAtX(x, 600f, 60))
        }
    }

    @Test
    fun indexAtXClampsOutsideArea() {
        assertEquals(0, ChartMath.indexAtX(-100f, 600f, 60))
        assertEquals(59, ChartMath.indexAtX(100000f, 600f, 60))
    }

    @Test
    fun yAtAndPriceAtYAreInverse() {
        val minP = 1900.0
        val maxP = 2100.0
        val h = 400f
        // 上沿 = 最高价，下沿 = 最低价
        assertEquals(0f, ChartMath.yAt(maxP, minP, maxP, h), 1e-3f)
        assertEquals(h, ChartMath.yAt(minP, minP, maxP, h), 1e-3f)
        for (p in listOf(1900.0, 1950.0, 2000.0, 2050.0, 2100.0)) {
            val y = ChartMath.yAt(p, minP, maxP, h)
            assertEquals(p, ChartMath.priceAtY(y, minP, maxP, h), 1e-6)
        }
    }

    // ---------------------------------------------------------------- 平移

    @Test
    fun draggingRightLooksBackAtHistory() {
        // slot = 600/60 = 10px；向右拖 100px = 回看 10 根
        val off = ChartMath.panOffset(current = 0f, panXPx = 100f, areaWidth = 600f, n = 60, total = 500, zoom = 1f)
        assertEquals(10f, off, 1e-3f)
    }

    @Test
    fun draggingLeftReturnsToLatestButStopsAtZero() {
        val off = ChartMath.panOffset(current = 5f, panXPx = -1000f, areaWidth = 600f, n = 60, total = 500, zoom = 1f)
        assertEquals(0f, off, 1e-3f)
    }

    @Test
    fun panningIsClampedToOldestBar() {
        val off = ChartMath.panOffset(current = 0f, panXPx = 1e6f, areaWidth = 600f, n = 60, total = 500, zoom = 1f)
        assertEquals(440f, off, 1e-3f)
    }

    // ---------------------------------------------------------------- 锚点缩放

    /** 在给定视图状态下，anchorFrac 处那根 K 线的全局索引 */
    private fun anchorIndexOf(total: Int, zoom: Float, offset: Float, frac: Float): Int {
        val w = ChartMath.window(total, zoom, offset)
        val n = ChartMath.count(w)
        return w.first + Math.round(frac * (n - 1))
    }

    @Test
    fun zoomKeepsTheAnchorBarUnderTheFingers() {
        // 视图必须停在历史中间：贴着最新一根时没有「未来的数据」可显示，
        // 锚点必然跟着右边界滑（真实图表软件同样如此），那不是缺陷。
        val total = 500
        val frac = 0.5f
        val offset = 200f
        val before = anchorIndexOf(total, 1f, offset, frac)
        val (z1, o1) = ChartMath.zoomAnchored(total, 1f, offset, gestureZoom = 2f, anchorFrac = frac)
        val after = anchorIndexOf(total, z1, o1, frac)
        assertEquals("捏合放大后，锚点那根 K 线不能跑位", before, after)
    }

    @Test
    fun zoomNearRightEdgePinsToTheLatestBar() {
        // 贴着最新一根放大：右边没有数据，只能把最新一根留在右边界
        val total = 500
        val (z1, o1) = ChartMath.zoomAnchored(total, 1f, 0f, gestureZoom = 2f, anchorFrac = 0.5f)
        assertEquals(499, ChartMath.window(total, z1, o1).last)
    }

    @Test
    fun anchorHoldsForZoomOutToo() {
        val total = 500
        val frac = 0.25f
        val (z0, o0) = ChartMath.zoomAnchored(total, 4f, 100f, gestureZoom = 1f, anchorFrac = frac)
        val before = anchorIndexOf(total, z0, o0, frac)
        val (z1, o1) = ChartMath.zoomAnchored(total, z0, o0, gestureZoom = 0.5f, anchorFrac = frac)
        val after = anchorIndexOf(total, z1, o1, frac)
        assertEquals("捏合缩小时锚点同样不能跑位", before, after)
    }

    @Test
    fun zoomIsClampedToUpperBound() {
        val (z, _) = ChartMath.zoomAnchored(500, 4f, 0f, gestureZoom = 100f, anchorFrac = 0.5f)
        assertEquals(ChartMath.MAX_ZOOM, z, 1e-6f)
    }

    @Test
    fun zoomIsClampedToLowerBound() {
        val (z, _) = ChartMath.zoomAnchored(500, 1f, 0f, gestureZoom = 1e-6f, anchorFrac = 0.5f)
        assertEquals(ChartMath.MIN_ZOOM, z, 1e-6f)
    }

    @Test
    fun zoomAtRightEdgeKeepsLatestBar() {
        val (z, o) = ChartMath.zoomAnchored(500, 1f, 0f, gestureZoom = 2f, anchorFrac = 1f)
        val w = ChartMath.window(500, z, o)
        assertEquals("在最右端缩放后仍应停在最新一根", 499, w.last)
    }

    @Test
    fun zoomAtLeftEdgeKeepsOldestBar() {
        // 先滚到最老的一端
        val off = ChartMath.maxOffset(500, 1f).toFloat()
        val (z, o) = ChartMath.zoomAnchored(500, 1f, off, gestureZoom = 0.5f, anchorFrac = 0f)
        val w = ChartMath.window(500, z, o)
        assertEquals("在最左端缩放后仍应停在最老一根", 0, w.first)
    }

    @Test
    fun zoomAnchoredOnEmptySeriesIsSafe() {
        val (z, o) = ChartMath.zoomAnchored(0, 1f, 0f, 2f, 0.5f)
        assertEquals(1f, z, 1e-6f)
        assertEquals(0f, o, 1e-6f)
    }

    // ---------------------------------------------------------------- 实体几何

    @Test
    fun bodyTopIsDrivenByTheHigherPrice() {
        // 价格越高 y 越小，所以实体上沿永远取两根价里较高的那根
        val h = 400f
        assertEquals(0f, ChartMath.bodyTop(1900.0, 2100.0, 1900.0, 2100.0, h), 1e-3f)
        // 阴线（开高收低）结果相同
        assertEquals(0f, ChartMath.bodyTop(2100.0, 1900.0, 1900.0, 2100.0, h), 1e-3f)
    }

    @Test
    fun bodyTopPlusHeightReachesTheLowerPrice() {
        val h = 400f
        val top = ChartMath.bodyTop(2000.0, 1950.0, 1900.0, 2100.0, h)
        val bh = ChartMath.bodyHeight(2000.0, 1950.0, 1900.0, 2100.0, h)
        assertEquals(ChartMath.yAt(1950.0, 1900.0, 2100.0, h), top + bh, 1e-3f)
    }

    @Test
    fun dojiStillHasAOnePixelBody() {
        // 开收相等时实体高度不能归零，否则十字星整根消失
        assertEquals(1f, ChartMath.bodyHeight(2000.0, 2000.0, 1900.0, 2100.0, 400f), 1e-6f)
    }

    @Test
    fun bodyHeightScalesWithPriceDifference() {
        // 区间 200 点映射到 400px（2px/点），10 点的实体 = 20px
        assertEquals(20f, ChartMath.bodyHeight(2000.0, 1990.0, 1900.0, 2100.0, 400f), 1e-3f)
    }

    @Test
    fun subPixelBodyIsNotSwallowed() {
        // 大价差区间里的小实体：几何高度不足 1px 时仍要留 1px，
        // 否则「有实体」会被价格轴的比例吃掉。
        assertEquals(1f, ChartMath.bodyHeight(2000.000, 2000.001, 1000.0, 3000.0, 400f), 1e-6f)
    }

    // ---------------------------------------------------------------- 主图 / 副图分配

    @Test
    fun noSubPanesGivesEverythingToThePriceChart() {
        val (price, pane) = ChartMath.splitPaneHeights(1000f, 0, 300f, 60f, 90f)
        assertEquals(1000f, price, 1e-3f)
        assertEquals(0f, pane, 1e-3f)
    }

    @Test
    fun paneHeightsAreClampedAndNothingIsLost() {
        val (price, pane) = ChartMath.splitPaneHeights(1000f, 2, 300f, 60f, 90f)
        // 理想高度 170 被上限 90 夹住
        assertEquals(90f, pane, 1e-3f)
        // 关键不变量：主图 + 全部副图 = 可用高度，像素不能被吞
        assertEquals(1000f, price + pane * 2, 1e-3f)
        assertTrue(price >= 300f)
    }

    @Test
    fun subPanesFallBackToMinimumBeforeGivingUp() {
        // 理想高度会让主图跌破 300 → 先退到副图最小高度
        val (price, pane) = ChartMath.splitPaneHeights(430f, 2, 300f, 60f, 90f)
        assertEquals(60f, pane, 1e-3f)
        assertEquals(430f, price + pane * 2, 1e-3f)
        assertTrue("主图仍须高于下限", price >= 300f)
    }

    @Test
    fun subPanesAreDroppedRatherThanCrushingThePriceChart() {
        // 连副图最小高度都放不下：宁可不画副图
        val (price, pane) = ChartMath.splitPaneHeights(400f, 2, 300f, 60f, 90f)
        assertEquals(0f, pane, 1e-3f)
        assertEquals(400f, price, 1e-3f)
    }

    // ---------------------------------------------------------------- 序列取值范围

    @Test
    fun fixedRangeIsReturnedVerbatim() {
        // RSI 必须钉死 0..100，不能随窗口浮动，否则 60 和 65 看起来天差地别
        val r = ChartMath.seriesRange(listOf(12.0, 88.0), 0..1, fixed = 0.0 to 100.0)
        assertEquals(0.0 to 100.0, r)
    }

    @Test
    fun seriesRangeIgnoresInvalidValues() {
        val r = ChartMath.seriesRange(listOf(Double.NaN, 10.0, 20.0, Double.NaN), 0..3)
        assertEquals(9.0, r.first, 1e-9)
        assertEquals(21.0, r.second, 1e-9)
    }

    @Test
    fun allInvalidSeriesFallsBackToAPlaceholderRange() {
        // 指标还没预热完：必须给出一个可用区间，否则除零
        val r = ChartMath.seriesRange(listOf(Double.NaN, Double.NaN), 0..1)
        assertEquals(0.0 to 1.0, r)
    }

    @Test
    fun constantSeriesGetsANonZeroBand() {
        val r = ChartMath.seriesRange(listOf(5.0, 5.0), 0..1)
        assertTrue(r.second > r.first)
        assertEquals(5.0, (r.first + r.second) / 2.0, 1e-9)
    }

    @Test
    fun constantZeroSeriesAlsoGetsANonZeroBand() {
        val r = ChartMath.seriesRange(listOf(0.0, 0.0), 0..1)
        assertTrue(r.second > r.first)
    }

    @Test
    fun seriesRangeOnlyLooksInsideTheWindow() {
        // 窗口外的极值不能影响当前窗口的比例
        val r = ChartMath.seriesRange(listOf(1.0, 100.0, 2.0), 0..0)
        assertTrue("100 在窗口外，不该被算进来", r.second < 10.0)
    }

    // ------------------------------------------------------------ 绘图几何
    //
    // 下面这几条是**真机崩溃的回归测试**。
    // 实机日志（Xiaomi 24129RT7CC / Android 16 / GoldLab 0.4.0）：
    //   java.lang.IllegalArgumentException: maxHeight(-669) must be >= than minHeight(0)
    //   at androidx.compose.ui.text.TextPainterKt.textLayoutConstraints
    //   at com.fxsimulator.ui.chart.CandleChartKt...CandleChart(CandleChart.kt:229)
    //
    // 根因：绘制用的几何是从 Composable 里缓存的 canvasSize 算的，而 canvasSize 由
    // onSizeChanged 在【布局之后】更新，绘制闭包却是【组合时】创建的，捕获的是上一帧的
    // 尺寸。图表突然变矮时（结算面板把图表挤小），就拿旧的 chartH 算坐标，
    // 于是 drawText 收到负的 maxHeight。
    //
    // 修法：几何只由「当帧尺寸」这一个输入决定（ChartMath.plotGeometry，纯函数），
    // 尺寸不够时返回 null，调用方直接放弃这一帧。

    private fun geo(w: Float, h: Float, panes: Int = 0) = ChartMath.plotGeometry(
        widthPx = w,
        heightPx = h,
        rightAxisPx = 58f,
        timeAxisPx = 20f,
        paneCount = panes,
        minPricePx = 120f,
        paneMinPx = 40f,
        paneMaxPx = 76f,
    )

    @Test
    fun plotGeometryRefusesToDrawOnACanvasTooSmallForItsAxes() {
        assertNull(geo(0f, 0f))
        assertNull(geo(360f, 0f))
        assertNull(geo(0f, 800f))
        assertNull(geo(360f, 10f))
        assertNull(geo(360f, 20f))
        assertNull(geo(30f, 800f))
        assertNull(geo(58f, 800f))
    }

    /**
     * 不变式：无论画布多大多小，只要 plotGeometry 返回了结果，
     * **绘图区底部 + 时间轴高度就绝不会超过画布高度**。
     *
     * 这正是崩溃的充要条件 —— 只要它不破，drawText 的 topLeft.y 就不可能超过
     * 画布高度，maxHeight 也就永远是正的。
     */
    @Test
    fun plotGeometryNeverPlacesContentOutsideTheCanvas() {
        val timeAxis = 20f
        var checked = 0
        for (h in 1..1200 step 7) {
            for (w in 1..900 step 13) {
                for (panes in 0..3) {
                    val g = geo(w.toFloat(), h.toFloat(), panes) ?: continue
                    checked++
                    assertTrue("areaWidth 必须为正 (w=$w)", g.areaWidth > 0f)
                    assertTrue("chartH 必须为正 (h=$h)", g.chartH > 0f)
                    assertTrue("paneH 不能为负", g.paneH >= 0f)
                    assertTrue(
                        "绘图区底部 " + g.plotBottom + " + 时间轴 " + timeAxis +
                            " 超过了画布高度 " + h + "（w=" + w + ", panes=" + panes + "）",
                        g.plotBottom + timeAxis <= h.toFloat() + 0.01f,
                    )
                }
            }
        }
        assertTrue("这个扫描应该覆盖到足够多的合法尺寸，实际只有 " + checked, checked > 1000)
    }

    @Test
    fun plotGeometryKeepsSubPanesInsideTheCanvas() {
        val g = geo(400f, 700f, panes = 3)!!
        assertEquals(3, g.paneCount)
        assertTrue(g.paneH > 0f)
        assertEquals(g.chartH + g.paneH * 3f, g.plotBottom, 0.001f)
    }

    @Test
    fun plotGeometryDropsSubPanesInsteadOfSqueezingThePriceChart() {
        // 高度只够主图时，应当一个副图都不画，而不是把主图压没
        val g = geo(400f, 200f, panes = 4)!!
        assertEquals(0, g.paneCount)
        assertEquals(0f, g.paneH, 0.001f)
        assertTrue("主图必须还留得住", g.chartH > 0f)
    }

    @Test
    fun plotGeometryGivesSubPanesRoomOnATallCanvas() {
        val g = geo(400f, 900f, panes = 2)!!
        assertEquals(2, g.paneCount)
        assertTrue("副图高度应落在配置的区间内", g.paneH >= 40f && g.paneH <= 76f)
        assertTrue("主图应至少拿到最小高度", g.chartH >= 120f)
    }
}
