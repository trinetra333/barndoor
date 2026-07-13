package com.barndoor.app.dns

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.barndoor.app.R

class SpeedTestAdapter(
    private val servers: List<DnsServer>
) : RecyclerView.Adapter<SpeedTestAdapter.ViewHolder>() {

    private val results = mutableMapOf<String, SpeedResult>()
    private var order: List<DnsServer> = servers

    fun setResult(result: SpeedResult) {
        results[result.server.id] = result
        order = servers.sortedWith(
            compareBy(
                { results[it.id]?.millis == null }, // finished-with-a-time first
                { results[it.id]?.millis ?: Long.MAX_VALUE }
            )
        )
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.speedName)
        val value: TextView = view.findViewById(R.id.speedValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_speed_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = order[position]
        holder.name.text = server.name
        val result = results[server.id]
        when {
            result == null -> {
                holder.value.text = "\u2026"
                holder.value.setTextColor(Color.parseColor("#9CA3AF"))
            }
            result.millis != null -> {
                holder.value.text = "${result.millis} ms"
                holder.value.setTextColor(
                    when {
                        result.millis < 50 -> Color.parseColor("#3DDCC1")
                        result.millis < 150 -> Color.parseColor("#FF7A3D")
                        else -> Color.parseColor("#FF5C5C")
                    }
                )
            }
            else -> {
                holder.value.text = "Failed"
                holder.value.setTextColor(Color.parseColor("#FF5C5C"))
            }
        }
    }

    override fun getItemCount(): Int = order.size
}
