package com.fxsimulator.ui.format

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 统一的显示格式化。行情时间按 UTC 解释（与解析基准一致）。 */
object Formatters {

    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateOnly = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeOnly = DateTimeFormatter.ofPattern("HH:mm")

    fun dateTime(millis: Long?): String {
        if (millis == null || millis <= 0L) return "—"
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).format(dateTime)
    }

    fun date(millis: Long?): String {
        if (millis == null || millis <= 0L) return "—"
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).format(dateOnly)
    }

    fun time(millis: Long?): String {
        if (millis == null || millis <= 0L) return "—"
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).format(timeOnly)
    }

    fun price(value: Double?, digits: Int = 3): String {
        if (value == null || !value.isFinite()) return "—"
        return String.format("%,." + digits + "f", value)
    }

    fun money(value: Double, digits: Int = 2): String {
        if (!value.isFinite()) return "—"
        return String.format("%,." + digits + "f", value)
    }

    fun signedMoney(value: Double, digits: Int = 2): String {
        if (!value.isFinite()) return "—"
        val sign = if (value > 0) "+" else if (value < 0) "-" else ""
        return sign + String.format("%,." + digits + "f", Math.abs(value))
    }

    fun signedPrice(value: Double?, digits: Int = 3): String {
        if (value == null || !value.isFinite()) return "—"
        val sign = if (value > 0) "+" else if (value < 0) "-" else ""
        return sign + String.format("%,." + digits + "f", Math.abs(value))
    }

    fun percent(value: Double): String =
        if (!value.isFinite()) "—" else String.format("%.1f%%", value)

    fun lot(value: Double): String = String.format("%.2f", value)

    fun bytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }
}
