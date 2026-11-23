package com.p0lymatic.lynk.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/** Transparent terminal touch target and text editor for hardware and software keyboards. */
class TerminalInputView(context: Context) : View(context) {

    var onScroll: ((lines: Int, x: Float, y: Float) -> Unit)? = null
    var onInput: (() -> Unit)? = null
    var cellHeightPx: Float = 1f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var lastY = 0f
    private var dragRemainder = 0f
    private var dragging = false

    var onBytes: ((ByteArray) -> Unit)? = null

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private var keyboardRequested = false
    private val showIme = Runnable {
        if (keyboardRequested && isAttachedToWindow && hasWindowFocus() && isFocused) {
            keyboardRequested = !imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downY = event.y
                lastY = event.y
                dragRemainder = 0f
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    lastY = downY + if (event.y > downY) touchSlop else -touchSlop
                }
                if (dragging) {
                    dragRemainder += lastY - event.y
                    lastY = event.y
                    val lines = (dragRemainder / cellHeightPx.coerceAtLeast(1f)).toInt()
                    if (lines != 0) {
                        dragRemainder -= lines * cellHeightPx.coerceAtLeast(1f)
                        onScroll?.invoke(lines, event.x, event.y)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging) performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        showKeyboard()
        return true
    }

    fun showKeyboard() {
        keyboardRequested = true
        requestFocus()
        scheduleKeyboard()
    }

    private fun scheduleKeyboard() {
        removeCallbacks(showIme)
        if (isAttachedToWindow && hasWindowFocus() && isFocused) post(showIme)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (keyboardRequested) {
            requestFocus()
            scheduleKeyboard()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && keyboardRequested) {
            requestFocus()
            scheduleKeyboard()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(showIme)
        super.onDetachedFromWindow()
    }

    fun hideKeyboard() {
        keyboardRequested = false
        removeCallbacks(showIme)
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, true) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                onInput?.invoke()
                return super.setComposingText(text, newCursorPosition)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Replace the local composing buffer, then forward the committed text once.
                editable?.clear()
                editable?.let { removeComposingSpans(it) }
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) emit(value)
                return true
            }

            // BaseInputConnection keeps preedit text locally; it must not reach the shell yet.
            override fun finishComposingText(): Boolean {
                val value = editable?.toString().orEmpty()
                super.finishComposingText()
                editable?.clear()
                if (value.isNotEmpty()) emit(value)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                emit("\r")
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!editable.isNullOrEmpty()) {
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }
                repeat(beforeLength.coerceIn(0, 64)) { emit("\u007f") }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (!editable.isNullOrEmpty()) {
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }
                return deleteSurroundingText(beforeLength, afterLength)
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
        onInput?.invoke()
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
        onBytes?.invoke(text.replace("\n", "\r").toByteArray(Charsets.UTF_8))
    }
}
