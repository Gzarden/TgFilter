package com.tgfilter

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.util.Locale

/** Снимок уведомления: все текстовые поля, в которых может быть название канала. */
class Snap(
    val pkg: String,
    val key: String,
    val groupKey: String?,
    val title: String,
    val text: String,
    val sub: String,
    val conv: String,
    val big: String,
    val lines: String,
    val senders: List<String>,
    val latest: String,
    val summary: Boolean,
    val sysChannel: String,
) {
    /** Дополнительные поля одной строкой, для журнала. */
    fun extra(): String {
        val parts = ArrayList<String>()
        if (big.isNotBlank()) parts.add("большой текст: $big")
        if (lines.isNotBlank()) parts.add("строки: $lines")
        if (senders.isNotEmpty()) parts.add("отправители: " + senders.joinToString(", "))
        return parts.joinToString("; ")
    }

    companion object {
        fun from(sbn: StatusBarNotification): Snap {
            val n = sbn.notification
            val x: Bundle = n.extras ?: Bundle()

            fun s(k: String): String = x.getCharSequence(k)?.toString().orEmpty()

            val senders = ArrayList<String>()
            val msgTexts = ArrayList<String>()
            @Suppress("DEPRECATION")
            val msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (msgs != null) {
                for (m in msgs) {
                    if (m is Bundle) {
                        val sender = m.getCharSequence("sender")?.toString().orEmpty()
                        val text = m.getCharSequence("text")?.toString().orEmpty()
                        if (sender.isNotBlank()) senders.add(sender)
                        if (text.isNotBlank()) msgTexts.add(text)
                    }
                }
            }
            val textLines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" | ") { it.toString() }.orEmpty()
            val lines = if (textLines.isNotBlank()) textLines else msgTexts.joinToString(" | ")
            val text = s(Notification.EXTRA_TEXT)

            return Snap(
                pkg = sbn.packageName,
                key = sbn.key,
                groupKey = sbn.groupKey,
                title = s(Notification.EXTRA_TITLE),
                text = text,
                sub = s(Notification.EXTRA_SUB_TEXT),
                conv = s(Notification.EXTRA_CONVERSATION_TITLE),
                big = s(Notification.EXTRA_BIG_TEXT),
                lines = lines,
                senders = senders,
                latest = msgTexts.lastOrNull() ?: text,
                summary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                sysChannel = n.channelId.orEmpty(),
            )
        }
    }
}

object Matcher {
    /** Оставляем только буквы и цифры в нижнем регистре: смайлики, пробелы и знаки не мешают. */
    fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.ROOT)) {
            val c = if (ch == 'ё') 'е' else ch
            if (Character.isLetterOrDigit(c)) sb.append(c)
        }
        return sb.toString()
    }

    fun matches(ch: Channel, s: Snap): Boolean {
        val n = norm(ch.name)
        if (n.isEmpty()) return false
        val primary = ArrayList<String>()
        primary.add(s.title)
        primary.add(s.conv)
        primary.add(s.sub)
        primary.addAll(s.senders)
        if (primary.any { norm(it).contains(n) }) return true
        if (norm(s.text).startsWith(n) || norm(s.big).startsWith(n)) return true
        return s.lines.split(" | ").any { norm(it).startsWith(n) }
    }
}
