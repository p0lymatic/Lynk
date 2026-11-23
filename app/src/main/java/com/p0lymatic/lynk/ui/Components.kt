package com.p0lymatic.lynk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p0lymatic.lynk.ssh.ConnectionManager
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono

private val CardShape = RoundedCornerShape(14.dp)
private val FieldShape = RoundedCornerShape(10.dp)

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = LynkColors.textMuted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun LynkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(CardShape)
        .background(LynkColors.surface)
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(modifier = clickable.padding(contentPadding), content = content)
}

@Composable
fun LynkCardBorder(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = LynkColors.surface,
        border = BorderStroke(1.dp, LynkColors.borderSoft),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Surface(
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (enabled && !loading) LynkColors.accent else LynkColors.surfaceHigh,
        onClick = { if (enabled && !loading) onClick() },
        enabled = enabled && !loading,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.6.dp,
                    color = LynkColors.bg,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                color = if (enabled && !loading) Color(0xFF121214) else LynkColors.textMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (danger) LynkColors.err.copy(alpha = 0.5f) else LynkColors.border),
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (danger) LynkColors.err else LynkColors.textDim,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = if (!enabled) LynkColors.textMuted else if (danger) LynkColors.err else LynkColors.text,
                fontSize = 13.5.sp,
            )
        }
    }
}

@Composable
fun LynkField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    isPassword: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, color = LynkColors.textMuted, fontSize = 12.sp) },
        placeholder = placeholder?.let { { Text(it, color = LynkColors.textMuted, fontSize = 13.sp) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = if (mono) Mono else FontFamily.SansSerif,
            fontSize = if (mono) 13.5.sp else 14.sp,
            color = LynkColors.text,
        ),
        shape = FieldShape,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LynkColors.textDim,
            unfocusedBorderColor = LynkColors.border,
            cursorColor = LynkColors.accent,
            focusedContainerColor = LynkColors.surface,
            unfocusedContainerColor = LynkColors.surface,
            focusedTextColor = LynkColors.text,
            unfocusedTextColor = LynkColors.text,
        ),
        visualTransformation = if (isPassword) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
    )
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = LynkColors.textDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LynkColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        color = LynkColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}

@Composable
fun StatusDot(phase: ConnectionManager.Phase, size: Dp = 7.dp) {
    val color = when (phase) {
        ConnectionManager.Phase.CONNECTED -> LynkColors.ok
        ConnectionManager.Phase.CONNECTING -> LynkColors.warn
        ConnectionManager.Phase.ERROR -> LynkColors.err
        ConnectionManager.Phase.IDLE -> LynkColors.textMuted
    }
    val alpha = if (phase == ConnectionManager.Phase.CONNECTING) {
        rememberInfiniteTransition(label = "dot").animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(680, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        ).value
    } else {
        1f
    }
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = alpha)))
}

@Composable
fun StatusLine(phase: ConnectionManager.Phase, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(phase)
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 12.sp, color = LynkColors.textDim, fontFamily = Mono)
    }
}

@Composable
fun MonoBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = LynkColors.surfaceHigh,
    ) {
        Text(
            text = text,
            fontFamily = Mono,
            fontSize = 11.sp,
            color = LynkColors.textDim,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun KeyRow(label: String, value: String, mono: Boolean = true, valueColor: Color = LynkColors.text) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.5.sp, color = LynkColors.textMuted, modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontFamily = if (mono) Mono else FontFamily.SansSerif,
            fontSize = 12.5.sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    text: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = LynkColors.textDim)
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            fontSize = 12.5.sp,
            color = LynkColors.textMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp,
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            GhostButton(text = actionText, onClick = onAction)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "Удалить",
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LynkColors.surfaceHigh,
        titleContentColor = LynkColors.text,
        textContentColor = LynkColors.textDim,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        text = { Text(text, fontSize = 13.sp, lineHeight = 19.sp) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmText, color = if (danger) LynkColors.err else LynkColors.text)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = LynkColors.textDim) }
        },
    )
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LynkColors.borderSoft))
}

@Composable
fun SectionNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp),
        fontSize = 11.5.sp,
        color = LynkColors.textMuted,
        lineHeight = 16.sp,
    )
}

@Composable
fun EditorColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        content = content,
    )
}

/**
 * Browser-style top panel: leading icon, a pill-shaped address field and trailing actions.
 * When [onAddressChange] is null the address is read-only text (the start screen shows the
 * address of the page it will open, not the one on screen).
 */
@Composable
fun BrowserToolbar(
    address: String,
    onAddressChange: ((String) -> Unit)?,
    onSubmit: () -> Unit,
    addressPrefix: String? = null,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(54.dp).background(LynkColors.bg).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(4.dp))
        AddressField(
            address = address,
            onAddressChange = onAddressChange,
            onSubmit = onSubmit,
            addressPrefix = addressPrefix,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        actions()
    }
}

