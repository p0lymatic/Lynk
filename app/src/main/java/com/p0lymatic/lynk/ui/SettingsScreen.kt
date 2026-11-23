package com.p0lymatic.lynk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.BuildConfig
import com.p0lymatic.lynk.data.HostKeyEntry
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: AppState, nav: Navigator) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val connStates by app.connections.states.collectAsStateWithLifecycle()
    val forwards by app.connections.forwards.collectAsStateWithLifecycle()

    // Reading the flows above is what makes this recompose when sessions/forwards change.
    val activeCount = remember(connStates, forwards) { app.connections.activeCount() }

    var pendingKey by remember { mutableStateOf<HostKeyEntry?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "Настройки",
            subtitle = "Lynk ${BuildConfig.VERSION_NAME}",
            onBack = null,
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("Соединения")
            KeyRow("Активных сессий", activeCount.toString())
            KeyRow("Туннелей поднято", forwards.size.toString())
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Отключить всё",
                enabled = activeCount > 0,
                onClick = { app.scope.launch { app.connections.disconnectAll() } },
            )

            SectionLabel("Ключи серверов")
            if (data.hostKeys.isEmpty()) {
                SectionNote("Отпечатки появятся после первого подключения. Lynk никогда не принимает новый ключ молча.")
            } else {
                data.hostKeys.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            KeyRow(entry.label, entry.type, valueColor = LynkColors.textDim)
                            Text(
                                text = entry.fingerprint.take(26) + "…",
                                fontFamily = Mono,
                                fontSize = 10.5.sp,
                                color = LynkColors.textMuted,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { pendingKey = entry },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Забыть ключ",
                                tint = LynkColors.textMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            SectionLabel("О приложении")
            SectionNote(
                "Lynk — менеджер SSH-туннелей со встроенным терминалом и браузером. " +
                    "Сервисы остаются на localhost узла и не выставляются в интернет.",
            )
            KeyRow("Репозиторий", "git.plmtc.ru/p0lymatic/Lynk")
            KeyRow("Версия", BuildConfig.VERSION_NAME)

            SectionLabel("Опасная зона")
            GhostButton(
                text = "Сбросить все данные",
                danger = true,
                onClick = { confirmReset = true },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingKey?.let { entry ->
        ConfirmDialog(
            title = "Забыть ключ ${entry.label}?",
            text = "При следующем подключении Lynk снова спросит подтверждение отпечатка.",
            confirmText = "Забыть",
            onConfirm = {
                app.store.forgetHostKey(entry.host, entry.port)
                pendingKey = null
            },
            onDismiss = { pendingKey = null },
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Сбросить все данные?",
            text = "Узлы, сервисы, туннели, сохранённые секреты и доверенные ключи будут удалены без возможности отмены.",
            confirmText = "Сбросить",
            onConfirm = {
                val servers = app.store.state.value.servers
                val tunnels = app.store.state.value.tunnels
                val hostKeys = app.store.state.value.hostKeys
                tunnels.forEach { app.store.deleteTunnel(it.id) }
                hostKeys.forEach { app.store.forgetHostKey(it.host, it.port) }
                servers.forEach {
                    app.store.clearSecrets(it.id)
                    app.store.deleteServer(it.id)
                }
                app.notify("Данные удалены")
                confirmReset = false
            },
            onDismiss = { confirmReset = false },
        )
    }
}
