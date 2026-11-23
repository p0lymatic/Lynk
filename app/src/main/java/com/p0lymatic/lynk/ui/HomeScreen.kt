package com.p0lymatic.lynk.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.data.Service
import com.p0lymatic.lynk.ssh.ConnectionManager.Phase
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono

/** Result of understanding an address bar entry such as `3000`, `8080/admin` or `https://host:443/p`. */
internal data class AddressInput(
    val host: String,
    val port: Int,
    val scheme: String,
    val path: String,
)

/**
 * `3000` → 127.0.0.1:3000, `8080/admin` → /admin, `192.168.1.5:8123`, `https://host:443/panel`.
 * Returns null when no usable port is present.
 */
internal fun parseAddressInput(raw: String): AddressInput? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    var scheme = "http"
    var rest = trimmed
    val lower = rest.lowercase()
    if (lower.startsWith("https://")) {
        scheme = "https"
        rest = rest.substring(8)
    } else if (lower.startsWith("http://")) {
        scheme = "http"
        rest = rest.substring(7)
    }
    val slash = rest.indexOf('/')
    val authority = if (slash >= 0) rest.substring(0, slash) else rest
    val rawPath = if (slash >= 0) rest.substring(slash) else ""
    val colon = authority.indexOf(':')
    val host = if (colon >= 0) authority.substring(0, colon).trim() else ""
    val portText = if (colon >= 0) authority.substring(colon + 1).trim() else authority.trim()
    val port = portText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val path = if (rawPath.isBlank()) "/" else if (rawPath.startsWith("/")) rawPath else "/$rawPath"
    return AddressInput(host = host.ifBlank { "127.0.0.1" }, port = port, scheme = scheme, path = path)
}

@Composable
fun HomeScreen(app: AppState, nav: Navigator, onMenu: () -> Unit) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val connStates by app.connections.states.collectAsStateWithLifecycle()

    var address by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var nodeMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }

    val selected = data.servers.firstOrNull { it.id == selectedId }

    LaunchedEffect(data.servers) {
        if (selectedId.isBlank() || data.servers.none { it.id == selectedId }) {
            selectedId = data.servers.firstOrNull()?.id ?: ""
        }
    }

    fun openAddress() {
        val parsed = parseAddressInput(address) ?: run {
            app.notify("Укажи порт, например 3000")
            return
        }
        val server = selected ?: run {
            app.notify("Сначала добавь узел")
            return
        }
        nav.push(
            Route.Browser(
                serverId = server.id,
                serviceId = null,
                port = parsed.port,
                scheme = parsed.scheme,
                path = parsed.path,
                title = "Порт ${parsed.port}",
                host = parsed.host,
            ),
        )
    }

    Column(Modifier.fillMaxSize()) {
        BrowserToolbar(
            address = address,
            onAddressChange = { address = it },
            onSubmit = { openAddress() },
            addressPrefix = selected?.name,
            leading = {
                IconButton(onClick = onMenu, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Меню",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
            actions = {
                Box {
                    IconButton(
                        onClick = {
                            if (data.servers.isEmpty()) app.notify("Сначала добавь узел") else nodeMenu = true
                        },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.List,
                            contentDescription = "Выбрать узел",
                            tint = LynkColors.textDim,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = nodeMenu,
                        onDismissRequest = { nodeMenu = false },
                        containerColor = LynkColors.surfaceHigh,
                    ) {
                        data.servers.forEach { server ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(server.name, fontSize = 13.sp, color = LynkColors.text)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "${server.host}:${server.port}",
                                            fontFamily = Mono,
                                            fontSize = 11.sp,
                                            color = LynkColors.textMuted,
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (server.id == selectedId) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = LynkColors.text,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                                onClick = {
                                    selectedId = server.id
                                    nodeMenu = false
                                },
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { overflowMenu = true }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Ещё",
                            tint = LynkColors.textDim,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowMenu,
                        onDismissRequest = { overflowMenu = false },
                        containerColor = LynkColors.surfaceHigh,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Добавить узел", fontSize = 13.sp, color = LynkColors.text) },
                            onClick = {
                                overflowMenu = false
                                nav.push(Route.ServerEdit(null))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Настройки", fontSize = 13.sp, color = LynkColors.text) },
                            onClick = {
                                overflowMenu = false
                                nav.selectTab(TabId.SETTINGS)
                            },
                        )
                    }
                }
            },
        )

        DividerLine()

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${data.servers.size} узлов · ${data.services.size} сервисов",
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = LynkColors.textMuted,
                )

                SectionLabel("Сервисы")

                if (data.servers.isEmpty()) {
                    EmptyState(
                        title = "Нет узлов",
                        text = "Lynk работает через SSH. Добавь первый узел — и его сервисы появятся здесь.",
                        actionText = "Добавить узел",
                    ) { nav.push(Route.ServerEdit(null)) }
                } else if (data.services.isEmpty()) {
                    EmptyState(
                        title = "Пока пусто",
                        text = "Открой узел и добавь первый сервис — он появится здесь",
                        actionText = "Открыть узел",
                    ) { data.servers.firstOrNull()?.let { nav.push(Route.ServerDetail(it.id)) } }
                } else {
                    val serversById = data.servers.associateBy { it.id }
                    val services = data.services.sortedWith(
                        compareByDescending<Service> { it.favorite }.thenBy { it.name.lowercase() },
                    )
                    services.forEach { service ->
                        val server = serversById[service.serverId]
                        LynkCard(
                            modifier = Modifier.animateContentSize(),
                            onClick = {
                                nav.push(
                                    Route.Browser(
                                        service.serverId,
                                        service.id,
                                        service.port,
                                        service.scheme,
                                        service.normalizedPath,
                                        service.name,
                                        service.host,
                                    ),
                                )
                            },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ServiceIcon(app, service)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = service.name,
                                        fontSize = 14.5.sp,
                                        color = LynkColors.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = "${server?.name ?: "—"} · ${service.host}:${service.port}",
                                        fontFamily = Mono,
                                        fontSize = 11.sp,
                                        color = LynkColors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("›", fontSize = 18.sp, color = LynkColors.textMuted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (data.servers.isNotEmpty()) {
                    SectionLabel("Узлы")
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        data.servers.forEach { server ->
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = LynkColors.surface,
                                border = BorderStroke(1.dp, LynkColors.border),
                                onClick = { nav.push(Route.ServerDetail(server.id)) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StatusDot(connStates[server.id]?.phase ?: Phase.IDLE)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = server.name,
                                        fontSize = 12.5.sp,
                                        color = LynkColors.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 130.dp),
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        text = server.port.toString(),
                                        fontFamily = Mono,
                                        fontSize = 11.sp,
                                        color = LynkColors.textMuted,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ServiceIcon(app: AppState, service: Service) {
    val bitmap: Bitmap? = remember(service.id, service.iconVersion) {
        val file = app.store.iconFile(service.id)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(LynkColors.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = service.name.take(1).uppercase(),
                fontFamily = Mono,
                fontSize = 15.sp,
                color = LynkColors.textDim,
            )
        }
    }
}
