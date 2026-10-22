package com.fxsimulator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.fxsimulator.indicators.IndicatorCatalog
import com.fxsimulator.indicators.IndicatorInstance
import com.fxsimulator.indicators.IndicatorPane
import com.fxsimulator.indicators.IndicatorParam
import com.fxsimulator.ui.chart.IndicatorMapper
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameUiState
import com.fxsimulator.viewmodel.GameViewModel

/**
 * 指标管理：自选指标 + 调节参数。
 *
 * 设计取舍：
 * - 参数用「− / 数值 / +」而不是滑块或输入框。周期类参数是整数、步进小，
 *   加减按钮能精确命中且不会弹出键盘挡住半屏图表。
 * - 每一步改动**立即生效并落库**，没有「保存」按钮 —— 调参数要能边看边调。
 * - 顺序可调：叠加线的绘制顺序与副图窗格的排列顺序都跟着它走。
 */
@Composable
fun IndicatorScreen(vm: GameViewModel, ui: GameUiState, nav: NavHostController) {
    var showPicker by remember { mutableStateOf(false) }
    val items = ui.indicatorConfig.items

    if (showPicker) {
        AddIndicatorDialog(
            onPick = { id -> vm.addIndicator(id) },
            onDismiss = { showPicker = false },
        )
    }

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
            Text("指标管理", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { nav.popBackStack() }) {
                Text("返回", color = GoldLabColors.Gold, fontSize = 13.sp)
            }
        }

        NoticeBar(
            "指标只用于模拟学习，不代表对未来走势的预测。" +
                "参数改动立即生效；指标按当前图表周期计算（M15 上的 EMA(20) 就是 20 根 M15）。",
            color = GoldLabColors.Info,
        )
        Spacer(Modifier.height(10.dp))

        if (items.isEmpty()) {
            GoldPanel(title = "当前没有指标") {
                Text(
                    "点下面的「添加指标」，从 17 个传统指标里挑。" +
                        "主图叠加类会画在 K 线上，副图类会各自占一条独立窗格。",
                    color = GoldLabColors.TextSecondary, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        for ((index, inst) in items.withIndex()) {
            val def = IndicatorCatalog.byId(inst.defId) ?: continue
            val params = def.normalize(inst.params)

            GoldPanel(title = (if (def.pane == IndicatorPane.MAIN) "主图 · " else "副图 · ") + IndicatorMapper.label(def, params)) {
                // 顺序 / 删除
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { vm.moveIndicator(index, -1) },
                        enabled = index > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    ) { Text("上移", fontSize = 11.sp, color = GoldLabColors.TextSecondary) }
                    OutlinedButton(
                        onClick = { vm.moveIndicator(index, 1) },
                        enabled = index < items.size - 1,
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    ) { Text("下移", fontSize = 11.sp, color = GoldLabColors.TextSecondary) }
                    OutlinedButton(
                        onClick = { vm.removeIndicator(index) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    ) { Text("删除", fontSize = 11.sp, color = GoldLabColors.Down) }
                }

                if (def.params.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    for (p in def.params) {
                        ParamRow(
                            p = p,
                            current = params[p.key] ?: p.default,
                            onChange = { nv ->
                                val next = LinkedHashMap<String, Double>(params)
                                next[p.key] = nv
                                vm.replaceIndicator(index, IndicatorInstance(def.id, next))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "输出：" + def.outputs.joinToString("、") { it.label } +
                        if (def.fixedRange != null) {
                            "   值域固定 " + fmt(def.fixedRange!!.first) + "~" + fmt(def.fixedRange!!.second)
                        } else "",
                    color = GoldLabColors.TextDisabled, fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { showPicker = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldLabColors.Gold, contentColor = GoldLabColors.Background,
                ),
            ) { Text("添加指标", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = { vm.resetIndicators() }, modifier = Modifier.weight(1f)) {
                Text("恢复默认", color = GoldLabColors.TextPrimary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "口径说明：MACD 柱 = DIF − DEA（不乘 2）；KDJ 用 2/3 旧值 + 1/3 新值的递推平滑；" +
                "OBV 以第一根为 0 起点（只看斜率，绝对值无意义）；" +
                "SAR 是路径依赖指标，初始方向按「首两根收涨即做多」假设。",
            color = GoldLabColors.TextDisabled, fontSize = 10.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}

/** 一行参数：标签 + 减 + 数值 + 加。越界一律夹回合法范围。 */
@Composable
private fun ParamRow(p: IndicatorParam, current: Double, onChange: (Double) -> Unit) {
    val step = if (p.isInteger) maxOf(1.0, p.step) else p.step
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            p.label + "（" + fmt(p.min) + "~" + fmt(p.max) + "）",
            color = GoldLabColors.TextSecondary, fontSize = 11.5.sp,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onChange(p.clamp(current - step)) },
            enabled = current > p.min,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.width(44.dp),
        ) { Text("−", fontSize = 15.sp, color = GoldLabColors.TextPrimary) }
        Text(
            fmt(current),
            color = GoldLabColors.Gold, fontSize = 13.sp, fontFamily = MonoFamily,
            modifier = Modifier
                .width(56.dp)
                .padding(horizontal = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedButton(
            onClick = { onChange(p.clamp(current + step)) },
            enabled = current < p.max,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.width(44.dp),
        ) { Text("+", fontSize = 15.sp, color = GoldLabColors.TextPrimary) }
    }
}

/** 指标选择器：主图叠加与副图分组列出 */
@Composable
private fun AddIndicatorDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GoldLabColors.Surface,
        title = { Text("添加指标", color = GoldLabColors.TextPrimary, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "同一个指标可以添加多次并各自调参数（例如 EMA(20) 与 EMA(50) 同时叠加）。",
                    color = GoldLabColors.TextDisabled, fontSize = 10.5.sp,
                )
                Spacer(Modifier.height(8.dp))

                SectionTitle("主图叠加（画在 K 线上）")
                for (def in IndicatorCatalog.mainDefs()) {
                    OutlinedButton(
                        onClick = { onPick(def.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(def.name, fontSize = 12.sp, color = GoldLabColors.TextPrimary) }
                    Spacer(Modifier.height(4.dp))
                }

                Spacer(Modifier.height(6.dp))
                SectionTitle("独立副图（各自占一条窗格）")
                for (def in IndicatorCatalog.subDefs()) {
                    OutlinedButton(
                        onClick = { onPick(def.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(def.name, fontSize = 12.sp, color = GoldLabColors.TextPrimary) }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = GoldLabColors.Gold) }
        },
    )
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = GoldLabColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(5.dp))
}

/** 整数参数不显示小数点，浮点参数去掉多余的 0 */
private fun fmt(v: Double): String =
    if (!v.isFinite()) "—"
    else if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString()
    else String.format("%.3f", v).trimEnd('0').trimEnd('.')
