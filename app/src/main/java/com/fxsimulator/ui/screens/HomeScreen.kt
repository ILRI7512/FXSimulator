package com.fxsimulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.MetricRow
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.components.Pill
import com.fxsimulator.ui.format.Formatters
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameUiState

/**
 * 首页：专业交易终端式的信息组织。
 *
 * 这里是唯一的主页 Tab，内容顺序为：标题栏 → 价格 → 模拟账户 → 交易统计 → 快捷入口。
 *
 * 说明：本页原先还有「猜涨跌」玩法入口卡与「行情回放」控制面板，二者已按需求移除。
 * 回放控制（播放 / 倍速）保留在「交易」Tab 的回放条里，功能没有丢失。
 * 「统计」原先是一个独立 Tab，现已并入本页（见 [StatsSection]）。
 */
@Composable
fun HomeScreen(ui: GameUiState, nav: NavHostController) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        // ---- 标题栏 ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("FX模拟器", color = GoldLabColors.Gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(
                    "黄金历史行情模拟交易 · 虚拟资金",
                    color = GoldLabColors.TextSecondary,
                    fontSize = 11.5.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    ui.spec.symbol,
                    color = GoldLabColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MonoFamily,
                )
                Spacer(Modifier.height(3.dp))
                Pill(
                    text = if (ui.replay.playing) "回放中 x" + ui.replay.speed else if (ui.replay.loaded) "已暂停" else "未加载",
                    color = if (ui.replay.playing) GoldLabColors.Up else GoldLabColors.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 价格卡 ----
        GoldPanel {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("当前模拟价格（BID）", color = GoldLabColors.TextSecondary, fontSize = 11.sp)
                    Text(
                        text = Formatters.price(ui.candle?.close, ui.spec.digits),
                        color = GoldLabColors.Gold,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = MonoFamily,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("行情时间", color = GoldLabColors.TextSecondary, fontSize = 11.sp)
                    Text(
                        text = Formatters.dateTime(ui.candle?.timeMillis),
                        color = GoldLabColors.TextPrimary,
                        fontSize = 12.5.sp,
                        fontFamily = MonoFamily,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "进度 " + (ui.replay.index + 1) + " / " + ui.replay.totalBars,
                        color = GoldLabColors.TextSecondary,
                        fontSize = 10.5.sp,
                        fontFamily = MonoFamily,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "开 " + Formatters.price(ui.candle?.open, ui.spec.digits) +
                        "  高 " + Formatters.price(ui.candle?.high, ui.spec.digits),
                    color = GoldLabColors.TextSecondary, fontSize = 10.5.sp, fontFamily = MonoFamily,
                )
                Text(
                    "低 " + Formatters.price(ui.candle?.low, ui.spec.digits) +
                        "  收 " + Formatters.price(ui.candle?.close, ui.spec.digits),
                    color = GoldLabColors.TextSecondary, fontSize = 10.5.sp, fontFamily = MonoFamily,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- 账户 ----
        GoldPanel(title = "模拟账户（虚拟资金）") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("余额 Balance", color = GoldLabColors.TextSecondary, fontSize = 11.sp)
                    Text(
                        Formatters.money(ui.account.balance),
                        color = GoldLabColors.TextPrimary, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, fontFamily = MonoFamily,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("净值 Equity", color = GoldLabColors.TextSecondary, fontSize = 11.sp)
                    Text(
                        Formatters.money(ui.account.equity),
                        color = GoldLabColors.Gold, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, fontFamily = MonoFamily,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            MetricRow(
                "浮动盈亏",
                Formatters.signedMoney(ui.account.floatingPnl) + " USD",
                color(ui.account.floatingPnl),
            )
            MetricRow("占用保证金", Formatters.money(ui.account.margin) + " USD")
            MetricRow("可用保证金", Formatters.money(ui.account.freeMargin) + " USD")
            MetricRow("保证金水平", ui.marginLevelText)
            MetricRow(
                "持仓数量",
                ui.positions.size.toString() + " 笔",
            )
            MetricRow(
                "已实现盈亏（累计）",
                Formatters.signedMoney(ui.stats.netProfit) + " USD",
                color(ui.stats.netProfit),
            )
        }

        if (ui.stopOut) {
            Spacer(Modifier.height(10.dp))
            NoticeBar(
                text = "模拟账户已触发爆仓（保证金水平 ≤ " +
                    String.format("%.0f", ui.spec.leverage.let { 50.0 }) +
                    "%），所有持仓已被强制平仓。请到「设置」重置模拟账户后重新开始。",
                color = GoldLabColors.Down,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- 交易统计（原独立 Tab，现并入本页）----
        StatsSection(ui)

        Spacer(Modifier.height(10.dp))

        // ---- 快捷入口 ----
        GoldPanel(title = "快捷入口") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.navigate("trade") }, modifier = Modifier.weight(1f)) {
                    Text("行情交易", color = GoldLabColors.TextPrimary, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { nav.navigate("history") }, modifier = Modifier.weight(1f)) {
                    Text("交易记录", color = GoldLabColors.TextPrimary, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { nav.navigate("settings") }, modifier = Modifier.weight(1f)) {
                    Text("设置", color = GoldLabColors.TextPrimary, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "本应用为交易学习工具，全部资金为虚拟美元，不连接任何真实交易账户、" +
                "不发送真实订单、不涉及充值提现，也不承诺任何盈利。" +
                "技术指标仅用于练习，不代表对市场走势的预测。",
            color = GoldLabColors.TextDisabled,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}

private fun color(v: Double): Color = when {
    v > 0 -> GoldLabColors.Up
    v < 0 -> GoldLabColors.Down
    else -> GoldLabColors.TextPrimary
}
