package com.fxsimulator.domain.risk

import com.fxsimulator.domain.money.MarginCalculator
import com.fxsimulator.domain.model.Account
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.TradeDirection

/** 下单前风控的检查结果 */
data class RiskCheckResult(
    val allowed: Boolean,
    /** 面向用户的中文原因，allowed=true 时为提示信息 */
    val message: String,
    /** 预估保证金 */
    val estimatedMargin: Double = 0.0,
) {
    companion object {
        fun ok(margin: Double, note: String) = RiskCheckResult(true, note, margin)
        fun reject(reason: String, margin: Double = 0.0) = RiskCheckResult(false, reason, margin)
    }
}

/**
 * 风险管理引擎：只做检查与提示，不修改账户状态。
 *
 * 所有阈值都基于**虚拟资金**，目的是让练习有意义。
 */
class RiskEngine(
    /** 止损必须与开仓价保持的最小距离（价格单位），防止 0 距离止损无意义爆仓 */
    private val minStopDistance: Double = 0.10,
    /** 单日最大模拟亏损（USD），超过后拒绝开新仓；0 表示不限制 */
    private val maxDailyLoss: Double = 0.0,
    /** 连续亏损达到该次数后给出警告；0 表示不限制 */
    private val maxConsecutiveLosses: Int = 0,
    /** 保证金水平低于该百分比视为爆仓 */
    private val stopOutLevelPercent: Double = 50.0,
) {

    /**
     * 下单前检查。
     * 保证金不足时**绝不放行**，调用方不得绕过该结果。
     */
    fun checkOrder(
        spec: SymbolSpec,
        account: Account,
        direction: TradeDirection,
        volume: Double,
        price: Double,
        stopLoss: Double?,
        takeProfit: Double?,
        usedMargin: Double,
        dailyRealizedPnl: Double,
        consecutiveLosses: Int,
    ): RiskCheckResult {
        if (!volume.isFinite() || volume <= 0.0) {
            return RiskCheckResult.reject("手数必须大于 0")
        }
        if (!spec.isValidLot(volume)) {
            return RiskCheckResult.reject(
                "手数不符合规格：需为 " + fmt(spec.lotStep) + " 的整数倍，范围 " +
                    fmt(spec.minLot) + " ~ " + fmt(spec.maxLot),
            )
        }
        if (!price.isFinite() || price <= 0.0) {
            return RiskCheckResult.reject("报价无效，无法下单")
        }

        // 止损方向校验
        if (stopLoss != null) {
            if (direction.isBuy && stopLoss >= price) {
                return RiskCheckResult.reject("买入的止损必须低于开仓价")
            }
            if (!direction.isBuy && stopLoss <= price) {
                return RiskCheckResult.reject("卖出的止损必须高于开仓价")
            }
            val dist = Math.abs(price - stopLoss)
            if (dist < minStopDistance) {
                return RiskCheckResult.reject(
                    "止损距离过近：至少需要 " + fmt(minStopDistance) + "，当前 " + fmt(dist),
                )
            }
        }

        // 止盈方向校验
        if (takeProfit != null) {
            if (direction.isBuy && takeProfit <= price) {
                return RiskCheckResult.reject("买入的止盈必须高于开仓价")
            }
            if (!direction.isBuy && takeProfit >= price) {
                return RiskCheckResult.reject("卖出的止盈必须低于开仓价")
            }
        }

        val margin = MarginCalculator.requiredMargin(spec, volume, price)
        if (margin <= 0.0 || !margin.isFinite()) {
            return RiskCheckResult.reject("保证金计算异常，已拒绝下单")
        }

        if (maxDailyLoss > 0.0 && dailyRealizedPnl <= -maxDailyLoss) {
            return RiskCheckResult.reject(
                "已触发当日最大模拟亏损限制（" + fmt(maxDailyLoss) + " USD），今日不再允许开新仓",
            )
        }

        if (!MarginCalculator.canOpen(account.equity, usedMargin, margin)) {
            return RiskCheckResult.reject(
                "可用保证金不足：需要 " + fmt(margin) + " USD，可用 " +
                    fmt(account.freeMargin) + " USD",
                margin,
            )
        }

        var note = "预计占用保证金 " + fmt(margin) + " USD"
        if (maxConsecutiveLosses > 0 && consecutiveLosses >= maxConsecutiveLosses) {
            note += "；提示：已连续亏损 " + consecutiveLosses + " 笔，建议降低手数"
        }
        return RiskCheckResult.ok(margin, note)
    }

    /**
     * 爆仓判定：净值 <= 占用保证金 × 强平线。
     * 无持仓（margin = 0）时不判定爆仓。
     */
    fun isStopOut(account: Account): Boolean {
        if (account.margin <= 0.0) return false
        val level = account.marginLevel
        return level.isFinite() && level <= stopOutLevelPercent
    }

    /** 保证金水平的中文描述 */
    fun marginLevelText(account: Account): String {
        val lvl = account.marginLevel
        return if (!lvl.isFinite() || account.margin <= 0.0) "—" else String.format("%.1f%%", lvl)
    }

    fun stopOutLevel(): Double = stopOutLevelPercent

    private fun fmt(v: Double): String = String.format("%.2f", v)
}
