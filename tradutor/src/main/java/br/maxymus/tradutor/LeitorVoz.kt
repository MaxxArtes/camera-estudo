package br.maxymus.tradutor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A leitura em voz alta no leitor de capítulo (Tradutor 0.28). A voz é a mesma da bolha (Voz.kt, em leitura contínua) e as
 * regras de decisão estão em RegraLeituraCapitulo; aqui fica só a costura com a lista na tela.
 *
 * A voz pede uma fala por vez (`pede`), e é aqui que se decide qual: a próxima do quadro da vez ou, acabado o quadro, a
 * primeira do seguinte, esperando o preparo dele. Uma fala por vez é o que permite, em quadro alto, rolar até a fala ANTES
 * de ela começar. Tudo roda na thread principal.
 */
internal class LeituraNoLeitor(
    private val contexto: Context,
    private val leitor: Leitor,
    private val lista: LazyListState,
    private val escopo: CoroutineScope
) {
    /** Preparando ou lendo: as barras ficam à mostra e o botão é "Parar". */
    var ativa by mutableStateOf(false)
        private set
    /** O quadro que está sendo (ou vai ser) ouvido; é o que o "Quadro X de N" mostra durante a leitura. */
    var quadroDaVez by mutableStateOf(-1)
        private set
    /** A linha de aviso da barra de baixo; nula sem aviso. */
    var aviso by mutableStateOf<String?>(null)
        private set
    /** Sem voz pt-BR que sirva: a tela mostra a caixa "Instalar voz". */
    var pedirVoz by mutableStateOf(false)
    /** "Avançar automaticamente": desligado no início e vale só nesta abertura do leitor. */
    var avancar by mutableStateOf(false)

    /** A rolagem em andamento é do próprio app (ir até o próximo quadro ou a próxima fala), e essa não interrompe a leitura. */
    var rolandoPeloApp = false
        private set

    /** Alturas das barras, em pixels: a área útil para mostrar a fala é a que sobra entre elas. */
    var alturaCima by mutableStateOf(0)
    var alturaBaixo by mutableStateOf(0)

    private val voz = Voz(contexto, avisa = { t, ms -> avisar(t, ms) }, semVoz = { pedirVoz = true }, aoMudar = { aoMudarVoz() })
    private var servindo: Job? = null
    private var apagaAviso: Job? = null
    private var primeiro = true
    private var falaDaVez = -1
    /** As falas do quadro da vez, copiadas quando ele começa: tradução refeita no meio não muda a fila. */
    private var dadosDaVez: RegraLeituraCapitulo.DadosQuadro? = null

    /** "Ouvir": começa pelo quadro `inicial` (o de maior área visível). No original não há o que ouvir. */
    fun ouvir(inicial: Int?) {
        if (ativa || voz.lendo) return
        if (leitor.mostrarOriginal) { avisar(AVISO_ORIGINAL, 3_000L); return }
        if (inicial == null || inicial !in leitor.quadros.indices) return
        avisar(null)
        primeiro = true
        falaDaVez = -1
        dadosDaVez = null
        quadroDaVez = inicial
        ativa = true
        voz.ouvirContinuo(RegraVoz.LEITOR) { token -> servir(token) }
        if (!voz.lendo) encerrou()      // destino que não é português, ou a voz recusou de saída
    }

    /** "Parar". */
    fun parar() = interromper(RegraVoz.PARAR)

    /** Para a leitura com o motivo dado (rolagem, original, tela fechada...). Sem leitura, não faz nada. */
    fun interromper(motivo: String) {
        if (!ativa) return
        if (voz.lendo) voz.parar(motivo) else encerrou()
    }

    /** A tela saiu de vista: tela desligada (ou bloqueada) se o aparelho não está interativo, senão o dono saiu do leitor. */
    fun aoSairDeVista() {
        val ligada = (contexto.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        interromper(if (ligada) RegraVoz.TELA_FECHADA else RegraVoz.TELA_DESLIGADA)
    }

    /** Fim da tela: para a leitura (tela_fechada) e desliga o motor de voz. */
    fun desligar() {
        voz.desligar()
        encerrou()
    }

    /** A pedido da caixa "Instalar voz": só o pedido de instalação, nunca texto do capítulo. */
    fun instalarVoz() {
        pedirVoz = false
        try {
            contexto.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        } catch (_: ActivityNotFoundException) {
            try {
                contexto.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: ActivityNotFoundException) {
                runCatching { contexto.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            }
        }
    }

    private fun aoMudarVoz() {
        if (!voz.lendo && ativa) encerrou()
    }

    private fun encerrou() {
        servindo?.cancel(); servindo = null
        ativa = false
        rolandoPeloApp = false
        leitor.alvoLeitura = -1
        dadosDaVez = null
        if (aviso == AVISO_PREPARANDO || aviso == AVISO_PREPARANDO_PRIMEIRO) avisar(null)
    }

    private fun avisar(texto: String?, ms: Long = 0L) {
        apagaAviso?.cancel(); apagaAviso = null
        aviso = texto
        if (texto != null && ms > 0) apagaAviso = escopo.launch { delay(ms); if (aviso == texto) aviso = null }
    }

    /** A voz terminou a fala anterior (ou acabou de começar a sessão) e espera a próxima. */
    private fun servir(token: Int) {
        servindo?.cancel()
        servindo = escopo.launch { proximaFala(token) }
    }

    private suspend fun proximaFala(token: Int) {
        val d = dadosDaVez
        if (!primeiro && d != null && falaDaVez + 1 < d.falas.size) { lerFala(token, quadroDaVez, d, falaDaVez + 1); return }
        val total = leitor.quadros.size
        var i: Int
        if (primeiro) i = quadroDaVez
        else when (val depois = RegraLeituraCapitulo.depoisDoQuadro(quadroDaVez, total, avancar)) {
            RegraLeituraCapitulo.Depois.Fim -> { voz.parar(RegraVoz.FIM); return }
            RegraLeituraCapitulo.Depois.FimCapitulo -> { fimDoCapitulo(); return }
            is RegraLeituraCapitulo.Depois.Proximo -> i = depois.indice
        }
        val inicial = primeiro
        primeiro = false
        while (currentCoroutineContext().isActive) {
            quadroDaVez = i
            leitor.alvoLeitura = i
            val q = leitor.quadros.getOrNull(i) ?: run { voz.parar(RegraVoz.ERRO); return }
            when (RegraLeituraCapitulo.passo(leitor.situacaoLeitura(q))) {
                RegraLeituraCapitulo.Passo.ESPERAR -> {
                    val texto = if (inicial) AVISO_PREPARANDO_PRIMEIRO else AVISO_PREPARANDO
                    if (aviso != texto) avisar(texto)
                    delay(150)
                }
                RegraLeituraCapitulo.Passo.PARAR_ERRO -> {
                    avisar("Não foi possível preparar o próximo quadro.", 5_000L)
                    voz.parar(RegraLeituraCapitulo.ERRO_QUADRO)
                    return
                }
                RegraLeituraCapitulo.Passo.PULAR -> {
                    // Sem avançar, o quadro escolhido é tudo o que se lê: sem fala, não há o que ouvir.
                    if (inicial && !avancar) {
                        avisar("Não há falas traduzidas para ouvir neste quadro.", 3_000L)
                        voz.parar(RegraVoz.FIM)
                        return
                    }
                    // Pula SEM rolar: a tela só anda quando houver o que ler, uma animação só em vez de uma por página vazia.
                    when (val depois = RegraLeituraCapitulo.depoisDoQuadro(i, total, avancar)) {
                        RegraLeituraCapitulo.Depois.Fim -> { voz.parar(RegraVoz.FIM); return }
                        RegraLeituraCapitulo.Depois.FimCapitulo -> { fimDoCapitulo(); return }
                        is RegraLeituraCapitulo.Depois.Proximo -> i = depois.indice
                    }
                }
                RegraLeituraCapitulo.Passo.LER -> {
                    val dados = q.dados ?: continue
                    if (aviso == AVISO_PREPARANDO || aviso == AVISO_PREPARANDO_PRIMEIRO) avisar(null)
                    dadosDaVez = dados
                    lerFala(token, i, dados, 0)
                    return
                }
            }
        }
    }

    private fun fimDoCapitulo() {
        avisar("Fim do capítulo.", 4_000L)
        voz.parar(RegraLeituraCapitulo.FIM_CAPITULO)
    }

    private suspend fun lerFala(token: Int, i: Int, d: RegraLeituraCapitulo.DadosQuadro, k: Int) {
        falaDaVez = k
        if (avancar && !mostrar(i, d, d.falas[k])) return
        if (!voz.acrescentar(token, listOf(d.falas[k].texto)) && voz.lendo) voz.parar(RegraVoz.ERRO)
    }

    /**
     * Rola só o necessário para a fala aparecer inteira entre as barras: primeiro até o quadro, se ele não está na tela, e
     * depois o quanto falta para a fala. Devolve falso se a rolagem foi interrompida (e aí a leitura já parou).
     */
    private suspend fun mostrar(i: Int, d: RegraLeituraCapitulo.DadosQuadro, f: RegraLeituraCapitulo.FalaQuadro): Boolean {
        if (lista.layoutInfo.visibleItemsInfo.none { it.index == i } && !rolar { lista.animateScrollToItem(i) }) return false
        val info = lista.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return true
        val larguraTela = lista.layoutInfo.viewportSize.width
        val topo = RegraLeituraCapitulo.naTela(f.topo, d.largura, larguraTela, info.offset)
        val base = RegraLeituraCapitulo.naTela(f.base, d.largura, larguraTela, info.offset)
        val inicio = lista.layoutInfo.viewportStartOffset + alturaCima
        val fim = lista.layoutInfo.viewportEndOffset - alturaBaixo
        val margem = (16 * contexto.resources.displayMetrics.density).toInt()
        val delta = RegraLeituraCapitulo.deslocamento(topo, base, inicio, fim, margem)
        return delta == 0 || rolar { lista.animateScrollBy(delta.toFloat()) }
    }

    /**
     * Uma rolagem do app. Se o dono arrastar no meio, a animação é cancelada pelo arrasto (e não por nós): isso é rolagem
     * do dono e para a leitura. O nosso próprio cancelamento (Parar) segue adiante como cancelamento.
     */
    private suspend fun rolar(bloco: suspend () -> Unit): Boolean {
        rolandoPeloApp = true
        try {
            bloco()
            return true
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            interromper(RegraLeituraCapitulo.ROLAGEM)
            return false
        } finally {
            rolandoPeloApp = false
        }
    }

    companion object {
        const val AVISO_ORIGINAL = "Selecione 'Ver traduzido' para ouvir."
        private const val AVISO_PREPARANDO = "Preparando próximo quadro…"
        private const val AVISO_PREPARANDO_PRIMEIRO = "Preparando quadro…"
    }
}
