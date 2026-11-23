package com.p0lymatic.lynk.ssh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/** One character cell. Colour indexes are ANSI 0..255, or [NO_COLOR] for the theme default. */
class Cell {
    var ch: Char = ' '
    var fg: Int = NO_COLOR
    var bg: Int = NO_COLOR
    var bold: Boolean = false
    var underline: Boolean = false
    var inverse: Boolean = false

    fun copyFrom(other: Cell) {
        ch = other.ch
        fg = other.fg
        bg = other.bg
        bold = other.bold
        underline = other.underline
        inverse = other.inverse
    }

    fun reset() {
        ch = ' '
        fg = NO_COLOR
        bg = NO_COLOR
        bold = false
        underline = false
        inverse = false
    }

    companion object {
        const val NO_COLOR = -1
    }
}

/** A run of cells with the same style, ready for a text renderer. */
data class Span(
    val start: Int,
    val end: Int,
    val fg: Int,
    val bg: Int,
    val bold: Boolean,
    val underline: Boolean,
    val inverse: Boolean,
)

/** One rendered row: the characters plus style runs. */
data class RowSnapshot(
    val text: String,
    val spans: List<Span>,
    val cursorCol: Int,
)

/**
 * A VT100/xterm-ish screen: a real grid with a cursor, scrollback, colours and the
 * alternate screen that full-screen programs (vim, top, less) switch to.
 *
 * Deliberately small but honest: what it does not understand it ignores instead of
 * corrupting the screen, and the parser never throws.
 */
class VtScreen(cols: Int, rows: Int) {

    var cols: Int = cols.coerceAtLeast(1)
        private set
    var rows: Int = rows.coerceAtLeast(1)
        private set

    private var main: Array<Array<Cell>> = newGrid(this.cols, this.rows)
    private var alt: Array<Array<Cell>> = newGrid(this.cols, this.rows)
    private var grid: Array<Array<Cell>> = main

    private val scrollback = ArrayDeque<Array<Cell>>()
    private var usingAlt = false

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set
    var title: String = ""
        private set

    /** Called when the host asks for a reply (DSR); the session forwards it back over SSH. */
    var onResponse: ((String) -> Unit)? = null

    private var revisionValue = 0L
    val revision: Long get() = revisionValue

    // pen
    private var penFg = Cell.NO_COLOR
    private var penBg = Cell.NO_COLOR
    private var penBold = false
    private var penUnderline = false
    private var penInverse = false

