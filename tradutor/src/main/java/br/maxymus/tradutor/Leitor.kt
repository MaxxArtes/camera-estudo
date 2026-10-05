package br.maxymus.tradutor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.system.Os
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
 * Um capítulo aberto no leitor. Guarda em disco o que baixou e o que traduziu, o quadro pintado POR DESTINO: reabrir o
 * mesmo capítulo no mesmo idioma não gasta rede, OCR nem tradução de novo, e trocar o idioma repinta em vez de trazer
 * de volta o quadro no idioma errado.
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
        @Volatile var ignorarCache = false
        /** destino para o qual o estado PRONTO ou SEM_TEXTO foi decidido; nulo enquanto não se sabe */
        @Volatile var paraDestino: String? = null
        /** as falas traduzidas para a leitura em voz alta, em ordem de leitura; escrito pelo motor, lido pela tela */
        @Volatile internal var dados: RegraLeituraCapitulo.DadosQuadro? = null
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
    @Volatile var visivel = 0
    /** o quadro que a leitura em voz alta espera (-1 sem leitura): os dois motores o põem na frente da fila */
    @Volatile var alvoLeitura = -1

    private val contados = HashSet<Int>()
    private var totalFalas = 0
    private var daRede = 0
    private var doDisco = 0
    private var bytes = 0L
    private val nascido = System.currentTimeMillis()

    private fun bruto(i: Int) = File(pasta, "$i.bin")

    /**
     * O quadro pintado, POR DESTINO: o nome leva o idioma, e trocar o destino não traz de volta o quadro no idioma errado.
     * Os arquivos antigos, da camada transparente (`<i>t_<destino>.jpg`) ou sem idioma (`<i>t.jpg`), ficam sem uso e saem junto com a pasta do capítulo na
     * limpeza de limparAntigos, que mantém só os 3 capítulos mais recentes.
     */
    private fun pintado(i: Int, dest: String) = File(pasta, RegraLeitor.nomePintado(i, dest))

    /**
     * As falas traduzidas do quadro pintado (RegraLeituraCapitulo.escrever), no mesmo diretório e com a mesma limpeza do
     * capítulo. É cache: fica fora do backup (o app não faz backup) e nunca vai para log nem telemetria.
     */
    private fun falasArq(i: Int, dest: String) = File(pasta, RegraLeituraCapitulo.nomeFalas(i, dest))

    /** Os dados de leitura gravados para o destino, ou nulo (não existe, outro formato ou outro destino). */
    @Synchronized
    private fun lerDados(i: Int, dest: String): RegraLeituraCapitulo.DadosQuadro? {
        val f = falasArq(i, dest)
        if (!f.exists()) return null
        val dados = runCatching { RegraLeituraCapitulo.ler(f.readText())?.takeIf { it.destino == dest } }.getOrNull()
        if (dados == null) f.delete()
        return dados
    }

    /** Publica só o arquivo completo; o temporário fica no mesmo sistema de arquivos para o rename ser atômico. */
    @Synchronized
    private fun gravarDados(i: Int, dest: String, dados: RegraLeituraCapitulo.DadosQuadro) {
        runCatching {
            val temporario = File.createTempFile("falas_", ".tmp", pasta)
            try {
                temporario.writeText(RegraLeituraCapitulo.escrever(dados))
                Os.rename(temporario.absolutePath, falasArq(i, dest).absolutePath)
            } finally { temporario.delete() }
        }
    }

    /** Em que pé o quadro está para a leitura em voz alta (RegraLeituraCapitulo.situacao), no destino de agora. */
    internal fun situacaoLeitura(q: Quadro): RegraLeituraCapitulo.Situacao {
        val dest = Traducao.destino
        val d = q.dados
        val e = q.estado
        return RegraLeituraCapitulo.situacao(e == Estado.PRONTO, e == Estado.SEM_TEXTO,
            e == Estado.ERRO_REDE || e == Estado.ERRO_TRAD, q.paraDestino == dest, d != null && d.destino == dest, d?.falas?.size ?: 0)
    }

    /** O quadro pronto (ou sem texto) foi decidido para outro destino que não o de agora? Então precisa ser repintado. */
    private fun defasado(q: Quadro, dest: String) = RegraLeitor.defasado(
        q.estado == Estado.PRONTO, q.estado == Estado.SEM_TEXTO, q.paraDestino, dest, mostrarOriginal)

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
        // guarda o último capítulo que abriu de verdade: fechar o leitor não pode ser beco sem saída quando a
        // área de transferência já mudou de conteúdo
        if (urls.isNotEmpty()) prefs.edit().putString("ultimo", endereco).apply()
        Telemetria.evento("leitor_abriu", mapOf("quadros" to urls.size, "do_disco" to reaproveitou))
    }

    /** Motor de rede: mantém bytes de vários quadros adiantados no disco. Não decodifica nada. */
    suspend fun motorRede() = withContext(Dispatchers.IO) {
        while (isActive) {
            val l = quadros
            var fez = false
            if (l.isNotEmpty()) {
                val fim = min(l.size - 1, visivel + JANELA_REDE)
                val alvo = alvoLeitura
                val ordem = if (alvo in l.indices) sequenceOf(alvo) + (max(0, visivel - 1)..fim).asSequence() else (max(0, visivel - 1)..fim).asSequence()
                for (i in ordem) {
                    val q = l[i]
                    // A imagem basta para exibir; só o quadro pedido pela voz precisa recuperar falas.
                    val dest = Traducao.destino
                    val dados = lerDados(i, dest)
                    val pronto = !mostrarOriginal && pintado(i, dest).exists() && (i != alvo || dados != null)
                    if (bruto(i).exists() || pronto || q.estado == Estado.ERRO_REDE) continue
                    val n = Capitulo.baixarPara(q.url, bruto(i))
                    if (n > 0) { daRede++; bytes += n }
                    else {
                        // falha em quadro distante é adiantamento, não leitura: não vira erro na cara do dono,
                        // que veria "não carregou" em dez quadros por um tropeço de rede de um segundo
                        if (i == alvo || (i <= visivel + JANELA_FRENTE && q.imagem == null)) q.estado = Estado.ERRO_REDE
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
        val dest = Traducao.destino
        // O quadro que a leitura espera vem primeiro, mas só enquanto a leitura ainda não sabe o que fazer com ele: pronto longe
        // da tela sairia da memória no mesmo instante e o motor o recarregaria para sempre.
        val alvo = alvoLeitura
        if (alvo in l.indices && !mostrarOriginal && situacaoLeitura(l[alvo]) == RegraLeituraCapitulo.Situacao.PREPARANDO &&
            (bruto(alvo).exists() || (pintado(alvo, dest).exists() && lerDados(alvo, dest) != null))) return l[alvo]
        for (i in max(0, visivel - 1)..min(l.size - 1, visivel + JANELA_FRENTE)) {
            val q = l[i]
            // Enquanto a rede recupera o original do alvo, os outros quadros podem ser preparados.
            if (i == alvo && !mostrarOriginal && !bruto(i).exists() && lerDados(i, dest) == null) continue
            // erro espera pedido do dono: repetir sozinho só queima bateria quando a causa é o site
            if (q.estado == Estado.ERRO_REDE || q.estado == Estado.ERRO_TRAD) continue
            // o pronto de outro destino também volta: o dono trocou o idioma com o leitor aberto
            if (q.estado == Estado.PRONTO || q.estado == Estado.SEM_TEXTO) { if (q.imagem == null || defasado(q, dest)) return q else continue }
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
        // O destino é lido UMA vez por quadro: dá o nome ao arquivo pintado e é o idioma pedido à tradução mais abaixo.
        val dest = Traducao.destino
        // Pronto ou sem texto para OUTRO destino (o dono trocou o idioma com o leitor aberto): sem arquivo do destino
        // atual, volta para a fila e é repintado, em vez de reabrir como original ou no idioma errado.
        if (defasado(q, dest)) { q.estado = Estado.ESPERA; q.imagem = null; q.paraDestino = null; q.dados = null }
        // A imagem da 0.27 basta para exibir. Recuperar falas só é necessário no quadro pedido pela voz.
        val dadosNoDisco = if (mostrarOriginal) null else lerDados(i, dest)
        val prontoNoDisco = pintado(i, dest).exists()
        val recuperarFalas = !mostrarOriginal && i == alvoLeitura && dadosNoDisco == null &&
            situacaoLeitura(q) == RegraLeituraCapitulo.Situacao.PREPARANDO
        if (!mostrarOriginal && q.estado == Estado.PRONTO && !prontoNoDisco) { q.estado = Estado.ESPERA; q.paraDestino = null; q.dados = null }

        // caminho barato: já traduzido antes, ou só voltando do disco depois de sair da memória
        if (!recuperarFalas && ((q.estado == Estado.PRONTO || q.estado == Estado.SEM_TEXTO) || (prontoNoDisco && !mostrarOriginal))) {
            val doDestino = prontoNoDisco && !mostrarOriginal
            val arq = if (doDestino) pintado(i, dest) else bruto(i)
            if (!arq.exists()) { q.estado = Estado.ESPERA; return false }
            val b = Capitulo.decodificar(arq) ?: run { q.estado = Estado.ERRO_REDE; return false }
            q.proporcao = b.height.toFloat() / b.width
            q.imagem = b
            if (q.estado != Estado.SEM_TEXTO) q.estado = Estado.PRONTO
            if (doDestino) { q.dados = dadosNoDisco; q.paraDestino = dest }
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
        if (!prontoNoDisco || mostrarOriginal) q.imagem = original // mantém o traduzido enquanto recupera as falas
        if (mostrarOriginal) { q.estado = Estado.PRONTO; return true }
        q.estado = Estado.TRADUZINDO

        val t1 = System.nanoTime()
        val falas = Falas.ler(original, -10, original.height + 10)
        val msOcr = (System.nanoTime() - t1) / 1_000_000
        if (falas.isEmpty()) {
            q.dados = semFalas(dest, original)
            gravarDados(i, dest, q.dados!!)
            q.estado = Estado.SEM_TEXTO
            q.paraDestino = dest
            Telemetria.evento("leitor_quadro", mapOf("i" to i, "kb" to (bruto(i).length() / 1024).toInt(),
                "ms_ocr" to msOcr, "falas" to 0, "puladas" to 0, "px_w" to original.width, "px_h" to original.height))
            return true
        }

        val t2 = System.nanoTime()
        // o pedido leva o MESMO destino que dá nome ao arquivo pintado, e as medidas dele vêm no resultado
        val r = Traducao.traduzirLoteDetalhado(ctx, falas.map { it.texto }, dest, ignorarCache = q.ignorarCache)
        q.ignorarCache = false
        val mapa = r.mapa
        val msTrad = (System.nanoTime() - t2) / 1_000_000
        // Fala que voltou igual não é tradução: pintar o texto original com a nossa fonte por cima do balão
        // só estraga o desenho. Então pinta-se apenas o que mudou.
        val uteis = falas.filter { (mapa[it.texto] ?: it.texto) != it.texto }
        // Nada mudou E havia texto que não foi pulado por já estar no destino: aí sim a tradução falhou. A guarda de tamanho existe porque
        // interjeição curta ("OK", "AH") traduz para ela mesma e não é falha nenhuma.
        if (uteis.isEmpty() && falas.filter { it.texto !in r.puladas }.sumOf { it.texto.length } >= 8) {
            q.estado = Estado.ERRO_TRAD                 // o original fica na tela; o dono pede de novo se quiser
            Telemetria.evento("leitor_quadro", mapOf("i" to i, "ms_ocr" to msOcr, "ms_trad" to msTrad,
                "falas" to falas.size, "puladas" to r.puladas.size, "traduzidas" to 0, "caminho" to r.caminho,
                "compartilhadas" to r.compartilhadas, "id" to r.idServidor))
            return true
        }
        if (uteis.isEmpty()) {
            q.dados = semFalas(dest, original)
            gravarDados(i, dest, q.dados!!)
            q.estado = Estado.SEM_TEXTO
            q.paraDestino = dest
            Telemetria.evento("leitor_quadro", mapOf("i" to i, "ms_ocr" to msOcr, "ms_trad" to msTrad,
                "falas" to falas.size, "puladas" to r.puladas.size, "traduzidas" to 0, "iguais" to true,
                "compartilhadas" to r.compartilhadas, "id" to r.idServidor))
            return true
        }

        val t3 = System.nanoTime()
        val camada = Pintura.camada(original, uteis) { mapa[it] ?: it }
        // A camada serve à bolha; no leitor a página precisa conservar o desenho original.
        val composta = try {
            original.copy(Bitmap.Config.ARGB_8888, true).also { Canvas(it).drawBitmap(camada, 0f, 0f, null) }
        } finally {
            if (camada !== original && camada !== q.imagem) camada.recycle()
        }
        val msPint = (System.nanoTime() - t3) / 1_000_000

        // As falas lidas são as PINTADAS (as que mudaram), com o texto traduzido e a caixa medida no original, em ordem de leitura.
        val dados = RegraLeituraCapitulo.DadosQuadro(dest, BuildConfig.VERSION_CODE, original.width, original.height,
            RegraLeituraCapitulo.emOrdemDeLeitura(uteis.map {
                RegraLeituraCapitulo.FalaQuadro(mapa[it.texto] ?: it.texto, it.caixa.left, it.caixa.top, it.caixa.right, it.caixa.bottom, it.alturaLinha)
            }))
        runCatching { pintado(i, dest).outputStream().use { s -> composta.compress(Bitmap.CompressFormat.JPEG, 88, s) } }
        gravarDados(i, dest, dados)
        q.dados = dados
        q.imagem = composta
        q.falas = falas.size
        q.estado = Estado.PRONTO
        q.paraDestino = dest
        totalFalas += falas.size

        // só números, códigos fixos e o id aleatório do serviço: nenhum texto de fala nem de resposta, nem hash deles
        Telemetria.evento("leitor_quadro", mapOf("i" to i, "kb" to (bruto(i).length() / 1024).toInt(),
            "ms_ocr" to msOcr, "ms_trad" to msTrad, "ms_pint" to msPint, "falas" to falas.size, "puladas" to r.puladas.size,
            "traduzidas" to uteis.size, "px_w" to original.width, "px_h" to original.height,
            "caminho" to r.caminho, "cache" to Traducao.noCache,
            "compartilhadas" to r.compartilhadas, "id" to r.idServidor))
        return true
    }

    private fun semFalas(dest: String, b: Bitmap) =
        RegraLeituraCapitulo.DadosQuadro(dest, BuildConfig.VERSION_CODE, b.width, b.height, emptyList())

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
        // A recuperação das falas mantém a imagem já disponível durante a nova tentativa de rede.
        if (q.estado == Estado.ERRO_TRAD) q.ignorarCache = true
        if (q.estado == Estado.ERRO_REDE) bruto(q.indice).delete()
        else q.imagem = null
        q.dados = null
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
