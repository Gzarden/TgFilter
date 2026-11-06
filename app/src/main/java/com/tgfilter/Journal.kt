package com.tgfilter

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Одна запись журнала.
 * kind: BLOCKED, ALLOWED, OTHER, SUMMARY, SUMMARY_REMOVED, DIGEST, SYSTEM, OTHER_APP.
 */
data class Entry(
    val ts: Long,
    val kind: String,
    val pkg: String = "",
    val title: String = "",
    val text: String = "",
    val sub: String = "",
    val conv: String = "",
    val extra: String = "",
    val summary: Boolean = false,
    val sysChannel: String = "",
    val rule: String = "",
    val reason: String = "",
)

/** Журнал хранится в файле: одна запись в строке (JSON), последние ~500 штук. */
object Journal {
    private const val FILE = "journal.jsonl"
    private const val KEEP = 500
    private val lock = Any()
    private var count = -1
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun cut(s: String, n: Int): String = if (s.length > n) s.substring(0, n) + "…" else s

    fun sys(ctx: Context, message: String) {
        add(ctx, Entry(System.currentTimeMillis(), "SYSTEM", reason = message))
    }

    fun add(ctx: Context, e: Entry) {
        try {
            synchronized(lock) {
                val f = File(ctx.applicationContext.filesDir, FILE)
                val j = JSONObject()
                    .put("t", e.ts)
                    .put("k", e.kind)
                    .put("p", e.pkg)
                    .put("ti", cut(e.title, 300))
                    .put("tx", cut(e.text, 400))
                    .put("sb", cut(e.sub, 200))
                    .put("cv", cut(e.conv, 200))
                    .put("ex", cut(e.extra, 600))
                    .put("sm", e.summary)
                    .put("sc", e.sysChannel)
                    .put("ru", e.rule)
                    .put("re", cut(e.reason, 300))
                f.appendText(j.toString() + "\n")
                count = if (count < 0) f.readLines().size else count + 1
                if (count > KEEP + 100) {
                    val keep = f.readLines().takeLast(KEEP)
                    f.writeText(keep.joinToString("\n") + "\n")
                    count = keep.size
                }
            }
        } catch (ex: Exception) {
            // журнал не должен ломать работу фильтра
        }
        for (l in listeners) l()
    }

    private fun fromJson(j: JSONObject): Entry = Entry(
        ts = j.optLong("t"),
        kind = j.optString("k"),
        pkg = j.optString("p"),
        title = j.optString("ti"),
        text = j.optString("tx"),
        sub = j.optString("sb"),
        conv = j.optString("cv"),
        extra = j.optString("ex"),
        summary = j.optBoolean("sm"),
        sysChannel = j.optString("sc"),
        rule = j.optString("ru"),
        reason = j.optString("re"),
    )

    /** Все записи, новые сверху. */
    fun read(ctx: Context): List<Entry> = synchronized(lock) {
        val f = File(ctx.applicationContext.filesDir, FILE)
        if (!f.exists()) {
            emptyList<Entry>()
        } else {
            f.readLines().mapNotNull { line ->
                try {
                    fromJson(JSONObject(line))
                } catch (ex: Exception) {
                    null
                }
            }.reversed()
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            File(ctx.applicationContext.filesDir, FILE).delete()
            count = 0
        }
        for (l in listeners) l()
    }
}

/** Текст журнала для копирования и отправки (по порядку времени). */
object Export {
    fun text(ctx: Context, maxEntries: Int = 300): String {
        val prefs = Prefs(ctx)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val st = Health.check(ctx)
        val all = Journal.read(ctx)
        val shown = all.take(maxEntries).reversed()

        val sb = StringBuilder()
        sb.append("TgFilter 2.0 — журнал\n")
        sb.append("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Сформировано: ${fmt.format(Date())}\n")
        sb.append("Доступ к уведомлениям: ${if (Health.listenerEnabled(ctx)) "да" else "НЕТ"}; ")
        sb.append("служба подключена: ${if (FilterService.connected) "да" else "НЕТ"}\n")
        sb.append("Фильтр: ${if (prefs.enabled) "включён" else "выключен"}; запись всех приложений: ${if (prefs.logAll) "да" else "нет"}\n")
        val last = prefs.lastTelegramAt
        sb.append("Последнее уведомление Telegram: ${if (last == 0L) "ни разу" else fmt.format(Date(last))}\n")
        sb.append("Копии для часов: ${if (prefs.forward) "включены" else "выключены"}\n")
        sb.append("Состояние: ${st.title}\n")
        for (i in st.issues) sb.append("  ! ${i.text}\n")
        sb.append("Каналы:\n")
        val channels = prefs.channels()
        if (channels.isEmpty()) sb.append("  (нет)\n")
        for (c in channels) {
            sb.append("  - «${c.name}»: ${c.scheduleText()}; ${if (c.enabled) "включён" else "выключен"}; ")
            sb.append("сводка: ${if (c.digest) "да" else "нет"}; ")
            sb.append("скрыто ${prefs.stat(c.id, "blocked")}, показано ${prefs.stat(c.id, "allowed")}\n")
        }
        sb.append("Записей в журнале: ${all.size}, выгружено последних: ${shown.size}\n")
        sb.append("\n")

        for (e in shown) {
            sb.append("[${fmt.format(Date(e.ts))}] ${e.kind}")
            if (e.pkg.isNotEmpty()) sb.append("  пакет=${e.pkg}")
            sb.append("\n")
            if (e.title.isNotEmpty()) sb.append("  заголовок: ${e.title}\n")
            if (e.text.isNotEmpty()) sb.append("  текст: ${e.text}\n")
            if (e.sub.isNotEmpty()) sb.append("  подзаголовок: ${e.sub}\n")
            if (e.conv.isNotEmpty()) sb.append("  беседа: ${e.conv}\n")
            if (e.extra.isNotEmpty()) sb.append("  доп.: ${e.extra}\n")
            if (e.pkg.isNotEmpty()) sb.append("  сводная группа: ${if (e.summary) "да" else "нет"}; канал Android: ${e.sysChannel}\n")
            if (e.rule.isNotEmpty()) sb.append("  правило: ${e.rule}\n")
            if (e.reason.isNotEmpty()) sb.append("  решение: ${e.reason}\n")
        }
        return sb.toString()
    }
}
