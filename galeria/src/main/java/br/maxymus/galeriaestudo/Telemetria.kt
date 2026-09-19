package br.maxymus.galeriaestudo

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Telemetria do estudo (mesmo coletor e desenho da câmera; app "galeria-estudo"): eventos com tempos, contagens e
 * aparelho, em fila local e enviados em lote. Nunca manda imagem, vetor facial, nome de pessoa ou rosto.
 * O token entra pelo CI (BuildConfig.TELEMETRIA_TOKEN); build sem token não envia nada.
 */
object Telemetria {
    private const val URL_INGEST = "https://pocketlm.maxymus.dev.br/tel/ingest"
    private const val FILA = "telemetria.jsonl"
    private const val LOTE_MAX = 200 * 1024

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trava = Mutex()
    private val travaEnvio = Mutex()
    private var contexto: Context? = null
    private var idAparelho = ""
    private var versao = "?"
    private var envioAgendado: Job? = null
    @Volatile var ligada = true

    fun iniciar(ctx: Context) {
        if (contexto != null) return
        contexto = ctx.applicationContext
        val prefs = ctx.getSharedPreferences("telemetria", Context.MODE_PRIVATE)
        idAparelho = prefs.getString("id", null) ?: UUID.randomUUID().toString().take(8).also { prefs.edit().putString("id", it).apply() }
        ligada = prefs.getBoolean("ligada", true)
        versao = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        evento("abriu", mapOf("android" to Build.VERSION.SDK_INT, "mem_max_mb" to Runtime.getRuntime().maxMemory() / 1048576))
        escopo.launch { while (true) { delay(20_000); enviar() } }
    }

    fun evento(tipo: String, campos: Map<String, Any?> = emptyMap()) {
        val ctx = contexto ?: return
        if (!ligada || BuildConfig.TELEMETRIA_TOKEN.isEmpty()) return
        val rt = Runtime.getRuntime()
        val linha = JSONObject().apply {
            put("app", "galeria-estudo"); put("versao", versao); put("id", idAparelho)
            put("quando", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            put("aparelho", Build.MANUFACTURER + " " + Build.MODEL)
            put("tipo", tipo)
            put("mem_usada_mb", (rt.totalMemory() - rt.freeMemory()) / 1048576)
            for ((k, v) in campos) put(k, when (v) { null -> JSONObject.NULL; is List<*> -> JSONArray(v); is FloatArray -> JSONArray(v.map { Math.round(it * 1000) / 1000.0 }); else -> v })
        }
        escopo.launch {
            trava.withLock { runCatching { File(ctx.filesDir, FILA).appendText(linha.toString() + "\n") } }
            envioAgendado?.cancel()
            envioAgendado = launch { delay(3_000); enviar() }
        }
    }

    fun agora() = System.nanoTime()
    fun ms(inicio: Long) = (System.nanoTime() - inicio) / 1_000_000

    private suspend fun enviar() = travaEnvio.withLock { enviarLote() }

    private suspend fun enviarLote() {
        val ctx = contexto ?: return
        if (BuildConfig.TELEMETRIA_TOKEN.isEmpty()) return
        val arq = File(ctx.filesDir, FILA)
        val linhas = trava.withLock { if (!arq.exists()) return; arq.readLines().filter { it.isNotBlank() } }
        if (linhas.isEmpty()) return
        val lote = ArrayList<String>(); var tam = 0
        for (l in linhas) { if (tam + l.length > LOTE_MAX) break; lote += l; tam += l.length }
        val corpo = "[" + lote.joinToString(",") + "]"
        val ok = runCatching {
            val con = (URL(URL_INGEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 8000; readTimeout = 8000; doOutput = true
                setRequestProperty("Authorization", "Bearer " + BuildConfig.TELEMETRIA_TOKEN)
                setRequestProperty("Content-Type", "application/json")
            }
            con.outputStream.use { it.write(corpo.toByteArray()) }
            val cod = con.responseCode; con.disconnect(); cod == 200
        }.getOrDefault(false)
        if (ok) trava.withLock {
            val restantes = arq.readLines().filter { it.isNotBlank() }.drop(lote.size)
            if (restantes.isEmpty()) arq.delete() else arq.writeText(restantes.joinToString("\n") + "\n")
        }
    }
}