    // modes
    private var wrapEnabled = true
    private var pendingWrap = false
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private val savedCursor = intArrayOf(0, 0)

    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC }

    private var state = State.GROUND
    private val csi = StringBuilder()
    private val osc = StringBuilder()

    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    // ---- public API ----------------------------------------------------------

    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == cols && r == rows) return
        main = resizeGrid(main, c, r)
        alt = resizeGrid(alt, c, r)
        grid = if (usingAlt) alt else main
        cols = c
        rows = r
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
        bump()
    }

    fun feed(bytes: ByteArray) = feed(bytes, 0, bytes.size)

    private var carry = ByteArray(0)

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val chunk = bytes.copyOfRange(offset, offset + length)
        val all = if (carry.isEmpty()) chunk else carry + chunk
        val input = ByteBuffer.wrap(all)
        val output = CharBuffer.allocate(all.size + 8)
        decoder.reset()
        // endOfInput = false keeps a split multi-byte character in the buffer for the next chunk.
        decoder.decode(input, output, false)
        val rest = ByteArray(input.remaining())
        input.get(rest)
        carry = if (rest.size > 4) ByteArray(0) else rest
        output.flip()
        if (output.hasRemaining()) feed(output.toString())
    }

    fun feed(text: String) {
        for (ch in text) step(ch)
        bump()
    }

    fun clear() {
        main = newGrid(cols, rows)
        alt = newGrid(cols, rows)
        scrollback.clear()
        grid = main
        usingAlt = false
        cursorRow = 0
        cursorCol = 0
        bump()
    }

    /** Rows currently on screen, top to bottom, with the cursor column marked. */
    fun snapshot(): List<RowSnapshot> {
        val out = ArrayList<RowSnapshot>(rows)
        for (r in 0 until rows) {
            val cells = grid[r]
            val text = StringBuilder(cols)
            val spans = ArrayList<Span>(8)
            var runStart = 0
            var cur = cells[0]
            for (c in 1 until cols) {
                val cell = cells[c]
                if (cell.fg != cur.fg || cell.bg != cur.bg || cell.bold != cur.bold ||
                    cell.underline != cur.underline || cell.inverse != cur.inverse
                ) {
                    spans += Span(runStart, c, cur.fg, cur.bg, cur.bold, cur.underline, cur.inverse)
                    runStart = c
                    cur = cell
                }
            }
            spans += Span(runStart, cols, cur.fg, cur.bg, cur.bold, cur.underline, cur.inverse)
            for (c in 0 until cols) text.append(cells[c].ch)
            out += RowSnapshot(
                text = text.toString().trimEnd(),
                spans = spans,
                cursorCol = if (cursorVisible && r == cursorRow) cursorCol else -1,
            )
        }
        return out
    }

    // ---- parser --------------------------------------------------------------

    private fun bump() {
        revisionValue++
    }

    private fun step(ch: Char) {
        when (state) {
            State.GROUND -> when {
                ch == '\u001b' -> state = State.ESC
                ch == '\r' -> { cursorCol = 0; pendingWrap = false }
                ch == '\n' -> lineFeed()
                ch == '\b' -> if (cursorCol > 0) { cursorCol--; pendingWrap = false }
                ch == '\t' -> tab()
                ch == '\u0007' -> Unit
                ch.code < 0x20 -> Unit
                else -> putChar(ch)
            }
            State.ESC -> when (ch) {
                '[' -> { csi.setLength(0); state = State.CSI }
                ']' -> { osc.setLength(0); state = State.OSC }
                '(' , ')' -> state = State.GROUND
                '7' -> { savedCursor[0] = cursorRow; savedCursor[1] = cursorCol; state = State.GROUND }
                '8' -> { cursorRow = savedCursor[0].coerceIn(0, rows - 1); cursorCol = savedCursor[1].coerceIn(0, cols - 1); state = State.GROUND }
                'M' -> { reverseIndex(); state = State.GROUND }
                'D' -> { lineFeed(); state = State.GROUND }
                'E' -> { cursorCol = 0; lineFeed(); state = State.GROUND }
                'c' -> { clear(); state = State.GROUND }
                else -> state = State.GROUND
            }
            State.CSI -> {
                if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '<' || ch == '!') {
                    if (csi.length < 64) csi.append(ch)
                } else {
                    handleCsi(csi.toString(), ch)
                    state = State.GROUND
                }
            }
            State.OSC -> when {
                ch == '\u0007' -> { finishOsc(); state = State.GROUND }
                ch == '\u001b' -> state = State.OSC_ESC
                osc.length < 512 -> osc.append(ch)
            }
            State.OSC_ESC -> if (ch == '\\') { finishOsc(); state = State.GROUND } else state = State.GROUND
        }
    }

    private fun finishOsc() {
        val payload = osc.toString()
        val semi = payload.indexOf(';')
        if (semi > 0) {
            val code = payload.substring(0, semi)
            val value = payload.substring(semi + 1)
            if (code == "0" || code == "2") title = value.trim()
        }
    }

    private fun putChar(ch: Char) {
        if (pendingWrap) {
            cursorCol = 0
            lineFeed()
            pendingWrap = false
        }
        val cell = grid[cursorRow][cursorCol]
        cell.ch = ch
        cell.fg = penFg
        cell.bg = penBg
        cell.bold = penBold
        cell.underline = penUnderline
        cell.inverse = penInverse
        if (cursorCol < cols - 1) {
            cursorCol++
        } else if (wrapEnabled) {
            pendingWrap = true
        }
    }

    private fun tab() {
        val next = ((cursorCol / 8) + 1) * 8
        cursorCol = if (next >= cols) cols - 1 else next
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorRow == scrollBottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
    }

    private fun scrollUp(count: Int) {
        repeat(count.coerceIn(1, rows)) {
            val top = grid[scrollTop]
            if (!usingAlt && scrollTop == 0) {
                scrollback.addLast(copyRow(top))
                while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
            }
            for (r in scrollTop until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = top.also { row -> row.forEach { it.reset() } }
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count.coerceIn(1, rows)) {
            val bottom = grid[scrollBottom]
            for (r in scrollBottom downTo scrollTop + 1) grid[r] = grid[r - 1]
            grid[scrollTop] = bottom.also { row -> row.forEach { it.reset() } }
        }
    }

    private fun handleCsi(raw: String, final: Char) {
        var body = raw
        var private = false
        if (body.startsWith("?")) { private = true; body = body.substring(1) }
        else if (body.startsWith(">") || body.startsWith("<") || body.startsWith("!")) {
            body = body.substring(1)
        }
        val args = if (body.isEmpty()) emptyList() else body.split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(i: Int, def: Int): Int {
            val v = args.getOrNull(i) ?: 0
            return if (v == 0) def else v
        }

        when (final) {
            'A' -> { cursorRow = (cursorRow - arg(0, 1)).coerceIn(scrollTop, scrollBottom); pendingWrap = false }
            'B' -> { cursorRow = (cursorRow + arg(0, 1)).coerceIn(scrollTop, scrollBottom); pendingWrap = false }
            'C' -> { cursorCol = (cursorCol + arg(0, 1)).coerceIn(0, cols - 1); pendingWrap = false }
            'D' -> { cursorCol = (cursorCol - arg(0, 1)).coerceIn(0, cols - 1); pendingWrap = false }
            'E' -> { cursorRow = (cursorRow + arg(0, 1)).coerceIn(scrollTop, scrollBottom); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - arg(0, 1)).coerceIn(scrollTop, scrollBottom); cursorCol = 0 }
            'G', '`' -> cursorCol = (arg(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (arg(1, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'J' -> eraseDisplay(args.getOrNull(0) ?: 0)
            'K' -> eraseLine(args.getOrNull(0) ?: 0)
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            'P' -> deleteChars(arg(0, 1))
            '@' -> insertChars(arg(0, 1))
            'X' -> eraseChars(arg(0, 1))
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))
            'r' -> {
                scrollTop = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (arg(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cursorRow = scrollTop
                cursorCol = 0
            }
            'm' -> applySgr(args)
            'h' -> if (private) setMode(args, true)
            'l' -> if (private) setMode(args, false)
            'n' -> when (args.getOrNull(0)) {
                5 -> onResponse?.invoke("\u001b[0n")
                6 -> onResponse?.invoke("\u001b[${cursorRow + 1};${cursorCol + 1}R")
            }
            's' -> { savedCursor[0] = cursorRow; savedCursor[1] = cursorCol }
            'u' -> {
                cursorRow = savedCursor[0].coerceIn(0, rows - 1)
                cursorCol = savedCursor[1].coerceIn(0, cols - 1)
            }
            else -> Unit
        }
    }

    private fun setMode(args: List<Int>, on: Boolean) {
        for (mode in args) {
            when (mode) {
                7 -> wrapEnabled = on
                25 -> cursorVisible = on
                47, 1047, 1049 -> switchAlt(on)
                1000, 1002, 1003, 1004, 1006, 2004 -> Unit // mouse/focus/paste: ignored
                else -> Unit
            }
        }
    }

    private fun switchAlt(on: Boolean) {
        if (on == usingAlt) return
        if (on) {
            alt = newGrid(cols, rows)
            grid = alt
            usingAlt = true
        } else {
            grid = main
            usingAlt = false
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
        }
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) grid[r].forEach { it.reset() }
            }
            1 -> {
                eraseLine(1)
                for (r in 0 until cursorRow) grid[r].forEach { it.reset() }
            }
            else -> grid.forEach { row -> row.forEach { it.reset() } }
        }
    }

    private fun eraseLine(mode: Int) {
        val row = grid[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until cols) row[c].reset()
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) row[c].reset()
            else -> row.forEach { it.reset() }
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (r in scrollBottom downTo cursorRow + 1) grid[r] = grid[r - 1]
            grid[cursorRow] = newRow(cols)
        }
        cursorCol = 0
    }

    private fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(count.coerceAtMost(scrollBottom - cursorRow + 1)) {
            for (r in cursorRow until scrollBottom) grid[r] = grid[r + 1]
            grid[scrollBottom] = newRow(cols)
        }
        cursorCol = 0
    }

    private fun deleteChars(count: Int) {
        val row = grid[cursorRow]
        val n = count.coerceAtMost(cols - cursorCol)
        for (c in cursorCol until cols - n) row[c].copyFrom(row[c + n])
        for (c in cols - n until cols) row[c].reset()
    }

    private fun insertChars(count: Int) {
        val row = grid[cursorRow]
        val n = count.coerceAtMost(cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + n) row[c].copyFrom(row[c - n])
        for (c in cursorCol until cursorCol + n) row[c].reset()
    }

    private fun eraseChars(count: Int) {
        val row = grid[cursorRow]
        for (c in cursorCol until (cursorCol + count).coerceAtMost(cols)) row[c].reset()
    }

    private fun applySgr(args: List<Int>) {
        if (args.isEmpty()) {
            resetPen()
            return
        }
        var i = 0
        while (i < args.size) {
            when (val code = args[i]) {
                0 -> resetPen()
                1 -> penBold = true
                2 -> penBold = false
                3 -> penBold = true
                4 -> penUnderline = true
                7 -> penInverse = true
                21, 22 -> { penBold = false }
                23 -> penBold = false
                24 -> penUnderline = false
                27 -> penInverse = false
                39 -> penFg = Cell.NO_COLOR
                49 -> penBg = Cell.NO_COLOR
                in 30..37 -> penFg = code - 30
                in 90..97 -> penFg = code - 90 + 8
                in 40..47 -> penBg = code - 40
                in 100..107 -> penBg = code - 100 + 8
                38, 48 -> {
                    val target = if (code == 38) true else false
                    when (args.getOrNull(i + 1)) {
                        5 -> {
                            val idx = args.getOrNull(i + 2) ?: 0
                            if (target) penFg = idx else penBg = idx
                            i += 2
                        }
                        2 -> {
                            // 24-bit colour: approximate to the nearest of the 16 base colours
                            val r = args.getOrNull(i + 2) ?: 0
                            val g = args.getOrNull(i + 3) ?: 0
                            val b = args.getOrNull(i + 4) ?: 0
                            val approx = nearestAnsi(r, g, b)
                            if (target) penFg = approx else penBg = approx
                            i += 4
                        }
                        else -> Unit
                    }
                }
            }
            i++
        }
    }

    private fun resetPen() {
        penFg = Cell.NO_COLOR
        penBg = Cell.NO_COLOR
        penBold = false
        penUnderline = false
        penInverse = false
    }

    private fun nearestAnsi(r: Int, g: Int, b: Int): Int {
        val base = intArrayOf(
            0, 0, 0, 128, 0, 0, 0, 128, 0, 128, 128, 0,
            0, 0, 128, 128, 0, 128, 0, 128, 128, 192, 192, 192,
            128, 128, 128, 255, 0, 0, 0, 255, 0, 255, 255, 0,
            0, 0, 255, 255, 0, 255, 0, 255, 255, 255, 255, 255,
        )
        var best = 7
        var bestDist = Int.MAX_VALUE
        for (i in 0 until 16) {
            val dr = r - base[i * 3]
            val dg = g - base[i * 3 + 1]
            val db = b - base[i * 3 + 2]
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    // ---- buffers -------------------------------------------------------------

    private fun newRow(width: Int): Array<Cell> = Array(width) { Cell() }

    private fun newGrid(width: Int, height: Int): Array<Array<Cell>> = Array(height) { newRow(width) }

    private fun copyRow(row: Array<Cell>): Array<Cell> = Array(row.size) { i -> Cell().also { it.copyFrom(row[i]) } }

    private fun resizeGrid(old: Array<Array<Cell>>, width: Int, height: Int): Array<Array<Cell>> {
        val fresh = newGrid(width, height)
        for (r in 0 until minOf(height, old.size)) {
            val from = old[r]
            val to = fresh[r]
            for (c in 0 until minOf(width, from.size)) to[c].copyFrom(from[c])
        }
        return fresh
    }

    companion object {
        const val MAX_SCROLLBACK = 2000
    }
}
