package com.fxsimulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.fxsimulator.data.market.BundledMarketData
import com.fxsimulator.domain.guess.GuessDirection
import com.fxsimulator.domain.model.SymbolSpecs
import com.fxsimulator.indicators.IndicatorCatalog
import com.fxsimulator.ui.chart.IndicatorMapper
import com.fxsimulator.ui.chart.OverlaySeries
import com.fxsimulator.viewmodel.GameUiState
import com.fxsimulator.domain.guess.GuessQuestion
import com.fxsimulator.domain.guess.GuessRunSummary
import com.fxsimulator.domain.guess.GuessResult
import com.fxsimulator.domain.guess.GuessStats
import com.fxsimulator.ui.chart.CandleChart
import com.fxsimulator.ui.components.DividerLine
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.MetricRow
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.components.Pill
import com.fxsimulator.ui.format.Formatters
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameViewModel
import com.fxsimulator.viewmodel.GuessController
import com.fxsimulator.viewmodel.GuessUiState
import java.util.Locale

/** 固定用 US 区域格式化数字：小数点是「.」，千分位是「,」，不随手机语言漂移 */
private fun num(value: Double, digits: Int): String =
    String.format(Locale.US, "%." + digits + "f", value)

private fun pct0(value: Double): String = num(value * 100.0, 0) + "%"

private fun signedPct(value: Double): String =
    (if (value >= 0) "+" else "") + num(value * 100.0, 2) + "%"

/**
 * 猜涨跌。
 *
 * ## 界面分三段
 * 1. **顶部**：题号、虚拟权益、重置战绩；
 * 2. **战绩条**：胜率 / 盈亏比 / 持有比 —— 这一行是这个玩法的核心产出，
 *    它把「赢的时候拿得短、亏的时候拿得久」直接摆在玩家眼前；
 * 3. **图表 + 操作区**：操作区按状态切换（待作答 / 揭晓中 / 已结算），
 *    三种状态占用同一块空间，所以图表不会跳来跳去。
 *
 * 注意这里**不订阅主界面状态** —— 否则后台回放每走一根 K 线就会让整个页面重组一次，
 * 揭晓动画会卡。价格小数位从 [GuessUiState.digits] 拿。
 */
@Composable
fun GuessScreen(vm: GameViewModel, nav: NavHostController) {
    val ctrl = vm.guess
    val s by ctrl.state.collectAsState()
    // 只订阅主状态里的指标配置：猜涨跌页不跟着回放走，不会因此多重组
    val main by vm.ui.collectAsState()
    val q = s.question

    // 指标算在**已揭晓的 K 线**上（答案不在其中），配置一改就立刻重算。
    // 图例与图表共用同一份结果，避免两处各算一次画出不一致的东西。
    val overlays = remember(s.chartBars, main.indicatorConfig) {
        ctrl.indicatorOverlays(s.chartBars)
    }
    val subPanes = remember(s.chartBars, main.indicatorConfig) {
        ctrl.indicatorPanes(s.chartBars)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        GuessTopBar(ctrl, s)
        GuessStatsStrip(ctrl, s)
        // 指标工具箱：按需求放在「重置战绩」下方
        GuessIndicatorBar(vm, main, nav)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GoldLabColors.Surface)
                .border(1.dp, GoldLabColors.Outline, RoundedCornerShape(14.dp)),
        ) {
            if (s.loading || q == null) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (s.error == null) {
                        CircularProgressIndicator(color = GoldLabColors.Gold)
                        Spacer(Modifier.height(10.dp))
                        Text("正在加载行情…", color = GoldLabColors.TextSecondary, fontSize = 12.sp)
                    } else {
                        Text(s.error ?: "", color = GoldLabColors.Warning, fontSize = 12.sp)
                    }
                }
            } else {
                // key 让每道题拿到一套全新的缩放 / 平移状态，
                // 否则上一题捏合的缩放会带到下一题，看起来像图表坏了
                key(q.sourceIndex, q.month, q.number) {
                    CandleChart(
                        bars = s.chartBars,
                        digits = s.digits,
                        modifier = Modifier.fillMaxSize(),
                        overlays = overlays,
                        hollowUp = true,
                        subPanes = subPanes,
                    )
                }
            }
            // 图例：不标出名字，玩家看到线也不知道那是什么指标
            if (!s.loading && q != null) {
                GuessIndicatorLegend(
                    overlays = overlays,
                    bars = s.chartBars.size,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            }
        }

        GoldPanel {
            when {
                // 评价优先：爆仓或跑满 30 轮之后，这一轮就结束了
                s.runSummary != null -> RunSummaryPanel(ctrl, s.runSummary!!)
                s.result != null -> SettledPanel(ctrl, s.result!!, s)
                s.running -> RunningPanel(ctrl, s)
                else -> ReadyPanel(ctrl, s)
            }
        }
    }
}

