package com.fxsimulator.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.fxsimulator.domain.model.CurvePoint
import com.fxsimulator.ui.theme.GoldLabColors

/**
 * 净值曲线。数据全部来自真实模拟成交记录（由 PerformanceAnalyzer 计算）。
 */
@Composable
fun EquityCurve(
    points: List<CurvePoint>,
    initialBalance: Double,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(GoldLabColors.Surface),
    ) {
        if (points.size < 2) {
            drawContext.canvas.nativeCanvas.drawColor(0x00000000)
            return@Canvas
        }
        val minV = points.minOf { it.equity }
        val maxV = points.maxOf { it.equity }
        val span = (maxV - minV).coerceAtLeast(1e-6)
        val padV = span * 0.1
        val lo = minV - padV
        val hi = maxV + padV

        fun x(i: Int) = size.width * i / (points.size - 1).toFloat()
        fun y(v: Double) = (size.height * (1.0 - (v - lo) / (hi - lo))).toFloat()

        // 初始资金基准线
        val baseY = y(initialBalance)
        drawLine(
            GoldLabColors.TextDisabled,
            Offset(0f, baseY),
            Offset(size.width, baseY),
            1f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )

        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(i), y(p.equity)) else path.lineTo(x(i), y(p.equity))
        }
        drawPath(path, GoldLabColors.Gold, style = Stroke(width = 2f))

        val fill = Path().apply {
            addPath(path)
            lineTo(x(points.size - 1), size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, GoldLabColors.Gold.copy(alpha = 0.12f))
    }
}
