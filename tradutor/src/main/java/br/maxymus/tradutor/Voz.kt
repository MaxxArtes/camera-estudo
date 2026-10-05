package br.maxymus.tradutor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat

/**
 * As regras da leitura em voz alta (Tradutor 0.27), sem Android, para rodar na JVM do harness. A leitura só existe para o
 * destino português e só lê as falas TRADUZIDAS que estão pintadas na tela congelada.
 */
internal object RegraVoz {
    /** Os códigos de `ouvir_terminou`. Nenhum outro valor sai na telemetria: o que não está aqui vira ERRO. */
    const val FIM = "fim"
    const val PARAR = "parar"
    const val TOQUE_BOLHA = "toque_bolha"
    const val TELA_FECHADA = "tela_fechada"
    const val FOCO_AUDIO = "foco_audio"
    const val SAIDA_AUDIO = "saida_audio"
    const val TELA_DESLIGADA = "tela_desligada"
    const val ERRO = "erro"
    val MOTIVOS_FIM: Set<String> = setOf(FIM, PARAR, TOQUE_BOLHA, TELA_FECHADA, FOCO_AUDIO, SAIDA_AUDIO, TELA_DESLIGADA, ERRO)

    /** Os códigos de `ouvir_indisponivel`. */
    const val SEM_VOZ = "sem_voz"
    const val IDIOMA_DESTINO = "idioma_destino"
    const val SEM_FALAS = "sem_falas"
    val MOTIVOS_INDISPONIVEL: Set<String> = setOf(SEM_VOZ, IDIOMA_DESTINO, SEM_FALAS)

    /** De onde veio o pedido de leitura: do menu da bolha ou do botão da tela congelada. */
    const val BOLHA = "bolha"
    const val CONGELADA = "congelada"

    /** A leitura é só em português nesta versão: a voz escolhida é pt-BR e o texto lido é o traduzido para o destino. */
    fun destinoServe(destino: String) = destino == "pt"

    /** Tolerância vertical de uma faixa de leitura, em múltiplos da altura mediana das linhas. */
    const val TOLERANCIA_FAIXA = 0.6

    /** Uma fala na tela, só com o que a ordem de leitura precisa: o canto de cima à esquerda da caixa e a altura de uma linha. */
    class PosicaoFala(val esquerda: Int, val topo: Int, val alturaLinha: Int)

    /**
     * A ordem de leitura, em índices da lista recebida: faixas de cima para baixo e, dentro da faixa, da esquerda para a
     * direita. Uma faixa começa na fala mais alta ainda não lida e leva todas as que estão a no máximo TOLERANCIA_FAIXA vezes a
     * altura MEDIANA das linhas abaixo dela (inclusive), então uma linha levemente desalinhada continua na mesma faixa e a
     * tolerância acompanha o tamanho do texto. Ordenar só por coordenada exata inverte falas lado a lado. Empate fica na ordem
     * da lista. É uma aproximação para mangá e página de composição complexa, e não repete nem descarta fala nenhuma.
     */
    fun ordemDeLeitura(falas: List<PosicaoFala>): List<Int> {
        if (falas.size < 2) return falas.indices.toList()
        val alturas = falas.map { it.alturaLinha }.sorted()
        val meio = alturas.size / 2
        val mediana = if (alturas.size % 2 == 1) alturas[meio].toDouble() else (alturas[meio - 1] + alturas[meio]) / 2.0
        val tolerancia = TOLERANCIA_FAIXA * mediana
        val porTopo = falas.indices.sortedWith(compareBy({ falas[it].topo }, { falas[it].esquerda }, { it }))
        val saida = ArrayList<Int>(falas.size)
        val faixa = ArrayList<Int>()
        var ancora = 0
        fun fecha() {
            saida += faixa.sortedWith(compareBy({ falas[it].esquerda }, { falas[it].topo }, { it }))
            faixa.clear()
        }
        for (i in porTopo) {
            if (faixa.isNotEmpty() && falas[i].topo - ancora > tolerancia) fecha()
            if (faixa.isEmpty()) ancora = falas[i].topo
            faixa += i
        }
        fecha()
        return saida
    }

