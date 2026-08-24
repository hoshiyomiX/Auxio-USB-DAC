/*
 * Copyright (c) 2026 Auxio Project
 * DebugLogAdapter.kt is part of Auxio.
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

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MR
import org.oxycblt.auxio.databinding.ItemDebugLogBinding
import org.oxycblt.auxio.util.getAttrColorCompat

// RecyclerView adapter for displaying DebugLogEntry items. Uses ListAdapter with DiffUtil.
// Log level color-coded by alpha: V/D=60%, I=100%, W=80% warm tint, E=100% warm tint.
class DebugLogAdapter(private val onLongClick: (DebugLogEntry) -> Unit) :
    ListAdapter<DebugLogEntry, DebugLogAdapter.LogViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding =
            ItemDebugLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(private val binding: ItemDebugLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onLongClick(getItem(position))
                    true
                } else {
                    false
                }
            }
        }

        fun bind(entry: DebugLogEntry) {
            val context = binding.root.context
            binding.logHeader.text = "${entry.formattedTimestamp} ${entry.levelChar}/${entry.tag}"
            binding.logMessage.text = entry.message
            val baseColor = context.getAttrColorCompat(MR.attr.colorOnSurface).defaultColor
            val errorColor =
                context.getAttrColorCompat(androidx.appcompat.R.attr.colorError).defaultColor
            val color =
                when (entry.level) {
                    Log.VERBOSE,
                    Log.DEBUG -> (baseColor and 0x00FFFFFF) or 0x99000000.toInt()
                    Log.WARN -> (errorColor and 0x00FFFFFF) or 0xCC000000.toInt()
                    Log.ERROR -> errorColor
                    else -> baseColor
                }
            binding.logHeader.setTextColor(color)
            binding.logMessage.setTextColor(color)
        }
    }

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<DebugLogEntry>() {
                override fun areItemsTheSame(
                    oldItem: DebugLogEntry,
                    newItem: DebugLogEntry,
                ): Boolean =
                    oldItem.timestampMs == newItem.timestampMs &&
                        oldItem.tag == newItem.tag &&
                        oldItem.message == newItem.message

                override fun areContentsTheSame(
                    oldItem: DebugLogEntry,
                    newItem: DebugLogEntry,
                ): Boolean = oldItem == newItem
            }
    }
}
