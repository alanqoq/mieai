package com.mieai.store.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * 时间工具类
 */
object TimeUtils {

    private val ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai")

    /**
     * 当前时间戳（毫秒）
     */
    fun nowMs(): Long = System.currentTimeMillis()

    /**
     * 时间戳转可读字符串
     */
    fun formatTimestamp(timestampMs: Long, pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
        val instant = Instant.ofEpochMilli(timestampMs)
        val dateTime = LocalDateTime.ofInstant(instant, ZONE_SHANGHAI)
        return dateTime.format(DateTimeFormatter.ofPattern(pattern))
    }

    /**
     * 解析日期字符串为时间戳（毫秒）
     * 支持格式：yyyy-MM-dd, yyyy-MM-dd HH:mm:ss
     */
    fun parseDate(dateStr: String): Long? {
        return try {
            // 尝试完整日期时间
            val dateTime = LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            dateTime.atZone(ZONE_SHANGHAI).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                // 尝试仅日期
                val date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                date.atStartOfDay(ZONE_SHANGHAI).toInstant().toEpochMilli()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * 解析时间范围字符串
     * 格式：2025-07-01~2025-07-11
     * @return Pair(startTimeMs, endTimeMs) 或 null
     */
    fun parseTimeRange(rangeStr: String): Pair<Long, Long>? {
        val parts = rangeStr.split("~")
        if (parts.size != 2) return null

        val start = parseDate(parts[0].trim()) ?: return null
        val end = parseDate(parts[1].trim()) ?: return null

        // 如果是仅日期，结束时间设为当天 23:59:59
        val adjustedEnd = if (parts[1].trim().length == 10) {
            end + 24 * 60 * 60 * 1000 - 1
        } else {
            end
        }

        return start to adjustedEnd
    }

    /**
     * 计算 N 天前的时间戳
     */
    fun daysAgoMs(days: Int): Long {
        return nowMs() - days.toLong() * 24 * 60 * 60 * 1000
    }

    /**
     * 可读的时间差描述
     */
    fun timeAgo(timestampMs: Long): String {
        val diff = nowMs() - timestampMs
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 30 -> "${days}天前"
            else -> formatTimestamp(timestampMs, "yyyy-MM-dd")
        }
    }
}
