package com.elarbi.omniiptv.ui

import android.app.AlertDialog
import android.content.Context
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.data.Shown

/** Choix des sources affichées ensemble. */
object SourcePicker {
    fun pickSeveral(ctx: Context, done: () -> Unit) {
        val list = Repo.sources
        if (list.isEmpty()) { Ui.toast(ctx, "Aucune source."); return }
        val shown = Shown.sources().map { it.id }.toSet()
        val checked = BooleanArray(list.size) { list[it].id in shown }
        AlertDialog.Builder(ctx).setTitle("Sources à afficher ensemble")
            .setMultiChoiceItems(list.map { "${it.name} — ${it.typeLabel}" }.toTypedArray(), checked) { _, w, on -> checked[w] = on }
            .setPositiveButton("Afficher") { _, _ ->
                val ids = list.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.isEmpty()) Ui.toast(ctx, "Choisissez au moins une source.")
                else { Shown.showSeveral(ids); done() }
            }
            .setNeutralButton("Toutes") { _, _ -> Shown.showSeveral(list.map { it.id }); done() }
            .setNegativeButton("Annuler", null).show()
    }

    /** Choisit une source (M3U, Xtream, portail MAC) et ouvre son écran de modification. */
    fun editOne(ctx: Context) {
        val list = com.elarbi.omniiptv.data.Repo.sources.filter {
            it.type == com.elarbi.omniiptv.model.SourceType.M3U || it.type == com.elarbi.omniiptv.model.SourceType.XTREAM ||
                it.type == com.elarbi.omniiptv.model.SourceType.STALKER
        }
        if (list.isEmpty()) { Ui.toast(ctx, "Aucun serveur à modifier : ajoutez d'abord une source."); return }
        if (list.size == 1) { edit(ctx, list[0].id); return }
        android.app.AlertDialog.Builder(ctx).setTitle("Quel serveur modifier ?")
            .setItems(list.map { "${it.name} — ${it.typeLabel}" }.toTypedArray()) { _, w -> edit(ctx, list[w].id) }
            .setNegativeButton("Annuler", null).show()
    }

    fun edit(ctx: Context, id: String) =
        ctx.startActivity(android.content.Intent(ctx, AddSourceActivity::class.java).putExtra("id", id))
}
