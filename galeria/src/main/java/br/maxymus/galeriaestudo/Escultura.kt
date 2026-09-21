package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Esculpir o rosto: deformação local por pontos de controle, guiada pelos marcos do ML Kit (os mesmos que o app já
 * usa para achar rostos). Medido na bancada em 21/09 (galeria/medicao/warp.py) sobre foto real: no tamanho de
 * trabalho de 1024 px o campo de deslocamento + reamostragem bilinear custou 170 ms, e 1,5 s na foto inteira de
 * 2592 px — sem artefato visível na força que usamos.
 *
 * Os tetos vêm do Astra (21/09) e existem para não virar caricatura: forma do rosto no máximo 3% da LARGURA DO
 * ROSTO, e olhos/nariz/lábios no máximo 5% do próprio tamanho. Deformar a foto deforma também o que está atrás da
 * pessoa: parede com linha reta perto do rosto entorta. O painel avisa isso.
 */
object Escultura {
    data class Parametros(val afinar: Float = 0f, val queixo: Float = 0f, val olhos: Float = 0f,
                          val nariz: Float = 0f, val labios: Float = 0f) {
        val neutro: Boolean get() = afinar == 0f && queixo == 0f && olhos == 0f && nariz == 0f && labios == 0f
        fun valor(p: String): Float = when (p) { "Afinar" -> afinar; "Queixo" -> queixo; "Olhos" -> olhos; "Nariz" -> nariz; else -> labios }
        fun com(p: String, v: Float): Parametros = when (p) {
            "Afinar" -> copy(afinar = v); "Queixo" -> copy(queixo = v); "Olhos" -> copy(olhos = v)
            "Nariz" -> copy(nariz = v); else -> copy(labios = v)
        }
    }
    val SLIDERS = listOf("Afinar", "Queixo", "Olhos", "Nariz", "Lábios")

    /** Marcos de um rosto em coordenadas NORMALIZADAS (0..1), para valerem em qualquer resolução. */
    class Marcos(val cx: Float, val cy: Float, val larg: Float, val alt: Float,
                 val olhoE: PointF?, val olhoD: PointF?, val bochechaE: PointF?, val bochechaD: PointF?,
                 val nariz: PointF?, val boca: PointF?, val queixoY: Float)

