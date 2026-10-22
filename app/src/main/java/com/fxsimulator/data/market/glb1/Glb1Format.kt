package com.fxsimulator.data.market.glb1

import java.io.ByteArrayOutputStream

/**
 * GLB1 —— GoldLab 自用的历史行情列式二进制格式。
 *
 * # 为什么要有它
 *
 * 原来直接把 MT5 导出的 CSV 放进 APK。实测（33 个月、67.85 MB 原始）：
 *
 * | 方案 | APK 内体积 |
 * |---|---|
 * | CSV + deflate | 18.83 MB |
 * | 二进制列式 + deflate | 约 6.6 MB |
 *
 * 加载耗时同理：CSV 读取+解析 161 ms，列式二进制解码 2 ms（单月 27,536 根）。
 *
 * **关键结论**：真正值钱的是「不要用 ASCII 存数字」，而不是压缩算法选哪个 ——
 * 数据一旦变成二进制列式 + 增量编码，deflate / zstd / brotli 的差距只有 0.5%。
 * 所以这里用 JDK 自带的 deflate（APK 的 zip 本来就压），不引入任何原生库。
 *
 * # 单位约定（这一节是格式的一部分，不是注释）
 *
 * 这个格式的前身踩过一次真实事故：`Candle.spread` 的文档写「价格为单位的原始值」，
 * 实际存的却是**原始点数**（黄金 3 位小数时 200 而不是 0.200），
 * 因为一直没人读它、而且旧测试断言的正是那个错误值，潜伏了很久；
 * 直到「猜涨跌」真的去读它 —— 入场价被抬高 200 美元，一局就把账户打成负数。
 *
 * 所以 GLB1 把单位做成**格式里显式声明的**，而不是靠调用方记得：
 *
 * 1. 全文件**只有一个价格尺度**：文件头里的 [Glb1Format.HEADER_DIGITS]（价格小数位）。
 *    `priceTicks = round(price × 10^digits)`，例如 3 位小数时 2350.123 → 2350123。
 * 2. **凡是价格量一律用这个 tick 单位**：开盘、最高、最低、收盘、点差，
 *    以及它们在列里存的全部增量。**没有第二个尺度。**
 * 3. 唯一的非价格量是成交量，单位是 **tick 笔数**。
 * 4. 读取端只有**一处**把 tick 换算回 Double（[Glb1Reader] 里的 `toPrice`）。
 * 5. 读取端自带合理性自检：点差超过首根价格的 1% 就判定为「单位被写错」，
 *    归零并记录警告 —— 宁可让上层退回默认点差，也不静默算错钱。
 *
 * 注意一个容易骗人的巧合：黄金是 3 位小数，而 MT5 的「点」也是 10^-3，
 * 所以 200 点 = 200 tick = 0.200 美元，**数字一模一样**。
 * 这正是当初那类 bug 难以察觉的原因 —— 靠肉眼比对数值是发现不了的，
 * 只能靠「文件里显式声明尺度 + 单一换算点 + 自检」。
 *
 * # 文件布局
 *
 * ```
 * 偏移  长度  内容
 * 0     4     magic "GLB1"
 * 4     1     version
 * 5     1     digits            ← 价格小数位；全文件唯一尺度
 * 6     1     columnCount (=7)
 * 7     1     reserved (=0)
 * 8     4     barCount          uint32 LE
 * 12    8     timeBaseMillis    int64  LE  第一根 K 线的绝对时间
 * 20    8     baseCloseTicks    int64  LE  第一根收盘价（价格 tick），增量链的起点
 * 28    2     symbolLen         uint16 LE
 * 30    N     symbol            UTF-8
 * 30+N  28    7 × uint32 LE      各列的字节长度（顺序见 [Glb1Column]）
 * ...         7 段列数据，依次拼接
 * ```
 *
 * # 列与编码
 *
 * 列的顺序是格式的一部分，不能改；语义与单位见 [Glb1Column]。
 * 全部整数用 7 位一组的 varint；有符号量用 zigzag（负数也能压到 1~2 字节）。
 */
object Glb1Format {

    /** "GLB1" */
    val MAGIC = byteArrayOf(0x47, 0x4C, 0x42, 0x31)

    const val VERSION = 1

    /** 到 symbolLen 为止的固定头长度 */
    const val HEADER_FIXED = 30

