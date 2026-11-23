package com.p0lymatic.lynk.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.data.Service
import com.p0lymatic.lynk.ssh.ConnectionManager.Phase
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono

@Composable
fun HomeScreen(app: AppState, nav: Navigator) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val connStates by app.connections.states.collectAsStateWithLifecycle()

    var port by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    val selected = data.servers.firstOrNull { it.id == selectedId }

    LaunchedEffect(data.servers) {
        if (selectedId.isBlank() || data.servers.none { it.id == selectedId }) {
            selectedId = data.servers.firstOrNull()?.id ?: ""
        }
    }

    fun openPort() {
        val number = port.trim().toIntOrNull()
        if (number == null || number !in 1..65535) {
            app.notify("Порт должен быть 1..65535")
            return
        }
        val server = selected ?: return
        nav.push(Route.Browser(server.id, null, number, "http", "/", "Порт $number"))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LYNK",
                    fontFamily = Mono,
                    fontSize = 26.sp,
                    letterSpacing = 6.sp,
                    fontWeight = FontWeight.Medium,
                    color = LynkColors.text,
                    modifier = Modifier.weight(1f),
                )
                Text("MVP", fontFamily = Mono, fontSize = 10.sp, color = LynkColors.textMuted)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${data.servers.size} узлов · ${data.services.size} сервисов",
                fontFamily = Mono,
                fontSize = 11.sp,
                color = LynkColors.textMuted,
            )
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            LynkCardBorder {
                SectionLabel("Открыть порт")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LynkField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                        label = "Порт",
                        modifier = Modifier.weight(1f),
                        placeholder = "8080",
                        mono = true,
                        keyboardType = KeyboardType.Number,
                    )
                    Spacer(Modifier.width(10.dp))
                    Box {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = LynkColors.surface,
                            border = BorderStroke(1.dp, LynkColors.border),
                            onClick = { if (data.servers.isNotEmpty()) menuOpen = true },
                        ) {
                            Row(
                                modifier = Modifier.height(56.dp).padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = selected?.name ?: "узел",
                                    fontSize = 13.sp,
                                    color = if (selected != null) LynkColors.text else LynkColors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 96.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Выбрать узел",
                                    tint = LynkColors.textMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
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
                                    onClick = {
                                        selectedId = server.id
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton(
                    text = "Открыть",
                    onClick = { openPort() },
                    enabled = port.isNotBlank() && selected != null,
                )
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
                    text = "Добавь узел и сервис — они появятся здесь",
                    actionText = "Добавить узел",
                ) { nav.push(Route.ServerEdit(null)) }
            } else {
                val serversById = data.servers.associateBy { it.id }
                val services = data.services.sortedWith(
                    compareByDescending<Service> { it.favorite }.thenBy { it.name.lowercase() },
                )
                services.forEach { service ->
                    val server = serversById[service.serverId]
                    LynkCard(
                        onClick = {
                            nav.push(
                                Route.Browser(
                                    service.serverId,
                                    service.id,
                                    service.port,
                                    service.scheme,
                                    service.normalizedPath,
                                    service.name,
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
        }

        Spacer(Modifier.height(24.dp))
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