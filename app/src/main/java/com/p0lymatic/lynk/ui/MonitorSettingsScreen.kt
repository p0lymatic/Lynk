package com.p0lymatic.lynk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.p0lymatic.lynk.data.GaugeStyle
import com.p0lymatic.lynk.data.MetricKind
import com.p0lymatic.lynk.data.MetricPref
import com.p0lymatic.lynk.data.MonitorSettings
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono

private val ChipShape = RoundedCornerShape(7.dp)

/** Where the monitor is configured. Everything is written straight to the store. */
@Composable
fun MonitorSettingsScreen(app: AppState, nav: Navigator) {
    val data by app.store.state.collectAsStateWithLifecycle()
    val settings = data.monitor

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "Мониторинг",
            subtitle = "плитки, стиль, порядок",
            onBack = { nav.pop() },
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("Главная")
            LynkCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                ToggleRow(
                    label = "Показывать на главной",
                    checked = settings.enabled,
                    onCheckedChange = { app.store.updateMonitor(settings.copy(enabled = it)) },
                )
                DividerLine()
                ToggleRow(
                    label = "Подключаться автоматически",
                    checked = settings.autoConnect,
                    onCheckedChange = { app.store.updateMonitor(settings.copy(autoConnect = it)) },
                )
            }

            SectionLabel("Обновление")
            LynkCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                ChoiceRow(
                    label = "Интервал",
                    options = listOf(2, 5, 10, 30),
                    selected = settings.refreshSec,
                    optionText = { "$it с" },
                    onSelect = { app.store.updateMonitor(settings.copy(refreshSec = it)) },
                )
                Spacer(Modifier.height(14.dp))
                ChoiceRow(
                    label = "Колонки",
                    options = listOf(2, 3, 4),
                    selected = settings.columns,
                    optionText = { it.toString() },
                    onSelect = { app.store.updateMonitor(settings.copy(columns = it)) },
                )
            }

            SectionLabel("Плитки")
            settings.metrics.forEachIndexed { index, pref ->
                MetricPrefCard(
                    pref = pref,
                    canMoveUp = index > 0,
                    canMoveDown = index < settings.metrics.lastIndex,
                    onMoveUp = { app.store.updateMonitor(settings.copy(metrics = settings.metrics.swapped(index, index - 1))) },
                    onMoveDown = { app.store.updateMonitor(settings.copy(metrics = settings.metrics.swapped(index, index + 1))) },
                    onChange = { updated ->
                        app.store.updateMonitor(
                            settings.copy(
                                metrics = settings.metrics.toMutableList().also { it[index] = updated },
                            ),
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Сбросить",
                onClick = {
                    app.store.updateMonitor(MonitorSettings(metrics = MonitorSettings.defaultMetricPrefs()))
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.5.sp,
            color = LynkColors.text,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LynkColors.accent,
                checkedTrackColor = LynkColors.textMuted,
                uncheckedThumbColor = LynkColors.textMuted,
                uncheckedTrackColor = LynkColors.surfaceHigh,
            ),
        )
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Int>,
    selected: Int,
    optionText: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = LynkColors.textMuted,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                Chip(
                    text = optionText(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun MetricPrefCard(
    pref: MetricPref,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onChange: (MetricPref) -> Unit,
) {
    val detailAlpha = if (pref.visible) 1f else 0.5f
    LynkCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = metricTitle(pref.kind),
                    fontSize = 13.5.sp,
                    color = if (pref.visible) LynkColors.text else LynkColors.textDim,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = kindHint(pref.kind),
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    color = LynkColors.textMuted,
                    maxLines = 1,
                )
            }
            Column {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Выше",
                        tint = if (canMoveUp) LynkColors.textDim else LynkColors.border,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Ниже",
                        tint = if (canMoveDown) LynkColors.textDim else LynkColors.border,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = pref.visible,
                onCheckedChange = { onChange(pref.copy(visible = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LynkColors.accent,
                    checkedTrackColor = LynkColors.textMuted,
                    uncheckedThumbColor = LynkColors.textMuted,
                    uncheckedTrackColor = LynkColors.surfaceHigh,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth().alpha(detailAlpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Стиль",
                fontSize = 11.sp,
                color = LynkColors.textMuted,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GaugeStyle.entries.forEach { style ->
                    Chip(
                        text = styleTitle(style),
                        selected = pref.style == style,
                        onClick = { onChange(pref.copy(style = style)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().alpha(detailAlpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Показывать",
                fontSize = 11.sp,
                color = LynkColors.textMuted,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(
                    text = "значение",
                    selected = pref.showValue,
                    onClick = { onChange(pref.copy(showValue = !pref.showValue)) },
                )
                Chip(
                    text = "подпись",
                    selected = pref.showLabel,
                    onClick = { onChange(pref.copy(showLabel = !pref.showLabel)) },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(ChipShape)
            .background(if (selected) LynkColors.surfaceHigh else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (selected) LynkColors.border else LynkColors.borderSoft, ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontFamily = Mono,
            color = if (selected) LynkColors.text else LynkColors.textDim,
            maxLines = 1,
        )
    }
}

private fun styleTitle(style: GaugeStyle): String = when (style) {
    GaugeStyle.RING -> "Кольцо"
    GaugeStyle.BAR -> "Полоса"
    GaugeStyle.TEXT -> "Текст"
}

private fun kindHint(kind: MetricKind): String = when (kind) {
    MetricKind.CPU -> "cpu %"
    MetricKind.RAM -> "ram"
    MetricKind.SWAP -> "swap"
    MetricKind.DISK -> "disk"
    MetricKind.LOAD -> "load avg"
    MetricKind.TEMP -> "temp"
    MetricKind.NET -> "net"
    MetricKind.UPTIME -> "uptime"
}

private fun <T> List<T>.swapped(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().also {
        val tmp = it[from]
        it[from] = it[to]
        it[to] = tmp
    }
}