package br.maxymus.tradutor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Dois caminhos, escolhidos sozinho conforme a rede (pedido do dono, 23/09):
 *
 *  ONLINE  — MyMemory, SEM CHAVE. Medido na bancada em 23/09: traduz melhor que o motor local
 *            ("Você realmente é digno de ser meu inimigo" contra a versão de máquina), e aceita coreano e japonês
 *            direto para português. Sem chave é o ponto central: nada de segredo dentro do APK.
 *  OFFLINE — ML Kit, pacote baixado uma vez e depois sem internet nenhuma.
 *
 * O idioma de origem é DETECTADO (ML Kit), então serve para qualquer língua que os dois lados conheçam, não só
 * inglês. O destino é escolhido pelo dono. A detecção é POR FALA (ver classificar): a tela de um app mistura a
 * interface, que está no idioma do dono, com o texto a traduzir, e uma detecção só sobre a tela inteira devolvia o
 * idioma da interface e deixava a fala estrangeira sem tradução.
 *
 * O cache é indexado pelo TEXTO reconhecido, nunca pela imagem: rolar três pixels muda o recorte e o cache nunca
 * acertaria. A chave é normalizada porque o OCR troca "I" por "l" de um quadro para outro. Ela leva o DESTINO mas
 * não a origem detectada: a detecção oscilava (en, sk, pt para o mesmo texto) e fragmentava o cache, e a mesma
 * fala era traduzida de novo.
 */
object Traducao {
    @Volatile var destino: String = TranslateLanguage.PORTUGUESE
    /** vazio = detectar sozinho; preenchido = o dono fixou o idioma de origem */
    @Volatile var origemFixa: String = ""

    fun carregarPreferencias(ctx: Context) {
        val p = ctx.getSharedPreferences("tradutor", Context.MODE_PRIVATE)
        destino = p.getString("destino", TranslateLanguage.PORTUGUESE) ?: TranslateLanguage.PORTUGUESE
        origemFixa = p.getString("origem", "") ?: ""
    }
    fun guardarPreferencias(ctx: Context) {
        ctx.getSharedPreferences("tradutor", Context.MODE_PRIVATE).edit()
            .putString("destino", destino).putString("origem", origemFixa).apply()
    }
    @Volatile var ultimaOrigem: String? = null
    @Volatile var ultimoCaminho: String = "-"
    /**
     * Sobe quando o modelo chega atrasado e MUDA o texto do cache. Quem desenha usa isto para redesenhar uma vez.
     * É AtomicInteger porque a Thread da correção tardia e quem lê rodam em threads diferentes e `++` em volátil perde
     * contagem.
     */
    val correcoes = AtomicInteger(0)

    /** "1. Fala" -> {0: "Fala"}. Devolve nulo quando vem lixo, para não estragar a tela com resposta pela metade. */
    private fun numeradas(r: String, quantas: Int): Map<Int, String>? {
        val m = r.split("\n").mapNotNull { l ->
            val g = Regex("^\\s*(\\d+)[.)]\\s*(.+)$").find(l.trim()) ?: return@mapNotNull null
            g.groupValues[1].toIntOrNull()?.minus(1) to g.groupValues[2].trim()
        }.filter { it.first != null }.associate { it.first!! to it.second }
        return if (m.size >= quantas * 0.6) m else null
    }

    /** Valor do cache. nivel: 2 = nosso modelo, 1 = tradutor de máquina (MyMemory), 0 = offline (ML Kit). */
    private class Entrada(val texto: String, val nivel: Int)
    private const val NIVEL_OFFLINE = 0
    private const val NIVEL_MAQUINA = 1
    private const val NIVEL_MODELO = 2

    private val cache = ConcurrentHashMap<String, Entrada>()
    private val tradutores = ConcurrentHashMap<String, Translator>()
    private val identificador by lazy { LanguageIdentification.getClient() }

    /**
     * Candidatos de idioma já detectados, por texto normalizado, até 300 falas. Guarda o que o ML Kit disse e não a
     * decisão, porque pular ou votar depende do destino, que o dono pode trocar. Serve para soCache e traduzirLote
     * não detectarem de novo a mesma fala.
     */
    private val memoIdioma = lruPequeno<List<CandidatoIdioma>>(300)

    /**
     * Origem do último lote em que alguma fala votou, guardada POR DESTINO: chute para o lote em que nenhuma fala
     * vota. O de um destino não vale para outro, e nunca devolve uma origem igual ao destino do pedido.
     */
    private val historico = HistoricoOrigem()

