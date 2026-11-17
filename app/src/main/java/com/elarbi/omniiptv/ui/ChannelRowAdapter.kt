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

/** Ligne de chaîne avec numéro, logo, programme en cours et progression. */
class ChannelRowAdapter(private val ctx: Context, var items: List<Channel>) : BaseAdapter() {
    override fun getCount() = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_channel_row, parent, false)
        val ch = items[position]
        v.findViewById<TextView>(R.id.row_num).text = (position + 1).toString()
        val star = if (Repo.isFavorite(ch.key)) "★ " else ""
        v.findViewById<TextView>(R.id.row_name).text = star + ch.name
        val now = if (ch.isVod) null else Repo.nowNext(ch).first
        val bar = v.findViewById<ProgressBar>(R.id.row_progress)
        val sub = v.findViewById<TextView>(R.id.row_now)
        if (now != null) {
            sub.text = "${Ui.hm(now.start)}  ${now.title}"
            val t = System.currentTimeMillis()
            bar.progress = (((t - now.start) * 100) / (now.end - now.start).coerceAtLeast(1)).toInt()
            bar.visibility = View.VISIBLE
        } else {
            val p = Library.progress(ch.key)
            sub.text = if (p != null) "Vu à $p %" else ch.group
            bar.progress = p ?: 0
            bar.visibility = if (p != null) View.VISIBLE else View.INVISIBLE
        }
        val logo = v.findViewById<ImageView>(R.id.row_logo)
        if (ch.logo.isNotBlank()) Glide.with(ctx).load(ch.logo).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(logo)
        else { Glide.with(ctx).clear(logo); logo.setImageResource(R.drawable.ic_tv) }
        return v
    }
}
