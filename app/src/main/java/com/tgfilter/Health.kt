package com.tgfilter

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

enum class Level { OK, WARN, BAD }

enum class Act { LISTENER_SETTINGS, REBIND, BATTERY, NOTIF_PERMISSION, ADD_CHANNEL }

class Issue(
    val level: Level,
    val text: String,
    val action: Act? = null,
    val button: String = "",
)

class Status(
    val level: Level,
    val title: String,
    val issues: List<Issue>,
    val infos: List<String>,
)

/** Проверка: работает ли приложение и что мешает. */
object Health {

    fun listenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == ctx.packageName
        }
    }

    fun notifPermissionDenied(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun ago(ms: Long): String {
        val min = ms / 60000L
        return when {
            min < 1 -> "только что"
            min < 60 -> "$min мин назад"
            min < 60 * 24 -> "${min / 60} ч назад"
            else -> "${min / (60 * 24)} дн назад"
        }
    }

    fun check(ctx: Context): Status {
        val prefs = Prefs(ctx)
        val issues = ArrayList<Issue>()
        val infos = ArrayList<String>()

        if (!listenerEnabled(ctx)) {
            issues.add(
                Issue(Level.BAD, "Нет доступа к уведомлениям: приложение не видит Telegram.", Act.LISTENER_SETTINGS, "Открыть настройки доступа")
            )
        } else if (!FilterService.connected) {
            issues.add(
                Issue(Level.BAD, "Служба фильтра сейчас не запущена. Система могла её выгрузить.", Act.REBIND, "Перезапустить службу")
            )
        }

        val channels = prefs.channels()
        if (channels.none { it.enabled }) {
            issues.add(Issue(Level.WARN, "Нет ни одного включённого канала.", Act.ADD_CHANNEL, "Добавить канал"))
        }
        if (!prefs.enabled) {
            issues.add(Issue(Level.WARN, "Фильтр выключен главным переключателем: уведомления не скрываются."))
        }

        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            issues.add(
                Issue(Level.WARN, "Включена экономия заряда: Xiaomi может выгружать приложение, и фильтр перестанет работать.", Act.BATTERY, "Как настроить")
            )
        }
        if (notifPermissionDenied(ctx)) {
            issues.add(
                Issue(Level.WARN, "Нет разрешения показывать уведомления: сводка пропущенного не появится.", Act.NOTIF_PERMISSION, "Разрешить")
            )
        }

        val last = prefs.lastTelegramAt
        if (last == 0L) {
            issues.add(
                Issue(
                    Level.WARN,
                    "От Telegram пока не пришло ни одного уведомления. Если в чатах писали, значит Telegram не шлёт их на телефон " +
                        "(например, пока он открыт на компьютере) или доступ не работает. Подробности смотрите в журнале."
                )
            )
        } else {
            infos.add("Последнее уведомление Telegram: " + ago(System.currentTimeMillis() - last))
        }

        val worst = issues.maxByOrNull { it.level.ordinal }?.level ?: Level.OK
        val title = when (worst) {
            Level.OK -> "Всё работает"
            Level.WARN -> "Есть замечания"
            Level.BAD -> "Не работает"
        }
        return Status(worst, title, issues, infos)
    }
}
