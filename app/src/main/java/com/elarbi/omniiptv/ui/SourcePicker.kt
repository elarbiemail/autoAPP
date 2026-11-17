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
}
