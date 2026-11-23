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
    /** Machine-readable OS id: ubuntu, debian, raspbian, arch, alpine, fedora, freebsd, darwin, linux… */
    val osId: String = "",
    /** Human-readable OS name from /etc/os-release, e.g. "Debian GNU/Linux 12 (bookworm)". */
    val osName: String = "",
    val osArch: String = "",
    val osCheckedAt: Long = 0L,
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

/** What a monitor tile can show. */
@Serializable
enum class MetricKind { CPU, RAM, SWAP, DISK, LOAD, TEMP, NET, UPTIME }

/** How a metric is drawn. */
@Serializable
enum class GaugeStyle { RING, BAR, TEXT }

@Serializable
data class MetricPref(
    val kind: MetricKind,
    val style: GaugeStyle = GaugeStyle.RING,
    val visible: Boolean = true,
    val showValue: Boolean = true,
    val showLabel: Boolean = true,
)

@Serializable
data class MonitorSettings(
    val enabled: Boolean = true,
    val autoConnect: Boolean = true,
    val refreshSec: Int = 5,
    val columns: Int = 3,
    val metrics: List<MetricPref> = defaultMetricPrefs(),
) {
    companion object {
        fun defaultMetricPrefs(): List<MetricPref> = listOf(
            MetricPref(MetricKind.CPU, GaugeStyle.RING),
            MetricPref(MetricKind.RAM, GaugeStyle.RING),
            MetricPref(MetricKind.DISK, GaugeStyle.RING),
            MetricPref(MetricKind.LOAD, GaugeStyle.BAR),
            MetricPref(MetricKind.TEMP, GaugeStyle.TEXT, visible = false),
            MetricPref(MetricKind.NET, GaugeStyle.BAR, visible = false),
            MetricPref(MetricKind.UPTIME, GaugeStyle.TEXT, visible = false),
            MetricPref(MetricKind.SWAP, GaugeStyle.BAR, visible = false),
        )
    }
}

@Serializable
data class TerminalSettings(
    val fontId: String = "system",
    val fontSize: Float = 11f,
    val schemeId: String = "grey",
    /** block, underline or bar */
    val cursorStyle: String = "block",
    val blink: Boolean = true,
    /** history or mouse */
    val scrollMode: String = "history",
)

@Serializable
data class AppData(
    val servers: List<ServerConfig> = emptyList(),
    val services: List<Service> = emptyList(),
    val tunnels: List<Tunnel> = emptyList(),
    val hostKeys: List<HostKeyEntry> = emptyList(),
    val monitor: MonitorSettings = MonitorSettings(),
    val terminal: TerminalSettings = TerminalSettings(),
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