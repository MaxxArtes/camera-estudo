package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rostos numa foto (ML Kit, no aparelho): caixa, olhos e quão de frente está (para escolher a capa).
 * Derivado do Rostos.kt da câmera; aqui o detector é reaproveitado entre fotos (indexação em lote).
 * Nada aqui identifica ninguém; a identidade é o Embedding.kt.
 */
object Rostos {
    class Rosto(val caixa: Rect, val olhoEsq: PointF?, val olhoDir: PointF?, val frontal: Float)

    private val opcoes = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.08f)
        .build()
    private var detector: FaceDetector? = null
    private fun det(): FaceDetector = synchronized(this) { detector ?: FaceDetection.getClient(opcoes).also { detector = it } }
    @Volatile var ultimoErro: String? = null

    /** Detecta numa cópia reduzida (lado ≤ ladoMax) e devolve as caixas na escala do bitmap dado. Fora do thread principal. */
    fun detectar(b: Bitmap, ladoMax: Int = 1000): List<Rosto> = runCatching {
        ultimoErro = null
        val esc = min(1f, ladoMax.toFloat() / max(b.width, b.height))
        val peq = if (esc < 1f) Bitmap.createScaledBitmap(b, max(1, (b.width * esc).toInt()), max(1, (b.height * esc).toInt()), true) else b
        val faces = try { Tasks.await(det().process(InputImage.fromBitmap(peq, 0))) } finally { if (peq !== b) peq.recycle() }
        val inv = 1f / esc
        faces.map { f ->
            val c = f.boundingBox
            val e = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val d = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val frontal = (1f - min(1f, abs(f.headEulerAngleY) / 45f)) * (1f - min(1f, abs(f.headEulerAngleZ) / 45f))
            Rosto(
                Rect((c.left * inv).toInt(), (c.top * inv).toInt(), (c.right * inv).toInt(), (c.bottom * inv).toInt()),
                e?.let { PointF(it.x * inv, it.y * inv) }, d?.let { PointF(it.x * inv, it.y * inv) }, frontal
            )
        }
    }.getOrElse { e ->
        ultimoErro = (e::class.java.simpleName + ": " + (e.message ?: "")).take(200)
        synchronized(this) { runCatching { detector?.close() }; detector = null }
        emptyList()
    }
}
