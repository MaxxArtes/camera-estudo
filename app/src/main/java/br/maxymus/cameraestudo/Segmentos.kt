package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Segmentação multiclasse de pessoa (MediaPipe "selfie multiclass", Apache-2.0, TFLite puro): 256x256 → 6 canais
 * [fundo, cabelo, pele do corpo, pele do rosto, roupa, acessórios]. É a máscara "Pessoas" do Lightroom com as
 * sub-máscaras. Pedido do dono (17/09). Substitui o tom de pele por faixa de cor no embelezador e o alfa do retrato
 * ganha o cabelo. Amostragem bilinear nas coordenadas normalizadas.
 */
object Segmentos {
    const val FUNDO = 0; const val CABELO = 1; const val PELE_CORPO = 2; const val PELE_ROSTO = 3; const val ROUPA = 4; const val OUTROS = 5
    private const val N = 256
    private var interp: Interpreter? = null
    @Volatile var ultimoMs = -1L; @Volatile var ultimoErro: String? = null

    class Mapa(val cats: FloatArray) {   // N*N*6
        fun em(c: Int, x: Float, y: Float): Float {   // bilinear em 0..1
            val fx = (x * (N - 1)).coerceIn(0f, N - 1f); val fy = (y * (N - 1)).coerceIn(0f, N - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(N - 1, x0 + 1); val y1 = min(N - 1, y0 + 1); val tx = fx - x0; val ty = fy - y0
            fun v(xx: Int, yy: Int) = cats[(yy * N + xx) * 6 + c]
            return (v(x0, y0) * (1 - tx) + v(x1, y0) * tx) * (1 - ty) + (v(x0, y1) * (1 - tx) + v(x1, y1) * tx) * ty
        }
        fun pessoa(x: Float, y: Float) = 1f - em(FUNDO, x, y)
        fun pele(x: Float, y: Float) = em(PELE_CORPO, x, y) + em(PELE_ROSTO, x, y)
    }

    private fun modelo(ctx: Context): Interpreter {
        interp?.let { return it }
        val fd = ctx.assets.openFd("selfie_multiclass_256x256.tflite")
        val mapa = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(mapa, Interpreter.Options().setNumThreads(2)).also { interp = it }
    }

    fun segmentar(ctx: Context, b: Bitmap): Mapa? = runCatching {
        val t = System.nanoTime()
        val peq = Bitmap.createScaledBitmap(b, N, N, true)
        val px = IntArray(N * N).also { peq.getPixels(it, 0, N, 0, 0, N, N) }; if (peq !== b) peq.recycle()
        val entrada = ByteBuffer.allocateDirect(N * N * 3 * 4).order(ByteOrder.nativeOrder())
        for (c in px) { entrada.putFloat((c shr 16 and 255) / 255f); entrada.putFloat((c shr 8 and 255) / 255f); entrada.putFloat((c and 255) / 255f) }
        entrada.rewind()
        val saida = ByteBuffer.allocateDirect(N * N * 6 * 4).order(ByteOrder.nativeOrder())
        synchronized(this) { modelo(ctx).run(entrada, saida) }
        saida.rewind(); val cats = FloatArray(N * N * 6); saida.asFloatBuffer().get(cats)
        ultimoMs = (System.nanoTime() - t) / 1_000_000; ultimoErro = null
        Mapa(cats)
    }.getOrElse { e -> ultimoErro = (e::class.java.simpleName + ": " + (e.message ?: "")).take(200); null }

    /** Máscara de pessoa no tamanho w x h (bilinear), para quem trabalha com arrays. */
    fun mascaraPessoa(m: Mapa, w: Int, h: Int): FloatArray = FloatArray(w * h) { k -> m.pessoa((k % w) / (w - 1f), (k / w) / (h - 1f)) }
    fun mascaraPele(m: Mapa, w: Int, h: Int): FloatArray = FloatArray(w * h) { k -> m.pele((k % w) / (w - 1f), (k / w) / (h - 1f)) }
}
