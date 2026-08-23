/*
 * Copyright (c) 2024 Auxio Project
 * LocationsDialog.kt is part of Auxio.
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

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.R as MR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogMusicLocationsBinding
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.mediastore.MediaStore
import timber.log.Timber as L

/**
 * Dialog for configuring music source locations.
 *
 * As of 2026-08-18, the legacy "File Picker" (Storage Access Framework) mode was removed. The
 * dialog now only manages the System Database (MediaStore) mode:
 * - Filter mode (Include / Exclude folders from MediaStore scan)
 * - "Exclude non-music" toggle
 *
 * The removed SAF mode produced content URIs that broke native libopus/libFLAC engines on Android
 * 10+ scoped storage (MediaStore.Audio.Media.DATA column returned null for SAF-picked documents,
 * defeating the path-based dispatch in `UsbAudioSink.configure()`). Users on the legacy SAF mode
 * are silently migrated to MediaStore via `LocationMode.fromInt()`.
 *
 * Existing SAF-specific UI (mode toggle button group, include/exclude folder lists, multithread and
 * with-hidden switches) has been removed from this dialog and its layout XML. The layout's
 * remaining relevant sections are: storage permission card, extras dropdown, filter mode, filter
 * list, and exclude-non-music toggle.
 */
@AndroidEntryPoint
class LocationsDialog : ViewBindingMaterialDialogFragment<DialogMusicLocationsBinding>() {

    private val filterLocationListener =
        object : LocationAdapter.Listener {
            override fun onRemoveLocation(location: Location) {
                filterLocationAdapter.remove(location as Location.Unopened)
                updateSaveButtonState()
            }
        }

    private val filterLocationAdapter: LocationAdapter<Location.Unopened> =
        LocationAdapter(filterLocationListener)
    private var localOnlyOpenDocumentTreeLauncher: ActivityResultLauncher<Uri?>? = null
    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    @Inject lateinit var musicSettings: MusicSettings

