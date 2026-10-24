package com.jainam.studentos.focusshield.core.utils

fun formatTime(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) "%d:%02d:%02d".format(hrs, mins, secs)
    else "%02d:%02d".format(mins, secs)
}

fun getDayStart(timestamp: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = timestamp
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

fun formatDuration(seconds: Long): String {
    return when {
        seconds >= 3600 -> "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        seconds >= 60 -> "%d:%02d".format(seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }
}
