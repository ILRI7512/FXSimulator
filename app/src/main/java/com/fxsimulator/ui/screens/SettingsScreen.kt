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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.fxsimulator.domain.model.SymbolSpecs
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.MetricRow
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.viewmodel.GameUiState
import com.fxsimulator.viewmodel.GameViewModel

/**
 * 设置页「模拟合约规格」面板的显示开关。
 *
 * 合约规格按标准内置（黄金 100 盎司/手、外汇 10 万货币单位/手、白银 5000 盎司/手），
 * 不再向用户暴露编辑入口 —— 这些是模拟参数，让用户随便改只会让盈亏/保证金对不上
 * 常识。代码保留在这里，需要恢复时把常量改成 true 即可。
 */
private const val SPEC_PANEL_VISIBLE = false

/**
 * 设置页「广告占位」面板的显示开关。
 *
 * 广告相关的实现与规则**全部保留在代码中**（[com.fxsimulator.data.ads.AdsRepository]、
 * [com.fxsimulator.data.repository.AdStateRepository]、GameViewModel 的 watchRewardedAd /
 * setAdsTestMode，以及 ad_state 数据表），默认只是不在界面上渲染。
 *
 * 原因：本应用未接入任何真实广告 SDK，隐私政策也明确写了「无广告 / 无统计 SDK」，
 * 设置页再摆一块「广告」面板容易让审核把两者对立起来。
 *
 * 需要恢复展示时，把它改成 true 即可，不必改动其它代码。
 */
private const val ADS_PANEL_VISIBLE = false

