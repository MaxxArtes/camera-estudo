package br.maxymus.tradutor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * Um capítulo aberto no leitor. Guarda em disco o que baixou e o que traduziu: reabrir o mesmo capítulo não
 * gasta rede, OCR nem tradução de novo.
 *
 * A memória é o limite real. Um capítulo tem ~153 quadros e cada quadro decodificado ocupa alguns megabytes.
 * Por isso há DOIS motores separados, como o Astra alertou (original decodificado, reduzido e pintado podem
 * coexistir): o motor de rede só baixa BYTES para o disco, bem à frente da leitura, e o motor de preparo
 * decodifica um quadro por vez. Na RAM fica só a janela visível.
 */
class Leitor(ctx: Context, val endereco: String) {
    enum class Estado { ESPERA, BAIXANDO, TRADUZINDO, PRONTO, SEM_TEXTO, ERRO_REDE, ERRO_TRAD }

    class Quadro(val indice: Int, val url: String) {
        var estado by mutableStateOf(Estado.ESPERA)
        /** altura dividida pela largura; 0 enquanto não se sabe — aí a tela mostra um marcador compacto,
         *  em vez de inventar uma altura enorme que faria a lista pular quando a real chegasse */
        var proporcao by mutableStateOf(0f)
        var imagem by mutableStateOf<Bitmap?>(null)
        var falas = 0
    }

    companion object {
        // A de RAM PRECISA ser maior que a de preparo: com 3 e 4 o quadro recém-preparado saía da memória no
        // mesmo instante e o motor refazia o trabalho para sempre, gastando bateria sem sair do lugar.
        private const val JANELA_FRENTE = 3      // quadros preparados à frente do visível
        private const val JANELA_RAM = 4         // quadros decodificados mantidos para cada lado
        private const val JANELA_REDE = 12       // quadros baixados à frente, só bytes

        private fun resumo(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

        /** Mantém no disco só os 3 capítulos mais recentes. */
        fun limparAntigos(ctx: Context) = runCatching {
            val raiz = File(ctx.cacheDir, "leitor")
            raiz.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }
                ?.drop(3)?.forEach { it.deleteRecursively() }
        }.getOrNull()
    }

    private val chave = resumo(endereco)
    private val pasta = File(ctx.cacheDir, "leitor/$chave")
        .apply { mkdirs(); setLastModified(System.currentTimeMillis()) }
    private val prefs = ctx.getSharedPreferences("leitor", Context.MODE_PRIVATE)

    var quadros by mutableStateOf<List<Quadro>>(emptyList())
    var carregando by mutableStateOf(true)
    var falhouLista by mutableStateOf(false)
    var mostrarOriginal by mutableStateOf(false)
    /** índice do quadro no topo da tela; a interface escreve aqui e os dois motores leem */
    var visivel = 0

    private val contados = HashSet<Int>()
    private var totalFalas = 0
    private var daRede = 0
    private var doDisco = 0
    private var bytes = 0L
    private val nascido = System.currentTimeMillis()

    private fun bruto(i: Int) = File(pasta, "$i.bin")
    private fun pintado(i: Int) = File(pasta, "${i}t.jpg")

    /** Onde o dono parou neste capítulo. Capítulo longo sem isso é inutilizável. */
    fun ondeParou() = prefs.getInt(chave, 0)
    fun anotarPosicao(i: Int) { if (i != prefs.getInt(chave, -1)) prefs.edit().putInt(chave, i).apply() }

    /** Lista de quadros: do disco se já veio antes, senão da página. Bloqueante. */
    suspend fun abrir() = withContext(Dispatchers.IO) {
        val lista = File(pasta, "lista.txt")
        val reaproveitou = lista.exists()
        val urls = if (reaproveitou) lista.readLines().filter { it.isNotBlank() }
        else Capitulo.quadros(endereco).also { if (it.isNotEmpty()) lista.writeText(it.joinToString("\n")) }
        quadros = urls.mapIndexed { i, u -> Quadro(i, u) }
        falhouLista = urls.isEmpty()
        carregando = false
        Telemetria.evento("leitor_abriu", mapOf("quadros" to urls.size, "do_disco" to reaproveitou))
    }

