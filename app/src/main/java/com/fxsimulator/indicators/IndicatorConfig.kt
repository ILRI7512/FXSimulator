package com.fxsimulator.indicators

import com.fxsimulator.domain.model.Candle

/**
 * 用户配置的一个指标实例。
 *
 * 同一个指标可以配多个实例（例如 EMA(20) 与 EMA(50) 同时叠加），
 * 因此**不能用 defId 当唯一键**，界面上的身份要用列表下标。
 */
data class IndicatorInstance(
    val defId: String,
    /** 用户调节后的参数；缺项由 [IndicatorDef.normalize] 补默认值 */
    val params: Map<String, Double> = emptyMap(),
) {
    fun def(): IndicatorDef? = IndicatorCatalog.byId(defId)

    /** 把参数补全并夹到合法范围 */
    fun normalized(): IndicatorInstance {
        val d = def() ?: return this
        return copy(defId = d.id, params = d.normalize(params))
    }
}

/** 图表的完整指标配置（有序：列表顺序即绘制顺序） */
data class IndicatorConfig(val items: List<IndicatorInstance> = emptyList()) {

    /** 丢弃无法识别的指标、补全并夹紧参数 */
    fun normalized(): IndicatorConfig = IndicatorConfig(
        items.mapNotNull { inst ->
            val d = IndicatorCatalog.byId(inst.defId) ?: return@mapNotNull null
            IndicatorInstance(d.id, d.normalize(inst.params))
        },
    )

    /**
     * 追加一个指标。**未知 defId 直接丢弃** ——
     * 不能依赖 [IndicatorInstance.normalized]，它对未知 def 是「无操作」，
     * 会把不认识的 id 原样塞进配置，之后永远算不出结果、界面上却显示着一项。
     */
    fun withAdded(inst: IndicatorInstance): IndicatorConfig {
        val d = IndicatorCatalog.byId(inst.defId) ?: return this
        return copy(items = items + IndicatorInstance(d.id, d.normalize(inst.params)))
    }

    fun withRemovedAt(index: Int): IndicatorConfig =
        if (index !in items.indices) this
        else copy(items = items.filterIndexed { i, _ -> i != index })

    fun withReplacedAt(index: Int, inst: IndicatorInstance): IndicatorConfig {
        if (index !in items.indices) return this
        val d = IndicatorCatalog.byId(inst.defId) ?: return this
        val fixed = IndicatorInstance(d.id, d.normalize(inst.params))
        return copy(items = items.mapIndexed { i, old -> if (i == index) fixed else old })
    }

    fun withMoved(index: Int, delta: Int): IndicatorConfig {
        val to = index + delta
        if (index !in items.indices || to !in items.indices) return this
        val list = items.toMutableList()
        val v = list.removeAt(index)
        list.add(to, v)
        return copy(items = list)
    }
}

/**
 * 指标配置的文本编解码。
 *
 * 格式：实例之间用 | 分隔，id 与参数之间用 : 分隔，参数之间用 , 分隔：
 *
 *     ema:period=20|ema:period=50|macd:fast=12,slow=26,signal=9
 *
 * 刻意不用 JSON：这个字符串要塞进 SQLite 的一个 setting，
 * 而手写编解码可以做到**完全容错**（未知 id 丢弃、非法数字忽略、
 * 缺失参数补默认），不会因为一个字符坏掉就把用户的整套配置弄丢。
 */
object IndicatorConfigCodec {

    /** 空配置的哨兵：用来区分「用户清空了指标」与「从未配置过」 */
    const val EMPTY_SENTINEL = "-"

    fun encode(config: IndicatorConfig): String {
        val items = config.normalized().items
        if (items.isEmpty()) return EMPTY_SENTINEL
        return items.joinToString("|") { inst ->
            inst.defId + ":" + inst.params.entries
                .sortedBy { it.key }
                .joinToString(",") { (k, v) -> k + "=" + num(v) }
        }
    }

    /**
     * 解码。
     *
     * @return null / 空白 → 默认配置；哨兵 → 空配置；其余 → 解析结果
     */
    fun decode(text: String?): IndicatorConfig {
        if (text.isNullOrBlank()) return IndicatorConfig(IndicatorCatalog.defaultInstances())
        if (text.trim() == EMPTY_SENTINEL) return IndicatorConfig(emptyList())

        val items = ArrayList<IndicatorInstance>()
        for (chunk in text.split('|')) {
            if (chunk.isBlank()) continue
            val colon = chunk.indexOf(':')
            val defId = (if (colon < 0) chunk else chunk.substring(0, colon)).trim()
            val def = IndicatorCatalog.byId(defId) ?: continue

            val raw = LinkedHashMap<String, Double>()
            if (colon >= 0) {
                for (kv in chunk.substring(colon + 1).split(',')) {
                    val eq = kv.indexOf('=')
                    if (eq <= 0) continue
                    val k = kv.substring(0, eq).trim()
                    val v = kv.substring(eq + 1).trim().toDoubleOrNull() ?: continue
                    if (v.isFinite()) raw[k] = v
                }
            }
            items.add(IndicatorInstance(def.id, def.normalize(raw)))
        }
        // 一个都没解析出来（脏数据 / 老版本格式）：回落到默认，
        // 绝不让图表变成一片空白 —— 那看起来像功能坏了。
        return IndicatorConfig(if (items.isEmpty()) IndicatorCatalog.defaultInstances() else items)
    }

    private fun num(v: Double): String =
        if (!v.isFinite()) "0"
        else if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString()
        else v.toString()
}

/**
 * 指标求值：把配置 + K 线算成与 K 线等长的序列。
 *
 * 只计算**当前启用**的指标 —— 17 个全算是浪费，
 * 而用户通常只开 2~4 个。
 */
object IndicatorEngine {

    /** 单个实例的计算结果 */
    data class Result(
        val instance: IndicatorInstance,
        val def: IndicatorDef,
        /** 顺序与 [IndicatorDef.outputs] 一致，长度与输入 K 线一致 */
        val outputs: List<DoubleArray>,
    )

    fun evaluate(inst: IndicatorInstance, candles: List<Candle>): Result? {
        val def = inst.def() ?: return null
        if (candles.isEmpty()) return null
        val params = Params(def.normalize(inst.params))
        val outs = try {
            def.compute(candles, params)
        } catch (e: Exception) {
            // 单个指标算崩不能连累整张图
            return null
        }
        if (outs.size != def.outputs.size) return null
        for (o in outs) if (o.size != candles.size) return null
        return Result(inst, def, outs)
    }

    fun evaluateAll(config: IndicatorConfig, candles: List<Candle>): List<Result> =
        config.normalized().items.mapNotNull { evaluate(it, candles) }
}
