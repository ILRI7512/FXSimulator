package com.fxsimulator.market

import com.fxsimulator.data.market.glb1.Glb1Reader
import com.fxsimulator.domain.model.SymbolSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 新接入的加密行情资产（Binance BTCUSDT / ETHUSDT 1m）的可用性验收。
 *
 * 这类测试专门防「导入脚本跑通了、但读回来是错的」：
 * 字节写对了不代表刻度、品种名、时间轴都对。
 */
class BinanceAssetTest {

    private fun load(symbol: String, month: String) =
        Glb1Reader.decode(File("src/main/assets/market/$symbol/$month.glb1").readBytes())

    @Test
    fun both_symbols_have_complete_month_ranges() {
        for (symbol in listOf("BTCUSDT", "ETHUSDT")) {
            val dir = File("src/main/assets/market/$symbol")
            assertTrue("$symbol 资产目录缺失", dir.isDirectory)
            val months = dir.listFiles { f -> f.extension == "glb1" }!!
                .map { it.nameWithoutExtension }
                .sorted()
            assertTrue("$symbol 至少应有 30 个月：${months.size}", months.size >= 30)
            assertEquals("起始月份", "2023-10", months.first())
            // 月份必须连续（中间缺月会让回放跳档）
            var expectedYear = 2023
            var expectedMonth = 10
            for (m in months) {
                val got = "%04d-%02d".format(expectedYear, expectedMonth)
                assertEquals("月份应连续，期望 $got", got, m)
                expectedMonth++
                if (expectedMonth > 12) { expectedMonth = 1; expectedYear++ }
            }
        }
    }

    @Test
    fun btc_asset_decodes_with_correct_symbol_scale_and_time_axis() {
        val src = load("BTCUSDT", "2023-10")
        assertTrue("一个月应有 4 万根以上：${src.candles.size}", src.candles.size > 40_000)
        assertEquals("品种名必须写在文件头里", "BTCUSDT", src.symbol)
        assertEquals("价格小数位与 SymbolSpecs 必须一致", 2, src.digits)

        var prevTime = 0L
        for (i in src.candles.indices) {
            val c = src.candles[i]
            assertTrue("时间必须严格递增（第 $i 根）", c.timeMillis > prevTime)
            prevTime = c.timeMillis
            assertTrue("OHLC 必须自洽（第 $i 根）", c.isValid())
            assertTrue("BTC 价格应在合理量级（第 $i 根：${c.close}）", c.close in 1_000.0..500_000.0)
        }
    }

    @Test
    fun eth_asset_matches_its_symbol_spec() {
        val spec = SymbolSpecs.of("ETHUSDT")
        assertEquals("ETH 1 手 = 1 枚", 1.0, spec.contractSize, 1e-9)
        assertEquals(2, spec.digits)
        val src = load("ETHUSDT", "2023-10")
        assertEquals("ETHUSDT", src.symbol)
        assertEquals(spec.digits, src.digits)
        val first = src.candles.first()
        assertTrue("ETH 首根价格应在合理量级：${first.close}", first.close in 100.0..50_000.0)
    }

    @Test
    fun symbol_specs_include_the_new_symbols() {
        assertTrue(SymbolSpecs.ALL.contains("BTCUSDT"))
        assertTrue(SymbolSpecs.ALL.contains("ETHUSDT"))
        assertTrue(SymbolSpecs.displayName("BTCUSDT").contains("BTC"))
        assertTrue(SymbolSpecs.of("BTCUSDT").spread > 0.0)
        assertTrue(SymbolSpecs.of("ETHUSDT").spread > 0.0)
    }
}
