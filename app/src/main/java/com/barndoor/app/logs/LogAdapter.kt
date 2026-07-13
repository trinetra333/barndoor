package com.barndoor.app.logs

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.barndoor.app.R
import com.barndoor.app.dns.QueryLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private val items = mutableListOf<QueryLog>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submit(newItems: List<QueryLog>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val domain: TextView = view.findViewById(R.id.logDomain)
        val duration: TextView = view.findViewById(R.id.logDuration)
        val meta: TextView = view.findViewById(R.id.logMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val log = items[position]
        holder.domain.text = log.domain
        holder.duration.text = if (log.success) "${log.durationMs}ms" else "failed"
        holder.duration.setTextColor(
            if (log.success) Color.parseColor("#3DDCC1") else Color.parseColor("#FF5C5C")
        )
        holder.meta.text = "${timeFormat.format(Date(log.timestamp))} \u2022 ${log.appLabel} \u2022 ${log.resolverName} \u2022 ${log.protocol}"
    }

    override fun getItemCount(): Int = items.size
}
