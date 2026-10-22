package com.fxsimulator.data.csv

import com.fxsimulator.domain.model.Candle
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 单行解析结果 */
data class LineParseResult(
    val candle: Candle?,
    /** 解析失败原因；成功时为 null */
    val problem: String? = null,
    /** 品种不匹配时跳过（不算错误） */
    val skipped: Boolean = false,
)

/**
 * CSV 行情解析器。
 *
 * 兼容两类真实文件：
 * 1) MetaTrader「导出到文件」的英文表头：`<DATE> <TIME> <OPEN> <HIGH> <LOW> <CLOSE> <TICKVOL> <VOL> <SPREAD>`（制表符分隔）
 * 2) 中文导出表头：`品种,日期,时间,开盘价,最高价,最低价,收盘价,Tick量,真实成交量,点差`（逗号分隔）
 *
 * 时间按**经纪商服务器时间**解释，本项目统一按 UTC 换算为 epoch 毫秒，
 * 不做时区推断（会在界面上说明）。
 */
object CsvCandleParser {

    /** 表头别名表：统一小写并去掉 <> 与空白后匹配 */
    private val DATE_KEYS = setOf("date", "日期")
    private val TIME_KEYS = setOf("time", "时间")
    private val OPEN_KEYS = setOf("open", "开盘价", "开盘")
    private val HIGH_KEYS = setOf("high", "最高价", "最高")
    private val LOW_KEYS = setOf("low", "最低价", "最低")
    private val CLOSE_KEYS = setOf("close", "收盘价", "收盘")
    private val VOLUME_KEYS = setOf("tickvol", "tick量", "tick成交量", "volume", "vol", "真实成交量", "成交量")
    private val SPREAD_KEYS = setOf("spread", "点差")
    private val SYMBOL_KEYS = setOf("symbol", "品种", "代码")

    /** 品种名匹配：忽略大小写，并允许经纪商后缀（XAUUSDm ≈ XAUUSD） */
    fun symbolMatches(actual: String, expected: String): Boolean {
        val a = actual.trim()
        val b = expected.trim()
        if (a.isEmpty() || b.isEmpty()) return true
        return a.equals(b, ignoreCase = true) ||
            a.contains(b, ignoreCase = true) ||
            b.contains(a, ignoreCase = true)
    }

    fun normalizeHeaderCell(raw: String): String =
        raw.trim()
            .removePrefix("\uFEFF") // 去掉 UTF-8 BOM，否则首列永远匹配不上
            .trim()
            .removeSurrounding("<", ">")
            .trim()
            .lowercase()

    /** 探测分隔符与列映射；无法识别时返回 null */
    fun detectSchema(headerLine: String): CsvSchema? {
        val candidates = listOf('\t', ',', ';')
        var best: CsvSchema? = null
        var bestScore = 0
        for (d in candidates) {
            val cells = headerLine.split(d)
            if (cells.size < 5) continue
            var score = 0
            var iSymbol = -1
            var iDate = -1
            var iTime = -1
            var iOpen = -1
            var iHigh = -1
            var iLow = -1
            var iClose = -1
            var iVolume = -1
            var iSpread = -1
            for (i in cells.indices) {
                val key = normalizeHeaderCell(cells[i])
                when {
                    key in DATE_KEYS && iDate < 0 -> { iDate = i; score++ }
                    key in TIME_KEYS && iTime < 0 -> { iTime = i; score++ }
                    key in OPEN_KEYS && iOpen < 0 -> { iOpen = i; score++ }
                    key in HIGH_KEYS && iHigh < 0 -> { iHigh = i; score++ }
                    key in LOW_KEYS && iLow < 0 -> { iLow = i; score++ }
                    key in CLOSE_KEYS && iClose < 0 -> { iClose = i; score++ }
                    key in VOLUME_KEYS && iVolume < 0 -> { iVolume = i }
                    key in SPREAD_KEYS && iSpread < 0 -> { iSpread = i }
                    key in SYMBOL_KEYS && iSymbol < 0 -> { iSymbol = i }
                }
            }
            // 至少需要日期/时间/OHLC 全部识别出来
            val ok = iDate >= 0 && iTime >= 0 && iOpen >= 0 && iHigh >= 0 && iLow >= 0 && iClose >= 0
            if (ok && score > bestScore) {
                bestScore = score
                best = CsvSchema(
                    delimiter = d,
                    hasHeader = true,
                    indexSymbol = iSymbol,
                    indexDate = iDate,
                    indexTime = iTime,
                    indexOpen = iOpen,
                    indexHigh = iHigh,
                    indexLow = iLow,
                    indexClose = iClose,
                    indexVolume = iVolume,
                    indexSpread = iSpread,
                    headerText = headerLine,
                )
            }
        }
        return best
    }

    /** 无表头时按 MT5 默认列序推断：date,time,open,high,low,close,tickvol,vol,spread */
    fun positionalSchema(delimiter: Char): CsvSchema = CsvSchema(
        delimiter = delimiter,
        hasHeader = false,
        indexSymbol = -1,
        indexDate = 0,
        indexTime = 1,
        indexOpen = 2,
        indexHigh = 3,
        indexLow = 4,
        indexClose = 5,
        indexVolume = 6,
        indexSpread = 8,
        headerText = "",
    )

