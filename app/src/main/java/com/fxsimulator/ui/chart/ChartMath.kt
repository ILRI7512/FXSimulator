package com.fxsimulator.ui.chart

import com.fxsimulator.domain.model.Candle
import kotlin.math.roundToInt

/**
 * 图表几何计算（纯函数，全部可单元测试）。
 *
 * 把「可见窗口 / 价格范围 / 像素↔索引 / 像素↔价格 / 拖动平移 / 锚点缩放」
 * 从 Composable 里抽出来，UI 层只负责调用与绘制。
 * 这样 MT5 式的交互（十字光标、捏合缩放、拖动平移）就有一套可回归测试的数学底座。
 *
 * ## 坐标约定
 * - x 轴：绘图区左边缘为 0，右边缘为 [areaWidth]；每根 K 线占一个 slot。
 * - y 轴：顶部为 0，底部为 [chartH]；价格越高 y 越小（与屏幕坐标一致）。
 * - offsetBars：从最新一根向历史方向回看的根数。0 = 停在最新一根。
 * - zoom：越大可见根数越多（沿用 GoldLab 既有语义，不是「放大倍数」）。
 */
object ChartMath {

    /** 可见 K 线根数下限 */
    const val MIN_VISIBLE = 15

    /** zoom = 1 时可见的 K 线根数 */
    const val BASE_VISIBLE = 60f

    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 8f

    /** 价格轴上下留白比例 */
    const val PAD_RATIO = 0.08

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** zoom 越大，可见 K 线根数越多 */
    fun visibleCount(total: Int, zoom: Float): Int =
        (BASE_VISIBLE * zoom).toInt().coerceIn(MIN_VISIBLE, maxOf(MIN_VISIBLE, total))

    /** 允许的最大回看偏移（根） */
    fun maxOffset(total: Int, zoom: Float): Int =
        (total - visibleCount(total, zoom)).coerceAtLeast(0)

    fun clampOffset(offset: Float, total: Int, zoom: Float): Float =
        offset.coerceIn(0f, maxOffset(total, zoom).toFloat())

    /**
     * 当前可见窗口，闭区间 [start, end]。
     * end 恒为 total - 1 - offset（最新一根或回看后的那根）。
     */
    fun window(total: Int, zoom: Float, offsetBars: Float): IntRange {
        if (total <= 0) return IntRange.EMPTY
        val visible = visibleCount(total, zoom)
        val offset = offsetBars.toInt().coerceIn(0, maxOffset(total, zoom))
        val end = total - 1 - offset
        val start = (end - visible + 1).coerceAtLeast(0)
        return start..end
    }

    /** 窗口内应绘制的根数（等于 min(可见根数, 现有数据)） */
    fun count(range: IntRange): Int = if (range.isEmpty()) 0 else range.last - range.first + 1

    /**
     * 价格轴范围：把窗口内 K 线的高低点、可见 EMA 值、以及额外价位（持仓的开仓/止损/止盈）
     * 全部纳入，再按 [PAD_RATIO] 上下留白。返回值保证 max > min。
     */
    fun priceRange(
        bars: List<Candle>,
        view: IntRange,
        /** 叠加在主图上的指标序列，必须一并纳入值域，否则线会画到图外 */
        extraSeries: List<List<Double>> = emptyList(),
        levels: List<Double> = emptyList(),
    ): Pair<Double, Double> {
        if (bars.isEmpty() || view.isEmpty()) return 0.0 to 1.0
        val from = view.first.coerceAtLeast(0)
        val to = view.last.coerceAtMost(bars.lastIndex)
        if (from > to) return 0.0 to 1.0

        var minP = Double.MAX_VALUE
        var maxP = -Double.MAX_VALUE
        for (i in from..to) {
            val c = bars[i]
            if (c.low < minP) minP = c.low
            if (c.high > maxP) maxP = c.high
        }
        for (series in extraSeries) {
            for (i in from..to) {
                val v = series.getOrNull(i) ?: continue
                if (!v.isFinite()) continue
                if (v < minP) minP = v
                if (v > maxP) maxP = v
            }
        }
        for (v in levels) {
            if (!v.isFinite()) continue
            if (v < minP) minP = v
            if (v > maxP) maxP = v
        }
        if (!minP.isFinite() || !maxP.isFinite() || maxP < minP) return 0.0 to 1.0

        val span = maxP - minP
        // 整段完全水平：给一个对称的假范围，避免除零
        if (span < 1e-9) return (minP - 1.0) to (maxP + 1.0)
        val pad = span * PAD_RATIO
        return (minP - pad) to (maxP + pad)
    }