/**
 * 指标工具箱（猜涨跌版）。
 *
 * 与交易页**共用同一份配置**（都走 [GameViewModel] 的 add/remove），
 * 所以在哪一页加减都同步 —— 不会出现「交易页有 MACD、猜涨跌没有」这种困惑。
 */
@Composable
private fun GuessIndicatorBar(vm: GameViewModel, main: GameUiState, nav: NavHostController) {
    var menu by remember { mutableStateOf(false) }
    val items = main.indicatorConfig.items
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(
                onClick = { menu = true },
                modifier = Modifier.height(34.dp),
            ) {
                Text(
                    "指标 " + items.size + "  ▾",
                    color = if (items.isNotEmpty()) GoldLabColors.TextPrimary
                    else GoldLabColors.TextSecondary,
                    fontSize = 11.5.sp,
                )
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = GoldLabColors.Surface,
            ) {
                if (items.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text("还没有指标", color = GoldLabColors.TextDisabled, fontSize = 11.sp)
                        },
                        enabled = false,
                        onClick = {},
                    )
                } else {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "当前启用（点按移除）",
                                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
                            )
                        },
                        enabled = false,
                        onClick = {},
                    )
                    items.forEachIndexed { i, inst ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    IndicatorMapper.label(inst),
                                    color = GoldLabColors.TextPrimary, fontSize = 11.5.sp,
                                )
                            },
                            trailingIcon = {
                                Text("✕", color = GoldLabColors.Down, fontSize = 11.sp)
                            },
                            onClick = { vm.removeIndicator(i) },
                        )
                    }
                }
                DividerLine(Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = {
                        Text("快速添加", color = GoldLabColors.TextDisabled, fontSize = 10.sp)
                    },
                    enabled = false,
                    onClick = {},
                )
                for (id in QUICK_INDICATORS) {
                    val def = IndicatorCatalog.byId(id) ?: continue
                    DropdownMenuItem(
                        text = {
                            Text(
                                "＋ " + def.name,
                                color = GoldLabColors.Gold, fontSize = 11.5.sp,
                            )
                        },
                        onClick = { vm.addIndicator(def.id) },
                    )
                }
                DividerLine(Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = {
                        Text("打开指标管理…", color = GoldLabColors.TextPrimary, fontSize = 11.5.sp)
                    },
                    onClick = {
                        menu = false
                        nav.navigate("indicators")
                    },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (items.isEmpty()) "加个指标再看图，别靠感觉" else "点这里增删；图例在图表左上角",
            color = GoldLabColors.TextSecondary,
            fontSize = 10.5.sp,
        )
    }
}

