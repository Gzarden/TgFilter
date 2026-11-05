package com.tgfilter

import android.app.Activity
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var accessStatus: TextView
    private lateinit var startBtn: Button
    private lateinit var endBtn: Button
    private lateinit var counter: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var keywordEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, sizeSp: Float = 15f, bold: Boolean = false, topPad: Int = 12): TextView =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(topPad), 0, dp(4))
        }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        root.addView(tv("TgFilter", 26f, bold = true, topPad = 0))
        root.addView(tv("Скрывает уведомления одного канала Telegram в нужное время.", 14f, topPad = 0))

        accessStatus = tv("", 15f, bold = true, topPad = 16)
        root.addView(accessStatus)
        root.addView(Button(this).apply {
            text = "Открыть доступ к уведомлениям"
            setAllCaps(false)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })

        root.addView(Switch(this).apply {
            text = "Фильтр включён"
            isChecked = prefs.enabled
            setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, on -> prefs.enabled = on }
        })

        root.addView(tv("Название канала (можно часть названия)", 15f, bold = true))
        keywordEdit = EditText(this).apply {
            setText(prefs.keyword)
            hint = "например: Новости"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.keyword = s?.toString().orEmpty()
                }
            })
        }
        root.addView(keywordEdit)

        root.addView(tv("Показывать уведомления только в интервале", 15f, bold = true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply { setOnClickListener { pickTime(true) } }
        endBtn = Button(this).apply { setOnClickListener { pickTime(false) } }
        row.addView(startBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(endBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        root.addView(Switch(this).apply {
            text = "Не показывать в выходные (сб и вс)"
            isChecked = prefs.blockWeekends
            setPadding(0, dp(12), 0, dp(8))
            setOnCheckedChangeListener { _, on -> prefs.blockWeekends = on }
        })

        counter = tv("", 15f, topPad = 12)
        root.addView(counter)

        root.addView(tv("Недавние уведомления Telegram (нажми, чтобы выбрать канал)", 15f, bold = true, topPad = 20))
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentBox)

        root.addView(tv(
            "Xiaomi: Настройки → Приложения → TgFilter → включи «Автозапуск», " +
                "в «Экономии заряда» выбери «Нет ограничений», а в списке недавних " +
                "приложений закрепи TgFilter (замок). Иначе система может его выгружать.",
            13f, topPad = 20
        ))

        return ScrollView(this).apply { addView(root) }
    }

    private fun pickTime(isStart: Boolean) {
        val cur = if (isStart) prefs.startMin else prefs.endMin
        TimePickerDialog(this, { _, h, m ->
            if (isStart) prefs.startMin = h * 60 + m else prefs.endMin = h * 60 + m
            refresh()
        }, cur / 60, cur % 60, true).show()
    }

    private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun listenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }

    private fun refresh() {
        accessStatus.text = if (listenerEnabled()) {
            "Доступ к уведомлениям: включён"
        } else {
            "Доступ к уведомлениям: ВЫКЛЮЧЕН. Нажми кнопку ниже и включи TgFilter."
        }
        startBtn.text = "С " + fmt(prefs.startMin)
        endBtn.text = "До " + fmt(prefs.endMin)
        counter.text = "Скрыто уведомлений: ${prefs.blockedCount}"

        recentBox.removeAllViews()
        val recent = prefs.recentTitles()
        if (recent.isEmpty()) {
            recentBox.addView(tv("Пока пусто. Дождись уведомления от Telegram, и название появится здесь.", 14f, topPad = 0))
        } else {
            recent.forEach { title ->
                recentBox.addView(Button(this).apply {
                    text = title
                    setAllCaps(false)
                    setOnClickListener { keywordEdit.setText(title) }
                })
            }
        }
    }
}