    private fun normalizar(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** O destino vem de quem chama, da foto do pedido: a chave não pode mudar porque o dono trocou o idioma no meio. */
    private fun chave(s: String, dest: String) = dest + "|" + normalizar(s)

    /** O que ficou no cache depois de guardar: a entrada de fato (pode ser a que já estava) e se o TEXTO dessa chave mudou. */
    private class Gravado(val entrada: Entrada, val mudou: Boolean)

    /**
     * Única porta de escrita do cache. Tradução de nível menor nunca troca a de nível maior: a do modelo vale mais
     * que a da máquina, que vale mais que a offline. Antes a máquina, chegando depois, sobrescrevia o modelo.
     * Devolve a entrada que ficou de fato no cache: quem preenche a tela usa o texto DELA, e não o que tentou guardar,
     * então a máquina que chega depois do modelo recebe o texto do modelo. `mudou` é true quando o TEXTO guardado
     * mudou, que é o que interessa a quem desenha.
     */
    private fun guardar(chave: String, texto: String, nivel: Int): Gravado {
        var mudou = false
        val efetiva = cache.compute(chave) { _, atual ->
            if (atual != null && nivel < atual.nivel) atual
            else { mudou = atual == null || atual.texto != texto; Entrada(texto, nivel) }
        } ?: Entrada(texto, nivel)
        return Gravado(efetiva, mudou)
    }

    /**
     * Separa o lote por idioma, FALA POR FALA. Antes o idioma era um só para a tela, detectado nos 300 primeiros
     * caracteres de todas as falas juntas, e quando dava o destino devolvia tudo sem traduzir: num app a interface
     * em português domina esses 300 caracteres e a fala em inglês ficava sem tradução (telemetria de 03/10: 3 de 3
     * telas assim). A decisão fica em RegraIdioma, pura, e aqui só entra a parte que fala com o ML Kit:
     * a detecção respeita um prazo comum de PRAZO_CLASSIFICA_MS para o lote inteiro, e o que não deu tempo fica
     * indeterminado em vez de segurar a tela.
     *
     * Quando NINGUÉM vota (um quadro só de interjeições e falas curtas), antes de cair no histórico do destino ou em
     * "en" detecta-se UMA vez o texto das indeterminadas juntas, até 300 caracteres, dentro do mesmo prazo. É o que a
     * detecção da tela inteira fazia, e é o que acerta "はい" e "なに?", que sozinhas não têm letra para votar. Essa
     * origem não vem de voto, então não entra no histórico.
     *
     * `dest` e `fixa` são a foto do pedido, lidos uma vez por quem chama: aqui não se lê o global.
     */
    private fun classificar(textos: List<String>, dest: String, fixa: String): Classificacao {
        val candidatos = HashMap<String, List<CandidatoIdioma>>()
        if (fixa.isBlank()) runCatching {
            val prazo = System.nanoTime() + PRAZO_CLASSIFICA_MS * 1_000_000L
            detectar(textos.filter { RegraIdioma.letras(it) >= RegraIdioma.MIN_LETRAS }, prazo, candidatos)
            val previa = RegraIdioma.decidir(textos, candidatos, dest, fixa, historico.de(dest))
            if (!previa.deVotos && previa.indeterminadas.isNotEmpty()) {
                val junto = RegraIdioma.textoJunto(previa.indeterminadas)
                if (junto.any { it.isLetter() }) detectar(listOf(junto), prazo, candidatos)
            }
        }
        val r = RegraIdioma.decidir(textos, candidatos, dest, fixa, historico.de(dest))
        if (r.deVotos) historico.registrar(dest, r.origem)
        return r
    }

    /**
     * Pede ao ML Kit o idioma de cada texto que ainda não está no LRU, todos de uma vez, e espera cada um só pelo que
     * resta até `prazo` (instante de System.nanoTime). Quem não respondeu a tempo fica fora de `candidatos`, e não é
     * guardado no LRU, para a próxima chamada tentar de novo.
     */
    private fun detectar(textos: List<String>, prazo: Long, candidatos: MutableMap<String, List<CandidatoIdioma>>) {
        val pendentes = LinkedHashMap<String, Task<List<IdentifiedLanguage>>>()
        for (t in textos) {
            if (t in candidatos || t in pendentes) continue
            val guardado = memoIdioma[normalizar(t)]
            if (guardado != null) candidatos[t] = guardado
            else pendentes[t] = identificador.identifyPossibleLanguages(t)
        }
        for ((t, tarefa) in pendentes) {
            val resta = (prazo - System.nanoTime()) / 1_000_000L
            if (resta <= 0) break
            val achados = runCatching { Tasks.await(tarefa, resta, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            val lista = achados.map { CandidatoIdioma(it.languageTag, it.confidence) }
            memoIdioma[normalizar(t)] = lista
            candidatos[t] = lista
        }
    }

    fun temRede(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork)
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private fun tradutorLocal(origem: String, dest: String): Translator? = runCatching {
        val de = TranslateLanguage.fromLanguageTag(origem) ?: return null
        tradutores.getOrPut(de + dest) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(de).setTargetLanguage(dest).build())
        }
    }.getOrNull()

    /** Baixa o pacote do par. Bloqueante, pode demorar. */
    fun prepararPacote(origem: String = TranslateLanguage.ENGLISH): Boolean = runCatching {
        val dest = destino
        val t = tradutorLocal(origem, dest) ?: return false
        Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
        Telemetria.evento("pacote_pronto", mapOf("de" to origem, "para" to dest)); true
    }.getOrElse {
        Telemetria.evento("erro", mapOf("onde" to "pacote", "msg" to Telemetria.classe(it))); false
    }

    fun pacotePronto(origem: String = TranslateLanguage.ENGLISH): Boolean = runCatching {
        val t = tradutorLocal(origem, destino) ?: return false
        Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()), 1, TimeUnit.SECONDS); true
    }.getOrDefault(false)

    @Volatile var ultimoOnline = 0
    @Volatile var ultimoOffline = 0
    /** Telemetria do último lote: falas puladas por já estarem no idioma de destino, e falas sem idioma decidido. */
    @Volatile var ultimoPuladas = 0
    @Volatile var ultimoUnd = 0
    /** Telemetria do último lote: ms do início do lote até a resposta do modelo, ou -1 se ela não veio dentro da espera. */
    @Volatile var ultimoMsModelo = -1L

    /**
     * Traduz a tela INTEIRA numa requisição só, numerando as falas. Medido em 23/09: o serviço devolve a
     * numeração intacta e cada linha traduzida. Mandar uma requisição por fala estourava o limite de uso e o app
     * caía para o motor local no meio da leitura, que foi o que a telemetria mostrou acontecendo.
     *
     * As falas que já estão no idioma de destino (classificar) voltam como estão, sem tradução; só as outras seguem
     * para cache, rede e local.
     *
     * Duas regras da revisão do Astra (03/10):
     *  - UM prazo absoluto, prazoFinal = início + PRAZO_MODELO, vale para tudo: o laço de espera, a espera extra pela
     *    máquina e o offline (o paralelo e o de fechamento). Nada abre tempo novo depois dele.
     *  - O pedido é uma FOTO: destino e origem fixa são lidos uma vez, aqui, e vão explícitos para a classificação, as
     *    chaves e os motores. Se o dono trocar o idioma no meio, este lote termina no idioma em que começou.
     */
    fun traduzirLote(ctx: Context, textos: List<String>): Map<String, String> {
        val t0 = System.nanoTime()
        val prazoFinal = t0 + PRAZO_MODELO * 1_000_000_000L
        val dest = destino
        val fixa = origemFixa
        ultimoOnline = 0; ultimoOffline = 0; ultimoMsModelo = -1L
        val saida = HashMap<String, String>()
        val c = classificar(textos, dest, fixa)
        val origem = c.origem
        ultimaOrigem = origem
        ultimoPuladas = textos.count { it in c.pular }
        ultimoUnd = c.und
        // só o dono fixar a origem igual ao destino devolve tudo sem traduzir; a origem automática nunca é igual ao destino
        if (RegraIdioma.devolveTudoSemTraduzir(fixa, dest)) { textos.forEach { saida[it] = it }; return saida }
        for (t in textos) if (t in c.pular) saida[t] = t
        val faltando = ArrayList<String>()
        // chaves calculadas uma vez, na mesma ordem de `faltando`, com o destino da foto
        val chaves = ArrayList<String>()
        for (t in textos) {
            if (t in c.pular || t in faltando) continue
            val k = chave(t, dest)
            val ja = cache[k]
            if (ja != null) saida[t] = ja.texto else { faltando += t; chaves += k }
        }
        if (faltando.isEmpty()) return saida
        // O offline do lote, dividido entre o paralelo (fLocal) e o fechamento: cada fala é tentada UMA vez só, por quem
        // pegar primeiro, e o que sai fica em offline.feitas à medida que sai.
        val offline = OfflineLote(faltando.size) { i, prazo -> local(faltando[i], origem, dest, prazo) }
        val comRede = temRede(ctx)
        val semMaquina = System.currentTimeMillis() < maquinaEsgotadaAte
        // Sem a máquina (cota acabada), quem faz o papel de resposta rápida é o ML Kit no aparelho, pedido do dono em
        // 01/10. Ele corre em paralelo e o modelo, chegando depois, corrige o cache. Para de pegar fala nova quando o
        // prazo final vence ou o lote termina (offline.encerrar), em vez de seguir traduzindo para ninguém.
        val fLocal = if (comRede && semMaquina) java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
            ex.submit(Runnable { offline.trabalhar(prazoFinal) }).also { ex.shutdown() }
        } else null
        if (comRede) {
            // Os dois caminhos online saem AO MESMO TEMPO. O modelo traduz melhor mas leva de 4 a 7 s; o tradutor
            // de máquina responde em ~1 s. Em fila, o dono esperava a soma — foi a lentidão que ele sentiu.
            // Agora espera-se o modelo até PRAZO_MODELO e, se ele não chegar, usa o que a máquina já trouxe.
            val piscina = java.util.concurrent.Executors.newFixedThreadPool(2)
            val numerado = faltando.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
            // o instante em que o modelo terminou é gravado por ele mesmo: o laço abaixo só olha de 40 em 40 ms
            val fimModelo = AtomicLong(0L)
            val fModelo = piscina.submit<List<String>?> { porModelo(faltando, origem, dest).also { fimModelo.set(System.nanoTime()) } }
            val fMaquina = piscina.submit<String?> { porRede(numerado, origem, dest) }
            piscina.shutdown()

            /*
             * O MODELO MANDA quando responde dentro de PREFERE_MODELO_MS; a máquina e o local são a reserva.
             * Medido na bancada em 27/09, com o serviço e a rede de verdade:
             *
             *     nosso serviço com modelo de linguagem .... 9,05 s
             *     tradutor de máquina ..................... 0,24 a 0,41 s
             *
             * O código antigo esperava o MODELO por 9 s antes de sequer olhar o resultado da máquina, que já
             * estava na mão desde os 300 ms. A tela ficava parada 9 s com a tradução disponível — foi essa a
             * lentidão. A telemetria mostrou 9,1 s no percentil 90 de 399 preparos, e o servidor acusando
             * "broken pipe" porque o app desistia no mesmo segundo em que a resposta saía. Por isso o laço passou
             * a devolver o que chegasse primeiro, e o modelo, chegando atrasado, corrigia o cache.
             *
             * Em 03/10 o nosso serviço passou a responder em ~0,4 s (mediana 373 ms, p90 583 ms no servidor). Mostrar
             * a máquina primeiro e trocar pelo modelo meio segundo depois é uma troca visível sem ganho nenhum.
             * Então o laço (a decisão de sair está em RegraEspera.sair):
             *   - modelo chegou com resultado: usa e sai;
             *   - máquina ou local já chegaram: continua esperando o modelo até PREFERE_MODELO_MS desde o início do
             *     lote e depois sai com o que tem (o modelo, chegando depois, ainda corrige o cache);
             *   - modelo terminou sem resultado: não segura nada, aceita a máquina ou o local assim que chegarem.
             * O teto do lote é o prazoFinal.
             */
            var doModelo: List<String>? = null
            var daMaquina: Map<Int, String>? = null
            var reservaLocal = false
            var modeloVisto = false
            var maquinaVista = false
            var localVisto = fLocal == null
            val prefere = t0 + PREFERE_MODELO_MS * 1_000_000L
            while (true) {
                if (!modeloVisto && fModelo.isDone) { modeloVisto = true; doModelo = runCatching { fModelo.get() }.getOrNull() }
                if (!maquinaVista && fMaquina.isDone) {
                    maquinaVista = true
                    daMaquina = runCatching { fMaquina.get() }.getOrNull()?.let { numeradas(it, faltando.size) }
                }
                if (!localVisto && fLocal != null && fLocal.isDone) {
                    localVisto = true
                    reservaLocal = offline.feitas.size >= faltando.size * 0.6
                }
                // modeloVisto aqui quer dizer modelo sem resultado, porque com resultado a decisão já é sair
                if (RegraEspera.sair(System.nanoTime(), prefere, prazoFinal, doModelo != null, modeloVisto,
                        daMaquina != null || reservaLocal, maquinaVista, localVisto)) break
                runCatching { Thread.sleep(RegraEspera.passoMs(System.nanoTime(), prazoFinal)) }
            }
            if (doModelo != null) {
                val fim = fimModelo.get().takeIf { it != 0L } ?: System.nanoTime()
                ultimoMsModelo = (fim - t0) / 1_000_000L
            } else {
                // não cancela: deixa o modelo terminar em segundo plano e CORRIGIR o cache, e avisa quem desenha.
                // Resposta válida igual ao original também vale (nível 2): ela pode desfazer uma alteração da máquina.
                Thread {
                    runCatching { fModelo.get(40, TimeUnit.SECONDS) }.getOrNull()?.let { tarde ->
                        var mudou = false
                        for (i in faltando.indices) RegraIdioma.respostaDoModelo(tarde.getOrNull(i))?.let {
                            if (guardar(chaves[i], it, NIVEL_MODELO).mudou) mudou = true
                        }
                        if (mudou) correcoes.incrementAndGet()
                    }
                }.start()
            }
            doModelo?.let { r ->
                for ((i, t) in faltando.withIndex()) RegraIdioma.respostaDoModelo(r.getOrNull(i))?.let {
                    saida[t] = guardar(chaves[i], it, NIVEL_MODELO).entrada.texto; ultimoOnline++
                }
            }
            if (!semMaquina && faltando.any { it !in saida }) {
                // a espera extra pela máquina nunca passa do que sobrou do prazo final
                val resta = RegraEspera.esperaMaquinaMs(System.nanoTime(), prazoFinal, 6_000L)
                val linhas = daMaquina ?: runCatching { fMaquina.get(resta, TimeUnit.MILLISECONDS) }.getOrNull()?.let { numeradas(it, faltando.size) }
                if (linhas != null) for ((i, t) in faltando.withIndex())
                    if (t !in saida) linhas[i]?.let { saida[t] = guardar(chaves[i], it, NIVEL_MAQUINA).entrada.texto; ultimoOnline++ }
            }
        }
        // Fechamento offline do que ainda falta. Teto: o MENOR entre o prazo final do lote e TETO_OFFLINE_MS a partir de
        // agora, para todas as falas juntas e não por fala. Reaproveita o que o paralelo já traduziu e só pega as falas
        // que ninguém pegou, sem repetir; o que não couber fica no original.
        if (faltando.any { it !in saida }) {
            val limiteOffline = RegraEspera.limiteOffline(System.nanoTime(), prazoFinal, TETO_OFFLINE_MS)
            offline.trabalhar(limiteOffline) { faltando[it] !in saida }
            fLocal?.let { runCatching { it.get(RegraEspera.restanteMs(System.nanoTime(), limiteOffline), TimeUnit.MILLISECONDS) } }
            for ((i, t) in faltando.withIndex()) if (t !in saida) {
                val l = offline.feitas[i]
                if (l != null) { saida[t] = guardar(chaves[i], l, NIVEL_OFFLINE).entrada.texto; ultimoOffline++ } else saida[t] = t
            }
        }
        offline.encerrar()
        ultimoCaminho = when { ultimoOnline > 0 && ultimoOffline == 0 -> "online"; ultimoOnline == 0 && ultimoOffline > 0 -> "offline"; ultimoOnline > 0 -> "misto"; else -> "cache" }
        return saida
    }

    /**
     * Só o que JÁ está no cache, sem tocar em rede. Serve para desenhar na hora e não deixar o dono esperando:
     * medido em 24/09, ler a tela custa 97 ms e desenhar 174 ms, mas traduzir custa 1776 ms. Com o que já é
     * conhecido a tela aparece em menos de 300 ms; o resto entra quando chegar.
     *
     * Usa a mesma classificação por fala de traduzirLote (a detecção vem do LRU quando a fala já passou por aqui), e
     * as falas puladas, que já estão no idioma de destino, voltam como estão. Destino e origem fixa são lidos uma vez.
     */
    fun soCache(ctx: Context, textos: List<String>): Map<String, String> {
        val dest = destino
        val fixa = origemFixa
        val c = classificar(textos, dest, fixa)
        val saida = HashMap<String, String>()
        for (t in textos) {
            if (t in c.pular) saida[t] = t
            else cache[chave(t, dest)]?.let { saida[t] = it.texto }
        }
        return saida
    }

    /**
     * Traduz uma fala. Bloqueante. Devolve o original quando tudo falha, para a tela nunca ficar vazia.
     * A mesma regra do lote decide se a fala já está no idioma de destino; fala indeterminada usa o histórico do destino.
     * Destino e origem fixa são lidos uma vez, e o offline tem o mesmo prazo absoluto do lote.
     */
    fun traduzir(ctx: Context, texto: String): String {
        val prazoFinal = System.nanoTime() + PRAZO_MODELO * 1_000_000_000L
        val dest = destino
        val fixa = origemFixa
        val c = classificar(listOf(texto), dest, fixa)
        val origem = c.origem
        ultimaOrigem = origem
        if (texto in c.pular || RegraIdioma.devolveTudoSemTraduzir(fixa, dest)) return texto
        if (normalizar(texto).isEmpty()) return texto
        val k = chave(texto, dest)
        cache[k]?.let { return it.texto }
        val online = if (temRede(ctx)) porRede(texto, origem, dest) else null
        val r = online ?: local(texto, origem, dest, prazoFinal) ?: texto
        ultimoCaminho = if (online != null) "online" else "offline"
        return if (r != texto) guardar(k, r, if (online != null) NIVEL_MAQUINA else NIVEL_OFFLINE).entrada.texto else r
    }

    /**
     * NOSSO serviço: modelo de linguagem, a tela inteira numa chamada. A chave do provedor fica NO SERVIDOR; o app
     * manda só um token que dá direito a traduzir dentro de uma cota. Foi por isso que ele existe: o APK é
     * instalado fora da loja e qualquer um consegue abrir e ler o que está dentro.
     * Medido em 23/09: acerta "Você é o Sr. Seonghyeon Han?", que os outros dois caminhos erravam.
     */
    private fun porModelo(falas: List<String>, origem: String, dest: String): List<String>? {
        if (BuildConfig.TRADUTOR_TOKEN.isEmpty() || falas.isEmpty()) return null
        return runCatching {
            val corpo = JSONObject().put("de", origem).put("para", Idiomas.nomeCheio(dest))
                .put("falas", org.json.JSONArray(falas)).toString().toByteArray()
            val con = (URL("https://pocketlm.maxymus.dev.br/tradutor/traduzir").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 6_000; readTimeout = 25_000
                setRequestProperty("Authorization", "Bearer " + BuildConfig.TRADUTOR_TOKEN)
                setRequestProperty("Content-Type", "application/json")
            }
            con.outputStream.use { it.write(corpo) }
            if (con.responseCode != 200) { ultimoModelo = "http " + con.responseCode; return null }
            val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            // o nome do modelo vem da resposta e vai para a telemetria: só passa se parecer nome de modelo
            ultimoModelo = SaneaTelemetria.modelo(j.optString("modelo", "?"))
            val a = j.getJSONArray("falas")
            List(a.length()) { a.getString(it) }
        }.getOrElse { ultimoModelo = "falhou"; null }
    }

    @Volatile var ultimoModelo: String = "-"
    /**
     * teto do lote INTEIRO, em segundos, contado do início: o prazo final (prazoFinal) em que o laço de espera, a espera
     * extra pela máquina e o offline têm que ter parado. Em 27/09 o modelo levava 4 a 7 s.
     */
    const val PRAZO_MODELO = 9L
    /**
     * quanto o lote espera pelo modelo, em ms desde o início, quando a máquina ou o local já chegaram. O modelo responde
     * em ~0,4 s (mediana 373 ms, p90 583 ms em 03/10); passando disso vale mais mostrar a reserva e deixar a correção
     * tardia trocar depois. Se o modelo falhou, nada espera.
     */
    const val PREFERE_MODELO_MS = 1200L
    /** teto da classificação por idioma do lote inteiro, em ms; as falas que não couberem ficam indeterminadas */
    private const val PRAZO_CLASSIFICA_MS = 400L
    /** teto TOTAL, em ms, do offline de fechamento: vale o MENOR entre ele e o que resta do prazo final do lote */
    private const val TETO_OFFLINE_MS = 3_000L

    /** MyMemory, sem chave. Reserva para quando o nosso serviço não responde. O par de idiomas vem da foto do pedido. */
    private fun porRede(texto: String, origem: String, dest: String): String? = runCatching {
        // sem origem não há par para o MyMemory, e a resposta de erro dele (em maiúsculas) viraria "tradução"
        if (origem == RegraIdioma.ORIGEM_DESCONHECIDA) return null
        val par = URLEncoder.encode("$origem|${dest.lowercase()}", "UTF-8")
        val q = URLEncoder.encode(texto.take(900), "UTF-8")
        val con = (URL("https://api.mymemory.translated.net/get?q=$q&langpair=$par").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 12_000
            setRequestProperty("User-Agent", "TradutorDeTela/0.5 (app pessoal)")
        }
        if (System.currentTimeMillis() < maquinaEsgotadaAte) return null
        if (con.responseCode == 429 || con.responseCode == 403) { esgotou(SaneaTelemetria.http(con.responseCode)); return null }
        if (con.responseCode != 200) return null
        val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        val t = j.getJSONObject("responseData").optString("translatedText").trim()
        // Medido em 01/10: com a cota gratuita acabada a API responde 200 e escreve um AVISO no lugar da
        // tradução, sem marcar quotaFinished. O evento cota_online nunca tinha chegado, por isso. O motivo que vai
        // para a telemetria é um código fixo: o texto do aviso é conteúdo da resposta e não sai do aparelho.
        val motivo = SaneaTelemetria.motivoCota(j.optBoolean("quotaFinished", false), j.optInt("responseStatus", 200), t)
        if (motivo != null) { esgotou(motivo); return null }
        // a API devolve aviso em MAIÚSCULAS quando não traduz de verdade
        if (t.isBlank() || t.startsWith("NO QUERY", true) || t.startsWith("QUERY LENGTH", true) || t.equals(texto, true)) null else t
    }.getOrNull()

    /**
     * ML Kit no aparelho, com PRAZO (instante de System.nanoTime): a consulta de pacotes e a tradução esperam só o que
     * resta até ele, nunca um tempo fixo, e sem tempo a fala fica sem tradução. O par vem da foto do pedido.
     */
    private fun local(texto: String, origem: String, dest: String, prazo: Long): String? = runCatching {
        if (origem == RegraIdioma.ORIGEM_DESCONHECIDA) return null
        // sem o pacote do par o ML Kit falha calado; conferir antes evita esperar à toa. Sem resposta a tempo é "não
        // sei", e então não se acusa pacote faltando
        val baixados = Idiomas.baixados(RegraEspera.restanteMs(System.nanoTime(), prazo)) ?: return null
        if (origem !in baixados || dest !in baixados) { semPacote = origem; return null }
        semPacote = null
        val t = tradutorLocal(origem, dest) ?: return null
        val resta = RegraEspera.restanteMs(System.nanoTime(), prazo)
        if (resta <= 0) return null
        Tasks.await(t.translate(texto), resta, TimeUnit.MILLISECONDS)
    }.getOrNull()

    /** Cota do tradutor de máquina acabou: não chamar de novo até a virada do dia (ela renova diariamente). */
    @Volatile var maquinaEsgotadaAte = 0L
    private fun esgotou(motivo: String) {
        val c = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 5)
        }
        if (maquinaEsgotadaAte < System.currentTimeMillis()) Telemetria.evento("cota_online", mapOf("motivo" to motivo))
        maquinaEsgotadaAte = c.timeInMillis
    }

    /** idioma que apareceu sem pacote baixado, para a tela poder avisar qual falta */
    @Volatile var semPacote: String? = null

    val noCache: Int get() = cache.size
}

