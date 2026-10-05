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
import java.util.concurrent.CompletableFuture
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
 * acertaria. A chave é normalizada (normalizarFala) porque o OCR varia maiúscula, pontuação e espaço de um quadro para
 * outro, mas sem juntar palavras diferentes. Ela leva o DESTINO mas não a origem detectada: a detecção oscilava (en, sk,
 * pt para o mesmo texto) e fragmentava o cache, e a mesma fala era traduzida de novo. O mesmo texto pedido por dois
 * pedidos ao mesmo tempo é traduzido uma vez só (emAndamento).
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

    /**
     * O que está sendo pedido AGORA, por chave do cache. O pré-carregamento do modo contínuo e o toque podiam pedir a
     * mesma fala ao mesmo tempo: o cache só era consultado antes e o trabalho em andamento não ficava registrado, então a
     * rede recebia o pedido repetido e a cota era gasta duas vezes. Agora quem chega primeiro registra o futuro da fala
     * (Coordenacao.consultarOuReservar, na mesma seção curta da consulta ao cache, sem rede dentro) e os outros esperam
     * por ele, no máximo até o PRÓPRIO prazo. Quem decide a fala completa o futuro e o tira do mapa (Coordenacao.concluir).
     */
    private val emAndamento = ConcurrentHashMap<String, CompletableFuture<Entrada?>>()
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

    /** A mesma normalização para a chave do cache e para o LRU de idioma (ver normalizarFala). */
    private fun normalizar(s: String) = normalizarFala(s)

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

    /**
     * Os motores que o LOTE chama para falar com o mundo de fora: verificação de rede, tradutor de máquina e ML Kit. São
     * trocáveis porque na JVM do harness não existe nenhum dos três e o teste precisa mandar em quem responde e quando; em
     * produção ficam sempre os reais. Cada pedido lê o conjunto uma vez, ao nascer (Pedido.mot), para não misturar motores
     * no caminho. A tradução de uma fala só (`traduzir`) continua chamando os reais.
     */
    @Volatile internal var motores = Motores(::temRede, ::porRede, ::local)

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

    /**
     * O estado de UM pedido de tradução. Tudo o que antes eram campos globais `ultimo*` mora aqui, e por isso dois pedidos
     * ao mesmo tempo (o pré-carregamento do modo contínuo e o toque) não misturam as medidas um do outro. Mais de uma
     * thread escreve aqui (o laço das próprias falas, o acompanhamento das alheias e as recuperações), então o mapa é
     * concorrente e os contadores são atômicos; o resultado devolve uma CÓPIA do mapa.
     */
    private class Pedido(val t0: Long, val dest: String, val origem: String) {
        /** o único prazo do pedido: nada espera nem abre tempo novo depois dele */
        val prazoFinal = t0 + PRAZO_MODELO * 1_000_000_000L
        val mot = motores
        val saida: MutableMap<String, String> = ConcurrentHashMap()
        @Volatile var comRede = false
        @Volatile var semMaquina = false
        val online = AtomicInteger(0)
        val offline = AtomicInteger(0)
        @Volatile var msModelo = -1L
        @Volatile var modelo: String? = null
        @Volatile var idServidor: String? = null
        val compartilhadas = AtomicInteger(0)
    }

    private fun resultado(p: Pedido, puladas: Set<String>, und: Int) = ResultadoLote(
        HashMap(p.saida), puladas, und, p.origem, SaneaTelemetria.caminho(p.online.get(), p.offline.get()), p.modelo, p.msModelo,
        p.online.get(), p.offline.get(), p.compartilhadas.get(), p.idServidor
    )

    /**
     * Quem só quer o mapa das traduções. Preenche, se pedido, o conjunto das falas puladas DESTE pedido (o contador global
     * de puladas era sobrescrito pelo pré-carregamento ao mesmo tempo; revisão do Astra, 04/10).
     */
    fun traduzirLote(
        ctx: Context, textos: List<String>, puladasDoPedido: MutableSet<String>? = null, ativo: () -> Boolean = { true }
    ): Map<String, String> {
        val r = traduzirLoteDetalhado(ctx, textos, ativo = ativo)
        puladasDoPedido?.addAll(r.puladas)
        return r.mapa
    }

    /**
     * Traduz a tela INTEIRA numa requisição só, numerando as falas. Medido em 23/09: o serviço devolve a
     * numeração intacta e cada linha traduzida. Mandar uma requisição por fala estourava o limite de uso e o app
     * caía para o motor local no meio da leitura, que foi o que a telemetria mostrou acontecendo.
     *
     * As falas que já estão no idioma de destino (classificar) voltam como estão, sem tradução; só as outras seguem
     * para cache, rede e local.
     *
     * Regras da revisão do Astra:
     *  - UM prazo absoluto, prazoFinal = início + PRAZO_MODELO, vale para tudo: o laço de espera, a espera extra pela
     *    máquina e o offline (o paralelo e o de fechamento). Nada abre tempo novo depois dele.
     *  - O pedido é uma FOTO: destino e origem fixa são lidos uma vez, aqui, e vão explícitos para a classificação, as
     *    chaves e os motores. Se o dono trocar o idioma no meio, este lote termina no idioma em que começou. Quem
     *    precisa de exatidão (o leitor dá nome de arquivo com o destino) passa o seu em `destinoDoPedido`.
     *  - Fala que outro pedido já está traduzindo NÃO vai de novo à rede: este pedido acompanha o futuro dele JUNTO com as
     *    próprias falas (acompanharAlheias), e não depois delas. Dentro dos mesmos 9 s fica reservada uma janela de
     *    JANELA_RECUPERACAO_MS: a espera pelo trabalho alheio acaba em prazoFinal menos a janela, as próprias falas de um
     *    pedido misto também, e a fala cujo futuro falhou (ou não veio) é recuperada nessa janela (recuperar).
     *  - As medidas voltam no ResultadoLote DESTE pedido; não há campo global.
     *  - `ativo` é a pergunta "ainda vale a pena esperar?" (o toque liga ao isActive da corrotina). Os laços de espera saem
     *    cedo quando ela é falsa. Só se PARA de esperar: o futuro compartilhado nunca é cancelado.
     */
    fun traduzirLoteDetalhado(
        ctx: Context, textos: List<String>, destinoDoPedido: String? = null, ativo: () -> Boolean = { true }
    ): ResultadoLote {
        val t0 = System.nanoTime()
        val dest = destinoDoPedido ?: destino
        val fixa = origemFixa
        val c = classificar(textos, dest, fixa)
        val p = Pedido(t0, dest, c.origem)
        // só o dono fixar a origem igual ao destino devolve tudo sem traduzir; a origem automática nunca é igual ao destino
        if (RegraIdioma.devolveTudoSemTraduzir(fixa, dest)) {
            textos.forEach { p.saida[it] = it }
            return resultado(p, textos.toSet(), c.und)
        }
        for (t in textos) if (t in c.pular) p.saida[t] = t
        // As falas deste pedido, repartidas: as que EU peço (`faltando`, com a chave e o futuro que registrei, na mesma
        // ordem) e as que outro pedido já está traduzindo (`alheias`). A consulta ao cache e o registro do futuro de cada
        // fala são uma seção só (Coordenacao.consultarOuReservar), curta e sem rede dentro.
        val faltando = ArrayList<String>()
        val chaves = ArrayList<String>()
        val futuros = ArrayList<CompletableFuture<Entrada?>?>()
        val alheias = ArrayList<Acompanhamento.Item<Entrada>>()
        val minhas = HashMap<String, Int>()
        val vistas = HashSet<String>()
        // o registro também está no try: o que ficou registrado até qualquer falha sai com null no finally
        try {
            for (t in textos) {
                if (t in c.pular || !vistas.add(t)) continue
                val k = chave(t, dest)
                if (k in minhas) continue      // outra grafia de uma fala que eu já peço: sai com o resultado dela
                val r = Coordenacao.consultarOuReservar(k, { cache[it] }, emAndamento)
                val pronta = r.pronta
                when {
                    pronta != null -> p.saida[t] = pronta.texto
                    r.minha -> { minhas[k] = faltando.size; faltando += t; chaves += k; futuros += r.futuro }
                    else -> alheias += Acompanhamento.Item(t, k, r.futuro!!)
                }
            }
            if (faltando.isEmpty() && alheias.isEmpty()) return resultado(p, c.pular, c.und)
            p.comRede = p.mot.temRede(ctx)
            p.semMaquina = System.currentTimeMillis() < maquinaEsgotadaAte
            // Com fala de outro pedido no lote, as próprias também param na janela de recuperação: o laço delas não pode
            // consumir o tempo que as alheias têm para ser recuperadas.
            val limiteProprio = RegraEspera.limiteProprio(p.prazoFinal, JANELA_RECUPERACAO_MS, alheias.isNotEmpty())
            if (faltando.isEmpty()) acompanharAlheias(p, alheias, ativo)
            else {
                // O acompanhamento das alheias roda JUNTO com as próprias falas, numa thread de apoio do pedido: o laço das
                // próprias bloqueia em etapas longas (espera extra da máquina, fechamento offline) e a sondagem pararia nelas.
                val apoio = if (alheias.isEmpty()) null else Thread { runCatching { acompanharAlheias(p, alheias, ativo) } }
                    .also { it.isDaemon = true; it.start() }
                executar(p, faltando, chaves, futuros, comModelo = true, limite = limiteProprio, ativo = ativo)
                apoio?.let { runCatching { it.join(RegraEspera.restanteMs(System.nanoTime(), p.prazoFinal) + 50) } }
            }
        } finally {
            // nada fica registrado para sempre: o que não foi decidido sai com null e libera a fala para nova tentativa
            for (i in faltando.indices) futuros[i]?.let { Coordenacao.concluir(chaves[i], it, emAndamento) { null } }
        }
        // a alheia que ninguém resolveu nem recuperou fica no original
        for (a in alheias) if (a.texto !in p.saida) p.saida[a.texto] = a.texto
        // outras grafias da mesma fala neste pedido saem com o resultado da que foi pedida
        for (t in textos) if (t !in p.saida) minhas[chave(t, dest)]?.let { p.saida[t] = p.saida[faltando[it]] ?: t }
        return resultado(p, c.pular, c.und)
    }

    /**
     * Acompanha as falas que OUTRO pedido traduz, JUNTO com as próprias do pedido (não depois delas). Sonda os futuros
     * dele com isDone, nunca os cancela, e termina no máximo em prazoFinal menos JANELA_RECUPERACAO_MS. O futuro que
     * termina com null (falha) tem a recuperação disparada NA HORA, sem esperar os demais; os que ainda não terminaram no
     * limite entram na recuperação com o tempo que sobra até o prazoFinal. As recuperações rodam em threads próprias.
     */
    private fun acompanharAlheias(p: Pedido, alheias: List<Acompanhamento.Item<Entrada>>, ativo: () -> Boolean) {
        val recuperacoes = ArrayList<java.util.concurrent.Future<*>>()
        var pool: java.util.concurrent.ExecutorService? = null
        fun dispara(itens: List<Acompanhamento.Item<Entrada>>) {
            val ex = pool ?: java.util.concurrent.Executors.newCachedThreadPool().also { pool = it }
            recuperacoes += ex.submit(Runnable { recuperar(p, itens, ativo) })
        }
        try {
            Acompanhamento.rodar(
                Acompanhamento.Alheias(alheias), RegraEspera.limiteAlheias(p.prazoFinal, JANELA_RECUPERACAO_MS), p.prazoFinal,
                { System.nanoTime() }, ativo,
                aoTerminar = { prontos ->
                    val falhas = ArrayList<Acompanhamento.Item<Entrada>>()
                    for (x in prontos) {
                        val e = x.entrada
                        if (e != null) { p.saida[x.item.texto] = e.texto; p.compartilhadas.incrementAndGet() } else falhas += x.item
                    }
                    if (falhas.isNotEmpty()) dispara(falhas)
                },
                aoVencer = { itens -> dispara(itens) },
                recuperando = { recuperacoes.any { !it.isDone } },
                dorme = { ms -> runCatching { Thread.sleep(ms) } }
            )
        } finally {
            pool?.shutdown()
        }
    }

    /**
     * A recuperação de falas cujo futuro alheio falhou ou não veio a tempo (Coordenacao.recuperar): confere o tempo (menos
     * de RegraRecuperacao.REDE_MIN_MS, nada de rede), refaz a consulta ao cache, reserva "rec|" + chave para que UM
     * consumidor recupere cada fala, e o dono tenta máquina e offline AO MESMO TEMPO, com o mesmo prazo e o mesmo `ativo`
     * (o que entregar primeiro serve), nunca o modelo (já foi tentado para ela). Quem não é dono espera o dono ou
     * reaproveita o que ele achou. Roda numa thread própria, disparada assim que a falha é vista.
     */
    private fun recuperar(p: Pedido, itens: List<Acompanhamento.Item<Entrada>>, ativo: () -> Boolean) {
        val grupos = itens.groupBy { it.chave }
        val r = Coordenacao.recuperar(
            grupos.keys.toList(), emAndamento, { cache[it] },
            { RegraEspera.restanteMs(System.nanoTime(), p.prazoFinal) },
            { f -> Coordenacao.esperar(f, RegraEspera.restanteMs(System.nanoTime(), p.prazoFinal), ativo) }
        ) { minhas, futurosRec, plano ->
            val textos = minhas.map { grupos.getValue(it).first().texto }
            executar(p, textos, minhas, futurosRec, comModelo = false, limite = p.prazoFinal, ativo = ativo,
                comRede = p.comRede && plano == RegraRecuperacao.Plano.REDE,
                chavesFuturo = minhas.map { Coordenacao.PREFIXO_RECUPERACAO + it },
                offlineParalelo = true)
        }
        for ((k, e) in r.reaproveitadas) for (item in grupos.getValue(k)) {
            p.saida[item.texto] = e.texto; p.compartilhadas.incrementAndGet()
        }
        // outras grafias da mesma chave, que este pedido recuperou, saem com o resultado da primeira
        for (k in r.minhas) {
            val g = grupos.getValue(k)
            val base = p.saida[g.first().texto] ?: continue
            for (extra in g.drop(1)) p.saida[extra.texto] = base
        }
    }

    /**
     * O caminho de uma lista de falas que ESTE pedido traduz: modelo e máquina em paralelo, o ML Kit em paralelo quando a
     * máquina está sem cota (ou na recuperação), e o fechamento offline do que sobrar. `futuros[i]` é o futuro da fala i
     * que este pedido registrou em emAndamento, ou nulo quando a fala não foi registrada (a recuperação de uma fala alheia,
     * cujo futuro é da chave "rec|"): cada fala decidida completa o seu futuro com a entrada que ficou de fato no cache, e
     * a que fica no original o completa com null. `comModelo` é falso na recuperação, em que o modelo já foi tentado.
     * `limite` é até onde este caminho espera (o prazo final, ou a janela de recuperação antes dele num pedido misto),
     * `ativo` faz os laços saírem cedo, `comRede` deixa a recuperação com pouco tempo fora da rede, `chavesFuturo` são as
     * chaves de emAndamento dos futuros (a de recuperação, "rec|" + chave, não é a do cache) e `offlineParalelo` põe o
     * ML Kit para trabalhar desde o início, ao lado da máquina, mesmo com a cota dela viva.
     */
    private fun executar(
        p: Pedido, faltando: List<String>, chaves: List<String>,
        futuros: List<CompletableFuture<Entrada?>?>, comModelo: Boolean,
        limite: Long, ativo: () -> Boolean, comRede: Boolean = p.comRede, chavesFuturo: List<String> = chaves,
        offlineParalelo: Boolean = false
    ) {
        if (faltando.isEmpty()) return
        val saida = p.saida
        val origem = p.origem
        val dest = p.dest
        val mot = p.mot
        // Fecha uma fala: grava no cache, completa o futuro (se a fala é minha) com a entrada que ficou de fato e o tira do
        // mapa. Devolve o texto que vale, que é o do modelo se ele já tinha gravado.
        fun fecha(i: Int, texto: String, nivel: Int): String {
            val futuro = futuros[i] ?: return guardar(chaves[i], texto, nivel).entrada.texto
            return Coordenacao.concluir(chavesFuturo[i], futuro, emAndamento) { guardar(chaves[i], texto, nivel).entrada }?.texto ?: texto
        }
        // O offline, dividido entre o paralelo (fLocal) e o fechamento: cada fala é tentada UMA vez só, por quem pegar
        // primeiro, e o que sai fica em offline.feitas à medida que sai.
        val offline = OfflineLote(faltando.size) { i, prazo -> mot.local(faltando[i], origem, dest, prazo) }
        // Sem a máquina (cota acabada), quem faz o papel de resposta rápida é o ML Kit no aparelho, pedido do dono em
        // 01/10. Ele corre em paralelo e o modelo, chegando depois, corrige o cache. Na recuperação (`offlineParalelo`) ele
        // também sai junto com a máquina, com o mesmo limite e o mesmo `ativo`: o tempo já foi gasto antes, e esperar o
        // MyMemory até o prazo final deixava o offline sem tempo para tentar (revisão do Astra, 04/10). Quem entregar
        // primeiro serve, e o nível do cache impede o offline de pisar numa tradução melhor. Para de pegar fala nova quando o
        // limite vence ou o lote termina (offline.encerrar), em vez de seguir traduzindo para ninguém.
        val fLocal = if (comRede && (p.semMaquina || offlineParalelo)) java.util.concurrent.Executors.newSingleThreadExecutor().let { ex ->
            ex.submit(Runnable { offline.trabalhar(limite, ativo) }).also { ex.shutdown() }
        } else null
        try {
            if (comRede) {
                // Os dois caminhos online saem AO MESMO TEMPO. O modelo traduz melhor mas leva de 4 a 7 s; o tradutor
                // de máquina responde em ~1 s. Em fila, o dono esperava a soma — foi a lentidão que ele sentiu.
                // Agora espera-se o modelo até PRAZO_MODELO e, se ele não chegar, usa o que a máquina já trouxe.
                val piscina = java.util.concurrent.Executors.newFixedThreadPool(2)
                val numerado = faltando.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
                // o instante em que o modelo terminou é gravado por ele mesmo: o laço abaixo só olha de 40 em 40 ms
                val fimModelo = AtomicLong(0L)
                val fModelo = if (comModelo) piscina.submit<RespostaModelo> {
                    porModelo(faltando, origem, dest).also { fimModelo.set(System.nanoTime()) }
                } else null
                val fMaquina = piscina.submit<String?> { mot.maquina(numerado, origem, dest) }
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
                 * O teto é o `limite`. Sem modelo neste caminho (recuperação), ele conta como terminado sem resultado, e a
                 * máquina e o local disputam: o que entregar primeiro encerra a espera.
                 */
                var resposta: RespostaModelo? = null
                var doModelo: List<String>? = null
                var daMaquina: Map<Int, String>? = null
                var reservaLocal = false
                var modeloVisto = fModelo == null
                var maquinaVista = false
                var localVisto = fLocal == null
                val prefere = p.t0 + PREFERE_MODELO_MS * 1_000_000L
                while (true) {
                    if (!modeloVisto && fModelo != null && fModelo.isDone) {
                        modeloVisto = true
                        resposta = runCatching { fModelo.get() }.getOrNull()
                        doModelo = resposta?.falas
                    }
                    if (!maquinaVista && fMaquina.isDone) {
                        maquinaVista = true
                        daMaquina = runCatching { fMaquina.get() }.getOrNull()?.let { numeradas(it, faltando.size) }
                    }
                    if (!localVisto && fLocal != null && fLocal.isDone) {
                        localVisto = true
                        reservaLocal = offline.feitas.size >= faltando.size * 0.6
                    }
                    // modeloVisto aqui quer dizer modelo sem resultado, porque com resultado a decisão já é sair
                    if (!ativo() || RegraEspera.sair(System.nanoTime(), prefere, limite, doModelo != null, modeloVisto,
                            daMaquina != null || reservaLocal, maquinaVista, localVisto)) break
                    runCatching { Thread.sleep(RegraEspera.passoMs(System.nanoTime(), limite)) }
                }
                if (resposta != null) { p.modelo = resposta.modelo; p.idServidor = resposta.id }
                if (doModelo != null) {
                    val fim = fimModelo.get().takeIf { it != 0L } ?: System.nanoTime()
                    p.msModelo = (fim - p.t0) / 1_000_000L
                } else if (fModelo != null) {
                    // não cancela: deixa o modelo terminar em segundo plano e CORRIGIR o cache, e avisa quem desenha.
                    // Resposta válida igual ao original também vale (nível 2): ela pode desfazer uma alteração da máquina.
                    // Esta Thread só grava no cache: os futuros das falas já foram completados e não são tocados aqui.
                    Thread {
                        runCatching { fModelo.get(40, TimeUnit.SECONDS) }.getOrNull()?.falas?.let { tarde ->
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
                        saida[t] = fecha(i, it, NIVEL_MODELO); p.online.incrementAndGet()
                    }
                }
                if (!p.semMaquina && ativo() && faltando.any { it !in saida }) {
                    // a espera extra pela máquina nunca passa do que sobrou do limite, e não existe quando o offline paralelo
                    // já entregou: a máquina atrasada não segura o lote, e se ela já chegou entra aqui do mesmo jeito
                    val resta = if (reservaLocal) 0L else RegraEspera.esperaMaquinaMs(System.nanoTime(), limite, 6_000L)
                    val linhas = daMaquina ?: Coordenacao.esperarFuturo(fMaquina, resta, ativo)?.let { numeradas(it, faltando.size) }
                    if (linhas != null) for ((i, t) in faltando.withIndex())
                        if (t !in saida) linhas[i]?.let { saida[t] = fecha(i, it, NIVEL_MAQUINA); p.online.incrementAndGet() }
                }
            }
            // Fechamento offline do que ainda falta. Teto: o MENOR entre o limite e TETO_OFFLINE_MS a partir de agora,
            // para todas as falas juntas e não por fala. Reaproveita o que o paralelo já traduziu e só pega as falas que
            // ninguém pegou, sem repetir; o que não couber fica no original.
            if (ativo() && faltando.any { it !in saida }) {
                val limiteOffline = RegraEspera.limiteOffline(System.nanoTime(), limite, TETO_OFFLINE_MS)
                offline.trabalhar(limiteOffline, ativo) { faltando[it] !in saida }
                fLocal?.let { Coordenacao.esperarFuturo(it, RegraEspera.restanteMs(System.nanoTime(), limiteOffline), ativo) }
                for ((i, t) in faltando.withIndex()) if (t !in saida) {
                    val l = offline.feitas[i]
                    if (l != null) { saida[t] = fecha(i, l, NIVEL_OFFLINE); p.offline.incrementAndGet() }
                }
            }
            // A que ninguém decidiu fica no original, e o futuro dela sai com null: quem esperava cai no caminho dele.
            for ((i, t) in faltando.withIndex()) if (t !in saida) {
                saida[t] = t
                futuros[i]?.let { Coordenacao.concluir(chavesFuturo[i], it, emAndamento) { null } }
            }
        } finally {
            offline.encerrar()
        }
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
        if (texto in c.pular || RegraIdioma.devolveTudoSemTraduzir(fixa, dest)) return texto
        if (normalizar(texto).isEmpty()) return texto
        val k = chave(texto, dest)
        cache[k]?.let { return it.texto }
        val online = if (temRede(ctx)) porRede(texto, origem, dest) else null
        val r = online ?: local(texto, origem, dest, prazoFinal) ?: texto
        return if (r != texto) guardar(k, r, if (online != null) NIVEL_MAQUINA else NIVEL_OFFLINE).entrada.texto else r
    }

    /**
     * NOSSO serviço: modelo de linguagem, a tela inteira numa chamada. A chave do provedor fica NO SERVIDOR; o app
     * manda só um token que dá direito a traduzir dentro de uma cota. Foi por isso que ele existe: o APK é
     * instalado fora da loja e qualquer um consegue abrir e ler o que está dentro.
     * Medido em 23/09: acerta "Você é o Sr. Seonghyeon Han?", que os outros dois caminhos erravam.
     */
    private fun porModelo(falas: List<String>, origem: String, dest: String): RespostaModelo {
        if (BuildConfig.TRADUTOR_TOKEN.isEmpty() || falas.isEmpty()) return RespostaModelo(null, null, null)
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
            if (con.responseCode != 200) return@runCatching RespostaModelo(null, "http " + con.responseCode, idDoErro(con.errorStream))
            val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val a = j.getJSONArray("falas")
            // o nome do modelo e o id vêm da resposta e vão para a telemetria: só passam se tiverem a cara de nome e de id
            RespostaModelo(List(a.length()) { a.getString(it) }, SaneaTelemetria.modelo(j.optString("modelo", "?")),
                SaneaTelemetria.idServidor(j.optString("id", "")))
        }.getOrElse { RespostaModelo(null, "falhou", null) }
    }

    /** O id que o serviço põe também na resposta de erro, para casar o pedido com o registro dele. Nada além do id sai daqui. */
    private fun idDoErro(fluxo: java.io.InputStream?): String? = runCatching {
        fluxo?.bufferedReader()?.use { it.readText().take(4096) }
            ?.let { SaneaTelemetria.idServidor(JSONObject(it).optString("id", "")) }
    }.getOrNull()

    /**
     * O que o nosso serviço respondeu neste pedido. `falas` nulo é pedido sem tradução; `modelo` diz o que houve ("http
     * 503", "falhou" ou o nome do modelo) e é nulo quando o serviço nem foi chamado; `id` é o aleatório do serviço.
     */
    private class RespostaModelo(val falas: List<String>?, val modelo: String?, val id: String?)

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
    /**
     * Janela reservada, DENTRO dos mesmos 9 s do prazo final, para recuperar a fala cujo futuro alheio falhou ou não veio.
     * A espera pelo trabalho de outro pedido acaba em prazoFinal menos esta janela, e as próprias falas de um pedido misto
     * também, para que nenhuma das duas consuma o tempo da recuperação. Foi a revisão do Astra de 04/10: esperar tudo
     * primeiro e recuperar depois podia devolver o original com o pacote offline à mão.
     */
    const val JANELA_RECUPERACAO_MS = 2500L
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
 *  - Só vota um idioma PLAUSÍVEL como origem de quadrinho (IDIOMAS_PLAUSIVEIS). Fala cujo melhor candidato está fora
 *    do conjunto ("zu", "sk", "gl" e "ca" fora da família) é indeterminada, como acima: a telemetria da 0.25 (04/10)
 *    mostrou frases curtas de interface em português decididas como "gl", "ca", "es" e até "zu".
 * FAMÍLIA DO PORTUGUÊS (só com destino "pt"): o galego escrito é quase igual ao português e galego não é conteúdo
 * provável para o dono, então a confiança de "pt" e de "gl" da fala é somada numa só, "pt". Com "pt" (ou "gl") entre os
 * candidatos com pelo menos 0,20, a confiança dos vizinhos "es", "ca" e "it" entra na mesma soma: o identificador troca
 * frase curta de interface em português por espanhol ou catalão. Sem "pt" nem "gl" entre os candidatos, o vizinho segue
 * como idioma próprio (um mangá em espanhol continua sendo espanhol). Depois da soma vale a regra de pular de sempre,
 * com a margem medida sobre o melhor candidato de FORA da família.
 * A origem do lote é o idioma de mais peso. Sem votos, vale o idioma do texto das indeterminadas juntas (textoJunto)
 * quando o identificador tem pelo menos 0,50 de confiança nele e ele é plausível, não é "und" nem o destino; sem isso, o
 * histórico deste destino (a última origem que veio de votos com ele) e, sem histórico, "en". A origem pelo texto junto
 * não conta como voto (deVotos = false). NENHUMA origem automática é igual ao destino: o histórico igual ao destino é
 * ignorado e, com destino "en", o chute final é "und" (origem desconhecida) em vez de "en".
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
    /** confiança mínima de "pt" (ou "gl") para os vizinhos entrarem na família do português */
    private const val CONF_FAMILIA = 0.20
    /** idiomas que confundem com o português em frase curta; só entram na família com "pt" ou "gl" entre os candidatos */
    private val VIZINHOS_DO_PORTUGUES = setOf("es", "ca", "it")
    /** origens plausíveis de quadrinho: só estas votam na origem da tela (ver acima) */
    val IDIOMAS_PLAUSIVEIS: Set<String> = setOf("en", "ja", "ko", "zh", "es", "fr", "de", "it", "ru", "id", "th", "vi", "tl", "pt")

    /**
     * Com destino "pt", soma numa só confiança de "pt" a de "pt" e "gl" da fala e, havendo "pt" ou "gl" com pelo menos
     * CONF_FAMILIA, também a dos vizinhos "es", "ca" e "it". A soma fica no lugar do primeiro membro da família e nunca
     * passa de 1. Com outro destino, ou sem "pt" nem "gl" entre os candidatos, a lista volta como veio.
     */
    fun familiaDoPortugues(candidatos: List<CandidatoIdioma>, destino: String): List<CandidatoIdioma> {
        if (destino != "pt") return candidatos
        val doPortugues = { c: CandidatoIdioma -> c.idioma == "pt" || c.idioma == "gl" }
        if (candidatos.none(doPortugues)) return candidatos
        val vizinhosEntram = candidatos.any { doPortugues(it) && it.confianca.toDouble() >= CONF_FAMILIA - FOLGA }
        val familia = candidatos.filter { doPortugues(it) || (vizinhosEntram && it.idioma in VIZINHOS_DO_PORTUGUES) }
        val soma = minOf(1.0, familia.sumOf { it.confianca.toDouble() }).toFloat()
        val primeiro = familia.first()
        return candidatos.mapNotNull { c -> if (c === primeiro) CandidatoIdioma("pt", soma) else if (c in familia) null else c }
    }

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

    /**
     * `historico` é a última origem boa DESTE destino (HistoricoOrigem.de), se houver. Os candidatos chegam crus, como o
     * identificador devolveu, e a família do português é somada aqui, uma vez, para a fala e para o texto junto.
     */
    fun decidir(
        textos: List<String>, candidatosCrus: Map<String, List<CandidatoIdioma>>, destino: String,
        origemFixa: String, historico: String?
    ): Classificacao {
        if (origemFixa.isNotBlank()) return Classificacao(emptySet(), origemFixa, 0)
        val candidatos = candidatosCrus.mapValues { familiaDoPortugues(it.value, destino) }
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
            else if (conf1 >= CONF_VOTO && primeiro.idioma != "und" && primeiro.idioma != destino && primeiro.idioma in IDIOMAS_PLAUSIVEIS)
                pesos[primeiro.idioma] = (pesos[primeiro.idioma] ?: 0) + minOf(n, PESO_MAX)
            else indeterminadas += t
        }
        val votada = pesos.maxByOrNull { it.value }?.key
        if (votada != null) return Classificacao(pular, votada, indeterminadas.size, true, indeterminadas)
        val junto = if (indeterminadas.isEmpty()) null else candidatos[textoJunto(indeterminadas)]?.maxByOrNull { it.confianca }
        val doJunto = if (junto != null && junto.confianca.toDouble() >= CONF_VOTO && junto.idioma != "und" && junto.idioma in IDIOMAS_PLAUSIVEIS && junto.idioma != destino) junto.idioma else null
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

    /** Até onde se espera pelo trabalho de OUTRO pedido: o prazo final menos a janela de recuperação, nunca depois dele. */
    fun limiteAlheias(prazoFinal: Long, janelaMs: Long): Long = prazoFinal - janelaMs * 1_000_000L

    /**
     * Até onde as PRÓPRIAS falas esperam. Num pedido misto, com fala de outro pedido no lote, é o mesmo limite das alheias:
     * o laço do próprio pedido não pode consumir a janela em que as alheias são recuperadas. Sem alheias, é o prazo final.
     */
    fun limiteProprio(prazoFinal: Long, janelaMs: Long, temAlheias: Boolean): Long =
        if (temAlheias) limiteAlheias(prazoFinal, janelaMs) else prazoFinal
}