    /**
     * Parte o texto em pedaços de no máximo `maximo` caracteres (o limite do motor, TextToSpeech.getMaxSpeechInputLength), sem
     * perder nada e na ordem. Corta no fim da última frase que cabe, senão no último espaço, senão a seco sem partir um par
     * substituto. Texto em branco não gera pedaço nenhum; os pedaços saem aparados.
     */
    fun dividir(texto: String, maximo: Int): List<String> {
        val limpo = texto.trim()
        if (limpo.isEmpty()) return emptyList()
        if (maximo <= 0 || limpo.length <= maximo) return listOf(limpo)
        val pecas = ArrayList<String>()
        var resto = limpo
        while (resto.length > maximo) {
            val corte = corteAte(resto, maximo)
            val peca = resto.substring(0, corte).trim()
            if (peca.isNotEmpty()) pecas += peca
            resto = resto.substring(corte).trim()
        }
        if (resto.isNotEmpty()) pecas += resto
        return pecas
    }

    /** Onde cortar `s` (mais comprido que `max`): logo depois da última pontuação final de frase, ou do último espaço, ou em `max`. */
    private fun corteAte(s: String, max: Int): Int {
        var fimDeFrase = -1
        var espaco = -1
        for (i in 0 until max) {
            val c = s[i]
            if ((c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') && (i + 1 >= s.length || s[i + 1].isWhitespace())) fimDeFrase = i + 1
            else if (c.isWhitespace()) espaco = i + 1
        }
        if (fimDeFrase > 0) return fimDeFrase
        if (espaco > 0) return espaco
        return if (max > 1 && Character.isHighSurrogate(s[max - 1])) max - 1 else max
    }

    /** O id de uma fala no motor: leva a GERAÇÃO da leitura, e callback de geração velha é ignorado. */
    fun idFala(geracao: Int, peca: Int) = "tr:$geracao:$peca"

    fun geracaoDoId(id: String?): Int? = id?.split(':')?.takeIf { it.size == 3 && it[0] == "tr" }?.get(1)?.toIntOrNull()

    /** Uma voz instalada, só com o que a escolha precisa (a Voice do Android não existe na JVM). */
    class VozInfo(
        val nome: String, val idioma: String, val pais: String, val precisaRede: Boolean,
        val naoInstalada: Boolean, val qualidade: Int, val latencia: Int
    )

    fun ehPtBR(idioma: String, pais: String): Boolean =
        (idioma.equals("pt", true) || idioma.equals("por", true)) && (pais.equals("BR", true) || pais.equals("BRA", true))

    /**
     * A voz da leitura: pt-BR, INSTALADA e que não precise de internet. Nenhuma alternativa automática (nem pt-PT, nem voz de
     * rede). Entre as que servem, a de maior qualidade, depois a de menor latência, depois a de menor nome (determinístico).
     * Devolve o índice na lista, ou nulo quando não há voz que sirva.
     */
    fun escolherVoz(vozes: List<VozInfo>): Int? =
        vozes.indices.filter { ehPtBR(vozes[it].idioma, vozes[it].pais) && !vozes[it].precisaRede && !vozes[it].naoInstalada }
            .minWithOrNull(compareBy({ -vozes[it].qualidade }, { vozes[it].latencia }, { vozes[it].nome }))
}

/**
 * Uma sessão de leitura, sem Android: a fila de pedaços, a contagem das falas lidas e a telemetria. A fila é a CÓPIA do
 * momento em que a leitura começou (os pedaços são montados no construtor): correção tardia de tradução não a muda. Cada
 * fala vira um ou mais pedaços (texto acima do limite do motor é dividido) e o motor fala UM pedaço por vez. Os ids levam a
 * geração, e quem chama ignora o que não for desta sessão. `ouvir_iniciou` sai uma vez, quando a primeira fala COMEÇA, e
 * `ouvir_terminou` uma vez, no fim; ambos só com números e códigos fixos, nunca texto, URL, nome de app nem hash.
 */
internal class SessaoLeitura(
    falas: List<String>, maxEntrada: Int, onde: String, val geracao: Int,
    private val registra: (tipo: String, campos: Map<String, Any?>) -> Unit
) {
    class Peca(val texto: String, val ultimaDaFala: Boolean)

    val pecas: List<Peca>
    val totalFalas: Int
    private val onde: String = if (onde == RegraVoz.BOLHA || onde == RegraVoz.CONGELADA) onde else RegraVoz.CONGELADA
    var proxima = 0
        private set
    var lidas = 0
        private set
    private var iniciou = false
    var encerrada = false
        private set

    init {
        val montadas = ArrayList<Peca>()
        var n = 0
        for (f in falas.toList()) {
            val partes = RegraVoz.dividir(f, maxEntrada)
            if (partes.isEmpty()) continue
            n++
            partes.forEachIndexed { i, p -> montadas += Peca(p, i == partes.lastIndex) }
        }
        pecas = montadas
        totalFalas = n
    }

    fun pecaAtual(): Peca? = if (encerrada) null else pecas.getOrNull(proxima)

    /** O id do pedaço que o motor deve estar falando agora. */
    fun idAtual() = RegraVoz.idFala(geracao, proxima)

    private fun marcaInicio() {
        if (iniciou) return
        iniciou = true
        registra("ouvir_iniciou", mapOf("falas" to totalFalas, "onde" to onde))
    }

    /** O motor começou a falar o pedaço `id`. Só vale para o pedaço atual desta sessão; a primeira vez registra ouvir_iniciou. */
    fun aoComecar(id: String?) {
        if (encerrada || id != idAtual()) return
        marcaInicio()
    }

    /** O motor terminou o pedaço `id`. Devolve se há outro pedaço para falar; id que não é o atual desta sessão é ignorado. */
    fun aoTerminar(id: String?): Boolean {
        if (encerrada || id != idAtual()) return false
        if (pecas[proxima].ultimaDaFala) lidas++
        proxima++
        return proxima < pecas.size
    }

    /** Fecha a sessão, uma vez só, com um dos códigos de RegraVoz.MOTIVOS_FIM (qualquer outro vira ERRO). */
    fun encerrar(motivo: String) {
        if (encerrada) return
        encerrada = true
        registra("ouvir_terminou", mapOf("motivo" to (if (motivo in RegraVoz.MOTIVOS_FIM) motivo else RegraVoz.ERRO), "lidas" to lidas))
    }
}

/**
 * Leitura em voz alta com o TextToSpeech do SISTEMA (Tradutor 0.27). O texto vai para o motor de voz que o Android tem
 * configurado, e a API não garante que esse motor não use a rede; por isso só se aceita voz pt-BR declarada como não
 * dependente de internet (isNetworkConnectionRequired == false) e instalada, escolhida EXPLICITAMENTE, e a primeira leitura
 * avisa o que isso quer dizer. Não há voz alternativa automática.
 *
 *  - antes de cada leitura confere que o motor padrão e a voz continuam os mesmos; se a voz mudou, escolhe de novo, e sem voz
 *    que sirva pede ao serviço a caixa "Instalar voz" (`semVoz`) e NÃO lê sozinho quando o dono volta;
 *  - áudio: conteúdo de fala, foco transitório que permite baixar o dos outros (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK). Foco
 *    negado, não começa. QUALQUER perda de foco, inclusive a transitória, para a leitura e não retoma sozinha. O foco é
 *    liberado ao parar ou terminar. Volume e saída de áudio nunca são tocados;
 *  - fone: ACTION_AUDIO_BECOMING_NOISY para a leitura, e ACTION_SCREEN_OFF também;
 *  - UM pedaço por vez. "Parar" sobe a geração, e callback de geração velha é ignorado;
 *  - tudo roda na thread principal; os callbacks do motor são postados para ela.
 */
internal class Voz(
    private val contexto: Context,
    private val avisa: (texto: String, ms: Long) -> Unit,
    private val semVoz: () -> Unit,
    private val aoMudar: () -> Unit
) {
    private val principal = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var pronto = false
    private var motor: String? = null
    private var nomeDaVoz: String? = null
    private var geracao = 0
    private var preparando = false
    private var sessao: SessaoLeitura? = null
    private var pedidoFoco: AudioFocusRequest? = null
    private var receptor: BroadcastReceiver? = null

    /** Preparando a voz ou falando: é o que o botão ("Parar") e a bolha precisam saber. */
    val lendo: Boolean get() = preparando || sessao != null

    /**
     * Pede a leitura de `textos` (já na ordem de leitura): a lista é COPIADA agora, e o que mudar depois não entra na fila em
     * andamento. `onde` é "bolha" ou "congelada". Nada acontece se já há leitura.
     */
    fun ouvir(textos: List<String>, onde: String) {
        if (lendo) return
        if (!destinoPermite()) return
        val copia = textos.filter { it.isNotBlank() }
        if (copia.isEmpty()) { semFalas(); return }
        preparando = true
        val minha = ++geracao
        // os motivos de parada também valem enquanto o motor prepara
        if (!registraReceptores()) { parar(RegraVoz.ERRO); return }
        aoMudar()
        garantirMotor { ok ->
            if (geracao != minha || !preparando) return@garantirMotor      // pararam enquanto a voz preparava
            if (!ok) { preparando = false; tiraReceptores(); falhaSemVoz(); aoMudar(); return@garantirMotor }
            comeca(copia, onde, minha)
        }
    }

    /** O destino não é português: aviso e `ouvir_indisponivel`. Devolve se a leitura pode seguir. */
    fun destinoPermite(): Boolean {
        if (RegraVoz.destinoServe(Traducao.destino)) return true
        Telemetria.evento("ouvir_indisponivel", mapOf("motivo" to RegraVoz.IDIOMA_DESTINO))
        avisa("A leitura em voz alta está disponível para traduções em português.", 3_000L)
        return false
    }

    /** Não há fala traduzida para ouvir: aviso e `ouvir_indisponivel`. */
    fun semFalas() {
        Telemetria.evento("ouvir_indisponivel", mapOf("motivo" to RegraVoz.SEM_FALAS))
        avisa("Não há falas traduzidas para ouvir nesta tela.", 3_000L)
    }

    /** Para a leitura (ou a preparação dela) com o motivo dado. Sem leitura, não faz nada. */
    fun parar(motivo: String) {
        val s = sessao
        if (s == null) {
            tiraReceptores()
            if (preparando) {
                preparando = false; geracao++
                Telemetria.evento("ouvir_terminou", mapOf("motivo" to (if (motivo in RegraVoz.MOTIVOS_FIM) motivo else RegraVoz.ERRO), "lidas" to 0))
                aoMudar()
            }
            return
        }
        encerra(s, motivo)
    }

    /** Fim do serviço: para a leitura e desliga o motor de voz (shutdown). */
    fun desligar() {
        parar(RegraVoz.TELA_FECHADA)
        preparando = false
        geracao++
        desligaMotor()
    }

    // ---------------- o motor de voz ----------------

    private fun garantirMotor(depois: (Boolean) -> Unit) {
        val atual = tts
        if (atual != null && pronto) {
            // o mesmo motor e a mesma voz de antes? A voz mudou: escolhe de novo. O motor padrão mudou: recria
            val mesmoMotor = runCatching { atual.defaultEngine }.getOrNull() == motor
            if (mesmoMotor && (vozContinua(atual) || escolheVoz(atual))) { depois(true); return }
            if (mesmoMotor) { depois(false); return }
        }
        criaMotor(depois)
    }

    private fun criaMotor(depois: (Boolean) -> Unit) {
        desligaMotor()
        var respondeu = false
        var criado: TextToSpeech? = null
        val novo = runCatching {
            TextToSpeech(contexto.applicationContext) { status ->
                // o motor avisa em thread própria (e às vezes de dentro do construtor): tudo segue na principal
                principal.post {
                    val t = criado
                    if (t != null && !respondeu && tts === t) {
                        respondeu = true
                        val ok = status == TextToSpeech.SUCCESS && configura(t)
                        if (!ok) desligaMotor()
                        depois(ok)
                    }
                }
            }
        }.getOrNull()
        if (novo == null) { depois(false); return }      // o sistema recusou criar o motor: como se não houvesse voz
        criado = novo
        tts = novo
        // motor que nunca responde não pode deixar o dono esperando: sem resposta em 6 s é como se não houvesse voz
        principal.postDelayed({
            if (!respondeu && tts === novo) { respondeu = true; desligaMotor(); depois(false) }
        }, 6_000L)
    }

    private fun desligaMotor() {
        val t = tts
        tts = null; pronto = false; motor = null; nomeDaVoz = null
        if (t != null) runCatching { t.shutdown() }
    }

    private fun configura(t: TextToSpeech): Boolean {
        motor = runCatching { t.defaultEngine }.getOrNull()
        if (!escolheVoz(t)) return false
        runCatching { t.setAudioAttributes(atributosDeFala()) }
        if (t.setOnUtteranceProgressListener(ouvinte) != TextToSpeech.SUCCESS) return false
        pronto = true
        return true
    }

    /** Escolhe a voz pt-BR instalada e sem internet (RegraVoz.escolherVoz), põe no motor e confere o retorno de setVoice. */
    private fun escolheVoz(t: TextToSpeech): Boolean {
        val todas = runCatching { t.voices?.toList() }.getOrNull().orEmpty()
        val infos = todas.map { v ->
            RegraVoz.VozInfo(v.name, v.locale?.language.orEmpty(), v.locale?.country.orEmpty(), v.isNetworkConnectionRequired,
                v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true, v.quality, v.latency)
        }
        val v = RegraVoz.escolherVoz(infos)?.let { todas[it] } ?: return false
        if (runCatching { t.setVoice(v) }.getOrDefault(TextToSpeech.ERROR) != TextToSpeech.SUCCESS) return false
        if (!vozContinua(t, v.name)) return false
        nomeDaVoz = v.name
        return true
    }

    /** A voz do motor ainda é a escolhida (`nome`, ou a gravada) e não precisa de internet? */
    private fun vozContinua(t: TextToSpeech, nome: String? = nomeDaVoz): Boolean {
        val v = runCatching { t.voice }.getOrNull() ?: return false
        return nome != null && v.name == nome && !v.isNetworkConnectionRequired
    }

    private fun falhaSemVoz() {
        Telemetria.evento("ouvir_indisponivel", mapOf("motivo" to RegraVoz.SEM_VOZ))
        // a próxima tentativa consulta o motor de novo: a voz pode ter sido instalada nesse meio tempo
        desligaMotor()
        semVoz()
    }

    private fun atributosDeFala(): AudioAttributes =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    // ---------------- a leitura ----------------

    private fun comeca(textos: List<String>, onde: String, minha: Int) {
        // o limite do motor vale como ele informa; só um valor sem sentido (zero ou negativo) cai no padrão
        val maximo = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(MAX_ENTRADA_PADRAO).takeIf { it > 0 } ?: MAX_ENTRADA_PADRAO
        if (geracao != minha) { preparando = false; tiraReceptores(); return }
        val s = SessaoLeitura(textos, maximo, onde, minha) { tipo, campos -> Telemetria.evento(tipo, campos) }
        if (s.pecas.isEmpty()) { preparando = false; tiraReceptores(); semFalas(); aoMudar(); return }
        if (!pedeFoco()) {
            preparando = false
            // foco negado: não começa. A sessão existiu, então o fim é registrado, sem nunca ter havido início
            tiraReceptores()
            s.encerrar(RegraVoz.FOCO_AUDIO)
            avisa("Não foi possível começar a leitura agora. Outro aplicativo está usando o áudio.", 3_000L)
            aoMudar()
            return
        }
        sessao = s
        preparando = false
        primeiroAviso()
        aoMudar()
        fala(s)
    }

    private fun fala(s: SessaoLeitura) {
        val t = tts
        val peca = s.pecaAtual()
        if (peca == null) { encerra(s, RegraVoz.FIM); return }
        if (t == null) { encerra(s, RegraVoz.ERRO); return }
        val r = runCatching { t.speak(peca.texto, TextToSpeech.QUEUE_ADD, null, s.idAtual()) }.getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) encerra(s, RegraVoz.ERRO)
    }

    private fun encerra(s: SessaoLeitura, motivo: String) {
        if (sessao === s) sessao = null
        geracao++
        runCatching { tts?.stop() }
        soltaFoco()
        tiraReceptores()
        s.encerrar(motivo)
        if (motivo == RegraVoz.ERRO) avisa("Não consegui ler em voz alta agora.", 3_000L)
        aoMudar()
    }

    /** Só vale callback da geração atual e da sessão que está de pé; o resto é de leitura que já parou. */
    private fun sessaoDoId(id: String?): SessaoLeitura? {
        val s = sessao ?: return null
        return if (RegraVoz.geracaoDoId(id) == geracao && s.geracao == geracao) s else null
    }

    private val ouvinte = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { principal.post { sessaoDoId(utteranceId)?.aoComecar(utteranceId) } }
        override fun onDone(utteranceId: String?) {
            principal.post {
                val s = sessaoDoId(utteranceId) ?: return@post
                if (s.aoTerminar(utteranceId)) fala(s) else if (s.pecaAtual() == null) encerra(s, RegraVoz.FIM)
            }
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) { principal.post { sessaoDoId(utteranceId)?.let { encerra(it, RegraVoz.ERRO) } } }
        override fun onError(utteranceId: String?, errorCode: Int) { principal.post { sessaoDoId(utteranceId)?.let { encerra(it, RegraVoz.ERRO) } } }
    }

