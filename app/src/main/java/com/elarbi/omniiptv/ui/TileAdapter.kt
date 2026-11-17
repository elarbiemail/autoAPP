package com.elarbi.omniiptv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.bumptech.glide.Glide
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Library
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.Channel

/** Vignette (vue grille) : logo ou affiche, nom, programme ou progression. */
class TileAdapter(private val ctx: Context, var items: List<Channel>) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_tile, parent, false)
        val ch = items[position]
        v.findViewById<TextView>(R.id.tile_name).text = ch.name
        val badge = v.findViewById<TextView>(R.id.tile_badge)
        badge.text = when {
            Repo.isFavorite(ch.key) -> "★"
            ch.url.startsWith("xtseries:") -> "SÉRIE"
            ch.isVod -> "FILM"
            ch.archive > 0 -> "REPLAY"
            else -> "${position + 1}"
        }
        val bar = v.findViewById<ProgressBar>(R.id.tile_progress)
        val sub = v.findViewById<TextView>(R.id.tile_sub)
        val p = if (ch.isVod) Library.progress(ch.key) else null
        val now = if (ch.isVod) null else Repo.nowNext(ch).first
        when {
            p != null -> { sub.text = "Vu à $p %"; bar.progress = p; bar.visibility = View.VISIBLE }
            now != null -> {
                sub.text = "${Ui.hm(now.start)} ${now.title}"
                val t = System.currentTimeMillis()
                bar.progress = (((t - now.start) * 100) / (now.end - now.start).coerceAtLeast(1)).toInt()
                bar.visibility = View.VISIBLE
            }
            else -> { sub.text = ch.group; bar.visibility = View.INVISIBLE }
        }
        val img = v.findViewById<ImageView>(R.id.tile_img)
        if (ch.logo.isNotBlank()) Glide.with(ctx).load(ch.logo).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(img)
        else { Glide.with(ctx).clear(img); img.setImageResource(R.drawable.ic_tv) }
        return v
    }
}
