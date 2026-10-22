package com.fxsimulator.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.fxsimulator.data.market.BundledMarketData
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.MetricRow
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.ui.theme.MonoFamily
import com.fxsimulator.viewmodel.GameUiState
import com.fxsimulator.viewmodel.GameViewModel

/** 运行期读到的应用身份。全部来自 PackageManager，不硬编码任何版本字符串。 */
private data class AppIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val debuggable: Boolean,
)

/**
 * 从系统读应用身份。
 *
 * **刻意不硬编码版本号**：硬编码的版本字符串迟早会和 build.gradle.kts 对不上，
 * 到时候「应用信息」页显示一个假的版本号，比没有这一页更糟。
 */
private fun readIdentity(context: Context): AppIdentity = runCatching {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pi.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        pi.versionCode.toLong()
    }
    AppIdentity(
        packageName = context.packageName,
        versionName = pi.versionName ?: "—",
        versionCode = code,
        debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )
}.getOrElse {
    AppIdentity(context.packageName, "—", 0L, false)
}

/**
 * 应用信息。
 *
 * 这一页是给**上架审核**和**用户**看的：包名与版本、内置数据从哪来、许可是什么、
 * 有没有联网、用了哪些第三方组件、免责声明是什么。
 * 信息都放在这里，就不必塞进设置页或首页去挤界面。
 */
@Composable
fun AboutScreen(vm: GameViewModel, ui: GameUiState, nav: NavHostController) {
    val context = LocalContext.current
    val id = remember(context) { readIdentity(context) }
    val months = ui.bundledMonths
    val range = if (months.isEmpty()) {
        "—"
    } else {
        BundledMarketData.monthLabel(months.first()) + " ~ " + BundledMarketData.monthLabel(months.last())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "关于本应用",
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
        Spacer(Modifier.height(6.dp))
        Text("FX模拟器", color = GoldLabColors.Gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(
            "黄金历史行情模拟交易 · 全部资金为虚拟美元",
            color = GoldLabColors.TextSecondary,
            fontSize = 11.5.sp,
        )

        Spacer(Modifier.height(12.dp))
        GoldPanel(title = "版本与构建") {
            MetricRow("应用名称", "FX模拟器")
            MetricRow("包名", id.packageName, valueColor = GoldLabColors.TextPrimary)
            MetricRow("版本", id.versionName + "（versionCode " + id.versionCode + "）")
            MetricRow(
                label = "构建类型",
                value = if (id.debuggable) "debug · 调试签名" else "release",
                valueColor = if (id.debuggable) GoldLabColors.Warning else GoldLabColors.TextPrimary,
            )
            MetricRow("目标 Android", "API 35（Android 15）")
            MetricRow("最低 Android", "API 26（Android 8.0）")
            MetricRow("架构", "arm64-v8a / armeabi-v7a / x86 / x86_64")
        }
        if (id.debuggable) {
            Spacer(Modifier.height(8.dp))
            NoticeBar(
                text = "当前是调试版本，使用调试签名，不能用于正式分发。" +
                    "正式发布需要配置 release 签名 —— 该密钥一旦丢失，这个应用就再也无法更新。",
                color = GoldLabColors.Warning,
            )
        }

        Spacer(Modifier.height(10.dp))
        GoldPanel(title = "内置历史行情") {
            MetricRow("品种", ui.spec.symbol)
            MetricRow("周期", "M1（1 分钟）")
            MetricRow("覆盖范围", range)
            MetricRow("月份数", months.size.toString() + " 个月")
            MetricRow("存储格式", "GLB1 列式二进制（自研，零第三方依赖）")
            Spacer(Modifier.height(6.dp))
            Text(
                "行情时间取自经纪商服务器时间，本应用按 UTC 直接换算，不做时区偏移推断。",
                color = GoldLabColors.TextDisabled,
                fontSize = 10.5.sp,
            )
            MetricRow("价格精度", ui.spec.digits.toString() + " 位小数（内部按整数 tick 存储）")
            Spacer(Modifier.height(6.dp))
            Text(
                "全部行情随包内置，打开即可玩，不需要联网，也不需要先导入任何文件。",
                color = GoldLabColors.TextSecondary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        GoldPanel(title = "隐私与权限") {
            MetricRow("网络权限", "未申请", valueColor = GoldLabColors.Up)
            MetricRow("联网行为", "无", valueColor = GoldLabColors.Up)
            MetricRow("数据上传", "无", valueColor = GoldLabColors.Up)
            MetricRow("数据存储", "仅本机 SQLite")
            MetricRow("设备权限", "无（读写文件走系统文件选择器）")
            Spacer(Modifier.height(6.dp))
            Text(
                "本应用不联网、不采集、不上传任何信息。清空应用数据即可彻底抹除全部记录。",
                color = GoldLabColors.TextSecondary,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        GoldPanel(title = "第三方组件") {
            Text(
                "只使用 Android 官方组件，没有任何第三方 SDK：",
                color = GoldLabColors.TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            MetricRow("界面", "Jetpack Compose + Material 3（Apache-2.0）")
            MetricRow("基础库", "AndroidX Core / Lifecycle / Navigation（Apache-2.0）")
            MetricRow("语言与协程", "Kotlin 标准库 + kotlinx.coroutines（Apache-2.0）")
            MetricRow("K 线图表", "自行绘制，未使用任何图表库")
            MetricRow("广告 / 统计", "无", valueColor = GoldLabColors.Up)
        }

        Spacer(Modifier.height(10.dp))
        GoldPanel(title = "免责声明") {
            Text(
                "本应用是**模拟交易学习工具**，账户余额、盈亏、手数全部是虚拟数值。",
                color = GoldLabColors.TextPrimary,
                fontSize = 11.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "它不连接任何经纪商服务器、不下真实订单、不涉及充值提现、不承诺任何盈利，" +
                    "也不构成投资建议。历史行情只代表过去，模拟结果与真实交易存在本质差异" +
                    "（滑点、流动性、点差变化、心理压力等）。",
                color = GoldLabColors.TextSecondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "本项目中出现的合约规格、杠杆、点差均为可配置的模拟参数，" +
                    "不代表任何真实经纪商。",
                color = GoldLabColors.TextDisabled,
                fontSize = 10.5.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "包名 " + id.packageName + " · " + id.versionName + " (" + id.versionCode + ")",
            color = GoldLabColors.TextDisabled,
            fontSize = 10.sp,
            fontFamily = MonoFamily,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }
}
