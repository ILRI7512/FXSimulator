package com.fxsimulator.tools

import com.fxsimulator.data.market.glb1.Glb1Writer
import com.fxsimulator.domain.model.Candle
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Binance 1m K 线 CSV → GLB1 资产的导入器（开发期工具，不是业务逻辑）。
 *
 * ## 为什么要做成测试
 * 转换脚本如果游离在工程外，下次换数据时没人知道它存在，也不知道它当时怎么处理脏数据。
 * 放在测试里：参数、口径、校验全在代码里，能重复执行、能被 review。
 *
 * ## 怎么跑
 * ```
 * .\gradlew.bat :app:testDebugUnitTest --tests "com.fxsimulator.tools.BinanceCsvImporter" `
 *   -Dgoldlab.importBinance=D:\19168\数据_extract
 * ```
 * 不传属性时**直接跳过**（返回），不会污染常规测试。
 *
 * ## 口径（必须与真实数据一致，不能凑）
 * - `timeMillis` = CSV 的 `open_time`（UTC 毫秒，不做时区推断）
 * - `open/high/low/close` 原样
 * - `volume` = CSV 的 `count`（成交笔数）。本项目 `volume` 的语义是 tick 量，
 *   Binance 的 `count` 与之同口径；不拿基础币成交量冒充，否则指标口径会错
 * - `spread` = 0（源数据没有逐笔点差，App 用 [com.fxsimulator.domain.model.SymbolSpecs] 的兜底值）
 * - 同一月份若同时存在月文件和日文件（例如 2026-10），合并后按时间去重排序
 */
class BinanceCsvImporter {

    private val symbols = listOf("BTCUSDT", "ETHUSDT")

    /** 价格小数位：BTC/ETH 在 Binance 都是 2 位（与 [SymbolSpecs] 保持一致）。 */
    private val digits = 2

    private val nameRegex = Regex("""^([A-Z]+)-1m-(\d{4}-\d{2})(?:-(\d{2}))?\.csv$""")

    @Test
    fun import_binance_csv_into_assets() {
        val rootPath = System.getProperty("goldlab.importBinance")
        if (rootPath.isNullOrBlank()) {
            println("IMPORT skip: 未设置 -Dgoldlab.importBinance")
            return
        }
        val root = File(rootPath)
        assertTrue("源目录不存在：${root.absolutePath}", root.isDirectory)

        var totalBars = 0L
        var totalBytes = 0L
        var totalFiles = 0

        for (symbol in symbols) {
            val dir = File(root, symbol)
            if (!dir.isDirectory) {
                println("IMPORT skip symbol=$symbol（目录不存在）")
                continue
            }
            // 按月份分组：月文件与日文件都可能出现
            val byMonth = LinkedHashMap<String, MutableList<File>>()
            for (f in dir.listFiles { file -> file.extension.lowercase() == "csv" } ?: emptyArray()) {
                val m = nameRegex.find(f.name) ?: continue
                val month = m.groupValues[2]
                byMonth.getOrPut(month) { ArrayList() }.add(f)
            }

            val outDir = File("src/main/assets/market/$symbol")
            outDir.mkdirs()

            for ((month, files) in byMonth.toSortedMap()) {
                val candles = ArrayList<Candle>(46_000)
                for (f in files.sortedBy { it.name }) candles.addAll(parse(f))
                if (candles.isEmpty()) {
                    println("IMPORT $symbol $month 空文件，跳过")
                    continue
                }
                val clean = candles
                    .filter { it.isValid() }
                    .distinctBy { it.timeMillis }
                    .sortedBy { it.timeMillis }
                require(clean.size >= 2) { "$symbol $month 有效 K 线不足" }
                require(clean.zipWithNext().all { (a, b) -> b.timeMillis > a.timeMillis }) {
                    "$symbol $month 时间不是严格递增"
                }

                val bytes = Glb1Writer.encode(clean, symbol, digits)
                val target = File(outDir, "$month.glb1")
                target.writeBytes(bytes)

                totalBars += clean.size
                totalBytes += bytes.size
                totalFiles++
                println(
                    "IMPORT $symbol $month bars=${clean.size} files=${files.size} " +
                        "bytes=${bytes.size} avg=${"%.2f".format(bytes.size.toDouble() / clean.size)} B/bar",
                )
            }
        }

        println("IMPORT 完成: 文件=$totalFiles K线=$totalBars 总字节=$totalBytes")
        assertTrue("应当至少导入一个文件", totalFiles > 0)
    }

    private fun parse(f: File): List<Candle> {
        val out = ArrayList<Candle>(46_000)
        var headerSkipped = false
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                if (!headerSkipped) {
                    headerSkipped = true
                    if (line.first().isLetter()) continue // 表头
                }
                val c = parseLine(line) ?: continue
                out.add(c)
            }
        }
        return out
    }

    private fun parseLine(line: String): Candle? {
        val p = line.split(',')
        if (p.size < 9) return null
        val t = p[0].toLongOrNull() ?: return null
        val o = p[1].toDoubleOrNull() ?: return null
        val h = p[2].toDoubleOrNull() ?: return null
        val l = p[3].toDoubleOrNull() ?: return null
        val c = p[4].toDoubleOrNull() ?: return null
        val count = p[8].toLongOrNull() ?: 0L
        return Candle(
            timeMillis = t,
            open = o, high = h, low = l, close = c,
            volume = count,
            spread = 0.0,
        )
    }
}
