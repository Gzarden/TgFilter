package com.tgfilter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private const val ID = 1
    private val CHANNELS = listOf("watch_copy", "watch_copy_low", "watch_copy_min")

    private val timers = HashMap<String, Runnable>()
    private val removals = HashMap<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Какой текст уже отправлялся на часы по каждому уведомлению. Хранится на диске: если
     * систему выгрузила наше приложение, а Telegram потом заново выставил старые уведомления
     * (он делает это при каждом новом сообщении в другом чате), старые копии не воскресают.
     */
    private fun sigs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("tgfilter_fwd", Context.MODE_PRIVATE)

    private fun sigGet(ctx: Context, key: String): String? =
        sigs(ctx).getString(key, null)?.substringAfter('|', "")

    private fun sigPut(ctx: Context, key: String, sig: String) {
        val sp = sigs(ctx)
        val all = sp.all
        val ed = sp.edit()
        if (all.size > 300) {
            // убираем самые старые записи, чтобы файл не рос бесконечно
            val old = all.entries
                .map { it.key to ((it.value as? String)?.substringBefore('|')?.toLongOrNull() ?: 0L) }
                .sortedBy { it.second }
                .take(100)
            for (o in old) ed.remove(o.first)
        }
        ed.putString(key, System.currentTimeMillis().toString() + "|" + sig).apply()
    }

    private fun sigDrop(ctx: Context, key: String) {
        sigs(ctx).edit().remove(key).apply()
    }

    /**
     * Недавно отправленные на часы сообщения. Запоминаем по содержанию и времени самого сообщения,
     * а не по ключу уведомления и не по тому, жива ли копия: Telegram иногда снимает уведомление и
     * через несколько секунд выставляет то же самое, иногда под другим ключом. Без этой памяти копия
     * к тому моменту уже убрана, и то же сообщение уходило на часы второй раз.
     */
    private const val RECENT_MS = 30 * 60 * 1000L
    private const val RECENT_NO_TIME_MS = 30 * 1000L

    private fun recentKey(title: String, text: String, time: Long): String =
        "r|" + title.hashCode() + ":" + text.hashCode() + ":" + time

    private fun recentlySent(ctx: Context, title: String, text: String, time: Long): Boolean {
        val v = sigs(ctx).getString(recentKey(title, text, time), null) ?: return false
        val at = v.substringBefore('|').toLongOrNull() ?: return false
        val limit = if (time > 0L) RECENT_MS else RECENT_NO_TIME_MS
        return System.currentTimeMillis() - at < limit
    }

    private fun markSent(ctx: Context, title: String, text: String, time: Long) {
        sigPut(ctx, recentKey(title, text, time), "r")
    }

    fun isCopyChannel(id: String?): Boolean = id != null && CHANNELS.contains(id)

    /** Канал, в который сейчас кладутся копии (зависит от выбранного вида). */
    fun channelId(ctx: Context): String = CHANNELS[Prefs(ctx).copyMode.coerceIn(0, 2)]

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val defs = listOf(
            Triple(CHANNELS[0], "Копии для часов: обычные", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CHANNELS[1], "Копии для часов: тихие", NotificationManager.IMPORTANCE_LOW),
            Triple(CHANNELS[2], "Копии для часов: свёрнутые", NotificationManager.IMPORTANCE_MIN),
        )
        for (d in defs) {
            if (nm.getNotificationChannel(d.first) != null) continue
            val ch = NotificationChannel(d.first, d.second, d.third)
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

    /** Убирает копию с телефона через заданное время (если оно задано). */
    private fun scheduleRemoval(ctx: Context, key: String) {
        val ttl = Prefs(ctx).copyTtlSec
        synchronized(timers) {
            val old = timers.remove(key)
            if (old != null) handler.removeCallbacks(old)
        }
        if (ttl <= 0) return
        val app = ctx.applicationContext
        val r = Runnable {
            synchronized(timers) { timers.remove(key) }
            app.getSystemService(NotificationManager::class.java)?.cancel(key, ID)
        }
        synchronized(timers) { timers[key] = r }
        handler.postDelayed(r, ttl * 1000L)
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

        // Оригинал вернулся (Telegram снял и тут же выставил его заново): копию не трогаем.
        cancelPendingRemoval(sbn.key)

        // Это же сообщение уже уходило на часы (в том числе под другим ключом или после того,
        // как прежняя копия была убрана): второй раз не отправляем.
        if (recentlySent(ctx, title, text, s.latestTime)) {
            return "копия не отправлена: это сообщение уже отправлялось на часы (метка сообщения: " + s.latestTime + ")"
        }

        // Подпись: заголовок и текст. Время сообщения в неё не входит: Telegram может выставить
        // одно и то же уведомление дважды подряд с разной меткой времени, и на часах получался дубль.
        val sig = title + "\u0000" + text
        if (sigGet(ctx, sbn.key) == sig) return "копия не отправлена: это повтор старого уведомления"
        sigPut(ctx, sbn.key, sig)
        markSent(ctx, title, text, s.latestTime)

        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return "копия НЕ отправлена: нет доступа к уведомлениям"
        ensureChannel(ctx)
        val b = Notification.Builder(ctx, channelId(ctx))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup("tgf_" + sbn.key)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(if (n.`when` > 0L) n.`when` else sbn.postTime)
        val open = n.contentIntent
        if (open != null) b.setContentIntent(open)
        nm.notify(sbn.key, ID, b.build())
        scheduleRemoval(ctx, sbn.key)
        return "копия для часов отправлена (метка сообщения: " + s.latestTime + ")"
    }

    /** Убирает копию сразу. */
    fun remove(ctx: Context, key: String) {
        cancelPendingRemoval(key)
        sigDrop(ctx, key)
        synchronized(timers) {
            val old = timers.remove(key)
            if (old != null) handler.removeCallbacks(old)
        }
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(key, ID)
    }

    /**
     * Оригинал пропал. Telegram снимает уведомление и в том случае, когда оно прочитано,
     * и когда он просто обновляет список после нового сообщения в другом чате. Поэтому
     * копию убираем с задержкой 20 с (у одного канала он возвращается примерно через 5 с):
     * если оригинал вернётся, копия останется как была.
     */
    fun removeLater(ctx: Context, key: String) {
        val app = ctx.applicationContext
        cancelPendingRemoval(key)
        val r = Runnable {
            synchronized(removals) { removals.remove(key) }
            remove(app, key)
        }
        synchronized(removals) { removals[key] = r }
        handler.postDelayed(r, 20000L)
    }

    private fun cancelPendingRemoval(key: String) {
        synchronized(removals) {
            val old = removals.remove(key)
            if (old != null) handler.removeCallbacks(old)
        }
    }

    fun forget(ctx: Context, key: String) {
        sigDrop(ctx, key)
    }

    /** Тестовое уведомление: помогает проверить, что Huawei Health пересылает TgFilter. */
    fun test(ctx: Context): Boolean {
        if (Health.notifPermissionDenied(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, channelId(ctx))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("TgFilter: проверка связи")
            .setContentText("Если это уведомление появилось на часах, пересылка настроена верно.")
            .setAutoCancel(true)
            .build()
        nm.notify("test", ID, n)
        scheduleRemoval(ctx, "test")
        return true
    }
}
