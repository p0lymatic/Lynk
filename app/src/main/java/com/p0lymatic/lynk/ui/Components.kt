package com.p0lymatic.lynk.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    Box(Modifier.size(size).clip(CircleShape).background(color))
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