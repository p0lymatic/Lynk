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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.data.AuthType
import com.p0lymatic.lynk.data.ServerConfig
import com.p0lymatic.lynk.data.Store
import com.p0lymatic.lynk.ui.theme.LynkColors
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ServerEditScreen(app: AppState, nav: Navigator, serverId: String?) {
    val data by app.store.state.collectAsStateWithLifecycle()

    val existing = remember(serverId) { serverId?.let { app.store.server(it) } }
    val hasStoredPassword = serverId != null && app.store.hasSecret(serverId, Store.SecretKind.PASSWORD)
    val hasStoredKey = serverId != null && app.store.hasSecret(serverId, Store.SecretKind.KEY)

    var name by rememberSaveable(serverId) { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable(serverId) { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable(serverId) { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by rememberSaveable(serverId) { mutableStateOf(existing?.user ?: "") }
    var auth by rememberSaveable(serverId) { mutableStateOf(existing?.auth ?: AuthType.PASSWORD) }
    var note by rememberSaveable(serverId) { mutableStateOf(existing?.note ?: "") }

    var password by remember(serverId) { mutableStateOf("") }
    var keyText by remember(serverId) { mutableStateOf("") }
    var passphrase by remember(serverId) { mutableStateOf("") }

    var saving by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    fun save() {
        if (saving) return
        val trimmedName = name.trim()
        val trimmedHost = host.trim()
        val trimmedUser = user.trim()
        when {
            trimmedName.isEmpty() -> {
                app.notify("Укажи имя узла")
                return
            }
            trimmedHost.isEmpty() -> {
                app.notify("Укажи хост")
                return
            }
        }
        val portValue = port.trim().toIntOrNull()
        if (portValue == null || portValue !in 1..65535) {
            app.notify("Порт должен быть 1..65535")
            return
        }
        if (trimmedUser.isEmpty()) {
            app.notify("Укажи пользователя")
            return
        }
        if (auth == AuthType.PASSWORD && password.isEmpty() && !hasStoredPassword) {
            app.notify("Введи пароль")
            return
        }
        if (auth == AuthType.KEY && keyText.isEmpty() && !hasStoredKey) {
            app.notify("Вставь приватный ключ")
            return
        }

        saving = true
        val id = serverId ?: UUID.randomUUID().toString()
        val current = serverId?.let { app.store.server(it) }
        if (current != null && (current.host != trimmedHost || current.port != portValue)) {
            app.store.forgetHostKey(current.host, current.port)
        }
        app.store.upsertServer(
            ServerConfig(
                id = id,
                name = trimmedName,
                host = trimmedHost,
                port = portValue,
                user = trimmedUser,
                auth = auth,
                note = note.trim(),
                createdAt = current?.createdAt ?: System.currentTimeMillis(),
            ),
        )
        if (auth == AuthType.PASSWORD && password.isNotEmpty()) {
            app.store.putSecret(id, Store.SecretKind.PASSWORD, password)
        }
        if (auth == AuthType.KEY && keyText.isNotEmpty()) {
            app.store.putSecret(id, Store.SecretKind.KEY, keyText)
        }
        if (auth == AuthType.KEY) {
            app.store.putSecret(id, Store.SecretKind.PASSPHRASE, passphrase.ifEmpty { null })
        }
        saving = false
        nav.pop()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = if (serverId == null) "Новый узел" else "Узел",
            subtitle = serverId?.let { data.servers.firstOrNull { s -> s.id == it }?.host },
            onBack = nav::pop,
            actions = {
                if (serverId != null) {
                    IconButton(onClick = { showDelete = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Удалить узел",
                            tint = LynkColors.err,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )

        EditorColumn {
            LynkField(
                value = name,
                onValueChange = { name = it },
                label = "Имя",
                placeholder = "Raspberry Pi",
            )
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = host,
                onValueChange = { host = it },
                label = "Хост",
                placeholder = "192.168.1.10",
                mono = true,
                keyboardType = KeyboardType.Uri,
            )
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = "Порт SSH",
                mono = true,
                keyboardType = KeyboardType.Number,
            )
            Spacer(Modifier.height(10.dp))
            LynkField(
                value = user,
                onValueChange = { user = it },
                label = "Пользователь",
                placeholder = "pi",
                mono = true,
            )

            SectionLabel("Аутентификация")
            Row {
                GhostButton(
                    text = if (auth == AuthType.PASSWORD) "• Пароль" else "Пароль",
                    onClick = { auth = AuthType.PASSWORD },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                GhostButton(
                    text = if (auth == AuthType.KEY) "• Ключ" else "Ключ",
                    onClick = { auth = AuthType.KEY },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))

            if (auth == AuthType.PASSWORD) {
                LynkField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (hasStoredPassword) "Пароль (оставь пустым, чтобы не менять)" else "Пароль",
                    isPassword = true,
                )
            } else {
                LynkField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = "Приватный ключ",
                    mono = true,
                    singleLine = false,
                    minLines = 5,
                    maxLines = 9,
                    placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                )
                Spacer(Modifier.height(10.dp))
                LynkField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = "Пароль ключа (если есть)",
                    isPassword = true,
                )
            }

            Spacer(Modifier.height(10.dp))
            LynkField(
                value = note,
                onValueChange = { note = it },
                label = "Заметка",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )

            Spacer(Modifier.height(18.dp))
            PrimaryButton(text = "Сохранить", onClick = { save() }, loading = saving)
        }
    }

    if (showDelete) {
        val id = serverId
        ConfirmDialog(
            title = "Удалить узел?",
            text = "Сервисы и туннели этого узла тоже будут удалены.",
            onConfirm = {
                if (id != null) {
                    app.scope.launch { app.connections.disconnect(id) }
                    app.connections.forgetKeyFile(id)
                    app.store.clearSecrets(id)
                    app.store.deleteServer(id)
                    nav.pop()
                }
            },
            onDismiss = { showDelete = false },
        )
    }
}