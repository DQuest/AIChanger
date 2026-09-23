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

/**
 * Первая настройка по шагам. Шаги 1 и 2 отмечаются сами (параметры разработчика включены, сопряжение
 * прошло), третий — автозапуск vivo — проверить нельзя, его подтверждает пользователь.
 */
@Composable
fun SetupWizard(state: UiState, actions: UiActions) {
    var skipDeveloperStep by rememberSaveable { mutableStateOf(false) }
    var pairingStarted by rememberSaveable { mutableStateOf(false) }
    val step = when {
        state.paired -> 3
        state.developerOptions || skipDeveloperStep -> 2
        else -> 1
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
                    title = "Включите параметры разработчика",
                    body = "Они нужны, чтобы AIChanger мог перехватывать кнопку питания. " +
                        "Откройте «О телефоне» и нажмите 7 раз на номер версии ПО, пока не появится сообщение, " +
                        "что вы стали разработчиком. Потом вернитесь сюда.",
                    primary = "Открыть «О телефоне»" to actions::openDeviceInfo,
                    secondary = "Уже включены" to { skipDeveloperStep = true },
                )
                2 -> Step(
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
                    Hint("Нужен Wi-Fi. Код вводится один раз, дальше AIChanger подключается сам.")
                    if (pairingStarted) Waiting("Ждём код сопряжения из уведомления…")
                }
                else -> Step(
                    title = "Разрешите автозапуск",
                    body = "Тогда кнопка будет работать и после перезагрузки телефона. " +
                        "Найдите AIChanger в списке и включите переключатель.",
                    primary = "Открыть автозапуск" to actions::openAutoStart,
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

private const val STEPS = 3
