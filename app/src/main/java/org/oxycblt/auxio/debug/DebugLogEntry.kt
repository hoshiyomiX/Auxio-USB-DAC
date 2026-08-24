/*
 * Copyright (c) 2026 Auxio Project
 * DebugLogEntry.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Immutable snapshot of a single log entry captured by DebugLogTree. All fields are val —
// safe to read from any thread. formattedTimestamp is precomputed at creation time.
data class DebugLogEntry(
    val timestampMs: Long,
    val level: Int,
    val tag: String,
    val message: String,
    val throwableStack: String? = null,
) {
    val formattedTimestamp: String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))

    // Single-letter level code for compact display (V/D/I/W/E).
    val levelChar: Char
        get() =
            when (level) {
                2 -> 'V'
                3 -> 'D'
                4 -> 'I'
                5 -> 'W'
                6 -> 'E'
                else -> '?'
            }

    // Full line as it would appear in logcat: "HH:mm:ss.SSS D/Tag: message".
    fun toLogcatLine(): String = buildString {
        append(formattedTimestamp)
        append(' ')
        append(levelChar)
        append('/')
        append(tag)
        append(": ")
        append(message)
        if (throwableStack != null) {
            append('\n')
            append(throwableStack)
        }
    }
}
