package br.maxymus.galeriaestudo

import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Varre as fotos do aparelho (mais recentes primeiro), acha rostos, calcula a identidade e agrupa em pessoas.
 * Roda enquanto o app está aberto; cada foto vira uma linha no índice, então parar e continuar é natural.
 *
 * Duas passadas (crítica do agy, 19/09): em linha, a foto casa com a pessoa cujo melhor exemplar tem cosseno ≥ 0,74
 * (0,82 se o rosto é borrado ou de lado), senão vira pessoa nova; rosto ruim que não casa fica sem pessoa (não vira
 * grupo de um só). No fim da varredura, pessoas cujos centroides têm cosseno ≥ 0,68 são juntadas (junção registrada,
 * o usuário pode desfazer). Os exemplares guardam
 * variedade: até 12 por pessoa, e uma vista bem diferente (sim < 0,80) entra no lugar do exemplar mais redundante.
 */
object Indexador {
    // Limiares calibrados pelo Ente Photos para esta mesma família de modelo (192-d): casa 0,76 / rosto ruim 0,84 /
    // funde 0,70. Aqui um pouco mais frouxos porque casamos pelo MELHOR exemplar (não pelo centroide): a validação da
    // câmera nas fotos do dono deu ≥0,78 na mesma pessoa e 0,49 em pessoa diferente. Separar errado é reversível (juntar).
    private const val LIMIAR_CASA = 0.74f
    private const val LIMIAR_CASA_RUIM = 0.82f     // rosto borrado ou de lado só entra num grupo com muita certeza
    private const val LIMIAR_CONSOLIDA = 0.68f
    private const val MAX_EXEMPLARES = 12
    private const val LADO = 1000
    private const val ROSTO_MIN = 70

    data class Estado(val ativa: Boolean = false, val rodando: Boolean = false, val pausada: Boolean = false, val preparando: Boolean = false,
                      val feitas: Int = 0, val total: Int = 0, val pessoas: Int = 0, val comRosto: Int = 0, val concluiuEm: Long = 0L)

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    @Volatile private var pedidoPausa = false
    @Volatile private var rodandoAgora = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("indexador", Context.MODE_PRIVATE)
    fun ativada(ctx: Context) = prefs(ctx).getBoolean("ativa", false)
    fun ativar(ctx: Context) { prefs(ctx).edit().putBoolean("ativa", true).apply(); _estado.update { it.copy(ativa = true) }; pedidoPausa = false; iniciar(ctx) }
    fun desativar(ctx: Context) { prefs(ctx).edit().putBoolean("ativa", false).apply(); pedidoPausa = true; job?.cancel(); _estado.update { Estado(ativa = false) } }

    /** Começa (ou retoma) se a análise está ativada e não há outra rodando. */
    fun iniciar(ctx: Context) {
        if (!ativada(ctx)) { _estado.update { it.copy(ativa = false) }; return }
        if (job?.isActive == true) return
        pedidoPausa = false
        val app = ctx.applicationContext
        job = escopo.launch { rodar(app) }
    }
    fun pausar() { pedidoPausa = true }
    fun continuar(ctx: Context) { pedidoPausa = false; iniciar(ctx) }