/** 指标图例：浮在图表左上角。没有它，玩家看到线也不知道那是哪个指标。 */
@Composable
private fun GuessIndicatorLegend(
    overlays: List<OverlaySeries>,
    bars: Int,
    modifier: Modifier = Modifier,
) {
    // 一根有效值都没有（指标还在预热）时不渲染，否则图上会糊一块空底色
    val visible = overlays.filter { o -> o.values.any { it.isFinite() } }
    if (visible.isEmpty()) return
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(GoldLabColors.Background.copy(alpha = 0.62f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        if (bars < 50) {
            Text(
                "数据不足（" + bars + " 根），指标还在预热",
                color = GoldLabColors.Warning, fontSize = 9.sp,
            )
        } else {
            for (o in visible) {
                val last = o.values.lastOrNull { it.isFinite() } ?: continue
                Text(
                    o.label + " " + Formatters.price(last, o.digits),
                    color = o.color,
                    fontSize = 9.sp,
                    fontFamily = MonoFamily,
                )
            }
        }
    }
}

/**
 * 本轮结算评价。
 *
 * 爆仓和跑满 30 轮走的是同一块面板 —— 两种结局都该被认真评价，
 * 而不是「爆仓了就直接退出去」。
 */
@Composable
private fun RunSummaryPanel(ctrl: GuessController, r: GuessRunSummary) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.grade,
                color = if (r.busted) GoldLabColors.Down else GoldLabColors.Gold,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (r.busted) "爆仓 · 第 " + r.rounds + " 轮" else "跑满 " + r.rounds + " 轮",
                    color = GoldLabColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "上限 " + r.maxRounds + " 轮",
                    color = GoldLabColors.TextDisabled,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(r.comment, color = GoldLabColors.TextSecondary, fontSize = 11.5.sp)
        Spacer(Modifier.height(8.dp))
        MetricRow("开局权益", Formatters.money(r.startEquity) + " USD")
        MetricRow("结束权益", Formatters.money(r.endEquity) + " USD")
        MetricRow(
            "收益率",
            (if (r.returnPct >= 0) "+" else "") + num(r.returnPct * 100, 2) + "%",
        )
        MetricRow("胜率", num(r.winRate * 100, 0) + "%（" + r.wins + " 胜 / " + r.losses + " 负）")
        MetricRow(
            "设了止损的轮数",
            r.stopLossRounds.toString() + " / " + r.rounds +
                "（其中被打掉 " + r.stopLossHit + "）",
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { ctrl.startRun() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldLabColors.Gold,
                contentColor = GoldLabColors.Background,
            ),
        ) { Text("再来一轮 30 局", fontWeight = FontWeight.Bold) }
    }
}

// ------------------------------------------------------------ 顶部

@Composable
private fun GuessTopBar(ctrl: GuessController, s: GuessUiState) {
    val st = s.stats
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "猜涨跌",
            color = GoldLabColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        if (!s.loading && s.question != null) {
            Pill("第 " + s.question!!.number + " 题", GoldLabColors.Info)
        }
        // 品种在**猜之前**就显示：玩家必须知道自己猜的是什么。
        // 月份仍然只在结算后揭晓 —— 品种不影响可预测性，但"我知道那阵子在涨"会。
        if (!s.loading && s.question != null) {
            Spacer(Modifier.width(6.dp))
            Pill(SymbolSpecs.displayName(s.question!!.symbol), GoldLabColors.Gold)
        }
        // 月份只在结算后揭晓：提前给出年份会让人用「我知道那阵子在涨」作弊
        if (s.result != null && s.month.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Pill(BundledMarketData.monthLabel(s.month), GoldLabColors.TextSecondary)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text("虚拟权益", color = GoldLabColors.TextDisabled, fontSize = 10.sp)
            Text(
                Formatters.money(st.equity),
                color = if (st.equity >= st.initialEquity) GoldLabColors.Up else GoldLabColors.Down,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFamily,
            )
        }
    }
}

// ------------------------------------------------------------ 战绩条

