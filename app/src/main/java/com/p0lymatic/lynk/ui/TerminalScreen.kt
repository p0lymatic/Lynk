package com.p0lymatic.lynk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p0lymatic.lynk.ssh.ConnectionManager
import com.p0lymatic.lynk.ssh.TerminalLine
import com.p0lymatic.lynk.ssh.TerminalScreen as TermScreen
import com.p0lymatic.lynk.ssh.TerminalSession
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val ANSI_PALETTE = intArrayOf(
    0xFF2A2A30.toInt(), 0xFFB4716F.toInt(), 0xFF7FA98C.toInt(), 0xFFB49A5E.toInt(),
    0xFF6F8FAF.toInt(), 0xFF9A7FA9.toInt(), 0xFF6F9FA9.toInt(), 0xFFD6D6DB.toInt(),
    0xFF55555E.toInt(), 0xFFC98A87.toInt(), 0xFF98BFA4.toInt(), 0xFFC9B27E.toInt(),
    0xFF8FA8BF.toInt(), 0xFFB39ABF.toInt(), 0xFF8FB9BF.toInt(), 0xFFF2F2F4.toInt(),
)

/**
 * Interactive shell. Colour output is kept, but muted to the same grey family as the rest of Lynk
 * so that `ls --color` stays readable without turning the screen into a rainbow.
 */
@Composable
fun TerminalScreen(app: AppState, nav: Navigator, serverId: String) {
    val server = app.store.server(serverId)
    var fontSize by rememberSaveable { mutableStateOf(13f) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var revision by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf(TerminalSession.Status.CONNECTING) }
    var error by remember { mutableStateOf<String?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableStateOf(0) }
    var cols by remember { mutableStateOf(80) }
    var rows by remember { mutableStateOf(24) }

    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    LaunchedEffect(serverId, reloadKey) {
        val node = server ?: run {
            error = "Узел не найден"
            return@LaunchedEffect
        }
        error = null
        status = TerminalSession.Status.CONNECTING
        try {
            val started = withContext(Dispatchers.IO) {
                app.connections.openTerminal(node, cols, rows)
            }
            session = started
        } catch (t: Throwable) {
            error = ConnectionManager.describe(t)
            status = TerminalSession.Status.ERROR
        }
    }

    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        current.revision.collect { revision = it }
    }

    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        current.status.collect { status = it }
    }

    val term: TermScreen? = session?.screen
    val totalLines = term?.totalLines() ?: 0

    LaunchedEffect(revision, totalLines) {
        if (autoScroll && totalLines > 0) {
            listState.scrollToItem((totalLines - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(listState.canScrollForward) {
        autoScroll = !listState.canScrollForward
    }

    LaunchedEffect(cols, rows, session) {
        if (session == null) return@LaunchedEffect
        delay(300)
        session?.resize(cols, rows)
    }

    fun sendText(text: String) {
        val current = session ?: return
        if (ctrl && text.isNotEmpty()) {
            val first = text.first()
            val code = when {
                first in 'a'..'z' -> first.code - 'a'.code + 1
                first in 'A'..'Z' -> first.code - 'A'.code + 1
                first == '[' -> 27
                first == ' ' -> 0
                else -> first.code
            }
            current.sendBytes(byteArrayOf(code.toByte()))
            if (text.length > 1) current.send(text.substring(1))
            ctrl = false
        } else {
            current.send(text)
        }
    }

    fun sendSequence(vararg bytes: Int) {
        session?.sendBytes(ByteArray(bytes.size) { bytes[it].toByte() })
    }

    BackHandler { nav.pop() }

    Column(Modifier.fillMaxSize().background(LynkColors.termBg).imePadding()) {
        ScreenHeader(
            title = server?.name ?: "Терминал",
            subtitle = when {
                error != null -> "ошибка"
                status == TerminalSession.Status.CONNECTED -> "ssh · ${server?.user}@${server?.host}"
                status == TerminalSession.Status.CONNECTING -> "подключение…"
                else -> "сессия закрыта"
            },
            onBack = { nav.pop() },
            actions = {
                TextButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(9f) }) {
                    Text("A−", color = LynkColors.textDim, fontSize = 12.sp, fontFamily = Mono)
                }
                TextButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(22f) }) {
                    Text("A+", color = LynkColors.textDim, fontSize = 12.sp, fontFamily = Mono)
                }
                IconButton(onClick = { reloadKey++ }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Переподключиться",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(17.dp),
                    )
                }
            },
        )

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val cellWidth = with(density) { (fontSize * 0.6f).dp.toPx() }
            val cellHeight = with(density) { (fontSize * 1.3f).dp.toPx() }
            val computedCols = max(20, ((widthPx - 16f) / cellWidth).toInt())
            val computedRows = max(8, ((heightPx - 8f) / cellHeight).toInt())

            LaunchedEffect(computedCols, computedRows) {
                cols = computedCols
                rows = computedRows
            }

            val textStyle = TextStyle(
                fontFamily = Mono,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.3f).sp,
                color = LynkColors.text,
            )

            if (error != null && session == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Терминал не открылся", fontSize = 15.sp, color = LynkColors.textDim)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error!!,
                        fontSize = 12.sp,
                        color = LynkColors.textMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    GhostButton("Повторить", onClick = { reloadKey++ })
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        items(totalLines) { index ->
                            val line = term?.lineAt(index)
                            if (line != null) {
                                Text(
                                    text = buildLine(
                                        line = line,
                                        index = index,
                                        cursorAbsRow = term.absoluteCursorRow(),
                                        cursorCol = term.cursorCol,
                                        showCursor = term.cursorVisible && status == TerminalSession.Status.CONNECTED,
                                    ),
                                    style = textStyle,
                                    softWrap = false,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    if (!autoScroll) {
                        Surface2(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            onClick = {
                                autoScroll = true
                                app.scope.launch { listState.scrollToItem((totalLines - 1).coerceAtLeast(0)) }
                            },
                        )
                    }
                }
            }
        }

        // Key strip: what a phone keyboard cannot send on its own.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LynkColors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyCap("Esc") { sendSequence(27) }
            KeyCap("Tab") { sendSequence(9) }
            KeyCap("Ctrl", active = ctrl) { ctrl = !ctrl }
            KeyCap("/") { sendText("/") }
            KeyCap("-") { sendText("-") }
            KeyCap("|") { sendText("|") }
            Spacer(Modifier.weight(1f))
            KeyCap("←") { sendSequence(27, 91, 68) }
            KeyCap("↑") { sendSequence(27, 91, 65) }
            KeyCap("↓") { sendSequence(27, 91, 66) }
            KeyCap("→") { sendSequence(27, 91, 67) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LynkColors.surface)
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyCap("Paste") {
                val text = clipboard.getText()?.text
                if (!text.isNullOrEmpty()) sendText(text)
            }
            KeyCap("Home") { sendSequence(27, 91, 72) }
            KeyCap("End") { sendSequence(27, 91, 70) }
            KeyCap("PgUp") { sendSequence(27, 91, 53, 126) }
            KeyCap("PgDn") { sendSequence(27, 91, 54, 126) }
            Spacer(Modifier.weight(1f))
            KeyCap("↵") { sendSequence(13) }
        }

        BasicTextField(
            value = input,
            onValueChange = { value ->
                if (value.isNotEmpty()) {
                    sendText(value)
                    input = ""
                }
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.None,
                autoCorrectEnabled = false,
            ),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        )
    }
}

