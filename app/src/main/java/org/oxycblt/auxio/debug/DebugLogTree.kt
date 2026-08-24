/*
 * Copyright (c) 2026 Auxio Project
 * DebugLogTree.kt is part of Auxio.
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

import java.util.ArrayDeque
import timber.log.Timber

// Timber.Tree that captures app-internal log messages into an in-memory ring buffer (5000
// entries, ~1MB). Enables in-built logcat-style debugging without root or READ_LOGS permission.
// Intercepts log calls at the Timber layer before they reach platform logcat. Does NOT capture
// native C++ logs (bypasses Timber), system/other-app logs, or pre-plant logs.
class DebugLogTree : Timber.Tree() {

    private val buffer = ArrayDeque<DebugLogEntry>(BUFFER_CAPACITY)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val entry =
            DebugLogEntry(
                timestampMs = System.currentTimeMillis(),
                level = priority,
                tag = tag ?: "Unknown",
                message = message,
                throwableStack = t?.stackTraceToString(),
            )
        synchronized(buffer) {
            if (buffer.size >= BUFFER_CAPACITY) {
                buffer.pollFirst()
            }
            buffer.addLast(entry)
        }
    }

    // Return a defensive snapshot of the current buffer contents as a List. Ordered
    // oldest-to-newest (matches logcat reading order). Safe to call from any thread.
    fun snapshot(): List<DebugLogEntry> = synchronized(buffer) { buffer.toList() }

    // Current number of entries in the buffer.
    fun size(): Int = synchronized(buffer) { buffer.size }

    // Clear all entries from the buffer.
    fun clear() {
        synchronized(buffer) { buffer.clear() }
    }

    // Export the entire buffer as a single logcat-formatted string.
    fun exportToString(): String = buildString {
        val entries = snapshot()
        for (entry in entries) {
            append(entry.toLogcatLine())
            append('\n')
        }
    }

    companion object {
        // Maximum number of log entries retained in the ring buffer. 5000 entries ≈ 1MB
        // memory at ~200 bytes/entry average. Covers 10-15 minutes of active USB DAC
        // debugging with comfortable headroom.
        const val BUFFER_CAPACITY = 5000

        @Volatile private var instance: DebugLogTree? = null

        // Get the singleton instance, or null if not yet planted.
        fun get(): DebugLogTree? = instance

        // Plant the singleton instance. Called from Auxio.onCreate.
        fun plant() {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = DebugLogTree()
                        Timber.plant(instance!!)
                    }
                }
            }
        }
    }
}
