package com.fxsimulator.ui.chart

import androidx.compose.ui.graphics.Color

/**
 * 序列的绘制风格。
 *
 * 注意 [HISTOGRAM] 的着色规则：柱体**按正负自动着色**（正用涨色、负用跌色，
 * 基准由 [SubPaneSpec.histogramBase] 决定），因此 [SubPaneLine.color] 对柱状线不生效。
 * 这是 MACD 柱、成交量柱的通行画法，也省掉了一份配色配置。
 */
enum class SeriesStyle { LINE, HISTOGRAM, POINTS }

/**
 * 主图叠加线（画在 K 线之上，与价格共用同一个价格轴）。
 */
data class OverlaySeries(
    val label: String,
    val values: List<Double>,
    val color: Color,
    val style: SeriesStyle = SeriesStyle.LINE,
    val digits: Int = 2,
    val width: Float = 1.4f,
)

/**
 * 副图中的一条线。
 *
 * [values] 与 K 线列表**等长、按索引对齐**，无效值用 NaN 表示。
 * 渲染必须跨无效值断开 —— 连到 0 会让指标预热期画出一条从底部窜起的假线。
 */
data class SubPaneLine(
    val label: String,
    val values: List<Double>,
    val color: Color,
    val style: SeriesStyle = SeriesStyle.LINE,
)

/**
 * 一个副图（子窗格）。
 *
 * 一个副图可含多条线/柱（MACD = DIF + DEA + 柱；KDJ = K + D + J）。
 * 把它们放进**同一个窗格**是必须的：它们的量纲相同、共用一根 Y 轴，
 * 拆成多个窗格反而无法比较。
 */
data class SubPaneSpec(
    val label: String,
    val lines: List<SubPaneLine>,
    /** 固定取值区间（如 RSI 恒为 0..100）；null = 由各条线合并自适应 */
    val fixedRange: Pair<Double, Double>? = null,
    /** 参考线（如 RSI 的 30 / 70） */
    val guides: List<Double> = emptyList(),
    val digits: Int = 2,
) {
    /** 含柱状线时，基准线必须被包含进值域，否则柱子的零轴跑到窗外 */
    val histogramBase: Double get() = 0.0

    val hasHistogram: Boolean get() = lines.any { it.style == SeriesStyle.HISTOGRAM }
}
