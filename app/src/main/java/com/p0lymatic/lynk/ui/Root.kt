package com.p0lymatic.lynk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p0lymatic.lynk.R
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono

@Composable
fun LynkRoot(app: AppState) {
    val nav = remember { Navigator() }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        app.messages.collect { message -> snackbarHost.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        app.store.state.value.tunnels.filter { it.autoStart }.forEach { app.startTunnel(it) }
    }

    BackHandler(enabled = nav.canPop) { nav.pop() }

    val route = nav.current

    Scaffold(
        containerColor = LynkColors.bg,
        snackbarHost = {
            SnackbarHost(snackbarHost) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = LynkColors.surfaceHigh,
                    contentColor = LynkColors.text,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                )
            }
        },
        bottomBar = {
            if (route is Route.Tab) {
                LynkBottomBar(current = route.tab, onSelect = nav::selectTab)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                is Route.Tab -> when (route.tab) {
                    TabId.HOME -> HomeScreen(app, nav)
                    TabId.SERVERS -> ServersScreen(app, nav)
                    TabId.TUNNELS -> TunnelsScreen(app, nav)
                    TabId.SETTINGS -> SettingsScreen(app, nav)
                }

                is Route.ServerDetail -> ServerDetailScreen(app, nav, route.serverId)
                is Route.ServerEdit -> ServerEditScreen(app, nav, route.serverId)

                is Route.ServiceEdit -> ServiceEditScreen(app, nav, route.serviceId, route.serverId)
                is Route.TunnelEdit -> TunnelEditScreen(app, nav, route.tunnelId, route.serverId)

                is Route.Terminal -> TerminalScreen(app, nav, route.serverId)

                is Route.Browser -> BrowserScreen(
                    app = app,
                    nav = nav,
                    serverId = route.serverId,
                    serviceId = route.serviceId,
                    port = route.port,
                    scheme = route.scheme,
                    path = route.path,
                    title = route.title,
                    host = route.host,
                )
            }
        }
    }

    app.pendingHostKey?.let { pending ->
        HostKeyDialog(
            pending = pending,
            onAccept = app::acceptPendingHostKey,
            onDismiss = app::dismissPendingHostKey,
        )
    }
}

@Composable
private fun LynkBottomBar(current: TabId, onSelect: (TabId) -> Unit) {
    NavigationBar(
        containerColor = LynkColors.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TabId.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        painter = painterResource(tab.iconRes()),
                        contentDescription = tab.title(),
                        modifier = Modifier.size(19.dp),
                    )
                },
                label = { Text(tab.title(), fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = LynkColors.text,
                    selectedTextColor = LynkColors.text,
                    unselectedIconColor = LynkColors.textMuted,
                    unselectedTextColor = LynkColors.textMuted,
                    indicatorColor = LynkColors.surfaceHigh,
                ),
            )
        }
    }
}

private fun TabId.title(): String = when (this) {
    TabId.HOME -> "Дом"
    TabId.SERVERS -> "Узлы"
    TabId.TUNNELS -> "Туннели"
    TabId.SETTINGS -> "Настройки"
}

private fun TabId.iconRes(): Int = when (this) {
    TabId.HOME -> R.drawable.ic_tab_home
    TabId.SERVERS -> R.drawable.ic_tab_servers
    TabId.TUNNELS -> R.drawable.ic_tab_tunnels
    TabId.SETTINGS -> R.drawable.ic_tab_settings
}

@Composable
private fun HostKeyDialog(
    pending: AppState.PendingHostKey,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LynkColors.surfaceHigh,
        titleContentColor = LynkColors.text,
        textContentColor = LynkColors.textDim,
        title = {
            Text(
                if (pending.changed) "Ключ сервера изменился" else "Новый ключ сервера",
                fontSize = 16.sp,
            )
        },
        text = {
            Column {
                Text(
                    "${pending.host}:${pending.port} · ${pending.keyType}",
                    fontFamily = Mono,
                    fontSize = 11.5.sp,
                    color = LynkColors.textMuted,
                )
                Spacer(Modifier.height(10.dp))
                Text(pending.fingerprint, fontFamily = Mono, fontSize = 12.sp, color = LynkColors.text)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (pending.changed) {
                        "Раньше был ${pending.knownFingerprint}. Это может быть переустановка сервера — " +
                            "или подмена. Принимай только если уверен."
                    } else {
                        "Сверь отпечаток с самим сервером: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub. " +
                            "Если строки не совпадают — не подключайся."
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(
                    if (pending.changed) "Принять новый" else "Доверять",
                    color = if (pending.changed) LynkColors.err else LynkColors.text,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = LynkColors.textDim) }
        },
    )
}