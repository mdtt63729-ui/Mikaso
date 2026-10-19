package com.mikaso.app.data

import java.util.Locale

/** "0 B", "345 KB", "1.2 MB", "1.4 GB" … */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var i = 0
    while (value >= 1024.0 && i < units.lastIndex) {
        value /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}

/** A format row's size. Null when unknown; "≈" when the size is only an estimate. */
fun formatSize(size: Long?, estimated: Boolean): String? {
    if (size == null || size <= 0) return null
    val text = formatBytes(size)
    return if (estimated) "≈ $text" else text
}

/** Download speed from bytes per second (what yt-dlp reports). */
fun formatSpeed(bytesPerSecond: Double): String {
    if (bytesPerSecond <= 0.0) return ""
    return formatBytes(bytesPerSecond.toLong()) + "/s"
}

/** Time left as m:ss, or h:mm:ss when it is over an hour. */
fun formatEta(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
