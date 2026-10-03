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
import java.util.concurrent.atomic.AtomicLong

/**
 * Telemetria do estudo: cada foto, rajada, HDR, scanner, retrato e erro vira uma linha JSON com tempos,
 * memória e aparelho, guardada em files/telemetria.jsonl e enviada em lote ao coletor da bancada
 * (o mesmo da PitaIA, com token e arquivo próprios). Envio a cada 20 s enquanto o app está aberto e
 * logo após cada evento (3 s de folga para juntar). Sem rede, fica na fila e vai depois.
 *
 * O token entra pelo CI (BuildConfig.TELEMETRIA_TOKEN); build local sem token não envia nada.
 * Nunca manda imagem, só números e nomes de modo. Desligável na gaveta ("Telemetria").
 *
 * NÃO é anônima: todo evento leva o id persistente do aparelho (sorteado na primeira abertura e mantido até desinstalar),
 * o horário com milissegundos, o modelo e a versão do app, e o coletor ainda enxerga o endereço de rede da conexão.
 * Quem deixa a opção ligada entrega um diagnóstico que se correlaciona por aparelho e por horário. Desligar invalida de vez
 * a geração atual da fila (nada dela sai, nem se a opção voltar a ser ligada logo em seguida), apaga o que ainda não saiu e
 * cancela o envio agendado; o que já foi enviado não tem como voltar.
 */
object Telemetria {
    private const val URL_INGEST = "https://pocketlm.maxymus.dev.br/tel/ingest"
    private const val FILA = "telemetria.jsonl"
    private const val LOTE_MAX = 200 * 1024   // o coletor aceita 256 KB por POST

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trava = Mutex()
    private val travaEnvio = Mutex()   // um envio por vez: o periódico e o agendado corriam juntos e o lote ia em dobro
    private var contexto: Context? = null
    private var idAparelho = ""
    private var versao = "?"
    @Volatile private var envioAgendado: Job? = null
    @Volatile var ligada = true

    /**
     * Geração da fila. Cada linha de telemetria.jsonl nasce com o número da geração em que o evento foi registrado
     * ("<geração>\t<json>"; linha sem número, de versão antiga, é geração 0). Desligar incrementa a geração e a grava nas
     * preferências na mesma escrita do "ligada": a anterior fica invalidada para sempre, neste processo e nos próximos.
     * Só linhas da geração atual são enviadas ou mantidas. Gravação e confirmação de envio levam a geração em que
     * nasceram e, vencida, não tocam na fila da geração nova; o apagamento das linhas velhas é só limpeza, e nada depende
     * de ele terminar (nem de ele rodar antes de a opção voltar a ser ligada).
     */
    private val geracao = AtomicLong(0L)

    fun iniciar(ctx: Context) {
        if (contexto != null) return
        contexto = ctx.applicationContext
        val prefs = ctx.getSharedPreferences("telemetria", Context.MODE_PRIVATE)
        idAparelho = prefs.getString("id", null) ?: UUID.randomUUID().toString().take(8).also { prefs.edit().putString("id", it).apply() }
        ligada = prefs.getBoolean("ligada", true)
        geracao.set(prefs.getLong("geracao", 0L))
        versao = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        apagarGeracoesAntigas()   // sobra de geração invalidada por um processo que morreu antes de apagar
        evento("abriu", mapOf("android" to Build.VERSION.SDK_INT, "mem_max_mb" to Runtime.getRuntime().maxMemory() / 1048576))
        escopo.launch { while (true) { delay(20_000); enviar() } }
    }

    /**
     * Liga ou desliga. Ao DESLIGAR, a geração atual da fila é invalidada na hora (a nova passa a valer) e o envio agendado
     * é cancelado: o dono que desliga não pode ter eventos antigos saindo depois, nem se religar antes de o apagamento
     * rodar. O apagamento das linhas da geração velha (telemetria.jsonl) passa pela mesma trava das gravações e poupa as
     * da geração nova.
     */
    fun alternar(): Boolean {
        ligada = !ligada
        val prefs = contexto?.getSharedPreferences("telemetria", Context.MODE_PRIVATE)
        if (!ligada) {
            val nova = geracao.incrementAndGet()
            prefs?.edit()?.putBoolean("ligada", false)?.putLong("geracao", nova)?.apply()
            envioAgendado?.cancel()
            envioAgendado = null
            apagarGeracoesAntigas()
        } else prefs?.edit()?.putBoolean("ligada", true)?.apply()
        return ligada
    }