@Composable
private fun GuessStatsStrip(ctrl: GuessController, s: GuessUiState) {
    val st = s.stats
    GoldPanel {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            MiniStat("已答", st.total.toString())
            MiniStat(
                label = "胜率",
                value = if (st.total == 0) "—" else pct0(st.winRate),
                valueColor = when {
                    st.total == 0 -> GoldLabColors.TextPrimary
                    st.winRate >= 0.5 -> GoldLabColors.Up
                    else -> GoldLabColors.Down
                },
                sub = "硬币 50%",
            )
            MiniStat(
                label = "盈亏比",
                value = if (st.payoff > 0.0) num(st.payoff, 2) else "—",
                valueColor = when {
                    st.payoff <= 0.0 -> GoldLabColors.TextPrimary
                    st.payoff < 1.0 -> GoldLabColors.Down
                    else -> GoldLabColors.Up
                },
                sub = "赢÷亏",
            )
            MiniStat(
                label = "持有比",
                value = if (st.holdRatio > 0.0) num(st.holdRatio, 1) + "×" else "—",
                valueColor = if (st.holdRatio >= 1.5) GoldLabColors.Down else GoldLabColors.TextPrimary,
                sub = "亏÷赢",
            )
            // 重置挪到「持有比」右侧：省掉一整行，窄屏也不会再把下面的仓位挤出去
            ResetStatsButton(ctrl, Modifier.align(Alignment.CenterVertically))
        }
        val insight = insightText(st)
        if (insight != null) {
            Spacer(Modifier.height(7.dp))
            NoticeBar(
                text = insight,
                color = when {
                    st.busted -> GoldLabColors.Down
                    st.holdRatio >= 1.5 -> GoldLabColors.Warning
                    else -> GoldLabColors.Info
                },
            )
        }
    }
}

/**
 * 极简战绩格子。
 *
 * 不复用公共的 StatTile：那个是给首页账户卡用的，字号偏大；
 * 这里一行要放下四个格子外加重置按钮，窄屏上会被挤爆。
 */
@Composable
private fun MiniStat(
    label: String,
    value: String,
    valueColor: Color = GoldLabColors.TextPrimary,
    sub: String? = null,
) {
    Column {
        Text(label, color = GoldLabColors.TextDisabled, fontSize = 9.sp)
        Spacer(Modifier.height(1.dp))
        Text(
            value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = MonoFamily,
        )
        if (sub != null) {
            Text(sub, color = GoldLabColors.TextDisabled, fontSize = 8.5.sp, fontFamily = MonoFamily)
        }
    }
}

/**
 * 把「胜率 / 盈亏比 / 持有时间」翻译成一句人话。
 *
 * 优先讲持有时间 —— 因为那是玩家自己造成的、最容易忽略、也最扎心的那一项。
 * 样本太少时返回 null，宁可什么都不说，也不给一个会误导人的结论。
 */
private fun insightText(st: GuessStats): String? {
    if (st.total < 8) return null
    if (st.busted) {
        return "账户已经归零。这就是「每局都在付成本、又没有一个方向优势」的终点。"
    }
    if (st.holdRatio >= 1.5) {
        return "赢的时候平均拿 " + num(st.avgWinBars, 1) + " 根，亏的时候拿 " +
            num(st.avgLossBars, 1) + " 根 —— 亏损时拿得久 " + num(st.holdRatio, 1) + " 倍。"
    }
    if (st.payoff > 0.0 && st.payoff < 0.95) {
        return "盈亏比 " + num(st.payoff, 2) + "：赢的没有亏的多。打平需要 " +
            pct0(st.breakEvenWinRate) + " 胜率，你现在 " + pct0(st.winRate) + "。"
    }
    if (st.payoff > 0.0) {
        return "按现在的盈亏比，打平需要 " + pct0(st.breakEvenWinRate) + " 胜率；" +
            "你现在 " + pct0(st.winRate) + "（抛硬币是 50%）。"
    }
    return null
}

// ------------------------------------------------------------ 操作区