    private suspend fun rodar(ctx: Context) {
        if (rodandoAgora) return   // trava dura: nunca dois trabalhadores (o guard do job tem janela entre threads)
        rodandoAgora = true
        _estado.update { it.copy(ativa = true, rodando = true, pausada = false, preparando = true) }
        val trava = runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "galeria:indexador").also { it.acquire(4 * 60 * 60 * 1000L) } }.getOrNull()
        try {
            val db = Indice.get(ctx)
            val t0 = Telemetria.agora()
            val todas = Midias.listar(ctx, soFotos = true)
            val feitas = db.fotosAnalisadas()
            val pendentes = todas.filter { it.id !in feitas }
            val exemplares = db.carregaExemplares()
            val exclusoes = db.carregaExclusoes()
            _estado.update { it.copy(preparando = false, total = todas.size, feitas = todas.size - pendentes.size, pessoas = exemplares.size) }
            Telemetria.evento("indexacao_inicio", mapOf("total" to todas.size, "pendentes" to pendentes.size, "pessoas" to exemplares.size, "ms_preparo" to Telemetria.ms(t0)))
            var n = 0; var comRosto = 0; var somaMs = 0L; var erros = 0
            for (m in pendentes) {
                if (pedidoPausa || !coroutineContext.isActive) break
                val t = Telemetria.agora()
                val rostos = runCatching { processa(ctx, db, m, exemplares, exclusoes) }.getOrElse { e ->
                    erros++; Telemetria.evento("erro", mapOf("onde" to "indexar", "msg" to (e.message ?: e::class.java.simpleName).take(120))); 0
                }
                somaMs += Telemetria.ms(t); n++; if (rostos > 0) comRosto++
                _estado.update { it.copy(feitas = it.feitas + 1, pessoas = exemplares.size, comRosto = it.comRosto + (if (rostos > 0) 1 else 0)) }
                if (n % 100 == 0) {
                    val rt = Runtime.getRuntime(); val mem = (rt.totalMemory() - rt.freeMemory()) / 1048576
                    Log.i("Indexador", "feitas=$n com_rosto=$comRosto pessoas=${exemplares.size} ms_media=${somaMs / n} erros=$erros mem_mb=$mem")
                    Telemetria.evento("indexacao", mapOf("feitas" to n, "com_rosto" to comRosto, "pessoas" to exemplares.size, "ms_media" to somaMs / n, "erros" to erros))
                }
                if (n % 200 == 0) System.gc()
                delay(30)   // respiro para a CPU (aquecimento a noite toda; agy)
            }
            val terminou = n == pendentes.size && !pedidoPausa
            var juncoes = 0
            if (terminou && exemplares.size > 1) {
                val tc = Telemetria.agora()
                juncoes = runCatching { consolida(db, exemplares) }.getOrElse { e -> Telemetria.evento("erro", mapOf("onde" to "consolidar", "msg" to (e.message ?: "").take(120))); 0 }
                Log.i("Indexador", "consolidacao: juncoes=$juncoes pessoas=${exemplares.size} ms=${Telemetria.ms(tc)}")
            }
            Telemetria.evento("indexacao_fim", mapOf("feitas" to n, "com_rosto" to comRosto, "pessoas" to exemplares.size, "juncoes_auto" to juncoes, "ms_media" to (if (n > 0) somaMs / n else 0L), "erros" to erros, "terminou" to terminou, "ms_total" to Telemetria.ms(t0)))
            _estado.update { it.copy(rodando = false, pausada = !terminou, pessoas = exemplares.size, concluiuEm = if (terminou) System.currentTimeMillis() else it.concluiuEm) }
        } finally { runCatching { if (trava != null && trava.isHeld) trava.release() }; rodandoAgora = false }
    }

    /** Uma foto: decodifica, detecta, identifica, grava. Devolve quantos rostos entraram. */
    private fun processa(ctx: Context, db: Indice, m: Midia, exemplares: HashMap<Long, MutableList<Indice.Exemplar>>, exclusoes: HashMap<Long, HashSet<Long>>): Int {
        val b = Midias.decodeReduzido(ctx, m.uri, LADO)
        if (b == null) { db.transacao { d -> db.upsertFoto(d, m.id, m.quando); db.marcaAnalisada(d, m.id, 0) }; return 0 }
        try {
            val rostos = Rostos.detectar(b, LADO).filter { it.caixa.width() >= ROSTO_MIN && it.caixa.height() >= ROSTO_MIN }
            val w = b.width.toFloat(); val h = b.height.toFloat()
            var entraram = 0
            db.transacao { d ->
                db.upsertFoto(d, m.id, m.quando)
                for (r in rostos) {
                    val e = Embedding.calcular(ctx, b, r) ?: continue
                    val vetados = exclusoes[m.id]
                    var melhorId = -1L; var melhorSim = -1f
                    for ((pid, vs) in exemplares) {
                        if (vetados != null && pid in vetados) continue
                        var s = -1f
                        for (x in vs) { val v = Embedding.sim(x.vetor, e.vetor); if (v > s) s = v }
                        if (s > melhorSim) { melhorSim = s; melhorId = pid }
                    }
                    val ruim = e.nitidez < 60f || r.frontal < 0.45f
                    val nova = melhorId < 0 || melhorSim < (if (ruim) LIMIAR_CASA_RUIM else LIMIAR_CASA)
                    val nota = r.frontal * (0.3f + 0.7f * min(1f, e.nitidez / 300f))
                    if (nova && ruim) {   // rosto ruim sem grupo: guarda sem pessoa, não vira "aparição única" de má qualidade
                        db.insereRosto(d, m.id, null, r.caixa.left / w, r.caixa.top / h, r.caixa.width() / w, r.caixa.height() / h, e.vetor, nota, false)
                        entraram++; continue
                    }
                    val pid = if (nova) db.criaPessoa(d).also { exemplares[it] = mutableListOf() } else melhorId
                    val lista = exemplares[pid] ?: mutableListOf<Indice.Exemplar>().also { exemplares[pid] = it }
                    // exemplar: pessoa nova; ou lista com vaga e vista não redundante; ou vista bem diferente no lugar do mais redundante
                    var substitui = -1
                    val exemplar = when {
                        nova -> true
                        lista.size < MAX_EXEMPLARES -> melhorSim < 0.9f
                        melhorSim < 0.80f -> { substitui = maisRedundante(lista); true }
                        else -> false
                    }
                    val rostoId = db.insereRosto(d, m.id, pid, r.caixa.left / w, r.caixa.top / h, r.caixa.width() / w, r.caixa.height() / h, e.vetor, nota, exemplar)
                    if (exemplar) {
                        if (substitui >= 0) { db.desmarcaExemplar(d, lista[substitui].rosto); lista[substitui] = Indice.Exemplar(rostoId, e.vetor) }
                        else lista += Indice.Exemplar(rostoId, e.vetor)
                    }
                    val notaCapa = db.capaNota(d, pid)
                    if (notaCapa <= 0f || nota > notaCapa * 1.3f) { if (guardaCapa(ctx, b, r, pid)) db.defineCapa(d, pid, rostoId, nota) }
                    entraram++
                }
                db.marcaAnalisada(d, m.id, entraram)
            }
            return entraram
        } finally { b.recycle() }
    }

    /** Índice do exemplar mais parecido com algum outro da própria lista (o que menos acrescenta variedade). */
    private fun maisRedundante(lista: List<Indice.Exemplar>): Int {
        var pior = 0; var maior = -1f
        for (i in lista.indices) {
            var s = -1f
            for (j in lista.indices) if (i != j) { val x = Embedding.sim(lista[i].vetor, lista[j].vetor); if (x > s) s = x }
            if (s > maior) { maior = s; pior = i }
        }
        return pior
    }

    private fun centroide(vs: List<Indice.Exemplar>): FloatArray? {
        if (vs.isEmpty()) return null
        val c = FloatArray(Embedding.DIM)
        for (x in vs) for (i in c.indices) c[i] += x.vetor[i]
        var n = 0f; for (v in c) n += v * v; n = sqrt(n) + 1e-6f
        for (i in c.indices) c[i] /= n
        return c
    }

    /**
     * Segunda passada: junta, do par mais parecido para baixo, pessoas cujos centroides têm cosseno ≥ 0,62,
     * recalculando o centroide a cada junção (evita a corrente A~B, B~C, A≁C). Junções ficam registradas.
     */
    private fun consolida(db: Indice, exemplares: HashMap<Long, MutableList<Indice.Exemplar>>): Int {
        val cent = HashMap<Long, FloatArray>()
        for ((id, vs) in exemplares) centroide(vs)?.let { cent[id] = it }
        var juncoes = 0
        while (true) {
            var a = -1L; var b = -1L; var melhor = LIMIAR_CONSOLIDA
            val ids = cent.keys.toList()
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val s = Embedding.sim(cent[ids[i]]!!, cent[ids[j]]!!)
                if (s >= melhor) { melhor = s; a = ids[i]; b = ids[j] }
            }
            if (a < 0) break
            // destino = quem tem mais exemplares (mais fotos); o nome que existir fica
            val (de, para) = if ((exemplares[a]?.size ?: 0) >= (exemplares[b]?.size ?: 0)) b to a else a to b
            val nome = db.nomeDaPessoa(para) ?: db.nomeDaPessoa(de)
            db.juntar(de, para, nome)
            exemplares[para]!!.addAll(exemplares[de] ?: emptyList()); exemplares.remove(de)
            cent.remove(de); centroide(exemplares[para]!!)?.let { cent[para] = it } ?: cent.remove(para)
            juncoes++
        }
        return juncoes
    }

    /** Capa de 160 px: rosto inteiro com cabelo e margem (1,8x a caixa), recorte quadrado. */
    private fun guardaCapa(ctx: Context, b: Bitmap, r: Rostos.Rosto, pessoa: Long): Boolean = runCatching {
        val lado = (max(r.caixa.width(), r.caixa.height()) * 1.8f).toInt().coerceIn(8, min(b.width, b.height))
        val x = (r.caixa.exactCenterX() - lado / 2f).toInt().coerceIn(0, max(0, b.width - lado))
        val y = (r.caixa.exactCenterY() - lado / 2f).toInt().coerceIn(0, max(0, b.height - lado))
        // createBitmap(b, 0,0,largura,altura) DEVOLVE o próprio b (identidade); recyclar "rec" matava o bitmap de
        // trabalho quando o rosto preenchia o quadro -> SIGABRT no drawBitmap seguinte (bug achado na noite 19/09).
        val rec = Bitmap.createBitmap(b, x, y, min(lado, b.width - x), min(lado, b.height - y))
        val peq = if (rec.width == 160 && rec.height == 160) rec else Bitmap.createScaledBitmap(rec, 160, 160, true)
        Indice.capa(ctx, pessoa).outputStream().use { peq.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (peq !== rec) peq.recycle()
        if (rec !== b) rec.recycle()
        true
    }.getOrDefault(false)
}
