package com.tgfilter

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Видит все уведомления на телефоне. Если уведомление пришло из Telegram,
 * относится к нужному каналу и сейчас «запретное» время, оно сразу удаляется.
 */
class FilterService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in TELEGRAM_PACKAGES) return

        val prefs = Prefs(this)
        val extras = sbn.notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        // Запоминаем название, чтобы его можно было выбрать в настройках
        prefs.remember(title.ifBlank { conversation })

        if (!prefs.enabled) return
        val keyword = prefs.keyword
        if (keyword.isEmpty()) return

        val matches = listOf(title, conversation, subText)
            .any { it.contains(keyword, ignoreCase = true) }
        if (!matches) return

        if (prefs.isAllowedNow()) return

        cancelNotification(sbn.key)
        prefs.blockedCount = prefs.blockedCount + 1
    }

    // Если система отвязала сервис (бывает на Xiaomi), просим привязать снова
    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, FilterService::class.java))
    }

    companion object {
        val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.thunderdog.challegram",
        )
    }
}
