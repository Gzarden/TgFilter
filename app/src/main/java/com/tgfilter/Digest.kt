package com.tgfilter

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Сводка пропущенного: пока канал скрыт, посты копятся; когда начинается время показа,
 * приходит одно уведомление «Канал: пропущено N постов».
 */
object Digest {
    private const val CH_ID = "digest"
    private const val NOTIF_BASE = 7000

    private fun notifAllowed(ctx: Context): Boolean = !Health.notifPermissionDenied(ctx)

    /** Показывает сводки по тем каналам, у которых время показа уже наступило. */
    fun flushIfDue(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val ids = prefs.pendingIds()
        if (ids.isEmpty()) return
        val channels = prefs.channels().associateBy { it.id }
        for (id in ids) {
            val ch = channels[id]
            if (ch == null || !ch.enabled || !ch.digest || !prefs.enabled) {
                prefs.takePending(id)
                continue
            }
            if (!ch.isAllowed()) continue
            if (!notifAllowed(app)) continue
            val p = prefs.takePending(id) ?: continue
            post(app, ch, p.first, p.second)
        }
    }

    /** Ставит будильник на ближайшее начало времени показа среди каналов со скрытыми постами. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            app, 1, Intent(app, DigestReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prefs = Prefs(app)
        val channels = prefs.channels().associateBy { it.id }
        var next = Long.MAX_VALUE
        for (id in prefs.pendingIds()) {
            val ch = channels[id] ?: continue
            if (!ch.enabled || !ch.digest) continue
            val t = ch.nextAllowedStart(Calendar.getInstance()) ?: continue
            if (t < next) next = t
        }
        if (next == Long.MAX_VALUE) {
            am.cancel(pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    private fun postsWord(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> "постов"
            m10 == 1 -> "пост"
            m10 in 2..4 -> "поста"
            else -> "постов"
        }
    }

    private fun launchIntent(ctx: Context): Intent {
        for (pkg in FilterService.TELEGRAM_PACKAGES) {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) return i
        }
        return Intent(ctx, MainActivity::class.java)
    }

    private fun post(ctx: Context, ch: Channel, count: Int, lines: List<String>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "Сводка пропущенного", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, launchIntent(ctx),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = ch.name + ": пропущено " + count + " " + postsWord(count)
        val body = lines.joinToString("\n")
        val n = Notification.Builder(ctx, CH_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines.lastOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_BASE + (ch.id.hashCode() and 0x3FF), n)
        Journal.add(
            ctx,
            Entry(
                ts = System.currentTimeMillis(),
                kind = "DIGEST",
                rule = ch.name,
                text = body,
                reason = "Показана сводка: пропущено $count"
            )
        )
    }
}

class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Digest.flushIfDue(context)
        Digest.schedule(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Journal.sys(context, "Телефон перезагружен")
        Digest.flushIfDue(context)
        Digest.schedule(context)
    }
}
