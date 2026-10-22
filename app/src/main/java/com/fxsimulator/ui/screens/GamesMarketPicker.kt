package com.fxsimulator.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily

/**
 * 玩法页的「品种 / 起始月份」选择器。
 *
 * ## 起始月份为什么这么设计
 * 可玩数据是连续 12 个月。玩家选某月作为起点后，feed 会从那个月开始往后加载，
 * 最多仍叠加 12 个月（不够就取到可用末尾）。
 *
 * 这样做的代价要说清：**从更晚的月份开始，可玩跨度就短于一年**。
 * 界面上会把实际跨度标出来，避免让人误以为永远有一年可播。
 */
@Composable
internal fun GamesMarketPicker(
    symbol: String,
    symbols: List<String>,
    availableMonths: List<String>,
    startMonth: String,
    effectiveMonths: Int,
    onSymbol: (String) -> Unit,
    onMonth: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    GoldPanel(title = "品种与起点", modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            symbols.forEach { s ->
                TextButton(onClick = { onSymbol(s) }) {
                    Text(
                        s,
                        color = if (s == symbol) GoldLabColors.Gold else GoldLabColors.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Text(
            "起始月份（向左滑可看更早）",
            color = GoldLabColors.TextDisabled, fontSize = 10.sp,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            availableMonths.forEach { m ->
                TextButton(onClick = { onMonth(m) }) {
                    Text(
                        m,
                        color = if (m == startMonth) GoldLabColors.Gold else GoldLabColors.TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = MonoFamily,
                    )
                }
            }
        }
        Text(
            "从 $startMonth 起可连续回放 $effectiveMonths 个月",
            color = GoldLabColors.TextSecondary, fontSize = 10.sp,
        )
    }
}
