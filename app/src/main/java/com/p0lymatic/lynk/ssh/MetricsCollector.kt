package com.p0lymatic.lynk.ssh

import com.p0lymatic.lynk.data.ServerConfig

/** One point-in-time snapshot of a node, as far as the monitor needs it. */
data class NodeMetrics(
    val cpuPercent: Float? = null,
    val cpuCount: Int? = null,
    val ramPercent: Float? = null,
    val ramUsedMb: Int? = null,
    val ramTotalMb: Int? = null,
    val swapPercent: Float? = null,
    val diskPercent: Float? = null,
    val diskUsedGb: Float? = null,
    val diskTotalGb: Float? = null,
    val load1: Float? = null,
    val load5: Float? = null,
    val load15: Float? = null,
    val tempC: Float? = null,
    val uptimeSec: Long? = null,
    val rxBps: Long? = null,
    val txBps: Long? = null,
    val at: Long = System.currentTimeMillis(),
) {
    val loadPercent: Float?
        get() = load1?.let { load -> (load / (cpuCount ?: 1).coerceAtLeast(1) * 100f).coerceIn(0f, 100f) }

    val hasAny: Boolean
        get() = cpuPercent != null || ramPercent != null || diskPercent != null
}

/** What the node says about itself. */
data class OsInfo(val id: String, val name: String, val arch: String)

/**
 * Parses the monitor shell command output. Kept pure so it can be tested on a fixture.
 */
object MetricsParser {

    private val sectionRe = Regex("^@@([A-Z0-9]+)\\s*$")

    private fun sections(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, StringBuilder>()
        var current: String? = null
        for (line in raw.lines()) {
            val match = sectionRe.find(line.trim())
            if (match != null) {
                current = match.groupValues[1]
                result.getOrPut(current) { StringBuilder() }
            } else {
                current?.let { result[it]!!.appendLine(line) }
            }
        }
        return result.mapValues { it.value.toString() }
    }

    /** `cpu  user nice system idle iowait irq softirq steal guest guest_nice` */
    private fun cpuTimes(line: String?): Pair<Long, Long>? {
        val tokens = line?.trim()?.split(Regex("\\s+")) ?: return null
        if (tokens.size < 5 || tokens[0] != "cpu") return null
        val values = tokens.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val total = values.take(8).sum()
        val idle = (values.getOrNull(3) ?: 0L) + (values.getOrNull(4) ?: 0L)
        return total to idle
    }

    private fun cpuPercent(first: String?, second: String?): Float? {
        val a = cpuTimes(first) ?: return null
        val b = cpuTimes(second) ?: return null
        val dTotal = b.first - a.first
        val dIdle = b.second - a.second
        if (dTotal <= 0L) return null
        val busy = 1f - (dIdle.toFloat() / dTotal.toFloat())
        return (busy * 100f).coerceIn(0f, 100f)
    }

