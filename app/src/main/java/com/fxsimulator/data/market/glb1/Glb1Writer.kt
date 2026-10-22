package com.fxsimulator.data.market.glb1

import com.fxsimulator.domain.model.Candle
import java.io.ByteArrayOutputStream

/**
 * GLB1 编码器。
 *
 * 只依赖 [Glb1Format.toTicks] 做价格换算，自己不碰 10^n ——
 * 单位只有一个入口，就不会再出现「文档说价格、实际存点数」那种事故。
 */
object Glb1Writer {

    /**
     * @param bars 必须按时间升序（调用方负责；乱序会让增量编码失效，也会让回放倒退）
     * @param digits 价格小数位。黄金填 3。写进文件头，读取端据此还原价格。
     */
    fun encode(bars: List<Candle>, symbol: String, digits: Int): ByteArray {
        require(bars.isNotEmpty()) { "空行情无法编码成 GLB1" }
        val symbolBytes = symbol.toByteArray(Charsets.UTF_8)
        require(symbolBytes.size in 1..0xFFFF) { "symbol 长度非法：" + symbolBytes.size }
        Glb1Format.scale(digits) // 提前校验 digits 合法

        val cols = Array(Glb1Column.COUNT) { ByteArrayOutputStream(bars.size + 16) }
        val baseCloseTicks = Glb1Format.toTicks(bars.first().close, digits)
        var prevCloseTicks = baseCloseTicks
        var prevTimeMillis = bars.first().timeMillis
        var prevSpreadTicks = 0L

        for (b in bars) {
            // 时间：第一根差值为 0，绝对时间在文件头里
            Varints.putUnsigned(cols[Glb1Column.TIME_DELTA.ordinal], b.timeMillis - prevTimeMillis)
            prevTimeMillis = b.timeMillis

            val o = Glb1Format.toTicks(b.open, digits)
            val h = Glb1Format.toTicks(b.high, digits)
            val l = Glb1Format.toTicks(b.low, digits)
            val c = Glb1Format.toTicks(b.close, digits)

            Varints.putSigned(cols[Glb1Column.OPEN_DELTA.ordinal], o - prevCloseTicks)
            Varints.putSigned(cols[Glb1Column.HIGH_DELTA.ordinal], h - o)
            Varints.putSigned(cols[Glb1Column.LOW_DELTA.ordinal], l - o)
            Varints.putSigned(cols[Glb1Column.CLOSE_DELTA.ordinal], c - o)
            Varints.putUnsigned(cols[Glb1Column.VOLUME.ordinal], if (b.volume > 0L) b.volume else 0L)

            // 点差**与价格同一个 tick 尺度**：3 位小数时 0.200 → 200 tick。
            // 注意 MT5 的「点」恰好也是 10^-3，所以数值上等于原始列，
            // 但这里走的是 toTicks，语义是「价格 tick」而不是「点」。
            val sp = Glb1Format.toTicks(b.spread, digits)
            Varints.putSigned(cols[Glb1Column.SPREAD_DELTA.ordinal], sp - prevSpreadTicks)
            prevSpreadTicks = sp

            prevCloseTicks = c
        }

        val payloads = cols.map { it.toByteArray() }
        val total = Glb1Format.HEADER_FIXED + symbolBytes.size + Glb1Format.COLUMN_TABLE_BYTES +
            payloads.sumOf { it.size }
        val out = ByteArrayOutputStream(total)
        out.write(Glb1Format.MAGIC)
        out.write(Glb1Format.VERSION)
        out.write(digits)
        out.write(Glb1Column.COUNT)
        out.write(0)
        writeU32(out, bars.size.toLong())
        writeI64(out, bars.first().timeMillis)
        writeI64(out, baseCloseTicks)
        writeU16(out, symbolBytes.size)
        out.write(symbolBytes)
        for (p in payloads) writeU32(out, p.size.toLong())
        for (p in payloads) out.write(p)
        return out.toByteArray()
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }

    private fun writeI64(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) out.write(((v ushr (i * 8)) and 0xFF).toInt())
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }
}
