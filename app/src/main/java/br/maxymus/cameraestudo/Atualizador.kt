package br.maxymus.cameraestudo

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
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização dentro do app, no mesmo desenho do canal do pitanga: o build publica um
 * `releases.json` (versão, versionCode, link do APK, o que mudou); o app lê, compara com a
 * própria versão e, se houver algo mais novo, baixa o APK pelo DownloadManager do Android e
 * abre o instalador do sistema. A assinatura do build é fixa, então o Android aceita instalar
 * por cima sem desinstalar.
 */
object Atualizador {
    private const val URL_MANIFESTO = "https://pub-520120b0b03b4d3f8c94c5c9ba10d569.r2.dev/camera-estudo/releases.json"
    /** Espelho em outro provedor. Em 30/09 o R2 passou o dia desabilitado e o aparelho ficou sem canal
     *  nenhum, porque o manifesto só existia lá. Dois caminhos, queda de um não cega o app. */
    private const val URL_ESPELHO = "https://github.com/MaxxArtes/camera-estudo/releases/download/ultimo/releases.json"

    /** Por que a última consulta falhou. Antes isso era jogado fora e a tela dizia "sem rede?" para
     *  qualquer causa, o que transformou um problema de 10 minutos numa investigação de horas. */
    @Volatile var ultimoErro: String = "-"

    data class Versao(val nome: String, val codigo: Int, val apk: String, val mudou: List<String>)

    fun versaoInstalada(contexto: Context): Pair<String, Long> {
        val info = contexto.packageManager.getPackageInfo(contexto.packageName, 0)
        val codigo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return (info.versionName ?: "?") to codigo
    }

    /** Consulta o canal. Devolve a versão do canal (mesmo que igual) ou null se não deu para ler. */
    suspend fun consultar(): Versao? = withContext(Dispatchers.IO) {
        ultimoErro = "-"
        consultarEm(URL_MANIFESTO) ?: consultarEm(URL_ESPELHO)
    }

    private fun consultarEm(endereco: String): Versao? =
        runCatching {
            // o parâmetro de tempo fura cache do CDN; no GitHub ele não faz falta e a URL redireciona
            val alvo = if (endereco.contains("r2.dev")) "$endereco?t=${System.currentTimeMillis()}" else endereco
            val con = (URL(alvo).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (con.responseCode != 200) throw java.io.IOException("http " + con.responseCode)
            val texto = con.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(texto)
            val mudou = mutableListOf<String>()
            val lista = j.optJSONArray("releases")
            if (lista != null && lista.length() > 0) {
                val m = lista.getJSONObject(0).optJSONArray("mudou")
                if (m != null) for (i in 0 until m.length()) mudou += m.getString(i)
            }
            Versao(j.getString("versionName"), j.getInt("versionCode"), j.getString("apk"), mudou)
        }.getOrElse { e ->
            ultimoErro = (e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")).take(120)
            Telemetria.evento("erro", mapOf("onde" to "atualizador",
                "host" to endereco.substringAfter("//").substringBefore("/"), "msg" to ultimoErro))
            null
        }

    /** Baixa o APK com o DownloadManager (barra na área de notificações) e, ao terminar, abre o instalador. */
    fun baixarEInstalar(contexto: Context, v: Versao) {
        val app = contexto.applicationContext
        val gerente = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val nome = "camera-estudo-v${v.nome}.apk"
        val destino = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), nome)
        if (destino.exists()) destino.delete()
        val pedido = DownloadManager.Request(Uri.parse(v.apk))
            .setTitle("Câmera Estudo ${v.nome}")
            .setDescription("Baixando a atualização")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, nome)
        val id = gerente.enqueue(pedido)
        Toast.makeText(app, "Baixando a versão ${v.nome}…", Toast.LENGTH_SHORT).show()

        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { app.unregisterReceiver(this) }
                val cursor = gerente.query(DownloadManager.Query().setFilterById(id))
                val ok = cursor.use { it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL }
                if (!ok || !destino.exists()) { Toast.makeText(app, "O download não terminou. Tente de novo.", Toast.LENGTH_LONG).show(); return }
                instalar(app, destino)
            }
        }
        ContextCompat.registerReceiver(app, receptor, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun instalar(contexto: Context, apk: File) {
        val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { contexto.startActivity(i) }
            .onFailure { Toast.makeText(contexto, "Não consegui abrir o instalador: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}
