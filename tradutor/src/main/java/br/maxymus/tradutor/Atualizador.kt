package br.maxymus.tradutor

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização dentro do app, igual aos apps irmãos: o CI publica um releases.json no R2 (versão, versionCode, link do APK);
 * o app compara com a própria versão e, se houver algo mais novo, baixa pelo DownloadManager e abre o instalador.
 */
object Atualizador {
    private const val URL_MANIFESTO = "https://pub-520120b0b03b4d3f8c94c5c9ba10d569.r2.dev/tradutor/releases.json"

    data class Versao(val nome: String, val codigo: Int, val apk: String, val mudou: List<String>)

    fun versaoInstalada(contexto: Context): Pair<String, Long> {
        val info = contexto.packageManager.getPackageInfo(contexto.packageName, 0)
        val codigo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return (info.versionName ?: "?") to codigo
    }

    suspend fun consultar(): Versao? = withContext(Dispatchers.IO) {
        runCatching {
            val con = (URL("$URL_MANIFESTO?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; setRequestProperty("Cache-Control", "no-cache")
            }
            val texto = con.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(texto)
            val mudou = mutableListOf<String>()
            val lista = j.optJSONArray("releases")
            if (lista != null && lista.length() > 0) {
                val m = lista.getJSONObject(0).optJSONArray("mudou")
                if (m != null) for (i in 0 until m.length()) mudou += m.getString(i)
            }
            Versao(j.getString("versionName"), j.getInt("versionCode"), j.getString("apk"), mudou)
        }.getOrNull()
    }

    /**
     * Baixa o APK na pasta Downloads pública com o DownloadManager; a notificação do Android abre o instalador do
     * sistema. Sem REQUEST_INSTALL_PACKAGES: com captura de tela e sobreposição, "instalar outros apps" fazia o Play
     * Protect pedir verificação (mesma solução da agenda, 17/09). A assinatura é fixa, então instala por cima.
     */
    fun baixarEInstalar(contexto: Context, v: Versao) {
        val app = contexto.applicationContext
        val gerente = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val nome = "tradutor-v${v.nome}.apk"
        val pedido = DownloadManager.Request(Uri.parse(v.apk))
            .setTitle("Tradutor ${v.nome}")
            .setDescription("Toque aqui quando terminar para instalar")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nome)
        val id = gerente.enqueue(pedido)
        Toast.makeText(app, "Baixando a versão ${v.nome}. Quando terminar, toque na notificação para instalar.", Toast.LENGTH_LONG).show()
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { app.unregisterReceiver(this) }
                // abre pelo próprio DownloadManager: a origem da instalação passa a ser o sistema, não este app
                runCatching { app.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        ContextCompat.registerReceiver(app, receptor, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }
}
