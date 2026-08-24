/*
 * Copyright (c) 2026 Auxio Project
 * LogViewerFragment.kt is part of Auxio.
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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentLogViewerBinding
import org.oxycblt.auxio.util.getSystemServiceCompat

// Fragment displaying a live tail of Timber-captured logs. Polls DebugLogTree every 500ms.
// Auto-scrolls to bottom (live tail) unless user manually scrolled up. Toolbar actions:
// Copy All (clipboard), Export to .txt (share intent), Clear (reset buffer).
@AndroidEntryPoint
class LogViewerFragment : Fragment() {

    private var _binding: FragmentLogViewerBinding? = null
    private val binding: FragmentLogViewerBinding
        get() = _binding!!

    private val adapter =
        DebugLogAdapter(
            onLongClick = { entry ->
                copyToClipboard(entry.toLogcatLine(), "Log entry copied to clipboard")
            }
        )

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable =
        object : Runnable {
            override fun run() {
                refreshLogs()
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }

    private var userScrolledUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLogViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.logToolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        binding.logToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_copy -> {
                    copyAllLogs()
                    true
                }
                R.id.action_export -> {
                    exportToTxt()
                    true
                }
                R.id.action_clear -> {
                    clearLogs()
                    true
                }
                else -> false
            }
        }
        binding.logRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@LogViewerFragment.adapter
            addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (dy < 0) userScrolledUp = true
                        val lm = recyclerView.layoutManager as LinearLayoutManager
                        val lastVisible = lm.findLastVisibleItemPosition()
                        if (lastVisible >= lm.itemCount - 2) userScrolledUp = false
                    }
                }
            )
        }
        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshHandler.removeCallbacks(refreshRunnable)
        binding.logRecycler.adapter = null
        _binding = null
    }

    private fun refreshLogs() {
        val tree =
            DebugLogTree.get()
                ?: run {
                    binding.logEmpty.visibility = View.VISIBLE
                    binding.logEmpty.text = getString(R.string.set_log_viewer_not_planted)
                    return
                }
        val entries = tree.snapshot()
        if (entries.isEmpty()) {
            binding.logEmpty.visibility = View.VISIBLE
            binding.logRecycler.visibility = View.GONE
        } else {
            binding.logEmpty.visibility = View.GONE
            binding.logRecycler.visibility = View.VISIBLE
        }
        adapter.submitList(entries) {
            if (!userScrolledUp && entries.isNotEmpty()) {
                binding.logRecycler.scrollToPosition(entries.size - 1)
            }
        }
        val count = entries.size
        val capacity = DebugLogTree.BUFFER_CAPACITY
        val statusText =
            if (count >= capacity) {
                getString(R.string.set_log_viewer_status_full, count, capacity)
            } else {
                getString(R.string.set_log_viewer_status, count, capacity)
            }
        binding.logStatus.text = statusText
        binding.logStatus.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun copyAllLogs() {
        val tree = DebugLogTree.get()
        if (tree == null || tree.size() == 0) {
            showSnackbar(getString(R.string.set_log_viewer_empty))
            return
        }
        val text = tree.exportToString()
        copyToClipboard(text, getString(R.string.set_log_viewer_copied, tree.size()))
    }

    private fun exportToTxt() {
        val tree = DebugLogTree.get()
        if (tree == null || tree.size() == 0) {
            showSnackbar(getString(R.string.set_log_viewer_empty))
            return
        }
        val text = tree.exportToString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val filename = "auxio_debug_log_$timestamp.txt"
        try {
            val cacheDir = requireContext().cacheDir
            val outFile = File(cacheDir, filename)
            FileOutputStream(outFile).use { it.write(text.toByteArray()) }
            val uri =
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    outFile,
                )
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Auxio debug log ($timestamp)")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Auxio debug log exported at $timestamp. ${tree.size()} entries.",
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            startActivity(
                Intent.createChooser(shareIntent, getString(R.string.set_log_viewer_export_chooser))
            )
        } catch (e: Exception) {
            showSnackbar(getString(R.string.set_log_viewer_export_failed, e.message ?: ""))
        }
    }

    private fun clearLogs() {
        DebugLogTree.get()?.clear()
        userScrolledUp = false
        refreshLogs()
        showSnackbar(getString(R.string.set_log_viewer_cleared))
    }

    private fun copyToClipboard(text: String, toastMessage: String) {
        val clipboard = requireContext().getSystemServiceCompat(ClipboardManager::class)
        val clip = ClipData.newPlainText("Auxio debug log", text)
        clipboard.setPrimaryClip(clip)
        showSnackbar(toastMessage)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 500L
    }
}
