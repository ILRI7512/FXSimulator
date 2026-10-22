package com.fxsimulator.indicators

import com.fxsimulator.domain.model.Candle

/** 指标画在哪里：主图叠加，还是独立副图 */
enum class IndicatorPane { MAIN, SUB }

/** 输出的绘制风格（与图表层的 SeriesStyle 对应，此处不依赖 UI 包） */
enum class OutputStyle { LINE, HISTOGRAM, POINTS }

/**
 * 一个可调参数的元数据。界面据此生成滑块/加减按钮，
 * 并在用户输入越界时夹回 [min]..[max]。
 */
data class IndicatorParam(
    val key: String,
    val label: String,
    val default: Double,
    val min: Double,
    val max: Double,
    val step: Double = 1.0,
    /** true = 只允许整数（周期类参数都是整数） */
    val isInteger: Boolean = true,
) {
    fun clamp(v: Double): Double = v.coerceIn(min, max)
}

/** 取参数的只读视图，避免各 compute 里到处做 Map 查找和类型转换 */
class Params(private val map: Map<String, Double>) {
    fun i(key: String): Int = (map[key] ?: 0.0).toInt().coerceAtLeast(1)
    fun d(key: String): Double = map[key] ?: 0.0
}

/** 一条输出（名字 + 风格）。线数决定 compute 要返回几个数组。 */
data class IndicatorOutput(val label: String, val style: OutputStyle = OutputStyle.LINE)

/**
 * 指标定义。**纯计算**：输入 K 线与参数，输出与输入等长的若干序列（NaN = 无值）。
 * 不依赖任何 UI 类型，便于单元测试与复用。
 */
data class IndicatorDef(
    val id: String,
    val name: String,
    val pane: IndicatorPane,
    val params: List<IndicatorParam> = emptyList(),
    val outputs: List<IndicatorOutput>,
    /** 固定值域（如 RSI 恒 0..100）；null = 按窗口自适应 */
    val fixedRange: Pair<Double, Double>? = null,
    /** 参考线（如 RSI 的 30 / 70） */
    val guides: List<Double> = emptyList(),
    val digits: Int = 2,
    val compute: (List<Candle>, Params) -> List<DoubleArray>,
) {
    fun defaultParams(): Map<String, Double> = params.associate { it.key to it.default }

    /** 把用户给的参数补全并夹到合法范围 */
    fun normalize(input: Map<String, Double>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(params.size)
        for (p in params) {
            val v = input[p.key] ?: p.default
            val c = p.clamp(if (v.isFinite()) v else p.default)
            out[p.key] = if (p.isInteger) Math.round(c).toDouble() else c
        }
        return out
    }
}

/**
 * 传统技术指标工具库。
 *
 * 全部为**经典教材口径**的常用指标，参数可调。指标只用于模拟学习，
 * 不代表对未来走势的预测。
 *
 * 口径说明（容易踩坑的地方）：
 * - MACD 柱 = DIF - DEA，**不乘 2**。部分国内终端会乘 2，此处已在界面标注。
 * - KDJ 用 2/3 旧值 + 1/3 新值的递推平滑；区间为零时沿用上一根的 K/D，不制造假信号。
 * - SAR 是路径依赖的，无法局部重算；初始方向按「首两根收涨即做多」假设。
 * - OBV 以第一根为 0 起点（原始定义如此，绝对数值无意义，只看斜率）。
 */
object IndicatorCatalog {

    private fun p(key: String, label: String, def: Double, min: Double, max: Double) =
        IndicatorParam(key, label, def, min, max)

    private fun closesOf(candles: List<Candle>) = DoubleArray(candles.size) { candles[it].close }

