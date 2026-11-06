package com.tgfilter

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Видит уведомления на телефоне. Для каждого уведомления Telegram находит подходящий
 * канал: если сейчас не время показа, удаляет уведомление и ничего не отправляет на часы;
 * иначе оставляет его и создаёт копию для часов. Всё записывается в журнал.
 */
class FilterService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        connected = true
        Journal.sys(this, "Служба фильтра подключена к системе")
        try {
            Digest.flushIfDue(this)
            Digest.schedule(this)
        } catch (e: Exception) {
            // не критично
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        Journal.sys(this, "Служба отключена системой, запрошено повторное подключение")
        rebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Journal.sys(this, "Ошибка обработки уведомления: " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap,
        reason: Int
    ) {
        try {
            handleRemoved(sbn, reason)
        } catch (e: Exception) {
            // не критично
        }
    }

    private fun handleRemoved(sbn: StatusBarNotification, reason: Int) {
        if (sbn.packageName == packageName) {
            // Копию для часов убрали (смахнули на телефоне или на часах): убираем и оригинал.
            if (!Forward.isCopyChannel(sbn.notification.channelId)) return
            if (reason == NotificationListenerService.REASON_CANCEL ||
                reason == NotificationListenerService.REASON_LISTENER_CANCEL
            ) {
                val orig = sbn.tag ?: return
                Forward.forget(this, orig)
                cancelNotification(orig)
            }
        } else if (sbn.packageName in TELEGRAM_PACKAGES) {
            // Оригинал пропал (прочитан в Telegram): копия больше не нужна.
            // С задержкой: Telegram мог просто обновить список и сейчас выставит его снова.
            Forward.removeLater(this, sbn.key)
        }
    }

    private fun log(kind: String, s: Snap, rule: String, reason: String) {
        Journal.add(
            this,
            Entry(
                ts = System.currentTimeMillis(),
                kind = kind,
                pkg = s.pkg,
                title = s.title,
                text = s.text,
                sub = s.sub,
                conv = s.conv,
                extra = s.extra(),
                summary = s.summary,
                sysChannel = s.sysChannel,
                rule = rule,
                reason = reason,
            )
        )
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val prefs = Prefs(this)
        val isTg = sbn.packageName in TELEGRAM_PACKAGES
        if (!isTg && !prefs.logAll) return

        val snap = Snap.from(sbn)

        if (!isTg) {
            log("OTHER_APP", snap, "", "Не Telegram, не обрабатывается")
            return
        }

        prefs.lastTelegramAt = System.currentTimeMillis()
        Digest.flushIfDue(this)

        if (snap.summary) {
            log("SUMMARY", snap, "", "Общая сводка Telegram (группа уведомлений), на часы не копируется")
            scheduleSummaryCheck(snap)
            return
        }

        val channels = prefs.channels()
        val ch = if (prefs.enabled) channels.firstOrNull { it.enabled && Matcher.matches(it, snap) } else null

        if (ch != null && !ch.isAllowed()) {
            cancelNotification(sbn.key)
            lastBlockedAt = System.currentTimeMillis()
            prefs.bump(ch.id, "blocked")
            Forward.remove(this, sbn.key)
            log(
                "BLOCKED", snap, ch.name,
                "Вне времени показа (" + ch.scheduleText() + "), уведомление удалено, на часы не отправлено"
            )
            if (ch.digest) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val body = snap.latest.ifBlank { snap.title }.take(100)
                prefs.addPending(ch.id, "$time  $body")
                Digest.schedule(this)
            }
            return
        }

        // Уведомление остаётся: создаём копию для часов.
        val fw = Forward.copy(this, sbn, snap)
        if (!prefs.enabled) {
            log("OTHER", snap, "", "Фильтр выключен главным переключателем; $fw")
        } else if (ch == null) {
            val names = channels.filter { it.enabled }.joinToString(", ") { "«" + it.name + "»" }
            val what = if (names.isEmpty()) "нет включённых каналов" else "искали: $names"
            log("OTHER", snap, "", "Не совпало ни с одним каналом ($what); $fw")
        } else {
            prefs.bump(ch.id, "allowed")
            log(
                "ALLOWED", snap, ch.name,
                "Сейчас время показа (" + ch.scheduleText() + "), уведомление оставлено; $fw"
            )
        }
    }

    /**
     * После удаления уведомления канала у Telegram может остаться пустая общая сводка
     * («N сообщений»). Если после этого в группе не осталось ни одного обычного уведомления,
     * сводку тоже убираем.
     */
    private fun scheduleSummaryCheck(s: Snap) {
        handler.postDelayed({
            try {
                if (System.currentTimeMillis() - lastBlockedAt > 120_000L) return@postDelayed
                val active: Array<StatusBarNotification>? = activeNotifications
                if (active == null) return@postDelayed
                var children = 0
                for (a in active) {
                    val isSummary = (a.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                    if (a.packageName == s.pkg && a.groupKey == s.groupKey && !isSummary) children++
                }
                if (children == 0) {
                    cancelNotification(s.key)
                    Journal.add(
                        this,
                        Entry(
                            ts = System.currentTimeMillis(),
                            kind = "SUMMARY_REMOVED",
                            pkg = s.pkg,
                            reason = "Пустая общая сводка Telegram убрана"
                        )
                    )
                }
            } catch (e: Exception) {
                // не критично
            }
        }, 1500L)
    }

    companion object {
        val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.thunderdog.challegram",
        )

        @Volatile
        var connected: Boolean = false

        @Volatile
        var lastBlockedAt: Long = 0L

        fun rebind(ctx: Context) {
            try {
                NotificationListenerService.requestRebind(ComponentName(ctx, FilterService::class.java))
            } catch (e: Exception) {
                // система не дала переподключить: останется подсказка в статусе
            }
        }
    }
}
