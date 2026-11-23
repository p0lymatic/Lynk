package com.p0lymatic.lynk.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One `shell` channel on an already-open SSH session, driven by [TerminalScreen].
 *
 * Constructed on Dispatchers.IO by ConnectionManager. After connection, only the writer touches
 * the JSch output stream, PTY and channel teardown. The reader owns incoming data.
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
    // Serializes admission from the UI and DSR reader, including the final close command.
    // Never hold this lock during socket I/O or screen mutation.
    private val queueLock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lynk-terminal-write").apply { isDaemon = true }
    }
    private var pendingBytes = 0

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
            // DSR replies share the FIFO with user input.
            screen.onResponse = { reply -> enqueueBytes(reply.toByteArray(Charsets.UTF_8)) }
            _status.value = Status.CONNECTED
            startPump(input)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private val _inputRevision = MutableStateFlow(0L)
    val inputRevision: StateFlow<Long> = _inputRevision.asStateFlow()

    // ---- input ---------------------------------------------------------------

    fun send(text: String) {
        if (text.isEmpty()) return
        sendBytes(text.replace("\n", "\r").toByteArray(Charsets.UTF_8))
    }

    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(queueLock) { _inputRevision.value++ }
        enqueueBytes(bytes)
    }

    /** Positive lines scroll up; negative lines scroll down. Coordinates are zero based. */
    fun scrollRemote(lines: Int, col: Int, row: Int) {
        if (lines == 0) return
        val sequence = synchronized(screen) {
            if (screen.mouseReporting) {
                val button = if (lines > 0) 64 else 65
                "\u001b[<$button;${col.coerceIn(0, screen.cols - 1) + 1};${row.coerceIn(0, screen.rows - 1) + 1}M"
            } else if (lines > 0) "\u001b[A" else "\u001b[B"
        }
        val bytes = sequence.toByteArray(Charsets.UTF_8)
        var remaining = kotlin.math.abs(lines.toLong())
        while (remaining > 0 && !closed.get()) {
            sendBytes(bytes)
            remaining--
        }
    }

    private fun enqueueBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(queueLock) {
            if (closed.get()) return
            // Bound retained input without ever waiting for the network on the caller.
            // Overflow is an explicit terminal error; never silently drop keystrokes.
            if (bytes.size > MAX_PENDING_BYTES - pendingBytes) {
                fail(IOException("Переполнена очередь ввода терминала"))
                return
            }
            val copy = bytes.copyOf()
            pendingBytes += copy.size
            writer.execute {
                try {
                    if (_status.value != Status.ERROR) {
                        val stream = output ?: throw IOException("Канал терминала закрыт")
                        // getOutputStream() creates a new buffer each time: reuse this stream.
                        stream.write(copy)
                        stream.flush()
                    }
                } catch (t: Throwable) {
                    fail(t, acceptedCommand = true)
                } finally {
                    synchronized(queueLock) { pendingBytes -= copy.size }
                }
            }
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
        synchronized(queueLock) {
            if (closed.get()) return
            writer.execute {
                if (_status.value != Status.ERROR) {
                    try {
                        val ch = channel ?: return@execute
                        // JSch setPtySize already sends SSH_MSG_CHANNEL_REQUEST/window-change.
                        ch.setPtySize(c, r, 0, 0)
                    } catch (t: Throwable) {
                        fail(t, acceptedCommand = true)
                    }
                }
            }
        }
    }

    // ---- lifecycle -----------------------------------------------------------

    override fun close() {
        synchronized(queueLock) { closeLocked() }
    }

    private fun closeLocked() {
        if (!closed.compareAndSet(false, true)) return
        flushStop.set(true)
        // FIFO: accepted input is flushed before disconnect. No joins or I/O on the caller.
        writer.execute {
            try {
                channel?.disconnect()
            } catch (t: Throwable) {
                if (_status.value != Status.ERROR) {
                    _error.value = ConnectionManager.describe(t)
                    _status.value = Status.ERROR
                }
            } finally {
                reader?.interrupt()
                flusher?.interrupt()
                channel = null
                output = null
                publishNow()
                if (_status.value != Status.ERROR) _status.value = Status.CLOSED
                runCatching { onClosed() }
            }
        }
        writer.shutdown() // queued work drains; no caller-runs rejection policy
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
                close()
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

    private fun fail(t: Throwable, acceptedCommand: Boolean = false) {
        synchronized(queueLock) {
            // An accepted write may fail while an explicit close is already queued.
            if (_status.value == Status.ERROR || (closed.get() && !acceptedCommand)) return
            _error.value = ConnectionManager.describe(t)
            _status.value = Status.ERROR
            closeLocked()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val THROTTLE_MS = 30L
        private const val MAX_PENDING_BYTES = 1024 * 1024

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