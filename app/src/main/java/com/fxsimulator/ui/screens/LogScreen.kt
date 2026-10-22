package com.fxsimulator.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.fxsimulator.ui.components.GoldPanel
import com.fxsimulator.ui.components.MetricRow
import com.fxsimulator.ui.components.NoticeBar
import com.fxsimulator.ui.format.Formatters
import com.fxsimulator.ui.theme.GoldLabColors
import com.fxsimulator.util.GoldLabLog
import com.fxsimulator.viewmodel.GameUiState
import com.fxsimulator.viewmodel.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日志与诊断。
 *
 * 本机无法用 adb 安装调试，所以这里必须让日志“能被拿出来”：
 * 既可以应用内直接看，也可以通过系统分享发给别人。
 */
@Composable
fun LogScreen(vm: GameViewModel, ui: GameUiState) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<File?>(null) }
    var content by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        files = withContext(Dispatchers.IO) { GoldLabLog.listLogs() }
        // 默认选中第一个崩溃记录；没有崩溃就选最新日志
        if (selected == null) selected = files.firstOrNull()
    }

    LaunchedEffect(selected, refresh) {
        content = selected?.let { withContext(Dispatchers.IO) { GoldLabLog.read(it) } } ?: ""
    }

    // 导出到用户选择的位置（SAF，无需存储权限）
    val exporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val src = selected
        if (uri != null && src != null) {
            val text = content
            Thread {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                    vm.showMessage("日志已导出")
                } catch (e: Exception) {
                    vm.showMessage("导出失败：" + e.message, isError = true)
                }
            }.start()
        }
        exporting = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("日志与诊断", color = GoldLabColors.TextPrimary, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(
            "崩溃会自动记录完整堆栈。遇到问题请把日志分享出来。",
            color = GoldLabColors.TextSecondary, fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))

        val crashes = files.count { it.name.startsWith("crash-") }
        if (crashes > 0) {
            NoticeBar("检测到 " + crashes + " 个崩溃记录，请优先查看并分享它们。", color = GoldLabColors.Down)
            Spacer(Modifier.height(8.dp))
        }

        GoldPanel(title = "日志文件（" + files.size + "）") {
            MetricRow("目录", GoldLabLog.directory()?.absolutePath ?: "不可用")
            Spacer(Modifier.height(6.dp))
            if (files.isEmpty()) {
                Text("暂无日志文件", color = GoldLabColors.TextSecondary, fontSize = 12.sp)
            } else {
                for (f in files) {
                    val isCrash = f.name.startsWith("crash-")
                    val isSel = f.absolutePath == selected?.absolutePath
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) GoldLabColors.SurfaceVariant else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSel) GoldLabColors.Gold else GoldLabColors.Outline,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (isCrash) "⚠ " else "") + f.name,
                                color = if (isCrash) GoldLabColors.Down else GoldLabColors.TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                Formatters.bytes(f.length()) + " · " +
                                    Formatters.dateTime(f.lastModified()),
                                color = GoldLabColors.TextDisabled, fontSize = 10.sp,
                            )
                        }
                        OutlinedButton(onClick = { selected = f }) {
                            Text("查看", color = GoldLabColors.Gold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "操作") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val f = selected ?: return@Button
                        try {
                            val uri = FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", f,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "GoldLab 日志 " + f.name)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享日志"))
                        } catch (e: Exception) {
                            vm.showMessage("分享失败：" + e.message, isError = true)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldLabColors.Gold, contentColor = GoldLabColors.Background,
                    ),
                ) { Text("分享", fontSize = 12.sp) }

                OutlinedButton(
                    onClick = {
                        exporting = true
                        exporter.launch("goldlab-" + System.currentTimeMillis() + ".txt")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("导出", color = GoldLabColors.TextPrimary, fontSize = 12.sp) }

                OutlinedButton(
                    onClick = { refresh++ },
                    modifier = Modifier.weight(1f),
                ) { Text("刷新", color = GoldLabColors.TextPrimary, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    GoldLabLog.clear()
                    selected = null
                    content = ""
                    refresh++
                    vm.showMessage("日志已清空")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清空全部日志", color = GoldLabColors.Down, fontSize = 12.sp) }
        }

        Spacer(Modifier.height(10.dp))

        GoldPanel(title = "日志内容" + (selected?.let { " · " + it.name } ?: "")) {
            if (content.isEmpty()) {
                Text("请选择一个日志文件", color = GoldLabColors.TextSecondary, fontSize = 12.sp)
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GoldLabColors.Background)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        content,
                        color = GoldLabColors.TextPrimary,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
