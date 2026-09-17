package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8n-seg (Ultralytics, AGPL-3.0; TFLite float16 640x640 de surendramaran/YOLO, CC BY 4.0 no código de exemplo):
 * detecta 80 classes COCO com máscara de instância. Pedido do dono (17/09, licença AGPL aceita para o app público).
 * Uso: retrato de pet/objeto (máscara do assunto quando não há pessoa) e etiquetas de cena na telemetria.
 * Saídas: [1,116,8400] = 4 caixa + 80 classes + 32 coeficientes por âncora; [1,32,160,160] = protótipos das máscaras.
 * Pós-processamento próprio: limiar 0,25, NMS IoU 0,5, máscara = sigmoid(coefs · protótipos) cortada na caixa.
 */
object Yolo {
    private const val N = 640; private const val NA = 8400; private const val NC = 80; private const val NM = 32; private const val PM = 160
    val CLASSES = listOf("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush")
    private var interp: Interpreter? = null
    @Volatile var ultimoMs = -1L; @Volatile var ultimoErro: String? = null

    /** ret normalizada 0..1 na imagem original; mascara(x,y) normalizada devolve 0..1. */
    class Det(val classe: Int, val nome: String, val pontuacao: Float, val ret: RectF, private val m: FloatArray, private val mx0: Int, private val my0: Int, private val mw: Int, private val mh: Int, private val esc: Float, private val padX: Float, private val padY: Float) {
        fun mascara(x: Float, y: Float): Float {
            // normalizada (imagem original) → pixel 640 letterbox → grade 160
            val px = (x * larguraOrig(esc, padX) * esc + padX) * PM / N - mx0; val py = (y * alturaOrig(esc, padY) * esc + padY) * PM / N - my0
            if (px < -0.5f || py < -0.5f || px > mw - 0.5f || py > mh - 0.5f) return 0f
            val fx = px.coerceIn(0f, mw - 1f); val fy = py.coerceIn(0f, mh - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(mw - 1, x0 + 1); val y1 = min(mh - 1, y0 + 1); val tx = fx - x0; val ty = fy - y0
            return (m[y0 * mw + x0] * (1 - tx) + m[y0 * mw + x1] * tx) * (1 - ty) + (m[y1 * mw + x0] * (1 - tx) + m[y1 * mw + x1] * tx) * ty
        }
        private fun larguraOrig(e: Float, p: Float) = (N - 2 * p) / e
        private fun alturaOrig(e: Float, p: Float) = (N - 2 * p) / e
    }

    private fun modelo(ctx: Context): Interpreter {
        interp?.let { return it }
        val fd = ctx.assets.openFd("yolov8n-seg.tflite")
        val mapa = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(mapa, Interpreter.Options().setNumThreads(4)).also { interp = it }
    }

