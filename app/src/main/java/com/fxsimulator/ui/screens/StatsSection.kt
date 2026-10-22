package com.fxsimulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsimulator.ui.chart.EquityCurve
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.MetricRow
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.components.StatTile
import com.fxsimulator.ui.format.Formatters
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.viewmodel.GameUiState

/**
 * 交易统计内容块。
 *
 * 原先是底部导航里独立的「统计」Tab，现在并入首页 Tab 与其它卡片一起滚动，
 * 所以这里只负责内容本身：**不自带滚动容器、页面级内边距和底部留白**，
 * 这些由宿主页面（[HomeScreen]）统一提供，避免出现嵌套滚动。
 */
@Composable
fun StatsSection(ui: GameUiState) {
    val s = ui.stats

    Text("交易统计", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(
        "全部指标由真实模拟成交记录计算，广告奖励不计入其中",
        color = GoldLabColors.TextSecondary, fontSize = 11.sp,
    )
    Spacer(Modifier.height(10.dp))

    GoldPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("总交易数", s.totalTrades.toString())
            StatTile("盈利", s.winTrades.toString(), valueColor = GoldLabColors.Up)
            StatTile("亏损", s.lossTrades.toString(), valueColor = GoldLabColors.Down)
            StatTile("胜率", Formatters.percent(s.winRate), valueColor = GoldLabColors.Gold)
        }
    }

    Spacer(Modifier.height(10.dp))

    GoldPanel(title = "盈亏") {
        MetricRow(
            "净利润",
            Formatters.signedMoney(s.netProfit) + " USD",
            if (s.netProfit >= 0) GoldLabColors.Up else GoldLabColors.Down,
        )
        MetricRow("总盈利", Formatters.money(s.grossProfit) + " USD", GoldLabColors.Up)
        MetricRow("总亏损", "-" + Formatters.money(s.grossLoss) + " USD", GoldLabColors.Down)
        MetricRow("平均盈利", Formatters.money(s.averageWin) + " USD")
        MetricRow("平均亏损", "-" + Formatters.money(s.averageLoss) + " USD")
        MetricRow(
            "盈亏比（平均盈利 / 平均亏损）",
            if (s.averageLoss > 0) String.format("%.2f", s.profitLossRatio) else "—",
        )
        MetricRow(
            "盈利因子（总盈利 / 总亏损）",
            if (s.grossLoss > 0) String.format("%.2f", s.profitFactor) else "—",
        )
        MetricRow("累计佣金", Formatters.money(s.totalCommission) + " USD")
        MetricRow("累计隔夜费", Formatters.money(s.totalSwap) + " USD")
    }

    Spacer(Modifier.height(10.dp))

    GoldPanel(title = "风险") {
        MetricRow("最大回撤", Formatters.money(s.maxDrawdown) + " USD", GoldLabColors.Down)
        MetricRow("最大回撤比例", Formatters.percent(s.maxDrawdownPercent), GoldLabColors.Down)
        MetricRow("最大连续亏损", s.maxConsecutiveLosses.toString() + " 笔")
        MetricRow("当前连续亏损", ui.consecutiveLosses.toString() + " 笔")
        MetricRow(
            "当日（行情日期 " + com.fxsimulator.stats.PerformanceAnalyzer.dayKeyOf(
                ui.candle?.timeMillis ?: System.currentTimeMillis(),
            ) + "）已实现盈亏",
            Formatters.signedMoney(ui.dailyRealizedPnl) + " USD",
            if (ui.dailyRealizedPnl >= 0) GoldLabColors.Up else GoldLabColors.Down,
        )
    }

    Spacer(Modifier.height(10.dp))

    GoldPanel(title = "账户净值曲线") {
        if (s.equityCurve.size < 2) {
            Text(
                "还没有已平仓交易，暂不绘制净值曲线。",
                color = GoldLabColors.TextSecondary, fontSize = 12.sp,
            )
        } else {
            EquityCurve(
                points = s.equityCurve,
                initialBalance = ui.account.balance - s.netProfit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.height(6.dp))
            MetricRow("曲线采样点", s.equityCurve.size.toString())
            MetricRow("初始资金", Formatters.money(ui.account.balance - s.netProfit) + " USD")
            MetricRow("当前余额", Formatters.money(ui.account.balance) + " USD", GoldLabColors.Gold)
        }
    }

    Spacer(Modifier.height(10.dp))
    NoticeBar(
        "统计口径：只有「已平仓」交易参与计算；被拒绝的订单不计入胜负。" +
            "最大回撤按净值曲线相对峰值的最大回落计算。",
        color = GoldLabColors.TextDisabled,
    )
}