    private val opcoes = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)   // editor: uma foto por vez, vale o custo
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.08f)
        .build()
    private var detector: FaceDetector? = null
    private fun det(): FaceDetector = synchronized(this) { detector ?: FaceDetection.getClient(opcoes).also { detector = it } }
    @Volatile var ultimoErro: String? = null

    /** Marcos de todos os rostos da foto. Lista vazia quando não acha ninguém. Bloqueante: chamar fora da principal. */
    fun marcos(b: Bitmap, ladoMax: Int = 1000): List<Marcos> = runCatching {
        ultimoErro = null
        val esc = min(1f, ladoMax.toFloat() / max(b.width, b.height))
        val peq = if (esc < 1f) Bitmap.createScaledBitmap(b, max(1, (b.width * esc).toInt()), max(1, (b.height * esc).toInt()), true) else b
        val pw = peq.width.toFloat(); val ph = peq.height.toFloat()
        val faces = try { Tasks.await(det().process(InputImage.fromBitmap(peq, 0))) } finally { if (peq !== b) peq.recycle() }
        faces.map { f ->
            val c = f.boundingBox
            fun pt(tipo: Int): PointF? = f.getLandmark(tipo)?.position?.let { PointF(it.x / pw, it.y / ph) }
            Marcos(
                cx = (c.exactCenterX()) / pw, cy = (c.exactCenterY()) / ph,
                larg = c.width() / pw, alt = c.height() / ph,
                olhoE = pt(FaceLandmark.LEFT_EYE), olhoD = pt(FaceLandmark.RIGHT_EYE),
                bochechaE = pt(FaceLandmark.LEFT_CHEEK), bochechaD = pt(FaceLandmark.RIGHT_CHEEK),
                nariz = pt(FaceLandmark.NOSE_BASE), boca = pt(FaceLandmark.MOUTH_BOTTOM),
                queixoY = c.bottom / ph)
        }
    }.getOrElse { e ->
        ultimoErro = (e::class.java.simpleName + ": " + (e.message ?: "")).take(200)
        synchronized(this) { runCatching { detector?.close() }; detector = null }
        emptyList()
    }

    /** Um empurrão ou um zoom local; o campo é a soma deles, aplicado na leitura (source lookup). */
    private class Op(val cx: Float, val cy: Float, val raio: Float, val dx: Float, val dy: Float, val escala: Float)

    private fun ops(m: Marcos, p: Parametros, w: Int, h: Int): List<Op> {
        val lista = ArrayList<Op>(8)
        val lw = m.larg * w                       // largura do rosto em pixels
        val tetoForma = 0.03f * lw                // Astra: 3% da largura do rosto
        if (p.afinar != 0f) {
            val d = p.afinar / 100f * tetoForma
            val raio = lw * 0.45f
            val be = m.bochechaE; val bd = m.bochechaD
            val ye = (be?.y ?: (m.cy + m.alt * 0.12f)) * h
            val yd = (bd?.y ?: (m.cy + m.alt * 0.12f)) * h
            lista += Op((be?.x ?: (m.cx - m.larg * 0.45f)) * w, ye, raio, d, 0f, 1f)
            lista += Op((bd?.x ?: (m.cx + m.larg * 0.45f)) * w, yd, raio, -d, 0f, 1f)
        }
        if (p.queixo != 0f) {
            val d = p.queixo / 100f * tetoForma
            lista += Op(m.cx * w, m.queixoY * h, lw * 0.5f, 0f, -d, 1f)   // positivo encurta o queixo
        }
        if (p.olhos != 0f) {
            val k = 1f - p.olhos / 100f * 0.05f    // Astra: ±5% do tamanho
            val r = lw * 0.22f
            m.olhoE?.let { lista += Op(it.x * w, it.y * h, r, 0f, 0f, k) }
            m.olhoD?.let { lista += Op(it.x * w, it.y * h, r, 0f, 0f, k) }
        }
        if (p.nariz != 0f) {
            val k = 1f + p.nariz / 100f * 0.05f
            m.nariz?.let { lista += Op(it.x * w, it.y * h, lw * 0.20f, 0f, 0f, k) }
        }
        if (p.labios != 0f) {
            val k = 1f - p.labios / 100f * 0.05f
            m.boca?.let { lista += Op(it.x * w, it.y * h, lw * 0.28f, 0f, 0f, k) }
        }
        return lista
    }

    /** Aplica a escultura. Devolve o próprio bitmap quando não há nada a fazer. */
    fun aplicar(b: Bitmap, p: Parametros, rostos: List<Marcos>): Bitmap {
        if (p.neutro || rostos.isEmpty()) return b
        val w = b.width; val h = b.height
        val todas = rostos.flatMap { ops(it, p, w, h) }
        if (todas.isEmpty()) return b
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val saida = IntArray(w * h)
        for (y in 0 until h) {
            val fy = y.toFloat()
            for (x in 0 until w) {
                var sx = x.toFloat(); var sy = fy
                for (o in todas) {
                    val ddx = sx - o.cx; val ddy = sy - o.cy
                    val d = sqrt(ddx * ddx + ddy * ddy)
                    if (d >= o.raio) continue
                    val t = 1f - d / o.raio
                    val peso = t * t                      // some suave na borda: sem costura visível
                    if (o.escala != 1f) { val f = 1f + (o.escala - 1f) * peso; sx = o.cx + ddx * f; sy = o.cy + ddy * f }
                    if (o.dx != 0f || o.dy != 0f) { sx -= o.dx * peso; sy -= o.dy * peso }
                }
                saida[y * w + x] = amostra(px, w, h, sx, sy)
            }
        }
        return Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun amostra(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val fx = x.coerceIn(0f, w - 1.001f); val fy = y.coerceIn(0f, h - 1.001f)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val tx = fx - x0; val ty = fy - y0
        val c00 = px[y0 * w + x0]; val c10 = px[y0 * w + x1]; val c01 = px[y1 * w + x0]; val c11 = px[y1 * w + x1]
        fun canal(desl: Int): Int {
            val a = (c00 shr desl and 255) * (1 - tx) + (c10 shr desl and 255) * tx
            val bb = (c01 shr desl and 255) * (1 - tx) + (c11 shr desl and 255) * tx
            return (a * (1 - ty) + bb * ty).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
    }
}
