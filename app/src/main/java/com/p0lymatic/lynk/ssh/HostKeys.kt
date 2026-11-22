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
 */
class StoreHostKeyRepository(private val store: Store) : HostKeyRepository {

    data class Offered(val host: String, val port: Int, val keyType: String, val fingerprint: String)

    @Volatile
    var lastOffered: Offered? = null
        private set

    override fun check(host: String?, key: HostKey?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val (hostName, port) = splitHost(host ?: key.host, key)
        val fingerprint = fingerprint(key)
        lastOffered = Offered(hostName, port, key.type, fingerprint)

        val known = store.hostKey(hostName, port)
            ?: return HostKeyRepository.NOT_INCLUDED
        return if (known.fingerprint.equals(fingerprint, ignoreCase = true)) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    override fun getKnownHostsRepositoryID(): String = "lynk"

    override fun getHostKey(host: String?, type: String?): Array<HostKey>? {
        val (hostName, port) = host to 0
        val entry = store.hostKey(hostName, port) ?: return null
        if (type != null && !entry.type.equals(type, ignoreCase = true)) return null
        return null
    }

    override fun add(key: HostKey?, ui: UserInfo?) {
        if (key == null) return
        val (hostName, port) = splitHost(key.host, key)
        store.trustHostKey(HostKeyEntry(hostName, port, key.type, fingerprint(key), System.currentTimeMillis()))
    }

    override fun remove(host: String?, type: String?) {
        if (host == null) return
        val (hostName, port) = splitHost(host, null)
        if (type == null) store.forgetHostKey(hostName, port)
        else store.state.value.hostKeys
            .firstOrNull { it.host.equals(hostName, true) && it.port == port && it.type.equals(type, true) }
            ?.let { store.forgetHostKey(hostName, port) }
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        remove(host, type)
    }

    companion object {
        /** JSch passes "host" or "host:port"; split it back into address + port. */
        fun splitHost(raw: String, key: HostKey?): Pair<String, Int> {
            val trimmed = raw.trim()
            val colon = trimmed.lastIndexOf(':')
            if (colon > 0 && trimmed.indexOf(']') < colon) {
                val hostPart = trimmed.substring(0, colon)
                val portPart = trimmed.substring(colon + 1).toIntOrNull()
                if (portPart != null) return hostPart to portPart
            }
            return trimmed to 22
        }

        fun fingerprint(key: HostKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.key)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}