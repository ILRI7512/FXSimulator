package com.fxsimulator.ui.chart

import androidx.compose.ui.graphics.Color
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.indicators.IndicatorCatalog
import com.fxsimulator.indicators.IndicatorConfig
import com.fxsimulator.indicators.IndicatorDef
import com.fxsimulator.indicators.IndicatorEngine
import com.fxsimulator.indicators.IndicatorInstance
import com.fxsimulator.indicators.IndicatorPane
import com.fxsimulator.indicators.OutputStyle
import com.fxsimulator.ui.theme.GoldLabColors

/**
 * 把「指标配置 + K 线」翻译成图表能画的 [OverlaySeries] / [SubPaneSpec]。
 *
 * 这层刻意独立：indicators 包不依赖任何 UI 类型（可纯 JVM 单测），
 * 颜色与布局这些只属于显示的东西全部留在 ui.chart 里。
 */
object IndicatorMapper {

    /** 指标线配色。深色底上要能和金/绿/红三色区分开。 */
    val palette: List<Color> = listOf(
        GoldLabColors.Gold,
        GoldLabColors.Info,
        Color(0xFF26C6DA),
        Color(0xFFAB47BC),
        Color(0xFFEC407A),
        Color(0xFF9CCC65),
        GoldLabColors.Warning,
        Color(0xFF5C6BC0),
        Color(0xFF4DB6AC),
        Color(0xFFFFD54F),
    )

    fun colorAt(i: Int): Color = palette[Math.floorMod(i, palette.size)]

    /**
     * 图例上的显示名，如 EMA(20) / BOLL(20,2) / MACD(12,26,9)。
     * 参数按定义顺序拼接，用户一眼能看出自己调的是什么。
     */
    fun label(def: IndicatorDef, params: Map<String, Double>): String {
        val abbr = def.id.uppercase()
        if (def.params.isEmpty()) return abbr
        val ps = def.params.joinToString(",") { p ->
            val v = params[p.key] ?: p.default
            if (p.isInteger) Math.round(v).toString() else trim(v)
        }
        return abbr + "(" + ps + ")"
    }

    fun label(inst: IndicatorInstance): String {
        val d = inst.def() ?: return inst.defId.uppercase()
        return label(d, inst.params)
    }

    private fun trim(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun styleOf(s: OutputStyle): SeriesStyle = when (s) {
        OutputStyle.LINE -> SeriesStyle.LINE
        OutputStyle.HISTOGRAM -> SeriesStyle.HISTOGRAM
        OutputStyle.POINTS -> SeriesStyle.POINTS
    }

    /** 主图叠加线（与 K 线共用价格轴） */
    fun overlays(config: IndicatorConfig, candles: List<Candle>): List<OverlaySeries> {
        if (candles.isEmpty()) return emptyList()
        val out = ArrayList<OverlaySeries>()
        var colorIdx = 0
        for (r in IndicatorEngine.evaluateAll(config, candles)) {
            if (r.def.pane != IndicatorPane.MAIN) continue
            val name = label(r.def, r.instance.params)
            for ((i, o) in r.outputs.withIndex()) {
                out.add(
                    OverlaySeries(
                        label = if (r.outputs.size > 1) name + "·" + r.def.outputs[i].label else name,
                        values = o.toList(),
                        color = colorAt(colorIdx),
                        style = styleOf(r.def.outputs[i].style),
                        digits = r.def.digits,
                    ),
                )
                colorIdx++
            }
        }
        return out
    }

    /** 副图窗格。每个 SUB 指标一个窗格，窗格内可含多条线（MACD = DIF+DEA+柱）。 */
    fun subPanes(config: IndicatorConfig, candles: List<Candle>): List<SubPaneSpec> {
        if (candles.isEmpty()) return emptyList()
        val out = ArrayList<SubPaneSpec>()
        var colorIdx = 0
        for (r in IndicatorEngine.evaluateAll(config, candles)) {
            if (r.def.pane != IndicatorPane.SUB) continue
            val lines = r.outputs.mapIndexed { i, o ->
                val meta = r.def.outputs[i]
                // 柱状线按正负自动着色，这里的 color 只作为兜底
                val c = if (meta.style == OutputStyle.HISTOGRAM) colorAt(colorIdx) else colorAt(colorIdx).also { colorIdx++ }
                SubPaneLine(
                    label = meta.label,
                    values = o.toList(),
                    color = c,
                    style = styleOf(meta.style),
                )
            }
            colorIdx++
            out.add(
                SubPaneSpec(
                    label = label(r.def, r.instance.params),
                    lines = lines,
                    fixedRange = r.def.fixedRange,
                    guides = r.def.guides,
                    digits = r.def.digits,
                ),
            )
        }
        return out
    }

    /** 供设置界面预览用：某指标的默认实例 */
    fun defaultInstance(defId: String): IndicatorInstance {
        val d = IndicatorCatalog.byId(defId)
        return IndicatorInstance(defId, d?.defaultParams() ?: emptyMap())
    }
}
