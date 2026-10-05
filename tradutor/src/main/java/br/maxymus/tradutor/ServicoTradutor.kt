package br.maxymus.tradutor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * O serviço faz tudo: a bolha, a captura e a sobreposição.
 *
 * Divergência do Astra, explicada: ele previu DUAS permissões, desenhar sobre outros apps e acessibilidade. Um
 * serviço de acessibilidade pode criar janelas do tipo TYPE_ACCESSIBILITY_OVERLAY sem a permissão de desenhar
 * sobre outros apps, então a primeira sai da configuração. Uma permissão a menos para o dono conceder.
 *
 * Desenho da primeira versão (Astra, 22/09): a tradução é uma leitura CONGELADA da tela. Mostramos a captura já
 * traduzida por cima de tudo e o toque fora dos botões fecha. Isso evita o desalinhamento entre desenho e texto quando a
 * pessoa rola, que seria o defeito mais visível de uma sobreposição viva. Desde a 0.27 a congelada tem dois botões
 * ("Voltar à página" e "Ouvir", que vira "Parar") e a leitura em voz alta mora em Voz.kt; a suspeita de captura escura ou
 * protegida, em Captura.kt.
 */
class ServicoTradutor : AccessibilityService() {

    companion object {
        @Volatile var ativo: ServicoTradutor? = null
        const val ACAO_TRADUZIR = "br.maxymus.tradutor.TRADUZIR"
        const val ACAO_BOLHA = "br.maxymus.tradutor.BOLHA"
        const val ACAO_PREPARAR = "br.maxymus.tradutor.PREPARAR"
        const val ACAO_NOTIFICAR = "br.maxymus.tradutor.NOTIFICAR"

        /** A tela de preparação precisa saber o que oferecer: mostrar ou esconder a bolha. */
        val bolhaNaTela: Boolean get() = ativo?.bolha != null
        private const val CANAL = "tradutor"
        private const val AVISO = 1
        // ids próprios das ações de acessibilidade da bolha: o byte alto diferente de zero marca ação personalizada
        private const val ACAO_A11Y_MENU = 0x4F000001
        private const val ACAO_A11Y_OUVIR = 0x4F000002
        private const val ACAO_A11Y_PARAR = 0x4F000003
    }

    /** Uma fala PINTADA na tela congelada e o texto que está pintado nela agora: é daqui que a leitura em voz alta lê. */
    private class Pintada(val fala: Falas.Fala, val traduzida: String)

    /**
     * O que a tela congelada precisa depois de traduzir: a camada, as falas (para a revisão), as medidas do pedido e as falas
     * pintadas (o mesmo filtro `uteis`, com o texto traduzido de cada uma).
     */
    private class Congelada(val camada: Bitmap, val falas: List<Falas.Fala>, val resultado: ResultadoLote, val pintadas: List<Pintada>)

