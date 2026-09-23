package dev.dquest.aichanger

import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionService
import dev.dquest.aichanger.daemon.DaemonProtocol

/** Ассистент, которого можно вызывать кнопкой: [target] в формате [DaemonProtocol.ASSISTANT]. */
data class Assistant(val label: String, val target: String) {
    /** Вызов через роль системного ассистента: при выборе меняется системный ассистент телефона. */
    val changesSystemAssistant get() = !DaemonProtocol.isDirect(target) && target != DaemonProtocol.GOOGLE_APP
}

object Assistants {

    val GEMINI = Assistant("Gemini", DaemonProtocol.GOOGLE_APP)

    /**
     * Прямой запуск режима ассистента у них ничего не показывает: работают только как системный ассистент.
     * Проверено на ChatGPT: его AssistantProxyActivity сразу закрывается.
     */
    private val ROLE_ONLY = setOf("com.openai.chatgpt")

    /**
     * Ассистенты, которые открываются поверх экрана: признак — своя служба голосового ассистента
     * (VoiceInteractionService). Приложения без неё, например DeepSeek, открылись бы целиком, их не берём.
     * BlueLM (пакеты vivo) не показываем: его и заменяем, а вернуть его можно выключением перехвата.
     */
    fun find(context: Context): List<Assistant> {
        val pm = context.packageManager
        val packages = pm.queryIntentServices(Intent(VoiceInteractionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
            .filter { it != context.packageName && !it.startsWith(VIVO_PREFIX) }
        val others = packages.filter { it != DaemonProtocol.GOOGLE_APP }.map { packageName ->
            val label = pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
            val assistActivity = pm.queryIntentActivities(Intent(Intent.ACTION_ASSIST).setPackage(packageName), 0)
                .firstOrNull { it.activityInfo.exported }?.activityInfo
            val target = if (assistActivity == null || packageName in ROLE_ONLY) packageName
            else "$packageName/${assistActivity.name}"
            Assistant(label, target)
        }.filter { DaemonProtocol.isValidTarget(it.target) }.sortedBy { it.label.lowercase() }
        return listOfNotNull(GEMINI.takeIf { DaemonProtocol.GOOGLE_APP in packages }) + others
    }

    private const val VIVO_PREFIX = "com.vivo."
}
