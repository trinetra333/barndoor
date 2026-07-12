package com.barndoor.app.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.barndoor.app.R

class AppListAdapter(
    private val onToggle: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val items = mutableListOf<AppInfo>()
    private var selected: MutableSet<String> = mutableSetOf()

    fun submit(newItems: List<AppInfo>, selectedPackages: Set<String>) {
        items.clear()
        items.addAll(newItems)
        selected = selectedPackages.toMutableSet()
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
        val checkbox: CheckBox = view.findViewById(R.id.appCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        holder.label.text = app.label
        holder.icon.setImageDrawable(app.icon)
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selected.contains(app.packageName)
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
            onToggle(app, checked)
        }
        holder.itemView.setOnClickListener { holder.checkbox.toggle() }
    }

    override fun getItemCount(): Int = items.size
}