    /** 列长度表占用的字节数 */
    const val COLUMN_TABLE_BYTES = 4 * 7

    const val MIN_DIGITS = 0
    const val MAX_DIGITS = 8

    /**
     * 点差的合理性上限：不得超过首根价格的 1%。
     *
     * 超过就说明写入端的单位搞错了（例如把某个别的尺度当成了 tick）。
     * 宁可退回默认点差并留下警告，也不静默按错的单位算钱。
     */
    const val SPREAD_SANITY_DENOMINATOR = 100L

    /** 10^digits。用循环而非幂运算，避免溢出 */
    fun scale(digits: Int): Long {
        require(digits in MIN_DIGITS..MAX_DIGITS) { "digits 必须在 " + MIN_DIGITS + ".." + MAX_DIGITS + "，实际 " + digits }
        var s = 1L
        repeat(digits) { s *= 10L }
        return s
    }

    /**
     * 价格 → tick。**这是全项目唯一的「价格转整数」入口。**
     *
     * 例如 digits = 3 时 2350.123 → 2350123。
     * 任何新增的读取/写入路径都必须走这里，不要自己乘 10^n ——
     * 单位一旦有两个入口，就一定会有一个写错。
     */
    fun toTicks(price: Double, digits: Int): Long {
        require(price.isFinite()) { "价格不是有限数：" + price }
        return Math.round(price * scale(digits))
    }

    /**
     * tick → 价格。**这是全项目唯一的「整数转价格」入口。**
     *
     * 例如 digits = 3 时 2350123 → 2350.123。
     * 开高低收与**点差**都用它，两者不可能再出现单位错位。
     */
    fun toPrice(ticks: Long, digits: Int): Double = ticks.toDouble() / scale(digits)
}

/**
 * 列定义。**单位写在这里，而不是写在文档里** —— 这样它是可编译、可断言、可搜索的。
 *
 * 顺序即格式里的物理顺序，不能改。
 */
enum class Glb1Column(val displayName: String, val unit: Glb1Unit) {
    /** 距上一根的时间差（毫秒）。第一根为 0，绝对时间在文件头 timeBaseMillis */
    TIME_DELTA("timeDeltaMs", Glb1Unit.MILLISECONDS),

    /** 开盘 − 上一根收盘 */
    OPEN_DELTA("openDelta", Glb1Unit.PRICE_TICKS),

    /** 最高 − 本根开盘 */
    HIGH_DELTA("highDelta", Glb1Unit.PRICE_TICKS),

    /** 最低 − 本根开盘 */
    LOW_DELTA("lowDelta", Glb1Unit.PRICE_TICKS),

    /** 收盘 − 本根开盘 */
    CLOSE_DELTA("closeDelta", Glb1Unit.PRICE_TICKS),

    /** Tick 成交量。**这不是价格量**，所以不属于 tick 尺度 */
    VOLUME("volume", Glb1Unit.TICKS_TRADED),

    /** 点差 − 上一根点差。**与价格同一个 tick 尺度**，不再有第二个单位 */
    SPREAD_DELTA("spreadDelta", Glb1Unit.PRICE_TICKS),
    ;

    companion object {
        val BY_INDEX: Array<Glb1Column> = entries.toTypedArray()
        val COUNT: Int get() = entries.size
    }
}

/**
 * 列里数字的单位。
 *
 * [PRICE_TICKS] 的含义由文件头的 digits 决定：
 * `真实价格 = ticks / 10^digits`。所有价格量都用它，没有例外。
 */
enum class Glb1Unit {
    MILLISECONDS,
    PRICE_TICKS,
    TICKS_TRADED,
}

// ------------------------------------------------------------------ varint

/** 7 位一组的 varint 编解码。有符号量走 zigzag，小负数也只占 1 字节。 */
internal object Varints {

    fun putUnsigned(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun putSigned(out: ByteArrayOutputStream, value: Long) {
        putUnsigned(out, (value shl 1) xor (value shr 63))
    }
}

/** 在定长字节数组上顺序读 varint */
internal class VarintReader(private val buf: ByteArray, private var pos: Int) {

    val position: Int get() = pos

    fun unsigned(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < buf.size) { "varint 越界：数据被截断" }
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift <= 63) { "varint 过长（超过 64 位）" }
        }
    }

    fun signed(): Long {
        val n = unsigned()
        return (n ushr 1) xor -(n and 1L)
    }
}
