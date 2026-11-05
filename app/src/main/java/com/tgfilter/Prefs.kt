package com.tgfilter

import android.content.Context
import java.util.Calendar

/** Настройки приложения. Время хранится в минутах от полуночи. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("tgfilter", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Часть названия канала, уведомления которого фильтруем. */
    var keyword: String
        get() = sp.getString("keyword", "") ?: ""
        set(v) = sp.edit().putString("keyword", v.trim()).apply()

    /** Начало интервала, когда уведомления разрешены. */
    var startMin: Int
        get() = sp.getInt("start", 10 * 60)
        set(v) = sp.edit().putInt("start", v).apply()

    /** Конец интервала, когда уведомления разрешены. */
    var endMin: Int
        get() = sp.getInt("end", 18 * 60)
        set(v) = sp.edit().putInt("end", v).apply()

    var blockWeekends: Boolean
        get() = sp.getBoolean("weekends", true)
        set(v) = sp.edit().putBoolean("weekends", v).apply()

    var blockedCount: Int
        get() = sp.getInt("blocked", 0)
        set(v) = sp.edit().putInt("blocked", v).apply()

    /** true, если прямо сейчас уведомления канала показывать можно. */
    fun isAllowedNow(now: Calendar = Calendar.getInstance()): Boolean {
        if (blockWeekends) {
            val day = now.get(Calendar.DAY_OF_WEEK)
            if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false
        }
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (startMin <= endMin) {
            m in startMin until endMin
        } else {
            // интервал через полночь, например 22:00–06:00
            m >= startMin || m < endMin
        }
    }

    /** Последние названия чатов из уведомлений Telegram (для подсказки в настройках). */
    fun recentTitles(): List<String> =
        (sp.getString("recent", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun remember(title: String) {
        val clean = title.replace('\n', ' ').trim()
        if (clean.isEmpty()) return
        val list = recentTitles().toMutableList()
        list.remove(clean)
        list.add(0, clean)
        sp.edit().putString("recent", list.take(10).joinToString("\n")).apply()
    }
}
