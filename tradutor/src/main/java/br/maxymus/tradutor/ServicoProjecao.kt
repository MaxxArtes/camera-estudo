package br.maxymus.tradutor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Captura manual congelada. Não recebe eventos de rolagem e não injeta gestos. */
class ServicoProjecao : Service() {
    companion object {
        var ativo: ServicoProjecao? = null
            private set
        const val INICIAR = "br.maxymus.tradutor.INICIAR_SESSAO"
        const val ENCERRAR = "br.maxymus.tradutor.ENCERRAR_SESSAO"
        private const val CANAL = "captura_manual"
        private const val NOTIFICACAO = 34
    }
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val janelas by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var projecao: ProjecaoTela? = null
    private var encerrada = false
    private var donaDaSessao = false
    private var bolha: View? = null
    private var painel: View? = null
    private var original: Bitmap? = null
    private var camada: Bitmap? = null
    private var imagemCamada: ImageView? = null
    private var pintadas: List<Pintada> = emptyList()
    private var botaoVoz: TextView? = null
    private var trabalho: Job? = null
    private var revisao: Job? = null
    private var geracaoTela = 0
    private val vozLazy = lazy {
        Voz(this, { texto, _ -> avisar(texto) },
            { avisar("Instale uma voz de português do Brasil nas configurações de voz do aparelho.") },
            { botaoVoz?.text = if (voz.lendo) "Parar voz" else "Ouvir" })
    }
    private val voz: Voz get() = vozLazy.value
    private val observador = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { conferirModo() }
    }
    private val telaDesligada = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            encerrar(true, "A tela foi bloqueada. Inicie uma nova sessão para capturar.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        EstadoCaptura.iniciar(this)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observador)
        androidx.core.content.ContextCompat.registerReceiver(this, telaDesligada,
            android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ENCERRAR) {
            encerrar(false, "Sessão encerrada.")
            return START_NOT_STICKY
        }
        // Um segundo comando não cria outra projeção nem outro VirtualDisplay.
        if (projecao != null || encerrada) return START_NOT_STICKY
        val pedido = intent?.getLongExtra("pedido", -1) ?: -1
        if (intent?.action != INICIAR || !EstadoCaptura.aguardando(pedido)) {
            // Um comando atrasado não pode cancelar o pedido novo que já está na Activity.
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        donaDaSessao = true
        try {
            check(!EstadoCaptura.acessibilidadeLigada(this)) { "A acessibilidade está ligada e tem prioridade." }
            check(Settings.canDrawOverlays(this)) { "Autorize a sobreposição para usar a bolha." }
            val dados = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("consentimento", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("consentimento")
            checkNotNull(dados) { "É necessário um novo consentimento de captura." }
            val codigo = intent.getIntExtra("resultado", 0)
            check(codigo == android.app.Activity.RESULT_OK)
            // Foreground vem antes de obter a MediaProjection, após o consentimento na Activity visível.
            val n = notificacao()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICACAO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICACAO, n)
            Telemetria.iniciar(this)
            Traducao.carregarPreferencias(this)
            Traducao.iniciarCacheDisco(this)
            val captura = ProjecaoTela(this,
                { encerrar(true, "O sistema encerrou a captura. Inicie uma nova sessão e autorize novamente.") },
                {
                    invalidarTela()
                    avisar("A tela mudou de tamanho. Toque na bolha para capturar novamente.")
                })
            projecao = captura
            captura.iniciar(codigo, dados)
            check(EstadoCaptura.ativar(this, pedido))
            ativo = this
            mostrarBolha()
            escopo.launch {
                while (isActive) {
                    delay(1_000)
                    conferirModo()
                }
            }
        } catch (_: Exception) {
            encerrar(true, "Não foi possível iniciar a captura. Confira a sobreposição e peça uma nova sessão.")
        }
        return START_NOT_STICKY
    }

    private fun notificacao(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CANAL, "Sessão de captura manual", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 34, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val parar = PendingIntent.getService(this, 34, Intent(this, ServicoProjecao::class.java).setAction(ENCERRAR),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Sessão de captura ativa")
            .setContentText("Toque na bolha para traduzir uma tela. Encerre quando terminar.")
            .setContentIntent(abrir).setOngoing(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Encerrar sessão", parar).build())
            .build()
    }

    private fun conferirModo() {
        if (encerrada) return
        if (EstadoCaptura.modo(this) == ModoCaptura.ACESSIBILIDADE)
            encerrar(false, "Acessibilidade avançada ativa: a sessão manual foi encerrada.")
        else if (!Settings.canDrawOverlays(this))
            encerrar(true, "A permissão de sobreposição foi retirada. Autorize-a antes de iniciar outra sessão.")
    }

    fun encerrar(peloSistema: Boolean, mensagem: String) {
        if (encerrada) return
        encerrada = true
        if (ativo === this) ativo = null
        EstadoCaptura.encerrar(this, peloSistema, mensagem)
        invalidarTela()
        remover(bolha); bolha = null
        projecao?.fechar(); projecao = null
        if (vozLazy.isInitialized()) voz.desligar()
        escopo.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!encerrada && donaDaSessao) encerrar(true, "A sessão foi interrompida. Inicie uma nova sessão para capturar.")
        escopo.cancel()
        contentResolver.unregisterContentObserver(observador)
        runCatching { unregisterReceiver(telaDesligada) }
        Traducao.descarregarCacheDisco()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { projecao?.configuracaoMudou() }.onFailure {
            encerrar(true, "Não consegui ajustar a captura à rotação. Inicie outra sessão.")
        }
        bolha?.let { v ->
            val p = v.layoutParams as WindowManager.LayoutParams
            p.x = 0; p.y = dp(140)
            runCatching { janelas.updateViewLayout(v, p) }
        }
    }

    fun leitorMudou() {
        if (LeitorActivity.visivel) invalidarTela()
        bolha?.visibility = if (LeitorActivity.visivel) View.INVISIBLE else View.VISIBLE
    }
    private fun invalidarTela() {
        geracaoTela++
        trabalho?.cancel()
        fecharPainel()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun remover(v: View?) { v?.let { runCatching { janelas.removeViewImmediate(it) } } }
    private fun parametros(w: Int, h: Int) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT)
    private fun botao(rotulo: String, acao: () -> Unit) = TextView(this).apply {
        text = rotulo; contentDescription = rotulo; textSize = 15f
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF252529.toInt())
        gravity = Gravity.CENTER; minHeight = dp(52); setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { acao() }
    }

    private fun mostrarBolha() {
        if (bolha != null || encerrada) return
        val v = botao("Traduzir") {
            if (vozLazy.isInitialized() && voz.lendo) voz.parar(RegraVoz.TOQUE_BOLHA)
            else if (painel != null) fecharPainel() else capturar()
        }
        val p = parametros(dp(96), dp(56)).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 0; y = dp(140) }
        var x0 = 0f; var y0 = 0f; var px = 0; var py = 0; var arrastou = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; px = p.x; py = p.y; arrastou = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - x0) + abs(e.rawY - y0) > dp(8)) arrastou = true
                    if (arrastou) {
                        p.x = (px + e.rawX - x0).roundToInt().coerceIn(0, maxOf(0, resources.displayMetrics.widthPixels - dp(96)))
                        p.y = (py + e.rawY - y0).roundToInt().coerceIn(0, maxOf(0, resources.displayMetrics.heightPixels - dp(56)))
                        runCatching { janelas.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!arrastou) v.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        janelas.addView(v, p); bolha = v
        if (LeitorActivity.visivel) v.visibility = View.INVISIBLE
    }

    private fun capturar() {
        conferirModo()
        // Cancelamento durante OCR bloqueante só termina ao sair do trabalhador. Até lá, não começar
        // outra captura: o finally antigo poderia tornar a bolha visível durante a espera da nova.
        if (encerrada || LeitorActivity.visivel || trabalho?.isCompleted == false || painel != null) return
        val fonte = projecao ?: return
        val geracao = geracaoTela
        trabalho = escopo.launch {
            var tela: Bitmap? = null
            var processada: FluxoCongelado.Processada? = null
            var mostrada = false
            try {
                bolha?.visibility = View.INVISIBLE
                delay(90) // preserva a espera pelo compositor da 0.33, antes de drenar o ImageReader
                val b = fonte.capturar()
                tela = b
                checkNotNull(b) { "Nenhum quadro novo chegou. Toque novamente; se persistir, reinicie a sessão." }
                bolha?.visibility = View.VISIBLE
                val topo = (b.height * 0.11f).toInt(); val base = (b.height * 0.96f).toInt()
                val preta = withContext(Dispatchers.Default) { QuadroPreto.rejeitar(b.width, topo, base, b::getPixel) }
                check(!preta) { "A captura ficou preta. Pode ser bloqueio de captura ou uma tela escura. Tente outra tela." }
                val referencia = Traducao.correcoes.get()
                withContext(Dispatchers.Default) {
                    processada = FluxoCongelado.processar(this@ServicoProjecao, b, topo, base) { isActive }
                }
                if (encerrada || geracao != geracaoTela || LeitorActivity.visivel) return@launch
                val r = checkNotNull(processada)
                val congelada = r.congelada
                if (congelada == null) {
                    avisar(when {
                        r.ocrFalhou -> "Não consegui reconhecer o texto. Capture novamente."
                        r.tudoNoIdioma -> "Esta tela já está no seu idioma."
                        r.fracaoSuspeita != null -> "A captura ficou escura e sem texto. Tente outra tela ou use o leitor."
                        else -> "Não encontrei texto nesta captura. Mova a página e tente novamente."
                    })
                } else {
                    mostrarPainel(b, congelada)
                    mostrada = true
                    revisar(b, congelada.falas, referencia)
                    FluxoCongelado.ouvir(voz, pintadas, RegraVoz.BOLHA)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                avisar(e.message ?: "Não consegui capturar esta tela. Tente novamente.")
            } finally {
                if (!mostrada) { tela?.recycle(); processada?.congelada?.camada?.recycle() }
                bolha?.visibility = if (LeitorActivity.visivel || encerrada) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun mostrarPainel(tela: Bitmap, resultado: Congelada) {
        fecharPainel()
        val caixa = FrameLayout(this)
        caixa.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        caixa.addView(ImageView(this).apply { setImageBitmap(tela); scaleType = ImageView.ScaleType.FIT_XY },
            FrameLayout.LayoutParams(-1, -1))
        val imagem = ImageView(this).apply { setImageBitmap(resultado.camada); scaleType = ImageView.ScaleType.FIT_XY }
        caixa.addView(imagem, FrameLayout.LayoutParams(-1, -1))
        val rodape = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(botao("Voltar à página") { fecharPainel() }, LinearLayout.LayoutParams(0, -2, 1f))
            val ouvir = botao("Ouvir") {
                if (voz.lendo) voz.parar(RegraVoz.PARAR) else FluxoCongelado.ouvir(voz, pintadas, RegraVoz.CONGELADA)
            }
            botaoVoz = ouvir
            addView(ouvir, LinearLayout.LayoutParams(0, -2, 1f))
        }
        caixa.addView(rodape, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = dp(36); leftMargin = dp(12); rightMargin = dp(12)
        })
        caixa.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) fecharPainel(); true }
        val p = parametros(tela.width, tela.height).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= 30) {
                // A imagem usa coordenadas físicas da tela inteira, inclusive as áreas das barras.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        janelas.addView(caixa, p)
        painel = caixa; original = tela; camada = resultado.camada; imagemCamada = imagem; pintadas = resultado.pintadas
        // A bolha fica acima da congelada; o restante da janela consome os toques.
        bolha?.let { v ->
            val parametros = v.layoutParams
            runCatching { janelas.removeViewImmediate(v); janelas.addView(v, parametros) }
        }
    }

    private fun revisar(tela: Bitmap, falas: List<Falas.Fala>, referencia: Int) {
        val alvo = imagemCamada ?: return
        revisao = escopo.launch {
            var vista = referencia; var restantes = 24
            while (restantes > 0) {
                delay(500)
                if (imagemCamada !== alvo) return@launch
                if (vozLazy.isInitialized() && voz.lendo) continue
                restantes--
                val atual = Traducao.correcoes.get()
                if (atual == vista) continue
                var nova: Bitmap? = null
                var novas: List<Pintada> = emptyList()
                try {
                    withContext(Dispatchers.Default) {
                        novas = FluxoCongelado.pintadas(falas, Traducao.soCache(this@ServicoProjecao, falas.map { it.texto }))
                        nova = FluxoCongelado.pintar(tela, novas)
                    }
                    if (imagemCamada !== alvo || voz.lendo) continue
                    alvo.setImageBitmap(nova)
                    camada?.recycle(); camada = nova; nova = null
                    pintadas = novas; vista = atual
                } finally { nova?.recycle() }
            }
        }
    }

    private fun fecharPainel() {
        revisao?.cancel(); revisao = null
        if (vozLazy.isInitialized()) voz.parar(RegraVoz.TELA_FECHADA)
        remover(painel); painel = null; imagemCamada = null; botaoVoz = null; pintadas = emptyList()
        // A revisão pode estar lendo o original em outra thread; o GC libera o bitmap ao terminar.
        original = null
        camada = null
    }
    private fun avisar(texto: String) {
        EstadoCaptura.avisar(texto)
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    }
}
