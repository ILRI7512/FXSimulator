package com.fxsimulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.fxsimulator.domain.event.EventSide
import com.fxsimulator.domain.event.FixedOddsCycle
import com.fxsimulator.domain.event.FixedOddsRules
import com.fxsimulator.domain.event.FixedOddsStatus
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.SymbolSpecs
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 固定赔率事件合约（方案 3.7 B 模式 / 币安口径）。
 *
 * 与概率定价制的关键差别：这里没有"份额价"，只有**本金 × 支付率**；
 * 持平退本金，而且**没有提前平仓**。盈亏平衡胜率锁死在 55.56%。
 */
@Composable
fun FixedOddsGameScreen(vm: GameViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val games = vm.games

    var symbol by remember { mutableStateOf("BTCUSDT") }
    var cycle by remember { mutableStateOf(FixedOddsCycle.M30) }
    var bars by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var cursor by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var feed by remember { mutableStateOf<com.fxsimulator.domain.games.GamesFeed?>(null) }
    var startMonth by remember { mutableStateOf("") }
    var allMonths by remember { mutableStateOf<List<String>>(emptyList()) }
    var stakeText by remember { mutableStateOf("10") }
    var message by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableIntStateOf(1) }
    var subTick by remember { mutableIntStateOf(0) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(symbol, startMonth) {
        loading = true
        val pair = withContext(Dispatchers.IO) {
            val all = GamesMarket.allMonths(context, symbol)
            val f = GamesMarket.feed(context, symbol, startMonth.ifEmpty { null }).also { it.loadNext() }
            all to f
        }
        allMonths = pair.first
        feed = pair.second
        bars = pair.second.bars
        cursor = minOf(1_000, pair.second.bars.size)
        loading = false
    }

    LaunchedEffect(bars, playing, speed) {
        if (bars.isEmpty()) return@LaunchedEffect
        while (true) {
            if (!playing) return@LaunchedEffect
            if (cursor >= bars.size - 1) {
                val f = feed ?: return@LaunchedEffect
                val more = withContext(Dispatchers.IO) { f.loadNext() }
                if (!more) return@LaunchedEffect
                bars = f.bars
                version++
                continue
            }
            delay((1000L / speed).coerceAtLeast(16L))
            subTick++
            if (subTick >= GamesTick.PER_BAR) {
                subTick = 0
                cursor += 1
                games.onBars(bars.subList(0, cursor))
            }
            version++
        }
    }

    val visible = if (cursor > 0) bars.subList(0, cursor) else emptyList<Candle>()
    val price = remember(visible.size, subTick) { GamesTick.price(visible, subTick) }
    val stake = stakeText.toDoubleOrNull() ?: 0.0
    val payoutRate = FixedOddsRules.PAYOUT_RATE

    fun buy(side: EventSide) {
        if (visible.isEmpty()) { message = "行情未就绪"; return }
        val err = games.openFixed(
            symbol = symbol, cycle = cycle, side = side, stake = stake,
            nowMillis = visible.last().timeMillis,
            expireIndex = (cursor - 1) + cycle.minutes,
            targetPrice = price,
        )
        message = err ?: String.format(
            Locale.US, "已下注 %.2f USDT 押%s；赢返还 %.2f（含本金）",
            stake, if (side == EventSide.UP) "涨" else "跌", stake * (1 + payoutRate),
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("固定赔率", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { nav.popBackStack() }) {
                Text("返回", color = GoldLabColors.Gold, fontSize = 13.sp)
            }
        }

        GamesMarketPicker(
            symbol = symbol,
            symbols = listOf("BTCUSDT", "ETHUSDT"),
            availableMonths = allMonths,
            startMonth = feed?.months?.firstOrNull() ?: "",
            effectiveMonths = feed?.months?.size ?: 0,
            onSymbol = { symbol = it },
            onMonth = { startMonth = it },
        )
        Spacer(Modifier.height(8.dp))

        GoldPanel(title = "权益与行情") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    String.format(Locale.US, "%,.2f USDT", games.equity),
                    color = GoldLabColors.Gold, fontSize = 16.sp, fontFamily = MonoFamily,
                )
                Text(
                    if (loading) "加载中…" else String.format(Locale.US, "%s %.2f", symbol, price),
                    color = GoldLabColors.TextSecondary, fontSize = 12.sp, fontFamily = MonoFamily,
                )
            }
            Text(
                "已推进 $cursor/${bars.size} 根（已载 ${feed?.loadedCount ?: 0}/${feed?.months?.size ?: 0} 月）｜当日亏损 " +
                    String.format(Locale.US, "%.2f / %.2f", games.dailyLoss, games.dailyLossCap()) +
                    (if (games.isDailyCapReached()) "（已达上限）" else ""),
                color = if (games.isDailyCapReached()) GoldLabColors.Down else GoldLabColors.TextDisabled,
                fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "K 线（最近 240 根）") {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                GamesChart(GamesTick.displayBars(visible, subTick), SymbolSpecs.of(symbol).digits, Modifier.fillMaxSize(), maxBars = chartBarsFor(cycle.minutes))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            GamesTick.hint(speed),
            color = GoldLabColors.TextSecondary, fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        GamesClockBar(
            playing = playing,
            speed = speed,
            canSkipToSettle = games.openFixed.isNotEmpty(),
            onTogglePlay = { playing = !playing },
            onSpeed = { speed = it },
            onSkipToSettle = {
                val next = games.openFixed.minOfOrNull { it.expireIndex } ?: -1
                if (next + 1 > cursor) {
                    cursor = (next + 1).coerceAtMost(bars.size)
                    games.onBars(bars.subList(0, cursor))
                    version++
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "周期") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FixedOddsCycle.entries.forEach { c ->
                    TextButton(onClick = { cycle = c }) {
                        Text(
                            c.label,
                            color = if (c == cycle) GoldLabColors.Gold else GoldLabColors.TextSecondary,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "赔率") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("支付率", color = GoldLabColors.TextSecondary, fontSize = 11.sp)
                    Text(
                        String.format(Locale.US, "%.0f%%", payoutRate * 100),
                        color = GoldLabColors.Gold, fontSize = 18.sp, fontFamily = MonoFamily,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("平衡胜率", color = GoldLabColors.TextSecondary, fontSize = 11.sp)
                    Text(
                        String.format(Locale.US, "%.2f%%", FixedOddsRules.breakEvenWinRate * 100),
                        color = GoldLabColors.Down, fontSize = 18.sp, fontFamily = MonoFamily,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "持平退本金、无手续费、不能提前平仓｜单笔上限 " +
                    String.format(Locale.US, "%.2f", games.maxFixedStake()) + " USDT",
                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "下注") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = stakeText,
                    onValueChange = { stakeText = it },
                    label = { Text("本金（≥ ${FixedOddsRules.MIN_STAKE.toInt()}）") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Column {
                    listOf(5, 10, 25, 50).forEach { v ->
                        TextButton(onClick = { stakeText = v.toString() }, modifier = Modifier.height(48.dp)) {
                            Text("$v", fontSize = 10.sp, color = GoldLabColors.Gold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { buy(EventSide.UP) }, modifier = Modifier.weight(1f)) {
                    Text(
                        "押涨 赢 " + String.format(Locale.US, "%.2f", stake * (1 + payoutRate)),
                        color = GoldLabColors.Up, fontSize = 12.sp,
                    )
                }
                OutlinedButton(onClick = { buy(EventSide.DOWN) }, modifier = Modifier.weight(1f)) {
                    Text(
                        "押跌 赢 " + String.format(Locale.US, "%.2f", stake * (1 + payoutRate)),
                        color = GoldLabColors.Down, fontSize = 12.sp,
                    )
                }
            }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = GoldLabColors.TextSecondary, fontSize = 11.sp)
            }
        }

        if (games.openFixed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GoldPanel(title = "持仓中（${games.openFixed.size}）") {
                games.openFixed.forEach { p ->
                    Text(
                        String.format(
                            Locale.US, "%s %s %.2f USDT｜目标 %.2f｜剩 %d 根",
                            p.symbol, if (p.side == EventSide.UP) "涨" else "跌", p.stake,
                            p.targetPrice, (p.expireIndex - (cursor - 1)).coerceAtLeast(0),
                        ),
                        color = GoldLabColors.TextSecondary, fontSize = 10.sp, fontFamily = MonoFamily,
                    )
                }
            }
        }

        if (games.settledFixed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GoldPanel(title = "结算记录（最近 5 条）") {
                games.settledFixed.takeLast(5).reversed().forEach { p ->
                    val label = when (p.status) {
                        FixedOddsStatus.WON -> "赢"
                        FixedOddsStatus.LOST -> "输"
                        FixedOddsStatus.REFUNDED -> "退本金"
                        FixedOddsStatus.OPEN -> "—"
                    }
                    Text(
                        String.format(
                            Locale.US, "%s %s %.2f USDT｜%s｜目标 %.2f → 结算 %.2f｜%+.2f",
                            p.symbol, if (p.side == EventSide.UP) "涨" else "跌", p.stake, label,
                            p.targetPrice, p.settlementPrice ?: 0.0, p.realizedPnl ?: 0.0,
                        ),
                        color = when (p.status) {
                            FixedOddsStatus.WON -> GoldLabColors.Up
                            FixedOddsStatus.LOST -> GoldLabColors.Down
                            else -> GoldLabColors.TextSecondary
                        },
                        fontSize = 10.sp, fontFamily = MonoFamily,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        NoticeBar(
            "固定赔率天生负期望：支付率 80% 需要 55.56% 胜率才打平。" +
                "结算价用到期窗口内 min(周期,5) 根 1m 均价——趋势里它会滞后于最新价，这是你可利用的信息。",
            color = GoldLabColors.Info,
        )
    }
}
