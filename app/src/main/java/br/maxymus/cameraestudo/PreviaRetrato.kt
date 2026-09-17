package br.maxymus.cameraestudo

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Prévia AO VIVO do retrato por software (pedido do dono, 17/09: o bokeh do aparelho muda na hora, o nosso só depois da
 * foto). Cada quadro do fluxo de análise (~640x480, RGBA) passa pelo segmentador em modo contínuo, o fundo recebe um
 * borrão de caixa com o raio da régua e a composição é desenhada por cima da prévia. É só uma amostra do resultado:
 * a foto final usa o disco em luz linear do Retrato.kt.
 */
object PreviaRetrato {
    private var segmentador: Segmenter? = null
    private fun seg(): Segmenter = segmentador ?: Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).enableRawSizeMask().build()).also { segmentador = it }
    fun fechar() { segmentador?.close(); segmentador = null }

    /** px: quadro RGBA já como ARGB ints. raio em pixels deste quadro. Devolve o quadro composto ou null se a segmentação falhar. */
    fun quadro(px: IntArray, w: Int, h: Int, raio: Int): Bitmap? = runCatching {
        val entrada = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val m = Tasks.await(seg().process(InputImage.fromBitmap(entrada, 0)))
        val mw = m.width; val mh = m.height; val bb = m.buffer; bb.rewind(); val conf = FloatArray(mw * mh) { bb.float }
        val fundo = caixa(px, w, h, raio)
        val saida = IntArray(w * h)
        for (y in 0 until h) { val my = (y * mh / h).coerceIn(0, mh - 1)
            for (x in 0 until w) { val mx = (x * mw / w).coerceIn(0, mw - 1)
                val c = conf[my * mw + mx]; val a = ((c - 0.15f) / 0.7f).coerceIn(0f, 1f); val a2 = a * a * (3 - 2 * a)
                val i = y * w + x; val f = px[i]; val b = fundo[i]
                val r = ((f shr 16 and 255) * a2 + (b shr 16 and 255) * (1 - a2)).toInt(); val g = ((f shr 8 and 255) * a2 + (b shr 8 and 255) * (1 - a2)).toInt(); val bl = ((f and 255) * a2 + (b and 255) * (1 - a2)).toInt()
                saida[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl } }
        entrada.recycle()
        Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Borrão de caixa separável por canal (duas passagens = quase gaussiano). */
    private fun caixa(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        fun passa(src: IntArray, horizontal: Boolean): IntArray {
            val dst = IntArray(src.size); val n = if (horizontal) w else h; val m = if (horizontal) h else w
            for (linha in 0 until m) {
                var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                fun idx(k: Int) = if (horizontal) linha * w + k else k * w + linha
                for (k in 0 until min(r, n - 1)) { val c = src[idx(k)]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; cnt++ }
                for (k in 0 until n) {
                    val e = k + r; if (e < n) { val c = src[idx(e)]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; cnt++ }
                    val s = k - r - 1; if (s >= 0) { val c = src[idx(s)]; sr -= c shr 16 and 255; sg -= c shr 8 and 255; sb -= c and 255; cnt-- }
                    dst[idx(k)] = (0xFF shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
                }
            }
            return dst
        }
        val uma = passa(passa(px, true), false)
        return passa(passa(uma, true), false)
    }
}
