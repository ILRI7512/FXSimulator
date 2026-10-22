package com.fxsimulator.stats

import com.fxsimulator.domain.model.CurvePoint
import com.fxsimulator.domain.model.TradeRecord
import com.fxsimulator.domain.model.TradeStats

/**
 * 交易统计与复盘。
 *
 * 所有指标**只从真实的模拟成交记录派生**；
 * 广告奖励不计入交易盈亏（广告奖励只改变 balance，不进入 [TradeRecord]）。
 */
object PerformanceAnalyzer {

    /**
     * 依据已平仓交易与初始资金计算统计。
     *
     * @param closedTrades 已平仓交易（顺序任意，内部会按平仓时间排序）
     * @param initialBalance 初始资金，用于计算回撤百分比与净值曲线起点
     */
    fun analyze(closedTrades: List<TradeRecord>, initialBalance: Double): TradeStats {
        val closed = closedTrades
            .filter { it.status == com.fxsimulator.domain.model.OrderStatus.CLOSED }
            .sortedBy { it.closeTimeMillis ?: it.openTimeMillis }

        if (closed.isEmpty()) {
            return TradeStats()
        }

        var grossProfit = 0.0
        var grossLoss = 0.0
        var commission = 0.0
        var swap = 0.0
        var net = 0.0
        var wins = 0
        var losses = 0
        var breakEven = 0
        var winSum = 0.0
        var lossSum = 0.0

        var consecutiveLosses = 0
        var maxConsecutiveLosses = 0

        val curve = ArrayList<CurvePoint>(closed.size + 1)
        curve.add(CurvePoint(closed.first().openTimeMillis, initialBalance))

        var equityAcc = initialBalance
        for (t in closed) {
            net += t.netProfit
            commission += t.commission
            swap += t.swap
            if (t.netProfit > 0.0) {
                wins++
                winSum += t.netProfit
                grossProfit += t.netProfit
                consecutiveLosses = 0
            } else if (t.netProfit < 0.0) {
                losses++
                lossSum += -t.netProfit
                grossLoss += -t.netProfit
                consecutiveLosses++
                if (consecutiveLosses > maxConsecutiveLosses) maxConsecutiveLosses = consecutiveLosses
            } else {
                breakEven++
                consecutiveLosses = 0
            }
            equityAcc += t.netProfit
            curve.add(CurvePoint(t.closeTimeMillis ?: t.openTimeMillis, equityAcc))
        }

        // 最大回撤：基于净值曲线的峰值回落
        var peak = initialBalance
        var maxDd = 0.0
        var maxDdPct = 0.0
        for (p in curve) {
            if (p.equity > peak) peak = p.equity
            val dd = peak - p.equity
            if (dd > maxDd) {
                maxDd = dd
                maxDdPct = if (peak > 0.0) dd / peak * 100.0 else 0.0
            }
        }

        val avgWin = if (wins > 0) winSum / wins else 0.0
        val avgLoss = if (losses > 0) lossSum / losses else 0.0
        val ratio = if (avgLoss > 0.0) avgWin / avgLoss else 0.0
        val factor = if (grossLoss > 0.0) grossProfit / grossLoss else 0.0
        val decided = wins + losses
        val winRate = if (decided > 0) wins.toDouble() / decided.toDouble() * 100.0 else 0.0

        return TradeStats(
            totalTrades = closed.size,
            winTrades = wins,
            lossTrades = losses,
            breakEvenTrades = breakEven,
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            netProfit = net,
            totalCommission = commission,
            totalSwap = swap,
            averageWin = avgWin,
            averageLoss = avgLoss,
            profitLossRatio = ratio,
            profitFactor = factor,
            winRate = winRate,
            maxDrawdown = maxDd,
            maxDrawdownPercent = maxDdPct,
            maxConsecutiveLosses = maxConsecutiveLosses,
            equityCurve = curve,
        )
    }

    /** 当日已实现盈亏（用于风控的「单日模拟亏损统计」） */
    fun dailyRealizedPnl(closedTrades: List<TradeRecord>, dayKey: String): Double {
        return closedTrades
            .filter { it.status == com.fxsimulator.domain.model.OrderStatus.CLOSED }
            .filter { dayKeyOf(it.closeTimeMillis ?: it.openTimeMillis) == dayKey }
            .sumOf { it.netProfit }
    }

    /** 由 epoch 毫秒得到 yyyy-MM-dd（UTC，与行情时间基准一致） */
    fun dayKeyOf(millis: Long): String {
        val d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        return d.toString()
    }
}
