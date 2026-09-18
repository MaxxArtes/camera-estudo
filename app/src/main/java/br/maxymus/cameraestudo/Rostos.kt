package br.maxymus.cameraestudo

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.max

/**
 * Rostos numa foto (ML Kit, no aparelho): caixa, olhos abertos, sorriso e posição dos olhos.
 * Fase 1 (16/09): a rajada escolhe como referência o quadro nítido em que os olhos estão abertos.
 * Nada aqui identifica ninguém; a identificação é o Pessoas.kt.
 */
object Rostos {
    enum class Presenca { Detectado, NaoDetectado, Falha }

    suspend fun verificarOnline(bitmap: Bitmap): Presenca = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val detector = FaceDetection.getClient(opcoes)
        try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuacao ->
                detector.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { faces ->
                        if (continuacao.isActive) continuacao.resumeWith(Result.success(if (faces.isEmpty()) Presenca.NaoDetectado else Presenca.Detectado))
                    }
                    .addOnFailureListener {
                        if (continuacao.isActive) continuacao.resumeWith(Result.success(Presenca.Falha))
                    }
            }
        } catch (erro: kotlinx.coroutines.CancellationException) { throw erro }
        catch (_: Exception) { Presenca.Falha }
        finally { detector.close() }
    }

    class Rosto(val caixa: Rect, val olhosAbertos: Float, val sorriso: Float, val olhoEsq: PointF?, val olhoDir: PointF?)

    private val opcoes = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.08f)
        .build()

    @Volatile var ultimoErro: String? = null   // motivo da última falha do detector (vai para a telemetria; selfie com 0 rostos em 17/09)

    /** Detecta em uma cópia reduzida (lado ≤ ladoMax) e devolve as caixas na escala do bitmap original. */
    fun detectar(b: Bitmap, ladoMax: Int = 800): List<Rosto> = runCatching {
        ultimoErro = null
        val esc = minOf(1f, ladoMax.toFloat() / max(b.width, b.height))
        val peq = if (esc < 1f) Bitmap.createScaledBitmap(b, (b.width * esc).toInt(), (b.height * esc).toInt(), true) else b
        val det = FaceDetection.getClient(opcoes)
        val faces = Tasks.await(det.process(InputImage.fromBitmap(peq, 0))); det.close()
        if (peq !== b) peq.recycle()
        val inv = 1f / esc
        faces.map { f ->
            val c = f.boundingBox
            val e = f.getLandmark(FaceLandmark.LEFT_EYE)?.position; val d = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            Rosto(
                Rect((c.left * inv).toInt(), (c.top * inv).toInt(), (c.right * inv).toInt(), (c.bottom * inv).toInt()),
                ((f.leftEyeOpenProbability ?: 0.5f) + (f.rightEyeOpenProbability ?: 0.5f)) / 2f,
                f.smilingProbability ?: 0.5f,
                e?.let { PointF(it.x * inv, it.y * inv) }, d?.let { PointF(it.x * inv, it.y * inv) }
            )
        }
    }.getOrElse { e -> ultimoErro = (e::class.java.simpleName + ": " + (e.message ?: "")).take(200); emptyList() }

    /**
     * Nota de "bom quadro" para a rajada: 1 sem rosto; com rostos, 0,4 + 0,6 x média de olhos abertos
     * (um quadro de olhos fechados vale menos da metade de um de olhos abertos).
     */
    fun notaOlhos(b: Bitmap): Float {
        val rostos = detectar(b, 640)
        if (rostos.isEmpty()) return 1f
        return 0.4f + 0.6f * rostos.map { it.olhosAbertos }.average().toFloat()
    }
}
