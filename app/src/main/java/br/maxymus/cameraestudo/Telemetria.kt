package br.maxymus.cameraestudo

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
 * Telemetria do estudo: cada foto, rajada, HDR, scanner, retrato e erro vira uma linha JSON com tempos,
 * memória e aparelho, guardada em files/telemetria.jsonl e enviada em lote ao coletor da bancada
 * (o mesmo da PitaIA, com token e arquivo próprios). Envio a cada 20 s enquanto o app está aberto e
 * logo após cada evento (3 s de folga para juntar). Sem rede, fica na fila e vai depois.
 *
 * O token entra pelo CI (BuildConfig.TELEMETRIA_TOKEN); build local sem token não envia nada.
 * Nunca manda imagem, só números e nomes de modo. Desligável na gaveta ("Telemetria").
 */
object Telemetria {
    private const val URL_INGEST = "https://pocketlm.maxymus.dev.br/tel/ingest"
    private const val FILA = "telemetria.jsonl"
    private const val LOTE_MAX = 200 * 1024   // o coletor aceita 256 KB por POST

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trava = Mutex()
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

    fun alternar(): Boolean {
        ligada = !ligada
        contexto?.getSharedPreferences("telemetria", Context.MODE_PRIVATE)?.edit()?.putBoolean("ligada", ligada)?.apply()
        return ligada
    }

    /** Registra um evento; valores: números, texto, booleanos, listas ou null. */
    fun evento(tipo: String, campos: Map<String, Any?> = emptyMap()) {
        val ctx = contexto ?: return
        if (!ligada || BuildConfig.TELEMETRIA_TOKEN.isEmpty()) return
        val rt = Runtime.getRuntime()
        val linha = JSONObject().apply {
            put("app", "camera-estudo"); put("versao", versao); put("id", idAparelho)
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

    /** Cronômetro simples para os tempos: `val t = Telemetria.agora()` ... `Telemetria.ms(t)`. */
    fun agora() = System.nanoTime()
    fun ms(inicio: Long) = (System.nanoTime() - inicio) / 1_000_000

    private suspend fun enviar() {
        val ctx = contexto ?: return
        if (BuildConfig.TELEMETRIA_TOKEN.isEmpty()) return
        val arq = File(ctx.filesDir, FILA)
        val linhas = trava.withLock { if (!arq.exists()) return; arq.readLines().filter { it.isNotBlank() } }
        if (linhas.isEmpty()) return
        // lote até LOTE_MAX; o resto vai na próxima rodada
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
