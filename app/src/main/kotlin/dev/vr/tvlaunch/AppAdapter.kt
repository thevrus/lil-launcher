package dev.vr.tvlaunch

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class AppEntry(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable)

class AppAdapter(
    private val onClick: (AppEntry) -> Unit,
    private val onLong: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.VH>() {
    var items: List<AppEntry> = emptyList(); private set
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)  // gentle ease-out, Apple-like

    fun submit(list: List<AppEntry>) { items = list; notifyDataSetChanged() }
    fun move(from: Int, to: Int) {
        items = items.toMutableList().apply { add(to, removeAt(from)) }
        notifyItemMoved(from, to)
    }
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))
        h.tile.clipToOutline = true  // outline comes from tile_bg; the XML attribute needs API 31
        h.tile.setOnFocusChangeListener { t, f ->
            if (!f) t.rotation = 0f
            val sc = if (f) FOCUS_SCALE else 1f
            t.animate().scaleX(sc).scaleY(sc).setDuration(260).setInterpolator(ease).withLayer().start()
            h.label.animate().alpha(if (f) 1f else 0f).setDuration(if (f) 160 else 100).start()
        }
        // NO_POSITION between a data change and the next layout pass.
        h.tile.setOnClickListener { items.getOrNull(h.bindingAdapterPosition)?.let(onClick) }
        h.tile.setOnLongClickListener { items.getOrNull(h.bindingAdapterPosition)?.let(onLong); true }
        return h
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val e = items[pos]
        h.tile.setImageDrawable(e.banner ?: e.icon)
        h.tile.scaleType = if (e.banner != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
        h.label.text = e.label
        h.label.alpha = if (h.tile.isFocused) 1f else 0f
        if (!h.tile.isFocused) { h.tile.rotation = 0f; h.tile.scaleX = 1f; h.tile.scaleY = 1f }
    }

    companion object { const val FOCUS_SCALE = 1.05f }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: ImageView = v.findViewById(R.id.tile)
        val label: TextView = v.findViewById(R.id.label)
    }
}