    /** Motor de rede: mantém bytes de vários quadros adiantados no disco. Não decodifica nada. */
    suspend fun motorRede() = withContext(Dispatchers.IO) {
        while (isActive) {
            val l = quadros
            var fez = false
            if (l.isNotEmpty()) {
                val fim = min(l.size - 1, visivel + JANELA_REDE)
                for (i in max(0, visivel - 1)..fim) {
                    val q = l[i]
                    if (bruto(i).exists() || pintado(i).exists() || q.estado == Estado.ERRO_REDE) continue
                    val n = Capitulo.baixarPara(q.url, bruto(i))
                    if (n > 0) { daRede++; bytes += n }
                    else {
                        // falha em quadro distante é adiantamento, não leitura: não vira erro na cara do dono,
                        // que veria "não carregou" em dez quadros por um tropeço de rede de um segundo
                        if (i <= visivel + JANELA_FRENTE && q.imagem == null) q.estado = Estado.ERRO_REDE
                        delay(500)
                    }
                    fez = true
                    break
                }
            }
            if (!fez) delay(200)
        }
    }

    /** Motor de preparo: um quadro por vez, do mais perto do olho para o mais longe. */
    suspend fun motorPreparo(ctx: Context) = withContext(Dispatchers.Default) {
        while (isActive) {
            soltarLonge()
            val alvo = proximo()
            if (alvo == null || !preparar(ctx, alvo)) delay(150)
        }
    }

    private fun proximo(): Quadro? {
        val l = quadros
        if (l.isEmpty()) return null
        for (i in max(0, visivel - 1)..min(l.size - 1, visivel + JANELA_FRENTE)) {
            val q = l[i]
            // erro espera pedido do dono: repetir sozinho só queima bateria quando a causa é o site
            if (q.estado == Estado.ERRO_REDE || q.estado == Estado.ERRO_TRAD) continue
            if (q.estado == Estado.PRONTO || q.estado == Estado.SEM_TEXTO) { if (q.imagem == null) return q else continue }
            return q
        }
        return null
    }

    /**
     * Baixa, decodifica, lê, traduz e pinta UM quadro. Bloqueante.
     * Cada etapa é cronometrada em separado porque foi assim que descobrimos, na bolha, que o tempo estava
     * todo na tradução e não no OCR nem na pintura (97 ms contra 1776 ms contra 174 ms, medido em 23/09).
     */
    private fun preparar(ctx: Context, q: Quadro): Boolean {
        val i = q.indice
        val prontoNoDisco = pintado(i).exists()

        // caminho barato: já traduzido antes, ou só voltando do disco depois de sair da memória
        if ((q.estado == Estado.PRONTO || q.estado == Estado.SEM_TEXTO) || (prontoNoDisco && !mostrarOriginal)) {
            val arq = if (mostrarOriginal || !prontoNoDisco) bruto(i) else pintado(i)
            if (!arq.exists()) { q.estado = Estado.ESPERA; return false }
            val b = Capitulo.decodificar(arq) ?: run { q.estado = Estado.ERRO_REDE; return false }
            q.proporcao = b.height.toFloat() / b.width
            q.imagem = b
            if (q.estado != Estado.SEM_TEXTO) q.estado = Estado.PRONTO
            if (prontoNoDisco && contados.add(i)) doDisco++
            return true
        }

        // o motor de rede ainda não chegou neste quadro; devolver false faz o laço dormir em vez de girar em vazio
        if (!bruto(i).exists()) { q.estado = Estado.BAIXANDO; return false }

        val original = Capitulo.decodificar(bruto(i)) ?: run {
            q.estado = Estado.ERRO_REDE
            Telemetria.evento("erro", mapOf("onde" to "leitor_decodifica", "i" to i)); return false
        }
        q.proporcao = original.height.toFloat() / original.width
        q.imagem = original                       // o original entra na tela AGORA; a tradução troca depois
        if (mostrarOriginal) { q.estado = Estado.PRONTO; return true }
        q.estado = Estado.TRADUZINDO

        val t1 = System.nanoTime()
        val falas = Falas.ler(original, -10, original.height + 10)
        val msOcr = (System.nanoTime() - t1) / 1_000_000
        if (falas.isEmpty()) {
            q.estado = Estado.SEM_TEXTO
            Telemetria.evento("leitor_quadro", mapOf("i" to i, "kb" to (bruto(i).length() / 1024).toInt(),
                "ms_ocr" to msOcr, "falas" to 0, "px_w" to original.width, "px_h" to original.height))
            return true
        }

        val t2 = System.nanoTime()
        val mapa = Traducao.traduzirLote(ctx, falas.map { it.texto })
        val msTrad = (System.nanoTime() - t2) / 1_000_000
        // Fala que voltou igual não é tradução: pintar o texto original com a nossa fonte por cima do balão
        // só estraga o desenho. Então pinta-se apenas o que mudou.
        val uteis = falas.filter { (mapa[it.texto] ?: it.texto) != it.texto }
        // Nada mudou E havia texto de verdade: aí sim a tradução falhou. A guarda de tamanho existe porque
        // interjeição curta ("OK", "AH") traduz para ela mesma e não é falha nenhuma.
        if (uteis.isEmpty() && falas.sumOf { it.texto.length } >= 8) {
            q.estado = Estado.ERRO_TRAD                 // o original fica na tela; o dono pede de novo se quiser
            Telemetria.evento("leitor_quadro", mapOf("i" to i, "ms_ocr" to msOcr, "ms_trad" to msTrad,
                "falas" to falas.size, "traduzidas" to 0, "caminho" to Traducao.ultimoCaminho))
            return true
        }
        if (uteis.isEmpty()) {
            q.estado = Estado.SEM_TEXTO
            Telemetria.evento("leitor_quadro", mapOf("i" to i, "ms_ocr" to msOcr, "ms_trad" to msTrad,
                "falas" to falas.size, "traduzidas" to 0, "iguais" to true))
            return true
        }

        val t3 = System.nanoTime()
        val camada = Pintura.camada(original, uteis) { mapa[it] ?: it }
        val msPint = (System.nanoTime() - t3) / 1_000_000

        runCatching { pintado(i).outputStream().use { s -> camada.compress(Bitmap.CompressFormat.JPEG, 88, s) } }
        q.imagem = camada
        q.falas = falas.size
        q.estado = Estado.PRONTO
        totalFalas += falas.size

        Telemetria.evento("leitor_quadro", mapOf("i" to i, "kb" to (bruto(i).length() / 1024).toInt(),
            "ms_ocr" to msOcr, "ms_trad" to msTrad, "ms_pint" to msPint, "falas" to falas.size,
            "traduzidas" to uteis.size, "px_w" to original.width, "px_h" to original.height,
            "caminho" to Traducao.ultimoCaminho, "cache" to Traducao.noCache))
        return true
    }

