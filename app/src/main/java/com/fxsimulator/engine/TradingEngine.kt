package com.fxsimulator.engine

import com.fxsimulator.domain.money.MarginCalculator
import com.fxsimulator.domain.money.PnlCalculator
import com.fxsimulator.domain.model.Account
import com.fxsimulator.domain.model.Candle
import com.fxsimulator.domain.model.CloseReason
import com.fxsimulator.domain.model.OrderStatus
import com.fxsimulator.domain.model.SymbolSpec
import com.fxsimulator.domain.model.TradeDirection
import com.fxsimulator.domain.model.TradeRecord
import com.fxsimulator.domain.risk.RiskEngine

/** 下单结果 */
data class OrderResult(
    val success: Boolean,
    val message: String,
    val record: TradeRecord? = null,
)

/**
 * 模拟交易引擎。
 *
 * 与 UI 完全分离，不含任何 Android 依赖，可直接单元测试。
 * 不连接真实交易账户、不发送任何真实订单、全部为虚拟资金。
 *
 * 成交模型见 [FillModel]：以 K 线开盘价 + 点差成交，止损止盈按保守规则判定。
 */
class TradingEngine(
    var spec: SymbolSpec,
    var riskEngine: RiskEngine,
    /** 初始资金 */
    initialBalance: Double = 10000.0,
) {

    /** 账户已实现余额（含广告奖励等非交易入账） */
    var balance: Double = initialBalance
        private set

    /** 初始资金，用于统计与重置 */
    var initialBalance: Double = initialBalance
        private set

    private val _openPositions = ArrayList<TradeRecord>()
    private val _history = ArrayList<TradeRecord>()

    /** 当前持仓（OPEN 状态） */
    val openPositions: List<TradeRecord> get() = _openPositions

    /** 历史记录（CLOSED 与 REJECTED） */
    val history: List<TradeRecord> get() = _history

    /** 仅已平仓交易，供统计使用 */
    val closedTrades: List<TradeRecord>
        get() = _history.filter { it.status == OrderStatus.CLOSED }

    private var nextOrderId: Long = 1L

    /** 最近一次已知的 BID 报价 */
    var lastBid: Double = 0.0
        private set

    /** 累计隔夜费（第一版未实现，恒为 0） */
    var accumulatedSwap: Double = 0.0
        private set

    // ---------------------------------------------------------------- 账户

    /** 当前账户快照 */
    fun account(): Account {
        val floating = floatingPnl()
        val margin = usedMargin()
        return Account(balance = balance, floatingPnl = floating, margin = margin)
    }

    /** 全部持仓的浮动盈亏 */
    fun floatingPnl(): Double {
        if (lastBid <= 0.0) return 0.0
        var sum = 0.0
        for (p in _openPositions) {
            val closePrice = FillModel.exitPrice(p.direction, lastBid, spec.spread)
            sum += PnlCalculator.floating(spec, p.direction, p.volume, p.openPrice, closePrice)
        }
        return sum
    }

    fun usedMargin(): Double {
        var sum = 0.0
        for (p in _openPositions) {
            sum += MarginCalculator.requiredMargin(spec, p.volume, p.openPrice)
        }
        return sum
    }

    /** 是否存在未实现盈亏的持仓 */
    fun hasOpenPositions(): Boolean = _openPositions.isNotEmpty()

    // ---------------------------------------------------------------- 下单

    /**
     * 市价开仓。
     *
     * @param bid 当前 BID 报价
     * @param timeMillis 成交时间（回放时间）
     * @param reference 参考价，用于「以 K 线开盘价成交」的确定性模型
     */
    fun marketOrder(
        direction: TradeDirection,
        volume: Double,
        bid: Double,
        timeMillis: Long,
        stopLoss: Double? = null,
        takeProfit: Double? = null,
        dailyRealizedPnl: Double = 0.0,
        consecutiveLosses: Int = 0,
    ): OrderResult {
        if (bid <= 0.0) {
            return reject("没有有效报价，无法下单", direction, volume, timeMillis, stopLoss, takeProfit)
        }
        val price = FillModel.entryPrice(direction, bid, spec.spread)

        val check = riskEngine.checkOrder(
            spec = spec,
            account = account(),
            direction = direction,
            volume = volume,
            price = price,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            usedMargin = usedMargin(),
            dailyRealizedPnl = dailyRealizedPnl,
            consecutiveLosses = consecutiveLosses,
        )
        if (!check.allowed) {
            return reject(check.message, direction, volume, timeMillis, stopLoss, takeProfit)
        }

        val commission = PnlCalculator.commission(spec, volume)
        val record = TradeRecord(
            orderId = nextOrderId++,
            symbol = spec.symbol,
            direction = direction,
            openTimeMillis = timeMillis,
            openPrice = spec.normalize(price),
            volume = volume,
            stopLoss = stopLoss?.let { spec.normalize(it) },
            takeProfit = takeProfit?.let { spec.normalize(it) },
            commission = commission,
            status = OrderStatus.OPEN,
            fillNote = "市价" + direction.label + "：以 " +
                if (direction.isBuy) "ASK(BID+" + f2(spec.spread) + ")" else "BID" +
                " 成交；点差 " + f2(spec.spread) + "，每手单边佣金 " + f2(spec.commissionPerLot),
        )
        _openPositions.add(record)
        // 允许记录被外部持久化：返回给调用方
        lastBid = bid
        return OrderResult(true, "开仓成功：" + check.message, record)
    }

    private fun reject(
        message: String,
        direction: TradeDirection,
        volume: Double,
        timeMillis: Long,
        stopLoss: Double?,
        takeProfit: Double?,
    ): OrderResult {
        val rec = TradeRecord(
            orderId = nextOrderId++,
            symbol = spec.symbol,
            direction = direction,
            openTimeMillis = timeMillis,
            openPrice = 0.0,
            volume = volume,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            status = OrderStatus.REJECTED,
            fillNote = message,
        )
        _history.add(rec)
        return OrderResult(false, message, rec)
    }

    // ---------------------------------------------------------------- 平仓

    /**
     * 平掉指定持仓。
     * @param bid 当前 BID
     * @param reason 平仓原因
     * @param overrideClosePrice 指定成交价（用于止损止盈成交价回填）
     */
    fun closePosition(
        orderId: Long,
        bid: Double,
        timeMillis: Long,
        reason: CloseReason = CloseReason.MANUAL,
        overrideClosePrice: Double? = null,
        /** 成交规则说明，会写入交易记录，便于复盘时解释成交口径 */
        note: String = "",
    ): OrderResult {
        val idx = _openPositions.indexOfFirst { it.orderId == orderId }
        if (idx < 0) return OrderResult(false, "未找到持仓 #" + orderId)

        val pos = _openPositions.removeAt(idx)
        val closePrice = overrideClosePrice ?: FillModel.exitPrice(pos.direction, bid, spec.spread)
        return settle(pos, spec.normalize(closePrice), timeMillis, reason, note)
    }

    /** 一键平掉全部持仓（按传入顺序成交） */
    fun closeAll(
        bid: Double,
        timeMillis: Long,
        reason: CloseReason = CloseReason.MANUAL,
    ): List<OrderResult> {
        val ids = _openPositions.map { it.orderId }
        return ids.map { closePosition(it, bid, timeMillis, reason) }
    }

    private fun settle(
        pos: TradeRecord,
        closePrice: Double,
        timeMillis: Long,
        reason: CloseReason,
        note: String = "",
    ): OrderResult {
        val gross = PnlCalculator.grossProfit(
            spec, pos.direction, pos.volume, pos.openPrice, closePrice,
        )
        // 开仓时已计一次单边佣金，这里补第二次
        val totalCommission = pos.commission + spec.commissionPerLot * pos.volume
        val swap = 0.0
        val net = PnlCalculator.netProfit(gross, totalCommission, swap)

        val closed = pos.copy(
            closeTimeMillis = timeMillis,
            closePrice = closePrice,
            grossProfit = gross,
            commission = totalCommission,
            swap = swap,
            netProfit = net,
            status = OrderStatus.CLOSED,
            closeReason = reason,
            fillNote = pos.fillNote + " | 平仓(" + reasonText(reason) + ") 成交于 " + f3(closePrice) +
                (if (note.isNotEmpty()) " | " + note else ""),
        )
        _history.add(closed)
        // 已实现盈亏结算进余额
        balance += net
        accumulatedSwap += swap

        return OrderResult(
            true,
            "平仓完成：" + (if (net >= 0) "盈利 " else "亏损 ") + f2(Math.abs(net)) + " USD",
            closed,
        )
    }

    /** 修改持仓的止损止盈（传 null 表示清除） */
    fun modifyPosition(
        orderId: Long,
        stopLoss: Double?,
        takeProfit: Double?,
    ): OrderResult {
        val idx = _openPositions.indexOfFirst { it.orderId == orderId }
        if (idx < 0) return OrderResult(false, "未找到持仓 #" + orderId)
        val pos = _openPositions[idx]

        if (stopLoss != null) {
            if (pos.direction.isBuy && stopLoss >= pos.openPrice) {
                return OrderResult(false, "买入持仓的止损必须低于开仓价 " + f3(pos.openPrice))
            }
            if (!pos.direction.isBuy && stopLoss <= pos.openPrice) {
                return OrderResult(false, "卖出持仓的止损必须高于开仓价 " + f3(pos.openPrice))
            }
        }
        if (takeProfit != null) {
            if (pos.direction.isBuy && takeProfit <= pos.openPrice) {
                return OrderResult(false, "买入持仓的止盈必须高于开仓价 " + f3(pos.openPrice))
            }
            if (!pos.direction.isBuy && takeProfit >= pos.openPrice) {
                return OrderResult(false, "卖出持仓的止盈必须低于开仓价 " + f3(pos.openPrice))
            }
        }

        val updated = pos.copy(
            stopLoss = stopLoss?.let { spec.normalize(it) },
            takeProfit = takeProfit?.let { spec.normalize(it) },
        )
        _openPositions[idx] = updated
        return OrderResult(true, "已更新止盈止损", updated)
    }

    // ---------------------------------------------------------------- 行情推进

    /**
     * 用一根新到达的 K 线推进引擎：更新报价、检查止损止盈、必要时强制平仓。
     *
     * 每根 K 线只调用一次，避免重复创建协程或重复处理。
     *
     * @return 本根 K 线内发生的平仓结果列表
     */
    fun onCandle(candle: Candle): List<OrderResult> {
        lastBid = candle.close
        val results = ArrayList<OrderResult>()

        // 1) 止损止盈检查（保守规则）
        val snapshot = ArrayList(_openPositions)
        for (pos in snapshot) {
            if (pos.stopLoss == null && pos.takeProfit == null) continue
            val exit = FillModel.evaluateBar(pos.direction, candle, pos.stopLoss, pos.takeProfit, spec.spread)
            if (exit.kind != ExitKind.NONE && exit.price != null) {
                val reason = when (exit.kind) {
                    ExitKind.AMBIGUOUS, ExitKind.STOP_LOSS -> CloseReason.STOP_LOSS
                    ExitKind.TAKE_PROFIT -> CloseReason.TAKE_PROFIT
                    else -> CloseReason.MANUAL
                }
                // 把成交规则说明一并写入记录，界面上可复盘
                val r = closePosition(
                    pos.orderId, candle.close, candle.timeMillis, reason, exit.price, exit.note,
                )
                results.add(
                    if (exit.kind == ExitKind.AMBIGUOUS) {
                        r.copy(message = r.message + "（" + exit.note + "）")
                    } else {
                        r
                    },
                )
            }
        }

        // 2) 爆仓检查：净值低于强平线时强制平掉全部持仓
        if (hasOpenPositions() && riskEngine.isStopOut(account())) {
            val forced = closeAll(candle.close, candle.timeMillis, CloseReason.MARGIN_CALL)
            results.addAll(forced)
        }

        return results
    }

    /** 仅更新报价，不检查止损（用于未完成 K 线的实时跳动） */
    fun updateBidOnly(bid: Double) {
        if (bid > 0.0) lastBid = bid
    }

    // ---------------------------------------------------------------- 资金与重置

    /**
     * 非交易入账（例如激励广告奖励）。
     * 明确：该金额只增加余额，**不进入交易记录**，因此不会影响任何交易统计。
     */
    fun creditReward(amount: Double) {
        if (amount > 0.0) balance += amount
    }

    /** 重置账户：清空持仓与历史 */
    fun reset(newInitialBalance: Double = initialBalance) {
        _openPositions.clear()
        _history.clear()
        initialBalance = newInitialBalance
        balance = newInitialBalance
        nextOrderId = 1L
        accumulatedSwap = 0.0
    }

    /** 重置账户但保留交易历史（用于「重置资金但保留复盘记录」） */
    fun resetBalanceKeepHistory(newInitialBalance: Double = initialBalance) {
        _openPositions.clear()
        initialBalance = newInitialBalance
        balance = newInitialBalance
        accumulatedSwap = 0.0
    }

    /** 恢复持久化状态 */
    fun restore(
        balance: Double,
        initialBalance: Double,
        openPositions: List<TradeRecord>,
        history: List<TradeRecord>,
        lastBid: Double,
    ) {
        this.balance = balance
        this.initialBalance = initialBalance
        _openPositions.clear()
        _openPositions.addAll(openPositions)
        _history.clear()
        _history.addAll(history)
        this.lastBid = lastBid
        nextOrderId = (openPositions + history).maxOfOrNull { it.orderId }?.plus(1) ?: 1L
    }

    private fun reasonText(r: CloseReason): String = when (r) {
        CloseReason.MANUAL -> "手动"
        CloseReason.STOP_LOSS -> "止损"
        CloseReason.TAKE_PROFIT -> "止盈"
        CloseReason.MARGIN_CALL -> "模拟爆仓强平"
        CloseReason.RESET -> "账户重置"
    }

    private fun f2(v: Double) = String.format("%.2f", v)
    private fun f3(v: Double) = String.format("%.3f", v)
}
