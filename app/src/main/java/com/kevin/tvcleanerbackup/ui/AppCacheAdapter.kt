package com.kevin.tvcleanerbackup.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kevin.tvcleanerbackup.core.AppCacheCleaner.AppCacheInfo
import com.kevin.tvcleanerbackup.databinding.ItemAppCacheBinding
import com.kevin.tvcleanerbackup.utils.FormatUtils

class AppCacheAdapter(
    private val items: List<AppCacheInfo>,
    private val selected: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<AppCacheAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemAppCacheBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppCacheBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.labelText.text = item.label
        holder.binding.sizeText.text = FormatUtils.humanReadableBytes(item.sizeBytes)
        holder.binding.checkBox.setOnCheckedChangeListener(null)
        holder.binding.checkBox.isChecked = selected.contains(item.packageName)
        holder.binding.checkBox.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(item.packageName) else selected.remove(item.packageName)
            onSelectionChanged()
        }
        holder.binding.root.setOnClickListener { holder.binding.checkBox.toggle() }
    }

    override fun getItemCount(): Int = items.size
}
