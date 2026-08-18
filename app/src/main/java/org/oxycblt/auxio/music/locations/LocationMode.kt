/*
 * Copyright (c) 2025 Auxio Project
 * LocationMode.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music.locations

import org.oxycblt.auxio.IntegerTable

/**
 * Represents the mode for loading music locations.
 *
 * As of 2026-08-18, the legacy [SAF] / "File Picker" option was removed. It produced content URIs
 * that broke native libopus/libFLAC engines (which require absolute file paths via
 * `ParcelFileDescriptor.open()`), and on Android 10+ scoped storage the
 * `MediaStore.Audio.Media.DATA` column returned null for SAF-picked documents, defeating the
 * path-based dispatch in `UsbAudioSink.configure()`.
 *
 * Only [MEDIA_STORE] remains. The legacy `LOCATION_MODE_SAF` int code is silently migrated to
 * `MEDIA_STORE` in [fromInt] so existing users keep working without manual intervention.
 */
enum class LocationMode {
    /** Use system MediaStore database to load all music (default, and only supported mode). */
    MEDIA_STORE;

    val intCode: Int
        get() =
            when (this) {
                MEDIA_STORE -> IntegerTable.LOCATION_MODE_MEDIA_STORE
            }

    companion object {
        /**
         * Decode a stored int back to a [LocationMode]. Silently migrates the legacy
         * `LOCATION_MODE_SAF` int code (from the removed File Picker mode) to [MEDIA_STORE] so
         * existing users are auto-migrated on first read after upgrade.
         */
        fun fromInt(int: Int): LocationMode {
            return when (int) {
                IntegerTable.LOCATION_MODE_MEDIA_STORE,
                IntegerTable.LOCATION_MODE_SAF -> MEDIA_STORE
                else -> MEDIA_STORE
            }
        }
    }
}
