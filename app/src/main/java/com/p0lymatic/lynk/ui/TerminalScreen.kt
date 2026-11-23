package com.p0lymatic.lynk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.p0lymatic.lynk.ssh.ConnectionManager
import com.p0lymatic.lynk.ssh.RowSnapshot
import com.p0lymatic.lynk.ssh.TerminalSession
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Terminal screen: a real grid renderer on top of [VtScreen].
 *
 * Input goes through [TerminalInputView] (an InputConnection, not a hidden text field), so
 * keystrokes arrive as bytes, IME composition never fights the screen, and Ctrl combos work.
 */
@Composable
fun TerminalScreen(app: AppState, nav: Navigator, serverId: String) {
    val server = remember(serverId) { app.serverOf(serverId) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var status by remember { mutableStateOf(TerminalSession.Status.CONNECTING) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0L) }
    var rowsData by remember { mutableStateOf<List<RowSnapshot>>(emptyList()) }
    var fontSize by rememberSaveable { mutableStateOf(11f) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var cols by remember { mutableStateOf(80) }
    var rows by remember { mutableStateOf(24) }
    var cursorOn by remember { mutableStateOf(true) }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val inputHolder = remember { arrayOfNulls<TerminalInputView>(1) }

    fun send(bytes: ByteArray) {
        val target = session ?: return
        if (ctrlArmed && bytes.size == 1) {
            val code = bytes[0].toInt() and 0xFF
            val mapped = when {
                code in 'a'.code..'z'.code -> code - 'a'.code + 1
                code in 'A'.code..'Z'.code -> code - 'A'.code + 1
                else -> code
            }
            ctrlArmed = false
            target.sendBytes(byteArrayOf(mapped.toByte()))
        } else {
            target.sendBytes(bytes)
        }
    }

    LaunchedEffect(serverId, reloadKey) {
        val node = server
        if (node == null) {
            error = "Узел не найден"
            status = TerminalSession.Status.ERROR
            return@LaunchedEffect
        }
        session?.close()
        session = null
        error = null
        status = TerminalSession.Status.CONNECTING
        try {
            session = app.connections.openTerminal(node, cols, rows)
        } catch (t: Throwable) {
            error = ConnectionManager.describe(t)
            status = TerminalSession.Status.ERROR
        }
    }

    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        launch { current.status.collect { status = it } }
        launch { current.error.collect { error = it } }
        current.revision.collect { revision = it }
    }

    LaunchedEffect(revision) {
        rowsData = session?.screen?.snapshot() ?: emptyList()
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            cursorOn = !cursorOn
        }
    }

    LaunchedEffect(status) {
        if (status == TerminalSession.Status.CONNECTED) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    BackHandler { nav.pop() }

    Column(Modifier.fillMaxSize().background(LynkColors.termBg).imePadding()) {
        ScreenHeader(
            title = server?.name ?: "Терминал",
            subtitle = when {
                error != null -> error ?: "ошибка"
                status == TerminalSession.Status.CONNECTED -> "ssh · ${server?.user}@${server?.host}"
                status == TerminalSession.Status.CONNECTING -> "подключение…"
                else -> "сессия закрыта"
            },
            onBack = { nav.pop() },
            actions = {
                TextButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(7f) }) {
                    Text("A−", color = LynkColors.textDim, fontSize = 12.sp, fontFamily = Mono)
                }
                TextButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(20f) }) {
                    Text("A+", color = LynkColors.textDim, fontSize = 12.sp, fontFamily = Mono)
                }
                IconButton(onClick = { reloadKey++ }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Переподключиться",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val cellWidth = with(density) { (fontSize * 0.605f).sp.toDp() }
            val cellHeight = with(density) { (fontSize * 1.2f).sp.toDp() }
            val wantCols = (maxWidth / cellWidth).toInt().coerceIn(20, 400)
            val wantRows = (maxHeight / cellHeight).toInt().coerceIn(4, 200)

            LaunchedEffect(wantCols, wantRows) {
                if (wantCols != cols || wantRows != rows) {
                    cols = wantCols
                    rows = wantRows
                    session?.resize(wantCols, wantRows)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            runCatching { focusRequester.requestFocus() }
                            keyboard?.show()
                        }
                    },
            ) {
                if (rowsData.isEmpty()) {
                    Text(
                        text = when {
                            error != null -> error ?: ""
                            status == TerminalSession.Status.CONNECTING -> "Подключаюсь…"
                            else -> "Пустая сессия"
                        },
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        color = LynkColors.textMuted,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    rowsData.forEach { row ->
                        Text(
                            text = buildRow(row, cursorOn, fontSize),
                            fontFamily = Mono,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.2f).sp,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            AndroidView(
                factory = { context ->
                    TerminalInputView(context).also { view ->
                        view.onBytes = { bytes -> send(bytes) }
                        inputHolder[0] = view
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester),
            )
        }

        if (error != null && status != TerminalSession.Status.CONNECTED) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = error ?: "",
                    color = LynkColors.err,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { reloadKey++ }) {
                    Text("Повторить", color = LynkColors.text, fontSize = 12.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TermKey("Esc") { send(byteArrayOf(27)) }
            TermKey("Tab") { send(byteArrayOf(9)) }
            TermKey("Ctrl", active = ctrlArmed) { ctrlArmed = !ctrlArmed }
            TermKey("↑") { send(byteArrayOf(27, 91, 65)) }
            TermKey("↓") { send(byteArrayOf(27, 91, 66)) }
            TermKey("←") { send(byteArrayOf(27, 91, 68)) }
            TermKey("→") { send(byteArrayOf(27, 91, 67)) }
            TermKey("^C") { send(byteArrayOf(3)) }
            TermKey("^D") { send(byteArrayOf(4)) }
            TermKey("|") { send("|".toByteArray()) }
            TermKey("-") { send("-".toByteArray()) }
            TermKey("/") { send("/".toByteArray()) }
            TermKey("~") { send("~".toByteArray()) }
            TermKey("Вставить") {
                val text = clipboard.getText()?.text.orEmpty()
                if (text.isNotEmpty()) session?.send(text)
            }
            TermKey("⌨") {
                runCatching { focusRequester.requestFocus() }
                keyboard?.show()
            }
        }
    }
}

@Composable
private fun TermKey(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) LynkColors.textDim else LynkColors.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = Mono,
            fontSize = 11.5.sp,
            color = if (active) LynkColors.termBg else LynkColors.textDim,
            maxLines = 1,
        )
    }
}

