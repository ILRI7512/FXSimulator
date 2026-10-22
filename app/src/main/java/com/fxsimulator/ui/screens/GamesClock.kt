package com.fxsimulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily

/**
 * 玩法页的历史回放控制条（暂停 / 倍速 / 跳到结算）。
 *
 * ## 为什么需要它
 * 玩法要靠"等行情走完"才能结算：5 分钟周期在 1× 下要等 300 根 × 350ms ≈ 105 秒。
 * 玩家想连开几局时会一直干等，体验很差。所以给三件事：
 * - **暂停**：看清 K 线再决定下注（回放不动，报价也不动，不会被时间推着走）；
 * - **倍速**：1×/2×/4×/8×，本质是缩小每根的间隔；
 * - **跳到结算**：直接推进到最近一个持仓的到期根数，立即结算。
 *
 * 倍速只改**播放速度**，不改结算规则、不改报价公式 —— 结算价永远按窗口内真实 K 线算。
 */
@Composable
internal fun GamesClockBar(
    playing: Boolean,
    speed: Int,
    canSkipToSettle: Boolean,
    onTogglePlay: () -> Unit,
    onSpeed: (Int) -> Unit,
    onSkipToSettle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GoldPanel(title = "回放控制", modifier = modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onTogglePlay) {
                Text(
                    if (playing) "⏸ 暂停" else "▶ 继续",
                    color = if (playing) GoldLabColors.Down else GoldLabColors.Up,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("速度", color = GoldLabColors.TextDisabled, fontSize = 10.sp,
                    modifier = Modifier.padding(end = 2.dp))
                listOf(1, 2, 4, 8).forEach { s ->
                    TextButton(onClick = { onSpeed(s) }) {
                        Text(
                            "${s}×",
                            color = if (s == speed) GoldLabColors.Gold else GoldLabColors.TextSecondary,
                            fontSize = 11.sp, fontFamily = MonoFamily,
                        )
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onSkipToSettle,
            enabled = canSkipToSettle,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (canSkipToSettle) "⏩ 跳到结算（推进到最近到期）" else "⏩ 跳到结算（当前无持仓）",
                color = if (canSkipToSettle) GoldLabColors.Gold else GoldLabColors.TextDisabled,
                fontSize = 11.sp,
            )
        }
    }
}