    private fun memKb(block: String?): Map<String, Long> {
        val map = HashMap<String, Long>()
        block?.lines()?.forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
            if (value != null) map[key] = value
        }
        return map
    }

    private fun netTotals(block: String?): Pair<Long, Long>? {
        var rx = 0L
        var tx = 0L
        var seen = false
        block?.lines()?.forEach { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            if (tokens.size < 10) return@forEach
            val name = tokens[0]
            if (name == "lo" || name.isBlank()) return@forEach
            val rxBytes = tokens[1].toLongOrNull() ?: return@forEach
            val txBytes = tokens[9].toLongOrNull() ?: return@forEach
            rx += rxBytes
            tx += txBytes
            seen = true
        }
        return if (seen) rx to tx else null
    }

    fun parseMetrics(raw: String, now: Long = System.currentTimeMillis()): NodeMetrics {
        val s = sections(raw)

        val cpu = cpuPercent(s["STAT1"], s["STAT2"])
        val mem = memKb(s["MEM"])
        val memTotal = mem["MemTotal"]
        val memAvailable = mem["MemAvailable"] ?: mem["MemFree"]
        val ramPercent = if (memTotal != null && memTotal > 0 && memAvailable != null) {
            ((memTotal - memAvailable).toFloat() / memTotal.toFloat() * 100f).coerceIn(0f, 100f)
        } else {
            null
        }
        val ramUsedMb = if (memTotal != null && memAvailable != null) {
            ((memTotal - memAvailable) / 1024).toInt()
        } else {
            null
        }
        val swapTotal = mem["SwapTotal"] ?: 0L
        val swapPercent = if (swapTotal > 0L) {
            val free = mem["SwapFree"] ?: 0L
            ((swapTotal - free).toFloat() / swapTotal.toFloat() * 100f).coerceIn(0f, 100f)
        } else {
            null
        }

        val dfLine = s["DF"]?.lines()?.firstOrNull { it.isNotBlank() }
        val dfTokens = dfLine?.trim()?.split(Regex("\\s+")).orEmpty()
        val diskUsedKb = dfTokens.getOrNull(2)?.toLongOrNull()
        val diskAvailKb = dfTokens.getOrNull(3)?.toLongOrNull()
        val diskPercent = if (diskUsedKb != null && diskAvailKb != null && (diskUsedKb + diskAvailKb) > 0) {
            (diskUsedKb.toFloat() / (diskUsedKb + diskAvailKb).toFloat() * 100f).coerceIn(0f, 100f)
        } else {
            null
        }
        val diskUsedGb = diskUsedKb?.let { it / 1024f / 1024f }
        val diskTotalGb = if (diskUsedKb != null && diskAvailKb != null) {
            (diskUsedKb + diskAvailKb) / 1024f / 1024f
        } else {
            null
        }

        val loadTokens = s["LOAD"]?.trim()?.split(Regex("\\s+")).orEmpty()
        val load1 = loadTokens.getOrNull(0)?.toFloatOrNull()
        val load5 = loadTokens.getOrNull(1)?.toFloatOrNull()
        val load15 = loadTokens.getOrNull(2)?.toFloatOrNull()

        val uptimeSec = s["UP"]?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toDoubleOrNull()?.toLong()

        val tempRaw = s["TEMP"]?.trim()?.toLongOrNull()
        val tempC = when {
            tempRaw == null || tempRaw <= 0L -> null
            tempRaw > 1000L -> tempRaw / 1000f
            else -> tempRaw.toFloat()
        }?.takeIf { it in 1f..150f }

        val cpuCount = s["CPU"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }

        // The two net samples are taken 0.4 s apart on the remote side; the extra .05 accounts for
        // round-trip jitter, so the rate is approximate but stable enough for a glance gauge.
        val netA = netTotals(s["NET1"])
        val netB = netTotals(s["NET2"])
        val rxBps: Long?
        val txBps: Long?
        if (netA != null && netB != null) {
            val elapsed = 0.45f
            rxBps = ((netB.first - netA.first) / elapsed).toLong().coerceAtLeast(0L)
            txBps = ((netB.second - netA.second) / elapsed).toLong().coerceAtLeast(0L)
        } else {
            rxBps = null
            txBps = null
        }

        return NodeMetrics(
            cpuPercent = cpu,
            cpuCount = cpuCount,
            ramPercent = ramPercent,
            ramUsedMb = ramUsedMb,
            ramTotalMb = memTotal?.let { (it / 1024).toInt() },
            swapPercent = swapPercent,
            diskPercent = diskPercent,
            diskUsedGb = diskUsedGb,
            diskTotalGb = diskTotalGb,
            load1 = load1,
            load5 = load5,
            load15 = load15,
            tempC = tempC,
            uptimeSec = uptimeSec,
            rxBps = rxBps,
            txBps = txBps,
            at = now,
        )
    }

    /** Parses `@@OSR` (/etc/os-release), `@@UNAME`, `@@ARCH`, `@@PI`. */
    fun parseOs(raw: String): OsInfo {
        val s = sections(raw)
        val props = HashMap<String, String>()
        s["OSR"]?.lines()?.forEach { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim().trim('"').trim('\'')
            props[key] = value
        }

        val uname = s["UNAME"]?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val arch = s["ARCH"]?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()

        val rawId = props["ID"].orEmpty().lowercase()
        val idLike = props["ID_LIKE"].orEmpty().lowercase()
        val pretty = props["PRETTY_NAME"].orEmpty()

        val normalized = when {
            rawId.isNotBlank() -> normalizeOsId(rawId)
            idLike.isNotBlank() -> normalizeOsId(idLike.split(Regex("\\s+")).first())
            uname.equals("Darwin", true) -> "macos"
            uname.startsWith("FreeBSD", true) -> "freebsd"
            uname.startsWith("OpenBSD", true) -> "openbsd"
            uname.startsWith("NetBSD", true) -> "netbsd"
            uname.startsWith("Linux", true) -> "linux"
            uname.startsWith("CYGWIN", true) || uname.contains("Windows", true) -> "windows"
            else -> "unknown"
        }

        val name = pretty.ifBlank {
            when (normalized) {
                "macos" -> "macOS"
                "freebsd" -> "FreeBSD"
                "openbsd" -> "OpenBSD"
                "netbsd" -> "NetBSD"
                "windows" -> "Windows"
                "linux" -> "Linux"
                "unknown" -> uname.ifBlank { "Неизвестно" }
                else -> normalized.replaceFirstChar { it.uppercase() }
            }
        }
        return OsInfo(id = normalized, name = name, arch = arch)
    }

    private fun normalizeOsId(id: String): String = when (id) {
        "ubuntu" -> "ubuntu"
        "debian" -> "debian"
        "raspbian", "raspberrypi", "raspberry-pi-os" -> "raspbian"
        "arch", "archarm", "archlinux" -> "arch"
        "manjaro" -> "manjaro"
        "alpine" -> "alpine"
        "fedora" -> "fedora"
        "centos", "rhel", "redhat" -> "centos"
        "rocky" -> "rocky"
        "almalinux" -> "almalinux"
        "opensuse", "opensuse-leap", "opensuse-tumbleweed", "sles" -> "opensuse"
        "gentoo" -> "gentoo"
        "void" -> "void"
        "nixos" -> "nixos"
        "linuxmint", "mint" -> "mint"
        "kali" -> "kali"
        "pop" -> "pop"
        "freebsd" -> "freebsd"
        "openbsd" -> "openbsd"
        "netbsd" -> "netbsd"
        "darwin", "macos", "osx" -> "macos"
        "windows" -> "windows"
        "linux" -> "linux"
        else -> if (id.isBlank()) "unknown" else id
    }

    /** Command that prints one metrics section block. */
    val METRICS_COMMAND: String = """
        LC_ALL=C
        echo '@@STAT1'; head -1 /proc/stat 2>/dev/null
        echo '@@MEM'; cat /proc/meminfo 2>/dev/null
        echo '@@DF'; df -Pk / 2>/dev/null | tail -n 1
        echo '@@LOAD'; cat /proc/loadavg 2>/dev/null
        echo '@@UP'; cat /proc/uptime 2>/dev/null
        echo '@@TEMP'; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null
        echo '@@CPU'; grep -c '^processor' /proc/cpuinfo 2>/dev/null
        echo '@@NET1'; tail -n +3 /proc/net/dev 2>/dev/null | sed 's/^ *//; s/: */ /'
        sleep 0.4
        echo '@@STAT2'; head -1 /proc/stat 2>/dev/null
        echo '@@NET2'; tail -n +3 /proc/net/dev 2>/dev/null | sed 's/^ *//; s/: */ /'
    """.trimIndent()

    /** Command that prints the OS identification block. */
    val OS_COMMAND: String = """
        LC_ALL=C
        echo '@@OSR'; cat /etc/os-release 2>/dev/null
        echo '@@UNAME'; uname -s 2>/dev/null
        echo '@@ARCH'; uname -m 2>/dev/null
        echo '@@PI'; cat /proc/device-tree/model 2>/dev/null
    """.trimIndent()
}

/** Thin wrapper that talks to a node and hands the raw text to [MetricsParser]. */
class MetricsCollector(private val connections: ConnectionManager) {

    suspend fun sample(server: ServerConfig): NodeMetrics {
        val raw = connections.exec(server, MetricsParser.METRICS_COMMAND)
        return MetricsParser.parseMetrics(raw)
    }

    suspend fun detectOs(server: ServerConfig): OsInfo {
        val raw = connections.exec(server, MetricsParser.OS_COMMAND)
        return MetricsParser.parseOs(raw)
    }
}
