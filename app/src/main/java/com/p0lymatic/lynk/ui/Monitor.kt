package com.p0lymatic.lynk.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p0lymatic.lynk.data.GaugeStyle
import com.p0lymatic.lynk.data.MetricKind
import com.p0lymatic.lynk.data.MetricPref
import com.p0lymatic.lynk.data.MonitorSettings
import com.p0lymatic.lynk.ssh.NodeMetrics
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import com.p0lymatic.lynk.util.formatUptime
import java.util.Locale

/** Ring starts at the lower-left corner and sweeps 270° clockwise. */
private const val ARC_START = 135f
private const val ARC_SWEEP = 270f

private const val WARN_AT = 70f
private const val ERR_AT = 88f

fun metricTitle(kind: MetricKind): String = when (kind) {
    MetricKind.CPU -> "ЦП"
    MetricKind.RAM -> "ОЗУ"
    MetricKind.SWAP -> "Своп"
    MetricKind.DISK -> "Диск"
    MetricKind.LOAD -> "Нагрузка"
    MetricKind.TEMP -> "Темп."
    MetricKind.NET -> "Сеть"
    MetricKind.UPTIME -> "Аптайм"
}

/** Colour of a gauge: calm below 70 %, warning below 88 %, alarm above. */
fun metricColor(percent: Float?): Color = when {
    percent == null -> LynkColors.textMuted
    percent >= ERR_AT -> LynkColors.err
    percent >= WARN_AT -> LynkColors.warn
    else -> LynkColors.ok
}

fun metricPercent(kind: MetricKind, metrics: NodeMetrics?): Float? = when (kind) {
    MetricKind.CPU -> metrics?.cpuPercent
    MetricKind.RAM -> metrics?.ramPercent
    MetricKind.SWAP -> metrics?.swapPercent
    MetricKind.DISK -> metrics?.diskPercent
    MetricKind.LOAD -> metrics?.loadPercent
    MetricKind.TEMP -> metrics?.tempC?.let { (it).coerceIn(0f, 100f) }
    MetricKind.NET -> null
    MetricKind.UPTIME -> null
}

private fun gb(value: Float?): String =
    if (value == null) "—" else String.format(Locale.US, "%.1f", value)

private fun rate(bytes: Long?): String {
    if (bytes == null) return "—"
    val kb = bytes / 1024f
    return when {
        kb >= 1024f -> String.format(Locale.US, "%.1f МБ/с", kb / 1024f)
        kb >= 10f -> String.format(Locale.US, "%.0f КБ/с", kb)
        else -> String.format(Locale.US, "%.1f КБ/с", kb)
    }
}

fun metricValueText(kind: MetricKind, metrics: NodeMetrics?): String {
    if (metrics == null) return "—"
    return when (kind) {
        MetricKind.CPU -> metrics.cpuPercent?.let { "${it.toInt()}%" } ?: "—"
        MetricKind.RAM -> when {
            metrics.ramUsedMb != null && metrics.ramTotalMb != null ->
                "${gb(metrics.ramUsedMb / 1024f)} / ${gb(metrics.ramTotalMb / 1024f)} ГБ"
            else -> "—"
        }
        MetricKind.SWAP -> metrics.swapPercent?.let { "${it.toInt()}%" } ?: "—"
        MetricKind.DISK -> when {
            metrics.diskPercent != null && metrics.diskTotalGb != null ->
                "${metrics.diskPercent.toInt()}% · ${gb(metrics.diskUsedGb)} / ${gb(metrics.diskTotalGb)}"
            metrics.diskPercent != null -> "${metrics.diskPercent.toInt()}%"
            else -> "—"
        }
        MetricKind.LOAD -> metrics.load1?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
        MetricKind.TEMP -> metrics.tempC?.let { "${it.toInt()}°C" } ?: "—"
        MetricKind.NET -> when {
            metrics.rxBps != null || metrics.txBps != null ->
                "↓${rate(metrics.rxBps)} ↑${rate(metrics.txBps)}"
            else -> "—"
        }
        MetricKind.UPTIME -> metrics.uptimeSec?.let { formatUptime(it * 1000L) } ?: "—"
    }
}

