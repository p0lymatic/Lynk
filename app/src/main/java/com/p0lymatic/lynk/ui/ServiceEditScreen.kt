package com.p0lymatic.lynk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.data.Service
import com.p0lymatic.lynk.ui.theme.LynkColors
import java.util.UUID

@Composable
fun ServiceEditScreen(app: AppState, nav: Navigator, serviceId: String?, serverId: String) {
    val data by app.store.state.collectAsStateWithLifecycle()
    // Snapshot once per edited id: store writes must not reset fields the user is typing into.
    val existing = remember(serviceId) {
        serviceId?.let { id -> data.services.firstOrNull { it.id == id } }
    }

    var name by remember(serviceId) { mutableStateOf(existing?.name.orEmpty()) }
    var port by remember(serviceId) { mutableStateOf(existing?.port?.toString().orEmpty()) }
    var scheme by remember(serviceId) { mutableStateOf(existing?.scheme ?: "http") }
    var host by remember(serviceId) { mutableStateOf(existing?.host ?: "127.0.0.1") }
    var path by remember(serviceId) { mutableStateOf(existing?.path ?: "/") }
    var confirmDelete by remember { mutableStateOf(false) }

    val portValue = port.toIntOrNull()
    val valid = name.isNotBlank() && portValue != null && portValue in 1..65535

    fun buildService(): Service {
        val id = serviceId ?: UUID.randomUUID().toString()
        return Service(
            id = id,
            serverId = serverId,
            name = name.trim(),
            host = host.ifBlank { "127.0.0.1" },
            port = portValue ?: 0,
            scheme = scheme,
            path = path.ifBlank { "/" },
            favorite = existing?.favorite ?: true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
    }

    fun save(): Service? {
        if (name.isBlank()) {
            app.notify("Укажи название сервиса")
            return null
        }
        val parsedPort = portValue
        if (parsedPort == null || parsedPort !in 1..65535) {
            app.notify("Порт должен быть 1..65535")
            return null
        }
        val service = buildService()
        app.store.upsertService(service)
        return service
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = if (serviceId == null) "Новый сервис" else "Сервис",
            subtitle = data.servers.firstOrNull { it.id == serverId }?.name,
            onBack = nav::pop,
            actions = {
                if (serviceId != null) {
                    IconButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Удалить сервис",
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
                placeholder = "Home Assistant",
            )
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                label = "Порт",
                mono = true,
                keyboardType = KeyboardType.Number,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.width(200.dp)) {
                GhostButton(
                    text = "HTTP",
                    icon = if (scheme == "http") Icons.Filled.Check else null,
                    onClick = { scheme = "http" },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                GhostButton(
                    text = "HTTPS",
                    icon = if (scheme == "https") Icons.Filled.Check else null,
                    onClick = { scheme = "https" },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = host,
                onValueChange = { host = it },
                label = "Хост (видимый с узла)",
                mono = true,
                placeholder = "127.0.0.1",
            )
            SectionNote("Оставь 127.0.0.1, если сервис на самом узле. Другой адрес — сервис в его локальной сети.")
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = path,
                onValueChange = { path = it },
                label = "Путь",
                mono = true,
                placeholder = "/",
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton(
                text = "Сохранить",
                enabled = valid,
                onClick = {
                    save()?.let { nav.pop() }
                },
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Открыть в Lynk",
                enabled = valid,
                onClick = {
                    save()?.let { service ->
                        nav.pop()
                        nav.push(
                            Route.Browser(
                                serverId = serverId,
                                serviceId = service.id,
                                port = service.port,
                                scheme = service.scheme,
                                path = service.normalizedPath,
                                title = service.name,
                                host = service.host,
                            ),
                        )
                    }
                },
            )
            if (serviceId != null) {
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    text = "Удалить",
                    danger = true,
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete && serviceId != null) {
        ConfirmDialog(
            title = "Удалить сервис?",
            text = "Сервис «${existing?.name ?: name}» будет удалён с узла.",
            confirmText = "Удалить",
            onConfirm = {
                app.store.deleteService(serviceId)
                nav.pop()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
