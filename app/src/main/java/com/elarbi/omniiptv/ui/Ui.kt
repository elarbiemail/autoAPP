package com.elarbi.omniiptv.ui

import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.elarbi.omniiptv.R
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ui {
    private val accents = Regex("\\p{Mn}+")

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(accents, "").lowercase()

    fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun button(ctx: Context, label: String, fullWidth: Boolean = true, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 18f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_button)
            setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                if (fullWidth) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(ctx, 5), dp(ctx, 10), dp(ctx, 5)) }
        }
}

/** Demande du code parental ; appelle onOk si le code est bon. */
fun Ui.askPin(ctx: android.content.Context, onOk: () -> Unit) {
    val pin = com.elarbi.omniiptv.data.Prefs.pin
    if (pin.isBlank()) { onOk(); return }
    val e = android.widget.EditText(ctx).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setTextColor(android.graphics.Color.WHITE)
        hint = "Code à 4 chiffres"
    }
    android.app.AlertDialog.Builder(ctx).setTitle("Contrôle parental").setView(e)
        .setPositiveButton("Déverrouiller") { _, _ ->
            if (e.text.toString() == pin) {
                com.elarbi.omniiptv.data.Prefs.unlocked = true
                onOk()
            } else toast(ctx, "Code incorrect")
        }
        .setNegativeButton("Annuler", null).show()
}