    val all: List<IndicatorDef> = listOf(

        // ---------------------------------------------------------- 主图叠加
        IndicatorDef(
            id = "sma", name = "SMA 简单移动平均", pane = IndicatorPane.MAIN,
            params = listOf(p("period", "周期", 20.0, 2.0, 400.0)),
            outputs = listOf(IndicatorOutput("SMA")),
            compute = { c, a -> listOf(Indicators.sma(closesOf(c), a.i("period"))) },
        ),
        IndicatorDef(
            id = "ema", name = "EMA 指数移动平均", pane = IndicatorPane.MAIN,
            params = listOf(p("period", "周期", 20.0, 2.0, 400.0)),
            outputs = listOf(IndicatorOutput("EMA")),
            compute = { c, a -> listOf(Indicators.emaFrom(closesOf(c), a.i("period"))) },
        ),
        IndicatorDef(
            id = "wma", name = "WMA 加权移动平均", pane = IndicatorPane.MAIN,
            params = listOf(p("period", "周期", 20.0, 2.0, 400.0)),
            outputs = listOf(IndicatorOutput("WMA")),
            compute = { c, a -> listOf(Indicators.wma(closesOf(c), a.i("period"))) },
        ),
        IndicatorDef(
            id = "boll", name = "BOLL 布林带", pane = IndicatorPane.MAIN,
            params = listOf(p("period", "周期", 20.0, 2.0, 400.0), p("mult", "标准差倍数", 2.0, 0.5, 5.0)),
            outputs = listOf(IndicatorOutput("上轨"), IndicatorOutput("中轨"), IndicatorOutput("下轨")),
            compute = { c, a ->
                val (u, m, l) = Indicators.bollinger(closesOf(c), a.i("period"), a.d("mult"))
                listOf(u, m, l)
            },
        ),
        IndicatorDef(
            id = "donchian", name = "唐奇安通道", pane = IndicatorPane.MAIN,
            params = listOf(p("period", "周期", 20.0, 2.0, 400.0)),
            outputs = listOf(IndicatorOutput("上轨"), IndicatorOutput("中轨"), IndicatorOutput("下轨")),
            compute = { c, a ->
                val (u, m, l) = Indicators.donchian(c, a.i("period"))
                listOf(u, m, l)
            },
        ),
        IndicatorDef(
            id = "sar", name = "SAR 抛物线转向", pane = IndicatorPane.MAIN,
            params = listOf(p("step", "加速因子", 0.02, 0.001, 0.5), p("max", "加速上限", 0.2, 0.01, 1.0)),
            outputs = listOf(IndicatorOutput("SAR", OutputStyle.POINTS)),
            digits = 3,
            compute = { c, a -> listOf(Indicators.parabolicSar(c, a.d("step"), a.d("max"))) },
        ),

        // ---------------------------------------------------------- 副图
        IndicatorDef(
            id = "vol", name = "VOL 成交量", pane = IndicatorPane.SUB,
            params = listOf(p("period", "均量周期", 20.0, 2.0, 200.0)),
            outputs = listOf(IndicatorOutput("VOL", OutputStyle.HISTOGRAM), IndicatorOutput("均量")),
            digits = 0,
            compute = { c, a ->
                listOf(Indicators.volumes(c), Indicators.sma(Indicators.volumes(c), a.i("period")))
            },
        ),
        IndicatorDef(
            id = "macd", name = "MACD 指数平滑异同", pane = IndicatorPane.SUB,
            params = listOf(
                p("fast", "快线", 12.0, 2.0, 200.0),
                p("slow", "慢线", 26.0, 3.0, 400.0),
                p("signal", "信号线", 9.0, 2.0, 200.0),
            ),
            outputs = listOf(IndicatorOutput("DIF"), IndicatorOutput("DEA"), IndicatorOutput("MACD", OutputStyle.HISTOGRAM)),
            digits = 3,
            compute = { c, a ->
                val (dif, dea, hist) = Indicators.macd(closesOf(c), a.i("fast"), a.i("slow"), a.i("signal"))
                listOf(dif, dea, hist)
            },
        ),
        IndicatorDef(
            id = "rsi", name = "RSI 相对强弱", pane = IndicatorPane.SUB,
            params = listOf(p("period", "周期", 14.0, 2.0, 200.0)),
            outputs = listOf(IndicatorOutput("RSI")),
            fixedRange = 0.0 to 100.0, guides = listOf(30.0, 70.0), digits = 1,
            compute = { c, a -> listOf(Indicators.rsi(closesOf(c), a.i("period"))) },
        ),
        IndicatorDef(
            id = "kdj", name = "KDJ 随机指标", pane = IndicatorPane.SUB,
            params = listOf(
                p("period", "RSV 周期", 9.0, 2.0, 200.0),
                p("k", "K 平滑", 3.0, 1.0, 50.0),
                p("d", "D 平滑", 3.0, 1.0, 50.0),
            ),
            outputs = listOf(IndicatorOutput("K"), IndicatorOutput("D"), IndicatorOutput("J")),
            guides = listOf(20.0, 80.0), digits = 1,
            compute = { c, a ->
                val (k, d, j) = Indicators.kdj(c, a.i("period"), a.i("k"), a.i("d"))
                listOf(k, d, j)
            },
        ),
        IndicatorDef(
            id = "atr", name = "ATR 平均真实波幅", pane = IndicatorPane.SUB,
            params = listOf(p("period", "周期", 14.0, 2.0, 200.0)),
            outputs = listOf(IndicatorOutput("ATR")),
            digits = 3,
            compute = { c, a -> listOf(Indicators.atr(c, a.i("period"))) },
        ),
        IndicatorDef(
            id = "adx", name = "DMI / ADX 趋向", pane = IndicatorPane.SUB,
            params = listOf(p("period", "周期", 14.0, 2.0, 200.0)),
            outputs = listOf(IndicatorOutput("ADX"), IndicatorOutput("+DI"), IndicatorOutput("-DI")),
            fixedRange = 0.0 to 100.0, guides = listOf(20.0, 40.0), digits = 1,
            compute = { c, a ->
                val (adx, pd, md) = Indicators.adx(c, a.i("period"))
                listOf(adx, pd, md)
            },
        ),
        IndicatorDef(
            id = "cci", name = "CCI 顺势指标", pane = IndicatorPane.SUB,
            params = listOf(p("period", "周期", 20.0, 2.0, 200.0)),
            outputs = listOf(IndicatorOutput("CCI")),
            guides = listOf(-100.0, 100.0), digits = 1,
            compute = { c, a -> listOf(Indicators.cci(c, a.i("period"))) },
        ),
        IndicatorDef(
            id = "willr", name = "W%R 威廉指标", pane = IndicatorPane.SUB,
            params = listOf(p("period", "周期", 14.0, 2.0, 200.0)),
            outputs = listOf(IndicatorOutput("W%R")),
            fixedRange = -100.0 to 0.0, guides = listOf(-80.0, -20.0), digits = 1,
            compute = { c, a -> listOf(Indicators.williamsR(c, a.i("period"))) },
        ),
        IndicatorDef(
            id = "mom", name = "MOM 动量", pane = IndicatorPane.SUB,
            params = listOf(p("period", "周期", 10.0, 1.0, 200.0)),
            outputs = listOf(IndicatorOutput("MOM")),
            digits = 3,
            compute = { c, a -> listOf(Indicators.momentum(closesOf(c), a.i("period"))) },
        ),
        IndicatorDef(
            id = "roc", name = "ROC 变动率", pane = IndicatorPane.SUB,
            params = listOf(p("period", "周期", 12.0, 1.0, 200.0)),
            outputs = listOf(IndicatorOutput("ROC%")),
            digits = 2,
            compute = { c, a -> listOf(Indicators.roc(closesOf(c), a.i("period"))) },
        ),
        IndicatorDef(
            id = "obv", name = "OBV 能量潮", pane = IndicatorPane.SUB,
            params = listOf(p("period", "均线周期", 30.0, 2.0, 200.0)),
            outputs = listOf(IndicatorOutput("OBV"), IndicatorOutput("OBV 均线")),
            digits = 0,
            compute = { c, a ->
                val o = Indicators.obv(c)
                listOf(o, Indicators.sma(o, a.i("period")))
            },
        ),
    )

    private val index: Map<String, IndicatorDef> = all.associateBy { it.id }

    fun byId(id: String): IndicatorDef? = index[id]

    fun mainDefs(): List<IndicatorDef> = all.filter { it.pane == IndicatorPane.MAIN }
    fun subDefs(): List<IndicatorDef> = all.filter { it.pane == IndicatorPane.SUB }

    /** 首次启动的默认配置：保持既有观感（EMA20 / EMA50 叠加，RSI14 与 ATR14 副图） */
    fun defaultInstances(): List<IndicatorInstance> = listOf(
        IndicatorInstance("ema", mapOf("period" to 20.0)),
        IndicatorInstance("ema", mapOf("period" to 50.0)),
        IndicatorInstance("rsi", mapOf("period" to 14.0)),
        IndicatorInstance("atr", mapOf("period" to 14.0)),
    )
}
