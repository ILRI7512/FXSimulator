package com.fxsimulator.domain.money

import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.TradeDirection

/**
 * 保证金估算。
 *
 * 模拟保证金 = 合约大小 × 手数 × **报价货币折算到 USD 的系数** / 模拟杠杆
 *
 * 注意这里的折算方向与盈亏**相反**：保证金看的是名义金额，名义金额以
 * **基础货币**计价（1 手 USDJPY 就是 10 万美元），所以 USDJPY 不能再乘价格 ——
 * 旧公式 `× price` 会让它的保证金多乘 150 倍。
 *
 * ⚠️ 这里的杠杆是**模拟参数**，用于让游戏有风险约束，
 * 与任何真实经纪商的杠杆规则无关。
 */
object MarginCalculator {

    fun requiredMargin(spec: SymbolSpec, volume: Double, price: Double): Double {
        if (spec.leverage <= 0) return Double.MAX_VALUE
        val notionalUsd = spec.contractSize * volume * spec.usdPerBaseUnit(price)
        return notionalUsd / spec.leverage.toDouble()
    }

    /**
     * 开仓后的总占用保证金。
     * 多空持仓在本简化模型中**不做对冲抵扣**，直接相加（保守）。
     */
    fun totalMargin(
        spec: SymbolSpec,
        positions: List<Triple<TradeDirection, Double, Double>>,
    ): Double {
        var sum = 0.0
        for (p in positions) sum += requiredMargin(spec, p.second, p.third)
        return sum
    }

    /**
     * 是否可用开新仓。
     * @param equity 当前净值
     * @param usedMargin 已有持仓占用保证金
     * @param newMargin 新仓需要的保证金
     * @param safetyFactor 安全系数，默认 1.0 表示必须完全覆盖
     */
    fun canOpen(
        equity: Double,
        usedMargin: Double,
        newMargin: Double,
        safetyFactor: Double = 1.0,
    ): Boolean {
        if (newMargin <= 0.0) return true
        val free = equity - usedMargin
        return free >= newMargin * safetyFactor
    }
}