/**
 * Os três motores que o lote chama: se há rede, o tradutor de máquina (uma requisição para todas as falas numeradas) e o
 * ML Kit no aparelho (uma fala, com o prazo em System.nanoTime). Ver Traducao.motores.
 */
internal class Motores(
    val temRede: (Context) -> Boolean,
    val maquina: (texto: String, origem: String, dest: String) -> String?,
    val local: (texto: String, origem: String, dest: String, prazo: Long) -> String?
)

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

    /**
     * `ativo` faz quem trabalha parar de pegar fala nova quando o pedido deixa de valer a pena. `precisa` deixa quem chama
     * pular falas que já estão resolvidas; a fala pulada também conta como pega. (`precisa` é o último parâmetro para o
     * lambda final continuar sendo ele.)
     */
    fun trabalhar(prazo: Long, ativo: () -> Boolean = { true }, precisa: (Int) -> Boolean = { true }) {
        while (!encerrado && ativo() && System.nanoTime() < prazo) {
            val i = proximo.getAndIncrement()
            if (i >= quantas) return
            if (!precisa(i)) continue
            val t = traduz(i, prazo)
            if (!t.isNullOrBlank()) feitas[i] = t
        }
    }
}

/**
 * O resultado de UM pedido de tradução, com as medidas do próprio pedido: nada aqui é global, então dois pedidos ao mesmo
 * tempo não misturam os números um do outro. `puladas` são as falas que já estavam no idioma de destino, `und` conta as
 * indeterminadas, `origem` é a origem do pedido e `caminho` diz de onde veio a tradução ("online", "offline", "misto" ou
 * "cache"). `modelo` é o que o nosso serviço respondeu ("http 503", "falhou" ou o nome do modelo), nulo se ele não foi
 * chamado, e `msModelo` o tempo até a resposta dele, -1 se ela não veio dentro da espera. `compartilhadas` conta as falas
 * que vieram do trabalho de outro pedido e `idServidor` é o id aleatório que o serviço põe na resposta, sem relação com o
 * texto; os dois podem ir para a telemetria, o texto não.
 */
