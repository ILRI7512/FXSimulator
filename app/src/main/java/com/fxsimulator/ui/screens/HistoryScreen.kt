package com.fxsimulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsimulator.domain.model.OrderStatus
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.components.Pill
import com.fxsimulator.ui.format.Formatters
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameUiState
import com.fxsimulator.viewmodel.GameViewModel

/** 交易记录 */
@Composable
fun HistoryScreen(vm: GameViewModel, ui: GameUiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text("交易记录", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "共 " + ui.history.size + " 条 · 已实现盈亏合计 " + Formatters.signedMoney(ui.stats.netProfit) + " USD",
            color = GoldLabColors.TextSecondary, fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(8.dp))
        NoticeBar(
            "佣金与隔夜费都会计入净盈亏；广告奖励只增加余额，不计入任何交易记录与统计。",
            color = GoldLabColors.Info,
        )
        Spacer(Modifier.height(8.dp))

        if (ui.history.isEmpty()) {
            Text("暂无交易记录", color = GoldLabColors.TextSecondary, fontSize = 12.sp)
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(ui.history) { t ->
                val isRejected = t.status == OrderStatus.REJECTED
                val pnlColor: Color = when {
                    isRejected -> GoldLabColors.Warning
                    t.netProfit > 0 -> GoldLabColors.Up
                    t.netProfit < 0 -> GoldLabColors.Down
                    else -> GoldLabColors.TextPrimary
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row {
                            Text(
                                t.direction.label,
                                color = if (t.direction.isBuy) GoldLabColors.Up else GoldLabColors.Down,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "  " + Formatters.lot(t.volume) + " 手",
                                color = GoldLabColors.TextPrimary, fontSize = 12.sp, fontFamily = MonoFamily,
                            )
                        }
                        if (isRejected) {
                            Pill("已拒绝", GoldLabColors.Warning)
                        } else {
                            Text(
                                Formatters.signedMoney(t.netProfit),
                                color = pnlColor, fontSize = 14.sp,
                                fontFamily = MonoFamily, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "开 " + Formatters.price(t.openPrice, ui.spec.digits) +
                            (t.closePrice?.let { "  → 平 " + Formatters.price(it, ui.spec.digits) } ?: ""),
                        color = GoldLabColors.TextSecondary, fontSize = 11.sp, fontFamily = MonoFamily,
                    )
                    Text(
                        Formatters.dateTime(t.openTimeMillis) +
                            (t.closeTimeMillis?.let { " → " + Formatters.dateTime(it) } ?: ""),
                        color = GoldLabColors.TextDisabled, fontSize = 10.sp, fontFamily = MonoFamily,
                    )
                    if (!isRejected) {
                        Text(
                            "毛盈亏 " + Formatters.signedMoney(t.grossProfit) +
                                " · 佣金 " + Formatters.money(t.commission) +
                                " · 隔夜费 " + Formatters.money(t.swap),
                            color = GoldLabColors.TextDisabled, fontSize = 10.sp, fontFamily = MonoFamily,
                        )
                    }
                    if (t.fillNote.isNotEmpty()) {
                        Text(
                            t.fillNote,
                            color = GoldLabColors.TextDisabled, fontSize = 9.5.sp,
                        )
                    }
                }
            }
        }
    }
}
