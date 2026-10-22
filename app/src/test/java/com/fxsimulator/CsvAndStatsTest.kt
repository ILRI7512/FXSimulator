package com.fxsimulator

import com.fxsimulator.data.csv.CsvCandleParser
import com.fxsimulator.domain.model.OrderStatus
import com.fxsimulator.domain.model.TradeDirection
import com.fxsimulator.domain.model.TradeRecord
import com.fxsimulator.stats.PerformanceAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSV 解析与交易统计。
 *
 * 说明：原来还有一条「乱序/重复时间戳能被修正」的用例，它依赖 CandleIndex。
 * 导入功能移除后 CandleIndex 一并删除，这条用例失去依托 —— 内置 GLB1 数据本身就保证有序，
 * 不用再在运行期做这件事。
 */
class CsvAndStatsTest {

    private val englishHeader =
        "<DATE>\t<TIME>\t<OPEN>\t<HIGH>\t<LOW>\t<CLOSE>\t<TICKVOL>\t<VOL>\t<SPREAD>"
    private val chineseHeader =
        "品种,日期,时间,开盘价,最高价,最低价,收盘价,Tick量,真实成交量,点差"

    @Test
    fun detectsMt5EnglishHeader() {
        val schema = CsvCandleParser.detectSchema(englishHeader)
        assertNotNull(schema)
        assertEquals('\t', schema!!.delimiter)
        assertEquals(0, schema.indexDate)
        assertEquals(1, schema.indexTime)
        assertEquals(2, schema.indexOpen)
        assertEquals(5, schema.indexClose)
        assertEquals(8, schema.indexSpread)
    }

    @Test
    fun detectsChineseHeaderWithSymbolColumn() {
        val schema = CsvCandleParser.detectSchema(chineseHeader)
        assertNotNull(schema)
        assertEquals(',', schema!!.delimiter)
        assertEquals(0, schema.indexSymbol)
        assertEquals(1, schema.indexDate)
        assertEquals(2, schema.indexTime)
        assertEquals(3, schema.indexOpen)
        // 列序：品种,日期,时间,开盘,最高,最低,收盘,Tick量,真实成交量,点差 → 点差在第 9 列
        assertEquals(9, schema.indexSpread)
    }

    @Test
    fun parsesTimestampInBothFormats() {
        val a = CsvCandleParser.parseTimestamp("2025.01.01", "23:05:00")
        val b = CsvCandleParser.parseTimestamp("2025-01-01", "23:05:00")
        assertNotNull(a)
        assertEquals(a, b)
        assertNull(CsvCandleParser.parseTimestamp("bad", "23:05:00"))
        assertNull(CsvCandleParser.parseTimestamp("2025.13.01", "23:05:00"))
    }

    @Test
    fun parsesEnglishDataRow() {
        val schema = CsvCandleParser.detectSchema(englishHeader)!!
        val line = "2021.01.03\t00:00:00\t1909.308\t1914.896\t1907.729\t1913.094\t3729\t0\t0"
        val r = CsvCandleParser.parseLine(line, schema)
        assertNotNull(r.candle)
        assertEquals(1909.308, r.candle!!.open, 1e-6)
        assertEquals(1914.896, r.candle.high, 1e-6)
        assertEquals(3729L, r.candle.volume)
    }

    @Test
    fun parsesChineseRowAndFiltersSymbol() {
        val schema = CsvCandleParser.detectSchema(chineseHeader)!!
        val line = "XAUUSDm,2025.01.01,23:05:00,2625.179,2625.298,2624.922,2625.020,41,0,160"
        val ok = CsvCandleParser.parseLine(line, schema, expectedSymbol = "XAUUSD")
        assertNotNull(ok.candle)
        assertEquals(2625.179, ok.candle!!.open, 1e-6)
        // 160 点 ÷ 1000（3 位小数）= 0.160 美元
        assertEquals(0.160, ok.candle.spread, 1e-9)

        val wrong = CsvCandleParser.parseLine(line, schema, expectedSymbol = "EURUSD")
        assertTrue(wrong.skipped)
        assertNull(wrong.candle)
    }