@Composable
private fun ReadyPanel(ctrl: GuessController, s: GuessUiState) {
    Column {
        // 权益不足、手数非法这类即时反馈必须出现在这里，
        // 否则玩家点了「涨」什么都没发生，会以为按钮坏了
        s.notice?.let {
            Text(it, color = GoldLabColors.Warning, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            "猜接下来 1 小时的方向；入场后逐根揭晓，随时可以平仓。",
            color = GoldLabColors.TextSecondary,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DirectionButton("涨", GoldLabColors.Up, Modifier.weight(1f)) {
                ctrl.answer(GuessDirection.UP)
            }
            DirectionButton("跌", GoldLabColors.Down, Modifier.weight(1f)) {
                ctrl.answer(GuessDirection.DOWN)
            }
        }
        Spacer(Modifier.height(7.dp))
        SetupChips(ctrl, s)
    }
}

@Composable
private fun RunningPanel(ctrl: GuessController, s: GuessUiState) {
    val dir = s.direction
    val bid = s.chartBars.lastOrNull()?.close ?: s.entryPrice
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    (dir?.label ?: "") + " · 入场 " + Formatters.price(s.entryPrice, s.digits),
                    color = GoldLabColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = MonoFamily,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    Formatters.signedMoney(s.floating),
                    color = if (s.floating >= 0) GoldLabColors.Up else GoldLabColors.Down,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MonoFamily,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "已揭晓 " + s.futureShown + " / " + s.maxBars + " 根",
                    color = GoldLabColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = MonoFamily,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "现价 " + Formatters.price(bid, s.digits),
                    color = GoldLabColors.TextPrimary,
                    fontSize = 12.sp,
                    fontFamily = MonoFamily,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { ctrl.close() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldLabColors.Gold,
                contentColor = Color(0xFF0B0E14),
            ),
        ) {
            Text("平 仓", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettledPanel(ctrl: GuessController, r: GuessResult, s: GuessUiState) {
    val color = if (r.isWin) GoldLabColors.Up else GoldLabColors.Down
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    if (r.isWin) "猜对了" else "猜错了",
                    color = color,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "猜" + r.direction.label + " · " + r.closeKind.label,
                    color = GoldLabColors.TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "图中已显示你离场之后、到窗口结束的全部走势",
                    color = GoldLabColors.TextDisabled,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                Formatters.signedMoney(r.profit),
                color = color,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFamily,
            )
        }
        Spacer(Modifier.height(8.dp))
        DividerLine()
        Spacer(Modifier.height(4.dp))
        MetricRow("持有", r.barsHeld.toString() + " 根（约 " + r.barsHeld + " 分钟）")
        MetricRow(
            label = "这段行情实际",
            value = signedPct(r.pct),
            valueColor = if (r.pct >= 0) GoldLabColors.Up else GoldLabColors.Down,
        )
        MetricRow(
            label = "持有期间最大浮盈",
            value = "+" + Formatters.price(r.maxFavorable, s.digits),
            valueColor = GoldLabColors.Up,
        )
        MetricRow(
            label = "持有期间最大浮亏",
            value = Formatters.price(r.maxAdverse, s.digits),
            valueColor = GoldLabColors.Down,
        )
        if (r.referenceStopAt > 0) {
            MetricRow(
                label = "若一开始就挂止损 " + Formatters.price(r.referenceStopPrice, s.digits),
                value = "第 " + r.referenceStopAt + " 根被打掉 · " +
                    Formatters.signedMoney(r.referenceStopProfit),
                valueColor = GoldLabColors.Warning,
            )
        } else {
            MetricRow(
                label = "若一开始就挂止损",
                value = "全程没被触发",
                valueColor = GoldLabColors.TextSecondary,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { ctrl.pickMarketAndDeal() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("换一段行情", color = GoldLabColors.TextSecondary, fontSize = 13.sp)
            }
            Button(
                onClick = { ctrl.next() },
                modifier = Modifier.weight(1.3f).height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldLabColors.Gold,
                    contentColor = Color(0xFF0B0E14),
                ),
            ) {
                Text("下一题", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(2.dp))
        SetupChips(ctrl, s)
    }
}

/**
 * 一段价格距离换算成美元：点数 × 一个点 × 合约大小 × 手数 × 报价货币折算。
 *
 * 最后那个折算不能省：USDJPY 这类品种的盈亏先天以日元计价，
 * 不折算的话金额会差上百倍（而屏幕上看起来仍然"像个正常数字"）。
 */
private fun usdForPoints(q: GuessQuestion, lot: Double, points: Int): Double {
    if (points <= 0 || lot <= 0.0) return 0.0
    return points * q.spec.point * q.spec.contractSize * lot * q.spec.usdPerQuoteUnit(q.anchor)
}

/** 仓位与止损止盈：手数、止损、止盈全部由玩家手填 */
@Composable
private fun SetupChips(ctrl: GuessController, s: GuessUiState) {
    val q = s.question
    // 换题时重填默认值；同一题内不覆盖玩家已经改过的数字
    var lotText by remember(q?.sourceIndex) { mutableStateOf(num(s.lot, 2)) }
    var slText by remember(q?.sourceIndex) { mutableStateOf(s.slPoints.toString()) }
    var tpText by remember(q?.sourceIndex) { mutableStateOf(s.tpPoints.toString()) }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GuessField("手数", lotText, Modifier.weight(1f), enabled = !s.running) {
                lotText = it
                it.toDoubleOrNull()?.let { v -> if (v > 0.0) ctrl.setLot(v) }
            }
            GuessField("止损(点)", slText, Modifier.weight(1f), enabled = !s.running) {
                slText = it
                ctrl.setStopLoss(it.toIntOrNull() ?: 0)
            }
            GuessField("止盈(点)", tpText, Modifier.weight(1f), enabled = !s.running) {
                tpText = it
                ctrl.setTakeProfit(it.toIntOrNull() ?: 0)
            }
        }
        if (q != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "第 " + (s.runRound + 1) + "/" + s.runMax + " 轮" +
                    "  ·  权益 " + Formatters.money(s.runEquity) +
                    "  ·  止损亏 " + Formatters.money(usdForPoints(q, s.lot, s.slPoints)) +
                    "  ·  止盈赚 " + Formatters.money(usdForPoints(q, s.lot, s.tpPoints)),
                color = GoldLabColors.TextSecondary,
                fontSize = 10.5.sp,
            )
            Text(
                SymbolSpecs.displayName(q.symbol) + " · " + num(s.lot, 2) + " 手" +
                    " · 1 点 = " + num(q.spec.point, q.spec.digits) + " 价格",
                color = GoldLabColors.TextDisabled,
                fontSize = 10.sp,
            )
        }
    }
}

