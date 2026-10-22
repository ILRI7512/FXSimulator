package com.fxsimulator.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import com.fxsimulator.ui.theme.GoldLabColors
import kotlin.math.abs

/**
 * 副图的值域：各条线合并；含柱状线时把零轴并进来（否则柱子越长越出界）。
 *
 * 抽成函数是必须的：[drawSubPane] 与 CandleChart 里十字光标右侧的读数
 * **必须用同一份范围**，否则读数标签和画面会对不上。
 */
internal fun subPaneRange(spec: SubPaneSpec, view: IntRange): Pair<Double, Double> {
    if (spec.fixedRange != null) return spec.fixedRange
    val ranges = spec.lines.map { ChartMath.seriesRange(it.values, view) }
    return ChartMath.mergeRanges(
        if (spec.hasHistogram) ranges + listOf(spec.histogramBase to spec.histogramBase) else ranges,
    )
}

/**
 * 绘制一个副图窗格（可含多条线 / 柱）。
 *
 * 与主图共用同一套 [ChartMath] 坐标换算，因此**缩放 / 拖动天然同步**——
 * 副图不需要自己的视图状态，否则两边一定会错位。
 *
 * @param readoutIndex 十字光标指向的全局索引；非 null 时读数显示该根的值而非最末值
 */
internal fun DrawScope.drawSubPane(
    spec: SubPaneSpec,
    view: IntRange,
    viewCount: Int,
    areaWidth: Float,
    top: Float,
    height: Float,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    gridColor: Color,
    readoutIndex: Int?,
) {
    if (viewCount <= 0 || height <= 0f || spec.lines.isEmpty()) return

    val (minV, maxV) = subPaneRange(spec, view)

    fun yv(v: Double): Float = top + ChartMath.yAt(v, minV, maxV, height)
    val bottom = top + height
    val slot = ChartMath.slotWidth(areaWidth, viewCount)
    val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))

    // 与主图（或上一个副图）的分隔线
    drawLine(gridColor, Offset(0f, top), Offset(areaWidth, top), 1f)

    // 参考线
    for (g in spec.guides) {
        if (g < minV || g > maxV) continue
        val gy = yv(g)
        drawLine(gridColor, Offset(0f, gy), Offset(areaWidth, gy), 1f, pathEffect = dash)
    }
    // 柱状线的零轴：画得比参考线清楚一点，MACD 全靠它读
    if (spec.hasHistogram && spec.histogramBase in minV..maxV) {
        val zy = yv(spec.histogramBase)
        drawLine(gridColor.copy(alpha = 0.95f), Offset(0f, zy), Offset(areaWidth, zy), 1.2f)
    }

    val firstIdx = view.first
    val lastIdx = view.last

    // ---- 先画柱，再画线：柱是背景，线不能被柱盖住
    for (line in spec.lines.filter { it.style == SeriesStyle.HISTOGRAM }) {
        val barW = (slot * 0.6f).coerceAtLeast(1f)
        val baseY = yv(spec.histogramBase)
        for (g in firstIdx..lastIdx) {
            val v = line.values.getOrNull(g) ?: continue
            if (!v.isFinite()) continue
            val xx = ChartMath.xAt(g - firstIdx, areaWidth, viewCount)
            val vy = yv(v)
            val barTop = minOf(baseY, vy)
            val barH = abs(vy - baseY).coerceAtLeast(1f)
            // 柱体按正负着色（MACD / 成交量的通行画法）
            val c = if (v >= spec.histogramBase) GoldLabColors.Up else GoldLabColors.Down
            drawRect(c.copy(alpha = 0.75f), Offset(xx - barW / 2f, barTop), Size(barW, barH))
        }
    }

    // ---- 线：切成连续有效段，段间**断开**（连到 0 会画出从底部窜起的假线）
    for (line in spec.lines.filter { it.style != SeriesStyle.HISTOGRAM }) {
        val vals = line.values
        val cap = lastIdx.coerceAtMost(vals.lastIndex)
        var i = firstIdx
        while (i <= cap) {
            if (vals.getOrNull(i)?.isFinite() != true) { i++; continue }
            val runStart = i
            while (i <= cap && vals.getOrNull(i)?.isFinite() == true) i++
            val runEnd = i - 1

            if (runEnd > runStart && line.style == SeriesStyle.LINE) {
                val fill = Path()
                fill.moveTo(ChartMath.xAt(runStart - firstIdx, areaWidth, viewCount), bottom)
                for (g in runStart..runEnd) {
                    fill.lineTo(ChartMath.xAt(g - firstIdx, areaWidth, viewCount), yv(vals[g]))
                }
                fill.lineTo(ChartMath.xAt(runEnd - firstIdx, areaWidth, viewCount), bottom)
                fill.close()
                drawPath(fill, line.color.copy(alpha = 0.14f))
            }

            if (runEnd > runStart) {
                val stroke = Path()
                for (g in runStart..runEnd) {
                    val xx = ChartMath.xAt(g - firstIdx, areaWidth, viewCount)
                    val yy = yv(vals[g])
                    if (g == runStart) stroke.moveTo(xx, yy) else stroke.lineTo(xx, yy)
                }
                drawPath(stroke, line.color, style = Stroke(width = 1.3f))
            } else {
                // 孤立单点（POINTS 风格也一样）：画 2px 方块，否则整段消失
                drawRect(
                    line.color,
                    Offset(ChartMath.xAt(runStart - firstIdx, areaWidth, viewCount) - 1f, yv(vals[runStart]) - 1f),
                    Size(2f, 2f),
                )
            }
        }
    }

    // ---- 左上角读数：有多条线时全部列出
    // 光标未激活 → 显示窗口内最后一个有效值；激活 → 显示光标那一根
    val readoutIdx = if (readoutIndex != null && readoutIndex in firstIdx..lastIdx) readoutIndex else null
    val parts = spec.lines.map { line ->
        val v: Double? = if (readoutIdx != null) {
            line.values.getOrNull(readoutIdx)?.takeIf { it.isFinite() }
        } else {
            var k = lastIdx.coerceAtMost(line.values.lastIndex)
            var found: Double? = null
            while (k >= firstIdx && k >= 0) {
                val x = line.values.getOrNull(k)
                if (x != null && x.isFinite()) { found = x; break }
                k--
            }
            found
        }
        v?.let { String.format("%,." + spec.digits + "f", it) } ?: "—"
    }
    val text = spec.label + "  " + parts.joinToString("  ")
    val chipStyle = labelStyle.copy(color = spec.lines.first().color)
    val measured = measurer.measure(text, chipStyle)
    drawText(
        measurer, text,
        topLeft = Offset(4f, clampTextY(top + 3f, measured.size.height)),
        style = chipStyle,
    )

    // ---- 光标落在本窗格内：在第一根线上画横向虚线，指明读的是哪里的值
    if (readoutIdx != null) {
        val v0 = spec.lines.first().values.getOrNull(readoutIdx)?.takeIf { it.isFinite() }
        if (v0 != null) {
            val cy = yv(v0)
            drawLine(
                spec.lines.first().color.copy(alpha = 0.7f),
                Offset(0f, cy), Offset(areaWidth, cy), 1f, pathEffect = dash,
            )
        }
    }
}
