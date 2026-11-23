package com.p0lymatic.lynk.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One `shell` channel on an already-open SSH session, driven by [TerminalScreen].
 *
 * The channel pump owns all screen mutation; readers only look at [screen] and [revision].
 */
class TerminalSession(
    private val session: Session,
    cols: Int,
    rows: Int,
    private val onClosed: () -> Unit,
) : Closeable {

    enum class Status { CONNECTING, CONNECTED, CLOSED, ERROR }

    val screen = VtScreen(cols, rows)

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val closed = AtomicBoolean(false)
    private val flushStop = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    private val writeLock = Any()
    private val closeLock = Any()

    @Volatile
    private var channel: ChannelShell? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var flusher: Thread? = null

    init {
        try {
            val ch = session.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color")
            ch.setPtySize(screen.cols, screen.rows, 0, 0)
            ch.setEnv("TERM", "xterm-256color")
            channel = ch
            // JSch installs the receive pipe here; output arriving before this is discarded.
            val input = ch.inputStream
            output = ch.outputStream
            ch.connect(CONNECT_TIMEOUT_MS)
            // Answers to DSR / device-status queries go straight back to the host.
            screen.onResponse = { reply -> sendBytes(reply.toByteArray(Charsets.UTF_8)) }
            _status.value = Status.CONNECTED
            startPump(input)
        } catch (t: Throwable) {
            runCatching { channel?.disconnect() }
            channel = null
            output = null
            _error.value = ConnectionManager.describe(t)
            _status.value = Status.ERROR
        }
    }

    // ---- input ---------------------------------------------------------------

    fun send(text: String) {
        if (text.isEmpty()) return
        sendBytes(text.replace("\n", "\r").toByteArray(Charsets.UTF_8))
    }

    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty() || closed.get()) return
        synchronized(writeLock) {
            val stream = output ?: return
            runCatching {
                // getOutputStream() creates a new buffered stream on every call.
                stream.write(bytes)
                stream.flush()
            }.onFailure { fail(it) }
        }
    }

    fun sendKey(keyCode: Int) {
        val bytes = when (keyCode) {
            KEY_ESCAPE -> ESC
            KEY_TAB -> byteArrayOf(9)
            KEY_ENTER -> byteArrayOf(13)
            KEY_BACKSPACE -> byteArrayOf(127)
            KEY_UP -> CSI_A
            KEY_DOWN -> CSI_B
            KEY_RIGHT -> CSI_C
            KEY_LEFT -> CSI_D
            KEY_HOME -> CSI_H
            KEY_END -> CSI_F
            KEY_PAGE_UP -> CSI_PGUP
            KEY_PAGE_DOWN -> CSI_PGDN
            else -> null
        }
        if (bytes != null) sendBytes(bytes)
    }

    fun resize(cols: Int, rows: Int) {
        val c = cols.coerceAtLeast(1)
        val r = rows.coerceAtLeast(1)
        screen.resize(c, r)
        dirty.set(true)
        publish()
        val ch = channel ?: return
        runCatching { ch.setPtySize(c, r, 0, 0) }
        runCatching { sendWindowChange(ch, c, r) }
    }

    // ---- lifecycle -----------------------------------------------------------

    override fun close() {
        synchronized(closeLock) {
            if (!closed.compareAndSet(false, true)) return
        }
        flushStop.set(true)
        runCatching { channel?.disconnect() }
        runCatching { reader?.interrupt() }
        runCatching { flusher?.interrupt() }
        val r = reader
        if (r != null && r !== Thread.currentThread()) runCatching { r.join(500) }
        val f = flusher
        if (f != null && f !== Thread.currentThread()) runCatching { f.join(200) }
        channel = null
        output = null
        publishNow()
        _status.value = Status.CLOSED
        runCatching { onClosed() }
    }

    // ---- pump ----------------------------------------------------------------

    private fun startPump(input: InputStream) {
        val readThread = Thread({
            val buf = ByteArray(8192)
            try {
                while (!closed.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        screen.feed(buf, 0, n)
                        dirty.set(true)
                    }
                }
            } catch (t: Throwable) {
                // Local close is expected; unexpected read failures must reach the UI.
                fail(t)
            } finally {
                publishNow()
                flushStop.set(true)
                if (!closed.get() && _status.value != Status.ERROR) _status.value = Status.CLOSED
            }
        }, "lynk-terminal-read")
        readThread.isDaemon = true
        reader = readThread
        readThread.start()

        val flushThread = Thread({
            while (!flushStop.get()) {
                try {
                    Thread.sleep(THROTTLE_MS)
                } catch (_: InterruptedException) {
                    break
                }
                publish()
            }
            publishNow()
        }, "lynk-terminal-flush")
        flushThread.isDaemon = true
        flusher = flushThread
        flushThread.start()
    }

    private fun publish() {
        if (dirty.getAndSet(false)) _revision.value = screen.revision
    }

    private fun publishNow() {
        dirty.set(false)
        _revision.value = screen.revision
    }

    private fun fail(t: Throwable) {
        if (closed.get()) return
        _error.value = ConnectionManager.describe(t)
        _status.value = Status.ERROR
    }

    /**
     * JSch exposes no public window-change call, and the method name drifted between forks, so this
     * is best effort: a missing method just means the peer keeps the old size until reconnects.
     */
    private fun sendWindowChange(ch: ChannelShell, cols: Int, rows: Int) {
        var type: Class<*>? = ch.javaClass
        val int = Int::class.javaPrimitiveType
        while (type != null) {
            try {
                val method = type.getDeclaredMethod("sendWindowChange", int, int, int, int)
                method.isAccessible = true
                method.invoke(ch, cols, rows, 0, 0)
                return
            } catch (_: NoSuchMethodException) {
                type = type.superclass
            } catch (_: Throwable) {
                return
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val THROTTLE_MS = 30L

        const val KEY_ESCAPE = 27
        const val KEY_TAB = 9
        const val KEY_ENTER = 13
        const val KEY_BACKSPACE = 127
        const val KEY_UP = 1000
        const val KEY_DOWN = 1001
        const val KEY_RIGHT = 1002
        const val KEY_LEFT = 1003
        const val KEY_HOME = 1004
        const val KEY_END = 1005
        const val KEY_PAGE_UP = 1006
        const val KEY_PAGE_DOWN = 1007

        private val ESC = byteArrayOf(27)
        private val CSI_A = byteArrayOf(27, 91, 65)
        private val CSI_B = byteArrayOf(27, 91, 66)
        private val CSI_C = byteArrayOf(27, 91, 67)
        private val CSI_D = byteArrayOf(27, 91, 68)
        private val CSI_H = byteArrayOf(27, 91, 72)
        private val CSI_F = byteArrayOf(27, 91, 70)
        private val CSI_PGUP = byteArrayOf(27, 91, 53, 126)
        private val CSI_PGDN = byteArrayOf(27, 91, 54, 126)
    }
}