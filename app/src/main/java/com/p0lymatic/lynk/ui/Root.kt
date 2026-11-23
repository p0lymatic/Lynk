package com.p0lymatic.lynk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.BuildConfig
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.launch

@Composable
fun LynkRoot(app: AppState) {
    val nav = remember { Navigator() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        app.messages.collect { message -> snackbarHost.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        app.store.state.value.tunnels.filter { it.autoStart }.forEach { app.startTunnel(it) }
    }

    val route = nav.current
    var lastTab by rememberSaveable { mutableStateOf(TabId.HOME) }
    val currentTab = (route as? Route.Tab)?.tab ?: lastTab
    LaunchedEffect(route) { (route as? Route.Tab)?.let { lastTab = it.tab } }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = !drawerState.isOpen && nav.canPop) { nav.pop() }

    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
    val selectTab: (TabId) -> Unit = { tab ->
        nav.selectTab(tab)
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            val connStates by app.connections.states.collectAsStateWithLifecycle()
            val forwards by app.connections.forwards.collectAsStateWithLifecycle()
            val terminals by app.connections.terminals.collectAsStateWithLifecycle()
            val activeCount = remember(connStates, forwards, terminals) { app.connections.activeCount() }
            LynkDrawerContent(
                current = currentTab,
                activeCount = activeCount,
                versionName = BuildConfig.VERSION_NAME,
                onSelect = selectTab,
            )
        },
    ) {
        Scaffold(
            containerColor = LynkColors.bg,
            snackbarHost = {
                SnackbarHost(snackbarHost) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = LynkColors.surfaceHigh,
                        contentColor = LynkColors.text,
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(
                    targetState = nav.current to nav.depth,
                    transitionSpec = {
                        val forward = targetState.second >= initialState.second
                        if (targetState.first is Route.Tab && initialState.first is Route.Tab) {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        } else if (forward) {
                            (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 5 } +
                                fadeIn(tween(220)))
                                .togetherWith(
                                    slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 8 } +
                                        fadeOut(tween(160)),
                                )
                        } else {
                            (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 5 } +
                                fadeIn(tween(220)))
                                .togetherWith(
                                    slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 8 } +
                                        fadeOut(tween(160)),
                                )
                        }
                    },
                    label = "route",
                ) { (destination, _) ->
                    when (destination) {
                        is Route.Tab -> when (destination.tab) {
                            TabId.HOME -> HomeScreen(app, nav, openMenu)
                            TabId.SERVERS -> ServersScreen(app, nav, openMenu)
                            TabId.MONITOR -> MonitoringScreen(app, nav, openMenu)
                            TabId.TUNNELS -> TunnelsScreen(app, nav, openMenu)
                            TabId.SETTINGS -> SettingsScreen(app, nav, openMenu)
                        }

                        is Route.MonitorSettings -> MonitorSettingsScreen(app, nav)

                        is Route.ServerDetail -> ServerDetailScreen(app, nav, destination.serverId)
                        is Route.ServerEdit -> ServerEditScreen(app, nav, destination.serverId)

                        is Route.ServiceEdit ->
                            ServiceEditScreen(app, nav, destination.serviceId, destination.serverId)

                        is Route.TunnelEdit ->
                            TunnelEditScreen(app, nav, destination.tunnelId, destination.serverId)

                        is Route.Terminal -> TerminalScreen(app, nav, destination.serverId)

                        is Route.Browser -> BrowserScreen(
                            app = app,
                            nav = nav,
                            serverId = destination.serverId,
                            serviceId = destination.serviceId,
                            port = destination.port,
                            scheme = destination.scheme,
                            path = destination.path,
                            title = destination.title,
                            host = destination.host,
                        )
                    }
                }
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
