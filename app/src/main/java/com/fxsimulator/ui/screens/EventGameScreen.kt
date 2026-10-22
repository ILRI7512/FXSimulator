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
import com.fxsimulator.domain.event.EventCycle
import com.fxsimulator.domain.event.EventPricing
import com.fxsimulator.domain.event.EventRules
import com.fxsimulator.domain.event.EventSide
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
import kotlin.random.Random

/**
 * 事件合约（方案第三节）：二元份额，价格即隐含概率。
 *
 * 行情用自动推进的 1m 时钟（350 ms/根），下注后能看到结算；
 * 结算严格等窗口走完，数据不足时持仓保持 OPEN。
 */
@Composable
fun EventGameScreen(vm: GameViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val games = vm.games

    var symbol by remember { mutableStateOf("BTCUSDT") }
    var cycle by remember { mutableStateOf(EventCycle.M5) }
    var bars by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var cursor by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var feed by remember { mutableStateOf<com.fxsimulator.domain.games.GamesFeed?>(null) }
    var startMonth by remember { mutableStateOf("") }
    var allMonths by remember { mutableStateOf<List<String>>(emptyList()) }
    var sentiment by remember { mutableStateOf(0.0) }
    var sharesText by remember { mutableStateOf("100") }
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
        sentiment = 0.0
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
                sentiment = EventPricing.nextSentiment(sentiment, (Random.nextDouble() - 0.5) * 0.06)
            }
            version++
            version++
        }
    }

    val visible = if (cursor > 0) bars.subList(0, cursor) else emptyList<Candle>()
    // 现价 = 当前 5 秒 tick（每 1 秒现实时间更新一次）
    val price = remember(visible.size, subTick) { GamesTick.price(visible, subTick) }
    // 作图：最后一根按已走完的 tick 动态成形（未收完的 K 线）
    val displayBars = remember(visible.size, subTick) { GamesTick.displayBars(visible, subTick) }
    // 当前轮次：目标价在本轮开始时固定，之后现价相对它摆动 —— 报价因此会变化
    val market = remember(version, cycle) {
        if (visible.size < 3) null else games.roundFor(symbol, cycle, visible)
    }
    // tick 模式下：当前 1 分钟还没收完（formingBars=1），现价用 5 秒 tick 价
    val quote = remember(version, cycle, market?.targetPrice, subTick) {
        if (market == null) null
        else games.eventQuote(
            market = market,
            bars = visible,
            noise = (Random.nextDouble() - 0.5) * 0.02,
            sentiment = sentiment * 0.2,
            formingBars = 1,
            spotOverride = price,
        )
    }
    val target = market?.targetPrice ?: 0.0
    val secondsLeft = market?.let { m ->
        ((m.expireAtMillis - (visible.lastOrNull()?.timeMillis ?: 0L)) / 1000L).coerceAtLeast(0L)
    } ?: 0L
    val upAsk = quote?.ask ?: 0.5
    val downAsk = quote?.let { 1.0 - it.bid } ?: 0.5
    val shares = sharesText.toIntOrNull() ?: 0

    fun buy(side: EventSide) {
        if (visible.isEmpty()) { message = "行情未就绪"; return }
        val m = market ?: run { message = "行情未就绪"; return }
        val p = if (side == EventSide.UP) upAsk else downAsk
        // 下注的目标价 = 本轮固定目标价（不是"下单瞬间的现价"），否则概率永远是 0.5
        val err = games.openEvent(
            symbol = symbol, cycle = cycle, side = side, shares = shares,
            price = p, nowMillis = m.openAtMillis, bars = visible,
            expireIndex = m.expireIndex, targetPrice = m.targetPrice,
        )
        message = err ?: String.format(
            Locale.US, "已买入 %d 张「%s」@ %.3f，成本 %.2f USDT",
            shares, if (side == EventSide.UP) "涨" else "跌", p, shares * p * (1 + EventRules.FEE_RATE),
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
            Text("事件合约", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    if (loading) "加载中…" else String.format(Locale.US, "%s  %.2f", symbol, price),
                    color = GoldLabColors.TextSecondary, fontSize = 12.sp, fontFamily = MonoFamily,
                )
            }
            Text(
                "已推进 $cursor/${bars.size} 根（已载 ${feed?.loadedCount ?: 0}/${feed?.months?.size ?: 0} 月） 1m｜年化波动 " +
                    String.format(Locale.US, "%.1f%%", (quote?.annualVol ?: 0.0) * 100),
                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "K 线（最近 240 根）") {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                GamesChart(displayBars, SymbolSpecs.of(symbol).digits, Modifier.fillMaxSize(), maxBars = chartBarsFor(cycle.minutes))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            GamesTick.hint(speed),
            color = GoldLabColors.TextSecondary, fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        Spacer(Modifier.height(6.dp))
        GamesClockBar(
            playing = playing,
            speed = speed,
            canSkipToSettle = games.openEvents.isNotEmpty(),
            onTogglePlay = { playing = !playing },
            onSpeed = { speed = it },
            onSkipToSettle = {
                val next = games.openEvents.minOfOrNull { it.expireIndex } ?: -1
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
                EventCycle.entries.forEach { c ->
                    TextButton(onClick = { cycle = c }) {
                        Text(
                            c.label,
                            color = if (c == cycle) GoldLabColors.Gold else GoldLabColors.TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "报价（价格 = 隐含概率）") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("押涨", color = GoldLabColors.Up, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        String.format(Locale.US, "%.3f", upAsk),
                        color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontFamily = MonoFamily,
                    )
                    Text("平衡胜率 " + String.format(Locale.US, "%.1f%%", upAsk * 100), color = GoldLabColors.TextDisabled, fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("押跌", color = GoldLabColors.Down, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        String.format(Locale.US, "%.3f", downAsk),
                        color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontFamily = MonoFamily,
                    )
                    Text("平衡胜率 " + String.format(Locale.US, "%.1f%%", downAsk * 100), color = GoldLabColors.TextDisabled, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                String.format(Locale.US, "目标价 %.2f｜现价 %.2f｜剩余 %d 秒（%d 根）",
                    target, price, secondsLeft, (secondsLeft / 60).toInt()),
                color = GoldLabColors.TextSecondary, fontSize = 10.sp, fontFamily = MonoFamily,
            )
            Text(
                "剩余时间越短、偏离越大 → 报价越极端（可到 0.01/0.99）；现价穿过目标价会立刻翻转。" +
                    "｜单笔上限 " + String.format(Locale.US, "%.2f", games.maxSingleStake()) + " USDT",
                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "下注") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = sharesText,
                    onValueChange = { sharesText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("张数（≥ ${EventRules.MIN_SHARES}）") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Column {
                    listOf(10, 50, 100, 500).forEach { n ->
                        TextButton(onClick = { sharesText = n.toString() }, modifier = Modifier.height(48.dp)) {
                            Text("$n", fontSize = 10.sp, color = GoldLabColors.Gold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { buy(EventSide.UP) }, modifier = Modifier.weight(1f)) {
                    Text(
                        "买涨 " + String.format(Locale.US, "%.3f", upAsk),
                        color = GoldLabColors.Up, fontSize = 13.sp,
                    )
                }
                OutlinedButton(onClick = { buy(EventSide.DOWN) }, modifier = Modifier.weight(1f)) {
                    Text(
                        "买跌 " + String.format(Locale.US, "%.3f", downAsk),
                        color = GoldLabColors.Down, fontSize = 13.sp,
                    )
                }
            }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = GoldLabColors.TextSecondary, fontSize = 11.sp)
            }
        }

        if (games.openEvents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GoldPanel(title = "持仓中（${games.openEvents.size}）") {
                games.openEvents.forEach { p ->
                    Text(
                        String.format(
                            Locale.US, "%s %s %d 张 @ %.3f｜到期 %s｜剩 %d 根",
                            p.symbol, if (p.side == EventSide.UP) "涨" else "跌", p.shares, p.price,
                            java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(p.expireAtMillis))
                                .let { it },
                            (p.expireIndex - (cursor - 1)).coerceAtLeast(0),
                        ),
                        color = GoldLabColors.TextSecondary, fontSize = 10.sp, fontFamily = MonoFamily,
                    )
                }
            }
        }

        if (games.settledEvents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GoldPanel(title = "结算记录（最近 5 条）") {
                games.settledEvents.takeLast(5).reversed().forEach { p ->
                    val won = p.won == true
                    Text(
                        String.format(
                            Locale.US, "%s %s %d 张｜判%s 目标 %.2f → 结算 %.2f（%+.1f bp）｜%+.2f USDT",
                            p.symbol, if (p.side == EventSide.UP) "涨" else "跌", p.shares,
                            if (won) "赢" else "输",
                            p.targetPrice, p.settlementPrice ?: 0.0, p.bpDiff ?: 0.0,
                            p.realizedPnl ?: 0.0,
                        ),
                        color = if (won) GoldLabColors.Up else GoldLabColors.Down,
                        fontSize = 10.sp, fontFamily = MonoFamily,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        NoticeBar(
            "结算价 = 整个周期内全部 1m 根的等权均价（真 TWAP：5m→5 根、1h→60 根、4h→240 根）；结算价 ≥ 目标价判涨。" +
                "标的为虚拟行情回放，不构成任何投资建议。",
            color = GoldLabColors.Info,
        )
    }
}
