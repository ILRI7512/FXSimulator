package com.fxsimulator.domain.model

/**
 * 交易品种的**模拟**合约规格。
 *
 * ⚠️ 这些数值是本项目的模拟参数，**不代表任何真实经纪商**。
 * 合约大小、点差、佣金按品种内置（见 [SymbolSpecs]），用户不可修改。
 *
 * ## 为什么要有基础货币 / 报价货币
 * 盈亏天然是**报价货币**计价的（USDJPY 赚的是日元），
 * 而保证金天然是**基础货币**计价的名义金额（USDJPY 的 1 手就是 10 万美元）。
 * 两者折算成美元的方向相反，所以必须分开声明、分开折算。
 */
data class SymbolSpec(
    val symbol: String = "XAUUSD",
    /** 基础货币：合约哪一侧的资产（XAU / EUR / USD …） */
    val baseCurrency: String = "XAU",
    /** 报价货币：价格用什么计价（USD / JPY / CAD / CHF …） */
    val quoteCurrency: String = "USD",
    /** 1.00 手对应的标的数量（黄金 100 盎司、外汇 10 万货币单位） */
    val contractSize: Double = 100.0,
    /** 最小手数 */
    val minLot: Double = 0.01,
    /** 手数步进 */
    val lotStep: Double = 0.01,
    /** 最大手数（模拟） */
    val maxLot: Double = 100.0,
    /** 模拟杠杆，仅用于保证金估算，不是真实经纪商杠杆。内置标准：最高 2000 倍 */
    val leverage: Int = 2000,
    /** 价格小数位（XAUUSD 常见 2 或 3 位） */
    val digits: Int = 3,
    /** 模拟点差（价格单位，例如 0.20 表示 20 美分）。行情里有逐日点差时以行情为准 */
    val spread: Double = 0.20,
    /** 每手单边佣金（USD）。Exness 标准账户黄金通常为 0，此处可配置 */
    val commissionPerLot: Double = 0.0,
    /** 每手隔夜费（USD/晚）。第一版未实现结算，默认 0 */
    val swapPerLotPerNight: Double = 0.0,
) {
    /** 一个最小价格变动单位 */
    val point: Double get() = Math.pow(10.0, -digits.toDouble())

    /**
     * 给定该品种当前价格，1 单位**基础货币**折合多少 USD。
     *
     * - 基础货币就是美元（USDJPY / USDCAD / USDCHF）：1.0
     * - 报价货币是美元（EURUSD / XAUUSD / XAGUSD）：就是价格本身
     * - 交叉盘（如 EURGBP）：还需要第三条汇率，当前内置品种表里没有这种
     */
    fun usdPerBaseUnit(price: Double): Double = when {
        baseCurrency == "USD" -> 1.0
        quoteCurrency == "USD" -> if (price > 0.0) price else 1.0
        else -> if (price > 0.0) price else 1.0
    }

    /**
     * 给定该品种当前价格，1 单位**报价货币**折合多少 USD。
     *
     * - 报价货币就是美元：1.0
     * - 基础货币是美元（USDJPY 等）：1 / 价格
     */
    fun usdPerQuoteUnit(price: Double): Double = when {
        quoteCurrency == "USD" -> 1.0
        baseCurrency == "USD" -> if (price > 0.0) 1.0 / price else 1.0
        else -> 1.0
    }

    /** 将价格对齐到品种精度 */
    fun normalize(price: Double): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(price * f) / f
    }

    /** 将手数对齐到合法步进并夹紧到 [minLot, maxLot] */
    fun normalizeLot(lot: Double): Double {
        if (!lot.isFinite() || lot <= 0.0) return minLot
        val steps = Math.round(lot / lotStep).toDouble()
        val aligned = steps * lotStep
        val clamped = aligned.coerceIn(minLot, maxLot)
        val f = Math.round(1.0 / lotStep)
        return Math.round(clamped * f) / f.toDouble()
    }

    fun isValidLot(lot: Double): Boolean {
        if (!lot.isFinite() || lot < minLot || lot > maxLot) return false
        val ratio = lot / lotStep
        return Math.abs(ratio - Math.round(ratio)) < 1e-6
    }
}