@Composable
private fun KeyCap(label: String, active: Boolean = false, onClick: () -> Unit) {
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
            color = if (active) LynkColors.bg else LynkColors.textDim,
            maxLines = 1,
        )
    }
}

@Composable
private fun Surface2(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LynkColors.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text("↓ вниз", fontFamily = Mono, fontSize = 11.sp, color = LynkColors.textDim)
    }
}

private fun buildLine(
    line: TerminalLine,
    index: Int,
    cursorAbsRow: Int,
    cursorCol: Int,
    showCursor: Boolean,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < line.chars.size) {
        val color = line.color[i].toInt() and 0x0F
        val bold = line.bold[i]
        val isCursor = showCursor && index == cursorAbsRow && i == cursorCol
        var j = i + 1
        while (j < line.chars.size) {
            val sameColor = (line.color[j].toInt() and 0x0F) == color
            val sameBold = line.bold[j] == bold
            val sameCursor = !(showCursor && index == cursorAbsRow && j == cursorCol)
            if (!sameColor || !sameBold || !sameCursor) break
            j++
        }
        val text = String(line.chars, i, j - i)
        val fg = if (color == 0) LynkColors.termBg else Color(ANSI_PALETTE[color.coerceIn(0, 15)])
        pushStyle(
            SpanStyle(
                color = if (isCursor) LynkColors.termBg else fg,
                background = if (isCursor) LynkColors.textDim else Color.Unspecified,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
        )
        append(text)
        pop()
        i = j
    }
}