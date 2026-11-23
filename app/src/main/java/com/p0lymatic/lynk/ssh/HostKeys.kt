package com.p0lymatic.lynk.ssh

import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import com.p0lymatic.lynk.data.HostKeyEntry
import com.p0lymatic.lynk.data.Store
import java.security.MessageDigest

/**
 * Verifies server keys against the ones the user explicitly trusted.
 *
 * An unknown key is never accepted silently: the repository records what the server offered and
 * returns NOT_INCLUDED so the connection fails; the UI then asks the user to compare the
 * fingerprint. A key that differs from a stored one is treated as a change and refused.
 *
 * JSch hands the raw SSH key blob to [check], which is exactly what OpenSSH hashes — so the
 * fingerprint shown in Lynk is directly comparable with `ssh-keygen -lf`.
 */
class StoreHostKeyRepository(private val store: Store) : HostKeyRepository {

    data class Offered(val host: String, val port: Int, val keyType: String, val fingerprint: String)

    @Volatile
    var lastOffered: Offered? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val (hostName, port) = splitHost(host)
        val fingerprint = fingerprint(key)
        lastOffered = Offered(hostName, port, keyType(key), fingerprint)

        val known = store.hostKey(hostName, port)
            ?: return HostKeyRepository.NOT_INCLUDED
        return if (known.fingerprint.equals(fingerprint, ignoreCase = true)) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    override fun getKnownHostsRepositoryID(): String = "lynk"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    override fun add(key: HostKey, ui: UserInfo?) {
        // JSch only calls this when a KnownHosts UI accepts a key; Lynk asks the user itself and
        // trusts the key through AppState, so remember what was just offered.
        val offered = lastOffered ?: return
        store.trustHostKey(
            HostKeyEntry(
                host = offered.host,
                port = offered.port,
                type = offered.keyType,
                fingerprint = offered.fingerprint,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    override fun remove(host: String?, type: String?) {
        if (host == null) return
        val (hostName, port) = splitHost(host)
        if (type == null) {
            store.forgetHostKey(hostName, port)
        } else {
            store.state.value.hostKeys
                .firstOrNull { it.host.equals(hostName, true) && it.port == port && it.type.equals(type, true) }
                ?.let { store.forgetHostKey(hostName, port) }
        }
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        remove(host, type)
    }

    companion object {
        /** JSch passes "host" or "host:port"; split it back into address + port. */
        fun splitHost(raw: String): Pair<String, Int> {
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) {
                val close = trimmed.indexOf(']')
                if (close > 0) {
                    val hostPart = trimmed.substring(1, close)
                    val rest = trimmed.substring(close + 1)
                    val port = rest.removePrefix(":").toIntOrNull() ?: 22
                    return hostPart to port
                }
            }
            val colon = trimmed.lastIndexOf(':')
            if (colon > 0 && trimmed.indexOf(':') == colon) {
                val port = trimmed.substring(colon + 1).toIntOrNull()
                if (port != null) return trimmed.substring(0, colon) to port
            }
            return trimmed to 22
        }

        fun fingerprint(blob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }

        /** The SSH key blob starts with a length-prefixed algorithm name ("ssh-ed25519", ...). */
        fun keyType(blob: ByteArray): String {
            if (blob.size < 4) return "unknown"
            var length = 0
            for (i in 0 until 4) {
                length = (length shl 8) or (blob[i].toInt() and 0xFF)
            }
            if (length <= 0 || length > blob.size - 4 || length > 128) return "unknown"
            return String(blob, 4, length, Charsets.US_ASCII)
        }
    }
}