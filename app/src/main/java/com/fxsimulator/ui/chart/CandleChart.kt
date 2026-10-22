package com.fxsimulator.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.TradeRecord
import com.fxsimulator.ui.format.Formatters
import com.fxsimulator.ui.theme.GoldLabColors

/** 右侧价格轴宽度 */
private val RIGHT_AXIS_WIDTH = 58.dp

/** 底部时间轴高度 */
private val TIME_AXIS_HEIGHT = 20.dp

/** 长按拖出的十字光标颜色 */
private val CrosshairColor = Color(0xFFB2B5BE)

/**
 * 轻量自绘 K 线图（零第三方图表库依赖）。
 *
 * ## 绘制
 * 蜡烛图（阳线中空、阴线实心）、未完成 K 线用虚线描边区分、价格轴、时间轴、
 * 当前价虚线 + 右侧价格标签、EMA20/50 叠加。
 *
 * ## MT5 式交互
 * - **捏合缩放**：以捏合中心为锚点，锚点那根 K 线缩放后停在原位（不整屏跳走）
 * - **左右拖动平移**：手指向右拖 = 回看更早的数据
 * - **长按拖出十字光标**：显示十字虚线 + 右侧价格标签 + 底部时间标签，
 *   并通过 [onCrosshairChange] 把光标所在 K 线抛给上层显示 OHLC 读数。
 *   **长按期间视图冻结**：回放继续跑也不会把光标下的 K 线冲走，松手后恢复跟随。
 * - **持仓价位线**：开仓价（带方向、手数与浮动盈亏标签）、止损、止盈
 *
 * ## 关于「未完成 K 线」
 * 回放中最后一根代表**正在形成**的 K 线，用虚线边框 + 半透明实体绘制。
 *
 * @param onCrosshairChange 十字光标指向的 K 线；没有光标时为 null
 * @param profitOf 计算单笔持仓的浮动盈亏，用于开仓价线上的标签
 */
