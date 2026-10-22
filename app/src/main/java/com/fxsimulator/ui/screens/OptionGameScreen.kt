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
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.SymbolSpecs
import com.fxsimulator.domain.options.OptionPricing
import com.fxsimulator.domain.options.OptionQuote
import com.fxsimulator.domain.options.OptionTenor
import com.fxsimulator.domain.options.OptionType
import com.fxsimulator.domain.options.OptionStatus
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
 * 期权（方案第四节）：欧式、现金结算、**只能做买方**。
 *
 * 行权价阶梯按期限百分比生成（平值 ±8），权利金用 Black-Scholes + 实测波动率，
 * 希腊值直接展示 —— 目的是让玩家看到「方向看对也可能亏钱」。
 */
@Composable
fun OptionGameScreen(vm: GameViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val games = vm.games

    var symbol by remember { mutableStateOf("BTCUSDT") }
    var tenor by remember { mutableStateOf(OptionTenor.H1) }
    var bars by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var cursor by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var feed by remember { mutableStateOf<com.fxsimulator.domain.games.GamesFeed?>(null) }
    var startMonth by remember { mutableStateOf("") }
    var allMonths by remember { mutableStateOf<List<String>>(emptyList()) }
    var qtyText by remember { mutableStateOf("0.10") }
    var selectedStrike by remember { mutableStateOf(0.0) }
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
    val spot = remember(visible.size, subTick) { GamesTick.price(visible, subTick) }
    val vol = remember(version, symbol) { games.realizedVol(visible).coerceAtLeast(0.05) }
    val spec = SymbolSpecs.of(symbol)
    val strikes = remember(spot, tenor) {
        if (spot <= 0.0) emptyList() else OptionPricing.strikes(spot, tenor, count = 8)
    }
    val chosen = if (selectedStrike > 0.0) selectedStrike else spot
    val callQuote = remember(chosen, vol, tenor) {
        if (spot <= 0.0) null
        else OptionPricing.quote(
            OptionType.CALL, spot, chosen, vol, tenor, tickSize = spec.spointTick(),
            skewFactor = OptionPricing.skew(OptionType.CALL, spot, chosen),
        )
    }
    val putQuote = remember(chosen, vol, tenor) {
        if (spot <= 0.0) null
        else OptionPricing.quote(
            OptionType.PUT, spot, chosen, vol, tenor, tickSize = spec.spointTick(),
            skewFactor = OptionPricing.skew(OptionType.PUT, spot, chosen),
        )
    }
    val qty = qtyText.toDoubleOrNull() ?: 0.0

    // 剩余可回放的根数。期限必须能在它里面走完，否则到期永远到不了、权利金白付。
    val remainingBars = (bars.size - cursor).coerceAtLeast(0)
    fun tenorFits(t: OptionTenor): Boolean =
        t.minutes + OptionPricing.settlementBars(t) <= remainingBars
    val usableTenors = OptionTenor.entries.filter { tenorFits(it) }

    fun buy(type: OptionType, q: OptionQuote) {
        if (visible.isEmpty()) { message = "行情未就绪"; return }
        if (!tenorFits(tenor)) { message = "剩余回放不足以让 ${tenor.label} 到期，请换更短期限"; return }
        val expireIndex = (cursor - 1) + tenor.minutes
        val err = games.openOption(
            symbol = symbol, quote = q, tenor = tenor, quantity = qty,
            nowMillis = visible.last().timeMillis,
            expireAtMillis = visible.last().timeMillis + tenor.minutes * 60_000L,
            expireIndex = expireIndex,
        )
        message = err ?: String.format(
            Locale.US, "已买入 %s %.2f 张，权利金 %.2f USDT（最大亏损）",
            if (type == OptionType.CALL) "看涨" else "看跌", qty, q.ask * qty,
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
            Text("期权", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    if (loading) "加载中…" else String.format(Locale.US, "%s %.2f", symbol, spot),
                    color = GoldLabColors.TextSecondary, fontSize = 12.sp, fontFamily = MonoFamily,
                )
            }
            Text(
                "已推进 $cursor/${bars.size} 根（已载 ${feed?.loadedCount ?: 0}/${feed?.months?.size ?: 0} 月）｜实测年化波动 " +
                    String.format(Locale.US, "%.1f%%", vol * 100) +
                    "｜无风险利率 " + String.format(Locale.US, "%.0f%%", OptionPricing.RISK_FREE * 100),
                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "K 线（最近 240 根）") {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                GamesChart(GamesTick.displayBars(visible, subTick), SymbolSpecs.of(symbol).digits, Modifier.fillMaxSize(), maxBars = chartBarsFor(tenor.minutes))
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
            canSkipToSettle = games.openOptions.isNotEmpty(),
            onTogglePlay = { playing = !playing },
            onSpeed = { speed = it },
            onSkipToSettle = {
                val next = games.openOptions.minOfOrNull { it.expireIndex } ?: -1
                if (next + 1 > cursor) {
                    cursor = (next + 1).coerceAtMost(bars.size)
                    games.onBars(bars.subList(0, cursor))
                    version++
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "期限") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                OptionTenor.entries.forEach { t ->
                    val fits = tenorFits(t)
                    TextButton(
                        onClick = { if (fits) { tenor = t; selectedStrike = 0.0 } else { message = "剩余回放不够 ${t.label}，无法到期" } },
                    ) {
                        Text(
                            t.label,
                            color = when {
                                t == tenor -> GoldLabColors.Gold
                                !fits -> GoldLabColors.TextDisabled
                                else -> GoldLabColors.TextSecondary
                            },
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            Text(
                "剩余可回放 $remainingBars 根｜可用期限：" +
                    (if (usableTenors.isEmpty()) "无（请先等行情或换月份）" else usableTenors.joinToString("/") { it.label }),
                color = if (usableTenors.isEmpty()) GoldLabColors.Down else GoldLabColors.TextDisabled,
                fontSize = 10.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "行权价阶梯（平值 ±8）") {
            Text(
                "间距 " + String.format(Locale.US, "%.2f%%", tenor.strikeSpacingPercent * 100) + "｜点选一档",
                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
            )
            for (k in strikes) {
                val c = OptionPricing.quote(
                    OptionType.CALL, spot, k, vol, tenor, spec.spointTick(),
                    OptionPricing.skew(OptionType.CALL, spot, k),
                )
                val pp = OptionPricing.quote(
                    OptionType.PUT, spot, k, vol, tenor, spec.spointTick(),
                    OptionPricing.skew(OptionType.PUT, spot, k),
                )
                TextButton(
                    onClick = { selectedStrike = k },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        String.format(
                            Locale.US, "%s %10.2f    C %8.2f    P %8.2f",
                            if (k == chosen) "●" else "○", k, c.ask, pp.ask,
                        ),
                        color = if (k == chosen) GoldLabColors.Gold else GoldLabColors.TextSecondary,
                        fontSize = 10.sp, fontFamily = MonoFamily,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "下单（平值附近）") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text("张数（≥0.01）") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Column {
                    listOf(0.01, 0.1, 1.0).forEach { q ->
                        TextButton(onClick = { qtyText = q.toString() }, modifier = Modifier.height(48.dp)) {
                            Text("$q", fontSize = 10.sp, color = GoldLabColors.Gold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { callQuote?.let { buy(OptionType.CALL, it) } }, modifier = Modifier.weight(1f)) {
                    Text(
                        "买看涨 " + String.format(Locale.US, "%.2f", callQuote?.ask ?: 0.0),
                        color = GoldLabColors.Up, fontSize = 12.sp,
                    )
                }
                OutlinedButton(onClick = { putQuote?.let { buy(OptionType.PUT, it) } }, modifier = Modifier.weight(1f)) {
                    Text(
                        "买看跌 " + String.format(Locale.US, "%.2f", putQuote?.ask ?: 0.0),
                        color = GoldLabColors.Down, fontSize = 12.sp,
                    )
                }
            }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = GoldLabColors.TextSecondary, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        GoldPanel(title = "希腊值（看涨 / 看跌）") {
            if (callQuote != null && putQuote != null) {
                Text(
                    String.format(Locale.US, "delta   %+.3f / %+.3f", callQuote.delta, putQuote.delta),
                    color = GoldLabColors.TextSecondary, fontSize = 11.sp, fontFamily = MonoFamily,
                )
                Text(
                    String.format(Locale.US, "theta   %+.2f / %+.2f 每天", callQuote.thetaDaily, putQuote.thetaDaily),
                    color = GoldLabColors.TextSecondary, fontSize = 11.sp, fontFamily = MonoFamily,
                )
                Text(
                    String.format(Locale.US, "vega    %.2f / %.2f 每 1%% 波动率", callQuote.vegaPerPct, putQuote.vegaPerPct),
                    color = GoldLabColors.TextSecondary, fontSize = 11.sp, fontFamily = MonoFamily,
                )
                Text(
                    String.format(Locale.US, "IV      %.1f%% / %.1f%%", callQuote.iv * 100, putQuote.iv * 100),
                    color = GoldLabColors.TextDisabled, fontSize = 10.sp, fontFamily = MonoFamily,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "人话：标的价格动 100，这张期权约动 " +
                        String.format(Locale.US, "%.0f", callQuote.delta * 100) +
                        "；什么都不做每天损耗 " + String.format(Locale.US, "%.2f", -callQuote.thetaDaily) + "。",
                    color = GoldLabColors.TextDisabled, fontSize = 10.sp,
                )
            } else {
                Text("行情未就绪", color = GoldLabColors.TextDisabled, fontSize = 11.sp)
            }
        }

        if (games.openOptions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GoldPanel(title = "持仓中（${games.openOptions.size}）") {
                games.openOptions.forEach { p ->
                    Text(
                        String.format(
                            Locale.US, "%s %s %.2f 张 K=%.2f %s｜剩 %d 根",
                            p.symbol, if (p.type == OptionType.CALL) "看涨" else "看跌",
                            p.quantity, p.strike, p.tenor.label,
                            (p.expireIndex - (cursor - 1)).coerceAtLeast(0),
                        ),
                        color = GoldLabColors.TextSecondary, fontSize = 10.sp, fontFamily = MonoFamily,
                    )
                }
            }
        }

        if (games.settledOptions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GoldPanel(title = "结算记录（最近 5 条）") {
                games.settledOptions.takeLast(5).reversed().forEach { p ->
                    val pnl = p.realizedPnl ?: 0.0
                    Text(
                        String.format(
                            Locale.US, "%s %s K=%.2f %.2f 张｜结算 %.2f｜兑付 %.2f − 成本 %.2f = %+.2f",
                            p.symbol, if (p.type == OptionType.CALL) "C" else "P", p.strike, p.quantity,
                            p.settlementPrice ?: 0.0, p.payout ?: 0.0, p.cost, pnl,
                        ),
                        color = if (pnl >= 0) GoldLabColors.Up else GoldLabColors.Down,
                        fontSize = 10.sp, fontFamily = MonoFamily,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        NoticeBar(
            "欧式、到期统一现金结算；只能做买方，最大亏损 = 权利金。" +
                "结算价用到期前 min(30, 期限) 根 1m 均价，行权费 = min(结算价值×0.015%, 实值额×10%)。",
            color = GoldLabColors.Info,
        )
    }
}

/** 该品种的最小价格变动（BTC 5 USDT、ETH 0.2 USDT，与方案 4.1 一致）。 */
private fun com.fxsimulator.domain.model.SymbolSpec.spointTick(): Double = when (symbol.uppercase()) {
    "BTCUSDT" -> 5.0
    "ETHUSDT" -> 0.2
    else -> point
}
