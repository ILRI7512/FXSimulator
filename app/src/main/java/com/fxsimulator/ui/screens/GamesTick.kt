package com.fxsimulator.ui.screens

import com.fxsimulator.domain.market.TickSynthesizer
import com.fxsimulator.domain.model.Candle
import java.util.Locale

/**
 * 三个玩法共用的「模拟 tick」口径与作图辅助。
 *
 * ## 口径（写死在这，三处一致）
 * - 1 分钟 = 12 个 tick，每个 tick = **5 秒行情**；
 * - 现实时间：每个 tick 占 `1000ms ÷ 倍速`，所以 **1× 下 12 秒走完 1 分钟**；
 * - 价格与图都按 tick 更新，结算仍按 1 分钟 TWAP。
 *
 * ## 为什么默认开启且不提供开关
 * 关掉 tick 时价格与图一分钟才动一次，玩法看起来像"静止的赌大小"；
 * 开着才是连续报价。所以不把它做成选项，而是**默认行为**，并在界面上说明节奏。
 */
internal object GamesTick {

    const val PER_BAR = TickSynthesizer.TICKS_PER_BAR

    /** 每根 1 分钟里的第 [subTick] 个 tick 对应的现价。 */
    fun price(bars: List<Candle>, subTick: Int): Double {
        val last = bars.lastOrNull() ?: return 0.0
        val idx = subTick.coerceIn(0, PER_BAR - 1)
        val ticks = TickSynthesizer.split(last)
        return ticks.getOrNull(idx)?.close ?: last.close
    }

    /**
     * 作图用的 bars：最后一根按已走完的 tick 动态成形（未收完的 K 线）。
     *
     * 不改前面的历史，也不新增蜡烛 —— 图上就是"当前这根在长"。
     */
    fun displayBars(bars: List<Candle>, subTick: Int): List<Candle> {
        if (bars.isEmpty()) return bars
        val last = bars.last()
        val ticks = TickSynthesizer.split(last)
        // 用「未收完的 1 分钟 K 线」表示进度：开=原开、收=当前 tick 价、高/低=已走区间极值。
        // 不做成"12 根 5 秒小蜡烛"——那会把两种粒度混在同一张图上，时间轴会变得不可读。
        val upto = ticks.take(subTick.coerceIn(1, ticks.size))
        if (upto.isEmpty()) return bars
        val o = last.open
        val c = upto.last().close
        val h = maxOf(o, upto.maxOf { it.high })
        val l = minOf(o, upto.minOf { it.low })
        return bars.dropLast(1) + last.copy(open = o, high = h, low = l, close = c)
    }

    /** 界面上的节奏说明（三个页面共用同一句，避免口径漂移）。 */
    fun hint(speed: Int): String {
        val ms = 1000 / speed
        val sec = 12.0 / speed
        return "模拟 Tick：每 ${ms}ms 走 5 秒行情｜1 分钟 K 线 = " +
            String.format(Locale.US, "%.1f", sec) + " 秒（${speed}×）｜结算按 1 分钟 TWAP"
    }
}
