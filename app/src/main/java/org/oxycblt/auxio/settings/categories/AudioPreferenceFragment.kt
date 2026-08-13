/*
 * Copyright (c) 2023 Auxio Project
 * AudioPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings.categories

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/**
 * Audio settings interface.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class AudioPreferenceFragment : BasePreferenceFragment(R.xml.preferences_audio) {

    private val playbackModel: PlaybackViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Gray out the USB DAC toggle preference when no DAC is physically connected.
        // The auto-enable-on-plug-in behavior is handled centrally by
        // MainActivity.handleUsbDeviceAttached (which sets usbDacMode = true on ATTACHED
        // broadcast),
        // so this fragment only needs to reflect the connection state visually — when a DAC is
        // plugged in, the preference becomes enabled and the user can toggle it freely; when no
        // DAC is connected, the preference is disabled (grayed out) so the user cannot enable
        // bit-perfect mode without a DAC to send PCM to.
        collectImmediately(playbackModel.usbDacConnected) { connected ->
            findPreference<SwitchPreferenceCompat>(getString(R.string.set_key_usb_dac_mode))
                ?.apply {
                    isEnabled = connected
                    if (!connected) {
                        // If user disabled the toggle while a DAC was connected and then unplugged,
                        // leave the persisted value as-is (false). The toggle stays grayed out
                        // but unchecked, reflecting "no bit-perfect until a DAC is plugged in".
                        // When a DAC is plugged back in, MainActivity.handleUsbDeviceAttached
                        // will auto-set usbDacMode = true, which syncs back to this preference
                        // via SharedPreferences.OnSharedPreferenceChangeListener (registered
                        // internally by PreferenceFragmentCompat).
                        L.d("USB DAC not connected; disabling bit-perfect preference")
                    }
                }
        }
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_pre_amp)) {
            L.d("Navigating to pre-amp dialog")
            findNavController().navigateSafe(AudioPreferenceFragmentDirections.preAmpSettings())
        }
    }
}
