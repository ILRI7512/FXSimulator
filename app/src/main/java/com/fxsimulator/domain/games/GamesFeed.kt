package com.fxsimulator.domain.games

import com.fxsimulator.domain.model.Candle

/**
 * 玩法用的行情流：**按月增量加载**，播完一月自动接下一月。
 *
 * ## 为什么不是一次性全load
 * 一年 = 12 个月 × ~43,200 根 ≈ 52 万根。`Candle` 是 8 个字段的对象，
 * 52 万根常驻约 30–40 MB —— 在中低端手机上足够触发 GC 抖动甚至 OOM。
 * 所以这里只保证"当前已播到的月份 + 之前已播的月份"在内存里，
 * 未播到的月份**不加载**。
 *
 * ## 为什么不丢弃已播过的月份
 * 结算要用**绝对下标**：事件合约要取 `expireIndex` 往前 5 根、期权要往前 30 根，
 * 而且 30 天期权要往前 43,200 根。如果滚动丢弃旧数据，下标就会错位，
 * 结算价会算在错误的位置上 —— 那比 OOM 更糟（是静默的错）。
 *
 * ## 跨度
 * [minMonths] 默认 12（一年）。真实资产 BTC/ETH 各有 37 个月，够用。
 * 如果可用月份不足，就退化为"有多少用多少"，并在 [spanDays] 上如实体现。
 */
class GamesFeed(
    /** 可用月份（升序，yyyy-MM） */
    val months: List<String>,
    /** 按月加载：由调用方决定数据从哪里来（资产 / 网络 / 测试假数据） */
    private val loader: (String) -> List<Candle>,
) {
    var bars: List<Candle> = emptyList()
        private set

    /** 已加载到第几个月（0 基）；-1 表示还没加载。 */
    var loadedCount: Int = 0
        private set

    val hasMoreMonths: Boolean get() = loadedCount < months.size

    /** 回放跨度（天），由已加载数据首尾时间算出。 */
    val spanDays: Long
        get() {
            if (bars.size < 2) return 0
            return (bars.last().timeMillis - bars.first().timeMillis) / 86_400_000L
        }

    /**
     * 加载下一个月并追加。
     *
     * @return true 表示确实追加了数据；false 表示没有更多月份（或该月为空）
     */
    fun loadNext(): Boolean {
        if (!hasMoreMonths) return false
        val month = months[loadedCount]
        val fresh = loader(month)
        loadedCount++
        if (fresh.isEmpty()) return false
        bars = if (bars.isEmpty()) {
            fresh.sortedBy { it.timeMillis }
        } else {
            // 按时间合并并去重：跨月边界上偶尔会有重复时间戳（资产切分导致），
            // 保留先出现的、丢弃后出现的，保证时间严格递增。
            val merged = ArrayList<Candle>(bars.size + fresh.size)
            merged.addAll(bars)
            val lastTime = bars.last().timeMillis
            for (c in fresh) if (c.timeMillis > lastTime) merged.add(c)
            merged
        }
        return true
    }

    /** 一次加载到满足 [minMonths] 个月（不足则全加载）。 */
    fun loadAtLeast(minMonths: Int): Int {
        var loaded = 0
        while (hasMoreMonths && loadedCount < minMonths) {
            if (loadNext()) loaded++
            else if (!hasMoreMonths) break
        }
        return loaded
    }

    companion object {
        /** 默认至少要凑够一年。 */
        const val MIN_MONTHS_FOR_A_YEAR = 12

        /** 从可用月份里取最近 [minMonths] 个月（不足则全取）。 */
        fun pickMonths(available: List<String>, minMonths: Int = MIN_MONTHS_FOR_A_YEAR): List<String> {
            if (available.isEmpty()) return emptyList()
            val sorted = available.sorted()
            return if (sorted.size <= minMonths) sorted else sorted.takeLast(minMonths)
        }

        /**
         * 从 [available] 里取「不早于 [startMonth]」的月份，最多 [minMonths] 个。
         *
         * 用途：让玩家自己选起始月份。`startMonth` 不在列表里（资产换版本后月份会变）时
         * 退回最近 [minMonths] 个月，而不是报错 —— 静默退化比崩掉好。
         */
        fun monthsFrom(
            available: List<String>,
            startMonth: String?,
            minMonths: Int = MIN_MONTHS_FOR_A_YEAR,
        ): List<String> {
            if (available.isEmpty()) return emptyList()
            val sorted = available.sorted()
            val start = startMonth?.trim()
            if (start.isNullOrEmpty()) return pickMonths(sorted, minMonths)
            val fromIndex = sorted.indexOfFirst { it >= start }
            if (fromIndex < 0) return pickMonths(sorted, minMonths)
            return sorted.drop(fromIndex).take(minMonths)
        }
    }
}
