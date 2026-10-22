package com.fxsimulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.navigation.NavHostController
import com.fxsimulator.domain.money.PnlCalculator
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.Timeframe
import com.fxsimulator.domain.model.TradeDirection
import com.fxsimulator.engine.FillModel
import com.fxsimulator.indicators.IndicatorCatalog
import com.fxsimulator.ui.chart.CandleChart
import com.fxsimulator.ui.chart.IndicatorMapper
import com.fxsimulator.ui.components.DividerLine
import com.fxsimulator.ui.components.Pill
import com.fxsimulator.ui.format.Formatters
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameUiState
import com.fxsimulator.viewmodel.GameViewModel

/** 可选倍速。10x 是上限 —— 每根 M1 只有 100ms 预算，再快手机也追不上。 */
private val SPEED_OPTIONS = listOf(1, 2, 5, 10)

/** 指标工具箱里的快速添加入口；完整 17 个在「指标管理」页。 */
/** 快速添加用的常用指标；猜涨跌页的工具箱也读这一份，避免两处列表各自漂移 */
internal val QUICK_INDICATORS = listOf("ema", "boll", "macd", "rsi", "kdj", "atr")

/**
 * 「交易」页 —— 按 MT5 的方式组织：
 *
 *   ┌ 顶栏：品种 · 周期 · 当前价 · 行情时间
 *   ├ 账户条：净值 / 浮动 / 余额 / 保证金 / 可用
 *   ├ 回放条：播放·停止 + 倍速下拉 + 右侧指标工具箱
 *   ├ K 线图（占满剩余空间，指标图例浮在左上角）
 *   └ 下单面板：手数 −/+ 与 SELL / BUY 两个大按钮（各自显示成交价）
 *
 * 页面**不滚动**：图表吃满剩余高度，控件都挤在上下两条里。
 * 点 SELL / BUY 打开 MT5 式的「新订单」确认框（可填止损止盈）。
 */