@Composable
private fun AddressField(
    address: String,
    onAddressChange: ((String) -> Unit)?,
    onSubmit: () -> Unit,
    addressPrefix: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(20.dp),
        color = LynkColors.surfaceHigh,
        border = BorderStroke(1.dp, LynkColors.border),
    ) {
        val focus = LocalFocusManager.current
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!addressPrefix.isNullOrBlank()) {
                Text(
                    text = addressPrefix,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    color = LynkColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
                Text(" · ", fontFamily = Mono, fontSize = 11.sp, color = LynkColors.textMuted)
            }
            if (onAddressChange == null) {
                Text(
                    text = address,
                    fontFamily = Mono,
                    fontSize = 12.5.sp,
                    color = LynkColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (address.isEmpty()) {
                        Text(
                            text = "порт или хост:порт",
                            fontFamily = Mono,
                            fontSize = 12.5.sp,
                            color = LynkColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = address,
                        onValueChange = onAddressChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = Mono,
                            fontSize = 12.5.sp,
                            color = LynkColors.text,
                        ),
                        cursorBrush = SolidColor(LynkColors.accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                focus.clearFocus()
                                onSubmit()
                            },
                        ),
                    )
                }
            }
        }
    }
}

// ---- operating system badge ---------------------------------------------------

/**
 * Small monochrome badge that tells you what is running on the other side.
 * Simple geometry for the families where a shape is recognisable, a three-letter
 * monogram for everything else — no logos, no colours, fits the strict grey theme.
 */
@Composable
fun OsIcon(osId: String, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    val tint = LynkColors.textDim
    val id = osId.lowercase()
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(LynkColors.surfaceHigh)
            .border(1.dp, LynkColors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (id) {
            "ubuntu" -> Canvas(Modifier.size(size * 0.66f)) {
                val stroke = this.size.minDimension * 0.14f
                drawCircle(tint, radius = this.size.minDimension * 0.30f, style = Stroke(stroke))
                val orbit = this.size.minDimension * 0.40f
                listOf(0f, 2.0944f, 4.18879f).forEach { angle ->
                    drawCircle(
                        color = tint,
                        radius = stroke,
                        center = Offset(
                            x = center.x + kotlin.math.cos(angle) * orbit,
                            y = center.y + kotlin.math.sin(angle) * orbit,
                        ),
                    )
                }
            }
            "arch", "manjaro" -> Canvas(Modifier.size(size * 0.62f)) {
                val w = this.size.width
                val h = this.size.height
                val path = Path().apply {
                    moveTo(w / 2f, h * 0.10f)
                    lineTo(w * 0.96f, h * 0.94f)
                    lineTo(w * 0.60f, h * 0.94f)
                    lineTo(w / 2f, h * 0.66f)
                    lineTo(w * 0.40f, h * 0.94f)
                    lineTo(w * 0.04f, h * 0.94f)
                    close()
                }
                drawPath(path, tint)
            }
            "alpine" -> Canvas(Modifier.size(size * 0.66f)) {
                val w = this.size.width
                val h = this.size.height
                val left = Path().apply {
                    moveTo(w * 0.06f, h * 0.90f)
                    lineTo(w * 0.34f, h * 0.28f)
                    lineTo(w * 0.62f, h * 0.90f)
                    close()
                }
                val right = Path().apply {
                    moveTo(w * 0.44f, h * 0.90f)
                    lineTo(w * 0.66f, h * 0.52f)
                    lineTo(w * 0.94f, h * 0.90f)
                    close()
                }
                drawPath(left, tint)
                drawPath(right, tint)
            }
            "raspbian", "raspberrypi" -> Canvas(Modifier.size(size * 0.62f)) {
                val r = this.size.minDimension * 0.18f
                drawCircle(tint, r, Offset(center.x - r * 1.1f, center.y - r * 0.7f))
                drawCircle(tint, r, Offset(center.x + r * 1.1f, center.y - r * 0.7f))
                drawCircle(tint, r, Offset(center.x, center.y + r))
            }
            "windows" -> Canvas(Modifier.size(size * 0.60f)) {
                val gap = this.size.minDimension * 0.08f
                val cell = (this.size.minDimension - gap) / 2f
                listOf(0f to 0f, cell + gap to 0f, 0f to cell + gap, cell + gap to cell + gap).forEach { (dx, dy) ->
                    drawRect(
                        color = tint,
                        topLeft = Offset(dx, dy),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
            "macos", "darwin" -> Canvas(Modifier.size(size * 0.62f)) {
                drawCircle(tint, radius = this.size.minDimension * 0.30f, center = Offset(center.x, center.y + this.size.minDimension * 0.06f))
                drawLine(
                    color = LynkColors.surfaceHigh,
                    start = Offset(center.x, center.y - this.size.minDimension * 0.22f),
                    end = Offset(center.x + this.size.minDimension * 0.14f, center.y - this.size.minDimension * 0.40f),
                    strokeWidth = this.size.minDimension * 0.10f,
                )
            }
            else -> {
                val mono = osMonogram(id)
                Text(
                    text = mono,
                    fontFamily = Mono,
                    fontSize = (size.value * if (mono.length >= 4) 0.24f else 0.34f).sp,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun osMonogram(id: String): String = when (id) {
    "debian" -> "DEB"
    "fedora" -> "FED"
    "centos", "rocky", "almalinux", "rhel", "redhat" -> "RH"
    "opensuse", "sles" -> "SUSE"
    "freebsd", "openbsd", "netbsd" -> "BSD"
    "gentoo" -> "GNT"
    "void" -> "VOID"
    "nixos" -> "NIX"
    "linux" -> "LX"
    "mint" -> "MINT"
    "kali" -> "KALI"
    "pop" -> "POP"
    "" , "unknown" -> "?"
    else -> id.take(3).uppercase()
}