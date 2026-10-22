package com.fxsimulator.data.market

import android.content.Context
import com.fxsimulator.data.csv.CsvCandleParser
import com.fxsimulator.data.market.glb1.Glb1Reader
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.util.GoldLabLog
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/**
 * 随包内置的历史行情。
 *
 * ## 为什么内置
 * 一个游戏不该要求玩家先导入上百 MB 的 CSV 才能玩。
 *
 * ## 为什么是 GLB1 而不是 CSV
 * 实测（33 个月）：CSV + deflate 在 APK 里占 18.83 MB，二进制列式只要约 6.6 MB；
 * 单月加载从 161 ms 降到 2 ms。核心收益来自「不用 ASCII 存数字」，
 * 而不是压缩算法 —— 细节见 [com.fxsimulator.data.market.glb1.Glb1Format]。
 *
 * CSV 仍然保留一条回退路径：万一某个 .glb1 缺失或损坏，只要同目录下有同名 .csv
 * 就能照常工作，不会整个玩法不可用。
 *
 * ## 资产布局
 * 早期只有黄金一个品种，文件直接平铺在 `assets/market/<yyyy-MM>.glb1`。
 * 加入多品种后月份名会互相撞车，因此：
 *
 * - `assets/market/XAUUSD/...` 或平铺 `assets/market/<yyyy-MM>.glb1` —— 黄金
 * - `assets/market/<品种>/<yyyy-MM>.glb1` —— 其它品种
 *
 * 查找顺序永远是「先按品种的子目录，再退回平铺目录」，
 * 所以旧的黄金资产不需要搬家，新老两种布局可以共存。
 *
 * ## 数据来源与许可
 * 数据由用户从自己的 Exness MT5 模拟账户导出。
 * Exness 官方客服（个人专区在线通道）明确答复：可以导出、**可以分发给第三方**、
 * 不限制对 MT5/MT4 中数据的用途。该对话记录已留存。
 */
object BundledMarketData {

    /** assets 下的目录名 */
    private const val DIR = "market"

    private const val TAG = "BundledMarketData"

    /** 首选格式，以及回退格式 */
    private const val EXT_GLB1 = ".glb1"
    private const val EXT_CSV = ".csv"

    private val CANDIDATES = arrayOf(EXT_GLB1, EXT_CSV, ".csv.gz")

    private val MONTH_RE = Regex("^\\d{4}-\\d{2}$")

    /** 默认品种：没有显式指定时保持旧行为（黄金平铺目录） */
    const val DEFAULT_SYMBOL = "XAUUSD"

    /**
     * 按品种列出的候选目录。
     *
     * **平铺目录只对默认品种（黄金）开放**：那是它早期的布局，里面的文件是黄金行情。
     * 如果让外汇也退回平铺目录，某个月份在子目录里缺失时就会静默加载到**黄金的价格**，
     * 而合约规格是 10 万货币单位 —— 盈亏会被放大到百万级，而且没有任何报错。
     * 这种「看着正常、数字全错」的失败方式比直接崩掉危险得多，所以宁可不回退。
     */
    private fun dirsFor(symbol: String): List<String> =
        if (symbol.equals(DEFAULT_SYMBOL, ignoreCase = true)) listOf(DIR + "/" + symbol, DIR)
        else listOf(DIR + "/" + symbol)

