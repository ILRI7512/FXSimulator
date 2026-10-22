package com.fxsimulator.domain.event

import com.fxsimulator.domain.model.Candle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 事件合约定价（方案 3.3）。
 *
 * ```
 * σ      = 近 60 根 1m 对数收益标准差 × sqrt(525600)   // 年化
 * T      = 剩余秒数 / (365×24×3600)
 * d      = [ln(S/K) + 0.5σ²T] / (σ√T)                 // 漂移取 0
 * p_fair = N(d)
 * m      = 情绪（AR(1)，±0.15）
 * p_quote= clamp(p_fair×(1+m) + noise, 0.05, 0.95)
 * bid/ask= p_quote ∓ 0.005
 * ```
 *
 * ## 为什么目标价就是现价时 p_fair = 0.5
 * 目标价取**周期开始时刻的标记价**，于是 S = K、ln(S/K) = 0、d = 0、N(0) = 0.5。
 * 这不是偷懒，而是方案明确要求的口径：真实 BTC/ETH 5 分钟上涨占比实测 49.7%/49.1%，
 * 无漂移近似成立；否则玩家会发现「永远押一边就赢」。
 *
 * 所以玩家的利润来源不是 p_fair，而是**情绪偏移 m**：报价会周期性偏离 0.5，
 * 能看出 K 线结构的人长期胜率高于报价隐含概率。
 *
 * `p_fair` 仍然按完整公式计算而不是直接写 0.5 —— 因为提前平仓时 S 会偏离 K，
 * 那时需要真实公式。
 */
object EventPricing {

    /** 一年多少分钟（1m K 线口径）。365×24×60 = 525600。 */
    const val MINUTES_PER_YEAR = 525_600.0

    /** 情绪硬上限（方案：夹在 ±0.15）。 */
    const val MAX_SENTIMENT = 0.15

    /** 情绪自回归系数。 */
    const val SENTIMENT_DECAY = 0.92

    /** 情绪噪声标准差。 */
    const val SENTIMENT_SIGMA = 0.03

    /** 报价噪声上限（方案 ±0.01）。 */
    const val MAX_NOISE = 0.01

    data class Quote(
        /** 买价（玩家卖出时得到的价） */
        val bid: Double,
        /** 卖价（玩家买入时付出的价） */
        val ask: Double,
        /** 中间价 = 隐含概率 */
        val mid: Double,
        val fairProbability: Double,
        /** 年化波动率（用于界面展示"市场认为多波动"） */
        val annualVol: Double,
    ) {
        fun buyPrice(side: EventSide): Double = if (side == EventSide.UP) ask else 1.0 - bid + 0.0
    }