class ResultadoLote(
    val mapa: Map<String, String>, val puladas: Set<String>, val und: Int, val origem: String,
    val caminho: String, val modelo: String?, val msModelo: Long, val online: Int, val offline: Int,
    val compartilhadas: Int, val idServidor: String?
)

/**
 * Normaliza uma fala para chave de cache e de detecção de idioma: minúsculas, e toda sequência de caracteres que não é
 * letra nem dígito vira UM espaço, sem espaço nas pontas. Antes se tirava espaço e pontuação, e "a nice" e "an ice"
 * viravam a mesma chave. O que o OCR varia de um quadro para outro (maiúscula, pontuação, espaço repetido) continua igual:
 * "Hey,   you!" e "hey you". "I can't!" ("i can t") e "I cant" ("i cant") deixam de ser iguais, o que é aceitável.
 */
internal fun normalizarFala(s: String): String {
    val baixa = s.lowercase()
    val sb = StringBuilder(baixa.length)
    var separador = false
    var i = 0
    while (i < baixa.length) {
        val cp = baixa.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.isLetterOrDigit(cp)) {
            if (separador && sb.isNotEmpty()) sb.append(' ')
            sb.appendCodePoint(cp)
            separador = false
        } else separador = true
    }
    return sb.toString()
}

/**
 * O coordenador de pedidos em andamento, sem rede nem Android: consulta o cache e reserva a chave na MESMA seção curta,
 * e conclui (grava, completa o futuro e o tira do mapa) na mesma trava. Sem isso, entre a consulta ao cache e o registro
 * o dono da fala podia terminar e sair do mapa, e o segundo pedido registrava de novo e pedia à rede outra vez. Dentro
 * da trava só há cache e mapa em memória; a rede roda fora.
 */