    /**
     * 合并多个取值范围（副图里多条线共用一根 Y 轴）。
     * 空列表或全无效时返回占位区间，保证调用方不会除零。
     */
    fun mergeRanges(ranges: List<Pair<Double, Double>>): Pair<Double, Double> {
        if (ranges.isEmpty()) return 0.0 to 1.0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var found = false
        for ((a, b) in ranges) {
            if (!a.isFinite() || !b.isFinite() || b <= a) continue
            found = true
            if (a < lo) lo = a
            if (b > hi) hi = b
        }
        return if (found && hi > lo) lo to hi else 0.0 to 1.0
    }

    // ------------------------------------------------------------ 副图布局

    /**
     * 在「主图 + N 个副图」之间分配绘图区高度。
     *
     * 副图理想高度取总高的 34% 均分，夹在 [paneMinPx, paneMaxPx]；
     * 主图必须保留 [minPricePx]，否则先压缩到副图最小高度，仍然不够就
     * 干脆返回 paneHeight = 0（**宁可不出副图，也不能把主图压没**）。
     *
     * @return (priceHeight, paneHeight)；paneHeight 为 0 表示不画副图
     */
    fun splitPaneHeights(
        available: Float,
        paneCount: Int,
        minPricePx: Float,
        paneMinPx: Float,
        paneMaxPx: Float,
    ): Pair<Float, Float> {
        val total = available.coerceAtLeast(0f)
        if (paneCount <= 0 || total <= 0f) return total to 0f

        val ideal = (total * 0.34f / paneCount).coerceIn(paneMinPx, paneMaxPx)
        if (total - ideal * paneCount >= minPricePx) return (total - ideal * paneCount) to ideal

        val fallbackPrice = total - paneMinPx * paneCount
        return if (fallbackPrice >= minPricePx) fallbackPrice to paneMinPx else total to 0f
    }

    /**
     * 一帧的绘图几何。
     *
     * 所有像素值都必须由**当帧真实尺寸**算出来，绝不能复用 Composable 里缓存的测量值。
     */
    data class PlotGeometry(
        /** 主图绘制区宽度（不含右侧价格轴） */
        val areaWidth: Float,
        /** 主图高度 */
        val chartH: Float,
        /** 每个副图窗格的高度；0 表示这一帧不画副图 */
        val paneH: Float,
        /** 实际画几个副图窗格 */
        val paneCount: Int,
        /** 主图 + 全部副图的底部 y */
        val plotBottom: Float,
    )

