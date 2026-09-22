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
        const val ACAO_LIGAR = "br.maxymus.tradutor.LIGAR"
    }

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var janelas: WindowManager
    private var bolha: View? = null
    private var sobreposicao: View? = null
    private var trabalhando = false
    private var bx = 0; private var by = 0

    override fun onServiceConnected() {
        ativo = this
        janelas = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Telemetria.iniciar(this)
        mostraBolha()
    }

    override fun onDestroy() { ativo = null; tiraSobreposicao(); tiraBolha(); super.onDestroy() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
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
        var x0 = 0f; var y0 = 0f; var px0 = 0; var py0 = 0; var arrastou = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; px0 = p.x; py0 = p.y; arrastou = false; true }
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
                    } else traduzTela()
                    true
                }
                else -> false
            }
        }
        runCatching { janelas.addView(v, p); bolha = v }
    }

    private fun tiraBolha() { bolha?.let { runCatching { janelas.removeView(it) } }; bolha = null }

    // ---------------- tradução ----------------

    private fun traduzTela() {
        if (trabalhando) return
        if (!Traducao.pacotePronto) { aviso("Idioma ainda baixando. Tente quando terminar."); return }
        trabalhando = true
        bolha?.alpha = 0f      // a captura pega a tela inteira, inclusive a bolha: some antes (alerta do Astra)
        escopo.launch {
            val tela = captura()
            bolha?.alpha = 1f
            if (tela == null) { trabalhando = false; aviso("Não consegui capturar a tela."); return@launch }
            val topo = dp(0); val base = tela.height
            val t0 = System.nanoTime()
            val camada = withContext(Dispatchers.Default) {
                val falas = Falas.ler(tela, topo, base)
                if (falas.isEmpty()) null
                else Pintura.camada(tela, falas) { Traducao.traduzir(it) } to falas.size
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            trabalhando = false
            if (camada == null) { aviso("Não encontrei texto. Mova um pouco a página e tente de novo."); Telemetria.evento("traduziu", mapOf("falas" to 0, "ms" to ms)); return@launch }
            Telemetria.evento("traduziu", mapOf("falas" to camada.second, "ms" to ms, "cache" to Traducao.noCache))
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
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
        })
        // o toque que fecha é CONSUMIDO: não pode vazar e clicar em algo do navegador por baixo
        caixa.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) tiraSobreposicao(); true }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE)
        runCatching { janelas.addView(caixa, p); sobreposicao = caixa }
        bolha?.let { runCatching { janelas.removeView(it) }; bolha = null }
        mostraBolha()   // a bolha volta por cima da sobreposição
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
