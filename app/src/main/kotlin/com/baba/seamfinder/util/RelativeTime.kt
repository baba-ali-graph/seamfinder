package com.baba.seamfinder.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Human-friendly last-used labels. Avoids awkward values like "0 minutes ago".
 */
fun formatLastUsed(millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - millis).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "Yesterday"
        days < 7 -> "${days}d ago"
        days < 30 -> {
            val weeks = (days / 7).coerceAtLeast(1)
            if (weeks == 1L) "1w ago" else "${weeks}w ago"
        }
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}