    /**
     * 由**当帧**画布尺寸算出绘图几何。
     *
     * @return null 表示画布太小（连价格轴与时间轴都放不下），调用方应当直接放弃这一帧的绘制。
     *
     * ## 为什么尺寸必须作为参数传进来
     *
     * 因为 `onSizeChanged` 是在**布局之后**才更新状态的，而 Canvas 的绘制闭包是在
     * **组合时**创建的 —— 闭包捕获的是**上一帧**的尺寸。图表突然变矮时
     * （例如「猜涨跌」里结算面板把图表挤小），就会拿旧的 chartH 去算坐标，
     * 最后 `drawText` 收到负的 `maxHeight = size.height - topLeft.y`，Compose 直接抛
     * `IllegalArgumentException: maxHeight(-669) must be >= than minHeight(0)`。
     *
     * **这个崩溃在真机上复现过**（Xiaomi 24129RT7CC / Android 16 / GoldLab 0.4.0）。
     *
     * 所以：几何一律由这个纯函数算 —— 它只依赖传入的尺寸，天然不可能用到过期值；
     * 而且它是纯函数，可以单测，见 ChartMathTest 的边界用例。
     */
    fun plotGeometry(
        widthPx: Float,
        heightPx: Float,
        rightAxisPx: Float,
        timeAxisPx: Float,
        paneCount: Int,
        minPricePx: Float,
        paneMinPx: Float,
        paneMaxPx: Float,
    ): PlotGeometry? {
        // 留 8px 余量：正好卡在边界上时各种标签仍会被裁掉一半，
        // 半截标签比什么都不画更难看，也更容易引出负数约束。
        if (widthPx <= rightAxisPx + 8f || heightPx <= timeAxisPx + 8f) return null
        val areaWidth = (widthPx - rightAxisPx).coerceAtLeast(1f)
        val drawableH = (heightPx - timeAxisPx).coerceAtLeast(1f)
        val (chartH, paneH) = splitPaneHeights(
            available = drawableH,
            paneCount = paneCount,
            minPricePx = minPricePx,
            paneMinPx = paneMinPx,
            paneMaxPx = paneMaxPx,
        )
        val active = if (paneH > 0f) paneCount else 0
        return PlotGeometry(
            areaWidth = areaWidth,
            chartH = chartH,
            paneH = paneH,
            paneCount = active,
            plotBottom = chartH + paneH * active,
        )
    }

