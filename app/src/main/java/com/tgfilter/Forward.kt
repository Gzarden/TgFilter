package com.tgfilter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification

/**
 * Копии уведомлений для часов.
 *
 * Huawei Health получает уведомление сразу после появления и пересылает на часы, поэтому
 * удалить его на телефоне «вовремя» нельзя. Схема другая: в Huawei Health отключается Telegram
 * и включается TgFilter, а TgFilter сам создаёт беззвучную копию каждого уведомления,
 * кроме тех, что скрыты правилами. Копия живёт, пока жив оригинал в Telegram.
 */
object Forward {
    const val CH_ID = "watch_copy"
    private const val ID = 1
    private val last = HashMap<String, String>()

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_ID) == null) {
            val ch = NotificationChannel(CH_ID, "Копии для часов", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Беззвучные копии уведомлений Telegram, которые Huawei Health пересылает на часы"
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.enableLights(false)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun body(s: Snap): String {
        var text = s.latest.ifBlank { s.text }.ifBlank { s.big }
        val title = s.title.ifBlank { s.conv }
        val who = s.lastSender
        if (who.isNotBlank() && !who.equals(title, ignoreCase = true) && text.isNotBlank()) {
            text = "$who: $text"
        }
        return text
    }

    /** Создаёт копию для часов. Возвращает короткое пояснение для журнала. */
    fun copy(ctx: Context, sbn: StatusBarNotification, s: Snap): String {
        if (!Prefs(ctx).forward) return "копия для часов выключена"
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        ) {
            return "копия не нужна: служебное уведомление"
        }
        if (n.category == Notification.CATEGORY_CALL) return "копия не нужна: звонок"
        if (Health.notifPermissionDenied(ctx)) return "копия НЕ отправлена: нет разрешения на уведомления"

        val title = s.title.ifBlank { s.conv }
        val text = body(s)
        if (title.isBlank() && text.isBlank()) return "копия не нужна: пустое уведомление"

        val sig = title + "\u0000" + text
        synchronized(last) {
            if (last[sbn.key] == sig) return "копия не отправлена: тот же текст уже отправлен"
            if (last.size > 200) last.clear()
            last[sbn.key] = sig
        }

        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return "копия НЕ отправлена: нет доступа к уведомлениям"
        ensureChannel(ctx)
        val b = Notification.Builder(ctx, CH_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup("tgf_" + sbn.key)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(sbn.postTime)
        val open = n.contentIntent
        if (open != null) b.setContentIntent(open)
        nm.notify(sbn.key, ID, b.build())
        return "копия для часов отправлена"
    }

    /** Убирает копию, когда пропал оригинал (прочитан или удалён). */
    fun remove(ctx: Context, key: String) {
        synchronized(last) { last.remove(key) }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(key, ID)
    }

    fun forget(key: String) {
        synchronized(last) { last.remove(key) }
    }

    /** Тестовое уведомление: помогает проверить, что Huawei Health пересылает TgFilter. */
    fun test(ctx: Context): Boolean {
        if (Health.notifPermissionDenied(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CH_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("TgFilter: проверка связи")
            .setContentText("Если это уведомление появилось на часах, пересылка настроена верно.")
            .setAutoCancel(true)
            .build()
        nm.notify("test", ID, n)
        return true
    }
}
