package com.elarbi.omniiptv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.model.Channel
import com.elarbi.omniiptv.model.Programme

/**
 * Grille du guide TV : chaînes en lignes, temps en colonnes.
 * Télécommande : ↑/↓ chaîne, ←/→ programme, OK activer. Tactile : glisser pour défiler, toucher pour choisir.
 */
class EpgGridView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    interface Listener {
        fun onSelect(ch: Channel, p: Programme?)
        fun onActivate(ch: Channel, p: Programme?)
    }

    var listener: Listener? = null
    private var channels: List<Channel> = emptyList()
    private val progCache = HashMap<String, List<Programme>>()
    private val logos = HashMap<String, Bitmap>()
    private val logoRequested = HashSet<String>()

    private val d = resources.displayMetrics.density
    private val colW = 230 * d
    private val rowH = 62 * d
    private val headH = 36 * d
    private val pxPerMin = 5.2f * d

    private var viewStart = 0L          // heure affichée au bord gauche
    private var offY = 0f
    private var selRow = 0
    private var selTime = System.currentTimeMillis()

    private val bg = Paint().apply { color = ThemeManager.background }
    private val cell = Paint().apply { color = Color.parseColor("#1B2A3E") }
    private val cellNow = Paint().apply { color = Color.parseColor("#24405F") }
    private val cellSel = Paint().apply { color = ThemeManager.accent }
    private val colBg = Paint().apply { color = Color.parseColor("#122236") }
    private val nowLine = Paint().apply { color = Color.parseColor("#FF5252"); strokeWidth = 2 * d }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15 * d }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B0BEC5"); textSize = 12 * d }
    private val dark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 15 * d }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        resetTime()
    }

    fun setChannels(list: List<Channel>) {
        channels = list
        progCache.clear()
        selRow = selRow.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        offY = 0f
        notifySelection()
        invalidate()
    }

    fun resetTime() {
        val now = System.currentTimeMillis()
        viewStart = now - (now % (30 * 60_000L)) - 30 * 60_000L
        selTime = now
        invalidate()
    }

    private fun progs(ch: Channel): List<Programme> = progCache.getOrPut(ch.key) { Repo.allProgrammes(ch) }

    private fun selected(): Programme? {
        val ch = channels.getOrNull(selRow) ?: return null
        return progs(ch).firstOrNull { selTime >= it.start && selTime < it.end }
    }

    private fun notifySelection() {
        val ch = channels.getOrNull(selRow) ?: return
        listener?.onSelect(ch, selected())
    }

    private fun xOf(t: Long) = colW + (t - viewStart) / 60_000f * pxPerMin

    private fun ensureVisible() {
        val visibleRows = ((height - headH) / rowH).toInt().coerceAtLeast(1)
        val firstRow = (offY / rowH).toInt()
        if (selRow < firstRow) offY = selRow * rowH
        if (selRow >= firstRow + visibleRows) offY = (selRow - visibleRows + 1) * rowH
        val p = selected()
        val minutesVisible = ((width - colW) / pxPerMin).toLong().coerceAtLeast(30)
        val left = p?.start ?: selTime
        val right = p?.end ?: selTime
        if (left < viewStart && (p == null || p.end <= viewStart + 15 * 60_000L)) viewStart = left - 10 * 60_000L
        if (selTime < viewStart) viewStart = selTime - 15 * 60_000L
        val viewEnd = viewStart + minutesVisible * 60_000L
        if (selTime > viewEnd - 10 * 60_000L) viewStart = selTime - (minutesVisible / 2) * 60_000L
        if (right > viewEnd && (p?.start ?: right) > viewEnd - 30 * 60_000L) viewStart += 30 * 60_000L
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val now = System.currentTimeMillis()
        // Règle des heures
        c.drawRect(0f, 0f, width.toFloat(), headH, colBg)
        var t = viewStart - (viewStart % (30 * 60_000L))
        while (xOf(t) < width) {
            val x = xOf(t)
            if (x >= colW) c.drawText(Ui.hm(t), x + 4 * d, headH - 11 * d, small)
            t += 30 * 60_000L
        }
        val first = (offY / rowH).toInt().coerceAtLeast(0)
        val count = ((height - headH) / rowH).toInt() + 2
        for (r in first until (first + count).coerceAtMost(channels.size)) {
            val ch = channels[r]
            val top = headH + r * rowH - offY
            // Colonne des chaînes : numéro, logo, nom
            c.drawRect(0f, top, colW - 2 * d, top + rowH - 2 * d, if (r == selRow) cellNow else colBg)
            c.drawText("${r + 1}", 8 * d, top + rowH / 2 + 5 * d, small)
            val logo = logoFor(ch)
            var nameX = 40 * d
            if (logo != null) {
                val lh = rowH - 18 * d
                val lw = lh * logo.width / logo.height.coerceAtLeast(1)
                c.drawBitmap(logo, null, RectF(40 * d, top + 8 * d, 40 * d + lw.coerceAtMost(70 * d), top + 8 * d + lh), null)
                nameX = 118 * d
            }
            val name = TextUtils.ellipsize(ch.name, text, colW - nameX - 6 * d, TextUtils.TruncateAt.END).toString()
            c.drawText(name, nameX, top + rowH / 2 + 5 * d, text)
            // Programmes
            val list = progs(ch)
            if (list.isEmpty()) {
                c.drawRect(colW, top, width.toFloat(), top + rowH - 2 * d, cell)
                c.drawText("Pas de guide TV", colW + 10 * d, top + rowH / 2 + 5 * d, small)
                if (r == selRow) c.drawRect(colW, top + rowH - 6 * d, width.toFloat(), top + rowH - 2 * d, cellSel)
                continue
            }
            for (p in list) {
                val x1 = xOf(p.start).coerceAtLeast(colW)
                val x2 = xOf(p.end).coerceAtMost(width.toFloat())
                if (x2 <= colW || x1 >= width) continue
                val sel = r == selRow && selTime >= p.start && selTime < p.end
                val paint = when {
                    sel -> cellSel
                    now >= p.start && now < p.end -> cellNow
                    else -> cell
                }
                c.drawRect(x1 + 1 * d, top, x2 - 1 * d, top + rowH - 2 * d, paint)
                val avail = x2 - x1 - 12 * d
                if (avail > 20 * d) {
                    val tp = if (sel) dark else text
                    c.drawText(TextUtils.ellipsize(p.title, tp, avail, TextUtils.TruncateAt.END).toString(), x1 + 6 * d, top + 25 * d, tp)
                    c.drawText(TextUtils.ellipsize("${Ui.hm(p.start)}–${Ui.hm(p.end)}", small, avail, TextUtils.TruncateAt.END).toString(), x1 + 6 * d, top + 45 * d, small)
                }
            }
        }
        val nx = xOf(now)
        if (nx > colW && nx < width) c.drawLine(nx, 0f, nx, height.toFloat(), nowLine)
    }

    private fun logoFor(ch: Channel): Bitmap? {
        logos[ch.logo]?.let { return it }
        if (ch.logo.isBlank() || !logoRequested.add(ch.logo)) return null
        val url = ch.logo
        Glide.with(context).asBitmap().load(url).override(140, 80).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                logos[url] = resource
                invalidate()
            }
            override fun onLoadCleared(placeholder: Drawable?) {}
        })
        return null
    }

    private fun move(dRow: Int, dProg: Int) {
        if (channels.isEmpty()) return
        if (dRow != 0) selRow = (selRow + dRow).coerceIn(0, channels.size - 1)
        if (dProg != 0) {
            val list = progs(channels[selRow])
            val cur = list.indexOfFirst { selTime >= it.start && selTime < it.end }
            val target = if (cur >= 0) list.getOrNull(cur + dProg) else null
            selTime = when {
                target != null -> target.start + 60_000L
                cur < 0 && dProg > 0 -> list.firstOrNull { it.start > selTime }?.let { it.start + 60_000L } ?: (selTime + 30 * 60_000L)
                cur < 0 && dProg < 0 -> list.lastOrNull { it.end <= selTime }?.let { it.start + 60_000L } ?: (selTime - 30 * 60_000L)
                else -> selTime + dProg * 30 * 60_000L
            }
        }
        ensureVisible()
        notifySelection()
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (selRow == 0) return false; move(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { move(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { move(0, 1); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { move(-8, 0); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { move(8, 0); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                channels.getOrNull(selRow)?.let { listener?.onActivate(it, selected()) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val maxY = (channels.size * rowH - (height - headH)).coerceAtLeast(0f)
            offY = (offY + dy).coerceIn(0f, maxY)
            viewStart += (dx / pxPerMin * 60_000f).toLong()
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < headH) return false
            val row = ((e.y - headH + offY) / rowH).toInt()
            if (row !in channels.indices) return false
            val t = if (e.x <= colW) System.currentTimeMillis() else viewStart + ((e.x - colW) / pxPerMin * 60_000f).toLong()
            val same = row == selRow && selected()?.let { t >= it.start && t < it.end } == true
            selRow = row
            selTime = t
            notifySelection()
            invalidate()
            if (same || e.x <= colW) listener?.onActivate(channels[row], selected())
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }
}