@Composable
fun TradeTabScreen(vm: GameViewModel, ui: GameUiState, nav: NavHostController) {
    val spec = ui.spec
    val bid = ui.candle?.close ?: 0.0
    val ask = if (bid > 0.0) com.fxsimulator.engine.FillModel.ask(bid, spec.spread) else 0.0

    var lotText by remember(ui.defaultLot) { mutableStateOf(String.format("%.2f", ui.defaultLot)) }
    var showOrder by remember { mutableStateOf(false) }
    var orderDirection by remember { mutableStateOf(TradeDirection.BUY) }
    var showPositions by remember { mutableStateOf(false) }
    var showTimeframePicker by remember { mutableStateOf(false) }
    // 十字光标指向的 K 线：非 null 时图例换成 OHLC 读数（MT5 行为）
    var crosshairBar by remember { mutableStateOf<Candle?>(null) }

    val lot = lotText.toDoubleOrNull()

    Column(
        Modifier
            .fillMaxSize()
            .background(GoldLabColors.Background)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // ---------------- 顶栏 ----------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec.symbol,
                    color = GoldLabColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                // 周期选择（MT5 里也是在符号旁边点开周期列表）
                Text(
                    ui.timeframe.label + " ▾",
                    color = GoldLabColors.Gold,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(GoldLabColors.SurfaceVariant)
                        .border(1.dp, GoldLabColors.Outline, RoundedCornerShape(6.dp))
                        .clickable { showTimeframePicker = true }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Formatters.price(ui.candle?.close, spec.digits),
                    color = GoldLabColors.Gold,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = MonoFamily,
                )
                Text(
                    Formatters.dateTime(ui.candle?.timeMillis),
                    color = GoldLabColors.TextSecondary,
                    fontSize = 9.5.sp,
                    fontFamily = MonoFamily,
                )
            }
        }

        Spacer(Modifier.height(5.dp))

        // ---------------- 账户条 ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(GoldLabColors.Surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TinyStat("净值", Formatters.money(ui.account.equity), GoldLabColors.Gold)
            TinyStat(
                "浮动",
                Formatters.signedMoney(ui.account.floatingPnl),
                if (ui.account.floatingPnl >= 0) GoldLabColors.Up else GoldLabColors.Down,
            )
            TinyStat("余额", Formatters.money(ui.account.balance), GoldLabColors.TextPrimary)
            TinyStat("保证金", Formatters.money(ui.account.margin), GoldLabColors.TextSecondary)
            TinyStat("可用", Formatters.money(ui.account.freeMargin), GoldLabColors.TextSecondary)
        }

        Spacer(Modifier.height(5.dp))

        // ---------------- 回放条：播放 + 速度下拉 + 指标工具箱 ----------------
        var speedMenu by remember { mutableStateOf(false) }
        var indicatorMenu by remember { mutableStateOf(false) }
        val indicatorCount = ui.indicatorConfig.items.size

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // --- 播放 / 停止 ---
            Button(
                onClick = { vm.togglePlay() },
                modifier = Modifier.weight(1.2f).height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ui.replay.playing) GoldLabColors.Down else GoldLabColors.Up,
                    contentColor = GoldLabColors.Background,
                ),
            ) {
                Text(
                    if (ui.replay.playing) "停止" else "播放",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // --- 倍速：下拉选择，上限 10x ---
            Box {
                OutlinedButton(
                    onClick = { speedMenu = true },
                    modifier = Modifier.height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = GoldLabColors.GoldDim),
                ) {
                    Text(
                        ui.replay.speed.toString() + "x  ▾",
                        color = GoldLabColors.Gold, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    )
                }
                DropdownMenu(
                    expanded = speedMenu,
                    onDismissRequest = { speedMenu = false },
                    containerColor = GoldLabColors.Surface,
                ) {
                    for (s in SPEED_OPTIONS) {
                        val on = ui.replay.speed == s
                        DropdownMenuItem(
                            text = {
                                Text(
                                    s.toString() + "x" + (if (s == SPEED_OPTIONS.last()) "（上限）" else ""),
                                    color = if (on) GoldLabColors.Gold else GoldLabColors.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            trailingIcon = {
                                if (on) Text("✓", color = GoldLabColors.Gold, fontSize = 12.sp)
                            },
                            onClick = { vm.setSpeed(s); speedMenu = false },
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // --- 指标工具箱（原倍速按钮占的地方）---
            Box {
                OutlinedButton(
                    onClick = { indicatorMenu = true },
                    modifier = Modifier.height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                ) {
                    Text(
                        "指标 " + indicatorCount + "  ▾",
                        color = if (indicatorCount > 0) GoldLabColors.TextPrimary else GoldLabColors.TextSecondary,
                        fontSize = 11.5.sp,
                    )
                }
                DropdownMenu(
                    expanded = indicatorMenu,
                    onDismissRequest = { indicatorMenu = false },
                    containerColor = GoldLabColors.Surface,
                ) {
                    // 当前启用：点一下移除
                    if (ui.indicatorConfig.items.isEmpty()) {
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
                                Text("当前启用（点按移除）", color = GoldLabColors.TextDisabled, fontSize = 10.sp)
                            },
                            enabled = false,
                            onClick = {},
                        )
                        ui.indicatorConfig.items.forEachIndexed { i, inst ->
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

                    // 一键添加常用指标
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
                            indicatorMenu = false
                            nav.navigate("indicators")
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        // ---------------- 图表（占满剩余）+ 指标图例叠加 ----------------
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            CandleChart(
                bars = ui.bars,
                digits = spec.digits,
                overlays = ui.overlays,
                positions = ui.positions,
                showSlTp = ui.showSlTp,
                hollowUp = ui.hollowUpCandles,
                subPanes = ui.subPanes,
                formingIndex = ui.bars.lastIndex,
                profitOf = { p ->
                    val b = ui.candle?.close ?: 0.0
                    if (b <= 0.0) 0.0
                    else PnlCalculator.floating(
                        ui.spec, p.direction, p.volume, p.openPrice,
                        FillModel.exitPrice(p.direction, b, ui.spec.spread),
                    )
                },
                onCrosshairChange = { crosshairBar = it },
                modifier = Modifier.fillMaxSize(),
            )
            IndicatorLegend(ui, crosshairBar, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (ui.barsLoading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(GoldLabColors.Background.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "正在聚合 " + ui.timeframe.label + " 数据…",
                        color = GoldLabColors.Gold,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ---------------- 下单面板（MT5 一键交易）----------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手数", color = GoldLabColors.TextSecondary, fontSize = 10.5.sp)
                Spacer(Modifier.padding(horizontal = 3.dp))
                OutlinedButton(
                    onClick = {
                        val v = (lot ?: spec.minLot) - spec.lotStep
                        lotText = String.format("%.2f", spec.normalizeLot(v))
                    },
                    modifier = Modifier.height(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 0.dp,
                    ),
                ) { Text("−", fontSize = 14.sp, color = GoldLabColors.TextPrimary) }
                Text(
                    "  " + (lotText.ifEmpty { "0.00" }) + "  ",
                    color = GoldLabColors.TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(
                    onClick = {
                        val v = (lot ?: spec.minLot) + spec.lotStep
                        lotText = String.format("%.2f", spec.normalizeLot(v))
                    },
                    modifier = Modifier.height(30.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 0.dp,
                    ),
                ) { Text("+", fontSize = 14.sp, color = GoldLabColors.TextPrimary) }
            }
            TextButton(onClick = { showPositions = true }) {
                Text(
                    "持仓 " + ui.positions.size + " ▸",
                    color = if (ui.positions.isEmpty()) GoldLabColors.TextSecondary else GoldLabColors.Gold,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // SELL：按 BID 成交
            Button(
                onClick = {
                    orderDirection = TradeDirection.SELL
                    showOrder = true
                },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldLabColors.Down,
                    contentColor = GoldLabColors.Background,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SELL", fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Text(
                        Formatters.price(bid, spec.digits),
                        fontSize = 12.sp,
                        fontFamily = MonoFamily,
                    )
                }
            }
            // BUY：按 ASK 成交
            Button(
                onClick = {
                    orderDirection = TradeDirection.BUY
                    showOrder = true
                },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldLabColors.Up,
                    contentColor = GoldLabColors.Background,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BUY", fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Text(
                        Formatters.price(ask, spec.digits),
                        fontSize = 12.sp,
                        fontFamily = MonoFamily,
                    )
                }
            }
        }

    }

    // ---------------- MT5 式「新订单」确认框 ----------------
    if (showOrder) {
        OrderConfirmDialog(
            vm = vm,
            ui = ui,
            direction = orderDirection,
            lotText = lotText,
            onLotChange = { lotText = it },
            bid = bid,
            onClose = { showOrder = false },
        )
    }

    // ---------------- 持仓管理 ----------------
    if (showPositions) {
        PositionsDialog(vm = vm, ui = ui, bid = bid, onClose = { showPositions = false })
    }

    if (showTimeframePicker) {
        TimeframePickerDialog(
            current = ui.timeframe,
            onPick = { tf ->
                vm.setTimeframe(tf)
                showTimeframePicker = false
            },
            onClose = { showTimeframePicker = false },
        )
    }
}

/** 周期选择：M1 / M5 / M15 / M30 / H1 / H4 / D1 */
@Composable
private fun TimeframePickerDialog(
    current: Timeframe,
    onPick: (Timeframe) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = GoldLabColors.Surface,
        title = {
            Text(
                "图表周期",
                color = GoldLabColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                for (tf in Timeframe.entries) {
                    val selected = tf == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) GoldLabColors.GoldDim else Color.Transparent)
                            .clickable { onPick(tf) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tf.label,
                            color = if (selected) GoldLabColors.Gold else GoldLabColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            tf.minutes.toString() + " 分钟" +
                                (if (tf == current) "  · 当前" else ""),
                            color = GoldLabColors.TextDisabled,
                            fontSize = 10.sp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "均由 M1 历史数据现场聚合，不会补造缺失的分钟。" +
                        "D1 受回扫上限影响，根数会少于其他周期。",
                    color = GoldLabColors.TextDisabled,
                    fontSize = 9.5.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("关闭", color = GoldLabColors.TextSecondary)
            }
        },
    )
}

/** 顶栏下的一行小指标 */
@Composable
private fun TinyStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = GoldLabColors.TextDisabled, fontSize = 8.5.sp)
        Text(
            value,
            color = valueColor,
            fontSize = 11.sp,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 指标图例：浮在图表左上角，不占布局高度 */
@Composable
private fun IndicatorLegend(
    ui: GameUiState,
    crosshairBar: Candle? = null,
    modifier: Modifier = Modifier,
) {
    // 没有任何内容时不渲染 —— 否则图上会糊一块空底色，看着像渲染坏了
    val overlayCount = ui.overlays.count { o -> o.values.any { it.isFinite() } }
    if (crosshairBar == null && overlayCount == 0 && ui.indicatorBarsAvailable >= 50) return
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(GoldLabColors.Background.copy(alpha = 0.62f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        // 长按拖出十字光标时，顶部换成该根 K 线的 OHLC 读数（MT5 的图例行为）
        crosshairBar?.let { c ->
            Text(
                Formatters.dateTime(c.timeMillis),
                color = GoldLabColors.TextSecondary,
                fontSize = 9.sp,
                fontFamily = MonoFamily,
            )
            Text(
                "O " + Formatters.price(c.open, ui.spec.digits) +
                    "  H " + Formatters.price(c.high, ui.spec.digits) +
                    "  L " + Formatters.price(c.low, ui.spec.digits) +
                    "  C " + Formatters.price(c.close, ui.spec.digits),
                color = if (c.close >= c.open) GoldLabColors.Up else GoldLabColors.Down,
                fontSize = 9.sp,
                fontFamily = MonoFamily,
            )
        }
        // 主图叠加指标：每条线一行，用线自己的颜色，和图上对得上。
        // 副图的读数由副图自己画在窗格左上角，这里不重复。
        if (ui.indicatorBarsAvailable in 1 until 50) {
            Text(
                "指标数据不足（" + ui.indicatorBarsAvailable + " 根）",
                color = GoldLabColors.Warning,
                fontSize = 9.sp,
            )
        } else {
            for (o in ui.overlays) {
                val last = o.values.lastOrNull { it.isFinite() }
                if (last == null) continue
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

/** MT5「新订单」：方向、手数、成交价、止损止盈、保证金，确认后下单 */
@Composable
private fun OrderConfirmDialog(
    vm: GameViewModel,
    ui: GameUiState,
    direction: TradeDirection,
    lotText: String,
    onLotChange: (String) -> Unit,
    bid: Double,
    onClose: () -> Unit,
) {
    val spec = ui.spec
    var slText by remember { mutableStateOf("") }
    var tpText by remember { mutableStateOf("") }

    val lot = lotText.toDoubleOrNull()
    val sl = slText.trim().ifEmpty { null }?.toDoubleOrNull()
    val tp = tpText.trim().ifEmpty { null }?.toDoubleOrNull()
    // 买入成交在 ASK，卖出成交在 BID
    val execPrice = com.fxsimulator.engine.FillModel.entryPrice(direction, bid, spec.spread)
    val margin = if (lot != null && lot > 0) {
        com.fxsimulator.domain.money.MarginCalculator.requiredMargin(spec, lot, execPrice)
    } else {
        0.0
    }
    val dirColor = if (direction.isBuy) GoldLabColors.Up else GoldLabColors.Down

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = GoldLabColors.Surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (direction.isBuy) "买入 " else "卖出 ",
                    color = dirColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(spec.symbol, color = GoldLabColors.TextPrimary, fontSize = 16.sp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("成交价", color = GoldLabColors.TextSecondary, fontSize = 12.sp)
                    Text(
                        Formatters.price(execPrice, spec.digits),
                        color = GoldLabColors.Gold,
                        fontSize = 15.sp,
                        fontFamily = MonoFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = lotText,
                        onValueChange = onLotChange,
                        label = { Text("手数", fontSize = 10.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            val v = (lot ?: spec.minLot) - spec.lotStep
                            onLotChange(String.format("%.2f", spec.normalizeLot(v)))
                        },
                        modifier = Modifier.height(52.dp),
                    ) { Text("−", fontSize = 16.sp, color = GoldLabColors.TextPrimary) }
                    OutlinedButton(
                        onClick = {
                            val v = (lot ?: spec.minLot) + spec.lotStep
                            onLotChange(String.format("%.2f", spec.normalizeLot(v)))
                        },
                        modifier = Modifier.height(52.dp),
                    ) { Text("+", fontSize = 16.sp, color = GoldLabColors.TextPrimary) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = slText,
                        onValueChange = { slText = it },
                        label = { Text("止损 SL", fontSize = 10.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = tpText,
                        onValueChange = { tpText = it },
                        label = { Text("止盈 TP", fontSize = 10.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                DialogRow("预估保证金", Formatters.money(margin) + " USD")
                DialogRow("可用保证金", Formatters.money(ui.account.freeMargin) + " USD", GoldLabColors.Gold)
                DialogRow(
                    "1.00 价格变动盈亏",
                    Formatters.money(spec.contractSize * (lot ?: 0.0)) + " USD",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "成交规则：买入按 ASK(BID+" + String.format("%.2f", spec.spread) + ")、卖出按 BID 成交。",
                    color = GoldLabColors.TextDisabled, fontSize = 9.5.sp,
                )
                Text(
                    "同一根 K 线内止损与止盈都被触及时，因只有 OHLC 无法判断先后，一律先触发止损。",
                    color = GoldLabColors.TextDisabled, fontSize = 9.5.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val l = lot
                    if (l == null || l <= 0) {
                        vm.showMessage("请输入有效手数", isError = true)
                    } else {
                        vm.openOrder(direction, l, sl, tp)
                        onClose()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = dirColor,
                    contentColor = GoldLabColors.Background,
                ),
            ) {
                Text(
                    if (direction.isBuy) "确认买入" else "确认卖出",
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text("取消", color = GoldLabColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun DialogRow(label: String, value: String, valueColor: Color = GoldLabColors.TextPrimary) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = GoldLabColors.TextSecondary, fontSize = 11.5.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontFamily = MonoFamily)
    }
}

/** 持仓管理：逐笔平仓、逐笔改止损止盈、一键全平 */
@Composable
private fun PositionsDialog(
    vm: GameViewModel,
    ui: GameUiState,
    bid: Double,
    onClose: () -> Unit,
) {
    val spec = ui.spec

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = GoldLabColors.Surface,
        title = {
            Text(
                "持仓（" + ui.positions.size + "）",
                color = GoldLabColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            if (ui.positions.isEmpty()) {
                Text("当前没有持仓", color = GoldLabColors.TextSecondary, fontSize = 12.sp)
            } else {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (p in ui.positions) {
                        key(p.orderId) {
                            var slEdit by remember { mutableStateOf(p.stopLoss?.let { String.format("%.3f", it) } ?: "") }
                            var tpEdit by remember { mutableStateOf(p.takeProfit?.let { String.format("%.3f", it) } ?: "") }
                            val floating = com.fxsimulator.domain.money.PnlCalculator.floating(
                                spec, p.direction, p.volume, p.openPrice,
                                com.fxsimulator.engine.FillModel.exitPrice(p.direction, bid, spec.spread),
                            )

                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GoldLabColors.SurfaceVariant)
                                    .padding(9.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        p.direction.label + " " + Formatters.lot(p.volume) + " 手",
                                        color = if (p.direction.isBuy) GoldLabColors.Up else GoldLabColors.Down,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        Formatters.signedMoney(floating),
                                        color = if (floating >= 0) GoldLabColors.Up else GoldLabColors.Down,
                                        fontSize = 13.5.sp,
                                        fontFamily = MonoFamily,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    "开仓 " + Formatters.price(p.openPrice, spec.digits) +
                                        "   " + Formatters.dateTime(p.openTimeMillis),
                                    color = GoldLabColors.TextSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = MonoFamily,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = slEdit,
                                        onValueChange = { slEdit = it },
                                        label = { Text("SL", fontSize = 9.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = tpEdit,
                                        onValueChange = { tpEdit = it },
                                        label = { Text("TP", fontSize = 9.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = { vm.closePosition(p.orderId) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = GoldLabColors.Down,
                                            contentColor = GoldLabColors.Background,
                                        ),
                                        modifier = Modifier.height(34.dp),
                                    ) { Text("平仓", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = {
                                            vm.modifyPosition(
                                                p.orderId,
                                                slEdit.trim().ifEmpty { null }?.toDoubleOrNull(),
                                                tpEdit.trim().ifEmpty { null }?.toDoubleOrNull(),
                                            )
                                        },
                                        modifier = Modifier.height(34.dp),
                                    ) { Text("改止损止盈", color = GoldLabColors.TextPrimary, fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    vm.closeAll()
                    onClose()
                },
                enabled = ui.positions.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldLabColors.Down,
                    contentColor = GoldLabColors.Background,
                ),
            ) { Text("全部平仓", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onClose) {
                Text("关闭", color = GoldLabColors.TextSecondary)
            }
        },
    )
}
