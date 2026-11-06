package com.tgfilter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var updated: TextView
    private lateinit var homePull: PullRefreshLayout
    private lateinit var homeBox: LinearLayout
    private lateinit var journalPull: PullRefreshLayout
    private lateinit var journalBox: LinearLayout
    private lateinit var tabHome: TextView
    private lateinit var tabJournal: TextView

    private var tab = 0
    private var filter = "ALL"
    private val expanded = HashSet<Long>()
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    private val filters = listOf(
        "ALL" to "Все",
        "BLOCKED" to "Скрытые",
        "ALLOWED" to "Показанные",
        "OTHERCHAT" to "Другие чаты",
        "SERVICE" to "Служебные",
    )

    private val autoRefresh = Runnable { refresh() }
    private val journalListener: () -> Unit = {
        handler.removeCallbacks(autoRefresh)
        handler.postDelayed(autoRefresh, 400L)
    }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.init(this)
        prefs = Prefs(this)

        window.statusBarColor = Pal.bg
        window.navigationBarColor = Pal.card
        if (!Pal.dark) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        setContentView(buildUi())
        showTab(0)

        if (Health.notifPermissionDenied(this) && !prefs.askedNotif) {
            prefs.askedNotif = true
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Journal.addListener(journalListener)
    }

    override fun onStop() {
        Journal.removeListener(journalListener)
        handler.removeCallbacks(autoRefresh)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (Health.listenerEnabled(this) && !FilterService.connected) FilterService.rebind(this)
        Digest.schedule(this)
        refresh()
        handler.postDelayed({ refresh() }, 1500L)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ------------------------------------------------------------------ каркас

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.bg)

        val header = vbox(20, 12)
        header.addView(label("TgFilter", 28f, Pal.text, true))
        updated = label("", 12f, Pal.sub)
        header.addView(updated)
        root.addView(header)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        homeBox = vbox(16, 4)
        homePull = pullOver(homeBox)
        journalBox = vbox(16, 4)
        journalPull = pullOver(journalBox)
        content.addView(homePull, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(journalPull, FrameLayout.LayoutParams(MATCH, MATCH))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Pal.card)
        tabHome = tabView("Главная") { showTab(0) }
        tabJournal = tabView("Журнал") { showTab(1) }
        nav.addView(tabHome, LinearLayout.LayoutParams(0, dp(56), 1f))
        nav.addView(tabJournal, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(nav)
        return root
    }

    private fun pullOver(box: LinearLayout): PullRefreshLayout {
        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        box.setPadding(dp(16), dp(4), dp(16), dp(28))
        sv.addView(box)
        val p = PullRefreshLayout(this) { refresh() }
        p.setContent(sv)
        return p
    }

    private fun tabView(title: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = title
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        t.setOnClickListener { onClick() }
        return t
    }

    private fun showTab(i: Int) {
        tab = i
        homePull.visibility = if (i == 0) View.VISIBLE else View.GONE
        journalPull.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabHome.setTextColor(if (i == 0) Pal.accent else Pal.sub)
        tabHome.typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tabJournal.setTextColor(if (i == 1) Pal.accent else Pal.sub)
        tabJournal.typeface = if (i == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        refresh()
    }

    private fun refresh() {
        if (!::homeBox.isInitialized) return
        try {
            Digest.flushIfDue(this)
        } catch (e: Exception) {
            // не критично
        }
        if (tab == 0) renderHome() else renderJournal()
        updated.text = "Обновлено в " + timeFmt.format(Date()) + " · потяните вниз, чтобы обновить"
    }

    private fun fmtTs(ts: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) timeFmt.format(Date(ts)) else dateFmt.format(Date(ts))
    }

    // ------------------------------------------------------------------ главная

    private fun dot(color: Int): View {
        val v = View(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        v.background = g
        val lp = LinearLayout.LayoutParams(dp(12), dp(12))
        lp.rightMargin = dp(10)
        v.layoutParams = lp
        return v
    }

    private fun renderHome() {
        val st = Health.check(this)
        val channels = prefs.channels()
        val entries = Journal.read(this)
        homeBox.removeAllViews()

        // --- статус
        val color = when (st.level) {
            Level.OK -> Pal.good
            Level.WARN -> Pal.warn
            Level.BAD -> Pal.bad
        }
        val sc = card()
        val head = hbox()
        head.addView(dot(color))
        head.addView(label(st.title, 20f, Pal.text, true))
        sc.addView(head)
        for (issue in st.issues) {
            sc.put(label(issue.text, 14f, Pal.text), 10)
            val a = issue.action
            if (a != null) sc.put(pill(issue.button, false) { doAction(a) }, 8, WRAP)
        }
        for (info in st.infos) sc.put(label(info, 13f, Pal.sub), 8)
        homeBox.put(sc, 8)

        // --- главный выключатель
        val mc = card()
        val mr = hbox()
        mr.addView(label("Фильтр включён", 16f, Pal.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val mainSwitch = Switch(this)
        mainSwitch.isChecked = prefs.enabled
        mainSwitch.setOnCheckedChangeListener { _, on ->
            prefs.enabled = on
            handler.post { refresh() }
        }
        mr.addView(mainSwitch)
        mc.addView(mr)
        homeBox.put(mc, 12)
        homeBox.put(watchCard(), 12)

        // --- каналы
        homeBox.put(label("Каналы", 18f, Pal.text, true), 22)
        if (channels.isEmpty()) {
            homeBox.put(
                label("Каналов пока нет. Добавьте канал кнопкой ниже или выберите его из списка недавних чатов.", 13f, Pal.sub),
                6
            )
        }
        for (ch in channels) homeBox.put(channelCard(ch), 10)
        homeBox.put(pill("+ Добавить канал", true) { showEditor(null) }, 12)

        // --- недавние чаты
        homeBox.put(label("Недавние чаты Telegram", 18f, Pal.text, true), 24)
        val recent = recentChats(entries, channels)
        if (recent.isEmpty()) {
            homeBox.put(
                label(
                    "Пока пусто: уведомления от Telegram на телефон ещё не приходили. " +
                        "Если вашего канала нет и дальше, откройте вкладку «Журнал»: там видно всё, что дошло до телефона.",
                    13f, Pal.sub
                ),
                6
            )
        } else {
            homeBox.put(label("Нажмите на чат, чтобы создать или изменить правило.", 13f, Pal.sub), 4)
            for (item in recent) {
                val title = item.first
                val rule = item.second
                val rc = card()
                val rr = hbox()
                rr.addView(label(title, 15f, Pal.text), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (rule == null) {
                    rr.addView(label("Добавить", 13f, Pal.accent, true))
                } else {
                    rr.addView(label("Правило есть", 13f, Pal.good, true))
                }
                rc.addView(rr)
                rc.setOnClickListener {
                    if (rule == null) showEditor(null, title) else showEditor(rule)
                }
                homeBox.put(rc, 8)
            }
        }
    }

    private fun watchCard(): View {
        val c = card()
        val r = hbox()
        val tx = vbox()
        tx.addView(label("Пересылка на часы", 16f, Pal.text, true))
        tx.addView(label("TgFilter сам отправляет на часы копии уведомлений Telegram и не отправляет скрытые.", 12f, Pal.sub))
        r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = prefs.forward
        sw.setOnCheckedChangeListener { _, on -> prefs.forward = on }
        r.addView(sw)
        c.addView(r)
        c.put(
            label(
                "Как настроить:\n" +
                    "1. В Huawei Health включите уведомления для TgFilter.\n" +
                    "2. Нажмите «Проверка на часах»: на часах должно появиться тестовое уведомление.\n" +
                    "3. Только после этого отключите там же уведомления для Telegram.\n\n" +
                    "На телефоне копии беззвучные. Они исчезают, когда вы прочитали сообщение в Telegram.",
                13f, Pal.sub
            ),
            12
        )
        c.put(label("Вид копий на телефоне", 13f, Pal.text, true), 16)
        c.put(chipRow(listOf("Обычные", "Тихие", "Свёрнутые"), prefs.copyMode) { prefs.copyMode = it }, 6)
        c.put(
            label(
                "«Свёрнутые» почти не видны на телефоне. После смены нажмите «Проверка на часах». " +
                    "Если тест не пришёл, выберите «Тихие» или «Обычные».",
                12f, Pal.sub
            ),
            4
        )
        c.put(label("Убирать копию с телефона через", 13f, Pal.text, true), 16)
        val ttlValues = listOf(0, 15, 60, 300)
        val ttlIndex = ttlValues.indexOf(prefs.copyTtlSec).coerceAtLeast(0)
        c.put(chipRow(listOf("Не убирать", "15 с", "1 мин", "5 мин"), ttlIndex) { prefs.copyTtlSec = ttlValues[it] }, 6)
        c.put(
            label(
                "Это убирает дубль на телефоне. Если вместе с ним пропадает и уведомление на часах, верните «Не убирать».",
                12f, Pal.sub
            ),
            4
        )

        val b = hbox()
        val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp.rightMargin = dp(8)
        b.addView(pill("Проверка на часах", true) { sendTest() }, lp)
        b.addView(pill("Настройки копий", false) { openCopySettings() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.put(b, 12)
        return c
    }

    /** Ряд кнопок-вариантов: выбранная подсвечена. */
    private fun chipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = hbox()
        val chips = ArrayList<TextView>()
        var cur = selected
        fun style() {
            for (i in chips.indices) {
                val on = i == cur
                chips[i].setTextColor(if (on) Pal.onAccent else Pal.text)
                chips[i].background = rounded(if (on) Pal.accent else Pal.chip, 12)
            }
        }
        for (i in options.indices) {
            val t = TextView(this)
            t.text = options[i]
            t.gravity = Gravity.CENTER
            t.textSize = 13f
            t.setPadding(dp(4), dp(10), dp(4), dp(10))
            t.setOnClickListener {
                cur = i
                style()
                onSelect(i)
            }
            chips.add(t)
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            if (i < options.size - 1) lp.rightMargin = dp(6)
            row.addView(t, lp)
        }
        style()
        return row
    }

    private fun sendTest() {
        val ok = Forward.test(this)
        val msg = if (ok) "Отправлено. Смотрите на часах." else "Нет разрешения на уведомления"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun openCopySettings() {
        Forward.ensureChannel(this)
        val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        i.putExtra(Settings.EXTRA_CHANNEL_ID, Forward.channelId(this))
        startActivity(i)
    }

    /** Недавние чаты Telegram; у тех, для кого уже есть правило, оно указано во втором поле. */
    private fun recentChats(entries: List<Entry>, channels: List<Channel>): List<Pair<String, Channel?>> {
        val seen = LinkedHashMap<String, Channel?>()
        for (e in entries) {
            if (e.pkg !in FilterService.TELEGRAM_PACKAGES || e.summary) continue
            if (e.kind != "BLOCKED" && e.kind != "ALLOWED" && e.kind != "OTHER") continue
            val t = e.title.trim()
            if (t.isEmpty() || t.equals("Telegram", ignoreCase = true)) continue
            if (seen.containsKey(t)) continue
            val n = Matcher.norm(t)
            val rule = channels.firstOrNull {
                val cn = Matcher.norm(it.name)
                cn.isNotEmpty() && n.contains(cn)
            }
            seen[t] = rule
            if (seen.size >= 10) break
        }
        return seen.entries.map { Pair(it.key, it.value) }
    }

    private fun channelCard(ch: Channel): View {
        val c = card()
        val top = hbox()
        top.addView(label(ch.name, 17f, Pal.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = ch.enabled
        sw.setOnCheckedChangeListener { _, on ->
            ch.enabled = on
            prefs.upsert(ch)
            Digest.schedule(this)
            handler.post { refresh() }
        }
        top.addView(sw)
        c.addView(top)

        c.put(label(ch.scheduleText(), 14f, Pal.accent), 6)
        val state = when {
            !ch.enabled -> "Правило выключено"
            ch.isAllowed() -> "Сейчас уведомления показываются"
            else -> "Сейчас уведомления скрываются"
        }
        c.put(label(state, 13f, Pal.sub), 4)

        val pend = prefs.pendingCount(ch.id)
        var stats = "Скрыто: " + prefs.stat(ch.id, "blocked") + " · Показано: " + prefs.stat(ch.id, "allowed")
        if (pend > 0) stats += " · Ждут в сводке: $pend"
        c.put(label(stats, 13f, Pal.sub), 4)

        val last = prefs.lastSeen(ch.id)
        if (last > 0L) c.put(label("Последнее уведомление: " + fmtTs(last), 13f, Pal.sub), 2)

        c.setOnClickListener { showEditor(ch) }
        return c
    }

    private fun doAction(a: Act) {
        when (a) {
            Act.LISTENER_SETTINGS -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Act.REBIND -> {
                FilterService.rebind(this)
                handler.postDelayed({ refresh() }, 1500L)
                Toast.makeText(this, "Запрошен перезапуск службы", Toast.LENGTH_SHORT).show()
            }
            Act.BATTERY -> showBatteryHelp()
            Act.NOTIF_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            }
            Act.ADD_CHANNEL -> showEditor(null)
        }
    }

    private fun showBatteryHelp() {
        AlertDialog.Builder(this)
            .setTitle("Чтобы Xiaomi не выгружал приложение")
            .setMessage(
                "1. Настройки → Приложения → TgFilter → Автозапуск: включить.\n\n" +
                    "2. Там же: Экономия заряда → «Нет ограничений».\n\n" +
                    "3. В списке недавних приложений зажмите карточку TgFilter и нажмите замок.\n\n" +
                    "Названия пунктов могут немного отличаться в зависимости от версии MIUI или HyperOS."
            )
            .setPositiveButton("Открыть настройки приложения") { _, _ ->
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = Uri.parse("package:$packageName")
                startActivity(i)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ------------------------------------------------------------------ редактор канала

    private fun showEditor(existing: Channel?, prefill: String = "") {
        val ch = existing?.copy() ?: Channel(Prefs.newId(), prefill)
        val sv = ScrollView(this)
        val box = vbox(20, 8)
        sv.addView(box)

        box.put(label("Канал", 13f, Pal.sub), 4)
        val nameEdit = EditText(this)
        nameEdit.setText(ch.name)
        nameEdit.hint = "Название канала или его часть"
        nameEdit.setSingleLine()
        nameEdit.inputType = InputType.TYPE_CLASS_TEXT
        box.put(nameEdit, 0)
        box.put(
            label("Совпадение ищется в заголовке уведомления. Регистр, смайлики и знаки не важны.", 12f, Pal.sub),
            2
        )

        box.put(label("Показывать уведомления в интервале", 13f, Pal.sub), 18)
        val fromBtn = TextView(this)
        val toBtn = TextView(this)
        fun updTimes() {
            fromBtn.text = "С " + Channel.fmt(ch.fromMin)
            toBtn.text = "До " + Channel.fmt(ch.toMin)
        }
        for (b in listOf(fromBtn, toBtn)) {
            b.textSize = 16f
            b.gravity = Gravity.CENTER
            b.setTextColor(Pal.text)
            b.typeface = Typeface.DEFAULT_BOLD
            b.setPadding(dp(8), dp(12), dp(8), dp(12))
            b.background = rounded(Pal.chip, 12)
        }
        fromBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                ch.fromMin = h * 60 + m
                updTimes()
            }, ch.fromMin / 60, ch.fromMin % 60, true).show()
        }
        toBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                ch.toMin = h * 60 + m
                updTimes()
            }, ch.toMin / 60, ch.toMin % 60, true).show()
        }
        updTimes()
        val timeRow = hbox()
        val lpFrom = LinearLayout.LayoutParams(0, WRAP, 1f)
        lpFrom.rightMargin = dp(8)
        timeRow.addView(fromBtn, lpFrom)
        timeRow.addView(toBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.put(timeRow, 6)
        box.put(label("Если «С» и «До» одинаковы, интервал занимает весь день.", 12f, Pal.sub), 4)

        box.put(label("Дни, когда уведомления показываются", 13f, Pal.sub), 18)
        val chips = ArrayList<TextView>()
        fun styleChips() {
            for (i in chips.indices) {
                val on = (ch.days and (1 shl i)) != 0
                chips[i].setTextColor(if (on) Pal.onAccent else Pal.text)
                chips[i].background = rounded(if (on) Pal.accent else Pal.chip, 12)
            }
        }
        val daysRow = hbox()
        for (i in 0 until 7) {
            val t = TextView(this)
            t.text = Channel.DAY_NAMES[i]
            t.gravity = Gravity.CENTER
            t.textSize = 13f
            t.setPadding(0, dp(10), 0, dp(10))
            t.setOnClickListener {
                ch.days = ch.days xor (1 shl i)
                styleChips()
            }
            chips.add(t)
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            if (i < 6) lp.rightMargin = dp(4)
            daysRow.addView(t, lp)
        }
        styleChips()
        box.put(daysRow, 6)
        box.put(label("В остальные дни и часы уведомления этого канала скрываются.", 12f, Pal.sub), 4)

        val digestRow = hbox()
        val digestText = vbox()
        digestText.addView(label("Сводка пропущенного", 15f, Pal.text, true))
        digestText.addView(label("Когда начнётся время показа, придёт одно уведомление о скрытых постах.", 12f, Pal.sub))
        digestRow.addView(digestText, LinearLayout.LayoutParams(0, WRAP, 1f))
        val digestSwitch = Switch(this)
        digestSwitch.isChecked = ch.digest
        digestSwitch.setOnCheckedChangeListener { _, on -> ch.digest = on }
        digestRow.addView(digestSwitch)
        box.put(digestRow, 20)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новый канал" else "Канал")
            .setView(sv)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
        if (existing != null) builder.setNeutralButton("Удалить", null)
        val dlg = builder.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Введите название канала", Toast.LENGTH_SHORT).show()
                } else {
                    ch.name = name
                    prefs.upsert(ch)
                    Digest.schedule(this)
                    dlg.dismiss()
                    refresh()
                }
            }
            if (existing != null) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Удалить правило?")
                        .setMessage("Правило для «" + existing.name + "» будет удалено.")
                        .setPositiveButton("Удалить") { _, _ ->
                            prefs.delete(existing.id)
                            Digest.schedule(this)
                            dlg.dismiss()
                            refresh()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ журнал

    private fun passes(e: Entry): Boolean = when (filter) {
        "ALL" -> true
        "BLOCKED" -> e.kind == "BLOCKED"
        "ALLOWED" -> e.kind == "ALLOWED"
        "OTHERCHAT" -> e.kind == "OTHER" || e.kind == "SUMMARY"
        else -> e.kind == "SYSTEM" || e.kind == "DIGEST" || e.kind == "OTHER_APP" || e.kind == "SUMMARY_REMOVED"
    }

    private fun kindLabel(k: String): String = when (k) {
        "BLOCKED" -> "Скрыто"
        "ALLOWED" -> "Показано"
        "OTHER" -> "Другой чат"
        "SUMMARY" -> "Сводка Telegram"
        "SUMMARY_REMOVED" -> "Сводка убрана"
        "SYSTEM" -> "Служба"
        "DIGEST" -> "Сводка пропущенного"
        "OTHER_APP" -> "Другое приложение"
        else -> k
    }

    private fun kindColor(k: String): Int = when (k) {
        "BLOCKED" -> Pal.accent
        "ALLOWED" -> Pal.good
        "SUMMARY_REMOVED" -> Pal.accent
        "DIGEST" -> Pal.accent
        "SYSTEM" -> Pal.warn
        else -> Pal.sub
    }

    private fun renderJournal() {
        val all = Journal.read(this)
        journalBox.removeAllViews()

        val head = card()
        head.addView(label("Журнал", 20f, Pal.text, true))
        head.put(
            label("Всё, что приложение видит и решает. Хранится только на этом телефоне, последние ~500 записей.", 13f, Pal.sub),
            4
        )

        val logRow = hbox()
        logRow.addView(
            label("Записывать уведомления всех приложений", 14f, Pal.text),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        val logSwitch = Switch(this)
        logSwitch.isChecked = prefs.logAll
        logSwitch.setOnCheckedChangeListener { _, on -> prefs.logAll = on }
        logRow.addView(logSwitch)
        head.put(logRow, 14)
        head.put(label("Нужно для диагностики: покажет, доходит ли уведомление до телефона вообще.", 12f, Pal.sub), 2)

        val btns = hbox()
        val lp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp1.rightMargin = dp(8)
        val lp2 = LinearLayout.LayoutParams(0, WRAP, 1f)
        lp2.rightMargin = dp(8)
        btns.addView(pill("Скопировать", true) { copyJournal() }, lp1)
        btns.addView(pill("Поделиться", false) { shareJournal() }, lp2)
        btns.addView(pill("Очистить", false) { confirmClear() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.put(btns, 14)
        journalBox.put(head, 8)

        // фильтры
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val chipRow = hbox()
        for (f in filters) {
            val on = filter == f.first
            val t = TextView(this)
            t.text = f.second
            t.textSize = 13f
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.setTextColor(if (on) Pal.onAccent else Pal.text)
            t.background = rounded(if (on) Pal.accent else Pal.chip, 16)
            t.setOnClickListener {
                filter = f.first
                renderJournal()
            }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(8)
            chipRow.addView(t, lp)
        }
        hs.addView(chipRow)
        journalBox.put(hs, 12)

        val nb = all.count { it.kind == "BLOCKED" }
        val na = all.count { it.kind == "ALLOWED" }
        journalBox.put(label("Записей: ${all.size} · скрыто: $nb · показано: $na", 13f, Pal.sub), 10)

        val filtered = all.filter { passes(it) }
        if (filtered.isEmpty()) {
            journalBox.put(
                label(
                    if (all.isEmpty()) "Пока нет записей. Они появятся, когда придёт уведомление Telegram."
                    else "В этом фильтре записей нет.",
                    14f, Pal.sub
                ),
                16
            )
        }
        for (e in filtered.take(150)) journalBox.put(entryRow(e), 8)
        if (filtered.size > 150) {
            journalBox.put(label("Показаны последние 150 из ${filtered.size}. Полный журнал есть в «Скопировать».", 12f, Pal.sub), 10)
        }
    }

    private fun entryRow(e: Entry): View {
        val open = expanded.contains(e.ts)
        val c = card()
        c.setPadding(dp(14), dp(12), dp(14), dp(12))

        val top = hbox()
        val color = kindColor(e.kind)
        val badge = TextView(this)
        badge.text = kindLabel(e.kind)
        badge.textSize = 12f
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.setTextColor(color)
        badge.setPadding(dp(10), dp(3), dp(10), dp(3))
        badge.background = rounded((color and 0x00FFFFFF) or 0x2B000000, 10)
        top.addView(badge)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(label(fmtTs(e.ts), 12f, Pal.sub))
        c.addView(top)

        val main = if (e.title.isNotBlank()) e.title else e.reason
        c.put(label(main, 15f, Pal.text, true), 6)
        if (e.title.isNotBlank()) {
            if (e.text.isNotBlank()) {
                val tv = label(e.text, 14f, Pal.text)
                if (!open) {
                    tv.maxLines = 2
                    tv.ellipsize = android.text.TextUtils.TruncateAt.END
                }
                c.put(tv, 2)
            }
            if (e.reason.isNotBlank()) c.put(label(e.reason, 12f, Pal.sub), 4)
        } else if (e.text.isNotBlank()) {
            c.put(label(e.text, 13f, Pal.sub), 2)
        }

        if (open) {
            val sb = StringBuilder()
            if (e.pkg.isNotEmpty()) sb.append("пакет: ").append(e.pkg).append('\n')
            sb.append("заголовок: ").append(e.title).append('\n')
            sb.append("текст: ").append(e.text).append('\n')
            sb.append("подзаголовок: ").append(e.sub).append('\n')
            sb.append("беседа: ").append(e.conv).append('\n')
            if (e.extra.isNotEmpty()) sb.append("доп.: ").append(e.extra).append('\n')
            sb.append("сводная группа: ").append(if (e.summary) "да" else "нет").append('\n')
            sb.append("канал Android: ").append(e.sysChannel).append('\n')
            sb.append("правило: ").append(e.rule)
            val raw = label(sb.toString(), 11f, Pal.sub)
            raw.typeface = Typeface.MONOSPACE
            raw.setTextIsSelectable(true)
            c.put(raw, 8)
        }

        c.setOnClickListener {
            if (open) expanded.remove(e.ts) else expanded.add(e.ts)
            renderJournal()
        }
        return c
    }

    private fun copyJournal() {
        val text = Export.text(this)
        val cm = getSystemService(ClipboardManager::class.java)
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Журнал TgFilter", text))
            Toast.makeText(this, "Журнал скопирован. Вставьте его в сообщение.", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareJournal() {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, Export.text(this, 200))
        startActivity(Intent.createChooser(i, "Поделиться журналом"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Очистить журнал?")
            .setMessage("Все записи будут удалены. Счётчики и правила останутся.")
            .setPositiveButton("Очистить") { _, _ ->
                Journal.clear(this)
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
