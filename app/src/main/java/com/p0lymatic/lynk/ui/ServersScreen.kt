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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.ssh.ConnectionManager.Phase
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(app: AppState, nav: Navigator) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val connStates by app.connections.states.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "Узлы",
            subtitle = "SSH-подключения",
            actions = {
                IconButton(onClick = { nav.push(Route.ServerEdit(null)) }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Добавить узел",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (data.servers.isEmpty()) {
                EmptyState(
                    title = "Нет узлов",
                    text = "Добавь SSH-узел: адрес, пользователя и ключ или пароль.",
                    actionText = "Добавить узел",
                ) { nav.push(Route.ServerEdit(null)) }
            } else {
                Spacer(Modifier.height(4.dp))
                data.servers.sortedBy { it.name.lowercase() }.forEach { server ->
                    val state = connStates[server.id]
                    val phase = state?.phase ?: Phase.IDLE
                    val statusText = when (phase) {
                        Phase.IDLE -> "Не подключён"
                        Phase.CONNECTING -> "Подключение…"
                        Phase.CONNECTED -> "Подключён"
                        Phase.ERROR -> "Ошибка"
                    }

                    LynkCard(onClick = { nav.push(Route.ServerDetail(server.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = server.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = LynkColors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = statusText,
                                fontSize = 11.5.sp,
                                color = LynkColors.textMuted,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(7.dp))
                            StatusDot(phase)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "${server.user}@${server.host}:${server.port}",
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            color = LynkColors.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val error = state?.error
                        if (!error.isNullOrBlank()) {
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = error,
                                fontSize = 11.sp,
                                color = LynkColors.err,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        GhostButton(
                            text = if (state?.connected == true) "Отключить" else "Подключить",
                            onClick = {
                                app.scope.launch {
                                    if (state?.connected == true) app.connections.disconnect(server.id)
                                    else app.connect(server)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}