/**
 * Short form for the ring: it has room for a percentage or a couple of glyphs, not for
 * "7.0 / 7.7 ГБ". The detailed numbers belong to the bar and text styles.
 */
fun metricRingText(kind: MetricKind, metrics: NodeMetrics?): String {
    if (metrics == null) return "—"
    return when (kind) {
        MetricKind.CPU, MetricKind.RAM, MetricKind.SWAP, MetricKind.DISK ->
            metricPercent(kind, metrics)?.let { "${it.toInt()}%" } ?: "—"
        MetricKind.LOAD -> metrics.load1?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
        MetricKind.TEMP -> metrics.tempC?.let { "${it.toInt()}°" } ?: "—"
        MetricKind.NET -> when {
            metrics.rxBps != null -> shortRate(metrics.rxBps)
            else -> "—"
        }
        MetricKind.UPTIME -> metrics.uptimeSec?.let { shortUptime(it) } ?: "—"
    }
}

private fun shortRate(bytes: Long): String {
    val kb = bytes / 1024f
    return when {
        kb >= 1024f -> String.format(Locale.US, "%.1fМ", kb / 1024f)
        kb >= 10f -> String.format(Locale.US, "%.0fК", kb)
        else -> String.format(Locale.US, "%.1fК", kb)
    }
}

private fun shortUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> "${days}д"
        hours > 0 -> "${hours}ч"
        else -> "${minutes}м"
    }
}

@Composable
fun MetricTile(pref: MetricPref, metrics: NodeMetrics?, modifier: Modifier = Modifier) {
    when (pref.style) {
        GaugeStyle.RING -> RingTile(pref, metrics, modifier)
        GaugeStyle.BAR -> BarTile(pref, metrics, modifier)
        GaugeStyle.TEXT -> TextTile(pref, metrics, modifier)
    }
}

@Composable
fun MetricsGrid(settings: MonitorSettings, metrics: NodeMetrics?, modifier: Modifier = Modifier) {
    val visible = settings.metrics.filter { it.visible }
    if (visible.isEmpty()) {
        Text(
            text = "Плитки выключены — включи их в настройках мониторинга",
            fontSize = 11.sp,
            color = LynkColors.textMuted,
            modifier = modifier,
        )
        return
    }
    val columns = settings.columns.coerceIn(2, 4)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        visible.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                row.forEach { pref ->
                    MetricTile(pref, metrics, Modifier.weight(1f))
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RingTile(pref: MetricPref, metrics: NodeMetrics?, modifier: Modifier = Modifier) {
    val percent = metricPercent(pref.kind, metrics)
    val target = ((percent ?: 0f) / 100f).coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "ring",
    )
    val color = metricColor(percent)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp)) {
                val stroke = 5.5.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = LynkColors.border,
                    startAngle = ARC_START,
                    sweepAngle = ARC_SWEEP,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (percent != null) {
                    drawArc(
                        color = color,
                        startAngle = ARC_START,
                        sweepAngle = ARC_SWEEP * progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (pref.showValue) {
                    Text(
                        text = metricRingText(pref.kind, metrics),
                        fontFamily = Mono,
                        fontSize = 13.sp,
                        color = LynkColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
                if (pref.showLabel) {
                    Text(
                        text = metricTitle(pref.kind),
                        fontSize = 8.5.sp,
                        color = LynkColors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun BarTile(pref: MetricPref, metrics: NodeMetrics?, modifier: Modifier = Modifier) {
    val percent = metricPercent(pref.kind, metrics)
    val target = ((percent ?: 0f) / 100f).coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "bar",
    )
    val color = metricColor(percent)

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pref.showLabel) {
                Text(
                    text = metricTitle(pref.kind),
                    fontSize = 10.5.sp,
                    color = LynkColors.textDim,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (pref.showValue) {
                Text(
                    text = metricValueText(pref.kind, metrics),
                    fontFamily = Mono,
                    fontSize = 10.5.sp,
                    color = LynkColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LynkColors.border),
        ) {
            if (percent != null) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun TextTile(pref: MetricPref, metrics: NodeMetrics?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pref.showValue) {
                Text(
                    text = metricValueText(pref.kind, metrics),
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    color = LynkColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (pref.showLabel) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = metricTitle(pref.kind),
                    fontSize = 9.5.sp,
                    color = LynkColors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}