/** Um candidato do identificador de idioma: tag e confiança de 0 a 1. Desacoplado do ML Kit para a regra rodar na JVM. */
internal class CandidatoIdioma(val idioma: String, val confianca: Float)

/**
 * Resultado da classificação de um lote. `pular` são as falas confiavelmente no idioma de destino, `origem` é a
 * origem do lote, `und` conta as falas indeterminadas e `deVotos` diz se a origem saiu de votos (e não do texto
 * junto nem do chute). `indeterminadas` são as falas indeterminadas em ordem de leitura.
 */
internal class Classificacao(
    val pular: Set<String>, val origem: String, val und: Int, val deVotos: Boolean = false,
    val indeterminadas: List<String> = emptyList()
)

/**
 * A decisão de idioma por fala, sem ML Kit nem Android: recebe os candidatos já prontos. Fica à parte para ser
 * testada na JVM com candidatos fixos.
 *
 *  - O tamanho da fala é contado em "letras": cada caractere de letra vale 1, e cada caractere de escrita densa
 *    (Hangul, Han, Hiragana e Katakana) vale 3, porque em coreano, japonês e chinês um caractere diz o que o latim
 *    diz em umas três letras. Sem isso "정말 고마워요" (6 caracteres) nunca passaria de 12.
 *  - Fala com menos de MIN_LETRAS letras é indeterminada: não vota e não é pulada. "Hmph!" e "KRAAAK!!" são
 *    interjeição, o identificador chuta qualquer língua nelas.
 *  - É PULADA (já está no destino) quando o idioma mais provável é o destino, com confiança de pelo menos 0,75 e
 *    margem de pelo menos 0,30 sobre o segundo.
 *  - VOTA no idioma mais provável quando a confiança é de pelo menos 0,50 e ele não é "und" nem o destino, com peso
 *    igual ao número de letras (no máximo PESO_MAX por fala, para um texto longo não calar os outros).
 *  - Fora isso, indeterminada.
 * A origem do lote é o idioma de mais peso. Sem votos, vale o idioma do texto das indeterminadas juntas (textoJunto)
 * quando o identificador tem pelo menos 0,50 de confiança nele e ele não é "und" nem o destino; sem isso, o histórico
 * deste destino (a última origem que veio de votos com ele) e, sem histórico, "en". A origem pelo texto junto não conta
 * como voto (deVotos = false). NENHUMA origem automática é igual ao destino: o histórico igual ao destino é ignorado
 * e, com destino "en", o chute final é "und" (origem desconhecida) em vez de "en".
 * Com `origemFixa` o dono já escolheu: nada é pulado e a origem é a dele, mesmo igual ao destino.
 */
