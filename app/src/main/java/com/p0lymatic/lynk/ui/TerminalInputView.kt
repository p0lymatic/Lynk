package com.p0lymatic.lynk.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * Invisible, full-size view that owns the keyboard for the terminal.
 *
 * Full-size on purpose: a 1 dp view cannot reliably take focus, and the IME refuses to open for
 * something it cannot see. Terminals also want finished text, not IME composition — with
 * [InputType.TYPE_NULL] the keyboard commits characters as they are typed.
 */
class TerminalInputView(context: Context) : View(context) {

    var onBytes: ((ByteArray) -> Unit)? = null

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!view.isFocused) view.requestFocus()
                showKeyboard()
            }
            false
        }
    }

    fun showKeyboard() {
        if (!isFocused) requestFocus()
        post { runCatching { imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) } }
    }

    fun hideKeyboard() {
        runCatching { imm.hideSoftInputFromWindow(windowToken, 0) }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) emit(value)
                return true
            }

            /** Composition is swallowed: the terminal only sees committed text. */
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true

            override fun finishComposingText(): Boolean = true

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 64)) { emit("\u007f") }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handle(event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        handle(event)
        return true
    }

    private fun handle(event: KeyEvent) {
        // Ctrl+letter → control code (Ctrl-C = 3, Ctrl-D = 4 …).
        if (event.isCtrlPressed) {
            val ch = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
            if (ch in 'a'.code..'z'.code) {
                emit(String(byteArrayOf((ch - 'a'.code + 1).toByte())))
                return
            }
        }
        val sequence = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            else -> {
                val unicode = event.unicodeChar
                if (unicode != 0) String(Character.toChars(unicode)) else return
            }
        }
        emit(sequence)
    }

    private fun emit(text: String) {
        if (text.isEmpty()) return
        onBytes?.invoke(text.toByteArray(Charsets.UTF_8))
    }
}
