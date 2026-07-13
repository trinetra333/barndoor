package com.barndoor.app.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.barndoor.app.R

class AppListAdapter(
    private val onClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val items = mutableListOf<AppInfo>()
    private var assignmentLabels: Map<String, String> = emptyMap()

    /** [assignmentLabelsByPackage] maps packageName -> display label, e.g. "Quad9" or "Default". */
    fun submit(newItems: List<AppInfo>, assignmentLabelsByPackage: Map<String, String>) {
        items.clear()
        items.addAll(newItems)
        assignmentLabels = assignmentLabelsByPackage
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
        val assignment: TextView = view.findViewById(R.id.appAssignment)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        holder.label.text = app.label
        holder.icon.setImageDrawable(app.icon)
        holder.assignment.text = assignmentLabels[app.packageName] ?: "Default"
        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount(): Int = items.size
}