    /**
     * 解析行情时间。
     * 支持 `2021.01.03` / `2021-01-03` 与 `00:00:00` / `00:00`。
     */
    fun parseTimestamp(dateRaw: String, timeRaw: String): Long? {
        val d = dateRaw.trim()
        val t = timeRaw.trim()
        if (d.isEmpty()) return null
        val dateParts = d.split('.', '-', '/')
        if (dateParts.size < 3) return null
        val year = dateParts[0].toIntOrNull() ?: return null
        val month = dateParts[1].toIntOrNull() ?: return null
        val day = dateParts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        if (year !in 1970..2100) return null

        var hh = 0
        var mm = 0
        var ss = 0
        if (t.isNotEmpty()) {
            val tp = t.split(':')
            hh = tp.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            mm = tp.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            ss = tp.getOrNull(2)?.trim()?.substringBefore('.')?.toIntOrNull() ?: 0
            if (hh !in 0..23 || mm !in 0..59 || ss !in 0..60) return null
        }
        return try {
            LocalDateTime.of(year, month, day, hh, mm, minOf(ss, 59))
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析一行数据。
     * @param expectedSymbol 若表中有品种列，则只接受该品种
     */
    fun parseLine(line: String, schema: CsvSchema, expectedSymbol: String? = null): LineParseResult {
        if (line.isBlank()) return LineParseResult(null, skipped = true)
        val cells = line.split(schema.delimiter)
        val maxNeeded = listOf(
            schema.indexDate, schema.indexTime, schema.indexOpen,
            schema.indexHigh, schema.indexLow, schema.indexClose,
        ).max()
        if (cells.size <= maxNeeded) {
            return LineParseResult(null, problem = "列数不足，期望至少 " + (maxNeeded + 1) + " 列，实际 " + cells.size)
        }

        if (expectedSymbol != null && schema.indexSymbol >= 0) {
            val sym = cells.getOrNull(schema.indexSymbol)?.trim().orEmpty()
            // 真实经纪商的品种名常带后缀（例如 Exness 的 XAUUSDm），
            // 因此采用包含式匹配，而不是要求完全相同。
            if (sym.isNotEmpty() && !symbolMatches(sym, expectedSymbol)) {
                return LineParseResult(null, skipped = true)
            }
        }

        val ts = parseTimestamp(cells[schema.indexDate], cells[schema.indexTime])
            ?: return LineParseResult(null, problem = "时间戳无法解析：\"" + cells[schema.indexDate] + " " + cells[schema.indexTime] + "\"")

        val o = cells[schema.indexOpen].trim().toDoubleOrNull()
        val h = cells[schema.indexHigh].trim().toDoubleOrNull()
        val l = cells[schema.indexLow].trim().toDoubleOrNull()
        val c = cells[schema.indexClose].trim().toDoubleOrNull()
        if (o == null || h == null || l == null || c == null) {
            return LineParseResult(null, problem = "OHLC 存在非数值字段")
        }

        val vol = if (schema.indexVolume >= 0) cells.getOrNull(schema.indexVolume)?.trim()?.toDoubleOrNull()?.toLong() ?: 0L else 0L

        // ---------------------------------------------------------------- 点差单位
        //
        // MT5 导出 CSV 里的「点差」列单位是**点**，不是价格：黄金 3 位小数时
        // 200 表示 0.200 美元，而不是 200 美元。这里必须换算。
        //
        // 此前直接把原始值塞进 Candle.spread，而 Candle 的文档写的是「价格为单位的
        // 原始值」—— 两者不一致。因为当时的测试断言的正是错误值（160.0），
        // 这个坑一直没暴露，直到「猜涨跌」真的去读它：入场价被抬高 200 美元，
        // 一局就把虚拟账户打成负数。
        val spreadRaw = if (schema.indexSpread >= 0) cells.getOrNull(schema.indexSpread)?.trim()?.toDoubleOrNull() ?: 0.0 else 0.0
        val spread = if (spreadRaw > 0.0) {
            spreadRaw / Math.pow(10.0, priceDigits(cells, schema).toDouble())
        } else {
            0.0
        }

        val candle = Candle(ts, o, h, l, c, vol, spread)
        if (!candle.isValid()) {
            return LineParseResult(null, problem = "OHLC 不合法（价格非正或 high/low 与开收盘矛盾）")
        }
        return LineParseResult(candle)
    }

    /**
     * 从这一行的 OHLC 文本推断价格小数位。
     *
     * MT5 点差列的单位是「点」，1 点 = 10^(-小数位)。不知道小数位就没法把它换算成价格。
     * 取四个价格里**最多**的小数位：有些行会省略末尾的 0（"2044.5" 而不是 "2044.500"），
     * 取最大值才能稳定还原品种精度。
     */
    private fun priceDigits(cells: List<String>, schema: CsvSchema): Int {
        var d = 0
        for (idx in intArrayOf(schema.indexOpen, schema.indexHigh, schema.indexLow, schema.indexClose)) {
            if (idx < 0) continue
            val raw = cells.getOrNull(idx)?.trim() ?: continue
            val dot = raw.indexOf(".")
            if (dot < 0) continue
            val n = raw.length - dot - 1
            if (n > d) d = n
        }
        return d
    }
}
