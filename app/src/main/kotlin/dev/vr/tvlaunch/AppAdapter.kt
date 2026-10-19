package dev.vr.tvlaunch

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
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
            // tvOS-style lift: the shadow grows with the scale, and the raised tile draws over its neighbours.
            val z = if (f) t.resources.getDimension(R.dimen.tile_lift) else 0f
            t.animate().scaleX(sc).scaleY(sc).translationZ(z).setDuration(260).setInterpolator(ease).start()
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
        h.tile.contentDescription = e.label
        if (!h.tile.isFocused) { h.tile.rotation = 0f; h.tile.scaleX = 1f; h.tile.scaleY = 1f; h.tile.translationZ = 0f }
    }

    companion object { const val FOCUS_SCALE = 1.07f }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tile: ImageView = v.findViewById(R.id.tile)
    }
}