internal object RegraIdioma {
    const val MIN_LETRAS = 12
    const val PESO_MAX = 200
    /** origem que ninguém sabe: segue para o modelo (que não usa a origem), mas MyMemory e ML Kit não têm par para ela */
    const val ORIGEM_DESCONHECIDA = "und"
    /** peso de um caractere de escrita densa (Hangul, Han, Hiragana, Katakana) na contagem de letras */
    const val PESO_ESCRITA_DENSA = 3
    /** o texto junto das indeterminadas nunca passa disto, que é o que a detecção da tela inteira olhava */
    const val JUNTO_MAX = 300
    private const val CONF_PULAR = 0.75
    private const val MARGEM_PULAR = 0.30
    private const val CONF_VOTO = 0.50
    /** 0,9f - 0,6f dá 0,29999995 em float: sem esta folga a margem de 0,30 escrita em decimal falharia por arredondamento */
    private const val FOLGA = 1e-7

    /** Letras da fala, com o peso de cada caractere (ver acima). Conta por ponto de código, para não partir par substituto. */
    fun letras(t: String): Int {
        var n = 0
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            n += if (escritaDensa(cp)) PESO_ESCRITA_DENSA else 1
        }
        return n
    }

    private fun escritaDensa(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    /** O texto que se detecta quando ninguém vota: as indeterminadas, em ordem de leitura, juntas e cortadas em JUNTO_MAX. */
    fun textoJunto(indeterminadas: List<String>): String = indeterminadas.joinToString(" ").take(JUNTO_MAX)

    /** O chute quando nada decide: inglês, a não ser que o destino seja o próprio inglês, que nunca serve de origem automática. */
    fun origemPadrao(destino: String) = if (destino == "en") ORIGEM_DESCONHECIDA else "en"

    /** Só o dono fixar a origem igual ao destino devolve o lote inteiro sem traduzir; a origem automática nunca chega a ser igual a ele. */
    fun devolveTudoSemTraduzir(origemFixa: String, destino: String) = origemFixa.isNotBlank() && origemFixa == destino

    /** Resposta válida do modelo para uma fala: qualquer texto não vazio, INCLUSIVE igual ao original (ele preservou de propósito). */
    fun respostaDoModelo(resposta: String?): String? = resposta?.takeIf { it.isNotBlank() }

    /** `historico` é a última origem boa DESTE destino (HistoricoOrigem.de), se houver. */
    fun decidir(
        textos: List<String>, candidatos: Map<String, List<CandidatoIdioma>>, destino: String,
        origemFixa: String, historico: String?
    ): Classificacao {
        if (origemFixa.isNotBlank()) return Classificacao(emptySet(), origemFixa, 0)
        val pular = HashSet<String>()
        val pesos = LinkedHashMap<String, Int>()      // em ordem de leitura, para o empate cair na fala que veio primeiro
        val indeterminadas = ArrayList<String>()
        for (t in textos) {
            val n = letras(t)
            val ordem = if (n < MIN_LETRAS) emptyList() else candidatos[t].orEmpty().sortedByDescending { it.confianca }
            val primeiro = ordem.firstOrNull()
            if (primeiro == null) { indeterminadas += t; continue }
            val conf1 = primeiro.confianca.toDouble()
            val conf2 = ordem.getOrNull(1)?.confianca?.toDouble() ?: 0.0
            if (primeiro.idioma == destino && conf1 >= CONF_PULAR && conf1 - conf2 >= MARGEM_PULAR - FOLGA) pular += t
            else if (conf1 >= CONF_VOTO && primeiro.idioma != "und" && primeiro.idioma != destino)
                pesos[primeiro.idioma] = (pesos[primeiro.idioma] ?: 0) + minOf(n, PESO_MAX)
            else indeterminadas += t
        }
        val votada = pesos.maxByOrNull { it.value }?.key
        if (votada != null) return Classificacao(pular, votada, indeterminadas.size, true, indeterminadas)
        val junto = if (indeterminadas.isEmpty()) null else candidatos[textoJunto(indeterminadas)]?.maxByOrNull { it.confianca }
        val doJunto = if (junto != null && junto.confianca.toDouble() >= CONF_VOTO && junto.idioma != "und" && junto.idioma != destino) junto.idioma else null
        val padrao = historico?.takeIf { it != destino } ?: origemPadrao(destino)
        return Classificacao(pular, doJunto ?: padrao, indeterminadas.size, false, indeterminadas)
    }
}

