package com.barndoor.app.dns

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.barndoor.app.R

class DnsListAdapter(
    private val onSelect: (DnsServer) -> Unit,
    private val onDelete: (DnsServer) -> Unit,
    private val onToggleTile: (DnsServer, Boolean) -> Unit
) : RecyclerView.Adapter<DnsListAdapter.ViewHolder>() {

    private val items = mutableListOf<DnsServer>()
    private var selectedId: String? = null
    private var tileIds: Set<String> = emptySet()

    fun submit(newItems: List<DnsServer>, selectedServerId: String?, tileServerIds: Set<String>) {
        items.clear()
        items.addAll(newItems)
        selectedId = selectedServerId
        tileIds = tileServerIds
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radio: RadioButton = view.findViewById(R.id.dnsRadio)
        val name: TextView = view.findViewById(R.id.dnsName)
        val addresses: TextView = view.findViewById(R.id.dnsAddresses)
        val tagline: TextView = view.findViewById(R.id.dnsTagline)
        val tileCheckbox: CheckBox = view.findViewById(R.id.dnsTileCheckbox)
        val delete: ImageView = view.findViewById(R.id.dnsDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dns, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Every callback is driven off the server object itself, not its position —
        // position can drift after re-sorts/deletes, an object reference can't.
        val server = items[position]
        holder.name.text = server.name
        holder.addresses.text = buildString {
            if (server.dotHostname != null) append("\uD83D\uDD12 ${server.dotHostname}")
            if (server.primary != null) {
                if (isNotEmpty()) append("  \u00B7  ")
                append(server.primary)
                if (server.secondary != null) append(", ${server.secondary}")
            }
        }
        if (server.tagline != null) {
            holder.tagline.visibility = View.VISIBLE
            holder.tagline.text = server.tagline
        } else {
            holder.tagline.visibility = View.GONE
        }
        holder.radio.isChecked = server.id == selectedId
        holder.delete.visibility = if (server.custom) View.VISIBLE else View.GONE

        holder.tileCheckbox.setOnCheckedChangeListener(null)
        holder.tileCheckbox.isChecked = server.id in tileIds
        holder.tileCheckbox.setOnCheckedChangeListener { _, checked -> onToggleTile(server, checked) }

        holder.itemView.setOnClickListener { onSelect(server) }
        holder.radio.setOnClickListener { onSelect(server) }
        holder.delete.setOnClickListener { onDelete(server) }
    }

    override fun getItemCount(): Int = items.size
}