internal object Coordenacao {
    private val trava = Any()

    /**
     * Resultado de consultar o cache e, se faltar, reservar a chave: `pronta` é a entrada que já estava no cache; senão
     * `futuro` é o da chave, e `minha` diz se fui eu que o registrei (então eu peço) ou se é de outro pedido (então espero).
     */
    class Reserva<E : Any>(val pronta: E?, val futuro: CompletableFuture<E?>?, val minha: Boolean)

    fun <E : Any> consultarOuReservar(
        chave: String, cache: (String) -> E?, mapa: ConcurrentHashMap<String, CompletableFuture<E?>>
    ): Reserva<E> = synchronized(trava) {
        val pronta = cache(chave)
        if (pronta != null) return@synchronized Reserva(pronta, null, false)
        val novo = CompletableFuture<E?>()
        val existente = mapa.putIfAbsent(chave, novo)
        if (existente != null) Reserva(null, existente, false) else Reserva(null, novo, true)
    }

    /**
     * Decide a fala: `gravar` escreve no cache e devolve a entrada que ficou de fato (ou null na falha); o futuro é
     * completado com ela e removido do mapa. A remoção é `remove(chave, futuro)`, que só tira o MESMO futuro: um pedido
     * atrasado não leva embora o registro de um mais novo. Completar com null libera a fala para nova tentativa, e quem
     * esperava cai no caminho dele. Completar de novo um futuro já completo não faz nada.
     */
    fun <E : Any> concluir(
        chave: String, futuro: CompletableFuture<E?>, mapa: ConcurrentHashMap<String, CompletableFuture<E?>>, gravar: () -> E?
    ): E? = synchronized(trava) {
        var entrada: E? = null
        try { entrada = gravar() } finally { futuro.complete(entrada); mapa.remove(chave, futuro) }
        entrada
    }

