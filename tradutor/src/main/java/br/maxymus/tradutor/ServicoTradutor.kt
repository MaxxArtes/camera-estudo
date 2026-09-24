package br.maxymus.tradutor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * traduzida por cima de tudo e qualquer toque fecha. Isso evita o desalinhamento entre desenho e texto quando a
 * pessoa rola, que seria o defeito mais visível de uma sobreposição viva.
 */
class ServicoTradutor : AccessibilityService() {

    companion object {
        @Volatile var ativo: ServicoTradutor? = null
        const val ACAO_TRADUZIR = "br.maxymus.tradutor.TRADUZIR"
        const val ACAO_BOLHA = "br.maxymus.tradutor.BOLHA"
        const val ACAO_PREPARAR = "br.maxymus.tradutor.PREPARAR"
        private const val CANAL = "tradutor"
        private const val AVISO = 1
    }

    /** Recebe os toques da notificação fixa. */
    private val receptor = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            when (i.action) {
                ACAO_TRADUZIR -> if (continuo) encerraContinuo() else iniciaContinuo()
                ACAO_BOLHA -> { if (bolha == null) mostraBolha() else tiraBolha(); notificacao() }
                ACAO_PREPARAR -> {
                    preparar = !preparar
                    getSharedPreferences("tradutor", Context.MODE_PRIVATE).edit().putBoolean("preparar", preparar).apply()
                    Telemetria.evento("preparar", mapOf("ligado" to preparar)); notificacao()
                }
            }
        }
    }

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var janelas: WindowManager
    private var bolha: View? = null
    private var sobreposicao: View? = null
    private var trabalhando = false
    private var msOcr = 0L; private var msTrad = 0L; private var msPint = 0L
    private var preparar = false
    private var ultimaRolagem = 0L
    private var ultimaAssinatura = 0L
    private var jobPreparo: kotlinx.coroutines.Job? = null
    private var continuo = false          // sobreposição que acompanha a rolagem, em vez da tela congelada
    private var fechar: View? = null      // o X, janela própria porque a camada não recebe toque
    private var menu: View? = null
    private var adiantando = false
    private var bx = 0; private var by = 0

    override fun onServiceConnected() {
        ativo = this
        janelas = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Telemetria.iniciar(this)
        val filtro = android.content.IntentFilter().apply { addAction(ACAO_TRADUZIR); addAction(ACAO_BOLHA); addAction(ACAO_PREPARAR) }
        androidx.core.content.ContextCompat.registerReceiver(this, receptor, filtro, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        preparar = getSharedPreferences("tradutor", Context.MODE_PRIVATE).getBoolean("preparar", true)
        Traducao.carregarPreferencias(this)
        mostraBolha()
        notificacao()
    }

    override fun onDestroy() {
        ativo = null; continuo = false; tiraSobreposicao(); tiraBolha(); tiraMenu()
        fechar?.let { runCatching { janelas.removeView(it) } }; fechar = null
        runCatching { unregisterReceiver(receptor) }
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(AVISO) }
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
            .setContentText((if (bolha != null) "Toque em Traduzir, ou use a bolha" else "Bolha escondida") +
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
        if (e == null || (!preparar && !continuo)) return
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED && e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        ultimaRolagem = System.currentTimeMillis()
        if (jobPreparo?.isActive == true) return
        jobPreparo = escopo.launch {
            while (System.currentTimeMillis() - ultimaRolagem < 700) kotlinx.coroutines.delay(250)
            if (trabalhando) return@launch
            if (continuo) desenhaContinuo()
            else if (preparar && sobreposicao == null) preparaEmSilencio()
        }
    }

    private suspend fun preparaEmSilencio() {
        if (!Traducao.temRede(this)) return
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
            if (falas.isEmpty()) 0 else { Traducao.traduzirLote(this@ServicoTradutor, falas.map { it.texto }); falas.size }
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
                    else if (continuo) encerraContinuo() else iniciaContinuo()
                    true
                }
                else -> false
            }
        }
        runCatching { janelas.addView(v, p); bolha = v }
    }

    private fun tiraBolha() { bolha?.let { runCatching { janelas.removeView(it) } }; bolha = null }

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
        continuo = true
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
        if (!continuo || trabalhando) return
        trabalhando = true
        sobreposicao?.visibility = View.INVISIBLE
        bolha?.visibility = View.INVISIBLE
        fechar?.visibility = View.INVISIBLE
        kotlinx.coroutines.delay(90)
        val tela = captura()
        bolha?.visibility = View.VISIBLE
        fechar?.visibility = View.VISIBLE
        if (tela == null || !continuo) { trabalhando = false; sobreposicao?.visibility = View.VISIBLE; return }
        val assin = assinaturaDe(tela)
        if (assin == ultimaAssinatura && sobreposicao != null) { trabalhando = false; sobreposicao?.visibility = View.VISIBLE; return }
        ultimaAssinatura = assin
        // DUAS PASSADAS, para a tela não ficar esperando tradução: primeiro desenha o que já está no cache
        // (medido: 97 ms de leitura + 174 ms de desenho), depois busca o que falta e redesenha. Com o capítulo
        // adiantado, a primeira passada já é a final.
        val falas = withContext(Dispatchers.Default) {
            Falas.ler(tela, (tela.height * 0.11f).toInt(), (tela.height * 0.96f).toInt())
        }
        if (falas.isEmpty()) { trabalhando = false; sobreposicao?.visibility = View.VISIBLE; return }
        val textos = falas.map { it.texto }
        val conhecido = withContext(Dispatchers.Default) { Traducao.soCache(this@ServicoTradutor, textos) }
        if (conhecido.isNotEmpty() && continuo)
            mostraCamada(withContext(Dispatchers.Default) { Pintura.camada(tela, falas) { conhecido[it] ?: it } })
        val mapa = withContext(Dispatchers.Default) { Traducao.traduzirLote(this@ServicoTradutor, textos) }
        trabalhando = false
        if (!continuo) return
        if (mapa != conhecido || conhecido.isEmpty())
            mostraCamada(withContext(Dispatchers.Default) { Pintura.camada(tela, falas) { mapa[it] ?: it } })
        Telemetria.evento("traduziu", mapOf("modo" to "continuo", "falas" to falas.size,
            "ja_no_cache" to conhecido.size, "caminho" to Traducao.ultimoCaminho,
            "origem" to (Traducao.ultimaOrigem ?: "?"), "modelo" to Traducao.ultimoModelo))
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
        val lado = dp(40)
        val v = TextView(this).apply {
            text = "✕"; setTextColor(0xFF111114.toInt()); textSize = 17f; gravity = Gravity.CENTER
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
            val tela = captura()
            bolha?.visibility = View.VISIBLE
            if (tela == null) break
            val assin = assinaturaDe(tela)
            if (assin == ultimaAssinatura) { iguais++; if (iguais >= 2) break } else iguais = 0
            ultimaAssinatura = assin
            withContext(Dispatchers.Default) {
                val falas = Falas.ler(tela, (tela.height * 0.11f).toInt(), (tela.height * 0.96f).toInt())
                if (falas.isNotEmpty()) Traducao.traduzirLote(this@ServicoTradutor, falas.map { it.texto })
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
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { tiraMenu(); acao() }
        }
        val caixa = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF252529.toInt()); cornerRadius = dp(14).toFloat()
            }
            addView(item("Traduzir a tela toda (parada)") { traduzTela() })
            addView(item(if (adiantando) "Parar de adiantar o capítulo" else "Adiantar o capítulo inteiro") {
                if (adiantando) adiantando = false else escopo.launch { adiantaCapitulo() }
            })
            addView(item("Idiomas") {
                startActivity(android.content.Intent(this@ServicoTradutor, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("abrir", "idiomas"))
            })
            addView(item("Fechar") { })
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

    private fun traduzTela() {
        if (trabalhando) return
        if (continuo) encerraContinuo()   // as duas camadas não podem existir juntas
        trabalhando = true
        escopo.launch {
            // A captura pega a tela inteira, inclusive a bolha. Mudar o alfa NAO basta: a janela so some depois de
            // desenhar o proximo quadro. Sem esta espera a bolha aparece congelada dentro da propria traducao.
            bolha?.visibility = View.INVISIBLE
            kotlinx.coroutines.delay(90)
            val tela = captura()
            bolha?.visibility = View.VISIBLE
            if (tela == null) { trabalhando = false; aviso("Não consegui capturar a tela."); return@launch }
            // fora as barras do sistema e do navegador: sem isto o app le a barra de endereço e gasta tradução
            val topo = (tela.height * 0.11f).toInt(); val base = (tela.height * 0.96f).toInt()
            val t0 = System.nanoTime()
            val camada = withContext(Dispatchers.Default) {
                val tOcr = System.nanoTime()
                val falas = Falas.ler(tela, topo, base)
                msOcr = (System.nanoTime() - tOcr) / 1_000_000
                if (falas.isEmpty()) null
                else {
                    val tTrad = System.nanoTime()
                    val mapa = Traducao.traduzirLote(this@ServicoTradutor, falas.map { it.texto })
                    msTrad = (System.nanoTime() - tTrad) / 1_000_000
                    val tPint = System.nanoTime()
                    val c = Pintura.camada(tela, falas) { mapa[it] ?: it }
                    msPint = (System.nanoTime() - tPint) / 1_000_000
                    c to falas.size
                }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            trabalhando = false
            if (camada == null) { aviso("Não encontrei texto. Mova um pouco a página e tente de novo."); Telemetria.evento("traduziu", mapOf("falas" to 0, "ms" to ms)); return@launch }
            Telemetria.evento("traduziu", mapOf("falas" to camada.second, "ms" to ms, "cache" to Traducao.noCache, "caminho" to Traducao.ultimoCaminho, "origem" to (Traducao.ultimaOrigem ?: "?"), "online" to Traducao.ultimoOnline, "offline" to Traducao.ultimoOffline, "modelo" to Traducao.ultimoModelo, "ms_ocr" to msOcr, "ms_trad" to msTrad, "ms_pint" to msPint))
            mostraSobreposicao(tela, camada.first)
        }
    }

    private suspend fun captura(): Bitmap? = withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            runCatching {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val b = runCatching {
                            Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        runCatching { r.hardwareBuffer.close() }
                        if (cont.isActive) cont.resume(b) {}
                    }
                    override fun onFailure(code: Int) {
                        Telemetria.evento("erro", mapOf("onde" to "captura", "codigo" to code))
                        if (cont.isActive) cont.resume(null) {}
                    }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) {} }
        }
    }

    private fun mostraSobreposicao(tela: Bitmap, camada: Bitmap) {
        tiraSobreposicao()
        val caixa = FrameLayout(this)
        caixa.addView(ImageView(this).apply { setImageBitmap(tela); scaleType = ImageView.ScaleType.FIT_XY })
        caixa.addView(ImageView(this).apply { setImageBitmap(camada); scaleType = ImageView.ScaleType.FIT_XY })
        caixa.addView(TextView(this).apply {
            text = "Toque para voltar à página"
            setTextColor(0xFFF5F5F5.toInt()); textSize = 13f
            setBackgroundColor(0xCC111114.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(28)
        })
        // o toque que fecha é CONSUMIDO: não pode vazar e clicar em algo do navegador por baixo
        caixa.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) tiraSobreposicao(); true }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE)
        runCatching { janelas.addView(caixa, p); sobreposicao = caixa }
        // a bolha precisa ficar POR CIMA da sobreposição; remover e recriar deixava duas na tela
        bolha?.let { b -> runCatching { janelas.removeViewImmediate(b) }; bolha = null }
        mostraBolha()
    }

    private fun tiraSobreposicao() { sobreposicao?.let { runCatching { janelas.removeView(it) } }; sobreposicao = null }

    private fun aviso(texto: String) {
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
        escopo.launch { kotlinx.coroutines.delay(3000); runCatching { janelas.removeView(t) } }
    }
}
