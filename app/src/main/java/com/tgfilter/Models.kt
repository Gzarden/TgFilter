package com.tgfilter

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Правило для одного канала: когда его уведомления можно показывать.
 * Время хранится в минутах от полуночи, дни недели в битовой маске (бит 0 = понедельник).
 */
data class Channel(
    val id: String,
    var name: String,
    var enabled: Boolean = true,
    var fromMin: Int = 10 * 60,
    var toMin: Int = 18 * 60,
    var days: Int = WEEKDAYS,
    var digest: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("enabled", enabled)
        .put("from", fromMin)
        .put("to", toMin)
        .put("days", days)
        .put("digest", digest)

    private fun dayOn(d: Int): Boolean = (days and (1 shl (((d % 7) + 7) % 7))) != 0

    /** true, если в этот день недели и минуту уведомления можно показывать. */
    fun isAllowedAt(day: Int, minute: Int): Boolean {
        if (fromMin == toMin) return dayOn(day)
        return if (fromMin < toMin) {
            dayOn(day) && minute >= fromMin && minute < toMin
        } else {
            // интервал через полночь, например 22:00-06:00
            (dayOn(day) && minute >= fromMin) || (dayOn(day - 1) && minute < toMin)
        }
    }

    fun isAllowed(c: Calendar = Calendar.getInstance()): Boolean =
        isAllowedAt(dayIndex(c), c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))

    /** Ближайший момент в будущем, когда показ станет разрешён. */
    fun nextAllowedStart(from: Calendar = Calendar.getInstance()): Long? {
        val c = from.clone() as Calendar
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        for (i in 1..(8 * 24 * 60)) {
            c.add(Calendar.MINUTE, 1)
            if (isAllowed(c)) return c.timeInMillis
        }
        return null
    }

    fun daysText(): String = when (days and ALL_DAYS) {
        ALL_DAYS -> "каждый день"
        WEEKDAYS -> "Пн–Пт"
        0 -> "никогда не показывать"
        else -> DAY_NAMES.filterIndexed { i, _ -> (days and (1 shl i)) != 0 }.joinToString(", ")
    }

    fun scheduleText(): String {
        val time = if (fromMin == toMin) "весь день" else fmt(fromMin) + "–" + fmt(toMin)
        return time + " · " + daysText()
    }

    companion object {
        const val WEEKDAYS = 31
        const val ALL_DAYS = 127
        val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

        /** Понедельник = 0 ... воскресенье = 6. */
        fun dayIndex(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7

        fun fmt(min: Int): String = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

        fun fromJson(o: JSONObject): Channel = Channel(
            id = o.getString("id"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            fromMin = o.optInt("from", 10 * 60),
            toMin = o.optInt("to", 18 * 60),
            days = o.optInt("days", WEEKDAYS),
            digest = o.optBoolean("digest", true),
        )
    }
}
