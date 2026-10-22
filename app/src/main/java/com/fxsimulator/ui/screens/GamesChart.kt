package com.fxsimulator.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.ui.chart.CandleChart
import com.fxsimulator.ui.theme.GoldLabColors

/**
 * 玩法页的 K 线图。
 *
 * ## 为什么必须有图
 * 方案第三条结论：玩家的利润来源是「报价偏离真实概率」，而判断偏离要**看 K 线结构**。
 * 只给一个当前价，等于把玩法变成纯猜大小 —— 那就只剩负期望。
 *
 * ## 两个刻意的限制
 * 1. **只画最近 [maxBars] 根**：玩法时钟每 350ms 推进一根，全月四万根每帧重绘会掉帧；
 * 2. **不叠加指标、不画持仓线**：玩法不考核技术指标，图要的是"看得懂形状"，
 *    把均线/布林都堆上去反而看不清。
 */
@Composable
internal fun GamesChart(
    bars: List<Candle>,
    digits: Int,
    modifier: Modifier = Modifier,
    maxBars: Int = 240,
) {
    val shown = if (bars.size > maxBars) bars.subList(bars.size - maxBars, bars.size) else bars
    Box(modifier) {
        if (shown.isEmpty()) {
            Text(
                "行情加载中…",
                color = GoldLabColors.TextDisabled,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        } else {
            CandleChart(
                bars = shown,
                digits = digits,
                formingIndex = shown.lastIndex,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 图上应该给多少根才够判断？
 *
 * 经验值：**周期长度的 20 倍**，夹在 240..720 之间。
 * - 5 分钟周期 → 100 根不够看趋势，取 240；
 * - 1 小时周期 → 1200 根太密，取 720（12 小时视野）；
 * 上限 720 是因为每帧重绘的代价随根数线性上升，再密就掉帧了。
 */
internal fun chartBarsFor(cycleMinutes: Int): Int =
    (cycleMinutes * 20).coerceIn(240, 720)