    /**
     * MT5 的「点差」列单位是**点**，不是价格。
     *
     * 黄金 3 位小数时 200 点 = 0.200 美元。此前解析器直接把原始值塞进 Candle.spread，
     * 而 Candle 的文档写的是「价格为单位的原始值」—— 两者不一致；
     * 又因为旧测试断言的正是错误值（160.0），这个坑一直没被发现，
     * 直到「猜涨跌」真的去读它，入场价被抬高 160 美元，一局就爆仓。
     */
    @Test
    fun convertsSpreadColumnFromPointsIntoPriceUnits() {
        val schema = CsvCandleParser.detectSchema(chineseHeader)!!

        // 3 位小数：160 点 = 0.160
        val d3 = "XAUUSDm,2025.01.01,23:05:00,2625.179,2625.298,2624.922,2625.020,41,0,160"
        assertEquals(0.160, CsvCandleParser.parseLine(d3, schema).candle!!.spread, 1e-9)

        // 2 位小数：160 点 = 1.60
        val d2 = "XAUUSDm,2025.01.01,23:05:00,2625.17,2625.29,2624.92,2625.02,41,0,160"
        assertEquals(1.60, CsvCandleParser.parseLine(d2, schema).candle!!.spread, 1e-9)

        // 源数据没给点差时保持 0，不能变成 1 或其他任何东西
        val d0 = "XAUUSDm,2025.01.01,23:05:00,2625.179,2625.298,2624.922,2625.020,41,0,0"
        assertEquals(0.0, CsvCandleParser.parseLine(d0, schema).candle!!.spread, 1e-9)
    }

    @Test
    fun rejectsInvalidOhlc() {
        val schema = CsvCandleParser.detectSchema(englishHeader)!!
        val bad = "2021.01.03\t00:00:00\t1900.0\t1800.0\t1950.0\t1900.0\t1\t0\t0"
        val r = CsvCandleParser.parseLine(bad, schema)
        assertNull(r.candle)
        assertNotNull(r.problem)
    }


    private fun trade(net: Double, closeAt: Long): TradeRecord = TradeRecord(
        orderId = closeAt,
        symbol = "XAUUSD",
        direction = TradeDirection.BUY,
        openTimeMillis = closeAt - 60_000,
        closeTimeMillis = closeAt,
        openPrice = 2000.0,
        closePrice = 2000.0 + net / 100.0,
        volume = 1.0,
        grossProfit = net,
        commission = 0.0,
        swap = 0.0,
        netProfit = net,
        status = OrderStatus.CLOSED,
    )

    @Test
    fun winRateAndProfitRatio() {
        val trades = listOf(
            trade(100.0, 1000L), trade(-50.0, 2000L), trade(200.0, 3000L), trade(-50.0, 4000L),
        )
        val s = PerformanceAnalyzer.analyze(trades, 10000.0)
        assertEquals(4, s.totalTrades)
        assertEquals(2, s.winTrades)
        assertEquals(2, s.lossTrades)
        assertEquals(50.0, s.winRate, 1e-9)
        assertEquals(300.0, s.grossProfit, 1e-9)
        assertEquals(100.0, s.grossLoss, 1e-9)
        assertEquals(200.0, s.netProfit, 1e-9)
        assertEquals(3.0, s.profitFactor, 1e-9)
        assertEquals(150.0, s.averageWin, 1e-9)
        assertEquals(50.0, s.averageLoss, 1e-9)
        assertEquals(3.0, s.profitLossRatio, 1e-9)
    }

    @Test
    fun maxDrawdownFromEquityPeak() {
        val trades = listOf(trade(500.0, 1000L), trade(-800.0, 2000L), trade(200.0, 3000L))
        val s = PerformanceAnalyzer.analyze(trades, 10000.0)
        assertEquals(800.0, s.maxDrawdown, 1e-9)
        assertEquals(800.0 / 10500.0 * 100.0, s.maxDrawdownPercent, 1e-9)
    }

    @Test
    fun maxConsecutiveLosses() {
        val trades = listOf(
            trade(-10.0, 1000L), trade(-20.0, 2000L), trade(-30.0, 3000L),
            trade(50.0, 4000L), trade(-40.0, 5000L),
        )
        val s = PerformanceAnalyzer.analyze(trades, 10000.0)
        assertEquals(3, s.maxConsecutiveLosses)
    }

    @Test
    fun emptyTradesProduceZeroStats() {
        val s = PerformanceAnalyzer.analyze(emptyList(), 10000.0)
        assertEquals(0, s.totalTrades)
        assertEquals(0.0, s.winRate, 1e-9)
        assertEquals(0.0, s.maxDrawdown, 1e-9)
    }

    @Test
    fun dailyRealizedPnlGroupsByUtcDay() {
        val dayA = PerformanceAnalyzer.dayKeyOf(1000L)
        val trades = listOf(trade(100.0, 1000L), trade(-40.0, 2000L))
        assertEquals(60.0, PerformanceAnalyzer.dailyRealizedPnl(trades, dayA), 1e-9)
    }
}
