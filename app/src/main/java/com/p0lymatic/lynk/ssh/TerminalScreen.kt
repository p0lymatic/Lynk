package com.p0lymatic.lynk.ssh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

private const val DEFAULT_COLOR: Byte = 7
private const val MAX_SCROLLBACK = 400
private val NO_BYTES = ByteArray(0)

/** One row of the terminal grid. Colours are xterm indices: 0..15, 7 is the default foreground. */
class TerminalLine(val cols: Int) {
    val chars = CharArray(cols) { ' ' }
    val color = ByteArray(cols) { DEFAULT_COLOR }
    val bold = BooleanArray(cols)

    fun clear(from: Int = 0, to: Int = cols) {
        val start = from.coerceIn(0, cols)
        val end = to.coerceIn(start, cols)
        for (i in start until end) {
            chars[i] = ' '
            color[i] = DEFAULT_COLOR
            bold[i] = false
        }
    }

    fun copy(): TerminalLine {
        val line = TerminalLine(cols)
        chars.copyInto(line.chars)
        color.copyInto(line.color)
        bold.copyInto(line.bold)
        return line
    }
}

/**
 * Minimal xterm-compatible screen model: enough of VT100/ECMA-48 for bash, top, vim, nano and less.
 *
 * The emulator owns no threading and no Android types; a session feeds it bytes and the UI renders
 * [lineAt]. Every mutation bumps [revision].
 */
class TerminalScreen(cols: Int, rows: Int) {

    var cols: Int = cols.coerceAtLeast(1)
        private set
    var rows: Int = rows.coerceAtLeast(1)
        private set

    var cursorCol: Int = 0
        private set
    var cursorRow: Int = 0
        private set
    var cursorVisible: Boolean = true

    @Volatile
    var revision: Long = 0L
        private set

    val scrollbackSize: Int get() = scrollback.size

    private val lines = ArrayList<TerminalLine>(this.rows)
    private val scrollback = ArrayDeque<TerminalLine>()

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var currentColor: Byte = DEFAULT_COLOR
    private var currentBold = false
    private var wrapPending = false

    private var savedCol = 0
    private var savedRow = 0
    private var savedColor: Byte = DEFAULT_COLOR
    private var savedBold = false

    private var altActive = false
    private var altLines: ArrayList<TerminalLine> = ArrayList()
    private var altCol = 0
    private var altRow = 0
    private var altColor: Byte = DEFAULT_COLOR
    private var altBold = false
    private var altScrollTop = 0
    private var altScrollBottom = 0

    private var state = ParseState.GROUND
    private val csiBuf = StringBuilder()
    private var pendingBytes = NO_BYTES

    /** Guards the grid: the channel pump writes while the UI reads rows. Intrinsic monitor, so
     *  nested calls such as [snapshotText] -> [lineAt] stay valid. */
    private val lock = Any()

    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    init {
        repeat(this.rows) { lines.add(TerminalLine(this.cols)) }
    }

    private enum class ParseState { GROUND, ESC, CSI, OSC, OSC_ESC, CHARSET }

    // ---- public surface ------------------------------------------------------

    fun totalLines(): Int = synchronized(lock) { scrollback.size + lines.size }

    /**
     * A live row, not a copy: the UI reads it while the pump writes, so a glyph may be torn for one
     * frame. Copying per row per frame would allocate the whole screen on every keystroke instead.
     */
    fun lineAt(index: Int): TerminalLine = synchronized(lock) {
        val total = scrollback.size + lines.size
        require(index in 0 until total) { "line $index outside 0..${total - 1}" }
        if (index < scrollback.size) scrollback[index] else lines[index - scrollback.size]
    }

    fun absoluteCursorRow(): Int = synchronized(lock) { scrollback.size + cursorRow }

