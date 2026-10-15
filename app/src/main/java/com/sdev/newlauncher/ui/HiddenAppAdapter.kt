package com.sdev.newlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.AppInfo

/**
 * Lists every installed app with a checkbox so the user can hide it from the app drawer.
 *
 * @param onToggle invoked with the app whose hidden state should be toggled.
 */
class HiddenAppAdapter(
    private val onToggle: (AppInfo) -> Unit
) : ListAdapter<AppInfo, HiddenAppAdapter.HiddenViewHolder>(DIFF) {

    /** Package names currently hidden from the drawer. */
    var hidden: Set<String> = emptySet()
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HiddenViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_hidden_app, parent, false)
        return HiddenViewHolder(view)
    }

    override fun onBindViewHolder(holder: HiddenViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HiddenViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.app_icon)
        private val label: TextView = itemView.findViewById(R.id.app_label)
        private val check: MaterialCheckBox = itemView.findViewById(R.id.app_check)

        fun bind(app: AppInfo) {
            icon.setImageDrawable(app.icon)
            label.text = app.label
            check.isChecked = app.packageName in hidden
            itemView.contentDescription = app.label
            itemView.setOnClickListener { onToggle(app) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.label == newItem.label && oldItem.key == newItem.key
        }
    }
}
