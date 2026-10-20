package br.maxymus.coordenador

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.google.androidbrowserhelper.trusted.LauncherActivity

/** Abre o painel no Chrome. Se a consulta anterior achou versão nova do app, pergunta antes de abrir. */
class Entrada : LauncherActivity() {
    private var nova: Atualizador.Versao? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Lido ANTES do super: é no onCreate da LauncherActivity que ela consulta shouldLaunchImmediately.
        nova = Atualizador.pendente(this)
        super.onCreate(savedInstanceState)
        Atualizador.consultarEmSegundoPlano(this)
        nova?.let { perguntar(it) }
    }

    override fun shouldLaunchImmediately(): Boolean = nova == null

    private fun perguntar(v: Atualizador.Versao) {
        var decidiu = false
        AlertDialog.Builder(this)
            .setTitle("Coordenador ${v.nome} disponível")
            .setMessage(v.mudou.joinToString("\n").ifBlank { "Há uma versão nova do app." })
            .setPositiveButton("Baixar") { _, _ ->
                decidiu = true
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.apk)))
                finish()
            }
            .setNegativeButton("Depois") { _, _ ->
                decidiu = true
                Atualizador.adiar(this, v)
                launchTwa()
            }
            .setOnDismissListener { if (!decidiu) launchTwa() }
            .show()
    }
}
