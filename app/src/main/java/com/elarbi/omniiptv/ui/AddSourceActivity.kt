package com.elarbi.omniiptv.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.elarbi.omniiptv.R
import com.elarbi.omniiptv.data.Repo
import com.elarbi.omniiptv.home.HomeSyncWorker
import com.elarbi.omniiptv.model.Source
import com.elarbi.omniiptv.model.SourceType
import kotlinx.coroutines.launch
import java.util.UUID

class AddSourceActivity : FragmentActivity() {
    private var type = SourceType.M3U
    private var editing: Source? = null
    private lateinit var fName: EditText
    private lateinit var fUrl: EditText
    private lateinit var fUser: EditText
    private lateinit var fPass: EditText
    private lateinit var fMac: EditText
    private lateinit var fEpg: EditText
    private lateinit var fUa: EditText
    private lateinit var status: TextView
    private lateinit var btnSave: Button

    private val macRe = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_source)
        type = SourceType.valueOf(intent.getStringExtra("type") ?: "M3U")
        editing = intent.getStringExtra("id")?.let { id -> Repo.sources.firstOrNull { it.id == id } }
        editing?.let { type = it.type }

        fName = findViewById(R.id.f_name); fUrl = findViewById(R.id.f_url)
        fUser = findViewById(R.id.f_user); fPass = findViewById(R.id.f_pass)
        fMac = findViewById(R.id.f_mac); fEpg = findViewById(R.id.f_epg); fUa = findViewById(R.id.f_ua)
        status = findViewById(R.id.form_status); btnSave = findViewById(R.id.btn_save)

        val title = findViewById<TextView>(R.id.form_title)
        val help = findViewById<TextView>(R.id.form_help)
        val creds = type == SourceType.XTREAM || type == SourceType.STALKER
        val mac = type == SourceType.STALKER
        fUser.visibility = if (creds) View.VISIBLE else View.GONE
        fPass.visibility = if (creds) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mac_row).visibility = if (mac) View.VISIBLE else View.GONE
        fEpg.visibility = if (mac) View.GONE else View.VISIBLE
        when (type) {
            SourceType.M3U -> {
                title.text = "Liste M3U / M3U8"
                help.text = "Collez le lien de la liste (http://…/liste.m3u). Les attributs tvg-id, tvg-logo, group-title et url-tvg sont reconnus."
                fUrl.hint = "URL de la liste M3U"
            }
            SourceType.XTREAM -> {
                title.text = "Xtream Codes"
                help.text = "Adresse du serveur (http://serveur:port) et identifiants fournis par votre abonnement. Un lien get.php complet est aussi accepté."
                fUrl.hint = "Serveur (http://exemple.com:8080)"
            }
            SourceType.STALKER -> {
                title.text = "Portail MAC (Stalker / MAG)"
                help.text = "Adresse du portail (http://portail:port/c/) et adresse MAC enregistrée auprès de votre fournisseur. " +
                    "Identifiant et mot de passe : seulement si votre fournisseur en a donné."
                fUa.hint = "Modèle de boîtier : MAG250 (défaut), MAG254, MAG270, MAG322, MAG420 — ou User-Agent complet"
                fUser.hint = "Identifiant (facultatif)"
                fPass.hint = "Mot de passe (facultatif)"
                fUrl.hint = "URL du portail (http://exemple.com:8080/c/)"
            }
            SourceType.TUNER -> { finish(); return }
            SourceType.YOUTUBE -> {
                startActivity(android.content.Intent(this, YoutubeActivity::class.java))
                finish()
                return
            }
        }

        val e = editing
        if (e != null) {
            fName.setText(e.name); fUrl.setText(e.url); fUser.setText(e.username); fPass.setText(e.password)
            fMac.setText(e.mac); fEpg.setText(e.epgUrl); fUa.setText(e.userAgent)
        } else {
            fMac.setText(Repo.deviceMac)
        }
        findViewById<Button>(R.id.btn_mac).setOnClickListener { fMac.setText(Repo.randomMac()) }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
        btnSave.setOnClickListener { save(true) }
        if (e != null) {
            // Mode modification : le titre l'indique, et on peut enregistrer sans tout recharger.
            title.text = "Modifier : ${e.name}"
            help.text = "${e.typeLabel} — modifiez le serveur, les identifiants, l'adresse MAC ou le nom. " +
                "La liste enregistrée (${Repo.channels(e.id).size} éléments), les favoris et les reprises sont conservés." +
                "\n\n" + help.text
            btnSave.text = "Enregistrer et recharger"
            val row = btnSave.parent as android.widget.LinearLayout
            row.addView(Button(this).apply {
                text = "Enregistrer sans recharger"
                isAllCaps = false
                setOnClickListener { save(false) }
            }, 1)
        }
        fUrl.requestFocus()
    }

    private fun save(reload: Boolean) {
        var url = fUrl.text.toString().trim()
        var user = fUser.text.toString().trim()
        var pass = fPass.text.toString().trim()
        val mac = fMac.text.toString().trim().uppercase()
        if (type == SourceType.XTREAM && url.contains("get.php")) {
            val u = Uri.parse(url)
            if (user.isBlank()) user = u.getQueryParameter("username").orEmpty()
            if (pass.isBlank()) pass = u.getQueryParameter("password").orEmpty()
            url = url.substringBefore("/get.php")
        }
        if (url.isBlank()) { status.text = "L'adresse est obligatoire."; return }
        if (!url.contains("://") && !url.startsWith("/")) url = "http://$url"
        if (type == SourceType.XTREAM && (user.isBlank() || pass.isBlank())) { status.text = "Identifiant et mot de passe obligatoires."; return }
        if (type == SourceType.STALKER && !macRe.matches(mac)) { status.text = "Adresse MAC invalide (format 00:1A:79:XX:XX:XX)."; return }

        val defaultName = Uri.parse(url).host ?: "Ma source"
        val s = Source(
            id = editing?.id ?: UUID.randomUUID().toString().take(8),
            type = type,
            name = fName.text.toString().trim().ifBlank { defaultName },
            url = url, username = user, password = pass,
            mac = if (type == SourceType.STALKER) mac else "",
            epgUrl = fEpg.text.toString().trim(), userAgent = fUa.text.toString().trim()
        )
        val old = editing
        Repo.upsertSource(s)
        Repo.activeSourceId = s.id
        if (old != null) {
            // Les liens enregistrés suivent tout de suite le nouveau serveur (favoris, reprises, liste).
            Repo.applySourceChange(old, s)
            if (!reload) { Ui.toast(this, "Modifications enregistrées"); finish(); return }
        }
        status.text = "Chargement des chaînes…"
        btnSave.isEnabled = false
        ProgressUi.run(this, "Chargement de « ${s.name} »", { Repo.refresh(s) }) { r ->
            r.onSuccess { n ->
                Ui.toast(this, "$n éléments chargés")
                HomeSyncWorker.runNow(this)
                finish()
            }.onFailure { e ->
                status.text = "Source enregistrée, mais le chargement a échoué : ${e.message}"
                btnSave.isEnabled = true
                editing = s
            }
        }
    }
}
