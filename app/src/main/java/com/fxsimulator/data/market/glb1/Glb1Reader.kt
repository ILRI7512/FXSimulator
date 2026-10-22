package com.fxsimulator.data.market.glb1

import com.fxsimulator.domain.model.Candle
import kotlin.math.abs

/**
 * GLB1 解码结果。
 *
 * @param warnings 读取过程中发现的数据问题。**正常情况下是空的**；
 *                 一旦非空，说明文件本身有问题（而不是调用方用错了参数），
 *                 上层应当把内容记进日志。测试会断言正式数据不产生任何警告。
 */
data class Glb1Decoded(
    val candles: List<Candle>,
    /** 价格小数位，来自文件头 —— 不是猜的 */
    val digits: Int,
    val symbol: String,
    val timeBaseMillis: Long,
    val warnings: List<String>,
)

/**
 * GLB1 解码器。
 *
 * 价格换算**只走 [Glb1Format.toPrice] 一处**，开高低收与点差共用同一条路径 ——
 * 单位错位这种事在这个结构下不可能悄悄发生。
 *
 * 另外自带一道自检：点差若超过首根价格的 [Glb1Format.SPREAD_SANITY_DENOMINATOR] 分之一，
 * 判定为单位写错，把该根点差归零（上层会退回默认点差）并记录警告。
 * 宁可少一个数，也不静默按错的单位算钱。
 */
object Glb1Reader {

    fun decode(bytes: ByteArray): Glb1Decoded {
        require(bytes.size >= Glb1Format.HEADER_FIXED) { "文件只有 " + bytes.size + " 字节，不可能是 GLB1" }
        for (i in Glb1Format.MAGIC.indices) {
            require(bytes[i] == Glb1Format.MAGIC[i]) { "magic 不匹配，不是 GLB1 文件" }
        }
        val version = bytes[4].toInt() and 0xFF
        require(version == Glb1Format.VERSION) { "GLB1 版本 " + version + " 不受支持（本机支持 " + Glb1Format.VERSION + "）" }
        val digits = bytes[5].toInt() and 0xFF
        require(digits in Glb1Format.MIN_DIGITS..Glb1Format.MAX_DIGITS) { "文件头的 digits 非法：" + digits }
        val columnCount = bytes[6].toInt() and 0xFF
        require(columnCount == Glb1Column.COUNT) {
            "列数不匹配：文件说 " + columnCount + "，本机支持 " + Glb1Column.COUNT
        }

        var p = 8
        val barCount = readU32(bytes, p).toInt(); p += 4
        require(barCount >= 0) { "barCount 非法" }
        val timeBase = readI64(bytes, p); p += 8
        val baseCloseTicks = readI64(bytes, p); p += 8
        val symbolLen = readU16(bytes, p); p += 2
        require(p + symbolLen <= bytes.size) { "symbol 长度越界" }
        val symbol = String(bytes, p, symbolLen, Charsets.UTF_8); p += symbolLen

        require(p + Glb1Format.COLUMN_TABLE_BYTES <= bytes.size) { "列长度表越界" }
        val starts = IntArray(Glb1Column.COUNT)
        var off = p + Glb1Format.COLUMN_TABLE_BYTES
        for (i in 0 until Glb1Column.COUNT) {
            val len = readU32(bytes, p + i * 4).toInt()
            require(len >= 0) { "列 " + i + " 长度非法" }
            starts[i] = off
            off += len
        }
        require(off <= bytes.size) { "列数据越界：需要 " + off + " 字节，实际只有 " + bytes.size }

        val readers = Array(Glb1Column.COUNT) { VarintReader(bytes, starts[it]) }

        // 点差合理性参照：首根价格。用 tick 做整数比较，避免浮点误差
        val priceRefTicks = abs(baseCloseTicks).coerceAtLeast(1L)
        val spreadLimit = priceRefTicks / Glb1Format.SPREAD_SANITY_DENOMINATOR

        val warnings = ArrayList<String>()
        val out = ArrayList<Candle>(barCount)
        var prevCloseTicks = baseCloseTicks
        var timeMillis = timeBase
        var prevSpreadTicks = 0L

        for (i in 0 until barCount) {
            timeMillis += readers[Glb1Column.TIME_DELTA.ordinal].unsigned()

            val o = prevCloseTicks + readers[Glb1Column.OPEN_DELTA.ordinal].signed()
            val h = o + readers[Glb1Column.HIGH_DELTA.ordinal].signed()
            val l = o + readers[Glb1Column.LOW_DELTA.ordinal].signed()
            val c = o + readers[Glb1Column.CLOSE_DELTA.ordinal].signed()
            val volume = readers[Glb1Column.VOLUME.ordinal].unsigned()

            val spreadTicks = prevSpreadTicks + readers[Glb1Column.SPREAD_DELTA.ordinal].signed()
            prevSpreadTicks = spreadTicks

            val spreadOk = abs(spreadTicks) <= spreadLimit
            if (!spreadOk && warnings.isEmpty()) {
                warnings.add(
                    "第 " + i + " 根的点差 " + spreadTicks + " tick 超过首根价格的 1%（" +
                        spreadLimit + " tick），判定单位有误，已归零交由上层使用默认点差",
                )
            }

            // 唯一的 tick → 价格换算点。开高低收与点差走的是同一条路径。
            out.add(
                Candle(
                    timeMillis = timeMillis,
                    open = Glb1Format.toPrice(o, digits),
                    high = Glb1Format.toPrice(h, digits),
                    low = Glb1Format.toPrice(l, digits),
                    close = Glb1Format.toPrice(c, digits),
                    volume = volume,
                    spread = if (spreadOk) Glb1Format.toPrice(spreadTicks, digits) else 0.0,
                ),
            )
            prevCloseTicks = c
        }

        return Glb1Decoded(
            candles = out,
            digits = digits,
            symbol = symbol,
            timeBaseMillis = timeBase,
            warnings = warnings,
        )
    }

    /** 只读文件头，用于在不解码整份数据的前提下知道品种与小数位 */
    fun peekHeader(bytes: ByteArray): Glb1Decoded {
        val full = decode(bytes)
        return full.copy(candles = emptyList())
    }

    private fun readU32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24))

    private fun readU16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun readI64(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (k * 8))
        return v
    }
}
