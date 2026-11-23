package com.p0lymatic.lynk.ssh

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.p0lymatic.lynk.data.AuthType
import com.p0lymatic.lynk.data.ChangedHostKeyException
import com.p0lymatic.lynk.data.HostKeyEntry
import com.p0lymatic.lynk.data.ServerConfig
import com.p0lymatic.lynk.data.Store
import com.p0lymatic.lynk.data.UnknownHostKeyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Owns every SSH session the app has open.
 *
 * A node is connected once and reused: the shell, the browser and the saved tunnels all share the
 * same session and add their own channels to it.
 */
class ConnectionManager(private val context: Context, private val store: Store) {

    enum class Phase { IDLE, CONNECTING, CONNECTED, ERROR }

    data class ConnState(
        val phase: Phase = Phase.IDLE,
        val error: String? = null,
        val since: Long = 0L,
    ) {
        val connected: Boolean get() = phase == Phase.CONNECTED
    }

    data class LocalForward(
        val id: String,
        val serverId: String,
        val localPort: Int,
        val remoteHost: String,
        val remotePort: Int,
        val tunnelId: String? = null,
        val label: String = "",
    )

    private val _states = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val states: StateFlow<Map<String, ConnState>> = _states.asStateFlow()

    private val _forwards = MutableStateFlow<Map<String, LocalForward>>(emptyMap())
    val forwards: StateFlow<Map<String, LocalForward>> = _forwards.asStateFlow()

    private val _tunnelErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val tunnelErrors: StateFlow<Map<String, String>> = _tunnelErrors.asStateFlow()

    private val _terminals = MutableStateFlow(0)
    val terminals: StateFlow<Int> = _terminals.asStateFlow()

    private val sessions = HashMap<String, Session>()
    private val mutex = Mutex()

    /** Called whenever something connects or disconnects, so the foreground service can react. */
    @Volatile
    var onActivityChanged: (() -> Unit)? = null

    fun connState(serverId: String): ConnState = _states.value[serverId] ?: ConnState()

    fun isConnected(serverId: String): Boolean = connState(serverId).connected

    fun activeCount(): Int =
        _states.value.values.count { it.phase == Phase.CONNECTED } + _forwards.value.size + _terminals.value

    private fun setState(serverId: String, state: ConnState) {
        _states.value = _states.value + (serverId to state)
        onActivityChanged?.invoke()
    }

    // ---- sessions ------------------------------------------------------------

    suspend fun ensureConnected(server: ServerConfig): Session = mutex.withLock {
        sessions[server.id]?.takeIf { it.isConnected }?.let { return it }
        withContext(Dispatchers.IO) { connectLocked(server) }
    }

    private fun connectLocked(server: ServerConfig): Session {
        setState(server.id, ConnState(Phase.CONNECTING))
        return try {
            val session = openSession(server)
            sessions[server.id] = session
            setState(server.id, ConnState(Phase.CONNECTED, since = System.currentTimeMillis()))
            session
        } catch (t: Throwable) {
            setState(server.id, ConnState(Phase.ERROR, error = describe(t)))
            throw t
        }
    }

    private fun openSession(server: ServerConfig): Session {
        val jsch = JSch()
        val repo = StoreHostKeyRepository(store)
        jsch.hostKeyRepository = repo

        val password = if (server.auth == AuthType.PASSWORD) {
            store.getSecret(server.id, Store.SecretKind.PASSWORD) ?: ""
        } else ""
        val keyBody = if (server.auth == AuthType.KEY) {
            store.getSecret(server.id, Store.SecretKind.KEY) ?: ""
        } else ""
        val passphrase = store.getSecret(server.id, Store.SecretKind.PASSPHRASE) ?: ""

        if (server.auth == AuthType.PASSWORD && password.isEmpty()) {
            throw IllegalStateException("Для ${server.name} не сохранён пароль")
        }
        if (server.auth == AuthType.KEY && keyBody.isEmpty()) {
            throw IllegalStateException("Для ${server.name} не сохранён приватный ключ")
        }

        if (server.auth == AuthType.KEY) {
            val keyFile = File(keysDir(), "${server.id}.pem")
            keyFile.writeText(keyBody)
            keyFile.setReadable(true, true)
            keyFile.setWritable(true, true)
            if (passphrase.isEmpty()) jsch.addIdentity(keyFile.absolutePath)
            else jsch.addIdentity(keyFile.absolutePath, passphrase.toByteArray())
        }

        val session = jsch.getSession(server.user, server.host, server.port)
        if (server.auth == AuthType.PASSWORD) session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        session.setConfig("MaxAuthTries", "3")

        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (t: Throwable) {
            repo.lastOffered?.let { offered ->
                val known = store.hostKey(offered.host, offered.port)
                when {
                    known == null -> throw UnknownHostKeyException(
                        offered.host, offered.port, offered.keyType, offered.fingerprint,
                    )
                    !known.fingerprint.equals(offered.fingerprint, true) -> throw ChangedHostKeyException(
                        offered.host, offered.port, offered.keyType, offered.fingerprint, known.fingerprint,
                    )
                }
            }
            throw t
        }

        session.setServerAliveInterval(20_000)
        session.setServerAliveCountMax(3)
        return session
    }

    private fun keysDir(): File = File(context.filesDir, "keys").apply { if (!exists()) mkdirs() }

    fun forgetKeyFile(serverId: String) {
        File(keysDir(), "$serverId.pem").delete()
    }