    fun detectar(ctx: Context, b: Bitmap, limiar: Float = 0.25f, maximo: Int = 10): List<Det> = runCatching {
        val t = System.nanoTime()
        // letterbox: encaixa a imagem em 640x640 com bordas cinza
        val esc = min(N.toFloat() / b.width, N.toFloat() / b.height)
        val nw = (b.width * esc).toInt(); val nh = (b.height * esc).toInt(); val padX = (N - nw) / 2f; val padY = (N - nh) / 2f
        val tela = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888); val cv = Canvas(tela); cv.drawColor(0xFF727272.toInt())
        cv.drawBitmap(b, null, RectF(padX, padY, padX + nw, padY + nh), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(N * N).also { tela.getPixels(it, 0, N, 0, 0, N, N) }; tela.recycle()
        val entrada = ByteBuffer.allocateDirect(N * N * 3 * 4).order(ByteOrder.nativeOrder())
        for (c in px) { entrada.putFloat((c shr 16 and 255) / 255f); entrada.putFloat((c shr 8 and 255) / 255f); entrada.putFloat((c and 255) / 255f) }
        entrada.rewind()
        val it0 = modelo(ctx)
        // identifica as saídas pela forma (a ordem varia entre exportações)
        var iDet = 0; var iProto = 1
        for (i in 0 until it0.outputTensorCount) { val s = it0.getOutputTensor(i).shape(); if (s.size == 4) iProto = i else iDet = i }
        val det = ByteBuffer.allocateDirect((4 + NC + NM) * NA * 4).order(ByteOrder.nativeOrder())
        val proto = ByteBuffer.allocateDirect(NM * PM * PM * 4).order(ByteOrder.nativeOrder())
        synchronized(this) { it0.runForMultipleInputsOutputs(arrayOf(entrada), mapOf(iDet to det, iProto to proto)) }
        det.rewind(); proto.rewind()
        val d = FloatArray((4 + NC + NM) * NA); det.asFloatBuffer().get(d)
        val p = FloatArray(NM * PM * PM); proto.asFloatBuffer().get(p)
        val protoCanaisPrimeiro = it0.getOutputTensor(iProto).shape()[1] == NM   // [1,32,160,160] ou [1,160,160,32]
        // candidatos: layout [116][8400] (canal, âncora)
        class Cand(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val cls: Int, val sc: Float, val a: Int)
        val cands = ArrayList<Cand>()
        for (a in 0 until NA) {
            var melhor = 0; var sc = 0f
            for (c in 0 until NC) { val v = d[(4 + c) * NA + a]; if (v > sc) { sc = v; melhor = c } }
            if (sc < limiar) continue
            val cx = d[0 * NA + a]; val cy = d[1 * NA + a]; val w = d[2 * NA + a]; val h = d[3 * NA + a]
            cands += Cand(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, melhor, sc, a)
        }
        cands.sortByDescending { it.sc }
        val mantidos = ArrayList<Cand>()
        for (c in cands) {
            if (mantidos.size >= maximo) break
            var ok = true
            for (m in mantidos) { val ix = max(0f, min(c.x1, m.x1) - max(c.x0, m.x0)); val iy = max(0f, min(c.y1, m.y1) - max(c.y0, m.y0)); val inter = ix * iy
                val uni = (c.x1 - c.x0) * (c.y1 - c.y0) + (m.x1 - m.x0) * (m.y1 - m.y0) - inter; if (uni > 0 && inter / uni > 0.5f) { ok = false; break } }
            if (ok) mantidos += c
        }
        val saida = ArrayList<Det>()
        for (c in mantidos) {
            // máscara na grade 160, só dentro da caixa
            val gx0 = (c.x0 * PM / N).toInt().coerceIn(0, PM - 1); val gy0 = (c.y0 * PM / N).toInt().coerceIn(0, PM - 1)
            val gx1 = (c.x1 * PM / N).toInt().coerceIn(gx0 + 1, PM); val gy1 = (c.y1 * PM / N).toInt().coerceIn(gy0 + 1, PM)
            val mw = gx1 - gx0; val mh = gy1 - gy0; val m = FloatArray(mw * mh)
            val coef = FloatArray(NM) { d[(4 + NC + it) * NA + c.a] }
            for (gy in gy0 until gy1) for (gx in gx0 until gx1) { var s = 0f
                for (j in 0 until NM) s += coef[j] * (if (protoCanaisPrimeiro) p[j * PM * PM + gy * PM + gx] else p[(gy * PM + gx) * NM + j])
                m[(gy - gy0) * mw + (gx - gx0)] = 1f / (1f + exp(-s)) }
            val ret = RectF(((c.x0 - padX) / esc / b.width).coerceIn(0f, 1f), ((c.y0 - padY) / esc / b.height).coerceIn(0f, 1f), ((c.x1 - padX) / esc / b.width).coerceIn(0f, 1f), ((c.y1 - padY) / esc / b.height).coerceIn(0f, 1f))
            saida += Det(c.cls, CLASSES[c.cls], c.sc, ret, m, gx0, gy0, mw, mh, esc, padX, padY)
        }
        ultimoMs = (System.nanoTime() - t) / 1_000_000; ultimoErro = null
        saida
    }.getOrElse { e -> ultimoErro = (e::class.java.simpleName + ": " + (e.message ?: "")).take(200); emptyList() }
}
