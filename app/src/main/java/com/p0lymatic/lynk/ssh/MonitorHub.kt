package com.p0lymatic.lynk.ssh

import com.p0lymatic.lynk.data.ServerConfig
import com.p0lymatic.lynk.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Polls nodes for CPU/RAM/disk/network and keeps the latest snapshot per node id.
 *
 * Only nodes someone is looking at are polled: [watch] starts a loop, [unwatch] stops it.
 * A failed tick is remembered in [errors] and does not kill the loop.
 */
class MonitorHub(
    private val store: Store,
    private val connections: ConnectionManager,
    private val scope: CoroutineScope,
    private val ensureConnected: suspend (ServerConfig) -> Boolean,
) {
    private val collector = MetricsCollector(connections)
    private val tickMutex = Mutex()

    private val _samples = MutableStateFlow<Map<String, NodeMetrics>>(emptyMap())
    val samples: StateFlow<Map<String, NodeMetrics>> = _samples.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val jobs = HashMap<String, Job>()

    /** Starts (or keeps) the polling loop for a node. Safe to call from composition. */
    fun watch(serverId: String) {
        val existing = jobs[serverId]
        if (existing?.isActive == true) return
        jobs[serverId] = scope.launch {
            while (isActive) {
                tick(serverId)
                val seconds = store.state.value.monitor.refreshSec.coerceIn(2, 120)
                delay(seconds * 1000L)
            }
        }
    }

    /** Stops polling and drops the snapshot, so a stale gauge is never shown. */
    fun unwatch(serverId: String) {
        jobs.remove(serverId)?.cancel()
        _samples.update { it - serverId }
        _errors.update { it - serverId }
        _busy.update { it - serverId }
    }

    fun unwatchAll() {
        jobs.keys.toList().forEach { unwatch(it) }
    }

    fun refreshNow(serverId: String) {
        scope.launch { tick(serverId) }
    }

    private suspend fun tick(serverId: String) {
        val server = store.server(serverId) ?: return
        if (!store.state.value.monitor.enabled) return
        if (!tickMutex.tryLock()) return
        try {
            _busy.update { it + serverId }
            if (!connections.isConnected(serverId)) {
                if (!store.state.value.monitor.autoConnect) {
                    _errors.update { it + (serverId to "Нет соединения") }
                    return
                }
                val ok = ensureConnected(server)
                if (!ok) {
                    _errors.update { it + (serverId to "Не удалось подключиться") }
                    return
                }
            }
            val metrics = collector.sample(server)
            _samples.update { it + (serverId to metrics) }
            _errors.update { it - serverId }

            val stale = System.currentTimeMillis() - server.osCheckedAt > 24 * 3600 * 1000L
            if (server.osId.isBlank() || stale) {
                runCatching { collector.detectOs(server) }.onSuccess { os ->
                    if (os.id.isNotBlank() && os.id != "unknown") {
                        store.updateServerOs(serverId, os.id, os.name, os.arch)
                    }
                }
            }
        } catch (t: Throwable) {
            _errors.update { it + (serverId to ConnectionManager.describe(t)) }
        } finally {
            _busy.update { it - serverId }
            runCatching { tickMutex.unlock() }
        }
    }
}
