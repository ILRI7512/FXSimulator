package com.fxsimulator.indicators

import com.fxsimulator.domain.model.Candle

/**
 * 技术指标计算。全部为**纯函数**，与 UI 无关，便于单元测试。
 *
 * 约定：
 * - 返回数组长度与输入相同；
 * - 数据不足以完成初始化时，对应位置返回 [NA]（NaN），
 *   绝不用 0 或首个价格等假数据填充，避免「虚假有效指标」。
 *
 * 指标只用于模拟学习，不代表对未来走势的预测。
 */
object Indicators {

    /** 无效值标记 */
    const val NA: Double = Double.NaN

    fun isValid(v: Double): Boolean = !v.isNaN() && v.isFinite()

    /**
     * 指数移动平均 EMA。
     * 初始化方式：第 (period-1) 个位置用前 period 个值的**简单平均**做种子，
     * 之后按 alpha = 2 / (period + 1) 递推。这是业界最常见的初始化方式。
     */
    fun ema(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0 || values.size < period) return out

        var sum = 0.0
        for (i in 0 until period) {
            if (!values[i].isFinite()) return out
            sum += values[i]
        }
        var prev = sum / period
        out[period - 1] = prev

        val alpha = 2.0 / (period + 1.0)
        for (i in period until values.size) {
            val v = values[i]
            if (!v.isFinite()) { out[i] = NA; continue }
            prev = v * alpha + prev * (1.0 - alpha)
            out[i] = prev
        }
        return out
    }

    /**
     * RSI（Wilder 平滑）。
     * 需要至少 period+1 个价格；第 period 个位置才有第一个有效值。
     * 当平均跌幅为 0 时 RSI 记为 100（严格意义的无下跌）。
     */
    fun rsi(values: DoubleArray, period: Int = 14): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0 || values.size < period + 1) return out

        var gainSum = 0.0
        var lossSum = 0.0
        for (i in 1..period) {
            val a = values[i - 1]
            val b = values[i]
            if (!a.isFinite() || !b.isFinite()) return out
            val d = b - a
            if (d >= 0) gainSum += d else lossSum += -d
        }
        var avgGain = gainSum / period
        var avgLoss = lossSum / period
        out[period] = rsiFrom(avgGain, avgLoss)

        for (i in period + 1 until values.size) {
            val a = values[i - 1]
            val b = values[i]
            if (!a.isFinite() || !b.isFinite()) { out[i] = NA; continue }
            val d = b - a
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            out[i] = rsiFrom(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiFrom(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss <= 0.0) return if (avgGain <= 0.0) 50.0 else 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /**
     * 真实波幅 TR。第 0 根没有前一根收盘价，用 high-low 代替。
     */
    fun trueRange(candles: List<Candle>): DoubleArray {
        val out = DoubleArray(candles.size) { NA }
        if (candles.isEmpty()) return out
        out[0] = candles[0].high - candles[0].low
        for (i in 1 until candles.size) {
            val c = candles[i]
            val pc = candles[i - 1].close
            val tr = Math.max(c.high - c.low, Math.max(Math.abs(c.high - pc), Math.abs(c.low - pc)))
            out[i] = tr
        }
        return out
    }

    /**
     * ATR（Wilder 平滑）。
     * 第 (period-1) 个位置用前 period 个 TR 的简单平均做种子。
     */
    fun atr(candles: List<Candle>, period: Int = 14): DoubleArray {
        val out = DoubleArray(candles.size) { NA }
        if (period <= 0 || candles.size < period) return out
        val tr = trueRange(candles)
        var sum = 0.0
        for (i in 0 until period) {
            if (!tr[i].isFinite()) return out
            sum += tr[i]
        }
        var prev = sum / period
        out[period - 1] = prev
        for (i in period until candles.size) {
            val t = tr[i]
            if (!t.isFinite()) { out[i] = NA; continue }
            prev = (prev * (period - 1) + t) / period
            out[i] = prev
        }
        return out
    }

    /** 取数组中最后一个有效值，没有则返回 null */
    fun lastValid(arr: DoubleArray): Double? {
        for (i in arr.indices.reversed()) {
            if (isValid(arr[i])) return arr[i]
        }
        return null
    }

    /** 转换成「只保留最近 n 个有效值」的展示列表 */
    fun tailValid(arr: DoubleArray, count: Int): List<Double> {
        val res = ArrayList<Double>(count)
        for (i in arr.indices.reversed()) {
            if (isValid(arr[i])) {
                res.add(arr[i])
                if (res.size >= count) break
            }
        }
        return res.reversed()
    }

    // ================================================================
    //  以下为「指标工具库」所需的扩展指标。全部遵循同一约定：
    //  返回数组与输入等长，预热期为 NA，绝不填假值。
    // ================================================================

    /**
     * 可跳过前置缺口的 EMA。
     *
     * 与 [ema] 的区别：[ema] 要求前 period 个输入全部有效，否则整条返回 NA。
     * 而 MACD 这类指标的输入（DIF）本身就带前置缺口，必须能从中段的第一个
     * 完整窗口开始累积，否则 MACD 永远算不出来。
     *
     * 缺口处理：**预热期遇到缺口重置计数**（不能跨越缺口凑周期），
     * **预热完成后遇到缺口跳过但保留状态**（一个空缺不该让 EMA 归零重来）。
     */
    fun emaFrom(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0) return out
        val alpha = 2.0 / (period + 1.0)
        var prev = NA
        var sum = 0.0
        var count = 0
        for (i in values.indices) {
            val v = values[i]
            if (!v.isFinite()) {
                if (prev.isNaN()) { sum = 0.0; count = 0 }
                continue
            }
            if (prev.isNaN()) {
                sum += v
                count++
                if (count >= period) {
                    prev = sum / period
                    out[i] = prev
                }
            } else {
                prev = v * alpha + prev * (1.0 - alpha)
                out[i] = prev
            }
        }
        return out
    }

    /** 简单移动平均 SMA */
    fun sma(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0 || values.size < period) return out
        var sum = 0.0
        for (i in values.indices) {
            val v = values[i]
            if (!v.isFinite()) { sum = 0.0; continue }
            sum += v
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    /** 线性加权移动平均 WMA：最近的权重最大（1..period） */
    fun wma(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0 || values.size < period) return out
        val denom = period * (period + 1) / 2.0
        for (i in period - 1 until values.size) {
            var acc = 0.0
            var ok = true
            for (k in 0 until period) {
                val v = values[i - period + 1 + k]
                if (!v.isFinite()) { ok = false; break }
                acc += v * (k + 1)
            }
            if (ok) out[i] = acc / denom
        }
        return out
    }

    /** 窗口内标准差（总体标准差，除以 n） */
    fun stdDev(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 1 || values.size < period) return out
        for (i in period - 1 until values.size) {
            var sum = 0.0
            var ok = true
            for (k in i - period + 1..i) {
                if (!values[k].isFinite()) { ok = false; break }
                sum += values[k]
            }
            if (!ok) continue
            val mean = sum / period
            var acc = 0.0
            for (k in i - period + 1..i) {
                val d = values[k] - mean
                acc += d * d
            }
            out[i] = Math.sqrt(acc / period)
        }
        return out
    }

    /** 窗口内最高值 */
    fun highest(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0 || values.size < period) return out
        for (i in period - 1 until values.size) {
            var m = -Double.MAX_VALUE
            var ok = true
            for (k in i - period + 1..i) {
                if (!values[k].isFinite()) { ok = false; break }
                if (values[k] > m) m = values[k]
            }
            if (ok) out[i] = m
        }
        return out
    }

    /** 窗口内最低值 */
    fun lowest(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0 || values.size < period) return out
        for (i in period - 1 until values.size) {
            var m = Double.MAX_VALUE
            var ok = true
            for (k in i - period + 1..i) {
                if (!values[k].isFinite()) { ok = false; break }
                if (values[k] < m) m = values[k]
            }
            if (ok) out[i] = m
        }
        return out
    }

    /** 布林带：返回 (上轨, 中轨, 下轨) */
    fun bollinger(values: DoubleArray, period: Int, mult: Double): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val mid = sma(values, period)
        val sd = stdDev(values, period)
        val up = DoubleArray(values.size) { NA }
        val low = DoubleArray(values.size) { NA }
        for (i in values.indices) {
            if (!mid[i].isFinite() || !sd[i].isFinite()) continue
            up[i] = mid[i] + mult * sd[i]
            low[i] = mid[i] - mult * sd[i]
        }
        return Triple(up, mid, low)
    }

    /**
     * MACD：返回 (DIF, DEA, 柱)。
     * 柱 = DIF - DEA（**不乘 2**；国内部分终端会乘 2，此处口径已在界面说明）。
     */
    fun macd(values: DoubleArray, fast: Int, slow: Int, signal: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val emaFast = emaFrom(values, fast)
        val emaSlow = emaFrom(values, slow)
        val dif = DoubleArray(values.size) { NA }
        for (i in values.indices) {
            if (emaFast[i].isFinite() && emaSlow[i].isFinite()) dif[i] = emaFast[i] - emaSlow[i]
        }
        val dea = emaFrom(dif, signal)
        val hist = DoubleArray(values.size) { NA }
        for (i in values.indices) {
            if (dif[i].isFinite() && dea[i].isFinite()) hist[i] = dif[i] - dea[i]
        }
        return Triple(dif, dea, hist)
    }

    /**
     * KDJ 随机指标：返回 (K, D, J)。
     * RSV = (C - Ln) / (Hn - Ln) * 100，K/D 用 2/3 旧值 + 1/3 新值的递推平滑
     * （国内 KDJ 惯例），J = 3K - 2D。种子取 50。
     */
    fun kdj(candles: List<Candle>, period: Int, kSmooth: Int, dSmooth: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = candles.size
        val k = DoubleArray(n) { NA }
        val d = DoubleArray(n) { NA }
        val j = DoubleArray(n) { NA }
        if (n == 0 || period <= 0) return Triple(k, d, j)
        val highs = DoubleArray(n) { candles[it].high }
        val lows = DoubleArray(n) { candles[it].low }
        val hn = highest(highs, period)
        val ln = lowest(lows, period)

        var prevK = 50.0
        var prevD = 50.0
        val kA = if (kSmooth <= 1) 1.0 else 1.0 / kSmooth
        val dA = if (dSmooth <= 1) 1.0 else 1.0 / dSmooth
        for (i in candles.indices) {
            if (!hn[i].isFinite() || !ln[i].isFinite()) continue
            val span = hn[i] - ln[i]
            // 区间为零（一字板 / 停牌）：RSV 无意义，沿用上一根的 K/D，不制造假信号
            val rsv = if (span <= 1e-12) prevK else (candles[i].close - ln[i]) / span * 100.0
            prevK = prevK * (1 - kA) + rsv * kA
            prevD = prevD * (1 - dA) + prevK * dA
            k[i] = prevK
            d[i] = prevD
            j[i] = 3 * prevK - 2 * prevD
        }
        return Triple(k, d, j)
    }

    /**
     * DMI / ADX：返回 (ADX, +DI, -DI)。全部用 Wilder 平滑。
     */
    fun adx(candles: List<Candle>, period: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = candles.size
        val adxOut = DoubleArray(n) { NA }
        val plusDi = DoubleArray(n) { NA }
        val minusDi = DoubleArray(n) { NA }
        if (n < 2 || period <= 0) return Triple(adxOut, plusDi, minusDi)

        val tr = trueRange(candles)
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        for (i in 1 until n) {
            val upMove = candles[i].high - candles[i - 1].high
            val downMove = candles[i - 1].low - candles[i].low
            plusDm[i] = if (upMove > downMove && upMove > 0) upMove else 0.0
            minusDm[i] = if (downMove > upMove && downMove > 0) downMove else 0.0
        }

        // Wilder 平滑：首个值用前 period 项之和，之后做累加-递减
        var trS = 0.0
        var pS = 0.0
        var mS = 0.0
        var adxSeedSum = 0.0
        var adxCount = 0
        var prevAdx = NA

        for (i in 1 until n) {
            if (i <= period) {
                trS += tr[i]; pS += plusDm[i]; mS += minusDm[i]
            } else {
                trS = trS - trS / period + tr[i]
                pS = pS - pS / period + plusDm[i]
                mS = mS - mS / period + minusDm[i]
            }
            if (i < period || trS <= 0.0) continue
            val pdi = 100.0 * pS / trS
            val mdi = 100.0 * mS / trS
            plusDi[i] = pdi
            minusDi[i] = mdi
            val sum = pdi + mdi
            val dx = if (sum <= 1e-12) 0.0 else 100.0 * Math.abs(pdi - mdi) / sum

            if (prevAdx.isNaN()) {
                adxSeedSum += dx
                adxCount++
                if (adxCount >= period) {
                    prevAdx = adxSeedSum / period
                    adxOut[i] = prevAdx
                }
            } else {
                prevAdx = (prevAdx * (period - 1) + dx) / period
                adxOut[i] = prevAdx
            }
        }
        return Triple(adxOut, plusDi, minusDi)
    }

    /** CCI 顺势指标 */
    fun cci(candles: List<Candle>, period: Int): DoubleArray {
        val n = candles.size
        val out = DoubleArray(n) { NA }
        if (period <= 0 || n < period) return out
        val tp = DoubleArray(n) { (candles[it].high + candles[it].low + candles[it].close) / 3.0 }
        val ma = sma(tp, period)
        for (i in period - 1 until n) {
            if (!ma[i].isFinite()) continue
            var dev = 0.0
            var ok = true
            for (k in i - period + 1..i) {
                if (!tp[k].isFinite()) { ok = false; break }
                dev += Math.abs(tp[k] - ma[i])
            }
            if (!ok) continue
            val md = dev / period
            out[i] = if (md <= 1e-12) 0.0 else (tp[i] - ma[i]) / (0.015 * md)
        }
        return out
    }

    /** 威廉指标 %R（取值 -100..0） */
    fun williamsR(candles: List<Candle>, period: Int): DoubleArray {
        val n = candles.size
        val out = DoubleArray(n) { NA }
        if (period <= 0 || n < period) return out
        val highs = DoubleArray(n) { candles[it].high }
        val lows = DoubleArray(n) { candles[it].low }
        val hn = highest(highs, period)
        val ln = lowest(lows, period)
        for (i in period - 1 until n) {
            if (!hn[i].isFinite() || !ln[i].isFinite()) continue
            val span = hn[i] - ln[i]
            out[i] = if (span <= 1e-12) -50.0 else -100.0 * (hn[i] - candles[i].close) / span
        }
        return out
    }

    /** 动量 MOM：收盘价减去 period 根之前的收盘价 */
    fun momentum(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0) return out
        for (i in period until values.size) {
            if (values[i].isFinite() && values[i - period].isFinite()) out[i] = values[i] - values[i - period]
        }
        return out
    }

    /** 变动率 ROC（百分比） */
    fun roc(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { NA }
        if (period <= 0) return out
        for (i in period until values.size) {
            val base = values[i - period]
            if (!values[i].isFinite() || !base.isFinite() || Math.abs(base) < 1e-12) continue
            out[i] = (values[i] - base) / base * 100.0
        }
        return out
    }

    /** 能量潮 OBV */
    fun obv(candles: List<Candle>): DoubleArray {
        val n = candles.size
        val out = DoubleArray(n) { NA }
        if (n == 0) return out
        var acc = 0.0
        out[0] = 0.0
        for (i in 1 until n) {
            val c = candles[i].close
            val pc = candles[i - 1].close
            acc += when {
                c > pc -> candles[i].volume.toDouble()
                c < pc -> -candles[i].volume.toDouble()
                else -> 0.0
            }
            out[i] = acc
        }
        return out
    }

    /** 唐奇安通道：返回 (上轨, 中轨, 下轨) */
    fun donchian(candles: List<Candle>, period: Int): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val n = candles.size
        val up = DoubleArray(n) { NA }
        val mid = DoubleArray(n) { NA }
        val low = DoubleArray(n) { NA }
        if (period <= 0 || n < period) return Triple(up, mid, low)
        val highs = DoubleArray(n) { candles[it].high }
        val lows = DoubleArray(n) { candles[it].low }
        val hn = highest(highs, period)
        val ln = lowest(lows, period)
        for (i in period - 1 until n) {
            if (!hn[i].isFinite() || !ln[i].isFinite()) continue
            up[i] = hn[i]
            low[i] = ln[i]
            mid[i] = (hn[i] + ln[i]) / 2.0
        }
        return Triple(up, mid, low)
    }

    /**
     * 抛物线转向 SAR。
     *
     * 说明：SAR 是**路径依赖**的，无法局部重算；且首根 K 线的初始方向
     * 只能靠假设（此处按「首两根收涨则初始做多」）。参数为加速因子
     * 起始值 step 与上限 maxStep。
     */
    fun parabolicSar(candles: List<Candle>, step: Double, maxStep: Double): DoubleArray {
        val n = candles.size
        val out = DoubleArray(n) { NA }
        if (n < 2) return out
        val st = if (step <= 0.0) 0.02 else step
        val mx = if (maxStep < st) st else maxStep

        var isLong = candles[1].close >= candles[0].close
        var af = st
        var ep = if (isLong) candles[1].high else candles[1].low
        // 首值用第一根的低/高作为初始 SAR
        var sar = if (isLong) candles[0].low else candles[0].high
        out[0] = sar

        for (i in 1 until n) {
            val c = candles[i]
            sar += af * (ep - sar)
            if (isLong) {
                // SAR 不得高于前两根的最低价
                sar = Math.min(sar, Math.min(candles[i - 1].low, if (i >= 2) candles[i - 2].low else candles[i - 1].low))
                if (c.low < sar) {
                    isLong = false; sar = ep; ep = c.low; af = st
                } else if (c.high > ep) {
                    ep = c.high; af = Math.min(af + st, mx)
                }
            } else {
                sar = Math.max(sar, Math.max(candles[i - 1].high, if (i >= 2) candles[i - 2].high else candles[i - 1].high))
                if (c.high > sar) {
                    isLong = true; sar = ep; ep = c.high; af = st
                } else if (c.low < ep) {
                    ep = c.low; af = Math.min(af + st, mx)
                }
            }
            out[i] = sar
        }
        return out
    }

    /** 成交量序列（转 Double） */
    fun volumes(candles: List<Candle>): DoubleArray = DoubleArray(candles.size) { candles[it].volume.toDouble() }
}
