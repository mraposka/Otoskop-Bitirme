package com.kou.otoskop.ui.objectlist

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kou.otoskop.R
import com.kou.otoskop.data.model.CelestialObject
import com.kou.otoskop.databinding.ItemCelestialObjectBinding

class CelestialObjectAdapter(
    private val onSelect: (CelestialObject) -> Unit,
) : ListAdapter<CelestialObject, CelestialObjectAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCelestialObjectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(getItem(position))

    inner class VH(private val b: ItemCelestialObjectBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(o: CelestialObject) {
            b.name.text = o.name
            b.meta.text = "${o.type} · mag ${"%.1f".format(o.magnitude)}"
            b.azalt.text = "Az ${"%.1f".format(o.azimuth)}°  " +
                    "Alt ${"%.1f".format(o.altitude)}°"
            b.typeDot.backgroundTintList =
                ColorStateList.valueOf(colorForType(o.type))
            b.selectBtn.isEnabled = o.visible
            b.selectBtn.text = if (o.visible)
                b.root.context.getString(R.string.action_select)
            else b.root.context.getString(R.string.status_not_visible)
            b.selectBtn.setOnClickListener { onSelect(o) }
        }
    }

    private fun colorForType(type: String): Int = when (type.lowercase()) {
        "planet" -> Color.parseColor("#FFFFA726")
        "star" -> Color.parseColor("#FF40C4FF")
        "dso" -> Color.parseColor("#FFCE93D8")
        else -> Color.parseColor("#FFBDBDBD")
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CelestialObject>() {
            override fun areItemsTheSame(a: CelestialObject, b: CelestialObject) =
                a.name == b.name
            override fun areContentsTheSame(a: CelestialObject, b: CelestialObject) =
                a == b
        }
    }
}
