package com.p0lymatic.lynk.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenTest {

    private fun screen(cols: Int = 10, rows: Int = 4) = TerminalScreen(cols, rows)

    private fun TerminalScreen.rowText(row: Int): String =
        String(lineAt(scrollbackSize + row).chars).trimEnd()

    private fun TerminalScreen.scrollbackText(index: Int): String =
        String(lineAt(index).chars).trimEnd()

    @Test
    fun `long line wraps on the next printed character`() {
        val s = screen(cols = 5, rows = 3)
        s.feed("abcde")

        assertEquals(4, s.cursorCol)
        assertEquals(0, s.cursorRow)
        assertEquals("abcde", s.rowText(0))

        s.feed("f")

        assertEquals(1, s.cursorRow)
        assertEquals(1, s.cursorCol)
        assertEquals("f", s.rowText(1))
    }

    @Test
    fun `carriage return overwrites the current row`() {
        val s = screen()
        s.feed("hello")
        s.feed("\rHi")

        assertEquals("Hillo", s.rowText(0))
        assertEquals(2, s.cursorCol)
    }

    @Test
    fun `newline moves down without returning to column zero`() {
        val s = screen()
        s.feed("ab\ncd")

        assertEquals("ab", s.rowText(0))
        assertEquals("  cd", s.rowText(1))
        assertEquals(4, s.cursorCol)
        assertEquals(1, s.cursorRow)
    }

    @Test
    fun `sgr maps ansi colours onto xterm indices`() {
        val s = screen()
        s.feed("\u001b[31mR\u001b[92mB\u001b[1mX\u001b[0mN")

        val line = s.lineAt(0)
        assertEquals(1, line.color[0].toInt())
        assertEquals(10, line.color[1].toInt())
        assertTrue(line.bold[2])
        assertEquals(7, line.color[3].toInt())
        assertFalse(line.bold[3])
    }

    @Test
    fun `cursor position sequence moves the cursor`() {
        val s = screen(cols = 10, rows = 4)
        s.feed("\u001b[2;3HX")

        assertEquals("  X", s.rowText(1))
        assertEquals(1, s.cursorRow)
        assertEquals(3, s.cursorCol)
    }

    @Test
    fun `erase in line variants clear the expected span`() {
        val tail = screen(cols = 6, rows = 2)
        tail.feed("abcdef\u001b[3G\u001b[K")
        assertEquals("ab", tail.rowText(0))

        val head = screen(cols = 6, rows = 2)
        head.feed("abcdef\u001b[1;3H\u001b[1K")
        assertEquals("   def", String(head.lineAt(0).chars))

        val all = screen(cols = 6, rows = 2)
        all.feed("abcdef\u001b[2K")
        assertEquals("", all.rowText(0))
    }

    @Test
    fun `scrolling pushes top lines into scrollback`() {
        val s = screen(cols = 6, rows = 2)
        s.feed("one\r\ntwo\r\nthree\r\nfour")

        assertEquals(2, s.scrollbackSize)
        assertEquals("one", s.scrollbackText(0))
        assertEquals("two", s.scrollbackText(1))
        assertEquals("three", s.rowText(0))
        assertEquals("four", s.rowText(1))
        assertEquals(2 + 2, s.totalLines())
        assertEquals(3, s.absoluteCursorRow())
    }

    @Test
    fun `scrollback is capped at four hundred lines`() {
        val s = screen(cols = 4, rows = 2)
        repeat(500) { s.feed("l$it\r\n") }

        assertEquals(400, s.scrollbackSize)
        assertEquals("l99", s.scrollbackText(0))
    }

    @Test
    fun `resize keeps content and clamps the cursor`() {
        val s = screen(cols = 10, rows = 4)
        s.feed("abc")
        s.feed("\u001b[4;9H")

        s.resize(5, 2)

        assertEquals(5, s.cols)
        assertEquals(2, s.rows)
        assertEquals(1, s.cursorRow)
        assertEquals(4, s.cursorCol)
        assertEquals("abc", s.rowText(0))
        assertEquals(2, s.totalLines())
    }

    @Test
    fun `resize grown adds blank rows`() {
        val s = screen(cols = 10, rows = 2)
        s.feed("x")

        s.resize(12, 5)

        assertEquals(5, s.totalLines())
        assertEquals("x", s.rowText(0))
        assertEquals("", s.rowText(4))
        assertEquals(12, s.lineAt(4).chars.size)
    }

    @Test
    fun `utf8 cyrillic survives split byte feeds`() {
        val s = screen(cols = 10, rows = 2)
        val bytes = "привет".toByteArray(Charsets.UTF_8)
        // Split inside the two-byte sequence of 'п'.
        s.feed(bytes.copyOfRange(0, 1))
        s.feed(bytes.copyOfRange(1, 5))
        s.feed(bytes.copyOfRange(5, bytes.size))

        assertEquals("привет", s.rowText(0))
        assertEquals(6, s.cursorCol)
    }

    @Test
    fun `snapshotText trims trailing spaces`() {
        val s = screen(cols = 8, rows = 3)
        s.feed("ab\r\ncd")

        assertEquals("ab\ncd\n", s.snapshotText())
    }

    @Test
    fun `alternate screen hides and restores the primary buffer`() {
        val s = screen(cols = 8, rows = 3)
        s.feed("primary")
        s.feed("\u001b[?1049h")
        assertEquals("", s.rowText(0))

        s.feed("full")
        s.feed("\u001b[?1049l")

        assertEquals("primary", s.rowText(0))
        assertEquals(7, s.cursorCol)
    }

    @Test
    fun `scroll region confines cursor movement`() {
        val s = screen(cols = 8, rows = 5)
        s.feed("\u001b[2;4r")
        s.feed("\u001b[1;1HX")

        assertEquals(1, s.cursorRow)
        assertEquals("X", s.rowText(1))
    }

    @Test
    fun `reset clears screen scrollback and attributes`() {
        val s = screen(cols = 4, rows = 2)
        s.feed("\u001b[31mone\r\ntwo\r\nthree")
        s.feed("\u001b[2J\u001bc")

        assertEquals(0, s.scrollbackSize)
        assertEquals(2, s.totalLines())
        assertEquals("", s.rowText(0))
        assertEquals("", s.rowText(1))
        assertEquals(0, s.cursorRow)
        assertEquals(0, s.cursorCol)
        assertTrue(s.cursorVisible)

        s.feed("z")
        assertEquals(7, s.lineAt(0).color[0].toInt())
    }

    @Test
    fun `osc sequence is ignored`() {
        val s = screen()
        s.feed("\u001b]0;title\u0007ok")

        assertEquals("ok", s.rowText(0))
    }
}