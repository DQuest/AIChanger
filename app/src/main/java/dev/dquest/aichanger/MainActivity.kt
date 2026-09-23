package dev.dquest.aichanger

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var pairButton: Button
    private lateinit var toggleButton: Button
    private lateinit var autoStartButton: Button
    private lateinit var voiceButton: RadioButton
    private lateinit var windowButton: RadioButton
    private lateinit var assistantGroup: RadioGroup
    private lateinit var assistantNote: TextView
    private val onChange: () -> Unit = { runOnUiThread(::refresh) }
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            PowerKeyController.refreshAsync()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(20)
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        pairButton = Button(this).apply {
            text = "Сопряжение с беспроводной отладкой"
            setOnClickListener { startPairing() }
        }
        toggleButton = Button(this).apply {
            setOnClickListener {
                if (PowerKeyController.isEnabled) {
                    PowerKeyController.disable(this@MainActivity)
                } else {
                    PowerKeyController.setEnabled(true)
                    // Без сопряжения запускать нечем: сначала оно, а запуск произойдёт по его завершении.
                    if (PowerKeyController.isPaired) AutoStartService.start(this@MainActivity) else startPairing()
                }
            }
        }
        autoStartButton = Button(this).apply {
            text = "Разрешить автозапуск (vivo)"
            setOnClickListener { openAutoStartSettings() }
        }
        assistantGroup = RadioGroup(this)
        assistantNote = TextView(this)
        voiceButton = RadioButton(this).apply { text = "Сразу голосовой ввод" }
        windowButton = RadioButton(this).apply { text = "Окно Gemini без прослушивания" }
        val modeGroup = RadioGroup(this).apply {
            addView(voiceButton)
            addView(windowButton)
            // Слушатель вешаем после добавления кнопок: id у них появляются только тогда.
            setOnCheckedChangeListener { _, checkedId -> PowerKeyController.setVoiceMode(checkedId == voiceButton.id) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(title("Удержание кнопки питания → ИИ-ассистент"))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(pairButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(toggleButton, LinearLayout.LayoutParams(-1, -2))
            addView(autoStartButton, LinearLayout.LayoutParams(-1, -2))
            addView(title("Ассистент"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding * 2 })
            addView(assistantGroup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
            addView(assistantNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
            addView(title("Режим Gemini"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding * 2 })
            addView(modeGroup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
            addView(TextView(this@MainActivity).apply {
                text = "Меню питания: держите кнопку дольше, около 2 с.\n\n" +
                    "Для запуска, в том числе после перезагрузки, нужен Wi-Fi, для которого в беспроводной " +
                    "отладке разрешено «Всегда разрешать в этой сети». Чтобы перехват работал после перезагрузки, " +
                    "включите AIChanger в списке автозапуска vivo (кнопка выше)."
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding * 2 })
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
    }

    /** Список строится заново при каждом показе экрана: ассистентов могли установить или удалить. */
    private fun showAssistants() {
        assistantGroup.setOnCheckedChangeListener(null)
        assistantGroup.removeAllViews()
        for (assistant in Assistants.find(this)) {
            assistantGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = assistant.label
                tag = assistant
            })
        }
        assistantGroup.setOnCheckedChangeListener { group, checkedId ->
            val assistant = group.findViewById<RadioButton>(checkedId)?.tag as? Assistant ?: return@setOnCheckedChangeListener
            if (assistant.target != PowerKeyController.assistant) PowerKeyController.setAssistant(assistant.target)
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
    }

    private fun startPairing() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Код сопряжения вводится в уведомлении, без него ничего не выйдет.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            return
        }
        startForegroundService(Intent(this, PairingService::class.java))
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        )
    }

    /** Без разрешения на автозапуск vivo отбрасывает загрузочную рассылку, и демон не стартует после перезагрузки. */
    private fun openAutoStartSettings() {
        val vivo = Intent().setComponent(ComponentName(VIVO_PERMISSION_MANAGER, VIVO_AUTOSTART_ACTIVITY))
        try {
            startActivity(vivo)
        } catch (_: ActivityNotFoundException) {
            openAppDetails()
        } catch (_: SecurityException) {
            openAppDetails()
        }
    }

    private fun openAppDetails() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startPairing()
    }

    override fun onStart() {
        super.onStart()
        PowerKeyController.addListener(onChange)
        showAssistants()
        refresh()
        PowerKeyController.startIfNeeded(applicationContext)
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        PowerKeyController.removeListener(onChange)
        super.onStop()
    }

    private fun refresh() {
        val enabled = PowerKeyController.isEnabled
        val paired = PowerKeyController.isPaired
        val version = PowerKeyController.daemonVersion
        status.text = buildString {
            append("Сопряжение: ${if (paired) "выполнено" else "нет"}\n")
            append("Перехват кнопки: ")
            append(
                when {
                    version == BuildConfig.VERSION_CODE -> "работает"
                    PowerKeyController.launching -> "запускается…"
                    version != null -> "работает старая версия, обновляется"
                    !enabled -> "выключен"
                    !paired -> "ждёт сопряжения"
                    else -> "не запущен" + (PowerKeyController.lastLaunchError?.let { ": $it" } ?: "")
                }
            )
            PowerKeyController.daemonStatus?.let { append("\n\n$it") }
        }
        toggleButton.text = if (enabled) "Выключить (вернуть BlueLM)" else "Включить"
        val selected = if (PowerKeyController.isVoiceMode) voiceButton else windowButton
        if (!selected.isChecked) selected.isChecked = true
        val target = PowerKeyController.assistant
        val assistantButton = (0 until assistantGroup.childCount).map { assistantGroup.getChildAt(it) as RadioButton }
            .firstOrNull { (it.tag as Assistant).target == target }
        if (assistantButton != null && !assistantButton.isChecked) assistantButton.isChecked = true
        val assistant = assistantButton?.tag as? Assistant
        assistantNote.text = when {
            assistant == null -> "Выбранный ассистент не найден, выберите другого."
            assistant.changesSystemAssistant -> "${assistant.label} вызывается только как системный ассистент, " +
                "поэтому AIChanger сделает его ассистентом телефона. Прежний вернётся, если выбрать другого " +
                "или выключить перехват."
            else -> ""
        }
        assistantNote.visibility = if (assistantNote.text.isEmpty()) View.GONE else View.VISIBLE
        // Остальные ассистенты сами решают, слушать ли сразу; выбрать можно только у Gemini.
        val gemini = target == Assistants.GEMINI.target
        voiceButton.isEnabled = gemini
        windowButton.isEnabled = gemini
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val POLL_MS = 2000L
        const val VIVO_PERMISSION_MANAGER = "com.vivo.permissionmanager"
        const val VIVO_AUTOSTART_ACTIVITY = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
    }
}
