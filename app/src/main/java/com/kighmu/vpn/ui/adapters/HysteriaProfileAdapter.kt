package com.kighmu.vpn.ui.adapters

import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.kighmu.vpn.profiles.HysteriaProfile

class HysteriaProfileAdapter(
    private var profiles: MutableList<HysteriaProfile>,
    private val onSelectionChanged: (String, Boolean) -> Unit,
    private val onEdit: (HysteriaProfile) -> Unit,
    private val onDelete: (HysteriaProfile) -> Unit,
    private val onClone: (HysteriaProfile) -> Unit
) : RecyclerView.Adapter<HysteriaProfileAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val checkbox: CheckBox = v.findViewById(android.R.id.checkbox)
        val tvName: TextView = v.findViewById(android.R.id.text1)
        val tvSub: TextView = v.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1F2E.toInt())
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            setPadding(16, 12, 16, 12)
        }
        val cb = CheckBox(parent.context).apply {
            id = android.R.id.checkbox
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF4FC3F7.toInt())
        }
        val textLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(12, 0, 0, 0)
        }
        val tv1 = TextView(parent.context).apply {
            id = android.R.id.text1
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        val tv2 = TextView(parent.context).apply {
            id = android.R.id.text2
            setTextColor(0xFF888888.toInt())
            textSize = 11f
        }
        textLayout.addView(tv1)
        textLayout.addView(tv2)
        root.addView(cb)
        root.addView(textLayout)

        val btnEdit = Button(parent.context).apply {
            text = "\u270F\uFE0F"
            textSize = 12f
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0xFF2A3550.toInt())
            setTextColor(0xFF4FC3F7.toInt())
        }
        val btnClone = Button(parent.context).apply {
            text = "\uD83D\uDCCB"
            textSize = 12f
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0xFF2A3550.toInt())
            setTextColor(0xFF4FC3F7.toInt())
        }
        val btnDel = Button(parent.context).apply {
            text = "\uD83D\uDDD1\uFE0F"
            textSize = 12f
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0xFF2A3550.toInt())
            setTextColor(0xFFFF5252.toInt())
        }
        root.addView(btnEdit)
        root.addView(btnClone)
        root.addView(btnDel)

        val vh = VH(root)
        btnEdit.setOnClickListener { if (vh.adapterPosition >= 0) onEdit(profiles[vh.adapterPosition]) }
        btnClone.setOnClickListener { if (vh.adapterPosition >= 0) onClone(profiles[vh.adapterPosition]) }
        btnDel.setOnClickListener { if (vh.adapterPosition >= 0) onDelete(profiles[vh.adapterPosition]) }
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = profiles[position]
        holder.tvName.text = p.profileName.ifBlank { "Profil Hysteria ${position + 1}" }
        val obfs = if (p.obfsPassword.isNotBlank()) " | obfs" else ""
        val hop = if (p.portHopping.isNotBlank()) " | hop:${p.portHopping}" else ""
        holder.tvSub.text = "${p.serverAddress}:${p.serverPort} | up:${p.uploadMbps}M dn:${p.downloadMbps}M$obfs$hop"
        holder.checkbox.isChecked = p.isSelected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            onSelectionChanged(p.id, checked)
        }
    }

    override fun getItemCount() = profiles.size

    fun setProfiles(newList: List<HysteriaProfile>) {
        profiles = newList.toMutableList()
        notifyDataSetChanged()
    }
}