    /** Prefixo da chave de recuperação: a de cada fala é reservada à parte, na mesma tabela do coordenador. */
    const val PREFIXO_RECUPERACAO = "rec|"

    /**
     * O que a recuperação devolveu: as entradas que este consumidor só reaproveitou (achadas no cache ou obtidas de quem
     * recuperou primeiro) e as chaves que ele mesmo recuperou.
     */
    class Recuperado<E : Any>(val reaproveitadas: Map<String, E>, val minhas: List<String>)

    /**
     * A recuperação de falas cujo futuro alheio falhou ou não veio a tempo, com as guardas que evitam gasto à toa:
     *  1. confere o tempo que resta (RegraRecuperacao.plano): sem tempo nada é reservado nem pedido; com menos de
     *     RegraRecuperacao.REDE_MIN_MS só o offline; senão rede e offline;
     *  2. consulta de novo o cache, porque outro pedido pode ter preenchido, na mesma trava da reserva;
     *  3. reserva "rec|" + chave com putIfAbsent: UM consumidor recupera cada fala. Quem não consegue a reserva espera o
     *     dono (`esperarAlheio`, no prazo de quem espera) e reaproveita o que ele achou, em vez de repetir a recuperação;
     *  4. o dono tenta (`tentar`, com os futuros "rec|" dele, que a tentativa completa fala a fala), e ao fim o que sobrar
     *     é concluído com o que está no cache, ou com null, o que libera a chave para uma nova tentativa depois.
     * `tentar` nunca recebe o modelo de volta: a recuperação é só máquina e offline.
     */
    fun <E : Any> recuperar(
        chaves: List<String>, mapa: ConcurrentHashMap<String, CompletableFuture<E?>>, cache: (String) -> E?,
        restanteMs: () -> Long, esperarAlheio: (CompletableFuture<E?>) -> E?,
        tentar: (minhas: List<String>, futuros: List<CompletableFuture<E?>>, plano: RegraRecuperacao.Plano) -> Unit
    ): Recuperado<E> {
        val reaproveitadas = LinkedHashMap<String, E>()
        val plano = RegraRecuperacao.plano(restanteMs())
        if (plano == RegraRecuperacao.Plano.NADA) {
            // sem tempo não se reserva nem se pede nada; o cache, que é de graça, ainda vale uma olhada
            for (k in chaves) cache(k)?.let { reaproveitadas[k] = it }
            return Recuperado(reaproveitadas, emptyList())
        }
        val minhas = ArrayList<String>()
        val meusFuturos = ArrayList<CompletableFuture<E?>>()
        val alheios = LinkedHashMap<String, CompletableFuture<E?>>()
        for (k in chaves) {
            // a chave da recuperação é própria: o futuro da chave original já saiu do mapa com a falha
            val r = consultarOuReservar(PREFIXO_RECUPERACAO + k, { cache(k) }, mapa)
            val pronta = r.pronta
            when {
                pronta != null -> reaproveitadas[k] = pronta
                r.minha -> { minhas += k; meusFuturos += r.futuro!! }
                else -> alheios[k] = r.futuro!!
            }
        }
        try {
            if (minhas.isNotEmpty()) tentar(minhas, meusFuturos, plano)
        } finally {
            for (i in minhas.indices) concluir(PREFIXO_RECUPERACAO + minhas[i], meusFuturos[i], mapa) { cache(minhas[i]) }
        }
        for ((k, f) in alheios) esperarAlheio(f)?.let { reaproveitadas[k] = it }
        return Recuperado(reaproveitadas, minhas)
    }