/** Builds one styled row, with a block cursor when it sits on this line. */
private fun buildRow(row: RowSnapshot, cursorOn: Boolean, fontSize: Float): AnnotatedString {
    val text = row.text
    return buildAnnotatedString {
        append(text)
        row.spans.forEach { span ->
            val end = span.end.coerceAtMost(text.length)
            if (span.start >= end) return@forEach
            val fg = if (span.fg >= 0) ansiColor(span.fg) else null
            val bg = if (span.bg >= 0) ansiColor(span.bg) else null
            addStyle(
                SpanStyle(
                    color = if (span.inverse) (bg ?: LynkColors.termBg) else (fg ?: LynkColors.text),
                    background = when {
                        span.inverse -> fg ?: LynkColors.text
                        bg != null -> bg
                        else -> Color.Transparent
                    },
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    textDecoration = if (span.underline) TextDecoration.Underline else null,
                ),
                span.start,
                end,
            )
        }
        if (row.cursorCol >= 0 && cursorOn) {
            val at = row.cursorCol.coerceAtLeast(0)
            if (at >= length) append(" ".repeat(at - length + 1))
            addStyle(
                SpanStyle(background = LynkColors.text, color = LynkColors.termBg),
                at,
                (at + 1).coerceAtMost(length),
            )
        }
    }
}

/** Muted 16-colour palette plus a rough 256-colour fallback, matching the grey theme. */
private fun ansiColor(index: Int): Color {
    val base = listOf(
        Color(0xFF1C1C1F), Color(0xFFB4716F), Color(0xFF7FA98C), Color(0xFFB49A5E),
        Color(0xFF7C90B4), Color(0xFFA08BB5), Color(0xFF6FA3A8), Color(0xFFC9C9CF),
        Color(0xFF5A5A64), Color(0xFFD08A87), Color(0xFF93C2A0), Color(0xFFCBB071),
        Color(0xFF93A8CC), Color(0xFFB79FCB), Color(0xFF86BCC1), Color(0xFFE7E7EA),
    )
    return when {
        index in 0..15 -> base[index]
        index in 16..231 -> {
            val i = index - 16
            val r = if (i / 36 == 0) 0 else 55 + (i / 36) * 40
            val g = if ((i % 36) / 6 == 0) 0 else 55 + ((i % 36) / 6) * 40
            val b = if (i % 6 == 0) 0 else 55 + (i % 6) * 40
            Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
        }
        index in 232..255 -> {
            val level = 8 + (index - 232) * 10
            Color(0xFF000000.toInt() or (level shl 16) or (level shl 8) or level)
        }
        else -> LynkColors.text
    }
}