    /** Solta da memória o que está longe do olho. NÃO recicla: o Compose ainda pode estar desenhando. */
    private fun soltarLonge() {
        val l = quadros
        val de = visivel - JANELA_RAM
        val ate = visivel + JANELA_RAM
        for (q in l) if (q.imagem != null && (q.indice < de || q.indice > ate)) {
            q.imagem = null
            if (q.estado == Estado.BAIXANDO || q.estado == Estado.TRADUZINDO) q.estado = Estado.ESPERA
        }
    }

    /** Alternador do capítulo inteiro. Não refaz tradução: só troca qual arquivo volta do disco. */
    fun alternarOriginal() {
        mostrarOriginal = !mostrarOriginal
        for (q in quadros) if (q.estado == Estado.PRONTO) { q.imagem = null }
        Telemetria.evento("leitor_alternou", mapOf("original" to mostrarOriginal, "i" to visivel))
    }

    /** Pedido explícito do dono num quadro que falhou. */
    fun tentarDeNovo(q: Quadro) {
        if (q.estado == Estado.ERRO_REDE) bruto(q.indice).delete()
        q.imagem = null
        q.estado = Estado.ESPERA
        Telemetria.evento("leitor_repetiu", mapOf("i" to q.indice))
    }

    fun fechar() {
        anotarPosicao(visivel)
        Telemetria.evento("leitor_fim", mapOf("quadros" to quadros.size,
            "prontos" to quadros.count { it.estado == Estado.PRONTO },
            "sem_texto" to quadros.count { it.estado == Estado.SEM_TEXTO },
            "erros" to quadros.count { it.estado == Estado.ERRO_REDE || it.estado == Estado.ERRO_TRAD },
            "parou_em" to visivel, "falas" to totalFalas, "mb" to bytes / 1048576,
            "da_rede" to daRede, "do_disco" to doDisco, "seg" to (System.currentTimeMillis() - nascido) / 1000))
    }
}
