package com.kevin.tvcleanerbackup.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kevin.tvcleanerbackup.core.BloatwareManager.Candidate
import com.kevin.tvcleanerbackup.databinding.ItemBloatwareBinding
import com.kevin.tvcleanerbackup.utils.FormatUtils

class BloatwareAdapter(
    private val items: MutableList<Candidate>,
    private val onUninstallRequested: (Candidate) -> Unit
) : RecyclerView.Adapter<BloatwareAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemBloatwareBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBloatwareBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.labelText.text = item.label
        holder.binding.descriptionText.text = item.description
        val kind = if (item.isSystemApp) "système" else "utilisateur"
        holder.binding.sizeText.text = "${FormatUtils.humanReadableBytes(item.sizeBytes)} · $kind · ${item.packageName}"
        holder.binding.btnUninstall.setOnClickListener { onUninstallRequested(item) }
    }

    override fun getItemCount(): Int = items.size

    fun removeItem(packageName: String) {
        val index = items.indexOfFirst { it.packageName == packageName }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }
}
