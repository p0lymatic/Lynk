package com.p0lymatic.lynk.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Single source of truth for nodes, services, tunnels and trusted host keys.
 * Small enough that a JSON file is nicer than a database: one file, atomic writes,
 * trivial to inspect or export.
 */
class Store(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dataFile = File(context.filesDir, "lynk.json")
    private val secrets = context.getSharedPreferences("lynk_secrets", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AppData())
    val state: StateFlow<AppData> = _state.asStateFlow()

    init {
        _state.value = read()
    }

    private fun read(): AppData = runCatching {
        if (!dataFile.exists()) return AppData()
        json.decodeFromString<AppData>(dataFile.readText())
    }.getOrElse { AppData() }

    private fun write(data: AppData) {
        val tmp = File(context.filesDir, "lynk.json.tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), data))
        if (!tmp.renameTo(dataFile)) {
            dataFile.writeText(tmp.readText())
            tmp.delete()
        }
        _state.value = data
    }

    private fun mutate(block: (AppData) -> AppData) = synchronized(this) { write(block(_state.value)) }

    // ---- servers -------------------------------------------------------------

    fun server(id: String): ServerConfig? = _state.value.servers.firstOrNull { it.id == id }

    fun upsertServer(server: ServerConfig) = mutate { data ->
        val exists = data.servers.any { it.id == server.id }
        data.copy(
            servers = if (exists) data.servers.map { if (it.id == server.id) server else it }
            else data.servers + server,
        )
    }

    fun deleteServer(id: String) = mutate { data ->
        data.copy(
            servers = data.servers.filterNot { it.id == id },
            services = data.services.filterNot { it.serverId == id },
            tunnels = data.tunnels.filterNot { it.serverId == id },
        )
    }

    // ---- services ------------------------------------------------------------

    fun service(id: String): Service? = _state.value.services.firstOrNull { it.id == id }

    fun servicesFor(serverId: String): List<Service> =
        _state.value.services.filter { it.serverId == serverId }.sortedBy { it.name.lowercase() }

    fun upsertService(service: Service) = mutate { data ->
        val exists = data.services.any { it.id == service.id }
        data.copy(
            services = if (exists) data.services.map { if (it.id == service.id) service else it }
            else data.services + service,
        )
    }

    fun deleteService(id: String) = mutate { data ->
        data.copy(services = data.services.filterNot { it.id == id }).also { iconFile(id).delete() }
    }

    fun bumpIcon(serviceId: String) = mutate { data ->
        data.copy(services = data.services.map { if (it.id == serviceId) it.copy(iconVersion = it.iconVersion + 1) else it })
    }

    fun iconFile(serviceId: String): File {
        val dir = File(context.filesDir, "icons")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$serviceId.png")
    }

    // ---- tunnels -------------------------------------------------------------

    fun tunnel(id: String): Tunnel? = _state.value.tunnels.firstOrNull { it.id == id }

    fun upsertTunnel(tunnel: Tunnel) = mutate { data ->
        val exists = data.tunnels.any { it.id == tunnel.id }
        data.copy(
            tunnels = if (exists) data.tunnels.map { if (it.id == tunnel.id) tunnel else it }
            else data.tunnels + tunnel,
        )
    }

    fun deleteTunnel(id: String) = mutate { data -> data.copy(tunnels = data.tunnels.filterNot { it.id == id }) }

    // ---- host keys -----------------------------------------------------------

    fun hostKey(host: String, port: Int): HostKeyEntry? =
        _state.value.hostKeys.firstOrNull { it.host.equals(host, ignoreCase = true) && it.port == port }

    fun trustHostKey(entry: HostKeyEntry) = mutate { data ->
        data.copy(
            hostKeys = data.hostKeys.filterNot {
                it.host.equals(entry.host, ignoreCase = true) && it.port == entry.port
            } + entry,
        )
    }

    fun forgetHostKey(host: String, port: Int) = mutate { data ->
        data.copy(hostKeys = data.hostKeys.filterNot { it.host.equals(host, ignoreCase = true) && it.port == port })
    }

    // ---- secrets -------------------------------------------------------------

    private fun secretKey(serverId: String, kind: String) = "${kind}_$serverId"

    fun putSecret(serverId: String, kind: SecretKind, plain: String?) {
        val editor = secrets.edit()
        when {
            plain.isNullOrEmpty() -> editor.remove(secretKey(serverId, kind.id))
            else -> SecureVault.encrypt(plain)?.let { editor.putString(secretKey(serverId, kind.id), it) }
        }
        editor.apply()
    }

    fun getSecret(serverId: String, kind: SecretKind): String? =
        SecureVault.decrypt(secrets.getString(secretKey(serverId, kind.id), null))

    fun hasSecret(serverId: String, kind: SecretKind): Boolean =
        secrets.contains(secretKey(serverId, kind.id))

    fun clearSecrets(serverId: String) {
        val editor = secrets.edit()
        SecretKind.entries.forEach { editor.remove(secretKey(serverId, it.id)) }
        editor.apply()
    }

    enum class SecretKind(val id: String) { PASSWORD("password"), KEY("key"), PASSPHRASE("passphrase") }
}