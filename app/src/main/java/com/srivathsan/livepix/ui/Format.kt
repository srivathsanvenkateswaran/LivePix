package com.srivathsan.livepix.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Date strings in the style Photos uses. */
object Format {
    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    private fun dayStart(ms: Long) = cal(ms).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** "Today", "Yesterday", "Monday", "28 October", or "28 October 2024". */
    fun viewerTitle(ms: Long, now: Long = System.currentTimeMillis()): String {
        val days = TimeUnit.MILLISECONDS.toDays(dayStart(now) - dayStart(ms))
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days in 2..6 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(ms)
            cal(ms).get(Calendar.YEAR) == cal(now).get(Calendar.YEAR) -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(ms)
            else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(ms)
        }
    }

    fun time(ms: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(ms)
    fun monthYear(ms: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(ms)
    fun month(ms: Long): String = SimpleDateFormat("MMMM", Locale.getDefault()).format(ms)
    fun year(ms: Long): Int = cal(ms).get(Calendar.YEAR)
    fun monthKey(ms: Long): Int = cal(ms).let { it.get(Calendar.YEAR) * 12 + it.get(Calendar.MONTH) }
    fun fullDate(ms: Long): String = SimpleDateFormat("EEEE, d MMMM yyyy • HH:mm", Locale.getDefault()).format(ms)
    fun shortDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(ms)

    fun dateRange(first: Long, last: Long): String {
        if (first == 0L) return ""
        val a = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(first)
        val b = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(last)
        return if (a == b) a else "$a – $b"
    }

    fun duration(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun bytes(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1f GB".format(n / 1e9)
        n >= 1_000_000 -> "%.1f MB".format(n / 1e6)
        n >= 1_000 -> "%.0f KB".format(n / 1e3)
        else -> "$n B"
    }

    fun count(n: Int, singular: String, plural: String = singular + "s") =
        "%,d %s".format(n, if (n == 1) singular else plural)
}
