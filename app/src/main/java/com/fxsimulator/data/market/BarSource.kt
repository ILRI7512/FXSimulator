package com.fxsimulator.data.market

import com.fxsimulator.domain.model.Candle

/**
 * K 线数据源抽象。回放引擎只依赖该接口，因此内存数据与文件索引数据可以互换。
 */
interface BarSource {
    /** 总根数 */
    val size: Int
    /** 数据标识（文件名或 DEMO 标记） */
    val sourceName: String
    /** 品种 */
    val symbol: String
    /** 是否为演示/模拟数据（必须向用户明示） */
    val isDemoData: Boolean
    /** 第一根与最后一根的时间（毫秒） */
    val startTimeMillis: Long
    val endTimeMillis: Long
    /** 按索引取 K 线，越界返回 null */
    fun barAt(index: Int): Candle?
}

/** 内存数据源：用于单元测试与 DEMO 数据 */
class InMemoryBarSource(
    private val candles: List<Candle>,
    override val sourceName: String,
    override val symbol: String = "XAUUSD",
    override val isDemoData: Boolean = false,
) : BarSource {
    override val size: Int get() = candles.size
    override val startTimeMillis: Long get() = candles.firstOrNull()?.timeMillis ?: 0L
    override val endTimeMillis: Long get() = candles.lastOrNull()?.timeMillis ?: 0L
    override fun barAt(index: Int): Candle? = candles.getOrNull(index)
}
