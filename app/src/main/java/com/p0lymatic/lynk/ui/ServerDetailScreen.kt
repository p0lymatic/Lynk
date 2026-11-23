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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.ssh.ConnectionManager
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.launch

@Composable
fun ServerDetailScreen(app: AppState, nav: Navigator, serverId: String) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val connStates by app.connections.states.collectAsStateWithLifecycle()
    val forwards by app.connections.forwards.collectAsStateWithLifecycle()
    val tunnelErrors by app.connections.tunnelErrors.collectAsStateWithLifecycle()

    val server = data.servers.firstOrNull { it.id == serverId }

    if (server == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader("Узел", onBack = nav::pop)
            EmptyState("Узел не найден", "Возможно, он был удалён.")
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val connState = connStates[server.id] ?: ConnectionManager.ConnState()
        val connected = connState.connected

        ScreenHeader(
            title = server.name,
            subtitle = "${server.user}@${server.host}:${server.port}",
            onBack = nav::pop,
            actions = {
                IconButton(
                    onClick = { nav.push(Route.ServerEdit(server.id)) },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Изменить узел",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            LynkCardBorder {
                StatusLine(connState.phase, phaseText(connState.phase))
                if (connState.error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = connState.error,
                        fontSize = 12.sp,
                        color = LynkColors.err,
                        lineHeight = 17.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (server.osId.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OsIcon(server.osId, size = 18.dp)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = server.osName.ifBlank { server.osId },
                            fontSize = 12.sp,
                            color = LynkColors.textDim,
                            maxLines = 1,
                        )
                        if (server.osArch.isNotBlank()) {
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = server.osArch,
                                fontFamily = Mono,
                                fontSize = 10.5.sp,
                                color = LynkColors.textMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton(
                        text = "Терминал",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { nav.push(Route.Terminal(server.id)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    GhostButton(
                        text = if (connected) "Отключить" else "Подключить",
                        onClick = {
                            app.scope.launch {
                                if (connected) app.connections.disconnect(server.id) else app.connect(server)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (server.note.isNotBlank()) {
                SectionNote(server.note)
            }

            SectionLabel(
                "Сервисы",
                trailing = {
                    TextButton(onClick = { nav.push(Route.ServiceEdit(null, server.id)) }) {
                        Text("+ добавить", fontSize = 12.sp, color = LynkColors.textDim)
                    }
                },
            )
            val services = app.store.servicesFor(server.id)
            if (services.isEmpty()) {
                SectionNote("Пока нет сохранённых сервисов.")
            } else {
                services.forEach { service ->
                    LynkCard(
                        modifier = Modifier.padding(bottom = 8.dp),
                        onClick = {
                            nav.push(
                                Route.Browser(
                                    serverId = server.id,
                                    serviceId = service.id,
                                    port = service.port,
                                    scheme = service.scheme,
                                    path = service.normalizedPath,
                                    title = service.name,
                                    host = service.host,
                                ),
                            )
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = service.name.ifBlank { "порт ${service.port}" },
                                    fontSize = 14.sp,
                                    color = LynkColors.text,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "${service.host}:${service.port}",
                                    fontFamily = Mono,
                                    fontSize = 11.sp,
                                    color = LynkColors.textMuted,
                                    maxLines = 1,
                                )
                            }
                            IconButton(
                                onClick = { nav.push(Route.ServiceEdit(service.id, server.id)) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Изменить сервис",
                                    tint = LynkColors.textMuted,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            SectionLabel(
                "Туннели",
                trailing = {
                    TextButton(onClick = { nav.push(Route.TunnelEdit(null, server.id)) }) {
                        Text("+ добавить", fontSize = 12.sp, color = LynkColors.textDim)
                    }
                },
            )
            val tunnels = data.tunnels.filter { it.serverId == server.id }
            if (tunnels.isEmpty()) {
                SectionNote("Туннелей пока нет.")
            } else {
                tunnels.forEach { tunnel ->
                    LynkCard(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = tunnel.name,
                                    fontSize = 14.sp,
                                    color = LynkColors.text,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "localhost:${tunnel.localPort} → ${tunnel.remoteHost}:${tunnel.remotePort}",
                                    fontFamily = Mono,
                                    fontSize = 11.sp,
                                    color = LynkColors.textMuted,
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
}

private fun phaseText(phase: ConnectionManager.Phase): String = when (phase) {
    ConnectionManager.Phase.CONNECTED -> "Подключён"
    ConnectionManager.Phase.CONNECTING -> "Подключение…"
    ConnectionManager.Phase.ERROR -> "Ошибка"
    ConnectionManager.Phase.IDLE -> "Не подключён"
}
