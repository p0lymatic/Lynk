package com.p0lymatic.lynk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.data.MetricKind
import com.p0lymatic.lynk.data.MonitorSettings
import com.p0lymatic.lynk.data.ServerConfig
import com.p0lymatic.lynk.ssh.ConnectionManager.Phase
import com.p0lymatic.lynk.ssh.NodeMetrics
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import java.util.Locale

@Composable
fun MonitoringScreen(app: AppState, nav: Navigator, onMenu: () -> Unit) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val connStates by app.connections.states.collectAsStateWithLifecycle()
    val samples by app.monitor.samples.collectAsStateWithLifecycle()
    val errors by app.monitor.errors.collectAsStateWithLifecycle()
    val busy by app.monitor.busy.collectAsStateWithLifecycle()

    val servers = data.servers

    LaunchedEffect(servers.map { it.id }) {
        servers.forEach { app.monitor.watch(it.id) }
    }
    DisposableEffect(Unit) {
        onDispose { app.monitor.unwatchAll() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Мониторинг",
            actions = {
                IconButton(onClick = onMenu) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Меню",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = { nav.push(Route.MonitorSettings) }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Настройки",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { servers.forEach { app.monitor.refreshNow(it.id) } },
                    enabled = servers.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Обновить всё",
                        tint = if (servers.isEmpty()) LynkColors.textMuted else LynkColors.textDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        DividerLine()

        if (servers.isEmpty()) {
            EmptyState(
                title = "Нет узлов",
                text = "Добавь узел, чтобы видеть его состояние.",
                actionText = "Добавить узел",
            ) { nav.push(Route.ServerEdit(null)) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerMonitorCard(
                        server = server,
                        phase = connStates[server.id]?.phase ?: Phase.IDLE,
                        sample = samples[server.id],
                        error = errors[server.id],
                        busy = busy.contains(server.id),
                        monitor = data.monitor,
                        onOpen = { nav.push(Route.ServerDetail(server.id)) },
                        onRefresh = { app.monitor.refreshNow(server.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerMonitorCard(
    server: ServerConfig,
    phase: Phase,
    sample: NodeMetrics?,
    error: String?,
    busy: Boolean,
    monitor: MonitorSettings,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
) {
    LynkCard(onClick = onOpen) {
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
            OsIcon(server.osId, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = server.osName.ifBlank { "ОС не определена" },
                fontSize = 11.sp,
                color = if (server.osName.isBlank()) LynkColors.textMuted else LynkColors.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }

        Spacer(Modifier.height(5.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${server.port} · ${server.user}@${server.host}",
                fontFamily = Mono,
                fontSize = 11.sp,
                color = LynkColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusDot(phase, size = 6.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = phaseLabel(phase),
                fontSize = 11.sp,
                color = if (phase == Phase.ERROR) LynkColors.err else LynkColors.textMuted,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(10.dp))

        when {
            error != null -> {
                Text(
                    text = error,
                    fontSize = 11.sp,
                    color = LynkColors.err,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(text = "Подключиться", onClick = onRefresh)
            }

            sample == null && busy -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = LynkColors.textDim,
                trackColor = Color.Transparent,
            )

            else -> {
                MetricsGrid(settings = monitor, metrics = sample)

                if (sample != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Нагрузка ${loadValue(sample.load1)} / ${loadValue(sample.load5)} / " +
                            loadValue(sample.load15),
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        color = LynkColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = extraLine(sample),
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        color = LynkColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(10.dp))
                GhostButton(
                    text = if (busy) "Обновление…" else "Обновить",
                    enabled = !busy,
                    onClick = onRefresh,
                )
            }
        }
    }
}

private fun phaseLabel(phase: Phase): String = when (phase) {
    Phase.CONNECTED -> "подключён"
    Phase.CONNECTING -> "подключение…"
    Phase.ERROR -> "ошибка"
    Phase.IDLE -> "не подключён"
}

private fun loadValue(value: Float?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

/** "Аптайм 5 д 4 ч · 48°C · ↓1.2 МБ/с ↑0.4 МБ/с", skipping metrics the node did not report. */
private fun extraLine(sample: NodeMetrics): String {
    val parts = mutableListOf("Аптайм ${metricValueText(MetricKind.UPTIME, sample)}")
    val temp = metricValueText(MetricKind.TEMP, sample)
    if (temp != "—") parts += temp
    val net = metricValueText(MetricKind.NET, sample)
    if (net != "—") parts += net
    return parts.joinToString(" · ")
}