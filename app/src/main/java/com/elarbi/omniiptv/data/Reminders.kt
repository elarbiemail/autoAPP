package com.elarbi.omniiptv.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.elarbi.omniiptv.App
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.ui.PlayerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Rappels de programmes : à l'heure prévue, la chaîne s'ouvre (ou une notification s'affiche). */
object Reminders {
    data class Reminder(val key: String, val channelName: String, val title: String, val start: Long)

    private fun file() = File(Repo.app.filesDir, "reminders.json")

    @Synchronized
    fun list(): List<Reminder> = try {
        val a = JSONArray(file().readText())
        List(a.length()) { a.getJSONObject(it).let { o -> Reminder(o.getString("k"), o.getString("c"), o.getString("t"), o.getLong("s")) } }
            .filter { it.start > System.currentTimeMillis() - 3600_000L }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save(l: List<Reminder>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("k", it.key).put("c", it.channelName).put("t", it.title).put("s", it.start)) }
        file().writeText(a.toString())
    }

    fun has(key: String, start: Long) = list().any { it.key == key && it.start == start }

    fun toggle(ctx: Context, r: Reminder): Boolean {
        val l = list().toMutableList()
        val existing = l.firstOrNull { it.key == r.key && it.start == r.start }
        val pi = pending(ctx, r)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (existing != null) {
            l.remove(existing); save(l); am.cancel(pi); false
        } else {
            l.add(r); save(l)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.start - 60_000L, pi)
            true
        }
    }

    private fun pending(ctx: Context, r: Reminder): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("key", r.key).putExtra("name", r.channelName).putExtra("title", r.title)
        return PendingIntent.getBroadcast(
            ctx, (r.key + r.start).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun notify(ctx: Context, key: String, name: String, title: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("reminders", "Rappels de programmes", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("omniiptv://play?key=" + Uri.encode(key)))
            .setClass(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, key.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, "reminders")
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle("$title commence")
            .setContentText("Sur $name — appuyez pour regarder")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(key.hashCode(), n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val name = intent.getStringExtra("name").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        if (App.foreground > 0) {
            // L'application est à l'écran : on bascule directement sur la chaîne.
            val i = Intent(context, PlayerActivity::class.java).putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i); return } catch (_: Exception) {}
        }
        Reminders.notify(context, key, name, title)
    }
}