    /**
     * Espera o futuro de outro pedido por NO MÁXIMO `ateMs` (o que resta do prazo de quem espera), saindo antes se `ativo()`
     * virar falso. Null em falha, em prazo vencido ou em desistência.
     */
    fun <E : Any> esperar(futuro: CompletableFuture<E?>, ateMs: Long, ativo: () -> Boolean = { true }): E? =
        esperarFuturo(futuro, ateMs, ativo)

    /**
     * Espera um futuro até `ateMs`, em passos de até 50 ms, para poder sair antes quando `ativo()` virar falso. Só PARA de
     * esperar: o futuro NUNCA é cancelado, porque é compartilhado com outros pedidos. Por isso não se usa
     * CompletableFuture.await(), que cancela o futuro quando a corrotina de quem espera é cancelada, nem orTimeout, que o
     * completaria com erro. O get com prazo só lança TimeoutException e deixa o futuro como está.
     */
    fun <T> esperarFuturo(f: java.util.concurrent.Future<T>, ateMs: Long, ativo: () -> Boolean = { true }): T? {
        val fim = System.nanoTime() + maxOf(0L, ateMs) * 1_000_000L
        while (true) {
            if (f.isDone) return runCatching { f.get() }.getOrNull()
            val resta = (fim - System.nanoTime()) / 1_000_000L
            if (resta <= 0 || !ativo()) return null
            try { return f.get(minOf(resta, 50L), TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { /* segue esperando, em passo curto */ }
            catch (_: Exception) { return null }
        }
    }
}

/**
 * O que a recuperação pode usar, conforme o tempo que resta até o prazo final. A rede só vale com folga: uma resposta que
 * chega depois do prazo é descartada, e mesmo assim gasta a cota do MyMemory.
 */
internal object RegraRecuperacao {
    /** Com menos que isto até o prazo final, nada de rede; sobra o offline, se o pacote já estiver pronto. */
    const val REDE_MIN_MS = 800L

    /** NADA: sem tempo. SO_OFFLINE: menos de REDE_MIN_MS. REDE: máquina e offline. */
    enum class Plano { NADA, SO_OFFLINE, REDE }

    fun plano(restanteMs: Long): Plano = when {
        restanteMs <= 0 -> Plano.NADA
        restanteMs < REDE_MIN_MS -> Plano.SO_OFFLINE
        else -> Plano.REDE
    }
}

/**
 * O acompanhamento, pelo próprio pedido, das falas que OUTRO pedido traduz. Sem Android nem rede, com relógio e sono
 * injetáveis, para rodar na JVM com CompletableFuture. As regras:
 *  - roda JUNTO com as próprias falas do pedido, e não depois delas (quem chama o põe numa thread de apoio);
 *  - sonda os futuros com isDone, a cada passo, e nunca os cancela: só PARA de esperar;
 *  - termina de esperar no máximo em `limite`, que é prazoFinal menos a janela de recuperação;
 *  - o futuro que termina com null (falha) é entregue NA HORA a `aoTerminar`, sem esperar os demais;
 *  - no `limite`, o que ainda não terminou é entregue a `aoVencer`, para ser recuperado com o tempo que sobra até prazoFinal;
 *  - depois disso só se espera as recuperações em andamento (`recuperando`), e nunca além de prazoFinal.
 */
internal object Acompanhamento {
    class Item<E : Any>(val texto: String, val chave: String, val futuro: CompletableFuture<E?>)

    /** Uma fala alheia que terminou: com a entrada, ou com null (falha, ou futuro completado com erro). */
    class Terminou<E : Any>(val item: Item<E>, val entrada: E?)

    /** Quem ainda não terminou. Sondar não espera nada e não cancela nada. */
    class Alheias<E : Any>(itens: List<Item<E>>) {
        private val pendentes = ArrayList(itens)
        val restantes: Int get() = pendentes.size

        /** Os que terminaram desde a última sondagem, na ordem da lista, com a entrada ou com null. */
        fun sondar(): List<Terminou<E>> {
            val prontos = ArrayList<Terminou<E>>()
            val it = pendentes.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (!item.futuro.isDone) continue
                it.remove()
                prontos += Terminou(item, runCatching { item.futuro.get() }.getOrNull())
            }
            return prontos
        }

        /** Os que ainda não terminaram quando o limite chegou: saem da espera e quem chama os recupera. */
        fun vencidos(): List<Item<E>> = ArrayList(pendentes).also { pendentes.clear() }
    }

    fun <E : Any> rodar(
        alheias: Alheias<E>, limite: Long, prazoFinal: Long, agora: () -> Long, ativo: () -> Boolean,
        aoTerminar: (List<Terminou<E>>) -> Unit, aoVencer: (List<Item<E>>) -> Unit,
        recuperando: () -> Boolean, dorme: (Long) -> Unit
    ) {
        while (ativo()) {
            val t = agora()
            val prontos = alheias.sondar()
            if (prontos.isNotEmpty()) aoTerminar(prontos)
            if (t >= limite && alheias.restantes > 0) aoVencer(alheias.vencidos())
            if (alheias.restantes == 0 && !recuperando()) return
            if (t >= prazoFinal) return
            dorme(RegraEspera.passoMs(t, prazoFinal))
        }
    }
}

/** Regras do cache em disco do leitor, puras para rodar na JVM. */
internal object RegraLeitor {
    /** O quadro pintado leva o idioma de destino no nome: trocar o destino não traz de volta o quadro no idioma errado. */
    fun nomePintado(indice: Int, destino: String) = "${indice}t_$destino.jpg"

