package com.p0lymatic.lynk.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.p0lymatic.lynk.data.ChangedHostKeyException
import com.p0lymatic.lynk.data.HostKeyEntry
import com.p0lymatic.lynk.data.ServerConfig
import com.p0lymatic.lynk.data.Service
import com.p0lymatic.lynk.data.Store
import com.p0lymatic.lynk.data.Tunnel
import com.p0lymatic.lynk.data.UnknownHostKeyException
import com.p0lymatic.lynk.ssh.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * UI-facing façade: one place that knows how to turn "open this service" into
 * "connect if needed, open a forward, show a readable error".
 */
class AppState(
    val context: Context,
    val store: Store,
    val connections: ConnectionManager,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    var pendingHostKey by mutableStateOf<PendingHostKey?>(null)
        private set

    var lastServerId by mutableStateOf<String?>(null)
        private set

    data class PendingHostKey(
        val serverName: String,
        val host: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        val changed: Boolean,
        val knownFingerprint: String?,
        val retry: suspend () -> Unit,
    )

    fun notify(text: String) {
        _messages.tryEmit(text)
    }

    // ---- host keys -----------------------------------------------------------

    private fun present(server: ServerConfig, failure: Throwable, retry: suspend () -> Unit) {
        when (failure) {
            is UnknownHostKeyException -> pendingHostKey = PendingHostKey(
                serverName = server.name,
                host = failure.host,
                port = failure.port,
                keyType = failure.keyType,
                fingerprint = failure.fingerprint,
                changed = false,
                knownFingerprint = null,
                retry = retry,
            )
            is ChangedHostKeyException -> pendingHostKey = PendingHostKey(
                serverName = server.name,
                host = failure.host,
                port = failure.port,
                keyType = failure.keyType,
                fingerprint = failure.fingerprint,
                changed = true,
                knownFingerprint = failure.knownFingerprint,
                retry = retry,
            )
            else -> notify(ConnectionManager.describe(failure))
        }
    }

    fun acceptPendingHostKey() {
        val pending = pendingHostKey ?: return
        store.trustHostKey(
            HostKeyEntry(
                host = pending.host,
                port = pending.port,
                type = pending.keyType,
                fingerprint = pending.fingerprint,
                addedAt = System.currentTimeMillis(),
            ),
        )
        pendingHostKey = null
        scope.launch { pending.retry() }
    }

    fun dismissPendingHostKey() {
        pendingHostKey = null
    }

    // ---- connections ---------------------------------------------------------

    /**
     * Connects to a node and keeps [retry] so that accepting a new host key can simply repeat
     * the action the user asked for.
     */
    suspend fun connect(server: ServerConfig, retry: suspend () -> Unit = {}): Boolean {
        return try {
            connections.ensureConnected(server)
            lastServerId = server.id
            true
        } catch (t: Throwable) {
            present(server, t) { retry.invoke() }
            false
        }
    }

    fun disconnect(serverId: String) {
        scope.launch {
            connections.disconnect(serverId)
            notify("Отключено")
        }
    }

    fun serverOf(serverId: String): ServerConfig? = store.server(serverId)

    /** Opens a service through the node, picking a free local port. */
    suspend fun openService(
        service: Service,
        localPort: Int = 0,
    ): ConnectionManager.LocalForward? {
        val server = store.server(service.serverId) ?: run {
            notify("Узел для сервиса не найден")
            return null
        }
        if (!connect(server)) return null
        return try {
            connections.openForward(
                server = server,
                remoteHost = service.host,
                remotePort = service.port,
                localPort = localPort,
                label = service.name,
            )
        } catch (t: Throwable) {
            notify(ConnectionManager.describe(t))
            null
        }
    }

    /** Opens an arbitrary port on a node without creating a saved service. */
    suspend fun openPort(
        server: ServerConfig,
        host: String = "127.0.0.1",
        port: Int,
        scheme: String = "http",
        path: String = "/",
    ): ConnectionManager.LocalForward? {
        if (!connect(server)) return null
        return try {
            connections.openForward(server, host, port, label = "порт $port")
        } catch (t: Throwable) {
            notify(ConnectionManager.describe(t))
            null
        }
    }

    // ---- tunnels -------------------------------------------------------------

    fun isTunnelActive(tunnelId: String): Boolean =
        connections.forwards.value.values.any { it.tunnelId == tunnelId }

    fun tunnelForward(tunnelId: String): ConnectionManager.LocalForward? =
        connections.forwards.value.values.firstOrNull { it.tunnelId == tunnelId }

    fun startTunnel(tunnel: Tunnel) {
        if (isTunnelActive(tunnel.id)) return
        val server = store.server(tunnel.serverId) ?: run {
            notify("Узел туннеля не найден")
            return
        }
        scope.launch {
            if (!connect(server)) {
                connections.setTunnelError(tunnel.id, "Нет соединения с ${server.name}")
                return@launch
            }
            try {
                connections.openForward(
                    server = server,
                    remoteHost = tunnel.remoteHost,
                    remotePort = tunnel.remotePort,
                    localPort = tunnel.localPort,
                    tunnelId = tunnel.id,
                    label = tunnel.name,
                )
                notify("Туннель «${tunnel.name}» поднят на ${tunnel.localPort}")
            } catch (t: Throwable) {
                connections.setTunnelError(tunnel.id, ConnectionManager.describe(t))
                notify(ConnectionManager.describe(t))
            }
        }
    }

    fun stopTunnel(tunnel: Tunnel) {
        connections.closeForwards { it.tunnelId == tunnel.id }
        connections.setTunnelError(tunnel.id, null)
    }

    // ---- misc ----------------------------------------------------------------

    fun renameServiceShort(service: Service): String = service.name.ifBlank { "порт ${service.port}" }
}