package br.maxymus.galeriaestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Identidade facial no aparelho: MobileFaceNet TFLite (BSD-3), recorte 112x112 alinhado pelos olhos,
 * vetor de 192 números normalizado; semelhança = cosseno. Igual ao Pessoas.kt da câmera, que validou
 * nas fotos do dono: mesma pessoa ≥ 0,65, pessoa diferente ~0,49.
 */
object Embedding {
    const val DIM = 192
    class Saida(val vetor: FloatArray, val nitidez: Float)

    private var interp: Interpreter? = null
    private fun modelo(ctx: Context): Interpreter {
        interp?.let { return it }
        val fd = ctx.assets.openFd("mobilefacenet.tflite")
        val mapa = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(mapa, Interpreter.Options().setNumThreads(2)).also { interp = it }
    }

    /** Recorte alinhado (112x112), vetor normalizado e uma nota de nitidez do recorte (variância do laplaciano). */
    fun calcular(ctx: Context, b: Bitmap, r: Rostos.Rosto): Saida? = runCatching {
        val cx = r.caixa.exactCenterX(); val cy = r.caixa.exactCenterY()
        val lado = max(r.caixa.width(), r.caixa.height()) * 1.15f
        val ang = if (r.olhoEsq != null && r.olhoDir != null) Math.toDegrees(atan2((r.olhoDir.y - r.olhoEsq.y).toDouble(), (r.olhoDir.x - r.olhoEsq.x).toDouble())).toFloat() else 0f
        val m = Matrix().apply { postTranslate(-cx, -cy); postRotate(-ang); postScale(112f / lado, 112f / lado); postTranslate(56f, 56f) }
        val face = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        Canvas(face).drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(112 * 112).also { face.getPixels(it, 0, 112, 0, 0, 112, 112) }
        face.recycle()
        val buf = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
        for (c in px) { buf.putFloat(((c shr 16 and 255) - 127.5f) / 128f); buf.putFloat(((c shr 8 and 255) - 127.5f) / 128f); buf.putFloat(((c and 255) - 127.5f) / 128f) }
        buf.rewind()
        val saida = Array(1) { FloatArray(DIM) }
        synchronized(this) { modelo(ctx).run(buf, saida) }
        val v = saida[0]; var n = 0f; for (x in v) n += x * x; n = sqrt(n) + 1e-6f
        Saida(FloatArray(DIM) { v[it] / n }, nitidez(px, 112, 112))
    }.getOrNull()

    /** Variância do laplaciano no cinza: quanto maior, mais nítido (borrado abaixo de ~50, nítido acima de ~300). */
    private fun nitidez(px: IntArray, w: Int, h: Int): Float {
        val g = FloatArray(w * h) { val p = px[it]; ((p shr 16 and 255) * 54 + (p shr 8 and 255) * 183 + (p and 255) * 19) / 256f }
        var soma = 0.0; var soma2 = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
            soma += l; soma2 += l * l; n++
        }
        if (n == 0) return 0f
        val media = soma / n
        return (soma2 / n - media * media).toFloat()
    }

    fun sim(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

    fun paraBytes(v: FloatArray): ByteArray { val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN); for (x in v) bb.putFloat(x); return bb.array() }
    fun deBytes(b: ByteArray): FloatArray { val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.size / 4) { bb.getFloat() } }
}
