package com.music.bitchord.ios

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * The few calendar facts Replay needs, in the device's own time zone, without a date library:
 * a day is its year, month and day, ordered and counted through its epoch day.
 */
internal data class Day(val year: Int, val month: Int, val dayOfMonth: Int) : Comparable<Day> {

    /** Days since 1970-01-01, Howard Hinnant's days-from-civil. */
    val epochDay: Long
        get() {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + dayOfMonth - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097L + doe - 719_468
        }

    /** 1 for Monday through 7 for Sunday, as ISO counts them. */
    val dayOfWeek: Int get() = (((epochDay + 3) % 7 + 7) % 7 + 1).toInt()

    fun plusDays(days: Long): Day = ofEpochDay(epochDay + days)

    /** The Monday on or before this day. */
    fun startOfWeek(): Day = plusDays(-(dayOfWeek - 1).toLong())

    val monthKey: MonthKey get() = MonthKey(year, month)

    override fun compareTo(other: Day): Int = epochDay.compareTo(other.epochDay)

    /** `YYYY-MM-DD`. */
    override fun toString(): String = "${pad(year, 4)}-${pad(month, 2)}-${pad(dayOfMonth, 2)}"

    companion object {
        fun today(): Day = localNow().day

        /** `YYYY-MM-DD`, or null for anything else. */
        fun parse(text: String): Day? {
            val parts = text.trim().split('-')
            if (parts.size != 3) return null
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            val d = parts[2].toIntOrNull()?.takeIf { it in 1..MonthKey(y, m).length } ?: return null
            return Day(y, m, d)
        }

        fun ofEpochDay(epochDay: Long): Day {
            val z = epochDay + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return Day(y, m, d)
        }
    }
}

/** A calendar month: Replay's buckets, one file each, named `YYYY-MM`. */
internal data class MonthKey(val year: Int, val month: Int) : Comparable<MonthKey> {
    val length: Int
        get() = when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

    fun firstDay() = Day(year, month, 1)
    fun lastDay() = Day(year, month, length)

    val name: String get() = MONTH_NAMES[month - 1]

    override fun compareTo(other: MonthKey): Int = compareValuesBy(this, other, { it.year }, { it.month })

    override fun toString(): String = "${pad(year, 4)}-${pad(month, 2)}"

    companion object {
        fun parse(text: String): MonthKey? {
            val parts = text.trim().split('-')
            if (parts.size != 2) return null
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            return MonthKey(y, m)
        }

        private val MONTH_NAMES = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
    }
}

/** A moment as the device's clock and time zone read it. */
internal data class LocalMoment(val day: Day, val hour: Int)

internal fun localNow(): LocalMoment = localMoment(com.music.bitchord.data.wallClockMs() / 1000.0)

internal fun localMoment(epochSeconds: Double): LocalMoment {
    val date = NSDate.dateWithTimeIntervalSince1970(epochSeconds)
    val parts = NSCalendar.currentCalendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour,
        fromDate = date,
    )
    return LocalMoment(Day(parts.year.toInt(), parts.month.toInt(), parts.day.toInt()), parts.hour.toInt())
}

private fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')