@Composable
fun CandleChart(
    bars: List<Candle>,
    digits: Int,
    modifier: Modifier = Modifier,
    /** 主图叠加线（MA / BOLL / SAR 等），由 IndicatorMapper 从用户配置生成 */
    overlays: List<OverlaySeries> = emptyList(),
    positions: List<TradeRecord> = emptyList(),
    showSlTp: Boolean = true,
    /** 正在形成的 K 线索引（通常是 bars.lastIndex）；-1 表示没有 */
    formingIndex: Int = -1,
    /**
     * 指标副图。每个元素在主图下方占一条独立窗格，**与主图共用同一套坐标换算**，
     * 因此缩放 / 拖动天然同步；由 [ChartMath.splitPaneHeights] 决定主图与副图的高度分配。
     */
    subPanes: List<SubPaneSpec> = emptyList(),
    /**
     * 阳线是否画成空心中空（MT5 默认样式）。
     * false（默认）= 阳线实心，与阴线一样有实体，可读性更好。
     */
    hollowUp: Boolean = false,
    /** 单笔持仓的浮动盈亏（用于开仓价线标签） */
    profitOf: (TradeRecord) -> Double = { 0.0 },
    /** 十字光标指向的 K 线，移出时为 null */
    onCrosshairChange: (Candle?) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 视图状态：zoom 越大可见 K 线越多；offsetBars 表示向历史方向回看的根数
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetBars by remember { mutableFloatStateOf(0f) }
    var crosshair by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    /** 长按查看时冻结的总根数；null 表示跟随最新行情 */
    var frozenTotal by remember { mutableStateOf<Int?>(null) }

    val rightAxisPx = with(density) { RIGHT_AXIS_WIDTH.toPx() }
    // 手势协程只创建一次（pointerInput(Unit)），必须用 rememberUpdatedState 读到最新数据，
    // 否则会把首次组合时的空 bars 永久捕获，导致缩放/平移完全失效。
    val barsState = rememberUpdatedState(bars)

    // 冻结只在长按期间生效，且不能超过实际数据量（导入新数据后可能变小）
    val total = (frozenTotal ?: bars.size).coerceAtMost(bars.size)

    val view = ChartMath.window(total, zoom, offsetBars)
    val viewCount = ChartMath.count(view)

    // 这里只保留【手势与十字光标】需要的宽度 —— 它必须来自 canvasSize，
    // 因为 pointerInput 的闭包里拿不到绘制尺寸。
    //
    // 绘制用的几何（主图高度、副图高度、绘图区底部）**不在这里算**：
    // 见下面 Canvas 里对 ChartMath.plotGeometry 的调用。
    // 用这里的缓存值去算坐标，会让 drawText 收到负的 maxHeight 并崩溃（真机复现过）。
    val areaWidth = (canvasSize.width - rightAxisPx).coerceAtLeast(1f)

    // 需要纳入价格轴的额外价位：持仓的开仓价 / 止损 / 止盈
    val levels: List<Double> = if (!showSlTp) emptyList()
    else positions.flatMap { listOfNotNull(it.openPrice, it.stopLoss, it.takeProfit) }

    val (minP, maxP) = ChartMath.priceRange(
        bars = bars,
        view = view,
        extraSeries = overlays.map { it.values },
        levels = levels,
    )

    // 十字光标指向的 K 线（也用于上层 OHLC 读数）
    val crosshairBar: Candle? = crosshair?.let { pos ->
        if (viewCount <= 0) null
        else {
            val i = ChartMath.indexAtX(pos.x.coerceIn(0f, areaWidth), areaWidth, viewCount)
            bars.getOrNull(view.first + i)
        }
    }
    LaunchedEffect(crosshairBar) { onCrosshairChange(crosshairBar) }

    Box(
        modifier = modifier
            .background(GoldLabColors.Background)
            // 先挂平移/缩放手势（外层），再挂长按手势（内层）。
            // 内层的 Main 阶段先收到事件，因此「长按后拖动 = 十字光标」优先于平移。
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val list = barsState.value
                    if (list.size < 5) return@detectTransformGestures
                    val w = (size.width - rightAxisPx).coerceAtLeast(1f)
                    if (gestureZoom != 1f && gestureZoom > 0f) {
                        val anchor = (centroid.x / w).coerceIn(0f, 1f)
                        val (z1, o1) = ChartMath.zoomAnchored(
                            total = list.size,
                            zoom = zoom,
                            offsetBars = offsetBars,
                            gestureZoom = gestureZoom,
                            anchorFrac = anchor,
                        )
                        zoom = z1
                        offsetBars = o1
                    }
                    if (pan.x != 0f) {
                        val n = ChartMath.visibleCount(list.size, zoom)
                        offsetBars = ChartMath.panOffset(
                            current = offsetBars,
                            panXPx = pan.x,
                            areaWidth = w,
                            n = n,
                            total = list.size,
                            zoom = zoom,
                        )
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        // 冻结视图：长按查看时不让回放把光标下的 K 线冲走
                        frozenTotal = barsState.value.size
                        crosshair = start
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        crosshair = change.position
                    },
                    onDragEnd = { crosshair = null; frozenTotal = null },
                    onDragCancel = { crosshair = null; frozenTotal = null },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
            if (viewCount < 2) return@Canvas

            // ---------------------------------------------------------- 几何
            // 全部由【当帧真实尺寸】算，绝不用 Composable 里缓存的 canvasSize。
            // 原因见 ChartMath.plotGeometry 的注释 —— 真机就是在这里崩的。
            val geo = ChartMath.plotGeometry(
                widthPx = size.width,
                heightPx = size.height,
                rightAxisPx = RIGHT_AXIS_WIDTH.toPx(),
                timeAxisPx = TIME_AXIS_HEIGHT.toPx(),
                paneCount = subPanes.size,
                minPricePx = 120.dp.toPx(),
                paneMinPx = 40.dp.toPx(),
                paneMaxPx = 76.dp.toPx(),
            ) ?: return@Canvas
            val liveAreaWidth = geo.areaWidth
            val liveChartH = geo.chartH
            val livePaneH = geo.paneH
            val livePanes = if (geo.paneCount > 0) subPanes else emptyList()
            val livePlotBottom = geo.plotBottom

            val labelStyle = TextStyle(
                color = GoldLabColors.TextSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
            val valueStyle = TextStyle(
                color = GoldLabColors.TextPrimary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )

            // ---------------------------------------------------------- 网格与价格轴
            val gridColor = GoldLabColors.Outline.copy(alpha = 0.55f)
            val gridLines = 5
            for (g in 0..gridLines) {
                val price = minP + (maxP - minP) * g / gridLines
                val yy = ChartMath.yAt(price, minP, maxP, liveChartH)
                drawLine(gridColor, Offset(0f, yy), Offset(liveAreaWidth, yy), 1f)
                val label = String.format("%,." + digits + "f", price)
                val tl = textMeasurer.measure(label, labelStyle)
                drawText(
                    textMeasurer, label,
                    topLeft = Offset(
                        liveAreaWidth + 4.dp.toPx(),
                        clampTextY(yy - tl.size.height / 2f, tl.size.height),
                    ),
                    style = labelStyle,
                )
            }

            // ---------------------------------------------------------- 时间轴
            val timeTicks = 4
            for (t in 0..timeTicks) {
                val idx = (viewCount - 1) * t / timeTicks
                val c = bars[view.first + idx]
                val xx = ChartMath.xAt(idx, liveAreaWidth, viewCount)
                // 时间轴的竖线贯穿主图与全部副图，方便对齐读数
                drawLine(gridColor, Offset(xx, 0f), Offset(xx, livePlotBottom), 1f)
                val label = Formatters.time(c.timeMillis)
                val tl = textMeasurer.measure(label, labelStyle)
                drawText(
                    textMeasurer, label,
                    topLeft = Offset(
                        (xx - tl.size.width / 2f).coerceIn(0f, (liveAreaWidth - tl.size.width).coerceAtLeast(0f)),
                        clampTextY(livePlotBottom + 4.dp.toPx(), tl.size.height),
                    ),
                    style = labelStyle,
                )
            }

            // ---------------------------------------------------------- 蜡烛
            val slot = ChartMath.slotWidth(liveAreaWidth, viewCount)
            val bodyW = (slot * 0.62f).coerceAtLeast(1.5f)
            for (i in 0 until viewCount) {
                val gi = view.first + i
                val c = bars.getOrNull(gi) ?: continue
                val up = c.close >= c.open
                val color = if (up) GoldLabColors.Up else GoldLabColors.Down
                val isForming = gi == bars.lastIndex && gi == formingIndex
                // 未完成 K 线只是「整体略淡 + 虚线边框」。
                // 注意：实体浓度必须与已收盘的完全一致 —— 之前未完成画 0.28、
                // 已收盘的阳线只画 0.18，于是阳线一收盘实体反而变淡，
                // 看上去就像「闭合后没有实体」。
                val alpha = if (isForming) 0.8f else 1f
                val cx = ChartMath.xAt(i, liveAreaWidth, viewCount)
                val yTop = ChartMath.bodyTop(c.open, c.close, minP, maxP, liveChartH)
                val bodyH = ChartMath.bodyHeight(c.open, c.close, minP, maxP, liveChartH)
                val left = cx - bodyW / 2f

                // 影线
                drawLine(
                    color.copy(alpha = alpha),
                    Offset(cx, ChartMath.yAt(c.high, minP, maxP, liveChartH)),
                    Offset(cx, ChartMath.yAt(c.low, minP, maxP, liveChartH)),
                    1.2f,
                )
                // 实体
                if (up && hollowUp) {
                    // 阳线中空：内部给一层明确可见的淡填充，让它看起来仍然是「有实体」
                    drawRect(color.copy(alpha = 0.30f * alpha), Offset(left, yTop), Size(bodyW, bodyH))
                    drawRect(
                        color.copy(alpha = alpha),
                        Offset(left, yTop), Size(bodyW, bodyH),
                        style = Stroke(width = 1.1f),
                    )
                } else {
                    drawRect(color.copy(alpha = alpha), Offset(left, yTop), Size(bodyW, bodyH))
                }
                // 未完成：虚线边框标记，实心/空心两种样式都适用
                if (isForming) {
                    drawRect(
                        color = color.copy(alpha = alpha),
                        topLeft = Offset(left, yTop),
                        size = Size(bodyW, bodyH),
                        style = Stroke(
                            width = 1.2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                        ),
                    )
                }
            }

            // ---------------------------------------------------------- 主图叠加指标
            for (o in overlays) {
                drawOverlay(o, view.first, viewCount, liveAreaWidth, minP, maxP, liveChartH)
            }

            // ---------------------------------------------------------- 持仓价位线
            if (showSlTp) {
                for (p in positions) {
                    // 开仓价：实线 + 左端标签（方向 / 手数 / 浮动盈亏）
                    val ey = ChartMath.yAt(p.openPrice, minP, maxP, liveChartH)
                    val pnl = profitOf(p)
                    val pnlColor = if (pnl >= 0.0) GoldLabColors.Up else GoldLabColors.Down
                    drawLine(
                        GoldLabColors.TextSecondary.copy(alpha = 0.75f),
                        Offset(0f, ey), Offset(liveAreaWidth, ey), 1f,
                    )
                    val tag = p.direction.label + " " + Formatters.lot(p.volume) + "  " +
                        Formatters.signedMoney(pnl)
                    drawLabelChip(textMeasurer, tag, pnlColor, 4.dp.toPx(), ey, liveAreaWidth)

                    p.stopLoss?.let { sl ->
                        drawLevelLine(
                            y = ChartMath.yAt(sl, minP, maxP, liveChartH), areaWidth = liveAreaWidth,
                            color = GoldLabColors.Down, digits = digits, price = sl,
                            tag = "SL", measurer = textMeasurer, style = labelStyle,
                        )
                    }
                    p.takeProfit?.let { tp ->
                        drawLevelLine(
                            y = ChartMath.yAt(tp, minP, maxP, liveChartH), areaWidth = liveAreaWidth,
                            color = GoldLabColors.Up, digits = digits, price = tp,
                            tag = "TP", measurer = textMeasurer, style = labelStyle,
                        )
                    }
                }
            }

            // ---------------------------------------------------------- 当前价
            val last = bars.getOrNull(view.last) ?: bars.last()
            val lastY = ChartMath.yAt(last.close, minP, maxP, liveChartH)
            val priceColor = if (last.close >= last.open) GoldLabColors.Up else GoldLabColors.Down
            drawLine(
                priceColor.copy(alpha = 0.85f),
                Offset(0f, lastY), Offset(liveAreaWidth, lastY), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
            )
            drawAxisChip(
                measurer = textMeasurer,
                text = String.format("%,." + digits + "f", last.close),
                bg = priceColor,
                left = liveAreaWidth + 2.dp.toPx(),
                width = size.width - liveAreaWidth - 2.dp.toPx(),
                centerY = lastY,
            )

            // ---------------------------------------------------------- 指标副图
            if (livePanes.isNotEmpty()) {
                val crosshairGlobalIndex = crosshair?.let { pos ->
                    view.first + ChartMath.indexAtX(pos.x.coerceIn(0f, liveAreaWidth), liveAreaWidth, viewCount)
                }
                for ((n, pane) in livePanes.withIndex()) {
                    drawSubPane(
                        spec = pane,
                        view = view,
                        viewCount = viewCount,
                        areaWidth = liveAreaWidth,
                        top = liveChartH + n * livePaneH,
                        height = livePaneH,
                        measurer = textMeasurer,
                        labelStyle = labelStyle,
                        gridColor = gridColor,
                        readoutIndex = crosshairGlobalIndex,
                    )
                }
            }

            // ---------------------------------------------------------- 十字光标（最上层）
            crosshair?.let { pos ->
                val cx = pos.x.coerceIn(0f, liveAreaWidth)
                val cy = pos.y.coerceIn(0f, livePlotBottom)
                val idx = ChartMath.indexAtX(cx, liveAreaWidth, viewCount)
                val snappedX = ChartMath.xAt(idx, liveAreaWidth, viewCount)
                val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))

                // 竖线贯穿主图 + 全部副图
                drawLine(
                    CrosshairColor.copy(alpha = 0.9f),
                    Offset(snappedX, 0f), Offset(snappedX, livePlotBottom), 1f,
                    pathEffect = dash,
                )

                // 横线只画在光标所在的那条窗格内：跨窗格横线没有意义，
                // 一条落在 ATR 副图上的横线不能拿主图的价格去解读。
                val band = when {
                    cy <= liveChartH -> 0
                    livePanes.isEmpty() -> 0
                    else -> (((cy - liveChartH) / livePaneH).toInt() + 1).coerceIn(1, livePanes.size)
                }
                val bandTop = if (band == 0) 0f else liveChartH + (band - 1) * livePaneH
                val bandBottom = if (band == 0) liveChartH else bandTop + livePaneH
                val cyInBand = cy.coerceIn(bandTop, bandBottom)
                drawLine(
                    CrosshairColor.copy(alpha = 0.9f),
                    Offset(0f, cyInBand), Offset(liveAreaWidth, cyInBand), 1f,
                    pathEffect = dash,
                )

                // 右侧标签：主图显示价格，副图显示该窗格自己的取值
                val chipText: String
                if (band == 0) {
                    chipText = String.format("%,." + digits + "f", ChartMath.priceAtY(cyInBand, minP, maxP, liveChartH))
                } else {
                    val pane = livePanes[band - 1]
                    val (pvMin, pvMax) = subPaneRange(pane, view)
                    val pv = ChartMath.priceAtY(cyInBand - bandTop, pvMin, pvMax, livePaneH)
                    chipText = String.format("%,." + pane.digits + "f", pv)
                }
                drawAxisChip(
                    measurer = textMeasurer,
                    text = chipText,
                    bg = GoldLabColors.SurfaceVariant,
                    textColor = GoldLabColors.TextPrimary,
                    left = liveAreaWidth + 2.dp.toPx(),
                    width = size.width - liveAreaWidth - 2.dp.toPx(),
                    centerY = cyInBand,
                )

                // 底部时间标签
                val c = bars.getOrNull(view.first + idx)
                if (c != null) {
                    val tl = Formatters.dateTime(c.timeMillis)
                    val measured = textMeasurer.measure(tl, valueStyle)
                    val chipW = measured.size.width + 8.dp.toPx()
                    val left = (snappedX - chipW / 2f).coerceIn(0f, (liveAreaWidth - chipW).coerceAtLeast(0f))
                    drawRect(
                        color = GoldLabColors.SurfaceVariant,
                        topLeft = Offset(left, livePlotBottom + 1.dp.toPx()),
                        size = Size(chipW, TIME_AXIS_HEIGHT.toPx() - 2.dp.toPx()),
                    )
                    drawText(
                        textMeasurer, tl,
                        topLeft = Offset(
                            left + 4.dp.toPx(),
                            clampTextY(livePlotBottom + 4.dp.toPx(), measured.size.height),
                        ),
                        style = valueStyle,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 绘制辅助

/**
 * 画一条主图叠加线。
 *
 * 无效值处**断开**（prev 置空），而不是连到 0 或跨过去直连 ——
 * 均线预热期跨断直连会画出一条并不存在的斜线。
 */
private fun DrawScope.drawOverlay(
    series: OverlaySeries,
    startIndex: Int,
    count: Int,
    areaWidth: Float,
    minP: Double,
    maxP: Double,
    chartH: Float,
) {
    val vals = series.values
    var prev: Offset? = null
    for (i in 0 until count) {
        val v = vals.getOrNull(startIndex + i)
        if (v == null || !v.isFinite()) { prev = null; continue }
        val p = Offset(
            ChartMath.xAt(i, areaWidth, count),
            ChartMath.yAt(v, minP, maxP, chartH),
        )
        when (series.style) {
            SeriesStyle.POINTS -> drawRect(series.color, Offset(p.x - 1.2f, p.y - 1.2f), Size(2.4f, 2.4f))
            else -> prev?.let { drawLine(series.color, it, p, series.width) }
        }
        prev = p
    }
}

/** 止损 / 止盈：细虚线 + 线上方左端标签 */
private fun DrawScope.drawLevelLine(
    y: Float,
    areaWidth: Float,
    color: Color,
    digits: Int,
    price: Double,
    tag: String,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    drawLine(
        color.copy(alpha = 0.8f),
        Offset(0f, y), Offset(areaWidth, y), 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)),
    )
    val text = tag + " " + String.format("%,." + digits + "f", price)
    val tl = measurer.measure(text, style.copy(color = color))
    drawText(
        measurer, text,
        topLeft = Offset(4f, clampTextY(y - tl.size.height - 1f, tl.size.height)),
        style = style.copy(color = color),
    )
}

/** 左端小标签（带底色），用于开仓价线 */
private fun DrawScope.drawLabelChip(
    measurer: TextMeasurer,
    text: String,
    color: Color,
    left: Float,
    centerY: Float,
    maxRight: Float,
) {
    val style = TextStyle(color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    val tl = measurer.measure(text, style)
    val w = (tl.size.width + 8.dp.toPx()).coerceAtMost((maxRight - left).coerceAtLeast(0f))
    val h = tl.size.height + 2f
    drawRect(
        color = GoldLabColors.SurfaceVariant.copy(alpha = 0.92f),
        topLeft = Offset(left, centerY - h / 2f),
        size = Size(w, h),
    )
    drawText(
        measurer, text,
        topLeft = Offset(left + 4.dp.toPx(), clampTextY(centerY - h / 2f + 1f, tl.size.height)),
        style = style,
    )
}

/** 右侧价格轴上的实心价格标签 */
private fun DrawScope.drawAxisChip(
    measurer: TextMeasurer,
    text: String,
    bg: Color,
    left: Float,
    width: Float,
    centerY: Float,
    textColor: Color = GoldLabColors.Background,
) {
    val style = TextStyle(color = textColor, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
    val tl = measurer.measure(text, style)
    val h = tl.size.height + 2f
    drawRect(bg, Offset(left, centerY - h / 2f), Size(width.coerceAtLeast(0f), h))
    drawText(
        measurer, text,
        topLeft = Offset(left + 2.dp.toPx(), clampTextY(centerY - h / 2f + 1f, tl.size.height)),
        style = style,
    )
}
