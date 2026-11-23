package com.p0lymatic.lynk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.data.Tunnel
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import java.util.UUID

@Composable
fun TunnelEditScreen(app: AppState, nav: Navigator, tunnelId: String?, serverId: String) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val existing = remember(tunnelId) {
        tunnelId?.let { id -> data.tunnels.firstOrNull { it.id == id } }
    }

    var name by remember(tunnelId) { mutableStateOf(existing?.name.orEmpty()) }
    var selectedServerId by remember(tunnelId) { mutableStateOf(existing?.serverId ?: serverId) }
    var localPort by remember(tunnelId) { mutableStateOf(existing?.localPort?.toString().orEmpty()) }
    var remoteHost by remember(tunnelId) { mutableStateOf(existing?.remoteHost ?: "127.0.0.1") }
    var remotePort by remember(tunnelId) { mutableStateOf(existing?.remotePort?.toString().orEmpty()) }
    var autoStart by remember(tunnelId) { mutableStateOf(existing?.autoStart ?: false) }
    var serverMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val selectedServer = data.servers.firstOrNull { it.id == selectedServerId }
    val localValue = localPort.toIntOrNull()
    val remoteValue = remotePort.toIntOrNull()
    val valid = name.isNotBlank() &&
        localValue != null && localValue in 1..65535 &&
        remoteValue != null && remoteValue in 1..65535 &&
        selectedServer != null

    fun save() {
        if (name.isBlank()) {
            app.notify("Укажи название туннеля")
            return
        }
        if (selectedServer == null) {
            app.notify("Выбери узел")
            return
        }
        val local = localValue
        val remote = remoteValue
        if (local == null || local !in 1..65535) {
            app.notify("Локальный порт должен быть 1..65535")
            return
        }
        if (remote == null || remote !in 1..65535) {
            app.notify("Порт на узле должен быть 1..65535")
            return
        }
        app.store.upsertTunnel(
            Tunnel(
                id = tunnelId ?: UUID.randomUUID().toString(),
                serverId = selectedServer.id,
                name = name.trim(),
                localPort = local,
                remoteHost = remoteHost.ifBlank { "127.0.0.1" },
                remotePort = remote,
                autoStart = autoStart,
            ),
        )
        nav.pop()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = if (tunnelId == null) "Новый туннель" else "Туннель",
            subtitle = data.servers.firstOrNull { it.id == serverId }?.name,
            onBack = nav::pop,
            actions = {
                if (tunnelId != null) {
                    IconButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Удалить туннель",
                            tint = LynkColors.err,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
        )

        EditorColumn {
            LynkField(
                value = name,
                onValueChange = { name = it },
                label = "Название",
                placeholder = "База разработки",
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = LynkColors.surface,
                    border = BorderStroke(1.dp, LynkColors.border),
                    onClick = { serverMenu = true },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Узел", fontSize = 11.sp, color = LynkColors.textMuted)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = selectedServer?.name ?: "не выбран",
                                fontSize = 14.sp,
                                color = if (selectedServer != null) LynkColors.text else LynkColors.textMuted,
                                maxLines = 1,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = LynkColors.textDim,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = serverMenu,
                    onDismissRequest = { serverMenu = false },
                    containerColor = LynkColors.surfaceHigh,
                ) {
                    if (data.servers.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Нет узлов", fontSize = 13.sp, color = LynkColors.textMuted) },
                            onClick = { serverMenu = false },
                        )
                    }
                    data.servers.forEach { server ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(server.name, fontSize = 13.5.sp, color = LynkColors.text)
                                    Text(
                                        text = "${server.user}@${server.host}:${server.port}",
                                        fontFamily = Mono,
                                        fontSize = 10.5.sp,
                                        color = LynkColors.textMuted,
                                    )
                                }
                            },
                            onClick = {
                                selectedServerId = server.id
                                serverMenu = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = localPort,
                onValueChange = { localPort = it.filter { ch -> ch.isDigit() } },
                label = "Локальный порт на телефоне",
                mono = true,
                keyboardType = KeyboardType.Number,
                placeholder = "15432",
            )
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = remoteHost,
                onValueChange = { remoteHost = it },
                label = "Хост на узле",
                mono = true,
                placeholder = "127.0.0.1",
            )
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = remotePort,
                onValueChange = { remotePort = it.filter { ch -> ch.isDigit() } },
                label = "Порт на узле",
                mono = true,
                keyboardType = KeyboardType.Number,
                placeholder = "5432",
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autoStart,
                    onCheckedChange = { autoStart = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LynkColors.accent,
                        checkedTrackColor = LynkColors.textMuted,
                        uncheckedThumbColor = LynkColors.textMuted,
                        uncheckedTrackColor = LynkColors.surfaceHigh,
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Поднимать при запуске приложения",
                    fontSize = 13.sp,
                    color = LynkColors.textDim,
                    modifier = Modifier.clickable { autoStart = !autoStart },
                )
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(
                text = "Сохранить",
                enabled = valid,
                onClick = { save() },
            )
        }
    }

    if (confirmDelete && tunnelId != null) {
        ConfirmDialog(
            title = "Удалить туннель?",
            text = "Туннель «${existing?.name ?: name}» будет удалён, локальный порт освобождён.",
            confirmText = "Удалить",
            onConfirm = {
                existing?.let { app.stopTunnel(it) }
                app.store.deleteTunnel(tunnelId)
                nav.pop()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}