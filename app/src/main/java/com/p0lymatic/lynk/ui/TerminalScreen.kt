package com.p0lymatic.lynk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.data.TerminalSettings
import com.p0lymatic.lynk.ssh.ConnectionManager
import com.p0lymatic.lynk.ssh.RowSnapshot
import com.p0lymatic.lynk.ssh.TerminalSession
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Terminal screen: a real grid renderer on top of [com.p0lymatic.lynk.ssh.VtScreen].
 *
 * The keyboard is owned by a full-size [TerminalInputView] sitting above the text, so a tap
 * anywhere focuses it and opens the IME. Font, size, palette and cursor are configurable.
 */
@Composable
fun TerminalScreen(app: AppState, nav: Navigator, serverId: String) {
    val server = remember(serverId) { app.serverOf(serverId) }
    val data by app.store.state.collectAsStateWithLifecycle()
    val settings = data.terminal
    val scheme = remember(settings.schemeId) { schemeById(settings.schemeId) }
    val font = remember(settings.fontId) { fontById(settings.fontId) }
    val fontSize = settings.fontSize

    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var status by remember { mutableStateOf(TerminalSession.Status.CONNECTING) }
    var error by remember { mutableStateOf<String?>(null) }
    var rowsData by remember { mutableStateOf<List<RowSnapshot>>(emptyList()) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var cols by remember { mutableStateOf(80) }
    var rows by remember { mutableStateOf(24) }
    var cursorOn by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

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
        rowsData = emptyList()
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
        // Snapshot this session even when its initial revision equals the previous one's.
        current.revision.collect { rowsData = current.screen.snapshot() }
    }

    LaunchedEffect(settings.blink) {
        cursorOn = true
        if (settings.blink) {
            while (true) {
                delay(530)
                cursorOn = !cursorOn
            }
        }
    }

    LaunchedEffect(status) {
        if (status == TerminalSession.Status.CONNECTED) {
            inputHolder[0]?.showKeyboard()
        }
    }

    BackHandler {
        if (showSettings) showSettings = false else nav.pop()
    }

    Column(Modifier.fillMaxSize().background(scheme.background).imePadding()) {
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
                TextButton(onClick = { fontSizeStep(app, settings, -1f) }) {
                    Text("A−", color = scheme.foreground.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Mono)
                }
                TextButton(onClick = { fontSizeStep(app, settings, +1f) }) {
                    Text("A+", color = scheme.foreground.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = Mono)
                }
                IconButton(onClick = { showSettings = true }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Настройки терминала",
                        tint = scheme.foreground.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { reloadKey++ }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Переподключиться",
                        tint = scheme.foreground.copy(alpha = 0.7f),
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

            LaunchedEffect(session, wantCols, wantRows) {
                if (wantCols != cols || wantRows != rows) {
                    cols = wantCols
                    rows = wantRows
                }
                session?.resize(wantCols, wantRows)
            }

            Box(Modifier.fillMaxSize()) {
                if (rowsData.isEmpty()) {
                    Text(
                        text = when {
                            error != null -> error ?: ""
                            status == TerminalSession.Status.CONNECTING -> "Подключаюсь…"
                            else -> "Пустая сессия"
                        },
                        fontFamily = font.family,
                        fontSize = fontSize.sp,
                        color = scheme.foreground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    Column(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
                        rowsData.forEach { row ->
                            Text(
                                text = buildRow(row, cursorOn, settings, scheme),
                                fontFamily = font.family,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.2f).sp,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                // Transparent overlay receives the whole tap gesture above the rendered rows.
                AndroidView(
                    factory = { context ->
                        TerminalInputView(context).also { inputHolder[0] = it }
                    },
                    update = { view -> view.onBytes = { bytes -> send(bytes) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
                    Text("Повторить", color = scheme.foreground, fontSize = 12.sp)
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
            TermKey("Esc", scheme) { send(byteArrayOf(27)) }
            TermKey("Tab", scheme) { send(byteArrayOf(9)) }
            TermKey("Ctrl", scheme, active = ctrlArmed) { ctrlArmed = !ctrlArmed }
            TermKey("↑", scheme) { send(byteArrayOf(27, 91, 65)) }
            TermKey("↓", scheme) { send(byteArrayOf(27, 91, 66)) }
            TermKey("←", scheme) { send(byteArrayOf(27, 91, 68)) }
            TermKey("→", scheme) { send(byteArrayOf(27, 91, 67)) }
            TermKey("^C", scheme) { send(byteArrayOf(3)) }
            TermKey("^D", scheme) { send(byteArrayOf(4)) }
            TermKey("|", scheme) { send("|".toByteArray()) }
            TermKey("-", scheme) { send("-".toByteArray()) }
            TermKey("/", scheme) { send("/".toByteArray()) }
            TermKey("~", scheme) { send("~".toByteArray()) }
            TermKey("Вставить", scheme) {
                val text = clipboard.getText()?.text.orEmpty()
                if (text.isNotEmpty()) session?.send(text)
            }
            TermKey("⌨", scheme) { inputHolder[0]?.showKeyboard() }
        }
    }

    if (showSettings) {
        TerminalSettingsSheet(
            settings = settings,
            onChange = { app.store.updateTerminal(it) },
            onDismiss = {
                showSettings = false
                inputHolder[0]?.showKeyboard()
            },
        )
    }
}

private fun fontSizeStep(app: AppState, settings: TerminalSettings, delta: Float) {
    app.store.updateTerminal(settings.copy(fontSize = (settings.fontSize + delta).coerceIn(7f, 20f)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalSettingsSheet(
    settings: TerminalSettings,
    onChange: (TerminalSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LynkColors.surface,
        contentColor = LynkColors.text,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Терминал", fontSize = 16.sp, color = LynkColors.text)
            Spacer(Modifier.height(14.dp))

            SectionNote("Шрифт")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                terminalFonts.forEach { option ->
                    ChoiceChip(
                        label = option.label,
                        selected = option.id == settings.fontId,
                        mono = option.family,
                    ) { onChange(settings.copy(fontId = option.id)) }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionNote("Размер: ${settings.fontSize.toInt()}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChoiceChip("−", selected = false) {
                    onChange(settings.copy(fontSize = (settings.fontSize - 1f).coerceAtLeast(7f)))
                }
                Spacer(Modifier.width(8.dp))
                ChoiceChip("+", selected = false) {
                    onChange(settings.copy(fontSize = (settings.fontSize + 1f).coerceAtMost(20f)))
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Моноширинный предпочтительнее",
                    fontSize = 11.sp,
                    color = LynkColors.textMuted,
                )
            }

            Spacer(Modifier.height(14.dp))
            SectionNote("Цветовая схема")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                terminalSchemes.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { option ->
                            ChoiceChip(option.label, selected = option.id == settings.schemeId) {
                                onChange(settings.copy(schemeId = option.id))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionNote("Курсор")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Блок", selected = settings.cursorStyle == "block") {
                    onChange(settings.copy(cursorStyle = "block"))
                }
                ChoiceChip("Подчёркивание", selected = settings.cursorStyle == "underline") {
                    onChange(settings.copy(cursorStyle = "underline"))
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Мигание курсора", fontSize = 13.sp, color = LynkColors.text, modifier = Modifier.weight(1f))
                Switch(checked = settings.blink, onCheckedChange = { onChange(settings.copy(blink = it)) })
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { onChange(TerminalSettings()) }) {
                Text("Сбросить", color = LynkColors.textDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    mono: androidx.compose.ui.text.font.FontFamily? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) LynkColors.surfaceHigh else LynkColors.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontFamily = mono ?: Mono,
            color = if (selected) LynkColors.text else LynkColors.textDim,
            maxLines = 1,
        )
    }
}

@Composable
private fun TermKey(
    label: String,
    scheme: TermScheme,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) scheme.foreground.copy(alpha = 0.35f) else scheme.foreground.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = Mono,
            fontSize = 11.5.sp,
            color = scheme.foreground.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}

/** Builds one styled row, with a cursor when it sits on this line. */
private fun buildRow(
    row: RowSnapshot,
    cursorOn: Boolean,
    settings: TerminalSettings,
    scheme: TermScheme,
): AnnotatedString {
    val text = row.text
    return buildAnnotatedString {
        append(text)
        row.spans.forEach { span ->
            val end = span.end.coerceAtMost(text.length)
            if (span.start >= end) return@forEach
            val fg = if (span.fg >= 0) scheme.color(span.fg) else null
            val bg = if (span.bg >= 0) scheme.color(span.bg) else null
            addStyle(
                SpanStyle(
                    color = if (span.inverse) (bg ?: scheme.background) else (fg ?: scheme.foreground),
                    background = when {
                        span.inverse -> fg ?: scheme.foreground
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
            when (settings.cursorStyle) {
                "underline" -> addStyle(
                    SpanStyle(color = scheme.cursor, textDecoration = TextDecoration.Underline),
                    at,
                    (at + 1).coerceAtMost(length),
                )
                else -> addStyle(
                    SpanStyle(background = scheme.cursor, color = scheme.background),
                    at,
                    (at + 1).coerceAtMost(length),
                )
            }
        }
    }
}
