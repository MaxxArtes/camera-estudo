package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Motor "Padrão" do recorte: ML Kit Subject Segmentation (Google Play services; o módulo é baixado na instalação pelo
 * meta-data do manifesto). Devolve a máscara de confiança de primeiro plano (0..1) no tamanho do bitmap de entrada.
 * Bloqueante: chamar fora da thread principal. Null se o módulo ainda não estiver no aparelho ou se falhar — o
 * chamador (Fundo.segmentar) cai no motor Leve e registra o motivo.
 */
object MlKitAssunto {
    @Volatile var ultimoErro: String? = null
    @Volatile var ultimoMs = -1L
    private var cliente: SubjectSegmenter? = null

    private fun cliente(): SubjectSegmenter = cliente ?: SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()).also { cliente = it }

    class Resultado(val mapa: FloatArray, val w: Int, val h: Int)

    fun segmentar(b: Bitmap): Resultado? = runCatching {
        val t = System.nanoTime()
        val r = Tasks.await(cliente().process(InputImage.fromBitmap(b, 0)), 30, TimeUnit.SECONDS)
        val buf = r.foregroundConfidenceMask ?: error("sem máscara")
        val n = b.width * b.height
        val mapa = FloatArray(n)
        buf.rewind(); buf.get(mapa, 0, min(n, buf.remaining()))
        ultimoMs = (System.nanoTime() - t) / 1_000_000; ultimoErro = null
        Telemetria.evento("mlkit_assunto", mapOf("ms" to ultimoMs, "larg" to b.width, "alt" to b.height))
        Resultado(mapa, b.width, b.height)
    }.getOrElse { e ->
        val causa = e.cause ?: e
        ultimoErro = (causa::class.java.simpleName + ": " + (causa.message ?: "")).take(200); null
    }
}