/**
 * Histórico da origem POR DESTINO: a última origem que veio de votos quando o destino era aquele. É separado porque a
 * origem de um destino não vale para outro (destino "en" com voto "pt" não pode virar origem "pt" quando o destino
 * passa a ser "pt": o lote seria descartado como já traduzido), e porque nunca guarda nem devolve origem igual ao destino.
 */
internal class HistoricoOrigem {
    private val porDestino = ConcurrentHashMap<String, String>()

    fun de(destino: String): String? = porDestino[destino]?.takeIf { it != destino }

    fun registrar(destino: String, origem: String) {
        if (origem != destino) porDestino[destino] = origem
    }
}

/**
 * As regras de TEMPO do lote, sem relógio nem Android: tudo em System.nanoTime, passado por quem chama. Existe um prazo
 * absoluto só, `prazoFinal`, e nada abre tempo novo depois dele.
 */
internal object RegraEspera {
    /** Milissegundos que restam até `prazo`, nunca negativo. */
    fun restanteMs(agora: Long, prazo: Long): Long = ((prazo - agora) / 1_000_000L).coerceAtLeast(0L)

    /**
     * Se o laço de espera do lote sai agora. Sai quando: o prazo final venceu; o modelo chegou com resultado; a máquina ou
     * o local chegaram (`temReserva`) e o modelo já falhou (`modeloVisto` sem resultado) ou já passou o tempo que se
     * prefere esperar por ele (`prefere`); ou os três terminaram sem que nenhum servisse, e esperar não traz mais nada.
     */
    fun sair(
        agora: Long, prefere: Long, prazoFinal: Long,
        modeloComResultado: Boolean, modeloVisto: Boolean, temReserva: Boolean, maquinaVista: Boolean, localVisto: Boolean
    ): Boolean = when {
        agora >= prazoFinal -> true
        modeloComResultado -> true
        temReserva && (modeloVisto || agora >= prefere) -> true
        modeloVisto && maquinaVista && localVisto -> true
        else -> false
    }