    private var isIncludeMode = true
    private var hasStoragePermission = false
    private var isExtrasExpanded = true
    private var pendingLocationCallback: ((Location.Unopened) -> Unit)? = null
    private var permissionGrantedInSession = false

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogMusicLocationsBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.set_locations)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(R.string.lbl_save) { _, _ -> saveChanges() }
    }

    override fun onBindingCreated(
        binding: DialogMusicLocationsBinding,
        savedInstanceState: Bundle?,
    ) {
        // SAF/file-picker launcher removed — only the local-only document tree launcher
        // (used by MediaStore filter list) remains.
        // TODO: Add failure mode for introduction of third-party filters in system loader
        localOnlyOpenDocumentTreeLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                addDocumentTreeUriToDirs(uri, true)
            }

        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                L.d("Storage permission granted: $isGranted")
                hasStoragePermission = isGranted
                if (isGranted && !permissionGrantedInSession) {
                    permissionGrantedInSession = true
                }
                updatePermissionDependentUI(binding)
                updatePermissionCardColors(binding)
                updatePermissionCardVisibility(binding)
                updateSaveButtonState()
            }

        binding.locationsFilterRecycler.apply {
            adapter = filterLocationAdapter
            itemAnimator = null
        }

        // Load initial state from MusicSettings
        loadInitialState(binding)

        // Set up string resources for the remaining (MediaStore-only) UI.
        binding.locationsExcludeModeHeader.setText(R.string.set_filter_mode)
        binding.locationsExcludeModeExclude.setText(R.string.set_include)
        binding.locationsExcludeModeInclude.setText(R.string.set_exclude)
        binding.locationsFilterListHeader.setText(R.string.set_folders_to_load)
        binding.locationsFilterAdd.contentDescription = getString(R.string.desc_add_folder)
        binding.locationsExtrasDropdown.setText(R.string.set_extra_settings)

        // Set up extras dropdown click listener
        binding.locationsExtrasDropdown.setOnClickListener {
            isExtrasExpanded = !isExtrasExpanded
            updateExtrasVisibility(binding)
        }

        binding.locationsExcludeModeExclude.setOnClickListener {
            updateFilterMode(binding, include = true)
        }
        binding.locationsExcludeModeInclude.setOnClickListener {
            updateFilterMode(binding, include = false)
        }

        // Set up add folder button for the MediaStore filter list
        binding.locationsFilterAdd.setOnClickListener {
            pendingLocationCallback = { location ->
                filterLocationAdapter.add(location)
                updateSaveButtonState()
            }
            onNewLocation(localOnlyOpenDocumentTreeLauncher)
        }

        // Set up grant permission card click
        binding.locationsPermsCard.setOnClickListener { requestStoragePermission() }

        // Initialize UI state — System Database mode only, no mode toggle.
        updateExcludeModeUI(binding)
        updatePermissionDependentUI(binding)
        updatePermissionCardColors(binding)
        updatePermissionCardVisibility(binding)
        updateExtrasVisibility(binding)
        updateSaveButtonState()
    }

    private fun loadInitialState(binding: DialogMusicLocationsBinding) {
        // Load MediaStore data
        musicSettings.mediaStoreQuery.let { query ->
            filterLocationAdapter.addAll(query.filtered)
            binding.locationsExcludeNonMusicSwitch.isChecked = query.excludeNonMusic

            isIncludeMode = query.mode == MediaStore.FilterMode.INCLUDE
            binding.locationsExcludeModeExclude.isChecked = isIncludeMode
            binding.locationsExcludeModeInclude.isChecked = !isIncludeMode
        }

        // Check storage permission status
        hasStoragePermission = checkStoragePermission()
    }

    private fun updateFilterMode(binding: DialogMusicLocationsBinding, include: Boolean) {
        // Enforce "selection required" behavior.
        binding.locationsExcludeModeExclude.isChecked = include
        binding.locationsExcludeModeInclude.isChecked = !include

        isIncludeMode = include
        updateExcludeModeUI(binding)
    }

    override fun onStart() {
        super.onStart()
        // Update save button state after dialog is shown
        updateSaveButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // TODO
    }

    override fun onDestroyBinding(binding: DialogMusicLocationsBinding) {
        super.onDestroyBinding(binding)
        localOnlyOpenDocumentTreeLauncher = null
        storagePermissionLauncher = null
        binding.locationsFilterRecycler.adapter = null
    }

    private fun onNewLocation(launcher: ActivityResultLauncher<Uri?>?) {
        L.d("Opening launcher")
        val launcher = requireNotNull(launcher) { "Document tree launcher was not available" }

        try {
            launcher.launch(null)
        } catch (e: ActivityNotFoundException) {
            requireContext().showToast(R.string.err_no_app)
        }
    }

    private fun addDocumentTreeUriToDirs(uri: Uri?, disableThirdParty: Boolean) {
        if (uri == null) {
            L.d("No URI given (user closed the dialog)")
            pendingLocationCallback = null
            return
        }
        val context = requireContext()
        val location = Location.Unopened.from(context, uri)

        if (location.path.volume is Volume.ThirdParty && disableThirdParty) {
            requireContext().showToast(R.string.err_bad_location)
            pendingLocationCallback = null
            return
        }
        pendingLocationCallback?.invoke(location)
        pendingLocationCallback = null
    }

    private fun updateExcludeModeUI(binding: DialogMusicLocationsBinding) {
        with(binding) {
            if (isIncludeMode) {
                locationsExcludeModeDesc.setText(R.string.lng_include_folders)
            } else {
                locationsExcludeModeDesc.setText(R.string.lng_exclude_folders)
            }
        }
    }

    private fun updatePermissionDependentUI(binding: DialogMusicLocationsBinding) {
        with(binding) {
            // System Database mode requires storage permission to enable the filter list.
            val isEnabled = hasStoragePermission

            locationsExcludeModeHeader.isEnabled = isEnabled
            locationsExcludeModeGroup.isEnabled = isEnabled
            locationsExcludeModeDesc.isEnabled = isEnabled
            locationsExcludeModeExclude.isEnabled = isEnabled
            locationsExcludeModeInclude.isEnabled = isEnabled

            locationsFilterListHeader.isEnabled = isEnabled
            locationsFilterAdd.isEnabled = isEnabled
            locationsFilterRecycler.isEnabled = isEnabled

            locationsExcludeNonMusicTitle.isEnabled = isEnabled
            locationsExcludeNonMusicDesc.isEnabled = isEnabled
            locationsExcludeNonMusic.isEnabled = isEnabled
        }
    }

    private fun updatePermissionCardColors(binding: DialogMusicLocationsBinding) {
        val context = requireContext()
        with(binding.locationsPermsCard) {
            if (hasStoragePermission) {
                // Has permission - use secondary colors
                setCardBackgroundColor(context.getAttrColorCompat(MR.attr.colorSecondaryContainer))
                binding.locationsPermsDesc.setTextColor(
                    context.getAttrColorCompat(MR.attr.colorOnSecondaryContainer)
                )
                binding.locationsPermsSubtitle.setTextColor(
                    context.getAttrColorCompat(MR.attr.colorOnSecondaryContainer)
                )
                binding.locationsPermsOpen.imageTintList =
                    context.getAttrColorCompat(MR.attr.colorOnSecondaryContainer)
            } else {
                setCardBackgroundColor(context.getAttrColorCompat(MR.attr.colorErrorContainer))
                binding.locationsPermsDesc.setTextColor(
                    context.getAttrColorCompat(MR.attr.colorOnErrorContainer)
                )
                binding.locationsPermsSubtitle.setTextColor(
                    context.getAttrColorCompat(MR.attr.colorOnErrorContainer)
                )
                binding.locationsPermsOpen.imageTintList =
                    context.getAttrColorCompat(MR.attr.colorOnErrorContainer)
            }
        }
    }

    private fun updatePermissionCardVisibility(binding: DialogMusicLocationsBinding) {
        with(binding) {
            // Hide the permission card when permissions are granted
            locationsPermsCard.isVisible = !hasStoragePermission
        }
    }

    private fun updateExtrasVisibility(binding: DialogMusicLocationsBinding) {
        with(binding) {
            // Update dropdown icon rotation
            locationsExtrasDropdownIcon.rotation = if (isExtrasExpanded) 180f else 0f

            // System Database mode - show filter mode when expanded
            // Include/exclude sections (legacy SAF) are gone; only filter mode remains.
            locationsExcludeModeHeader.isVisible = isExtrasExpanded
            locationsExcludeModeGroup.isVisible = isExtrasExpanded
            locationsExcludeModeDesc.isVisible = isExtrasExpanded
            locationsFilterModeDivider.isVisible = isExtrasExpanded
            locationsFilterListHeader.isVisible = isExtrasExpanded
            locationsFilterAdd.isVisible = isExtrasExpanded
            locationsFilterRecycler.isVisible = isExtrasExpanded

            // Config section
            configDivider.isVisible = isExtrasExpanded
            locationsExcludeNonMusicTitle.isVisible = isExtrasExpanded
            locationsExcludeNonMusicDesc.isVisible = isExtrasExpanded
            locationsExcludeNonMusic.isVisible = isExtrasExpanded
        }
    }

    private fun saveChanges() {
        val binding = requireBinding()

        // Check if MediaStore query changed
        val currentMediaStoreQuery = musicSettings.mediaStoreQuery
        val filterMode =
            if (isIncludeMode) {
                MediaStore.FilterMode.INCLUDE
            } else {
                MediaStore.FilterMode.EXCLUDE
            }
        val newMediaStoreQuery =
            MediaStore.Query(
                mode = filterMode,
                filtered = filterLocationAdapter.locations,
                excludeNonMusic = binding.locationsExcludeNonMusicSwitch.isChecked,
            )

        val configChanged = currentMediaStoreQuery != newMediaStoreQuery

        // Save the new MediaStore query
        musicSettings.mediaStoreQuery = newMediaStoreQuery

        // Persist mode (always MEDIA_STORE now — kept for SharedPreferences migration
        // so any legacy SAF int code is overwritten on next read).
        musicSettings.locationMode = LocationMode.MEDIA_STORE

        // If no configuration changed but permission was granted in this session,
        // force a location update
        if (!configChanged && permissionGrantedInSession) {
            L.d("No config changes detected, but permission was granted - forcing location update")
            musicSettings.forceLocationUpdate()
        }
    }

    private fun checkStoragePermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        return ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        val launcher =
            requireNotNull(storagePermissionLauncher) {
                "Storage permission launcher was not available"
            }

        try {
            L.d("Requesting storage permission: $permission")
            launcher.launch(permission)
        } catch (e: Exception) {
            L.e("Failed to request storage permission")
            L.e(e.stackTraceToString())
            requireContext().showToast(R.string.err_no_app)
        }
    }

    private fun updateSaveButtonState() {
        val dialog = dialog as? AlertDialog ?: return

        // System mode: Enable save only if permission is granted
        val isEnabled = hasStoragePermission

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = isEnabled
    }
}