    /** Número da geração de uma linha da fila; sem número (versão antiga) é a geração 0, e número ilegível nunca vale. */
    private fun geracaoDe(linha: String): Long {
        val i = linha.indexOf('\t')
        return if (i < 0) 0L else linha.substring(0, i).toLongOrNull() ?: -1L
    }

    private fun corpoDe(linha: String): String = linha.substring(linha.indexOf('\t') + 1)

    /** Limpeza assíncrona: tira da fila o que não é da geração atual. Quem decide o que sai é a geração, não esta tarefa. */
    private fun apagarGeracoesAntigas() {
        val ctx = contexto ?: return
        escopo.launch { trava.withLock { podarFila(File(ctx.filesDir, FILA)) } }
    }

    /** Dentro da trava: a fila fica só com as linhas da geração atual; sem nenhuma, o arquivo é apagado. */
    private fun podarFila(arq: File) {
        try {
            if (!arq.exists()) return
            val g = geracao.get()
            val todas = arq.readLines().filter { it.isNotBlank() }
            val vivas = todas.filter { geracaoDe(it) == g }
            if (vivas.isEmpty()) arq.delete()
            else if (vivas.size != todas.size) arq.writeText(vivas.joinToString("\n") + "\n")
        } catch (e: Exception) { }
    }

    /** Registra um evento; valores: números, texto, booleanos, listas ou null. */
    fun evento(tipo: String, campos: Map<String, Any?> = emptyMap()) {
        val ctx = contexto ?: return
        val g = geracao.get()   // lida ANTES de conferir a opção: um desligar no meio deixa este evento na geração velha
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
            // a opção e a geração são conferidas de novo dentro da trava: desligar entre o evento e a gravação não deixa
            // linha na fila, nem religando logo depois (a geração do evento já não vale)
            trava.withLock { if (ligada && g == geracao.get()) runCatching { File(ctx.filesDir, FILA).appendText("$g\t$linha\n") } }
            if (!ligada || g != geracao.get()) return@launch
            envioAgendado?.cancel()
            envioAgendado = launch { delay(3_000); enviar() }
        }
    }

    /** Cronômetro simples para os tempos: `val t = Telemetria.agora()` ... `Telemetria.ms(t)`. */
    fun agora() = System.nanoTime()
    fun ms(inicio: Long) = (System.nanoTime() - inicio) / 1_000_000

    private suspend fun enviar() {
        if (!ligada) return   // desligada: nenhuma conexão é aberta, nem a periódica nem a agendada
        travaEnvio.withLock { enviarLote() }
    }

    private suspend fun enviarLote() {
        if (!ligada) return
        val ctx = contexto ?: return
        if (BuildConfig.TELEMETRIA_TOKEN.isEmpty()) return
        val arq = File(ctx.filesDir, FILA)
        // o lote pertence a UMA geração: a atual quando foi lido. Linha de outra geração nunca entra nele.
        val g = geracao.get()
        val linhas = trava.withLock { if (!arq.exists()) return; arq.readLines().filter { it.isNotBlank() && geracaoDe(it) == g } }
        if (linhas.isEmpty()) return
        // lote até LOTE_MAX; o resto vai na próxima rodada
        val lote = ArrayList<String>(); var tam = 0
        for (l in linhas) { val c = corpoDe(l); if (tam + c.length > LOTE_MAX) break; lote += c; tam += c.length }
        val corpo = "[" + lote.joinToString(",") + "]"
        // última conferência, imediatamente antes de abrir a conexão: o dono pode ter desligado (e até religado) enquanto o
        // lote era lido, e aí a geração dele já não vale
        if (!ligada || g != geracao.get()) return
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
            // desligaram enquanto o lote ia: a geração dele foi invalidada e a fila de agora é outra, que não se toca
            if (g != geracao.get() || !arq.exists()) return@withLock
            val restantes = arq.readLines().filter { it.isNotBlank() && geracaoDe(it) == g }.drop(lote.size)
            if (restantes.isEmpty()) arq.delete() else arq.writeText(restantes.joinToString("\n") + "\n")
        }
    }
}