    /** Quanto dormir entre uma olhada e outra: 40 ms, mas nunca passando do prazo final e nunca menos de 1 ms. */
    fun passoMs(agora: Long, prazoFinal: Long): Long = restanteMs(agora, prazoFinal).coerceIn(1L, 40L)

    /** Limite do offline de fechamento: o MENOR entre o prazo final do lote e `tetoMs` a partir de agora. */
    fun limiteOffline(agora: Long, prazoFinal: Long, tetoMs: Long): Long = minOf(prazoFinal, agora + tetoMs * 1_000_000L)

    /** Quanto a espera extra pela máquina pode durar: o que sobra do prazo final, no máximo `maxMs`. */
    fun esperaMaquinaMs(agora: Long, prazoFinal: Long, maxMs: Long): Long = restanteMs(agora, prazoFinal).coerceAtMost(maxMs)
}

/**
 * O offline de UM lote, dividido entre quem quiser trabalhar nele (o paralelo `fLocal` e o fechamento). Cada fala é
 * tentada UMA vez só, por quem pegar primeiro, mesmo quando a tentativa falha: ninguém repete. O resultado vai para
 * `feitas` (índice -> texto) à medida que sai. Quem trabalha para de pegar fala nova quando o prazo vence ou quando o
 * lote encerra. `traduz` recebe o índice e o prazo e respeita o prazo por conta própria.
 */
