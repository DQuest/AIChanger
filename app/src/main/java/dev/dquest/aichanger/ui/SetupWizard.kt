package dev.dquest.aichanger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dquest.aichanger.SetupStep

/**
 * Первая настройка по шагам. Шаги 2 и 3 отмечаются сами (параметры разработчика включены, сопряжение
 * прошло). Настройки на шагах 1, 4 и 5 (BlueLM, автозапуск и работа в фоне) приложению не видны,
 * их подтверждает пользователь.
 */
@Composable
fun SetupWizard(state: UiState, actions: UiActions) {
    // Отметки пользователя хранятся в настройках (PowerKeyController), а не на экране: из настроек телефона
    // возвращаются и по уведомлению, и мастер должен продолжиться с того же шага. Индикатор ожидания кода — только экранный.
    val done = state.setupSteps
    var pairingStarted by rememberSaveable { mutableStateOf(false) }
    // BlueLM первым: сразу после сопряжения перехват уже запускается.
    val step = when {
        SetupStep.BLUELM_OFF !in done -> 1
        state.paired && SetupStep.AUTOSTART in done -> 5
        state.paired -> 4
        state.developerOptions || SetupStep.DEVELOPER_OPTIONS in done -> 3
        else -> 2
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(40.dp)
                Spacer(Modifier.width(12.dp))
                Text("AIChanger", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(28.dp))
            StepProgress(step)
            Spacer(Modifier.height(8.dp))
            Text(
                "Шаг $step из $STEPS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            when (step) {
                1 -> Step(
                    title = "Выключите BlueLM на кнопке питания",
                    body = "AIChanger не меняет настройки BlueLM. Если BlueLM тоже вызывается удержанием кнопки питания, " +
                        "откроются оба ассистента. Найдите в настройках телефона BlueLM (蓝心小V) и выключите его вызов " +
                        "кнопкой питания. Если потом выключите AIChanger, BlueLM можно будет включить там же.",
                    primary = "Готово, выключил" to { actions.completeSetupStep(SetupStep.BLUELM_OFF) },
                )
                2 -> Step(
                    title = "Включите параметры разработчика",
                    body = "Они нужны, чтобы AIChanger мог перехватывать кнопку питания. " +
                        "Откройте «О телефоне» и нажмите 7 раз на номер версии ПО, пока не появится сообщение, " +
                        "что вы стали разработчиком. Потом вернитесь сюда.",
                    primary = "Открыть «О телефоне»" to actions::openDeviceInfo,
                    secondary = "Уже включены" to { actions.completeSetupStep(SetupStep.DEVELOPER_OPTIONS) },
                )
                3 -> Step(
                    title = "Подключите беспроводную отладку",
                    body = null,
                    primary = "Открыть настройки отладки" to {
                        pairingStarted = true
                        actions.startPairing()
                    },
                ) {
                    Instructions(
                        "Включите «Беспроводная отладка».",
                        "Отметьте «Всегда разрешать в этой сети».",
                        "Нажмите «Сопряжение по коду».",
                        "Введите показанный код в уведомлении AIChanger.",
                    )
                    if (state.pairingLost) {
                        Hint(
                            "Прежнее сопряжение перестало действовать: телефон забывает его, если отладкой неделю " +
                                "не пользовались. Выполните его заново — теперь AIChanger отключает этот срок, и больше " +
                                "оно не истечёт."
                        )
                    }
                    Hint("Нужен Wi-Fi. Код вводится один раз, дальше AIChanger подключается сам.")
                    if (!state.notificationsEnabled) {
                        Hint(
                            "Уведомления AIChanger запрещены, а код вводится в уведомлении. " +
                                "Кнопка ниже попросит их разрешить или откроет настройки уведомлений."
                        )
                    } else if (pairingStarted) {
                        Waiting("Ждём код сопряжения из уведомления…")
                    }
                }
                4 -> Step(
                    title = "Разрешите автозапуск",
                    body = "Тогда кнопка будет работать и после перезагрузки телефона. " +
                        "Найдите AIChanger в списке и включите переключатель.",
                    primary = "Открыть автозапуск" to actions::openAutoStart,
                    secondary = "Готово" to { actions.completeSetupStep(SetupStep.AUTOSTART) },
                )
                else -> Step(
                    title = "Разрешите работу в фоне",
                    body = "Иначе vivo усыпит AIChanger, пока он ждёт Wi-Fi после перезагрузки, и кнопка не заработает. " +
                        "Нажмите на AIChanger в списке и выберите «Разрешить высокое энергопотребление».",
                    primary = "Открыть управление питанием" to actions::openBackgroundPower,
                    secondary = "Готово" to actions::finishSetup,
                )
            }
        }
    }
}

@Composable
private fun StepProgress(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(STEPS) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (index < step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
            )
        }
    }
}

@Composable
private fun Step(
    title: String,
    body: String?,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
    extra: @Composable () -> Unit = {},
) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    if (body != null) {
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))
    extra()
    Spacer(Modifier.height(24.dp))
    Button(onClick = primary.second, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(primary.first) }
    if (secondary != null) {
        TextButton(onClick = secondary.second, modifier = Modifier.fillMaxWidth()) { Text(secondary.first) }
    }
}

@Composable
private fun Instructions(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.forEachIndexed { index, line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(line, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Waiting(text: String) {
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val STEPS = 5