    /**
     * 一条（可能含 NaN / 无效值）序列在窗口内的取值范围。
     *
     * @param fixed 给定则原样返回（如 RSI 恒为 0..100，不能随窗口浮动）
     */
    fun seriesRange(
        values: List<Double>,
        view: IntRange,
        fixed: Pair<Double, Double>? = null,
        padRatio: Double = 0.10,
    ): Pair<Double, Double> {
        if (fixed != null) return fixed
        if (values.isEmpty() || view.isEmpty()) return 0.0 to 1.0
        val from = view.first.coerceAtLeast(0)
        val to = view.last.coerceAtMost(values.lastIndex)
        if (from > to) return 0.0 to 1.0

        var found = false
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (i in from..to) {
            val v = values[i]
            if (!v.isFinite()) continue
            found = true
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        // 注意：**不能**用 minV.isFinite() 判断「有没有找到值」——
        // Double.MAX_VALUE 本身是 finite，哨兵值会让这个判断永远不成立，
        // 于是返回一个 ±MAX_VALUE 量级的垃圾区间。必须显式记标志位。
        // 窗口内一根有效值都没有（指标还没预热完）：给占位区间，别除零。
        if (!found) return 0.0 to 1.0

        val span = maxV - minV
        if (span < 1e-12) {
            // 恒定序列（例如完全震荡的市场里 ATR 不变）：给一个居中的窄带
            val half = if (kotlin.math.abs(maxV) > 1e-12) kotlin.math.abs(maxV) * 0.05 else 0.5
            return (minV - half) to (maxV + half)
        }
        val pad = span * padRatio
        return (minV - pad) to (maxV + pad)
    }

    // ------------------------------------------------------------ 坐标换算

    /** 每根 K 线占据的像素宽度 */
    fun slotWidth(areaWidth: Float, n: Int): Float =
        if (n <= 0) 0f else areaWidth / n

    /** 窗口内第 i 根 K 线的中心 x */
    fun xAt(i: Int, areaWidth: Float, n: Int): Float {
        if (n <= 0) return 0f
        val slot = areaWidth / n
        return i * slot + slot / 2f
    }

    /** 由 x 反推窗口内索引；越界夹到 [0, n-1] */
    fun indexAtX(x: Float, areaWidth: Float, n: Int): Int {
        if (n <= 0) return 0
        val slot = slotWidth(areaWidth, n)
        if (slot <= 0f) return 0
        return (x / slot).toInt().coerceIn(0, n - 1)
    }

    fun yAt(price: Double, minP: Double, maxP: Double, chartH: Float): Float {
        val span = maxP - minP
        if (span <= 0.0 || chartH <= 0f) return chartH / 2f
        return (chartH * (1.0 - (price - minP) / span)).toFloat()
    }

    /** 实体上沿的 y（open/close 中价格较高的那个） */
    fun bodyTop(open: Double, close: Double, minP: Double, maxP: Double, chartH: Float): Float =
        minOf(yAt(open, minP, maxP, chartH), yAt(close, minP, maxP, chartH))

    /**
     * 实体高度。至少 1px：否则开收相等的十字星会彻底消失，
     * 在高价差区间里连「这里有一根 K 线」都看不出来。
     */
    fun bodyHeight(open: Double, close: Double, minP: Double, maxP: Double, chartH: Float): Float {
        val h = kotlin.math.abs(yAt(open, minP, maxP, chartH) - yAt(close, minP, maxP, chartH))
        return h.coerceAtLeast(1f)
    }

    fun priceAtY(y: Float, minP: Double, maxP: Double, chartH: Float): Double {
        if (chartH <= 0f) return minP
        val frac = (1.0 - (y / chartH).toDouble()).coerceIn(0.0, 1.0)
        return minP + (maxP - minP) * frac
    }

    // ------------------------------------------------------------ 手势

    /**
     * 拖动平移：像素位移 → 根数偏移。
     * 约定：手指**向右**拖 = 回看更早的数据（offset 增大），与 MT5 一致。
     */
    fun panOffset(
        current: Float,
        panXPx: Float,
        areaWidth: Float,
        n: Int,
        total: Int,
        zoom: Float,
    ): Float {
        val slot = slotWidth(areaWidth, n)
        if (slot <= 0f) return clampOffset(current, total, zoom)
        return clampOffset(current + panXPx / slot, total, zoom)
    }

    /**
     * 以捏合锚点为不动点缩放：缩放后，原本位于 [anchorFrac] 处的那根 K 线
     * 仍停在原来的横向比例位置（不会整屏跳走）。
     *
     * @param anchorFrac 捏合中心在绘图区宽度上的比例，0 = 最左，1 = 最右
     * @return (newZoom, newOffsetBars)
     */
    fun zoomAnchored(
        total: Int,
        zoom: Float,
        offsetBars: Float,
        gestureZoom: Float,
        anchorFrac: Float,
    ): Pair<Float, Float> {
        if (total <= 0) return clampZoom(zoom) to 0f
        val z0 = clampZoom(zoom)
        val z1 = clampZoom(z0 * gestureZoom)
        val vis0 = visibleCount(total, z0)
        val vis1 = visibleCount(total, z1)
        val f = anchorFrac.coerceIn(0f, 1f)

        val off0 = offsetBars.toInt().coerceIn(0, maxOffset(total, z0))
        val end0 = total - 1 - off0
        val start0 = (end0 - vis0 + 1).coerceAtLeast(0)

        // 锚点在「K 线坐标」上的连续位置（浮点，避免半根误差累积）。
        // 用浮点而不是 toInt() 截断很重要：可见根数 60→120 这种偶数倍缩放，
        // f*(n-1) 会落在 .5 上，截断会让锚点整根跳位。
        val anchor = start0 + f * (vis0 - 1)
        // 让该坐标在缩放后仍落在同一比例位置
        val start1 = (anchor - f * (vis1 - 1)).roundToInt()
        val end1 = (start1 + vis1 - 1).coerceAtMost(total - 1)
        val off1 = clampOffset((total - 1 - end1).toFloat(), total, z1)
        return z1 to off1
    }
}