    /**
     * O quadro que está pronto (ou sem texto) foi decidido para OUTRO destino? Então volta para a fila e é repintado.
     * Com o original na tela não há o que refazer, e quadro sem etiqueta de destino (nunca traduzido) não conta.
     */
    fun defasado(pronto: Boolean, semTexto: Boolean, paraDestino: String?, destino: String, mostrarOriginal: Boolean) =
        !mostrarOriginal && (pronto || semTexto) && paraDestino != null && paraDestino != destino
}

/** O que da telemetria vem de resposta ou de texto vira código fixo: fala e conteúdo de resposta nunca saem do aparelho. */
internal object SaneaTelemetria {
    private val NOME_MODELO = Regex("^[A-Za-z0-9._:/-]{1,60}$")
    private val ID_SERVIDOR = Regex("^[A-Za-z0-9_-]{4,40}$")

    /** Nome de modelo só se parecer nome de modelo (letras, dígitos e . _ : / -); texto livre vira "?". */
    fun modelo(s: String): String = if (NOME_MODELO.matches(s)) s else "?"

    /** O id aleatório do serviço só passa se tiver a cara de id (letras, dígitos, _ e -); qualquer outra coisa vira null. */
    fun idServidor(s: String): String? = if (ID_SERVIDOR.matches(s)) s else null

    /** De onde veio a tradução do pedido: só online, só offline, os dois, ou nada pedido (cache ou compartilhada). */
    fun caminho(online: Int, offline: Int): String = when {
        online > 0 && offline == 0 -> "online"
        online == 0 && offline > 0 -> "offline"
        online > 0 -> "misto"
        else -> "cache"
    }

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
