package com.kevin.tvcleanerbackup.utils

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object FormatUtils {
    fun humanReadableBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val units = arrayOf("Ko", "Mo", "Go", "To")
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size)
        val value = bytes / 1024.0.pow(exp.toDouble())
        return String.format(Locale.FRANCE, "%.1f %s", value, units[exp - 1])
    }
}