internal class OfflineLote(private val quantas: Int, private val traduz: (indice: Int, prazo: Long) -> String?) {
    val feitas = ConcurrentHashMap<Int, String>()
    private val proximo = AtomicInteger(0)
    @Volatile private var encerrado = false

    fun encerrar() { encerrado = true }

    /** `precisa` deixa quem chama pular falas que já estão resolvidas; a fala pulada também conta como pega. */
    fun trabalhar(prazo: Long, precisa: (Int) -> Boolean = { true }) {
        while (!encerrado && System.nanoTime() < prazo) {
            val i = proximo.getAndIncrement()
            if (i >= quantas) return
            if (!precisa(i)) continue
            val t = traduz(i, prazo)
            if (!t.isNullOrBlank()) feitas[i] = t
        }
    }
}

/** O que da telemetria vem de resposta ou de texto vira código fixo: fala e conteúdo de resposta nunca saem do aparelho. */
internal object SaneaTelemetria {
    private val NOME_MODELO = Regex("^[A-Za-z0-9._:/-]{1,60}$")

    /** Nome de modelo só se parecer nome de modelo (letras, dígitos e . _ : / -); texto livre vira "?". */
    fun modelo(s: String): String = if (NOME_MODELO.matches(s)) s else "?"

    fun http(codigo: Int): String = "http_$codigo"

    /** Motivo fixo da cota do MyMemory acabada, ou null se não é caso de cota. O texto recebido só decide, nunca é devolvido. */
    fun motivoCota(quotaFinished: Boolean, status: Int, texto: String): String? = when {
        quotaFinished -> "quota_finished"
        status == 429 -> "status_429"
        texto.contains("MYMEMORY WARNING", true) || texto.contains("FREE TRANSLATIONS", true) -> "mymemory_warning"
        else -> null
    }
}

/** Mapa com no máximo `max` entradas: ao passar disso sai a que ficou mais tempo sem ser usada. Seguro entre threads. */
internal fun <V> lruPequeno(max: Int): MutableMap<String, V> =
    java.util.Collections.synchronizedMap(object : LinkedHashMap<String, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > max
    })