/** 设置：模拟参数、风控、图表、广告测试通道、数据与重置 */
@Composable
fun SettingsScreen(vm: GameViewModel, ui: GameUiState, nav: NavHostController) {
    var showResetDialog by remember { mutableStateOf(false) }

    var contractSize by remember(ui.spec.contractSize) { mutableStateOf(ui.spec.contractSize.toString()) }
    var leverage by remember(ui.spec.leverage) { mutableStateOf(ui.spec.leverage.toString()) }
    var spread by remember(ui.spec.spread) { mutableStateOf(ui.spec.spread.toString()) }
    var commission by remember(ui.spec.commissionPerLot) { mutableStateOf(ui.spec.commissionPerLot.toString()) }
    var digits by remember(ui.spec.digits) { mutableStateOf(ui.spec.digits.toString()) }
    var minLot by remember(ui.spec.minLot) { mutableStateOf(ui.spec.minLot.toString()) }
    var lotStep by remember(ui.spec.lotStep) { mutableStateOf(ui.spec.lotStep.toString()) }
    var initialBalance by remember { mutableStateOf("10000") }
    var maxDailyLoss by remember(ui.riskMaxDailyLoss) { mutableStateOf(ui.riskMaxDailyLoss.toString()) }
    var maxConsecutive by remember(ui.riskMaxConsecutive) { mutableStateOf(ui.riskMaxConsecutive.toString()) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = GoldLabColors.Surface,
            title = { Text("重置模拟账户", color = GoldLabColors.TextPrimary) },
            text = {
                Text(
                    "资金将恢复为初始值。请选择是否同时清空交易历史——" +
                        "选择「清空」后，统计与净值曲线也会一并归零。",
                    color = GoldLabColors.TextSecondary, fontSize = 12.5.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.resetAccount(true); showResetDialog = false }) {
                    Text("重置并清空历史", color = GoldLabColors.Down)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resetAccount(false); showResetDialog = false }) {
                    Text("仅重置资金，保留历史", color = GoldLabColors.Gold)
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("设置", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // ── 交易品种：唯一可切换的入口。合约规格不再开放编辑，但品种必须能选 ──
        GoldPanel(title = "交易品种") {
            Text(
                "共 " + SymbolSpecs.ALL.size + " 个品种；切换后合约规格与行情一起生效。" +
                    "有持仓时不能切换（不同品种的价格不可比）。",
                color = GoldLabColors.TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            for (sym in SymbolSpecs.ALL) {
                val active = sym.equals(ui.spec.symbol, ignoreCase = true)
                OutlinedButton(
                    onClick = { vm.switchSymbol(sym) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (active) GoldLabColors.Gold else GoldLabColors.TextPrimary,
                    ),
                ) {
                    Text(
                        (if (active) "● " else "") + SymbolSpecs.displayName(sym),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── 杠杆：唯一保留的可调参数。合约大小、点差、佣金一律内置，避免算错钱 ──
        GoldPanel(title = "模拟杠杆") {
            Text(
                "仅用于保证金估算，上限 2000 倍。",
                color = GoldLabColors.TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("杠杆倍数", leverage, { leverage = it }, Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        val lv = leverage.toIntOrNull()
                        if (lv == null || lv < 1) {
                            vm.showMessage("杠杆必须是正整数", isError = true)
                            return@OutlinedButton
                        }
                        // 与 SymbolSpec / SettingsRepository 的内置上限保持一致
                        vm.updateSpec(ui.spec.copy(leverage = lv.coerceIn(1, 2000)))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存杠杆") }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── 合约规格按标准内置，不再向用户暴露编辑入口（见文件顶部 SPEC_PANEL_VISIBLE）──
        if (SPEC_PANEL_VISIBLE) {
            NoticeBar(
                "以下合约规格全部是**模拟参数**，不代表任何真实经纪商的规则。" +
                    "XAUUSD 的合约大小、点差、佣金因经纪商而异，请按需修改。",
                color = GoldLabColors.Info,
            )
            Spacer(Modifier.height(10.dp))

            GoldPanel(title = "模拟合约规格（XAUUSD）") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("合约大小(盎司/手)", contractSize, { contractSize = it }, Modifier.weight(1f))
                    NumField("模拟杠杆", leverage, { leverage = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("点差(价格)", spread, { spread = it }, Modifier.weight(1f))
                    NumField("每手单边佣金", commission, { commission = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("最小手数", minLot, { minLot = it }, Modifier.weight(1f))
                    NumField("手数步进", lotStep, { lotStep = it }, Modifier.weight(1f))
                    NumField("价格小数位", digits, { digits = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val cs = contractSize.toDoubleOrNull()
                        val lv = leverage.toIntOrNull()
                        if (cs == null || cs <= 0 || lv == null || lv <= 0) {
                            vm.showMessage("合约大小与杠杆必须是正数", isError = true)
                            return@Button
                        }
                        vm.updateSpec(
                            ui.spec.copy(
                                contractSize = cs,
                                leverage = lv,
                                spread = spread.toDoubleOrNull() ?: ui.spec.spread,
                                commissionPerLot = commission.toDoubleOrNull() ?: ui.spec.commissionPerLot,
                                digits = (digits.toIntOrNull() ?: ui.spec.digits).coerceIn(0, 5),
                                minLot = minLot.toDoubleOrNull() ?: ui.spec.minLot,
                                lotStep = lotStep.toDoubleOrNull() ?: ui.spec.lotStep,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldLabColors.Gold, contentColor = GoldLabColors.Background,
                    ),
                ) { Text("保存合约规格", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(6.dp))
                MetricRow(
                    "当前 1 手 1.00 价格变动盈亏",
                    String.format("%.2f", ui.spec.contractSize) + " USD",
                )
                MetricRow(
                    "当前保证金（1 手 @ " + ui.candle?.close?.let { String.format("%.2f", it) } + "）",
                    String.format(
                        "%.2f",
                        (ui.candle?.close ?: 0.0) * ui.spec.contractSize / ui.spec.leverage,
                    ) + " USD",
                )
            }

            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "资金与风控") {
            NumField("初始模拟资金(USD)", initialBalance, { initialBalance = it })
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("单日最大亏损(0=不限)", maxDailyLoss, { maxDailyLoss = it }, Modifier.weight(1f))
                NumField("连续亏损警示(0=不限)", maxConsecutive, { maxConsecutive = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val v = initialBalance.toDoubleOrNull()
                        if (v == null || v <= 0) vm.showMessage("初始资金必须为正数", isError = true)
                        else vm.updateInitialBalance(v)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存初始资金", color = GoldLabColors.TextPrimary, fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        vm.updateRisk(
                            maxDailyLoss.toDoubleOrNull() ?: 0.0,
                            maxConsecutive.toIntOrNull() ?: 0,
                            ui.spec.let { 50.0 },
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存风控参数", color = GoldLabColors.TextPrimary, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            MetricRow("当前余额", String.format("%.2f", ui.account.balance) + " USD")
            MetricRow("当前净值", String.format("%.2f", ui.account.equity) + " USD")
            MetricRow("当日已实现盈亏", String.format("%.2f", ui.dailyRealizedPnl) + " USD")
            MetricRow("当前连续亏损", ui.consecutiveLosses.toString() + " 笔")
        }

        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "图表显示") {
            OutlinedButton(
                onClick = { nav.navigate("indicators") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "管理技术指标（已启用 " + ui.indicatorConfig.items.size + " 个）",
                    color = GoldLabColors.Gold, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow("在图上显示持仓止损/止盈线", ui.showSlTp) {
                vm.updateChartOptions(showSlTp = it)
            }
            SwitchRow("阳线空心（MT5 默认样式，关闭则实心）", ui.hollowUpCandles) {
                vm.updateChartOptions(hollowUpCandles = it)
            }
        }

        // ── 广告占位：代码保留，UI 默认不展示（见文件顶部 ADS_PANEL_VISIBLE）──
        if (ADS_PANEL_VISIBLE) {
            Spacer(Modifier.height(10.dp))

            GoldPanel(title = "广告（未接入真实 SDK）") {
                NoticeBar(
                    "第一版没有接入任何真实广告 SDK，因此默认无法展示广告、也不会发放任何广告奖励。",
                    color = GoldLabColors.Warning,
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    "启用本地测试广告通道（非真实广告）",
                    ui.adsLocalTest,
                ) { on ->
                    vm.setAdsTestMode(on)
                }
                Spacer(Modifier.height(6.dp))
                MetricRow("广告能力可用", if (ui.adsAvailable) "可用" else "不可用")
                MetricRow("实现类型", if (ui.adsLocalTest) "本地测试实现" else "官方占位实现（不发奖）")
                MetricRow("今日已观看（有效）", ui.adDay.watched.toString() + " 条")
                MetricRow("今日已发奖励", String.format("%.0f", ui.adDay.granted) + " USD")
                MetricRow(
                    "今日剩余奖励额度",
                    String.format("%.0f", ui.adDay.remainingRewardCap) + " USD",
                )
                MetricRow(
                    "当日免插屏广告",
                    if (ui.adDay.interstitialFree) "已解锁（观看 ≥10 条）" else "未解锁",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.watchRewardedAd() }, modifier = Modifier.fillMaxWidth()) {
                    Text("请求一条激励广告（本地测试）", color = GoldLabColors.TextPrimary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "规则：每条完整观看奖励 200 USD，逐条观看奖励每日合计上限 4000 USD；" +
                        "观看满 10 条解锁当日免插屏广告，满 20 条额外奖励 1000 USD" +
                        "（里程碑奖励为独立奖励，不计入上述逐条上限）。" +
                        "广告失败或中途关闭一律不发奖。奖励只增加余额，不计入交易盈利。",
                    color = GoldLabColors.TextDisabled, fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "数据与账户") {
            OutlinedButton(onClick = { nav.navigate("data") }, modifier = Modifier.fillMaxWidth()) {
                Text("行情与存档（换月份 / 看存档）", color = GoldLabColors.TextPrimary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.navigate("history") }, modifier = Modifier.fillMaxWidth()) {
                Text("查看交易记录", color = GoldLabColors.TextPrimary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { nav.navigate("logs") }, modifier = Modifier.fillMaxWidth()) {
                Text("日志与诊断（崩溃记录）", color = GoldLabColors.Gold, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldLabColors.Down, contentColor = GoldLabColors.Background,
                ),
            ) { Text("重置模拟账户", fontWeight = FontWeight.Bold) }
        }

        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "关于") {
            OutlinedButton(onClick = { nav.navigate("about") }, modifier = Modifier.fillMaxWidth()) {
                Text("应用信息 · 版本 · 数据来源 · 隐私", color = GoldLabColors.TextPrimary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "风险提示：本应用是模拟交易学习工具，所有盈亏均为虚拟数值。" +
                "它不连接任何经纪商服务器，不下真实订单，不承诺盈利，也不能作为投资建议。" +
                "模拟表现与真实交易存在本质差异（滑点、流动性、心理因素等）。",
            color = GoldLabColors.TextDisabled, fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun NumField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = GoldLabColors.TextSecondary, fontSize = 12.5.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
