package com.sdev.newlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdev.newlauncher.R
import com.sdev.newlauncher.data.WidgetProviderItem
import com.sdev.newlauncher.databinding.ItemWidgetCardBinding

/**
 * The widget picker's grid: cards grouped under the app that provides them. Tapping a card adds
 * the widget, long-pressing one lifts it so it can be dragged onto the home screen.
 */
class WidgetSheetAdapter(
    private var rows: List<WidgetRow>,
    private val onSelected: (WidgetProviderItem) -> Unit,
    private val onLifted: (WidgetProviderItem, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class WidgetHolder(val binding: ItemWidgetCardBinding) : RecyclerView.ViewHolder(binding.root)

    class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    /** True when the row at [position] is an app heading, which spans the whole grid width. */
    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is WidgetRow.Header

    override fun getItemViewType(position: Int): Int =
        if (isHeader(position)) VIEW_TYPE_HEADER else VIEW_TYPE_WIDGET

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_widget_header, parent, false) as TextView)
        } else {
            WidgetHolder(ItemWidgetCardBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is WidgetRow.Header -> (holder as HeaderHolder).text.text = row.appName
            is WidgetRow.Widget -> bindWidget(holder as WidgetHolder, row.item)
        }
    }

    private fun bindWidget(holder: WidgetHolder, provider: WidgetProviderItem) {
        val binding = holder.binding
        // A card recycled from a widget that was lifted away must not stay see-through.
        binding.root.alpha = 1f
        binding.widgetLabel.text = provider.label
        binding.widgetPreview.setImageDrawable(provider.preview)
        binding.widgetSize.text =
            binding.root.context.getString(R.string.widget_size_value, provider.minSpanX, provider.minSpanY)

        val icon = try {
            binding.root.context.packageManager.getApplicationIcon(provider.packageName)
        } catch (_: Exception) {
            null
        }
        binding.widgetAppIcon.setImageDrawable(icon)

        binding.root.setOnClickListener { onSelected(provider) }
        binding.root.setOnLongClickListener {
            onLifted(provider, binding.root)
            true
        }
    }

    /** Replaces the rows: the picker is small enough that a wholesale swap is fine. */
    fun submit(newRows: List<WidgetRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_WIDGET = 1
    }
}