    /** Recebe os toques da notificação fixa. */
    private val receptor = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            when (i.action) {
                ACAO_TRADUZIR -> if (continuo) encerraContinuo() else iniciaContinuo()
                ACAO_BOLHA -> { if (bolha == null) mostraBolha() else tiraBolha(); notificacao() }
                ACAO_NOTIFICAR -> notificacao()
                ACAO_PREPARAR -> {
                    preparar = !preparar
                    getSharedPreferences("tradutor", Context.MODE_PRIVATE).edit().putBoolean("preparar", preparar).apply()
                    Telemetria.evento("preparar", mapOf("ligado" to preparar)); notificacao()
                }
            }
        }
    }

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val portaoCaptura = Mutex()
    private var ultimaCaptura: Long? = null
    private lateinit var janelas: WindowManager
    private var bolha: View? = null
    private var sobreposicao: View? = null
    private var trabalhando = false
    private var msOcr = 0L; private var msTrad = 0L; private var msPint = 0L
    private var preparar = false
    private var ultimaRolagem = 0L
    private var ultimaAssinatura = 0L
    private var ultimaCorrecao = 0
    private var jobRevisao: kotlinx.coroutines.Job? = null
    private var jobPreparo: kotlinx.coroutines.Job? = null
    private var continuo = false          // sobreposição que acompanha a rolagem, em vez da tela congelada
    private var fechar: View? = null      // o X, janela própria porque a camada não recebe toque
    private var menu: View? = null
    private var camadaCongelada: ImageView? = null   // a camada traduzida da tela congelada, para trocar quando chega correção do modelo
    private var jobRevisaoCongelada: kotlinx.coroutines.Job? = null
    private var adiantando = false
    private var bx = 0; private var by = 0
    private var caixaAviso: View? = null    // a caixa com mensagem e botões (captura escura, captura protegida), uma por vez
    // avisos já dados NESTA ativação do modo contínuo, para a suspeita de captura não repetir aviso nem evento a cada rolagem
    private var capturaPretaEmitida = false
    private var capturaPretaNaoConfirmadaEmitida = false
    // a leitura em voz alta nasce na primeira vez que é pedida; ao destruir o serviço só se desliga o que foi criado
    private val vozLazy = lazy { Voz(this, { texto, ms -> aviso(texto, ms) }, { mostraSemVoz() }, { aoMudarLeitura() }) }
    private val voz: Voz get() = vozLazy.value
    private var botaoOuvir: TextView? = null                  // "Ouvir"/"Parar" do rodapé da tela congelada
    private var congeladaPintadas: List<Pintada> = emptyList() // o que está pintado na congelada AGORA; a fila da leitura sai daqui
    private var assinaturaEscuraTratada = 0L    // a última tela escura já diagnosticada no contínuo: a mesma tela não gasta outra captura

    override fun onServiceConnected() {
        ativo = this
        janelas = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Telemetria.iniciar(this)
        val filtro = android.content.IntentFilter().apply { addAction(ACAO_TRADUZIR); addAction(ACAO_BOLHA); addAction(ACAO_PREPARAR); addAction(ACAO_NOTIFICAR) }
        androidx.core.content.ContextCompat.registerReceiver(this, receptor, filtro, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        preparar = getSharedPreferences("tradutor", Context.MODE_PRIVATE).getBoolean("preparar", true)
        Traducao.carregarPreferencias(this)
        mostraBolha()
        notificacao()
    }

    override fun onDestroy() {
        ativo = null; continuo = false; tiraSobreposicao(); tiraBolha(); tiraMenu(); tiraCaixa()
        // o motor de voz é desligado (shutdown) e a leitura em andamento para: o serviço morreu, como a tela congelada
        if (vozLazy.isInitialized()) voz.desligar()
        fechar?.let { runCatching { janelas.removeView(it) } }; fechar = null
        runCatching { unregisterReceiver(receptor) }
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(AVISO) }
        // serviço destruído: as esperas de toque e de pré-carregamento param (ativo = false nos laços), sem cancelar os
        // futuros compartilhados do coordenador, que terminam pelo próprio prazo
        escopo.cancel()
        super.onDestroy()
    }

    /**
     * Notificação fixa (pedido do dono, 24/09): traduzir sem depender da bolha, e um jeito de sumir com ela quando
     * ela atrapalhar a leitura. Silenciosa e de baixa prioridade, para não piscar nem tocar a cada uso.
     */
    private fun notificacao() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        runCatching {
            nm.createNotificationChannel(android.app.NotificationChannel(CANAL, "Tradutor de tela",
                android.app.NotificationManager.IMPORTANCE_LOW).apply {
                description = "Atalho fixo para traduzir a tela"; setShowBadge(false)
            })
        }
        fun acao(a: String) = android.app.PendingIntent.getBroadcast(this, a.hashCode(),
            android.content.Intent(a).setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_sort_alphabetically)
            .setContentTitle("Tradutor de tela")
            .setContentText((if (bolha != null) "Toque em Traduzir, ou use a bolha" else "Bolha escondida · toque em Mostrar bolha") +
                (if (preparar) " · adiantando" else ""))
            .setOngoing(true).setShowWhen(false)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                android.content.Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.app.Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Traduzir", acao(ACAO_TRADUZIR)).build())
            .addAction(android.app.Notification.Action.Builder(null as android.graphics.drawable.Icon?,
                if (bolha != null) "Esconder bolha" else "Mostrar bolha", acao(ACAO_BOLHA)).build())
            .addAction(android.app.Notification.Action.Builder(null as android.graphics.drawable.Icon?,
                if (preparar) "Parar de adiantar" else "Adiantar", acao(ACAO_PREPARAR)).build())
            .build()
        runCatching { nm.notify(AVISO, n) }
    }
    /** Pausa a bolha sobre o leitor; ao sair, retoma a escolha que já estava ligada. */
    fun leitorMudou() {
        // Fora do contínuo a camada que estava na tela é de outra página: ao voltar do leitor ela fica escondida.
        sobreposicao?.visibility = if (!LeitorActivity.visivel && continuo) View.VISIBLE else View.INVISIBLE
        if (!LeitorActivity.visivel && (continuo || preparar)) {
            jobPreparo = escopo.launch {
                kotlinx.coroutines.delay(700)
                if (LeitorActivity.visivel || trabalhando) return@launch
                ultimaAssinatura = 0L
                if (continuo) desenhaContinuo() else preparaEmSilencio()
            }
        }
    }

    /**
     * Pré-carregamento (pedido do dono, 24/09): enquanto ele rola e LÊ, o app traduz em silêncio o que está na
     * tela e guarda no cache. Como o cache é indexado pelo TEXTO, o trabalho feito agora vale quando ele tocar na
     * bolha — e aí só sobra ler a tela e desenhar, sem esperar tradução. É a diferença entre ~5 s e ~1 s.
     *
     * Só roda quando: está ligado, não há tradução na tela, nada em andamento, e passou o tempo de descanso desde
     * a última rolagem. E só processa se a TELA MUDOU de verdade — a comparação é feita numa miniatura de 24x24,
     * que custa quase nada, em vez de refazer o reconhecimento à toa.
     */
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (LeitorActivity.visivel || e == null || (!preparar && !continuo)) return
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED && e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        ultimaRolagem = System.currentTimeMillis()
        if (jobPreparo?.isActive == true) return
        jobPreparo = escopo.launch {
            while (System.currentTimeMillis() - ultimaRolagem < 700) kotlinx.coroutines.delay(250)
            if (LeitorActivity.visivel || trabalhando) return@launch
            if (continuo) desenhaContinuo()
            else if (preparar && sobreposicao == null) preparaEmSilencio()
        }
    }

    private suspend fun preparaEmSilencio() {
        if (LeitorActivity.visivel || !Traducao.temRede(this)) return
        bolha?.visibility = View.INVISIBLE
        kotlinx.coroutines.delay(90)
        val tela = captura()
        bolha?.visibility = View.VISIBLE
        if (tela == null) return
        val assinatura = assinaturaDe(tela)
        if (assinatura == ultimaAssinatura) return           // tela igual: nada novo para adiantar
        ultimaAssinatura = assinatura
        val t0 = System.nanoTime()
        val n = withContext(Dispatchers.Default) {
            val falas = Falas.ler(tela, (tela.height * 0.11f).toInt(), (tela.height * 0.96f).toInt())
            if (falas.isEmpty() || LeitorActivity.visivel) 0 else { Traducao.traduzirLote(this@ServicoTradutor, falas.map { it.texto }, ativo = { isActive && !LeitorActivity.visivel }); falas.size }
        }
        if (n > 0) Telemetria.evento("preparou", mapOf("falas" to n, "ms" to (System.nanoTime() - t0) / 1_000_000, "cache" to Traducao.noCache))
    }

    /** Miniatura de 24x24 somada: barata o bastante para rodar sempre e detectar que a tela mudou. */
    private fun assinaturaDe(b: Bitmap): Long {
        val p = Bitmap.createScaledBitmap(b, 24, 24, true)
        val px = IntArray(24 * 24).also { p.getPixels(it, 0, 24, 0, 0, 24, 24) }
        if (p !== b) p.recycle()
        var h = 1125899906842597L
        for (c in px) h = h * 31 + (c and 0x00F0F0F0).toLong()
        return h
    }
    override fun onInterrupt() {}

    // ---------------- bolha ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    fun mostraBolha() {
        if (bolha != null) return
        val lado = dp(56)
        val v = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF252529.toInt()); setStroke(dp(2), 0xFFFF575F.toInt())
            }
            addView(TextView(this@ServicoTradutor).apply {
                text = "PT"; setTextColor(0xFFF5F5F5.toInt()); textSize = 15f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            contentDescription = "Tradutor de tela"
            // O toque longo e o toque cru não podem ser o único caminho (TalkBack): as ações da bolha também são ações de
            // acessibilidade, com ids próprios. "Parar leitura" só existe enquanto se lê.
            setAccessibilityDelegate(object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACAO_A11Y_MENU, "Abrir menu"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACAO_A11Y_OUVIR, "Ouvir esta tela"))
                    if (vozLazy.isInitialized() && voz.lendo) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACAO_A11Y_PARAR, "Parar leitura"))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                    ACAO_A11Y_MENU -> { abreMenu(); true }
                    ACAO_A11Y_OUVIR -> { ouvirEstaTela(); true }
                    ACAO_A11Y_PARAR -> { voz.parar(RegraVoz.PARAR); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            })
        }
        val p = WindowManager.LayoutParams(lado, lado,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - lado - dp(12)
            y = (resources.displayMetrics.heightPixels * 0.60f).toInt()
        }
        bx = p.x; by = p.y
        var x0 = 0f; var y0 = 0f; var px0 = 0; var py0 = 0; var arrastou = false; var descidaEm = 0L
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; px0 = p.x; py0 = p.y; arrastou = false; descidaEm = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) arrastou = true
                    if (arrastou) { p.x = px0 + dx.toInt(); p.y = py0 + dy.toInt(); runCatching { janelas.updateViewLayout(v, p) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (arrastou) {   // gruda na borda mais próxima
                        val meio = resources.displayMetrics.widthPixels / 2
                        p.x = if (p.x + lado / 2 < meio) dp(12) else resources.displayMetrics.widthPixels - lado - dp(12)
                        runCatching { janelas.updateViewLayout(v, p) }
                        bx = p.x; by = p.y
                    } else if (adiantando) adiantando = false
                    else if (System.currentTimeMillis() - descidaEm > 550) abreMenu()
                    else if (vozLazy.isInitialized() && voz.lendo) voz.parar(RegraVoz.TOQUE_BOLHA)   // durante a leitura o toque SÓ para a leitura
                    else if (continuo) encerraContinuo() else iniciaContinuo()
                    true
                }
                else -> false
            }
        }
        runCatching { janelas.addView(v, p); bolha = v }.onFailure {
            Telemetria.evento("erro", mapOf("onde" to "mostrar_bolha", "msg" to Telemetria.classe(it)))
        }
    }

    private fun tiraBolha() { bolha?.let { runCatching { janelas.removeView(it) } }; bolha = null }

    /**
     * Caminho DIRETO para a tela do app, sem broadcast no meio. O broadcast tinha uma falha silenciosa: se o
     * serviço estivesse listado nas configurações mas não estivesse rodando, o pedido não chegava a ninguém e
     * o botão não fazia nada, sem dizer nada. Aqui a tela só chama isto quando tem a instância na mão, e
     * recebe de volta o estado REAL em vez de adivinhar depois de uma espera.
     */
    /** A tela do app recoloca a notificação fixa ao abrir, porque ela pode ter sido dispensada num arrasto. */
    fun reafirmaNotificacao() = notificacao()

    fun alternarBolha(): Boolean {
        if (bolha == null) mostraBolha() else tiraBolha()
        notificacao()
        Telemetria.evento("bolha", mapOf("na_tela" to (bolha != null), "de" to "app"))
        return bolha != null
    }

    // ---------------- tradução ----------------

    /**
     * Modo contínuo (pedido do dono, 24/09): a camada traduzida fica POR CIMA sem receber toque, então a página
     * rola normalmente por baixo. Quando a rolagem para, ela se redesenha na posição nova. Fecha no X ou tocando
     * na bolha de novo.
     *
     * Isto substitui a tela congelada como gesto principal. A congelada continua existindo no menu do toque longo,
     * porque ela é melhor quando o dono quer PARAR e ler com calma sem nada se mexendo.
     */
    private fun iniciaContinuo() {
        if (camadaCongelada != null) tiraSobreposicao()   // as duas camadas não podem existir juntas; fechar também para a leitura
        continuo = true
        capturaPretaEmitida = false; capturaPretaNaoConfirmadaEmitida = false; assinaturaEscuraTratada = 0L
        mostraFechar()
        notificacao()
        escopo.launch { desenhaContinuo() }
    }

    private fun encerraContinuo() {
        continuo = false
        tiraSobreposicao()
        fechar?.let { runCatching { janelas.removeView(it) } }; fechar = null
        notificacao()
    }

    private suspend fun desenhaContinuo() {
        if (LeitorActivity.visivel || !continuo || trabalhando) return
        trabalhando = true
        sobreposicao?.visibility = View.INVISIBLE
        bolha?.visibility = View.INVISIBLE
        fechar?.visibility = View.INVISIBLE
        kotlinx.coroutines.delay(90)
        val cap = capturar()
        val tela = cap.tela
        bolha?.visibility = View.VISIBLE
        fechar?.visibility = View.VISIBLE
        // janela protegida: o aviso sai uma vez por ativação, e o modo contínuo continua ligado
        if (tela == null && cap.protegida && continuo) avisaCapturaProtegida()
        if (tela == null || !continuo || LeitorActivity.visivel) {
            trabalhando = false
            sobreposicao?.visibility = if (LeitorActivity.visivel) View.INVISIBLE else View.VISIBLE
            return
        }
        val assin = assinaturaDe(tela)
        // tela igual não basta para pular: se o modelo chegou atrasado e melhorou o cache, tem que redesenhar
        val corr = Traducao.correcoes.get()
        if (assin == ultimaAssinatura && corr == ultimaCorrecao && sobreposicao != null) {
            trabalhando = false; sobreposicao?.visibility = View.VISIBLE; return
        }
        ultimaAssinatura = assin
        ultimaCorrecao = corr
        // DUAS PASSADAS, para a tela não ficar esperando tradução: primeiro desenha o que já está no cache
        // (medido: 97 ms de leitura + 174 ms de desenho), depois busca o que falta e redesenha. Com o capítulo
        // adiantado, a primeira passada já é a final.
        val tOcr = System.nanoTime()
        val topo = (tela.height * 0.11f).toInt(); val base = (tela.height * 0.96f).toInt()
        val lidas = withContext(Dispatchers.Default) { Falas.lerOuNulo(tela, topo, base) }
        val falas = lidas?.falas.orEmpty()
        val msO = (System.nanoTime() - tOcr) / 1_000_000
        if (falas.isEmpty()) {
            // OCR que rodou e não achou texto: se a imagem é quase toda preta, pode ser página escura ou captura bloqueada
            if (lidas?.algumTexto == false) suspeitaNoContinuo(tela, topo, base, assin)
            trabalhando = false; sobreposicao?.visibility = View.VISIBLE; return
        }
        if (LeitorActivity.visivel) { trabalhando = false; return }
        val textos = falas.map { it.texto }
        val conhecido = withContext(Dispatchers.Default) { Traducao.soCache(this@ServicoTradutor, textos) }
        // Só se pinta o que MUDOU, o mesmo filtro do Leitor (uteis): fala pulada por já estar no idioma do dono, ou
        // traduzida igual ao original, não ganha faixa. Senão a interface do app em português levaria uma por cima.
        val uteisConhecido = falas.filter { (conhecido[it.texto] ?: it.texto) != it.texto }
        if (uteisConhecido.isNotEmpty() && continuo && !LeitorActivity.visivel)
            mostraCamada(withContext(Dispatchers.Default) { Pintura.camada(tela, uteisConhecido) { conhecido[it] ?: it } })
        val tTrad = System.nanoTime()
        // as medidas do pedido vêm no resultado dele: pré-carregamento e toque rodam ao mesmo tempo e não se misturam
        val r = withContext(Dispatchers.Default) { Traducao.traduzirLoteDetalhado(this@ServicoTradutor, textos, ativo = { isActive && !LeitorActivity.visivel }) }
        val mapa = r.mapa
        val msT = (System.nanoTime() - tTrad) / 1_000_000
        trabalhando = false
        if (!continuo || LeitorActivity.visivel) return
        val tPint = System.nanoTime()
        val uteis = falas.filter { (mapa[it.texto] ?: it.texto) != it.texto }
        // Sem nada útil a camada sai vazia, e mesmo assim é posta: ela troca a da tela anterior, que senão ficaria com
        // as faixas velhas por cima da tela nova (e a tela igual continua sendo reconhecida acima, por haver camada).
        if (uteisConhecido.isEmpty() || uteis.map { it.texto to mapa[it.texto] } != uteisConhecido.map { it.texto to conhecido[it.texto] })
            mostraCamada(withContext(Dispatchers.Default) { Pintura.camada(tela, uteis) { mapa[it] ?: it } })
        val msP = (System.nanoTime() - tPint) / 1_000_000
        // só números, códigos fixos e o id aleatório do serviço: nenhum texto de fala nem de resposta, nem hash deles
        Telemetria.evento("traduziu", mapOf("modo" to "continuo", "falas" to falas.size,
            "ja_no_cache" to conhecido.size, "caminho" to r.caminho,
            "origem" to r.origem, "modelo" to (r.modelo ?: "-"),
            "puladas" to falas.count { it.texto in r.puladas }, "und" to r.und, "ms_modelo" to r.msModelo,
            "compartilhadas" to r.compartilhadas, "id" to r.idServidor,
            "ms_ocr" to msO, "ms_trad" to msT, "ms_pint" to msP, "cache" to Traducao.noCache))
        agendaRevisao(corr)
    }

    /**
     * O modelo bom chega atrasado e corrige o cache. Sem isto a tradução rápida ficaria na tela para sempre,
     * porque o laço só redesenha quando a tela MUDA — e o dono está parado, lendo. Então espera-se a correção
     * por até 12 s e redesenha UMA vez quando ela vem.
     *
     * `referencia` é o valor de `correcoes` lido ANTES de a tradução começar, e não o de agora: uma correção que chegou no
     * meio do pedido já conta, em vez de entrar na referência e ficar invisível. Se o desenho estiver ocupado quando ela
     * for notada, a revisão fica pendente (espera a próxima volta) em vez de se perder.
     */
    private fun agendaRevisao(referencia: Int) {
        jobRevisao?.cancel()
        jobRevisao = escopo.launch {
            repeat(24) {
                kotlinx.coroutines.delay(500)
                if (!continuo || LeitorActivity.visivel) return@launch
                if (Traducao.correcoes.get() != referencia && !trabalhando) { desenhaContinuo(); return@launch }
            }
        }
    }

    /**
     * O mesmo para a tela congelada, que antes nem agendava revisão: o modelo bom chega depois e a camada já mostrada
     * ficava com a tradução inferior até o dono fechar. Por até 12 s, quando `correcoes` muda em relação a `referencia`
     * (lida antes de a tradução começar), a camada é refeita a partir do cache e trocada no mesmo ImageView, desde que
     * a sobreposição ainda seja a mesma. Reaproveita o contador e o filtro `uteis` do modo contínuo.
     *
     * Durante a leitura em voz alta o que se vê e o que se ouve são a MESMA versão do texto: a correção espera a leitura
     * acabar ou ser interrompida (sem gastar os 12 s) e então entra, e a lista do que está pintado muda junto com a camada.
     */
    private fun agendaRevisaoCongelada(tela: Bitmap, falas: List<Falas.Fala>, referencia: Int) {
        val alvo = camadaCongelada ?: return
        jobRevisaoCongelada?.cancel()
        jobRevisaoCongelada = escopo.launch {
            var vista = referencia
            var restantes = 24
            while (restantes > 0) {
                kotlinx.coroutines.delay(500)
                if (camadaCongelada !== alvo) return@launch
                if (vozLazy.isInitialized() && voz.lendo) continue
                restantes--
                val atual = Traducao.correcoes.get()
                if (atual != vista) {
                    val mapa = withContext(Dispatchers.Default) { Traducao.soCache(this@ServicoTradutor, falas.map { it.texto }) }
                    val uteis = falas.filter { (mapa[it.texto] ?: it.texto) != it.texto }
                    val nova = withContext(Dispatchers.Default) { Pintura.camada(tela, uteis) { mapa[it] ?: it } }
                    if (camadaCongelada !== alvo) return@launch
                    // a leitura pode ter começado enquanto a camada era refeita: a correção fica para depois dela
                    if (vozLazy.isInitialized() && voz.lendo) continue
                    vista = atual
                    alvo.setImageBitmap(nova)
                    congeladaPintadas = uteis.map { Pintada(it, mapa[it.texto] ?: it.texto) }
                }
            }
        }
    }

    /** Camada que NÃO recebe toque: a página continua rolando por baixo. */
    private fun mostraCamada(camada: Bitmap) {
        val alvo = sobreposicao as? ImageView
        if (alvo != null) { alvo.setImageBitmap(camada); alvo.visibility = View.VISIBLE; return }
        val v = ImageView(this).apply { setImageBitmap(camada); scaleType = ImageView.ScaleType.FIT_XY }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT)
        runCatching { janelas.addView(v, p); sobreposicao = v }
    }

    private fun mostraFechar() {
        if (fechar != null) return
        val lado = dp(48)
        val v = TextView(this).apply {
            text = "✕"; setTextColor(0xFF111114.toInt()); textSize = 17f; gravity = Gravity.CENTER
            contentDescription = "Fechar tradução"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFF575F.toInt())
            }
            setOnClickListener { encerraContinuo() }
        }
        val p = WindowManager.LayoutParams(lado, lado, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(90)
        }
        runCatching { janelas.addView(v, p); fechar = v }
    }

    /**
     * Adiantar o capítulo (pedido do dono, 24/09): a captura só alcança o que está DESENHADO na tela, e o que está
     * abaixo da dobra o navegador ainda nem desenhou — não existe como pixel. O único jeito de traduzir o que ele
     * ainda não viu é rolar de verdade. Então o app rola sozinho, lê e traduz cada tela para o cache, e volta.
     * Depois disso a leitura fica instantânea, porque o cache é indexado pelo TEXTO.
     *
     * Volta ao ponto de partida rolando o mesmo tanto para trás. Não é exato, e o aviso diz isso.
     */
    private suspend fun adiantaCapitulo(maxTelas: Int = 25) {
        if (adiantando) return
        adiantando = true
        aviso("Adiantando o capítulo. Toque na bolha para parar.")
        var telas = 0
        var iguais = 0
        for (i in 0 until maxTelas) {
            if (!adiantando) break
            bolha?.visibility = View.INVISIBLE
            sobreposicao?.visibility = View.INVISIBLE
            kotlinx.coroutines.delay(90)
            val cap = capturar()
            val tela = cap.tela
            bolha?.visibility = View.VISIBLE
            if (tela == null) { if (cap.protegida) avisaCapturaProtegida(); break }
            val assin = assinaturaDe(tela)
            if (assin == ultimaAssinatura) { iguais++; if (iguais >= 2) break } else iguais = 0
            ultimaAssinatura = assin
            withContext(Dispatchers.Default) {
                val falas = Falas.ler(tela, (tela.height * 0.11f).toInt(), (tela.height * 0.96f).toInt())
                if (falas.isNotEmpty()) Traducao.traduzirLote(this@ServicoTradutor, falas.map { it.texto }, ativo = { isActive && !LeitorActivity.visivel })
            }
            telas++
            if (!rola(true)) break
            kotlinx.coroutines.delay(650)
        }
        // volta para onde estava
        repeat(telas) { if (rola(false)) kotlinx.coroutines.delay(500) }
        adiantando = false
        aviso("Adiantei $telas telas. A leitura agora sai na hora.")
        Telemetria.evento("adiantou_capitulo", mapOf("telas" to telas, "cache" to Traducao.noCache))
    }

    /** Rolagem por gesto: é o que alcança o navegador, que não expõe a página como conteúdo. */
    private suspend fun rola(paraBaixo: Boolean): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val l = resources.displayMetrics.widthPixels / 2f
        val alt = resources.displayMetrics.heightPixels
        val de = if (paraBaixo) alt * 0.80f else alt * 0.28f
        val ate = if (paraBaixo) alt * 0.22f else alt * 0.86f
        val caminho = android.graphics.Path().apply { moveTo(l, de); lineTo(l, ate) }
        val gesto = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(caminho, 0, 320)).build()
        val ok = runCatching {
            dispatchGesture(gesto, object : GestureResultCallback() {
                override fun onCompleted(d: android.accessibilityservice.GestureDescription?) { if (cont.isActive) cont.resume(true) {} }
                override fun onCancelled(d: android.accessibilityservice.GestureDescription?) { if (cont.isActive) cont.resume(false) {} }
            }, null)
        }.getOrDefault(false)
        if (!ok && cont.isActive) cont.resume(false) {}
    }

    /** Toque longo na bolha: as escolhas que o dono pediu. */
    private fun abreMenu() {
        if (menu != null) { tiraMenu(); return }
        fun item(texto: String, acao: () -> Unit) = TextView(this).apply {
            this.text = texto; setTextColor(0xFFF5F5F5.toInt()); textSize = 15f
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48)    // alvo tocável de pelo menos 48 dp
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { tiraMenu(); acao() }
        }
        val caixa = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF252529.toInt()); cornerRadius = dp(14).toFloat()
            }
            addView(item("Ouvir esta tela") { ouvirEstaTela() })
            addView(item("Traduzir a tela toda (parada)") { traduzTela() })
            addView(item(if (adiantando) "Parar de adiantar o capítulo" else "Adiantar o capítulo inteiro") {
                if (adiantando) adiantando = false else escopo.launch { adiantaCapitulo() }
            })
            addView(item("Idiomas") {
                startActivity(android.content.Intent(this@ServicoTradutor, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("abrir", "idiomas"))
            })
            addView(item("Fechar este menu") { })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
        }
        runCatching { janelas.addView(caixa, p); menu = caixa }
    }

    private fun tiraMenu() { menu?.let { runCatching { janelas.removeView(it) } }; menu = null }

    /**
     * "Ouvir esta tela" (item do menu e ação de acessibilidade da bolha): faz o mesmo que "Traduzir a tela toda (parada)" e,
     * quando a tela congelada aparecer, começa a ler. Com destino que não é português nem traduz.
     */
    private fun ouvirEstaTela() {
        if (!voz.destinoPermite()) return
        traduzTela(ouvir = true)
    }

    private fun traduzTela(ouvir: Boolean = false) {
        if (trabalhando) return
        if (continuo) encerraContinuo()   // as duas camadas não podem existir juntas
        trabalhando = true
        escopo.launch {
            // trabalhando volta a false de qualquer jeito: corrotina cancelada ou exceção no meio não podem deixar o serviço
            // recusando os toques seguintes. O caminho normal solta mais cedo; a liberação é uma vez só, para o finally não
            // atropelar um toque novo que já começou depois da soltura antecipada.
            var liberou = false
            fun libera() { if (!liberou) { liberou = true; trabalhando = false } }
            try {
                // A captura pega a tela inteira, inclusive a bolha. Mudar o alfa NAO basta: a janela so some depois de
                // desenhar o proximo quadro. Sem esta espera a bolha aparece congelada dentro da propria traducao.
                bolha?.visibility = View.INVISIBLE
                kotlinx.coroutines.delay(90)
                val cap = capturar()
                val tela = cap.tela
                bolha?.visibility = View.VISIBLE
                if (tela == null) {
                    libera()
                    if (cap.protegida) avisaCapturaProtegida() else aviso("Não consegui capturar a tela.")
                    return@launch
                }
                // fora as barras do sistema e do navegador: sem isto o app le a barra de endereço e gasta tradução
                val topo = (tela.height * 0.11f).toInt(); val base = (tela.height * 0.96f).toInt()
                val t0 = System.nanoTime()
                // a referência da revisão é lida ANTES de traduzir: correção que chega no meio do pedido não pode ficar invisível
                val referencia = Traducao.correcoes.get()
                var tudoNoIdioma = false
                var fracaoSuspeita: Float? = null
                val camada = withContext(Dispatchers.Default) {
                    val tOcr = System.nanoTime()
                    val lidas = Falas.lerOuNulo(tela, topo, base)
                    val falas = lidas?.falas.orEmpty()
                    msOcr = (System.nanoTime() - tOcr) / 1_000_000
                    if (falas.isEmpty()) {
                        // OCR que rodou e não achou texto: se a imagem é quase toda preta, pode ser página escura ou captura
                        // bloqueada. OCR que falhou (lidas nulo) é inconclusivo e não gera suspeita.
                        if (lidas?.algumTexto == false) {
                            val f = RegraCaptura.fracaoEscura(tela, topo, base)
                            if (RegraCaptura.suspeita(0, false, f)) fracaoSuspeita = f
                        }
                        null
                    }
                    else {
                        val tTrad = System.nanoTime()
                        // as falas puladas e as medidas são DESTE pedido: o pré-carregamento do contínuo roda ao mesmo tempo
                        val r = Traducao.traduzirLoteDetalhado(this@ServicoTradutor, falas.map { it.texto }, ativo = { isActive && !LeitorActivity.visivel })
                        val mapa = r.mapa
                        msTrad = (System.nanoTime() - tTrad) / 1_000_000
                        // o mesmo filtro do Leitor (uteis): fala pulada ou traduzida igual ao original não ganha faixa
                        val uteis = falas.filter { (mapa[it.texto] ?: it.texto) != it.texto }
                        if (uteis.isEmpty() && falas.all { it.texto in r.puladas }) { tudoNoIdioma = true; null }
                        else {
                            // há fala ainda sem tradução útil (o modelo pode chegar depois do prazo): a tela congela com
                            // camada transparente e a revisão congelada a completa quando o cache for corrigido
                            // (revisão do Astra, 03/10)
                            val tPint = System.nanoTime()
                            val c = if (uteis.isEmpty()) Bitmap.createBitmap(tela.width, tela.height, Bitmap.Config.ARGB_8888)
                                    else Pintura.camada(tela, uteis) { mapa[it] ?: it }
                            msPint = (System.nanoTime() - tPint) / 1_000_000
                            Congelada(c, falas, r, uteis.map { Pintada(it, mapa[it.texto] ?: it.texto) })
                        }
                    }
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                // Suspeita de captura escura: UMA segunda captura confirma, e o serviço só é solto depois dela
                val fracao1 = fracaoSuspeita
                val confirmacao = if (camada == null && fracao1 != null) confirmaCapturaEscura(comCamada = false) else Confirmacao.NAO
                val escuraConfirmada = confirmacao == Confirmacao.ESCURA
                libera()
                if (tudoNoIdioma) {
                    if (ouvir) voz.semFalas() else aviso("Esta tela já está no seu idioma.")
                    Telemetria.evento("traduziu", mapOf("falas" to 0, "ms" to ms, "tudo_no_idioma" to true))
                    return@launch
                }
                if (camada == null) {
                    if (confirmacao == Confirmacao.PROTEGIDA) avisaCapturaProtegida()
                    else if (fracao1 != null) registraCapturaEscura(fracao1, escuraConfirmada)
                    if (confirmacao == Confirmacao.NAO) {
                        if (ouvir) voz.semFalas()
                        else aviso("Não encontrei texto. Mova um pouco a página e tente de novo.")
                    }
                    Telemetria.evento("traduziu", mapOf("falas" to 0, "ms" to ms))
                    return@launch
                }
                val r = camada.resultado
                // só números, códigos fixos e o id aleatório do serviço: nenhum texto de fala nem de resposta, nem hash deles
                Telemetria.evento("traduziu", mapOf("falas" to camada.falas.size, "ms" to ms, "cache" to Traducao.noCache,
                    "caminho" to r.caminho, "origem" to r.origem, "online" to r.online, "offline" to r.offline,
                    "modelo" to (r.modelo ?: "-"), "puladas" to camada.falas.count { it.texto in r.puladas }, "und" to r.und,
                    "ms_modelo" to r.msModelo, "compartilhadas" to r.compartilhadas, "id" to r.idServidor,
                    "ms_ocr" to msOcr, "ms_trad" to msTrad, "ms_pint" to msPint))
                mostraSobreposicao(tela, camada.camada, camada.pintadas)
                agendaRevisaoCongelada(tela, camada.falas, referencia)
                if (ouvir) ouvirCongelada(RegraVoz.BOLHA)
            } finally {
                libera()
            }
        }
    }

    /**
     * O que uma captura devolveu: a imagem, ou nada. `protegida` é só o código EXPLÍCITO do Android para janela segura
     * (ERROR_TAKE_SCREENSHOT_SECURE_WINDOW, API 34): os outros erros de captura não recebem essa interpretação.
     */
    private class Capturada(val tela: Bitmap?, val protegida: Boolean = false)

    private suspend fun captura(): Bitmap? = capturar().tela

    private suspend fun capturar(): Capturada = withContext(Dispatchers.Main) {
        portaoCaptura.withLock {
            // todas as tentativas, inclusive a confirmação, respeitam o intervalo do Android
            ultimaCaptura?.let { ultima ->
                val falta = 1_100L - (SystemClock.elapsedRealtime() - ultima)
                if (falta > 0) kotlinx.coroutines.delay(falta)
            }
            if (LeitorActivity.visivel) return@withLock Capturada(null)
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<Capturada> { cont ->
                    ultimaCaptura = SystemClock.elapsedRealtime()
                    runCatching {
                        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                            override fun onSuccess(r: ScreenshotResult) {
                                val b = runCatching {
                                    Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                                }.getOrNull()
                                runCatching { r.hardwareBuffer.close() }
                                if (cont.isActive) cont.resume(Capturada(b)) {}
                            }
                            override fun onFailure(code: Int) {
                                // a constante é da API 34 e o minSdk é 30: sem a guarda, o código não existe no aparelho
                                val protegida = Build.VERSION.SDK_INT >= 34 && code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                                // janela protegida não é erro de captura: o aviso e o evento captura_preta saem de quem pediu
                                if (!protegida) Telemetria.evento("erro", mapOf("onde" to "captura", "codigo" to code))
                                if (cont.isActive) cont.resume(Capturada(null, protegida)) {}
                            }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(Capturada(null)) {} }
                }
            } ?: run {
                Telemetria.evento("erro", mapOf("onde" to "captura", "codigo" to -1))
                Capturada(null)
            }
        }
    }

    /**
     * Janela protegida contra captura (código explícito do Android). Aviso e evento, sem fração: nenhuma imagem foi
     * produzida. No modo contínuo sai uma vez por ativação, e o modo nunca é desligado por isso.
     */
    private fun avisaCapturaProtegida() {
        if (!marcaCapturaPreta()) return
        Telemetria.evento("captura_preta", mapOf("evidencia" to "secure_window"))
        mostraCaixa("Esta tela está protegida contra captura. Se o capítulo estiver disponível em um site, tente abri-lo no navegador.",
            listOf("Entendi" to {}))
    }

    /** O aviso da suspeita CONFIRMADA de captura escura: condicional, porque página escura legítima parece igual. */
    private fun avisaCapturaEscura() {
        mostraCaixa("A captura ficou escura e não encontramos texto. Pode ser uma página escura ou um bloqueio de captura. " +
            "Se acontecer novamente, tente abrir o capítulo no navegador.", listOf("Entendi" to {}))
    }

    /** `fracao` é a da primeira captura, a que levantou a suspeita; só número e código fixo vão para a telemetria. */
    private fun registraCapturaEscura(fracao: Float, confirmada: Boolean) {
        if (confirmada) {
            if (!marcaCapturaPreta()) return
        } else if (continuo) {
            if (capturaPretaNaoConfirmadaEmitida) return
            capturaPretaNaoConfirmadaEmitida = true
        }
        Telemetria.evento("captura_preta", mapOf("evidencia" to "grade", "fracao" to RegraCaptura.duasCasas(fracao), "confirmada" to confirmada))
        if (confirmada) avisaCapturaEscura()
    }

    /** Uma única emissão confirmada ou protegida por ativação do contínuo. */
    private fun marcaCapturaPreta(): Boolean {
        if (!continuo) return true
        if (capturaPretaEmitida) return false
        capturaPretaEmitida = true
        return true
    }

    private enum class Confirmacao { NAO, ESCURA, PROTEGIDA }

    /**
     * A segunda captura da suspeita espera o portão de captura, com as janelas do tradutor escondidas como na primeira
     * (`comCamada`: também a camada traduzida, que o modo contínuo esconde). Repete o diagnóstico: só confirma com a captura
     * em mãos, OCR que rodou sem achar texto e fração de pretos de pelo menos RegraCaptura.FRACAO_SUSPEITA. Sem repetição.
     */
    private suspend fun confirmaCapturaEscura(comCamada: Boolean): Confirmacao {
        bolha?.visibility = View.INVISIBLE
        fechar?.visibility = View.INVISIBLE
        if (comCamada) sobreposicao?.visibility = View.INVISIBLE
        // espera só o próximo quadro para esconder as janelas; o intervalo fica no portão
        kotlinx.coroutines.delay(90)
        val cap = capturar()
        val outra = cap.tela
        bolha?.visibility = View.VISIBLE
        fechar?.visibility = View.VISIBLE
        if (cap.protegida) return Confirmacao.PROTEGIDA
        if (outra == null) return Confirmacao.NAO
        return withContext(Dispatchers.Default) {
            val topo = (outra.height * 0.11f).toInt(); val base = (outra.height * 0.96f).toInt()
            val lidas = Falas.lerOuNulo(outra, topo, base)
            if (lidas?.algumTexto == false && RegraCaptura.suspeita(0, false, RegraCaptura.fracaoEscura(outra, topo, base)))
                Confirmacao.ESCURA else Confirmacao.NAO
        }
    }

    /**
     * Modo contínuo: o OCR rodou e não achou texto. Com a imagem quase toda preta, confirma com uma segunda captura. Aviso e
     * evento confirmado compartilham a marca com a janela protegida; o descarte tem sua própria marca por ativação. A mesma
     * tela escura não é diagnosticada duas vezes. Nunca desliga o modo.
     */
    private suspend fun suspeitaNoContinuo(tela: Bitmap, topo: Int, base: Int, assinatura: Long) {
        if (assinatura == assinaturaEscuraTratada || capturaPretaEmitida) return
        val fracao = withContext(Dispatchers.Default) { RegraCaptura.fracaoEscura(tela, topo, base) }
        if (!RegraCaptura.suspeita(0, false, fracao)) return
        assinaturaEscuraTratada = assinatura
        val confirmada = confirmaCapturaEscura(comCamada = true)
        if (!continuo || LeitorActivity.visivel) return
        if (confirmada == Confirmacao.PROTEGIDA) avisaCapturaProtegida()
        else registraCapturaEscura(fracao, confirmada == Confirmacao.ESCURA)
    }

    /**
     * Caixa simples no centro da tela, no estilo do menu da bolha: a mensagem e os botões, cada um com pelo menos 48 dp de
     * altura. Só existe uma por vez; não toma o foco nem tapa os toques fora dela.
     */
    private fun mostraCaixa(mensagem: String, botoes: List<Pair<String, () -> Unit>>) {
        tiraCaixa()
        val v = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF252529.toInt()); cornerRadius = dp(14).toFloat()
            }
            addView(TextView(this@ServicoTradutor).apply {
                text = mensagem; setTextColor(0xFFF5F5F5.toInt()); textSize = 15f
                setPadding(dp(18), dp(16), dp(18), dp(8))
            })
            for ((rotulo, acao) in botoes) addView(TextView(this@ServicoTradutor).apply {
                text = rotulo; setTextColor(0xFFFF575F.toInt()); textSize = 15f
                gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setOnClickListener { tiraCaixa(); acao() }
            })
        }
        val largura = minOf(dp(320), resources.displayMetrics.widthPixels - dp(32))
        val p = WindowManager.LayoutParams(largura, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        runCatching { janelas.addView(v, p); caixaAviso = v }
    }

    private fun tiraCaixa() { caixaAviso?.let { runCatching { janelas.removeView(it) } }; caixaAviso = null }

    private fun mostraSobreposicao(tela: Bitmap, camada: Bitmap, pintadas: List<Pintada>) {
        tiraSobreposicao()
        congeladaPintadas = pintadas
        val caixa = FrameLayout(this)
        // as duas imagens são a captura e a camada: nada para o leitor de tela anunciar nelas
        caixa.addView(ImageView(this).apply {
            setImageBitmap(tela); scaleType = ImageView.ScaleType.FIT_XY; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val imagemCamada = ImageView(this).apply {
            setImageBitmap(camada); scaleType = ImageView.ScaleType.FIT_XY; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        caixa.addView(imagemCamada)
        caixa.addView(rodapeCongelada(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM; bottomMargin = dp(28); marginStart = dp(16); marginEnd = dp(16)
        })
        // O toque FORA dos botões fecha (e tiraSobreposicao para a leitura) e é CONSUMIDO: não pode vazar e clicar em algo do
        // navegador por baixo. Os botões tratam o próprio toque, e o toque neles não chega aqui.
        caixa.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) tiraSobreposicao(); true }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE)
        runCatching { janelas.addView(caixa, p); sobreposicao = caixa; camadaCongelada = imagemCamada }
        // a bolha precisa ficar POR CIMA da sobreposição; remover e recriar deixava duas na tela
        bolha?.let { b -> runCatching { janelas.removeViewImmediate(b) }; bolha = null }
        mostraBolha()
    }

    /**
     * O rodapé da tela congelada: "Voltar à página" e "Ouvir" (que durante a leitura vira "Parar"), com o texto que explica o
     * resto da tela. Cada botão tem pelo menos 48 dp de altura e de largura, divide a linha por igual e quebra o texto se a
     * fonte for grande.
     */
    private fun rodapeCongelada(): View {
        fun botao(rotulo: String, acao: () -> Unit) = TextView(this).apply {
            text = rotulo; setTextColor(0xFFF5F5F5.toInt()); textSize = 15f; gravity = Gravity.CENTER
            minHeight = dp(48); minWidth = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF252529.toInt()); setStroke(dp(1), 0xFFFF575F.toInt()); cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { acao() }
        }
        val voltar = botao("Voltar à página") { tiraSobreposicao() }
        val lendo = vozLazy.isInitialized() && voz.lendo
        val ouvir = botao(if (lendo) "Parar" else "Ouvir") { alternaLeitura() }
        ouvir.contentDescription = if (lendo) "Parar leitura" else "Ouvir esta tela"
        botaoOuvir = ouvir
        val linha = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(voltar, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(ouvir, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC111114.toInt()); cornerRadius = dp(14).toFloat() }
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(TextView(this@ServicoTradutor).apply {
                text = "Toque fora dos botões para voltar à página."
                setTextColor(0xFFF5F5F5.toInt()); textSize = 13f; gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6))
            })
            addView(linha)
        }
    }

    /**
     * Lê a tela congelada: as falas TRADUZIDAS pintadas agora (o mesmo `uteis`), na ordem de leitura (faixas de cima para baixo,
     * esquerda para direita; RegraVoz.ordemDeLeitura). Falas iguais em balões diferentes são lidas as duas vezes. O texto lido é
     * a cópia deste momento: a revisão troca a referência da lista, nunca o conteúdo, e Voz.ouvir ainda copia.
     */
    private fun ouvirCongelada(onde: String) {
        val pintadas = congeladaPintadas
        if (pintadas.isEmpty()) { voz.semFalas(); return }
        val ordem = RegraVoz.ordemDeLeitura(pintadas.map { RegraVoz.PosicaoFala(it.fala.caixa.left, it.fala.caixa.top, it.fala.alturaLinha) })
        voz.ouvir(ordem.map { pintadas[it].traduzida }, onde)
    }

    /** O botão do rodapé da congelada: "Parar" mantém a tela aberta (motivo parar); "Ouvir" lê a tela. */
    private fun alternaLeitura() {
        if (voz.lendo) voz.parar(RegraVoz.PARAR) else ouvirCongelada(RegraVoz.CONGELADA)
    }

    /** A leitura começou ou acabou (ou está preparando a voz): o botão do rodapé passa de "Ouvir" a "Parar" e volta. */
    private fun aoMudarLeitura() {
        val lendo = voz.lendo
        botaoOuvir?.apply {
            text = if (lendo) "Parar" else "Ouvir"
            contentDescription = if (lendo) "Parar leitura" else "Ouvir esta tela"
        }
    }

    /** Sem voz pt-BR instalada que funcione sem internet: a caixa com os dois botões. Quem voltar precisa tocar em Ouvir de novo. */
    private fun mostraSemVoz() {
        mostraCaixa("Não encontramos uma voz de português do Brasil disponível sem internet. Instale uma voz para ouvir.",
            listOf("Instalar voz" to { instalaVoz() }, "Agora não" to {}))
    }

    /**
     * O pedido de instalação da voz (TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA) leva só o pedido, nunca o texto do capítulo.
     * Se nenhum aplicativo atender, abre as configurações de voz do sistema.
     */
    private fun instalaVoz() {
        val nova = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(nova))
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(nova))
            } catch (_: android.content.ActivityNotFoundException) {
                runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(nova)) }
            }
        }
    }

    private fun tiraSobreposicao() {
        // a congelada fechando (toque fora dos botões, "Voltar à página", outra tradução, serviço destruído) para a leitura junto
        if (camadaCongelada != null && vozLazy.isInitialized()) voz.parar(RegraVoz.TELA_FECHADA)
        sobreposicao?.let { runCatching { janelas.removeView(it) } }; sobreposicao = null
        jobRevisaoCongelada?.cancel(); camadaCongelada = null
        botaoOuvir = null; congeladaPintadas = emptyList()
    }

    private fun aviso(texto: String, ms: Long = 3000L) {
        val t = TextView(this).apply {
            text = texto; setTextColor(0xFFF5F5F5.toInt()); textSize = 13f
            setBackgroundColor(0xEE252529.toInt()); setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(120)
        }
        runCatching { janelas.addView(t, p) }
        escopo.launch { kotlinx.coroutines.delay(ms); runCatching { janelas.removeView(t) } }
    }
}