    suspend fun disconnect(serverId: String) = mutex.withLock {
        _forwards.value.values.filter { it.serverId == serverId }.forEach { closeForwardInternal(it) }
        sessions.remove(serverId)?.let { runCatching { it.disconnect() } }
        setState(serverId, ConnState(Phase.IDLE))
    }

    suspend fun disconnectAll() = mutex.withLock {
        _forwards.value.values.toList().forEach { closeForwardInternal(it) }
        sessions.values.forEach { runCatching { it.disconnect() } }
        sessions.clear()
        _states.value = _states.value.mapValues { ConnState(Phase.IDLE) }
        onActivityChanged?.invoke()
    }

    fun trustHostKey(entry: HostKeyEntry) = store.trustHostKey(entry)

    // ---- port forwards -------------------------------------------------------

    /**
     * Opens an SSH local port forward. With [localPort] = 0 the OS picks a free port, which is what
     * the built-in browser uses so that two services never fight over the same number.
     */
    suspend fun openForward(
        server: ServerConfig,
        remoteHost: String,
        remotePort: Int,
        localPort: Int = 0,
        tunnelId: String? = null,
        label: String = "",
    ): LocalForward = mutex.withLock {
        val session = sessions[server.id]?.takeIf { it.isConnected }
            ?: withContext(Dispatchers.IO) { connectLocked(server) }
        try {
            val bound = withContext(Dispatchers.IO) {
                session.setPortForwardingL(localPort, remoteHost, remotePort)
            }
            val forward = LocalForward(
                id = UUID.randomUUID().toString(),
                serverId = server.id,
                localPort = bound,
                remoteHost = remoteHost,
                remotePort = remotePort,
                tunnelId = tunnelId,
                label = label,
            )
            _forwards.value = _forwards.value + (forward.id to forward)
            if (tunnelId != null) _tunnelErrors.value = _tunnelErrors.value - tunnelId
            onActivityChanged?.invoke()
            forward
        } catch (t: Throwable) {
            if (tunnelId != null) _tunnelErrors.value = _tunnelErrors.value + (tunnelId to describe(t))
            throw t
        }
    }

    fun closeForward(forward: LocalForward) {
        closeForwardInternal(forward)
        onActivityChanged?.invoke()
    }

    private fun closeForwardInternal(forward: LocalForward) {
        runCatching { sessions[forward.serverId]?.delPortForwardingL(forward.localPort) }
        _forwards.value = _forwards.value - forward.id
    }

    fun closeForwards(predicate: (LocalForward) -> Boolean) {
        _forwards.value.values.filter(predicate).forEach { closeForward(it) }
    }

    fun setTunnelError(tunnelId: String, message: String?) {
        _tunnelErrors.value = if (message == null) _tunnelErrors.value - tunnelId
        else _tunnelErrors.value + (tunnelId to message)
    }

    // ---- terminals -----------------------------------------------------------

    suspend fun openTerminal(server: ServerConfig, cols: Int, rows: Int): TerminalSession {
        val session = ensureConnected(server)
        _terminals.value = _terminals.value + 1
        onActivityChanged?.invoke()
        return try {
            TerminalSession(session, cols, rows) { _terminals.value = (_terminals.value - 1).coerceAtLeast(0) }
        } catch (t: Throwable) {
            _terminals.value = (_terminals.value - 1).coerceAtLeast(0)
            onActivityChanged?.invoke()
            throw t
        }
    }

    /**
     * Runs one non-interactive command over the shared session and returns stdout.
     * Used by the monitor: no PTY, so output arrives without carriage returns.
     */
    suspend fun exec(server: ServerConfig, command: String, timeoutMs: Int = 20_000): String {
        val session = ensureConnected(server)
        return withContext(Dispatchers.IO) {
            val channel = session.openChannel("exec") as ChannelExec
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            try {
                channel.setCommand(command)
                channel.setInputStream(null)
                channel.setErrStream(err, true)
                channel.outputStream = out
                channel.connect(timeoutMs)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                    if (!channel.isConnected) break
                    Thread.sleep(30)
                }
                val text = out.toString("UTF-8")
                if (text.isBlank() && channel.exitStatus != 0) {
                    throw IOException("команда вернула код ${channel.exitStatus}: ${err.toString("UTF-8").trim().take(200)}")
                }
                text
            } finally {
                runCatching { channel.disconnect() }
            }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000

        fun describe(t: Throwable): String {
            val raw = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            return when {
                raw.contains("Auth fail", true) || raw.contains("auth cancel", true) ->
                    "Аутентификация не прошла: проверь логин, пароль или ключ"
                raw.contains("Connection refused", true) -> "Соединение отклонено (SSH-сервер не слушает порт?)"
                raw.contains("timed out", true) || raw.contains("timeout", true) ->
                    "Таймаут: узел не отвечает по SSH"
                raw.contains("UnknownHost", true) -> "Хост не найден — проверь адрес"
                raw.contains("Network is unreachable", true) -> "Сеть недоступна"
                raw.contains("invalid privatekey", true) -> "Не удалось прочитать приватный ключ"
                raw.contains("reject HostKey", true) -> "Ключ сервера не принят"
                raw.contains("Address already in use", true) -> "Локальный порт уже занят"
                raw.contains("forwarding", true) -> "Сервер не разрешил проброс порта ($raw)"
                else -> raw
            }
        }
    }
}
