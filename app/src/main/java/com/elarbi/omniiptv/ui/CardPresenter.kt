package com.elarbi.omniiptv.ui

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.ActionItem
import com.elarbi.omniiptv.model.Channel

class CardPresenter(private val onLong: (Any) -> Unit) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val card = ImageCardView(ctx)
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.setMainImageDimensions(Ui.dp(ctx, 200), Ui.dp(ctx, 112))
        card.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)
        card.setBackgroundColor(ContextCompat.getColor(ctx, R.color.brand))
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = viewHolder.view as ImageCardView
        val ctx = card.context
        card.setOnLongClickListener { if (item != null) onLong(item); true }
        when (item) {
            is Channel -> {
                card.titleText = item.name
                val now = Repo.nowNext(item).first
                card.contentText = if (now != null) "${Ui.hm(now.start)} ${now.title}" else item.group
                card.badgeImage = if (Repo.isFavorite(item.key)) ContextCompat.getDrawable(ctx, R.drawable.ic_star) else null
                if (item.logo.isNotBlank()) {
                    Glide.with(ctx).load(item.logo).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(card.mainImageView)
                } else {
                    card.mainImage = ContextCompat.getDrawable(ctx, R.drawable.ic_tv)
                }
            }
            is ActionItem -> {
                card.titleText = item.title
                card.contentText = item.subtitle
                card.badgeImage = null
                card.mainImage = ContextCompat.getDrawable(ctx, item.icon)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        try { Glide.with(card.context).clear(card.mainImageView) } catch (_: Exception) {}
        card.badgeImage = null
        card.mainImage = null
    }
}
