package dev.dquest.aichanger.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.dquest.aichanger.Assistants
import dev.dquest.aichanger.BuildConfig
import dev.dquest.aichanger.R
import dev.dquest.aichanger.describeError
import dev.dquest.aichanger.describeProblem
import dev.dquest.aichanger.describeStatus
import dev.dquest.aichanger.daemon.DaemonProtocol

@Composable
fun HomeScreen(state: UiState, assistants: List<AssistantItem>, actions: UiActions) {
    val selected = assistants.firstOrNull { it.assistant.target == state.assistant }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                AppIcon(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            }
            StatusCard(state, selected?.assistant?.label, actions)
            AssistantCard(assistants, state.assistant, state.changedSystemAssistant, actions)
            if (state.assistant == Assistants.GEMINI.target) GeminiModeCard(state.voice, actions)
            TipCard()
            Footer(state, actions)
        }
    }
}

@Composable
private fun StatusCard(state: UiState, assistantLabel: String?, actions: UiActions) {
    val status = LocalStatusColors.current
    val scheme = MaterialTheme.colorScheme
    val error = state.error
    val problem = state.problem.takeIf { state.running }
    val stopError = state.stopError
    val context = LocalContext.current
    val (container, content) = when {
        problem != null || stopError != null -> scheme.errorContainer to scheme.onErrorContainer
        state.running -> status.successContainer to status.onSuccessContainer
        state.enabled && !state.launching && !state.waitingForWifi && error != null -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurface
    }
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(content.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        problem != null || stopError != null -> StatusIcon(R.drawable.ic_status_error)
                        state.running -> StatusIcon(R.drawable.ic_status_ok)
                        state.waitingForWifi -> StatusIcon(R.drawable.ic_status_wifi)
                        state.launching -> CircularProgressIndicator(Modifier.size(22.dp), color = content, strokeWidth = 2.dp)
                        state.enabled && error != null -> StatusIcon(R.drawable.ic_status_error)
                        else -> StatusIcon(R.drawable.ic_status_power)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val (title, subtitle) = when {
                        problem != null -> stringResource(R.string.status_problem_title) to
                            describeProblem(context, problem).replaceFirstChar { it.uppercase() }
                        state.running -> stringResource(R.string.status_running_title) to
                            stringResource(R.string.status_running_text, assistantLabel ?: stringResource(R.string.assistant_generic))
                        state.waitingForWifi -> stringResource(
                            when {
                                state.untrustedWifi -> R.string.status_untrusted_wifi_title
                                state.wifiEnabled -> R.string.status_waiting_wifi_title
                                else -> R.string.status_wifi_off_title
                            }
                        ) to (if (state.untrustedWifi) stringResource(R.string.untrusted_reason) + " " else "") +
                            stringResource(R.string.wifi_wait_text)
                        state.launching -> stringResource(R.string.status_launching_title) to
                            stringResource(R.string.status_launching_text)
                        stopError != null -> stringResource(R.string.status_stop_error_title) to
                            stringResource(R.string.status_stop_error_text, describeError(context, stopError))
                        !state.enabled -> stringResource(R.string.status_off_title) to stringResource(R.string.status_off_text)
                        error != null -> stringResource(R.string.status_failed_title) to describeError(context, error)
                        else -> stringResource(R.string.status_not_started_title) to stringResource(R.string.status_not_started_text)
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.8f))
                }
            }
            when {
                stopError != null -> {
                    ActionButton(stringResource(R.string.action_retry), actions::disable)
                    TextButton(onClick = actions::enable, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_enable_again))
                    }
                }
                !state.enabled -> ActionButton(stringResource(R.string.action_enable), actions::enable)
                state.waitingForWifi -> ActionButton(stringResource(R.string.action_open_wifi), actions::openWifi)
                !state.running && !state.launching && error != null -> ActionButton(stringResource(R.string.action_retry), actions::enable)
            }
        }
    }
}

@Composable
private fun StatusIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
private fun AssistantCard(
    assistants: List<AssistantItem>,
    selected: String,
    changedSystemAssistant: Boolean,
    actions: UiActions,
) {
    SectionCard(stringResource(R.string.section_assistant)) {
        if (assistants.isEmpty()) {
            Text(
                stringResource(R.string.no_assistants),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        for (item in assistants) {
            val isSelected = item.assistant.target == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(isSelected, role = Role.RadioButton) { actions.selectAssistant(item.assistant.target) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                if (item.icon != null) {
                    Image(item.icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                } else {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer))
                }
                Spacer(Modifier.width(16.dp))
                Text(item.assistant.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                RadioButton(selected = isSelected, onClick = null)
            }
        }
        // Показываем, только если демон действительно поменял ассистента телефона: для Gemini это нужно,
        // лишь когда системным ассистентом был не Google.
        val current = assistants.firstOrNull { it.assistant.target == selected }?.assistant
        if (current != null && !DaemonProtocol.isDirect(current.target) && changedSystemAssistant) {
            val why = if (current == Assistants.GEMINI) stringResource(R.string.role_changed_gemini)
            else stringResource(R.string.role_changed_other, current.label)
            Text(
                stringResource(R.string.role_restore_note, why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun GeminiModeCard(voice: Boolean, actions: UiActions) {
    SectionCard(null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(voice, role = Role.Switch) { actions.setVoice(it) }
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.gemini_listen_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (voice) R.string.gemini_listen_on else R.string.gemini_listen_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = voice, onCheckedChange = null)
        }
    }
}

@Composable
private fun TipCard() {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Icon(
                painterResource(R.drawable.ic_status_power),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.tip_power_menu_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.tip_power_menu_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String?, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            content()
        }
    }
}

@Composable
private fun Footer(state: UiState, actions: UiActions) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.enabled) {
            TextButton(onClick = actions::disable) { Text(stringResource(R.string.action_turn_off)) }
        }
        Row {
            TextButton(onClick = actions::restartSetup) { Text(stringResource(R.string.action_setup_again)) }
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(stringResource(if (showDetails) R.string.details_hide else R.string.details_show))
            }
        }
        val context = LocalContext.current
        AnimatedVisibility(showDetails) {
            Text(
                buildString {
                    append(stringResource(R.string.details_version, BuildConfig.VERSION_NAME)).append('\n')
                    append(state.details?.let { describeStatus(context, it) } ?: stringResource(R.string.details_no_daemon))
                    state.error?.let { append('\n').append(stringResource(R.string.details_last_error, describeError(context, it))) }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(16.dp),
            )
        }
    }
}
