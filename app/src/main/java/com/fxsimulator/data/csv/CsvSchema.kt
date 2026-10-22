package com.fxsimulator.data.csv

/**
 * CSV 列定义。支持按表头名自动识别列位置，因此
 * MetaTrader 的英文表头与其中文导出表头都能解析。
 */
data class CsvSchema(
    val delimiter: Char,
    val hasHeader: Boolean,
    val indexSymbol: Int,
    val indexDate: Int,
    val indexTime: Int,
    val indexOpen: Int,
    val indexHigh: Int,
    val indexLow: Int,
    val indexClose: Int,
    val indexVolume: Int,
    val indexSpread: Int,
    val headerText: String,
) {
    /** 人类可读的列映射说明，用于导入报告 */
    fun describe(): String {
        val d = when (delimiter) {
            '\t' -> "制表符"
            ',' -> "逗号"
            ';' -> "分号"
            else -> delimiter.toString()
        }
        return "分隔符=" + d + "，表头=" + (if (hasHeader) "有" else "无（按位置解析）") +
            "，日期列=" + indexDate + "，时间列=" + indexTime +
            "，开盘=" + indexOpen + "，最高=" + indexHigh +
            "，最低=" + indexLow + "，收盘=" + indexClose
    }
}