/** 紧凑的数字输入框（手数 / 止损 / 止盈共用） */
@Composable
private fun GuessField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(label, fontSize = 10.sp) },
        textStyle = TextStyle(
            fontSize = 14.sp,
            fontFamily = MonoFamily,
            color = GoldLabColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        ),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
        ),
        modifier = modifier,
    )
}

@Composable
private fun DirectionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color(0xFF0B0E14),
        ),
    ) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) GoldLabColors.Gold else GoldLabColors.TextSecondary
    // 这里**不能**用 TextButton：Material3 的按钮会把触控目标强制撑到 48dp，
    // 而我们只想给它 26dp —— 结果就是底部被裁掉一半（真机上「仓位」那排显示不全就是这个原因）。
    // 自己画一个，高度想多小就多小。
    Box(
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) GoldLabColors.GoldDim else Color.Transparent)
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.55f) else GoldLabColors.Outline,
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) accent else accent.copy(alpha = 0.35f),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = MonoFamily,
        )
    }
}

/** 两步确认的重置按钮：战绩是玩家花时间攒出来的，不该被误触清零。 */
@Composable
private fun ResetStatsButton(ctrl: GuessController, modifier: Modifier = Modifier) {
    var armed by remember { mutableStateOf(false) }
    // 同样自己画：TextButton 的 48dp 最小触控目标会把战绩条整行顶高
    Box(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(
                1.dp,
                if (armed) GoldLabColors.Down.copy(alpha = 0.6f) else GoldLabColors.Outline,
                RoundedCornerShape(7.dp),
            )
            .clickable {
                if (armed) {
                    ctrl.resetStats()
                    armed = false
                } else {
                    armed = true
                }
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // 两种状态都是 4 个字，切换时宽度不跳
            if (armed) "确认清空" else "重置战绩",
            color = if (armed) GoldLabColors.Down else GoldLabColors.TextDisabled,
            fontSize = 10.sp,
        )
    }
}
