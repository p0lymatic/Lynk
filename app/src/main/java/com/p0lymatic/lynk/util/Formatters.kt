package com.p0lymatic.lynk.util

import kotlin.math.abs

/** Human-readable elapsed time: "12с", "4м", "3ч 12м", "2д 4ч". */
fun formatUptime(sinceMillis: Long): String {
    if (sinceMillis <= 0L) return "0с"
    val totalSeconds = sinceMillis / 1000
    val days = totalSeconds / 86_400
    val hours = totalSeconds % 86_400 / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "${days}д ${hours}ч"
        hours > 0 -> "${hours}ч ${minutes}м"
        minutes > 0 -> "${minutes}м"
        else -> "${seconds}с"
    }
}

/** "127.0.0.1:3000" */
fun portLabel(host: String, port: Int): String = "$host:$port"

/** Keeps the algorithm prefix, abbreviates the digest: "SHA256:abcd1234…wxyz". */
fun shortFingerprint(fp: String): String {
    val text = fp.trim()
    if (text.isEmpty()) return ""
    val colon = text.lastIndexOf(':')
    val prefix = if (colon >= 0) text.substring(0, colon + 1) else ""
    val body = if (colon >= 0) text.substring(colon + 1) else text
    if (body.length <= 12) return prefix + body
    return prefix + body.take(8) + "…" + body.takeLast(4)
}

/** Russian plural form: plural(3, "узел", "узла", "узлов") == "узла". */
fun plural(n: Int, one: String, few: String, many: String): String {
    val value = abs(n)
    val lastTwo = value % 100
    val last = value % 10
    return when {
        lastTwo in 11..14 -> many
        last == 1 -> one
        last in 2..4 -> few
        else -> many
    }
}