package com.fxsimulator.ui.screens

import android.content.Context
import com.fxsimulator.data.market.BundledMarketData
import com.fxsimulator.domain.games.GamesFeed
import com.fxsimulator.domain.model.Candle

/**
 * 玩法页共用的行情驱动：**按月增量加载，播完自动接下一月，保证至少一年可用**。
 *
 * 为什么玩法不走「交易」页的履约回放：那里带持仓/保证金，节奏由用户点播放控制；
 * 玩法需要的是自动推进的行情时钟，两套时钟互不干扰。
 */
internal object GamesMarket {

    /** 该品种所有可用月份（升序）。 */
    fun allMonths(context: Context, symbol: String): List<String> =
        runCatching { BundledMarketData.availableMonths(context, symbol) }
            .getOrDefault(emptyList())
            .sorted()

    /** 该品种可用于玩法的月份（最近 12 个月；不足则全给）。 */
    fun monthsFor(context: Context, symbol: String): List<String> =
        GamesFeed.pickMonths(allMonths(context, symbol), GamesFeed.MIN_MONTHS_FOR_A_YEAR)

    /** 单个自然月的 1m K 线。 */
    fun loadMonth(context: Context, symbol: String, month: String): List<Candle> {
        val src = runCatching { BundledMarketData.load(context, month, symbol) }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<Candle>(src.size)
        for (i in 0 until src.size) src.barAt(i)?.let { out.add(it) }
        return out
    }

    /**
     * 构造按月加载的 feed（此时**尚未加载任何数据**，调用方先 `loadNext()` 拿第一个月）。
     *
     * 这样设计是为了不在进入页面时就把一年（约 52 万根）塞进内存。
     */
    fun feed(context: Context, symbol: String, startMonth: String? = null): GamesFeed {
        val months = GamesFeed.monthsFrom(allMonths(context, symbol), startMonth)
        return GamesFeed(months) { month -> loadMonth(context, symbol, month) }
    }
}