    /** 列某个目录里的月份；目录不存在时返回空 */
    private fun monthsIn(context: Context, dir: String): List<String> = try {
        (context.assets.list(dir) ?: emptyArray())
            .map { it.removeSuffix(".gz").removeSuffix(EXT_GLB1).removeSuffix(EXT_CSV) }
            .filter { MONTH_RE.matches(it) }
            .distinct()
            .sorted()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 内置了哪些月份（yyyy-MM），升序。用于「自由探索」的月份选择。
     *
     * @param symbol 品种；不传时按默认品种（黄金）查，保持旧调用点行为不变
     */
    fun availableMonths(context: Context, symbol: String = DEFAULT_SYMBOL): List<String> {
        for (dir in dirsFor(symbol)) {
            val months = monthsIn(context, dir)
            if (months.isNotEmpty()) return months
        }
        return emptyList()
    }

    /** 这个月有没有内置数据（按品种） */
    fun has(context: Context, month: String, symbol: String = DEFAULT_SYMBOL): Boolean =
        CANDIDATES.any { ext ->
            dirsFor(symbol).any { dir ->
                try {
                    context.assets.open(dir + "/" + month + ext).close()
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

    /**
     * 读取并解析某个月的行情。
     *
     * 一个月约 3 万根 M1，全部读进内存约 2 MB —— 完全可接受，
     * 因此直接用 [InMemoryBarSource]，不需要文件索引那一套。
     *
     * @return null 表示没有这一月的数据，或数据不可用
     */
    fun load(context: Context, month: String, symbol: String): BarSource? {
        loadGlb1(context, month, symbol)?.let { return it }
        loadCsv(context, month, symbol)?.let { return it }
        return null
    }

    /** GLB1 路径。解析出来的点差已经是**价格单位**，可直接使用。 */
    private fun loadGlb1(context: Context, month: String, symbol: String): BarSource? {
        for (dir in dirsFor(symbol)) {
            val path = dir + "/" + month + EXT_GLB1
            try {
                context.assets.open(path).use { raw ->
                    val decoded = Glb1Reader.decode(raw.readBytes())
                    // 读取器发现的数据问题必须留下痕迹，不能悄悄放过
                    for (w in decoded.warnings) GoldLabLog.w(TAG, month + "：" + w)
                    if (decoded.candles.isEmpty()) {
                        GoldLabLog.w(TAG, path + " 的 GLB1 没有任何 K 线，回退 CSV")
                    } else {
                        return InMemoryBarSource(
                            candles = decoded.candles,
                            sourceName = "内置 " + monthLabel(month),
                            symbol = symbol,
                            isDemoData = false,
                        )
                    }
                }
            } catch (e: Exception) {
                // 这个目录没有该文件（或解码失败），试下一个候选目录
            }
        }
        return null
    }

    /**
     * CSV 回退路径。
     *
     * 保留它有两个用处：一是 GLB1 万一缺失/损坏时玩法不会整体不可用；
     * 二是允许直接把 MT5 导出的 CSV 丢进 assets/market 里，无需转换。
     */
    private fun loadCsv(context: Context, month: String, symbol: String): BarSource? {
        for (dir in dirsFor(symbol)) {
            for (ext in arrayOf(EXT_CSV, ".csv.gz")) {
                try {
                    context.assets.open(dir + "/" + month + ext).use { raw ->
                        val buffered = BufferedInputStream(raw)
                        val opened = if (isGzip(buffered)) GZIPInputStream(buffered) else buffered
                        opened.bufferedReader(Charsets.UTF_8).use { reader ->
                            val parsed = parseCsv(reader, month, symbol)
                            if (parsed != null) return parsed
                        }
                    }
                } catch (e: Exception) {
                    // 这个扩展名不存在或解析失败，试下一个
                }
            }
        }
        return null
    }

    /** 嗅探 gzip 魔数 0x1f 0x8b。用 mark/reset 读两个字节后还原，调用方无感知 */
    private fun isGzip(stream: BufferedInputStream): Boolean {
        stream.mark(2)
        val b0 = stream.read()
        val b1 = stream.read()
        stream.reset()
        return b0 == 0x1f && b1 == 0x8b
    }

    private fun parseCsv(reader: java.io.BufferedReader, month: String, symbol: String): BarSource? {
        var header = reader.readLine() ?: return null
        // 去掉 UTF-8 BOM，否则首列永远匹配不上
        if (header.startsWith("\uFEFF")) header = header.removePrefix("\uFEFF")
        val schema = CsvCandleParser.detectSchema(header) ?: return null

        val candles = ArrayList<Candle>(32_000)
        var line = reader.readLine()
        while (line != null) {
            val c = CsvCandleParser.parseLine(line, schema, symbol).candle
            if (c != null) candles.add(c)
            line = reader.readLine()
        }
        if (candles.isEmpty()) return null
        // 跨月文件理论上已排好序，但排序是廉价的保险：乱序会让回放倒退
        candles.sortBy { it.timeMillis }
        return InMemoryBarSource(
            candles = candles,
            sourceName = "内置 " + monthLabel(month),
            symbol = symbol,
            isDemoData = false,
        )
    }

    /** "2024-03" → "2024 年 3 月"。纯函数，便于单测与界面复用。 */
    fun monthLabel(month: String): String {
        val y = month.substringBefore("-")
        val m = month.substringAfter("-").trimStart('0')
        return y + " 年 " + (m.ifEmpty { "0" }) + " 月"
    }
}
