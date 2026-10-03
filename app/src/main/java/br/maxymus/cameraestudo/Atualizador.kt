package br.maxymus.cameraestudo

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Atualização dentro do app, no mesmo desenho do canal do pitanga: o build publica um
 * `releases.json` (versão, versionCode, link do APK, o que mudou); o app lê, compara com a
 * própria versão e, se houver algo mais novo, baixa o APK pelo DownloadManager do Android e, por toque
 * do dono, abre o instalador do sistema. A assinatura do build é fixa, então o Android aceita instalar
 * por cima sem desinstalar.
 *
 * O instalador NUNCA abre sozinho: terminado o download (e a espera por um teste de câmera em curso ou por câmera por
 * fechar), a atualização fica sempre "pronta" e só o toque do dono em "Instalar" chama instalarPronta(), que é o único
 * caminho até o instalador do sistema. Mesmo assim, ele só abre com o app em primeiro plano e a câmera liberada.
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

    /**
     * App à vista: MainActivity.onStart/onStop. O instalador só abre, e só por toque do dono, com o app em primeiro
     * plano; fora dele o toque em "Instalar" pede para abrir o app (S13).
     */
    @Volatile var primeiroPlano: Boolean = false

    /** APK já baixado, à espera do toque do dono em "Instalar" (o instalador não abre sozinho). */
    class Pronta(val nome: String, val apk: File)
    private val prontaInterna = MutableStateFlow<Pronta?>(null)
    /** "Atualização pronta": a tela mostra o aviso e o dono toca em Instalar. */
    val pronta: StateFlow<Pronta?> = prontaInterna.asStateFlow()

    /** Um pedido de cada vez: baixando, ou esperando a rodada terminar. Quem chega com um pendente é ignorado. */
    private enum class Estado { LIVRE, BAIXANDO, ESPERANDO }
    private val estado = AtomicReference(Estado.LIVRE)
    private var idDownload = -1L
    private var receptorAtual: BroadcastReceiver? = null
    private val principal = Handler(Looper.getMainLooper())
    /** Quanto esperar a rodada (e a câmera dela) terminar antes de publicar "Atualização pronta" com a rodada ainda em curso. */
    private const val ESPERA_RODADA_S = 120

    private fun rodadaOcupada(): Boolean = DoisSensores.ativa != null || DoisSensores.retida != null

    private fun downloadAtivo(gerente: DownloadManager, id: Long): Boolean = runCatching {
        gerente.query(DownloadManager.Query().setFilterById(id)).use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) in
                setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)
        }
    }.getOrDefault(false)

    /** Baixa o APK com o DownloadManager (barra na área de notificações) e, ao terminar, deixa a atualização "pronta" para o dono instalar. */
    fun baixarEInstalar(contexto: Context, v: Versao) {
        val app = contexto.applicationContext
        val gerente = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // um pedido pendente por vez: antes, cada toque em "Atualizar" criava outro download e outro receptor, e cada um
        // disputava o instalador
        when (estado.get()) {
            Estado.ESPERANDO -> { Toast.makeText(app, "A atualização está esperando o teste terminar.", Toast.LENGTH_SHORT).show(); return }
            Estado.BAIXANDO -> {
                if (downloadAtivo(gerente, idDownload)) { Toast.makeText(app, "Já estou baixando a atualização.", Toast.LENGTH_SHORT).show(); return }
                // o download sumiu sem avisar (cancelado na notificação): solta o receptor velho e aceita o pedido novo
                receptorAtual?.let { runCatching { app.unregisterReceiver(it) } }
                receptorAtual = null
                estado.set(Estado.LIVRE)
            }
            Estado.LIVRE -> {}
        }
        if (!estado.compareAndSet(Estado.LIVRE, Estado.BAIXANDO)) return
        prontaInterna.value = null
        val nome = "camera-estudo-v${v.nome}.apk"
        val destino = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), nome)
        val id = try {
            if (destino.exists()) destino.delete()
            val pedido = DownloadManager.Request(Uri.parse(v.apk))
                .setTitle("Câmera Estudo ${v.nome}")
                .setDescription("Baixando a atualização")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, nome)
            gerente.enqueue(pedido)
        } catch (e: Exception) {
            estado.set(Estado.LIVRE)
            Telemetria.evento("erro", mapOf("onde" to "atualizador", "acao" to "baixar", "classe" to e.javaClass.simpleName))
            Toast.makeText(app, "Não consegui iniciar o download. Tente de novo.", Toast.LENGTH_LONG).show()
            return
        }
        idDownload = id
        Toast.makeText(app, "Baixando a versão ${v.nome}…", Toast.LENGTH_SHORT).show()

        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { app.unregisterReceiver(this) }
                receptorAtual = null
                val cursor = gerente.query(DownloadManager.Query().setFilterById(id))
                val ok = cursor.use { it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL }
                if (!ok || !destino.exists()) {
                    estado.set(Estado.LIVRE)
                    Toast.makeText(app, "O download não terminou. Tente de novo.", Toast.LENGTH_LONG).show()
                    return
                }
                estado.set(Estado.ESPERANDO)
                esperarRodada(app, destino, v.nome, 0)
            }
        }
        receptorAtual = receptor
        ContextCompat.registerReceiver(app, receptor, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    /**
     * Um teste de dois sensores segura a câmera por baixo do CameraX; o instalador mataria o processo no meio dele. Espera
     * de 1 em 1 s, por no máximo 2 min, a rodada acabar E a câmera dela ser liberada (onClosed). Terminada a espera, com a
     * rodada encerrada ou não, publica SEMPRE "Atualização pronta": o instalador não abre aqui, só por instalarPronta(),
     * a partir de um toque do dono.
     */
    private fun esperarRodada(app: Context, apk: File, nome: String, segundos: Int) {
        if (rodadaOcupada()) {
            if (segundos < ESPERA_RODADA_S) {
                principal.postDelayed({ esperarRodada(app, apk, nome, segundos + 1) }, 1_000L)
            } else deixarPronta(app, apk, nome, "rodada_ativa")
            return
        }
        deixarPronta(app, apk, nome, if (segundos == 0) "download_concluido" else "rodada_encerrada")
    }

    private fun deixarPronta(app: Context, apk: File, nome: String, motivo: String) {
        estado.set(Estado.LIVRE)
        if (!apk.exists()) {
            prontaInterna.value = null
            Toast.makeText(app, "O arquivo da atualização sumiu. Baixe de novo.", Toast.LENGTH_LONG).show()
            return
        }
        prontaInterna.value = Pronta(nome, apk)
        Telemetria.evento("atualizador", mapOf("acao" to "pronta", "motivo" to motivo))
        Toast.makeText(app, "Atualização pronta. Toque em Instalar quando quiser.", Toast.LENGTH_LONG).show()
    }

    /** O dono tocou em "Instalar" no aviso de "Atualização pronta". */
    fun instalarPronta(contexto: Context) {
        val p = prontaInterna.value ?: return
        val app = contexto.applicationContext
        when (abrirInstalador(app, p.apk)) {
            null -> {}   // fica "pronta": se o dono recusar no sistema, o botão continua valendo
            "camera_nao_liberada" -> Toast.makeText(app, "A câmera ainda está fechando. Tente em instantes.", Toast.LENGTH_SHORT).show()
            "apk_sumiu" -> { prontaInterna.value = null; Toast.makeText(app, "O arquivo da atualização sumiu. Baixe de novo.", Toast.LENGTH_LONG).show() }
            else -> Toast.makeText(app, "Abra o app e toque em Instalar.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Última conferência, imediatamente antes de iniciar o instalador: app em primeiro plano e câmera liberada.
     * Devolve null se abriu, ou o motivo de não ter aberto. Só instalarPronta() chama, ou seja, só um toque do dono.
     */
    private fun abrirInstalador(app: Context, apk: File): String? {
        val motivo = when {
            !primeiroPlano -> "segundo_plano"
            rodadaOcupada() -> "camera_nao_liberada"
            !apk.exists() -> "apk_sumiu"
            else -> null
        }
        if (motivo != null) return motivo
        val uri = FileProvider.getUriForFile(app, app.packageName + ".arquivos", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { app.startActivity(i) }.onFailure {
            Telemetria.evento("erro", mapOf("onde" to "atualizador", "acao" to "instalador", "classe" to it.javaClass.simpleName))
            Toast.makeText(app, "Não consegui abrir o instalador.", Toast.LENGTH_LONG).show()
        }
        estado.set(Estado.LIVRE)
        return null
    }
}
