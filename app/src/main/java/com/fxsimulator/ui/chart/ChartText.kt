package com.fxsimulator.ui.chart

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 自绘图表里所有文字的**安全落笔 Y**。
 *
 * ## 为什么需要这个
 *
 * Compose 的 drawText 会根据 topLeft 与**当帧**画布尺寸反推布局约束：
 *
 *     maxHeight = size.height - topLeft.y
 *
 * 只要 topLeft.y 超过画布高度，这个值就是负的，Compose 会直接抛：
 *
 *     java.lang.IllegalArgumentException: maxHeight(-669) must be >= than minHeight(0)
 *
 * 这在真机上真实发生过（Xiaomi 24129RT7CC / Android 16 / GoldLab 0.4.0）：
 * 图表从「待作答」切到「已结算」时高度一次矮了 200 多 dp，
 * 而绘制闭包捕获的仍是上一帧的几何，于是价格轴标签的 Y 落到了画布外。
 *
 * 根因修复在 [ChartMath.plotGeometry] —— 几何只由当帧尺寸决定（见 CandleChart 的调用）。
 * 这里是**第二道防线**：把 Y 夹进画布内，以后新增任何一处文字都不必再想这件事。
 *
 * @param textHeightPx 该行文字实测高度，由调用方 measure 后传入，避免重复测量
 */
fun DrawScope.clampTextY(y: Float, textHeightPx: Int): Float =
    y.coerceAtMost(size.height - textHeightPx)
