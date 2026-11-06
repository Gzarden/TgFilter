package com.tgfilter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Все настройки и счётчики приложения. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("tgfilter", Context.MODE_PRIVATE)

    /** Главный выключатель фильтра. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    /** Записывать в журнал уведомления всех приложений (для диагностики). */
    var logAll: Boolean
        get() = sp.getBoolean("logAll", false)
        set(v) { sp.edit().putBoolean("logAll", v).apply() }

    var askedNotif: Boolean
        get() = sp.getBoolean("askedNotif", false)
        set(v) { sp.edit().putBoolean("askedNotif", v).apply() }

    /** Когда последний раз пришло любое уведомление Telegram. */
    var lastTelegramAt: Long
        get() = sp.getLong("lastTg", 0L)
        set(v) { sp.edit().putLong("lastTg", v).apply() }

    // ---------- каналы ----------

    fun channels(): List<Channel> = synchronized(LOCK) { readChannels() }

    private fun readChannels(): List<Channel> {
        val raw = sp.getString("channels", null) ?: return migrate()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Channel.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeChannels(list: List<Channel>) {
        val arr = JSONArray()
        for (c in list) arr.put(c.toJson())
        sp.edit().putString("channels", arr.toString()).apply()
    }

    /** Переносим единственный канал из версии 1, если он был настроен. */
    private fun migrate(): List<Channel> {
        val list = ArrayList<Channel>()
        val kw = sp.getString("keyword", "") ?: ""
        if (kw.isNotBlank()) {
            list.add(
                Channel(
                    id = newId(),
                    name = kw.trim(),
                    fromMin = sp.getInt("start", 10 * 60),
                    toMin = sp.getInt("end", 18 * 60),
                    days = if (sp.getBoolean("weekends", true)) Channel.WEEKDAYS else Channel.ALL_DAYS,
                )
            )
        }
        writeChannels(list)
        return list
    }

    fun upsert(ch: Channel) {
        synchronized(LOCK) {
            val list = readChannels().toMutableList()
            val i = list.indexOfFirst { it.id == ch.id }
            if (i >= 0) list[i] = ch else list.add(ch)
            writeChannels(list)
        }
    }

    fun delete(id: String) {
        synchronized(LOCK) {
            writeChannels(readChannels().filter { it.id != id })
        }
        takePending(id)
    }

    // ---------- счётчики ----------

    fun bump(id: String, field: String) {
        synchronized(LOCK) {
            sp.edit()
                .putInt(field + "_" + id, sp.getInt(field + "_" + id, 0) + 1)
                .putLong("last_" + id, System.currentTimeMillis())
                .apply()
        }
    }

    fun stat(id: String, field: String): Int = sp.getInt(field + "_" + id, 0)

    fun lastSeen(id: String): Long = sp.getLong("last_" + id, 0L)

    // ---------- сводка пропущенного ----------

    private fun readPending(): JSONObject = try {
        JSONObject(sp.getString("pending", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun addPending(id: String, snippet: String) {
        synchronized(LOCK) {
            val root = readPending()
            val o = root.optJSONObject(id) ?: JSONObject()
            val old = o.optJSONArray("l") ?: JSONArray()
            val lines = ArrayList<String>()
            for (i in 0 until old.length()) lines.add(old.getString(i))
            lines.add(snippet)
            val keep = JSONArray()
            for (s in lines.takeLast(6)) keep.put(s)
            o.put("n", o.optInt("n", 0) + 1)
            o.put("l", keep)
            root.put(id, o)
            sp.edit().putString("pending", root.toString()).apply()
        }
    }

    fun pendingIds(): List<String> = synchronized(LOCK) {
        val keys = readPending().keys()
        val out = ArrayList<String>()
        while (keys.hasNext()) out.add(keys.next())
        out
    }

    fun pendingCount(id: String): Int = synchronized(LOCK) {
        readPending().optJSONObject(id)?.optInt("n", 0) ?: 0
    }

    /** Забирает накопленное по каналу и очищает его. */
    fun takePending(id: String): Pair<Int, List<String>>? = synchronized(LOCK) {
        val root = readPending()
        val o = root.optJSONObject(id)
        if (o == null) {
            null
        } else {
            val arr = o.optJSONArray("l") ?: JSONArray()
            val lines = ArrayList<String>()
            for (i in 0 until arr.length()) lines.add(arr.getString(i))
            val n = o.optInt("n", 0)
            root.remove(id)
            sp.edit().putString("pending", root.toString()).apply()
            Pair(n, lines)
        }
    }

    companion object {
        private val LOCK = Any()
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}