    fun feed(bytes: ByteArray) = feed(bytes, 0, bytes.size)

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val chunk = if (offset == 0 && length == bytes.size) bytes
        else bytes.copyOfRange(offset, offset + length)
        synchronized(lock) {
            val input = if (pendingBytes.isEmpty()) chunk else pendingBytes + chunk
            for (ch in decode(input)) consume(ch)
        }
    }

    fun feed(text: String) {
        if (text.isEmpty()) return
        feed(text.toByteArray(Charsets.UTF_8))
    }

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == cols && r == rows) return
        synchronized(lock) {
            val fullScreen = scrollTop == 0 && scrollBottom == rows - 1
            if (r < lines.size) {
                // Keep what is visible from the top and drop the tail; the cursor follows the clamp.
                cursorRow = cursorRow.coerceAtMost(r - 1)
                cursorCol = cursorCol.coerceAtMost(c - 1)
                while (lines.size > r) lines.removeAt(lines.size - 1)
            } else {
                while (lines.size < r) lines.add(TerminalLine(c))
            }
            if (c != cols) for (i in lines.indices) lines[i] = resizeLine(lines[i], c)
            normalizeAlt(c, r)

            cols = c
            rows = r
            cursorCol = cursorCol.coerceIn(0, cols - 1)
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            if (fullScreen) {
                scrollTop = 0
                scrollBottom = rows - 1
            } else {
                scrollTop = scrollTop.coerceIn(0, rows - 1)
                scrollBottom = scrollBottom.coerceIn(0, rows - 1)
                if (scrollBottom <= scrollTop) {
                    scrollTop = 0
                    scrollBottom = rows - 1
                }
            }
            wrapPending = false
            revision++
        }
    }

    fun reset() {
        synchronized(lock) {
            lines.forEach { it.clear() }
            scrollback.clear()
            cursorCol = 0
            cursorRow = 0
            cursorVisible = true
            scrollTop = 0
            scrollBottom = rows - 1
            currentColor = DEFAULT_COLOR
            currentBold = false
            wrapPending = false
            savedCol = 0
            savedRow = 0
            savedColor = DEFAULT_COLOR
            savedBold = false
            altActive = false
            altLines = ArrayList()
            state = ParseState.GROUND
            csiBuf.setLength(0)
            pendingBytes = NO_BYTES
            revision++
        }
    }

    fun snapshotText(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            for (i in 0 until totalLines()) {
                if (i > 0) sb.append('\n')
                val line = lineAt(i)
                var end = line.chars.size
                while (end > 0 && line.chars[end - 1] == ' ') end--
                sb.append(line.chars, 0, end)
            }
            return sb.toString()
        }
    }

    // ---- decoding ------------------------------------------------------------

    private fun decode(bytes: ByteArray): CharArray {
        decoder.reset()
        val input = ByteBuffer.wrap(bytes)
        var out = CharBuffer.allocate(bytes.size + 4)
        while (true) {
            val result = decoder.decode(input, out, false)
            if (result.isOverflow) {
                val bigger = CharBuffer.allocate(out.capacity() * 2)
                out.flip()
                bigger.put(out)
                out = bigger
                continue
            }
            break
        }
        out.flip()
        val chars = CharArray(out.remaining())
        out.get(chars)
        pendingBytes = if (input.hasRemaining()) {
            ByteArray(input.remaining()).also { input.get(it) }
        } else {
            NO_BYTES
        }
        return chars
    }

    // ---- parser --------------------------------------------------------------

    private fun consume(c: Char) {
        when (state) {
            ParseState.GROUND -> ground(c)
            ParseState.ESC -> esc(c)
            ParseState.CSI -> csi(c)
            ParseState.OSC -> when (c) {
                '\u0007' -> state = ParseState.GROUND
                '\u001b' -> state = ParseState.OSC_ESC
            }
            ParseState.OSC_ESC -> state = if (c == '\\') ParseState.GROUND else ParseState.OSC
            ParseState.CHARSET -> state = ParseState.GROUND
        }
    }

    private fun ground(c: Char) {
        when {
            c == '\u001b' -> state = ParseState.ESC
            c == '\r' -> { cursorCol = 0; wrapPending = false }
            c == '\n' -> lineFeed()
            c == '\b' -> {
                if (wrapPending) wrapPending = false else cursorCol = (cursorCol - 1).coerceAtLeast(0)
            }
            c == '\t' -> {
                cursorCol = (((cursorCol / 8) + 1) * 8).coerceAtMost(cols - 1)
                wrapPending = false
            }
            c == '\u0007' -> Unit
            c < ' ' || c == '\u007f' -> Unit
            else -> putChar(c)
        }
    }

    private fun esc(c: Char) {
        state = ParseState.GROUND
        when (c) {
            '[' -> { state = ParseState.CSI; csiBuf.setLength(0) }
            ']' -> state = ParseState.OSC
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> index()
            'E' -> { cursorCol = 0; index() }
            'M' -> reverseIndex()
            'c' -> reset()
            '(', ')', '*', '+', '-', '.', '/', '#', '%' -> state = ParseState.CHARSET
        }
    }

    private fun csi(c: Char) {
        when {
            c in '0'..'9' || c == ';' || c == ':' || c in ' '..'/' || c == '?' || c == '>' || c == '<' || c == '=' ->
                csiBuf.append(c)
            c in '@'..'~' -> dispatchCsi(c)
            c == '\u001b' -> state = ParseState.ESC
            else -> state = ParseState.GROUND
        }
    }

    private fun dispatchCsi(fin: Char) {
        var raw = csiBuf.toString()
        csiBuf.setLength(0)
        state = ParseState.GROUND

        var private = false
        if (raw.isNotEmpty() && raw[0] in "?><=") {
            private = raw[0] == '?'
            raw = raw.substring(1)
        }
        val body = buildString { for (ch in raw) if (ch in '0'..'9' || ch == ';' || ch == ':') append(ch) }
        val params = if (body.isEmpty()) emptyList() else body.split(';')
        fun num(i: Int): Int? = params.getOrNull(i)?.toIntOrNull()
        fun count(i: Int = 0): Int = (num(i) ?: 1).coerceAtLeast(1)
        fun mode(i: Int = 0): Int = num(i) ?: 0

        if (private) {
            when (fin) {
                'h', 'l' -> {
                    val on = fin == 'h'
                    for (p in params) {
                        when (p) {
                            "25" -> cursorVisible = on
                            "1049", "1047", "47" -> if (on) enterAlt(p == "1049") else exitAlt()
                        }
                    }
                    revision++
                }
            }
            return
        }

        when (fin) {
            'A' -> moveUp(count())
            'B' -> moveDown(count())
            'C' -> moveRight(count())
            'D' -> moveLeft(count())
            'E' -> { cursorCol = 0; moveDown(count()) }
            'F' -> { cursorCol = 0; moveUp(count()) }
            'G', '`' -> { cursorCol = (count() - 1).coerceIn(0, cols - 1); wrapPending = false; revision++ }
            'H', 'f' -> {
                cursorRow = ((num(0) ?: 1) - 1).coerceIn(scrollTop, scrollBottom)
                cursorCol = ((num(1) ?: 1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
                revision++
            }
            'd' -> {
                cursorRow = ((num(0) ?: 1) - 1).coerceIn(scrollTop, scrollBottom)
                wrapPending = false
                revision++
            }
            'J' -> when (mode()) {
                0 -> {
                    lines[cursorRow].clear(cursorCol, cols)
                    for (r in cursorRow + 1 until rows) lines[r].clear()
                    revision++
                }
                1 -> {
                    for (r in 0 until cursorRow) lines[r].clear()
                    lines[cursorRow].clear(0, cursorCol + 1)
                    revision++
                }
                2 -> { lines.forEach { it.clear() }; revision++ }
                3 -> { lines.forEach { it.clear() }; scrollback.clear(); revision++ }
            }
            'K' -> when (mode()) {
                0 -> { lines[cursorRow].clear(cursorCol, cols); revision++ }
                1 -> { lines[cursorRow].clear(0, cursorCol + 1); revision++ }
                2 -> { lines[cursorRow].clear(); revision++ }
            }
            'L' -> insertLines(count())
            'M' -> deleteLines(count())
            'P' -> deleteChars(count())
            '@' -> insertChars(count())
            'X' -> {
                lines[cursorRow].clear(cursorCol, (cursorCol + count()).coerceAtMost(cols))
                revision++
            }
            'S' -> scrollUp(count())
            'T' -> scrollDown(count())
            'r' -> {
                val top = (num(0) ?: 1) - 1
                val bottom = (num(1) ?: rows) - 1
                if (top >= 0 && top < bottom && bottom < rows) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorCol = 0
                    cursorRow = 0
                    wrapPending = false
                    revision++
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'm' -> sgr(params)
        }
    }

    private fun sgr(params: List<String>) {
        val codes = if (params.isEmpty()) listOf(0) else params.map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> { currentColor = DEFAULT_COLOR; currentBold = false }
                1 -> currentBold = true
                22 -> currentBold = false
                in 30..37 -> currentColor = (code - 30).toByte()
                39 -> currentColor = DEFAULT_COLOR
                in 90..97 -> currentColor = (code - 90 + 8).toByte()
                38, 48 -> {
                    // Extended colour: skip its payload instead of reading it as a plain SGR.
                    i += when (codes.getOrNull(i + 1)) {
                        5 -> 2
                        2 -> 4
                        else -> 0
                    }
                }
            }
            i++
        }
        revision++
    }

    // ---- writing -------------------------------------------------------------

    private fun putChar(c: Char) {
        if (wrapPending) {
            wrapPending = false
            cursorCol = 0
            lineFeed()
        }
        val line = lines[cursorRow]
        line.chars[cursorCol] = c
        line.color[cursorCol] = currentColor
        line.bold[cursorCol] = currentBold
        if (cursorCol >= cols - 1) wrapPending = true else cursorCol++
        revision++
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
        revision++
    }

    private fun index() {
        if (cursorRow == scrollBottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
        revision++
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
        revision++
    }

    // ---- scrolling -----------------------------------------------------------

    private fun scrollUp(count: Int) {
        val height = scrollBottom - scrollTop + 1
        repeat(count.coerceIn(1, height)) {
            val top = lines.removeAt(scrollTop)
            if (scrollTop == 0) pushScrollback(top)
            lines.add(scrollBottom, TerminalLine(cols))
        }
        revision++
    }

    private fun scrollDown(count: Int) {
        val height = scrollBottom - scrollTop + 1
        repeat(count.coerceIn(1, height)) {
            if (scrollBottom < lines.size) lines.removeAt(scrollBottom)
            lines.add(scrollTop, TerminalLine(cols))
        }
        revision++
    }

    private fun pushScrollback(line: TerminalLine) {
        if (altActive) return
        scrollback.addLast(line)
        while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
    }

    private fun insertLines(count: Int) {
        val n = count.coerceIn(1, scrollBottom - cursorRow + 1)
        repeat(n) {
            if (scrollBottom < lines.size) lines.removeAt(scrollBottom)
            lines.add(cursorRow, TerminalLine(cols))
        }
        revision++
    }

    private fun deleteLines(count: Int) {
        val n = count.coerceIn(1, scrollBottom - cursorRow + 1)
        repeat(n) {
            lines.removeAt(cursorRow)
            lines.add(scrollBottom, TerminalLine(cols))
        }
        revision++
    }

    private fun deleteChars(count: Int) {
        val line = lines[cursorRow]
        val n = count.coerceIn(1, cols - cursorCol)
        for (i in cursorCol until cols) {
            val src = i + n
            if (src < cols) {
                line.chars[i] = line.chars[src]
                line.color[i] = line.color[src]
                line.bold[i] = line.bold[src]
            } else {
                line.chars[i] = ' '
                line.color[i] = DEFAULT_COLOR
                line.bold[i] = false
            }
        }
        revision++
    }

    private fun insertChars(count: Int) {
        val line = lines[cursorRow]
        val n = count.coerceIn(1, cols - cursorCol)
        for (i in cols - 1 downTo cursorCol) {
            val src = i - n
            if (src >= cursorCol) {
                line.chars[i] = line.chars[src]
                line.color[i] = line.color[src]
                line.bold[i] = line.bold[src]
            } else {
                line.chars[i] = ' '
                line.color[i] = DEFAULT_COLOR
                line.bold[i] = false
            }
        }
        revision++
    }

    // ---- cursor --------------------------------------------------------------

    private fun moveUp(n: Int) {
        val limit = if (cursorRow >= scrollTop) scrollTop else 0
        cursorRow = (cursorRow - n).coerceAtLeast(limit)
        wrapPending = false
        revision++
    }

    private fun moveDown(n: Int) {
        val limit = if (cursorRow <= scrollBottom) scrollBottom else rows - 1
        cursorRow = (cursorRow + n).coerceAtMost(limit)
        wrapPending = false
        revision++
    }

    private fun moveLeft(n: Int) {
        cursorCol = (cursorCol - n).coerceAtLeast(0)
        wrapPending = false
        revision++
    }

    private fun moveRight(n: Int) {
        cursorCol = (cursorCol + n).coerceAtMost(cols - 1)
        wrapPending = false
        revision++
    }

    private fun saveCursor() {
        savedCol = cursorCol
        savedRow = cursorRow
        savedColor = currentColor
        savedBold = currentBold
    }

    private fun restoreCursor() {
        cursorCol = savedCol.coerceIn(0, cols - 1)
        cursorRow = savedRow.coerceIn(0, rows - 1)
        currentColor = savedColor
        currentBold = savedBold
        wrapPending = false
        revision++
    }

    // ---- alternate screen ----------------------------------------------------

    private fun enterAlt(clear: Boolean) {
        if (altActive) {
            if (clear) { lines.forEach { it.clear() }; revision++ }
            return
        }
        altLines = ArrayList(lines.map { it.copy() })
        altCol = cursorCol
        altRow = cursorRow
        altColor = currentColor
        altBold = currentBold
        altScrollTop = scrollTop
        altScrollBottom = scrollBottom
        altActive = true
        lines.clear()
        repeat(rows) { lines.add(TerminalLine(cols)) }
        cursorCol = 0
        cursorRow = 0
        wrapPending = false
        revision++
    }

    private fun exitAlt() {
        if (!altActive) return
        altActive = false
        lines.clear()
        lines.addAll(altLines)
        altLines = ArrayList()
        cursorCol = altCol.coerceIn(0, cols - 1)
        cursorRow = altRow.coerceIn(0, rows - 1)
        currentColor = altColor
        currentBold = altBold
        scrollTop = altScrollTop.coerceIn(0, rows - 1)
        scrollBottom = altScrollBottom.coerceIn(scrollTop, rows - 1)
        wrapPending = false
        revision++
    }

    private fun normalizeAlt(c: Int, r: Int) {
        if (!altActive || altLines.isEmpty()) return
        if (c != cols) for (i in altLines.indices) altLines[i] = resizeLine(altLines[i], c)
        while (altLines.size > r) altLines.removeAt(altLines.size - 1)
        while (altLines.size < r) altLines.add(TerminalLine(c))
        altCol = altCol.coerceIn(0, c - 1)
        altRow = altRow.coerceIn(0, r - 1)
        altScrollTop = altScrollTop.coerceIn(0, r - 1)
        altScrollBottom = altScrollBottom.coerceIn(altScrollTop, r - 1)
    }

    private fun resizeLine(line: TerminalLine, newCols: Int): TerminalLine {
        if (line.cols == newCols) return line
        val resized = TerminalLine(newCols)
        val keep = minOf(line.cols, newCols)
        line.chars.copyInto(resized.chars, 0, 0, keep)
        line.color.copyInto(resized.color, 0, 0, keep)
        line.bold.copyInto(resized.bold, 0, 0, keep)
        return resized
    }
}