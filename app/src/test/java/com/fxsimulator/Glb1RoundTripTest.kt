package com.fxsimulator

import com.fxsimulator.data.csv.CsvCandleParser
import com.fxsimulator.data.market.glb1.Glb1Column
import com.fxsimulator.data.market.glb1.Glb1Format
import com.fxsimulator.data.market.glb1.Glb1Reader
import com.fxsimulator.data.market.glb1.Glb1Unit
import com.fxsimulator.data.market.glb1.Glb1Writer
import com.fxsimulator.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * GLB1 的**无损性**校验：拿 33 个月的真实 CSV 做逐根逐字段比对。
 *
 * ## 生成 vs 校验
 *
 * 这个测试平时只**校验**（读取已提交的 assets/market 下的 .glb1 与 market-data 下的 .csv 对比）；
 * 只有显式带上 `-Dgoldlab.genMarket=1` 时才会把 `.glb1` 写出来：
 *
 * ```
 * ./gradlew testDebugUnitTest -Dgoldlab.genMarket=1 --tests com.fxsimulator.Glb1RoundTripTest
 * ```
 *
 * 之所以把生成放进测试而不是 Gradle 任务：它需要用到 [CsvCandleParser] 与 [Glb1Writer]，
 * 而这两者恰好就在单元测试的 classpath 上；写成 Gradle 任务反而要为 Android 模块
 * 手工拼一套 runtime classpath。副作用被一个显式参数挡住，平时不会动源码树。
 *
 * ## 为什么必须逐字段比对
 *
 * 只比根数是不够的 —— 单位错位（例如点差存成了别的尺度）根数照样对得上。
 * 这个格式的前身就出过这种事：`Candle.spread` 文档写价格、实际存点数，
 * 因为旧测试断言的正是错误值而潜伏至今。所以这里比对的是**每一根的每一个字段**。
 */
class Glb1RoundTripTest {

    private val regen = System.getProperty("goldlab.genMarket") == "1"

    /** 从 app/ 往上找，直到找到 market-data 目录 */
    private fun findUpwards(name: String): File? {
        var d: File? = File(".").absoluteFile
        var hops = 0
        while (d != null && hops < 6) {
            val c = File(d, name)
            if (c.isDirectory) return c
            d = d.parentFile
            hops++
        }
        return null
    }

    @Test
    fun everyBundledMonthDecodesBackByteForByte() {
        val src = findUpwards("market-data")
        assumeTrue("没有 market-data 目录，跳过", src != null)
        val outDir = File("src/main/assets/market")

        val months = src!!.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            .orEmpty().map { it.nameWithoutExtension }.sorted()
        assertTrue("market-data 里没有 CSV", months.isNotEmpty())
        if (regen) outDir.mkdirs()

        var csvBytes = 0L
        var binBytes = 0L
        for (month in months) {
            val csvFile = File(src, "$month.csv")
            val bars = parseCsv(csvFile)
            val digits = inferDigits(csvFile)
            assertEquals("内置行情都应当是 3 位小数（" + month + "）", 3, digits)

            val encoded = Glb1Writer.encode(bars, "XAUUSD", digits)
            if (regen) File(outDir, "$month.glb1").writeBytes(encoded)

            val glbFile = File(outDir, "$month.glb1")
            assertTrue(
                month + ".glb1 不存在；先跑一次 ./gradlew testDebugUnitTest -Dgoldlab.genMarket=1",
                glbFile.isFile,
            )
            val decoded = Glb1Reader.decode(glbFile.readBytes())

            assertTrue(month + " 读取时产生警告：" + decoded.warnings, decoded.warnings.isEmpty())
            assertEquals(month + " 的小数位", digits, decoded.digits)
            assertEquals(month + " 的品种", "XAUUSD", decoded.symbol)
            assertEquals(month + " 的根数", bars.size, decoded.candles.size)
            assertEquals(month + " 的首根时间", bars[0].timeMillis, decoded.candles[0].timeMillis)

            // 逐根逐字段：这是「无损」的真正含义
            for (i in bars.indices) {
                val a = bars[i]
                val b = decoded.candles[i]
                val where = month + " 第 " + i + " 根"
                assertEquals(where + " 时间", a.timeMillis, b.timeMillis)
                assertEquals(where + " 开盘", tick(a.open, digits), tick(b.open, digits))
                assertEquals(where + " 最高", tick(a.high, digits), tick(b.high, digits))
                assertEquals(where + " 最低", tick(a.low, digits), tick(b.low, digits))
                assertEquals(where + " 收盘", tick(a.close, digits), tick(b.close, digits))
                // 点差走的是同一条 tick 路径，这里正对着当初出事的那个字段
                assertEquals(where + " 点差", tick(a.spread, digits), tick(b.spread, digits))
                assertEquals(where + " 成交量", a.volume, b.volume)
            }

            csvBytes += csvFile.length()
            binBytes += glbFile.length()
            println(
                "[glb1] " + month + "  " + bars.size + " 根  " +
                    (csvFile.length() / 1024) + " KB CSV → " + (glbFile.length() / 1024) + " KB GLB1",
            )
        }

        val ratio = binBytes.toDouble() / csvBytes.toDouble() * 100.0
        println(
            "[glb1] 合计 " + months.size + " 个月：" + (csvBytes / 1048576) + " MB CSV → " +
                (binBytes / 1048576) + " MB GLB1（未再压缩，占 " +
                String.format(java.util.Locale.US, "%.1f", ratio) + "%）",
        )
    }

