package com.fxsimulator.domain.model

/**
 * 图表周期。
 *
 * 第一版的历史行情只有 M1，因此**其他周期全部由 M1 现场聚合**得到，
 * 与 MT5 的做法一致：周线以下是按时间对齐后合并 OHLC，
 * 聚合出来的高/低/开/收与原始 M1 完全自洽，不会凭空捏造数据。
 *
 * 对齐规则（沿用本项目的 UTC 约定）：
 * - M1/M5/M15/M30/H1/H4：按 epoch 起算的整数倍对齐，
 *   因此 H4 的边界是 UTC 00:00 / 04:00 / 08:00 …；
 * - D1：对齐到 UTC 自然日 00:00。
 *
 * 注意：数据本身是经纪商服务器时间，本项目按 UTC 解释（不做时区推断），
 * 所以周期边界以服务器时间为准。
 */
enum class Timeframe(val label: String, val minutes: Int) {
    M1("M1", 1),
    M5("M5", 5),
    M15("M15", 15),
    M30("M30", 30),
    H1("H1", 60),
    H4("H4", 240),
    D1("D1", 1440),
    ;

    /** 一个周期的毫秒数 */
    val millis: Long get() = minutes * 60_000L

    /** 把时间戳对齐到所在周期的起始时刻 */
    fun bucketStart(timeMillis: Long): Long = when (this) {
        M1 -> timeMillis
        D1 -> timeMillis - Math.floorMod(timeMillis, ONE_DAY_MILLIS)
        else -> timeMillis - Math.floorMod(timeMillis, millis)
    }

    companion object {
        private const val ONE_DAY_MILLIS = 86_400_000L

        /** 由名字还原（持久化用），非法值回落到 M1 */
        fun fromName(name: String?): Timeframe =
            entries.firstOrNull { it.name == name } ?: M1
    }
}
