package dev.dquest.aichanger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var permissionButton: Button
    private lateinit var toggleButton: Button
    private lateinit var voiceButton: RadioButton
    private lateinit var windowButton: RadioButton
    private lateinit var autoStatus: TextView
    private lateinit var pairButton: Button
    private lateinit var autoToggleButton: Button
    private lateinit var startNowButton: Button
    private val onChange: () -> Unit = { runOnUiThread(::refresh) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(20)
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        permissionButton = Button(this).apply {
            text = "Выдать доступ Shizuku"
            setOnClickListener { PowerKeyController.requestPermission() }
        }
        toggleButton = Button(this).apply {
            setOnClickListener {
                if (PowerKeyController.isEnabled) PowerKeyController.disable() else PowerKeyController.enable()
            }
        }
        voiceButton = RadioButton(this).apply { text = "Сразу голосовой ввод" }
        windowButton = RadioButton(this).apply { text = "Окно Gemini без прослушивания" }
        val modeGroup = RadioGroup(this).apply {
            addView(voiceButton)
            addView(windowButton)
            // Слушатель вешаем после добавления кнопок: id у них появляются только тогда.
            setOnCheckedChangeListener { _, checkedId -> PowerKeyController.setVoiceMode(checkedId == voiceButton.id) }
        }
        autoStatus = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        pairButton = Button(this).apply {
            text = "Сопряжение с беспроводной отладкой"
            setOnClickListener { startPairing() }
        }
        autoToggleButton = Button(this).apply {
            setOnClickListener { PowerKeyController.setAutoStartEnabled(!PowerKeyController.isAutoStartEnabled) }
        }
        startNowButton = Button(this).apply {
            text = "Запустить Shizuku сейчас"
            setOnClickListener { AutoStartService.start(this@MainActivity) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(title("Удержание кнопки питания → Gemini"))
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(permissionButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(toggleButton, LinearLayout.LayoutParams(-1, -2))
            addView(title("Что открывать"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding * 2 })
            addView(modeGroup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding / 2 })
            addView(title("Автозапуск Shizuku"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding * 2 })
            addView(autoStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(pairButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
            addView(autoToggleButton, LinearLayout.LayoutParams(-1, -2))
            addView(startNowButton, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startPairing()
    }

    override fun onStart() {
        super.onStart()
        PowerKeyController.addListener(onChange)
        refresh()
    }

    override fun onStop() {
        PowerKeyController.removeListener(onChange)
        super.onStop()
    }

    private fun refresh() {
        val shizuku = PowerKeyController.isShizukuRunning()
        val permission = PowerKeyController.hasPermission()
        val service = PowerKeyController.service
        val running = service?.let { runCatching { it.isRunning }.getOrDefault(false) } ?: false
        status.text = buildString {
            append("Shizuku: ${if (shizuku) "запущен" else "не запущен — запустите его"}\n")
            append("Доступ Shizuku: ${if (permission) "есть" else "нет"}\n")
            append("Перехват кнопки: ${if (running) "работает" else "не работает"}")
            service?.let { runCatching { it.status() }.getOrNull() }?.let { append("\n\n$it") }
        }
        permissionButton.isEnabled = shizuku && !permission
        toggleButton.isEnabled = permission || PowerKeyController.isEnabled
        toggleButton.text = if (PowerKeyController.isEnabled) "Выключить (вернуть BlueLM)" else "Включить"
        val selected = if (PowerKeyController.isVoiceMode) voiceButton else windowButton
        if (!selected.isChecked) selected.isChecked = true

        val secureSettings = ShizukuStarter.canWriteSecureSettings(this)
        val paired = PowerKeyController.isPaired
        autoStatus.text = buildString {
            append("WRITE_SECURE_SETTINGS: ${if (secureSettings) "выдано" else "нет — выдайте по adb (см. README)"}\n")
            append("Сопряжение: ${if (paired) "выполнено" else "нет"}\n")
            append("Автозапуск после перезагрузки: ${if (PowerKeyController.isAutoStartEnabled) "включён" else "выключен"}\n")
            append("Нужен Wi-Fi, для которого разрешена беспроводная отладка («Всегда разрешать в этой сети»).")
        }
        autoToggleButton.isEnabled = secureSettings && paired
        autoToggleButton.text = if (PowerKeyController.isAutoStartEnabled) "Выключить автозапуск" else "Включить автозапуск"
        startNowButton.isEnabled = secureSettings && paired && !shizuku
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
