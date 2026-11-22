package com.p0lymatic.lynk.data

import kotlinx.serialization.Serializable

@Serializable
enum class AuthType { PASSWORD, KEY }

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: AuthType = AuthType.PASSWORD,
    val note: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class Service(
    val id: String,
    val serverId: String,
    val name: String,
    val host: String = "127.0.0.1",
    val port: Int,
    val scheme: String = "http",
    val path: String = "/",
    val favorite: Boolean = true,
    val iconVersion: Int = 0,
    val createdAt: Long = 0L,
) {
    val normalizedPath: String get() = if (path.isBlank()) "/" else if (path.startsWith("/")) path else "/$path"
    val target: String get() = "$host:$port"
}

@Serializable
data class Tunnel(
    val id: String,
    val serverId: String,
    val name: String,
    val localPort: Int,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int,
    val autoStart: Boolean = false,
)

@Serializable
data class HostKeyEntry(
    val host: String,
    val port: Int,
    val type: String,
    val fingerprint: String,
    val addedAt: Long = 0L,
) {
    val label: String get() = "$host:$port"
}

@Serializable
data class AppData(
    val servers: List<ServerConfig> = emptyList(),
    val services: List<Service> = emptyList(),
    val tunnels: List<Tunnel> = emptyList(),
    val hostKeys: List<HostKeyEntry> = emptyList(),
)

/** Thrown when the server offers a key we have never seen. */
class UnknownHostKeyException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
) : Exception("Неизвестный ключ сервера $host:$port ($keyType) $fingerprint")

/** Thrown when the server key changed under a known host. */
class ChangedHostKeyException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val knownFingerprint: String,
) : Exception("Ключ сервера $host:$port изменился: было $knownFingerprint, стало $fingerprint")