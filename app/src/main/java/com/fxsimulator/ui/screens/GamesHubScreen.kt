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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.fxsimulator.ui.theme.TypeScale
import com.fxsimulator.ui.theme.Space
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameViewModel

/**
 * 「玩法」入口页：把非交易的玩法集中在这里，点卡片进入具体玩法。
 *
 * 目前两项：事件合约（二元份额）、期权（欧式买方）。
 *
 * ## 权益是独立的
 * 这页显示的 USDT 权益与「交易」页的保证金账户**互不影响**：
 * 玩法零杠杆、无强平，没法跟保证金账户共用一个余额。
 */
@Composable
fun GamesHubScreen(vm: GameViewModel, nav: NavHostController) {
    val games = vm.games

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("玩法", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { nav.popBackStack() }) {
                Text("返回", color = GoldLabColors.Gold, fontSize = 13.sp)
            }
        }

        NoticeBar(
            "这里的三种玩法都用**独立 USDT 权益**（初始 10,000），与交易账户的保证金账户互不影响。" +
                "全部为虚拟记账单位，不涉及真实资产。",
            color = GoldLabColors.Info,
        )
        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "玩法权益") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("可用权益", color = GoldLabColors.TextSecondary, fontSize = 11.sp)
                    Text(
                        String.format(java.util.Locale.US, "%,.2f USDT", games.equity),
                        color = GoldLabColors.Gold, fontSize = 20.sp, fontFamily = MonoFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("累计已实现", color = GoldLabColors.TextSecondary, fontSize = 11.sp)
                    val pnl = games.realizedPnl
                    Text(
                        String.format(java.util.Locale.US, "%+,.2f", pnl),
                        color = if (pnl >= 0) GoldLabColors.Up else GoldLabColors.Down,
                        fontSize = 16.sp, fontFamily = MonoFamily,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "持仓中：事件合约 ${games.openEvents.size} 张 / 期权 ${games.openOptions.size} 张" +
                    "｜已结算：${games.settledEvents.size} / ${games.settledOptions.size}",
                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(10.dp))

        GameCard(
            title = "事件合约",
            subtitle = "BTC/ETH 到期涨或跌，二元赔付",
            detail = "份额价 0.01–0.99 = 隐含概率；最小 6 张 / 5 USDT；1m–4h；结算用全周期 TWAP 均价",
            accent = GoldLabColors.Gold,
            onClick = { nav.navigate("game-event") },
        )
        Spacer(Modifier.height(8.dp))
        GameCard(
            title = "期权",
            subtitle = "欧式看涨/看跌，只能做买方",
            detail = "权利金按 Black-Scholes + 实测波动率；1h–30d；最大亏损 = 权利金；显示希腊值",
            accent = GoldLabColors.Up,
            onClick = { nav.navigate("game-option") },
        )

        Spacer(Modifier.height(8.dp))
        GameCard(
            title = "固定赔率",
            subtitle = "币安口径：支付率 80%，平衡胜率 55.56%",
            detail = "一笔本金定输赢；持平退本金；单笔 5–250 USDT；不能提前平仓；10m–1d",
            accent = GoldLabColors.TextSecondary,
            onClick = { nav.navigate("game-fixed") },
        )

        Spacer(Modifier.height(12.dp))
        GoldPanel(title = "怎么玩") {
            Text(
                "事件合约：报价围绕 0.5 抖动（情绪偏移），找到「报价偏离真实概率」的时机买入，" +
                    "长期胜率高于报价隐含概率才有正期望。",
                color = GoldLabColors.TextSecondary, fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "期权：方向看对也可能亏钱 —— 时间价值会衰减。先看 delta 与 theta，" +
                    "再决定买哪一档、买多少。",
                color = GoldLabColors.TextSecondary, fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "权益与持仓只保存在本次运行内，重启 App 会重置。",
                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    subtitle: String,
    detail: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    GoldPanel(title = title, accent = accent) {
        Text(
            subtitle,
            color = accent,
            fontSize = TypeScale.body,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            detail,
            color = GoldLabColors.TextDisabled,
            fontSize = TypeScale.caption,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(Space.sm))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClick) {
                Text(
                    "进入 $title",
                    color = GoldLabColors.Gold,
                    fontSize = TypeScale.body,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(Space.xs))
                Text("›", color = GoldLabColors.Gold, fontSize = TypeScale.number)
            }
        }
    }
}
