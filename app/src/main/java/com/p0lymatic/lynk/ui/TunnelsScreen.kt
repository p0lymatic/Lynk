package com.p0lymatic.lynk.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.p0lymatic.lynk.data.ServerConfig
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono

@Composable
fun TunnelsScreen(app: AppState, nav: Navigator) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val forwards by app.connections.forwards.collectAsStateWithLifecycle()
    val tunnelErrors by app.connections.tunnelErrors.collectAsStateWithLifecycle()

    var pickServer by remember { mutableStateOf<Boolean>(false) }

    fun createTunnel() {
        val servers = data.servers
        when {
            servers.isEmpty() -> app.notify("Сначала добавь узел")
            servers.size == 1 -> nav.push(Route.TunnelEdit(null, servers.first().id))
            else -> pickServer = true
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "Туннели",
            subtitle = "Локальные порты через SSH",
            onBack = null,
            actions = {
                IconButton(
                    onClick = { createTunnel() },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Добавить туннель",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            if (data.tunnels.isEmpty()) {
                EmptyState(
                    title = "Нет туннелей",
                    text = "Туннель поднимает локальный порт на телефоне и ведёт его к сервису на узле. " +
                        "Например, localhost:15432 → 127.0.0.1:5432.",
                    actionText = "Добавить туннель",
                    onAction = { createTunnel() },
                )
            } else {
                data.tunnels.forEach { tunnel ->
                    val serverName = data.servers.firstOrNull { it.id == tunnel.serverId }?.name ?: "узел удалён"
                    LynkCard(
                        modifier = Modifier.padding(bottom = 8.dp),
                        onClick = { nav.push(Route.TunnelEdit(tunnel.id, tunnel.serverId)) },
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = tunnel.name,
                                        fontSize = 14.sp,
                                        color = LynkColors.text,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    MonoBadge(serverName)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "localhost:${tunnel.localPort} → ${tunnel.remoteHost}:${tunnel.remotePort}",
                                    fontFamily = Mono,
                                    fontSize = 11.5.sp,
                                    color = LynkColors.textDim,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(
                                checked = forwards.values.any { it.tunnelId == tunnel.id },
                                onCheckedChange = { on ->
                                    if (on) app.startTunnel(tunnel) else app.stopTunnel(tunnel)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = LynkColors.accent,
                                    checkedTrackColor = LynkColors.textMuted,
                                    uncheckedThumbColor = LynkColors.textMuted,
                                    uncheckedTrackColor = LynkColors.surfaceHigh,
                                ),
                            )
                        }
                        val error = tunnelErrors[tunnel.id]
                        if (error != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = error,
                                fontSize = 11.sp,
                                color = LynkColors.err,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickServer) {
        ServerPickerDialog(
            servers = data.servers,
            onDismiss = { pickServer = false },
            onPick = { server ->
                pickServer = false
                nav.push(Route.TunnelEdit(null, server.id))
            },
        )
    }
}

@Composable
private fun ServerPickerDialog(
    servers: List<ServerConfig>,
    onDismiss: () -> Unit,
    onPick: (ServerConfig) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LynkColors.surfaceHigh,
        titleContentColor = LynkColors.text,
        textContentColor = LynkColors.textDim,
        title = { Text("Выбери узел", fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                servers.forEach { server ->
                    TextButton(
                        onClick = { onPick(server) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.name, fontSize = 14.sp, color = LynkColors.text)
                                Text(
                                    text = "${server.user}@${server.host}:${server.port}",
                                    fontFamily = Mono,
                                    fontSize = 11.sp,
                                    color = LynkColors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = LynkColors.textDim) }
        },
    )
}
