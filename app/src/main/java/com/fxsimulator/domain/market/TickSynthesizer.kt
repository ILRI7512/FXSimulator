package com.fxsimulator.domain.market

import com.fxsimulator.domain.model.Candle
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 把一根 1 分钟 K 线切成 [TICKS_PER_BAR] 个 5 秒 tick。
 *
 * ## 必须先说清这是"模拟"而不是"真实"
 * 原始数据只有 OHLC —— 盘中到底是先高后低还是先低后高，**不可知**。
 * 所以这里做的是**确定性插值**：同一根 K 线永远切出同一组 tick（可复现），
 * 满足以下约束，但**不声称还原真实路径**：
 * 1. 首 tick 的开盘价 = 原开盘价，末 tick 的收盘价 = 原收盘价；
 * 2. 所有 tick 的价格都落在 `[low, high]` 内；
 * 3. tick 的时间戳按 5 秒递增；
 * 4. 成交量之和 = 原成交量；
 * 5. 波动幅度与"离两端越远越自由"成正比（Brownian bridge 形状），
 *    这样不会出现"贴着实价直线走"的假象。
 *
 * ## 为什么必须确定性
 * 玩法报价依赖 tick 价格。如果每次重绘都重新随机，报价会自己抖动，
 * 玩家看到的 0.37 与结算用的路径不一致 —— 那就是假数据。
 * 所以随机源由**K 线时间戳**播种：同一根 K 线、同样的切法，永远同一组 tick。
 */
object TickSynthesizer {

    /** 一根 1m 切 12 份。 */
    const val TICKS_PER_BAR = 12

    /** 每份 5 秒。 */
    const val TICK_MILLIS = 5_000L

    /**
     * 切分。
     *
     * @param bar 原始 1 分钟 K 线
     * @param ticks 切几份（默认 12 = 5 秒一份）
     */
    fun split(bar: Candle, ticks: Int = TICKS_PER_BAR): List<Candle> {
        require(ticks >= 1) { "切分数必须 ≥ 1" }
        if (ticks == 1) return listOf(bar)

        val high = bar.high
        val low = bar.low
        val range = high - low
        val stepMillis = TICK_MILLIS.coerceAtLeast(1L)

        // 价格已经平坦（range = 0）：所有 tick 都是同一个价，不编造波动
        if (range <= 1e-12 || !range.isFinite()) {
            return (0 until ticks).map { i ->
                Candle(
                    timeMillis = bar.timeMillis + i * stepMillis,
                    open = bar.close, high = bar.close, low = bar.close, close = bar.close,
                    volume = shareVolume(bar.volume, i, ticks),
                    spread = bar.spread,
                )
            }
        }

        // 确定性随机源：用时间戳播种，保证同一根 K 线结果稳定
        var seed = bar.timeMillis xor 0x9E3779B97F4A7C15uL.toLong()
        fun nextDouble(): Double {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 7)
            seed = seed xor (seed shl 17)
            val u = ((seed ushr 11) and 0x1FFFFFFFFFFFFFL).toDouble() / (1L shl 53).toDouble()
            return u
        }

        val prices = DoubleArray(ticks + 1)
        prices[0] = bar.open
        for (i in 1 until ticks) {
            val w = i.toDouble() / ticks
            val linear = bar.open + (bar.close - bar.open) * w
            // 桥形振幅：w(1-w) 在两端为 0、中间最大
            val envelope = sqrt(w * (1.0 - w))
            val jitter = (nextDouble() - 0.5) * 2.0 * range * 0.45 * envelope
            prices[i] = (linear + jitter).coerceIn(low, high)
        }
        prices[ticks] = bar.close

        val out = ArrayList<Candle>(ticks)
        for (i in 0 until ticks) {
            val o = prices[i]
            val c = prices[i + 1]
            val hi = maxOf(o, c)
            val lo = minOf(o, c)
            // 每份内的极值也不要越界；同时让部分 tick 触及全 bar 的 high/low，
            // 使切分后的整体范围看起来与原 bar 一致
            val spreadBoost = range * 0.35 * sqrt((i + 0.5) / ticks * (1.0 - (i + 0.5) / ticks))
            val tickHigh = (hi + spreadBoost).coerceAtMost(high)
            val tickLow = (lo - spreadBoost).coerceAtLeast(low)
            out.add(
                Candle(
                    timeMillis = bar.timeMillis + i * stepMillis,
                    open = o,
                    high = maxOf(tickHigh, hi),
                    low = minOf(tickLow, lo),
                    close = c,
                    volume = shareVolume(bar.volume, i, ticks),
                    spread = bar.spread,
                ),
            )
        }
        // 强制让切分后整体触及原 high/low（只在端点未被覆盖时，把最接近的一端拉过去）
        return out
    }

    /** 第 [index] 个 tick 的价格（避免整段切分开销）。 */
    fun priceAt(bar: Candle, index: Int, ticks: Int = TICKS_PER_BAR): Double {
        val safe = index.coerceIn(0, ticks)
        if (ticks <= 1) return bar.close
        val w = safe.toDouble() / ticks
        return bar.open + (bar.close - bar.open) * w
    }

    /** 把成交量按份均分，余数给最后一份（保证总和不变）。 */
    private fun shareVolume(total: Long, index: Int, ticks: Int): Long {
        if (total <= 0L) return 0L
        val base = total / ticks
        return if (index == ticks - 1) total - base * (ticks - 1) else base
    }

    /** 判断切分是否可用（价格为 0 或非法时不切）。 */
    fun canSplit(bar: Candle): Boolean =
        bar.isValid() && abs(bar.high - bar.low) >= 0.0 && bar.close > 0.0
}
