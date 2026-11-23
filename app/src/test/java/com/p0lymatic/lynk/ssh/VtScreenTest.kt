package com.p0lymatic.lynk.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VtScreenTest {

    private fun screen(cols: Int = 20, rows: Int = 5) = VtScreen(cols, rows)

    private fun VtScreen.text(): List<String> = snapshot().map { it.text }

    @Test
    fun `plain text and newline`() {
        val vt = screen()
        vt.feed("hello\r\nworld")
        assertEquals("hello", vt.text()[0])
        assertEquals("world", vt.text()[1])
        assertEquals(1, vt.cursorRow)
        assertEquals(5, vt.cursorCol)
    }

    @Test
    fun `carriage return overwrites the line`() {
        val vt = screen()
        vt.feed("abcdef\rXY")
        assertEquals("XYcdef", vt.text()[0])
    }

    @Test
    fun `sgr sets colour runs`() {
        val vt = screen()
        vt.feed("\u001b[31mred\u001b[0m plain")
        val row = vt.snapshot()[0]
        assertEquals("red plain", row.text)
        assertTrue(row.spans.any { it.fg == 1 && it.start == 0 && it.end == 3 })
        assertTrue(row.spans.any { it.fg == -1 && it.start == 3 })
    }

    @Test
    fun `cursor addressing and erase line`() {
        val vt = screen()
        vt.feed("aaaaaaaaaa\r\nbbbbbbbbbb\r\ncccccccccc")
        vt.feed("\u001b[2;1H\u001b[K")
        assertEquals("", vt.text()[1])
        vt.feed("\u001b[3;5H")
        assertEquals(2, vt.cursorRow)
        assertEquals(4, vt.cursorCol)
    }

    @Test
    fun `wrap at the right edge`() {
        val vt = screen(cols = 5, rows = 3)
        vt.feed("abcdefgh")
        assertEquals("abcde", vt.text()[0])
        assertEquals("fgh", vt.text()[1])
    }

    @Test
    fun `alternate screen is separate and restores`() {
        val vt = screen()
        vt.feed("main")
        vt.feed("\u001b[?1049h")
        vt.feed("\u001b[HALT")
        assertEquals("ALT", vt.text()[0])
        vt.feed("\u001b[?1049l")
        assertEquals("main", vt.text()[0])
    }

    @Test
    fun `scroll region keeps the top line`() {
        val vt = screen(rows = 4)
        vt.feed("one\r\ntwo\r\nthree\r\nfour")
        // Restrict scrolling to the last two rows, then add a line at the bottom.
        vt.feed("\u001b[3;4r\u001b[4;1H\nnext")
        assertEquals("one", vt.text()[0])
        assertEquals("two", vt.text()[1])
        assertEquals("four", vt.text()[2])
        assertEquals("next", vt.text()[3])
    }

    @Test
    fun `resize keeps content`() {
        val vt = screen(cols = 10, rows = 3)
        vt.feed("abc\r\ndef")
        vt.resize(6, 4)
        assertEquals("abc", vt.text()[0])
        assertEquals("def", vt.text()[1])
    }

    @Test
    fun `concurrent feed and snapshot never throw`() {
        val vt = VtScreen(80, 24)
        val writer = Thread {
            repeat(400) { i ->
                vt.feed("line $i\r\n")
                vt.feed("\u001b[31mred\u001b[0m text\r\n")
            }
        }
        writer.start()
        repeat(600) {
            val rows = vt.snapshot()
            assertEquals(24, rows.size)
        }
        writer.join(5_000)
        assertTrue(vt.snapshot().isNotEmpty())
    }
}