    /**
     * 单位契约：**凡是价格量都必须是 PRICE_TICKS**，只有成交量例外。
     *
     * 这条断言的意义在于：以后有人往这个格式里加一列「点差」或者别的价格量时，
     * 如果单位写成了 MILLISECONDS / TICKS_TRADED，测试会直接拦住。
     */
    @Test
    fun everyPriceLikeColumnDeclaresTheSameUnit() {
        val priceColumns = listOf(
            Glb1Column.OPEN_DELTA, Glb1Column.HIGH_DELTA,
            Glb1Column.LOW_DELTA, Glb1Column.CLOSE_DELTA, Glb1Column.SPREAD_DELTA,
        )
        for (c in priceColumns) {
            assertEquals(
                c.displayName + " 必须与价格共用同一个 tick 尺度",
                Glb1Unit.PRICE_TICKS, c.unit,
            )
        }
        assertEquals(Glb1Unit.MILLISECONDS, Glb1Column.TIME_DELTA.unit)
        assertEquals("成交量不是价格量，必须单独标注", Glb1Unit.TICKS_TRADED, Glb1Column.VOLUME.unit)
        assertEquals("列数变了就要同步改文件头解析", 7, Glb1Column.COUNT)
    }

    private fun tick(v: Double, digits: Int): Long = Glb1Format.toTicks(v, digits)

    private fun parseCsv(file: File): List<Candle> {
        val lines = file.readLines()
        val schema = CsvCandleParser.detectSchema(lines[0]) ?: error("表头无法识别：" + file.name)
        val out = ArrayList<Candle>(lines.size)
        for (i in 1 until lines.size) {
            CsvCandleParser.parseLine(lines[i], schema, "XAUUSD").candle?.let { out.add(it) }
        }
        return out
    }

    /** 从首行数据的价格文本里数小数位，决定 GLB1 文件头的 digits */
    private fun inferDigits(file: File): Int {
        val lines = file.readLines()
        val schema = CsvCandleParser.detectSchema(lines[0]) ?: error("表头无法识别：" + file.name)
        val cells = lines[1].split(schema.delimiter)
        var d = 0
        for (idx in intArrayOf(schema.indexOpen, schema.indexHigh, schema.indexLow, schema.indexClose)) {
            val raw = cells.getOrNull(idx)?.trim() ?: continue
            val dot = raw.indexOf(".")
            if (dot >= 0) d = maxOf(d, raw.length - dot - 1)
        }
        return d
    }
}
