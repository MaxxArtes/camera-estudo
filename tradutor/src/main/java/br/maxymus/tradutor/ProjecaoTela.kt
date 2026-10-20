package br.maxymus.tradutor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Um MediaProjection e um VirtualDisplay por sessão; todos os callbacks usam a thread principal. */
internal class ProjecaoTela(
    private val ctx: Context, private val aoParar: () -> Unit, private val aoRedimensionar: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var projecao: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var leitor: ImageReader? = null
    private var largura = 0
    private var altura = 0
    private var ultimoTimestamp = 0L
    private var fechada = false
    private class Pedido(val portao: PortaoQuadro) {
        val resultado = CompletableDeferred<Unit>()
        var bitmap: Bitmap? = null
    }
    private var pedido: Pedido? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (fechada) return
            fechar()
            aoParar()
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (fechada || display == null) return
            runCatching { redimensionar(width, height) }.onFailure { fechar(); aoParar() }
        }
    }

    fun iniciar(codigo: Int, dados: Intent) {
        check(projecao == null && !fechada)
        val (w, h) = dimensoes()
        largura = w; altura = h
        val mp = (ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(codigo, dados) ?: error("Projeção indisponível")
        projecao = mp
        // Android 14 exige o callback registrado ANTES do único createVirtualDisplay.
        mp.registerCallback(callback, handler)
        val imagens = novoLeitor(w, h)
        leitor = imagens
        display = mp.createVirtualDisplay("Tradutor: captura manual", w, h, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imagens.surface, null, handler)
            ?: error("Tela de captura indisponível")
    }

    @Suppress("DEPRECATION")
    private fun dimensoes(): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    fun configuracaoMudou() {
        if (fechada || display == null) return
        val (w, h) = dimensoes()
        redimensionar(w, h)
    }

    private fun redimensionar(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == largura && h == altura)) return
        pedido?.resultado?.completeExceptionally(IllegalStateException("A tela girou. Capture novamente."))
        pedido = null
        aoRedimensionar()
        val antigo = leitor
        val novo = novoLeitor(w, h)
        leitor = novo
        // A rotação troca a superfície e redimensiona o MESMO VirtualDisplay.
        try {
            display?.resize(w, h, ctx.resources.displayMetrics.densityDpi)
            display?.surface = novo.surface
            largura = w; altura = h
        } finally {
            antigo?.setOnImageAvailableListener(null, null)
            antigo?.close()
        }
    }

    private fun novoLeitor(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ fonte ->
                // Consome e fecha inclusive quando ninguém pediu captura: nunca guarda a tela anterior.
                if (fonte !== leitor || fechada) return@setOnImageAvailableListener
                runCatching {
                    fonte.acquireLatestImage()?.use { imagem ->
                        ultimoTimestamp = maxOf(ultimoTimestamp, imagem.timestamp)
                        val alvo = pedido
                        if (alvo != null && alvo.portao.aceitar(imagem.timestamp)) {
                            val bitmap = copiar(imagem)
                            alvo.bitmap = bitmap
                            if (!alvo.resultado.complete(Unit)) { bitmap.recycle(); alvo.bitmap = null }
                        }
                    }
                }.onFailure {
                    pedido?.resultado?.completeExceptionally(IllegalStateException("Não consegui ler o quadro da captura.", it))
                }
            }, handler)
        }

    /** Chamado somente depois de esconder todas as janelas do tradutor e esperar 90 ms. */
    suspend fun capturar(): Bitmap? {
        check(!fechada && pedido == null)
        val fonte = checkNotNull(leitor)
        fonte.acquireLatestImage()?.use { ultimoTimestamp = maxOf(ultimoTimestamp, it.timestamp) }
        // A superfície nova força o compositor a produzir inclusive uma tela estática. Só drenar a fila
        // poderia esperar para sempre por outra mudança na tela. O VirtualDisplay continua sendo o mesmo.
        val novo = novoLeitor(largura, altura)
        val alvo = Pedido(PortaoQuadro(ultimoTimestamp, superficieNova = true))
        pedido = alvo
        var entregue = false
        try {
            leitor = novo
            try { checkNotNull(display).surface = novo.surface }
            finally { fonte.setOnImageAvailableListener(null, null); fonte.close() }
            val bitmap = withTimeoutOrNull(3_000) { alvo.resultado.await(); alvo.bitmap }
            entregue = bitmap != null
            return bitmap
        } finally {
            if (pedido === alvo) pedido = null
            // Se o cancelamento ganhou da entrega no mesmo quadro, a imagem também precisa ser liberada.
            if (!entregue) alvo.bitmap?.recycle()
            alvo.bitmap = null
            alvo.resultado.cancel()
        }
    }

    private fun copiar(imagem: Image): Bitmap {
        val plano = imagem.planes[0]
        check(plano.pixelStride == 4)
        val w = imagem.width; val h = imagem.height
        val buffer = plano.buffer
        val inicio = buffer.position()
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = inicio + y * plano.rowStride + x * plano.pixelStride
            pixels[y * w + x] = (0xFF shl 24) or ((buffer.get(i).toInt() and 255) shl 16) or
                ((buffer.get(i + 1).toInt() and 255) shl 8) or (buffer.get(i + 2).toInt() and 255)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun fechar() {
        if (fechada) return
        fechada = true
        pedido?.resultado?.cancel(); pedido = null
        val mp = projecao; projecao = null
        runCatching { mp?.unregisterCallback(callback) }
        runCatching { display?.release() }; display = null
        runCatching { leitor?.setOnImageAvailableListener(null, null) }
        runCatching { leitor?.close() }; leitor = null
        runCatching { mp?.stop() }
    }
}
