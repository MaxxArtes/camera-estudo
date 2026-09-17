package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Onde está o assunto principal (Google "Mobile Object Localizer v1", Apache-2.0, TFLite): 192x192 uint8 → até 100
 * caixas sem classe, com pontuação. Usado quando não há rosto: centro do radial das auto-máscaras, área do desfoque
 * no retrato de objeto/pet e referência da rajada. Pedido do dono (17/09).
 */
object Assunto {
    private const val N = 192
    private var interp: Interpreter? = null
    @Volatile var ultimoMs = -1L; @Volatile var ultimoErro: String? = null
    class Caixa(val ret: RectF, val pontuacao: Float)   // ret normalizada 0..1 (left, top, right, bottom)

    private fun modelo(ctx: Context): Interpreter {
        interp?.let { return it }
        val fd = ctx.assets.openFd("mobile_object_localizer.tflite")
        val mapa = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(mapa, Interpreter.Options().setNumThreads(2)).also { interp = it }
    }

    /** Caixas com pontuação ≥ minimo, da maior pontuação para a menor. */
    fun caixas(ctx: Context, b: Bitmap, minimo: Float = 0.3f): List<Caixa> = runCatching {
        val t = System.nanoTime()
        val peq = Bitmap.createScaledBitmap(b, N, N, true)
        val px = IntArray(N * N).also { peq.getPixels(it, 0, N, 0, 0, N, N) }; if (peq !== b) peq.recycle()
        val entrada = ByteBuffer.allocateDirect(N * N * 3).order(ByteOrder.nativeOrder())
        for (c in px) { entrada.put((c shr 16 and 255).toByte()); entrada.put((c shr 8 and 255).toByte()); entrada.put((c and 255).toByte()) }
        entrada.rewind()
        val boxes = Array(1) { Array(100) { FloatArray(4) } }; val classes = Array(1) { FloatArray(100) }; val scores = Array(1) { FloatArray(100) }; val num = FloatArray(1)
        synchronized(this) { modelo(ctx).runForMultipleInputsOutputs(arrayOf(entrada), mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to num)) }
        val n = num[0].toInt().coerceIn(0, 100)
        val saida = ArrayList<Caixa>()
        for (i in 0 until n) if (scores[0][i] >= minimo) { val bx = boxes[0][i]; saida += Caixa(RectF(bx[1].coerceIn(0f, 1f), bx[0].coerceIn(0f, 1f), bx[3].coerceIn(0f, 1f), bx[2].coerceIn(0f, 1f)), scores[0][i]) }
        ultimoMs = (System.nanoTime() - t) / 1_000_000; ultimoErro = null
        saida.sortedByDescending { it.pontuacao }
    }.getOrElse { e -> ultimoErro = (e::class.java.simpleName + ": " + (e.message ?: "")).take(200); emptyList() }

    /** O assunto principal: pontuação x raiz da área (prefere o grande e confiante), ou null. */
    fun principal(ctx: Context, b: Bitmap): Caixa? = caixas(ctx, b).maxByOrNull { it.pontuacao * kotlin.math.sqrt(it.ret.width() * it.ret.height()) }
}