    // ---------------- foco de áudio, fone e tela desligada ----------------

    private val ouvinteFoco = AudioManager.OnAudioFocusChangeListener { mudanca ->
        // qualquer perda, até a transitória, para e não retoma sozinha; o ganho depois de uma perda é ignorado
        if (mudanca == AudioManager.AUDIOFOCUS_LOSS || mudanca == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            mudanca == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) parar(RegraVoz.FOCO_AUDIO)
    }

    private fun pedeFoco(): Boolean {
        val am = contexto.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val pedido = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(atributosDeFala())
            .setOnAudioFocusChangeListener(ouvinteFoco, principal)
            .build()
        if (runCatching { am.requestAudioFocus(pedido) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        pedidoFoco = pedido
        return true
    }

    private fun soltaFoco() {
        val pedido = pedidoFoco ?: return
        pedidoFoco = null
        val am = contexto.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.abandonAudioFocusRequest(pedido) }
    }

    private fun registraReceptores(): Boolean {
        if (receptor != null) return true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY -> parar(RegraVoz.SAIDA_AUDIO)
                    Intent.ACTION_SCREEN_OFF -> parar(RegraVoz.TELA_DESLIGADA)
                }
            }
        }
        val filtro = IntentFilter().apply { addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY); addAction(Intent.ACTION_SCREEN_OFF) }
        if (runCatching { ContextCompat.registerReceiver(contexto, r, filtro, ContextCompat.RECEIVER_NOT_EXPORTED) }.isFailure) return false
        receptor = r
        return true
    }

    private fun tiraReceptores() {
        receptor?.let { runCatching { contexto.unregisterReceiver(it) } }
        receptor = null
    }

    /** Na PRIMEIRA leitura (a marca fica em SharedPreferences) diz uma vez o que a voz do sistema quer dizer para o texto. */
    private fun primeiroAviso() {
        val p = contexto.getSharedPreferences("tradutor", Context.MODE_PRIVATE)
        if (p.getBoolean(MARCA_AVISO_VOZ, false)) return
        p.edit().putBoolean(MARCA_AVISO_VOZ, true).apply()
        avisa("A leitura usa uma voz instalada no aparelho. O processamento é feito pelo mecanismo de voz configurado no Android.", 7_000L)
    }

    private companion object {
        const val MARCA_AVISO_VOZ = "aviso_voz_visto"
        /** o limite do motor costuma ser 4000; este é o valor de reserva */
        const val MAX_ENTRADA_PADRAO = 4000
    }
}
