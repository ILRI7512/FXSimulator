package com.fxsimulator.domain.model

/**
 * 把 M1 K 线聚合成更高周期。
 *
 * 纯函数、无副作用，便于单元测试。
 * 聚合只做「同一时间桶内的开高低收合并」，不插值、不补造数据。
 */
object CandleAggregator {

    /**
     * 聚合整段序列。输入必须按时间升序（行情源已保证）。
     * 最后一根可能是尚未走完的「未完成」聚合 K 线，这里照常返回。
     */
    fun aggregate(m1: List<Candle>, tf: Timeframe): List<Candle> {
        if (tf == Timeframe.M1 || m1.isEmpty()) return m1

        val out = ArrayList<Candle>(m1.size / tf.minutes + 1)
        var bucket = Long.MIN_VALUE
        var open = 0.0
        var high = 0.0
        var low = 0.0
        var close = 0.0
        var volume = 0L
        var spread = 0.0

        for (bar in m1) {
            val start = tf.bucketStart(bar.timeMillis)
            if (start != bucket) {
                if (bucket != Long.MIN_VALUE) {
                    out.add(Candle(bucket, open, high, low, close, volume, spread))
                }
                bucket = start
                open = bar.open
                high = bar.high
                low = bar.low
                close = bar.close
                volume = bar.volume
                spread = bar.spread
            } else {
                if (bar.high > high) high = bar.high
                if (bar.low < low) low = bar.low
                close = bar.close
                volume += bar.volume
            }
        }
        if (bucket != Long.MIN_VALUE) {
            out.add(Candle(bucket, open, high, low, close, volume, spread))
        }
        return out
    }

    /**
     * 把一根新的 M1 并入「当前正在形成」的聚合 K 线。
     *
     * 回放过程中每来一根 M1 就调用一次；跨桶时返回新的聚合 K 线，
     * 同桶时返回合并后的同一根。语义与 [aggregate] 完全一致。
     */
    fun mergeInto(last: Candle?, m1: Candle, tf: Timeframe): Candle {
        val start = tf.bucketStart(m1.timeMillis)
        if (last == null || last.timeMillis != start) {
            return Candle(start, m1.open, m1.high, m1.low, m1.close, m1.volume, m1.spread)
        }
        return last.copy(
            high = maxOf(last.high, m1.high),
            low = minOf(last.low, m1.low),
            close = m1.close,
            volume = last.volume + m1.volume,
        )
    }
}