    /** 年化波动率：近 [lookback] 根 1m 对数收益标准差 × sqrt(525600)。 */
    fun annualizedVol(candles: List<Candle>, lookback: Int = 60): Double {
        if (candles.size < 3) return 0.0
        val w = candles.takeLast(lookback.coerceAtLeast(3))
        val returns = ArrayList<Double>(w.size - 1)
        for (i in 1 until w.size) {
            val prev = w[i - 1].close
            if (prev > 0.0 && w[i].close > 0.0) returns.add(ln(w[i].close / prev))
        }
        if (returns.size < 2) return 0.0
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1)
        return sqrt(variance) * sqrt(MINUTES_PER_YEAR)
    }

    /**
     * 无漂移 GBM 的公平概率。
     *
     * 注意这里**不含** `+0.5σ²T` 项：方案 3.3 的注释明确写"漂移取 0 时 d = ln(S/K)/(σ√T)"。
     * 若保留该项，S=K 会得到 0.5002 而不是 0.5，与"N(0)=0.5、报价围绕 0.5 抖动"的设计相矛盾。
     */
    fun fairProbability(spot: Double, target: Double, annualVol: Double, secondsLeft: Double): Double {
        if (spot <= 0.0 || target <= 0.0) return 0.5
        if (secondsLeft <= 0.0) return if (spot >= target) 1.0 else 0.0
        if (annualVol <= 1e-9) return if (spot >= target) 1.0 else 0.0
        val t = secondsLeft / (365.0 * 24.0 * 3600.0)
        val volT = annualVol * sqrt(t)
        if (volT <= 1e-12) return if (spot >= target) 1.0 else 0.0
        val d = ln(spot / target) / volT
        return normalCdf(d)
    }

    /**
     * 报价 = **结算价（剩余窗口均价）高于目标价的概率**。
     *
     * ## 为什么不能只看现价
     * 结算价是"到期前 N 根 1m 收盘均价"（N = min(周期, 5)），不是到期那一刻的现价。
     * 所以概率要对**均价**建模：
     * - 窗口里**已经走完**的根是已知数，直接计入均值；
     * - **还没走完**的根按随机游走估计，权重按距离现在的时间递增（第 j 根方差 ∝ j）。
     *
     * 这自然给出 Gate 上的三个真实行为：
     * 1. 轮次开始时窗口全是未来根 → 均价期望 = 现价 → p ≈ 0.5；
     * 2. 随着窗口被填满，已知部分把均值"钉住" → 剩余时间越短，p 越极端（趋近 0.01/0.99）；
     * 3. 累计均价一旦穿过目标价，p 立刻从 0.0x 翻到 0.99x。
     *
     * @param target 目标价（轮次开始时固定）
     * @param bars 已推进的 1m K 线（last 为当前根）
     * @param expireIndex 结算根在 [bars] 中的下标（若 > bars.lastIndex 则尚未到期）
     * @param cycleMinutes 周期分钟数（决定结算窗口 N）
     * @param annualVol 年化波动率
     */
    fun settlementProbability(
        target: Double,
        bars: List<Candle>,
        expireIndex: Int,
        cycleMinutes: Int,
        annualVol: Double,
        /** 末尾还有几根是"正在形成"的（tick 模式下当前这根 1m 尚未收完），不能算已知 */
        formingBars: Int = 0,
        /** 现价覆盖（tick 价）；null 则用最后一根收盘 */
        spotOverride: Double? = null,
    ): Double {
        if (bars.isEmpty() || target <= 0.0) return 0.5
        // 真 TWAP：整个周期所有 1m 根等权参与（5m→5 根、1h→60 根、4h→240 根）
        val n = cycleMinutes.coerceAtLeast(1)
        val lastIndex = bars.lastIndex - formingBars.coerceIn(0, bars.size)
        val firstIndex = expireIndex - n + 1

        // 窗口里已确定的根（下标 <= 当前根）
        var knownSum = 0.0
        var knownCount = 0
        val knownStart = maxOf(firstIndex, 0)
        val knownEnd = minOf(expireIndex, lastIndex)
        if (knownStart <= knownEnd) {
            for (i in knownStart..knownEnd) {
                val c = bars[i].close
                if (c.isFinite() && c > 0.0) { knownSum += c; knownCount++ }
            }
        }
        // 尚未发生的根（到期前还剩几根进入窗口）
        val future = (n - knownCount).coerceAtLeast(0)

        val spot = spotOverride ?: bars.last().close
        if (spot <= 0.0) return 0.5
        // 全部未来：均值期望 = spot
        if (knownCount == 0 && future == n && formingBars == 0) {
            val sigmaBar = annualVol / sqrt(MINUTES_PER_YEAR)
            val varSum = spot * spot * sigmaBar * sigmaBar * (n * (n + 1) / 2.0)
            val sd = sqrt(varSum) / n
            if (sd <= 1e-12) return if (spot >= target) EventRules.MAX_PRICE else EventRules.MIN_PRICE
            return normalCdf((spot - target) / sd).coerceIn(EventRules.MIN_PRICE, EventRules.MAX_PRICE)
        }

        // 混合：已知部分固定，未来部分按 random walk 加权
        val sigmaBar = annualVol / sqrt(MINUTES_PER_YEAR)
        val mean = (knownSum + future * spot) / n
        // Σ_{j=1..future} j = future(future+1)/2，每根方差 ∝ j
        val varSum = spot * spot * sigmaBar * sigmaBar * (future * (future + 1) / 2.0)
        val sd = sqrt(varSum) / n
        if (sd <= 1e-12) {
            return if (mean >= target) EventRules.MAX_PRICE else EventRules.MIN_PRICE
        }
        return normalCdf((mean - target) / sd).coerceIn(EventRules.MIN_PRICE, EventRules.MAX_PRICE)
    }

    /** 报价（买卖价 = 中价 ∓ 半价差）。 */
    fun quote(
        target: Double,
        bars: List<Candle>,
        expireIndex: Int,
        cycleMinutes: Int,
        annualVol: Double,
        noise: Double = 0.0,
        sentiment: Double = 0.0,
        formingBars: Int = 0,
        spotOverride: Double? = null,
    ): Quote {
        val fair = settlementProbability(
            target, bars, expireIndex, cycleMinutes, annualVol, formingBars, spotOverride,
        )
        val m = sentiment.coerceIn(-MAX_SENTIMENT, MAX_SENTIMENT)
        val e = noise.coerceIn(-MAX_NOISE, MAX_NOISE)
        val mid = (fair * (1.0 + m) + e).coerceIn(EventRules.MIN_PRICE, EventRules.MAX_PRICE)
        return Quote(
            bid = (mid - EventRules.HALF_SPREAD).coerceAtLeast(EventRules.MIN_PRICE),
            ask = (mid + EventRules.HALF_SPREAD).coerceAtMost(EventRules.MAX_PRICE),
            mid = mid,
            fairProbability = fair,
            annualVol = annualVol,
        )
    }
    /** AR(1) 情绪递推：m_t = 0.92·m_{t-1} + ε，ε 由调用方给（可复现）。 */
    fun nextSentiment(previous: Double, epsilon: Double): Double {
        val next = SENTIMENT_DECAY * previous + epsilon
        return next.coerceIn(-MAX_SENTIMENT, MAX_SENTIMENT)
    }

    /** 标准正态 CDF（Abramowitz-Stegun 7.1.26，误差 < 1.5e-7）。 */
    fun normalCdf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
            0.254829592) * t * exp(-ax * ax)
        return 0.5 * (1.0 + sign * y)
    }
}
