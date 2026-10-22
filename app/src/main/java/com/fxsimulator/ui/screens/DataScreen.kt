package com.fxsimulator.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.fxsimulator.data.market.BundledMarketData
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.MetricRow
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameUiState
import com.fxsimulator.viewmodel.GameViewModel

/**
 * 行情与存档。
 *
 * **没有导入功能，也没有本地目录，更没有 DEMO 数据。**
 * 行情随包内置（33 个月真实黄金 M1），打开就能玩 —— 这才是这个应用该有的样子：
 * 一个游戏不该要求玩家先准备上百 MB 的 CSV 才能开始。
 *
 * 这一页只剩两件事：**换月份**、**看/管理这个月份的存档**。
 */
@Composable
fun DataScreen(vm: GameViewModel, ui: GameUiState, nav: NavHostController) {
    var monthsOpen by remember { mutableStateOf(false) }
    val rs = ui.replay
    val bm = ui.bookmark

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "行情与存档",
                color = GoldLabColors.TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "← 返回",
                color = GoldLabColors.Gold,
                fontSize = 12.sp,
                modifier = Modifier.clickable { nav.popBackStack() }.padding(6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "内置历史行情") {
            Text(
                "应用内置了 " + ui.bundledMonths.size + " 个月的真实黄金 M1 行情。" +
                    "不需要导入任何文件，也不需要联网。",
                color = GoldLabColors.TextSecondary,
                fontSize = 11.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            MetricRow("当前行情", rs.sourceName.ifEmpty { "—" })
            MetricRow("K 线根数", if (rs.totalBars > 0) rs.totalBars.toString() + " 根 M1" else "—")
            MetricRow(
                label = "回放进度",
                value = if (rs.totalBars > 0) (rs.index + 1).toString() + " / " + rs.totalBars else "—",
            )
            Spacer(Modifier.height(10.dp))
            Box {
                Button(
                    onClick = { monthsOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldLabColors.Gold,
                        contentColor = GoldLabColors.Background,
                    ),
                ) {
                    Text(
                        "选择月份（共 " + ui.bundledMonths.size + " 个月）",
                        fontWeight = FontWeight.Bold,
                    )
                }
                DropdownMenu(expanded = monthsOpen, onDismissRequest = { monthsOpen = false }) {
                    // 新的月份排在前面：多数人更关心最近一段行情
                    for (m in ui.bundledMonths.reversed()) {
                        val label = BundledMarketData.monthLabel(m)
                        val current = rs.sourceName == label
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (current) label + "　（当前）" else label,
                                    color = if (current) GoldLabColors.Gold else GoldLabColors.TextPrimary,
                                    fontSize = 13.sp,
                                )
                            },
                            onClick = {
                                monthsOpen = false
                                vm.useBundledMonth(m)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "回放存档") {
            if (bm == null) {
                Text(
                    "这个月份还没有存档。随便走几根再退出，进度会自动记下来，下次打开就接着看。",
                    color = GoldLabColors.TextSecondary,
                    fontSize = 11.5.sp,
                )
            } else {
                Text(
                    "每个月份各存一份，互不覆盖 —— 在 2024 年 3 月看到第 8000 根，" +
                        "切到 2026 年 6 月再切回来，两边都还在原处。",
                    color = GoldLabColors.TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                MetricRow("存档来源", bm.sourceName ?: "—")
                MetricRow("停在第", (bm.barIndex + 1).toString() + " 根")
                MetricRow("回放速度", bm.speed.toString() + "x")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.resumeFromBookmark() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GoldLabColors.Gold,
                            contentColor = GoldLabColors.Background,
                        ),
                    ) { Text("回到存档位置", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { vm.clearBookmark() },
                        modifier = Modifier.weight(1f),
                    ) { Text("删除存档", color = GoldLabColors.TextSecondary, fontSize = 12.sp) }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Spacer(Modifier.height(20.dp))
    }
}
