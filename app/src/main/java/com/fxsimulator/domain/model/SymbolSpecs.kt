package com.fxsimulator.domain.model

/**
 * 内置品种规格表。
 *
 * 合约规模按行业标准：
 * - 黄金 100 盎司/手，白银 5000 盎司/手
 * - 外汇 100,000 基础货币单位/手
 *
 * 这里只放**不随行情变化**的参数。点差以行情里逐日的累计值为准
 * （数据管线已按"一天一个平均点差"写入 GLB1），表里的 spread 只是兜底值。
 *
 * 用户不可修改这些数值 —— 设置页不再提供合约规格编辑入口。
 */
object SymbolSpecs {

    /** 可选品种，顺序即界面展示顺序 */
    val ALL: List<String> = listOf(
        "XAUUSD", "XAGUSD", "EURUSD", "GBPUSD", "USDJPY", "USDCAD", "USDCHF", "BTCUSDT", "ETHUSDT",
    )

    /** 界面展示名："XAUUSD" → "黄金 XAUUSD" */
    fun displayName(symbol: String): String = when (symbol.uppercase()) {
        "XAUUSD" -> "黄金 XAUUSD"
        "XAGUSD" -> "白银 XAGUSD"
        "EURUSD" -> "欧元 EURUSD"
        "GBPUSD" -> "英镑 GBPUSD"
        "USDJPY" -> "美日 USDJPY"
        "USDCAD" -> "美加 USDCAD"
        "USDCHF" -> "美瑞 USDCHF"
        "BTCUSDT" -> "比特币 BTCUSDT"
        "ETHUSDT" -> "以太坊 ETHUSDT"
        else -> symbol
    }

    /** 点差的兜底值（价格单位），仅在行情里读不到点差时使用 */
    private fun fallbackSpread(symbol: String): Double = when (symbol.uppercase()) {
        "XAUUSD" -> 0.20
        "XAGUSD" -> 0.030
        "EURUSD" -> 0.00012
        "GBPUSD" -> 0.00015
        "USDJPY" -> 0.015
        "USDCAD" -> 0.00015
        "USDCHF" -> 0.00015
        // 加密品种：Binance 现货的真实点差量级（USDT），仅作兜底
        "BTCUSDT" -> 2.0
        "ETHUSDT" -> 0.20
        else -> 0.0
    }

    /**
     * 取某品种的内置规格。未知品种退回黄金，保证永远有可用规格。
     */
    fun of(symbol: String): SymbolSpec {
        val s = symbol.uppercase().ifEmpty { "XAUUSD" }
        val spread = fallbackSpread(s)
        return when (s) {
            "XAUUSD" -> SymbolSpec(
                symbol = s, baseCurrency = "XAU", quoteCurrency = "USD",
                contractSize = 100.0, digits = 3, spread = spread,
            )
            "XAGUSD" -> SymbolSpec(
                symbol = s, baseCurrency = "XAG", quoteCurrency = "USD",
                contractSize = 5_000.0, digits = 3, spread = spread,
            )
            "EURUSD" -> SymbolSpec(
                symbol = s, baseCurrency = "EUR", quoteCurrency = "USD",
                contractSize = 100_000.0, digits = 5, spread = spread,
            )
            "GBPUSD" -> SymbolSpec(
                symbol = s, baseCurrency = "GBP", quoteCurrency = "USD",
                contractSize = 100_000.0, digits = 5, spread = spread,
            )
            "USDJPY" -> SymbolSpec(
                symbol = s, baseCurrency = "USD", quoteCurrency = "JPY",
                contractSize = 100_000.0, digits = 3, spread = spread,
            )
            "USDCAD" -> SymbolSpec(
                symbol = s, baseCurrency = "USD", quoteCurrency = "CAD",
                contractSize = 100_000.0, digits = 5, spread = spread,
            )
            "USDCHF" -> SymbolSpec(
                symbol = s, baseCurrency = "USD", quoteCurrency = "CHF",
                contractSize = 100_000.0, digits = 5, spread = spread,
            )
            // 加密品种（行情来自 Binance 1m K 线）。
            // 1 手 = 1 枚；报价货币实际是 USDT，这里按 1:1 视作 USD（模拟器口径，
            // 不引入 USDT/USD 汇率）。杠杆按现货常见档位取 100，避免加密波动下保证金失真。
            "BTCUSDT" -> SymbolSpec(
                symbol = s, baseCurrency = "BTC", quoteCurrency = "USD",
                contractSize = 1.0, digits = 2, spread = spread, leverage = 100,
            )
            "ETHUSDT" -> SymbolSpec(
                symbol = s, baseCurrency = "ETH", quoteCurrency = "USD",
                contractSize = 1.0, digits = 2, spread = spread, leverage = 100,
            )
            else -> SymbolSpec(
                symbol = "XAUUSD", baseCurrency = "XAU", quoteCurrency = "USD",
                contractSize = 100.0, digits = 3, spread = 0.20,
            )
        }
    }
}
