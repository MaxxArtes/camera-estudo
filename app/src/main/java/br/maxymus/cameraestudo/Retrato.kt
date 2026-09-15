package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Retrato por software, para aparelhos sem bokeh nativo: o ML Kit separa a pessoa do fundo
 * (máscara por pixel, 0 = fundo, 1 = pessoa) e o fundo recebe um desfoque. O resultado
 * sobrescreve a própria foto no MediaStore.
 *
 * O desfoque é feito reduzindo a imagem e ampliando de volta com filtro bilinear (um "blur"
 * barato que roda em qualquer aparelho, sem RenderScript nem GPU).
 */
object Retrato {
    private const val LADO_MAX = 1600   // processar em 12 Mpx levaria muitos segundos; 1600 px basta para tela e redes

    suspend fun aplicar(contexto: Context, uri: Uri): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            val original = contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching false
            val rotacao = contexto.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            val certa = if (rotacao != 0) Bitmap.createBitmap(original, 0, 0, original.width, original.height, Matrix().apply { postRotate(rotacao.toFloat()) }, true) else original
            val escala = minOf(1f, LADO_MAX.toFloat() / maxOf(certa.width, certa.height))
            val base = if (escala < 1f) Bitmap.createScaledBitmap(certa, (certa.width * escala).toInt(), (certa.height * escala).toInt(), true) else certa
            val w = base.width; val h = base.height

            val segmentador = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
            val mascara = Tasks.await(segmentador.process(InputImage.fromBitmap(base, 0)))
            segmentador.close()
            val mw = mascara.width; val mh = mascara.height
            val conf = FloatArray(mw * mh).also { mascara.buffer.rewind(); mascara.buffer.get(it) }

            // fundo desfocado: 1/10 do tamanho e de volta
            val pequeno = Bitmap.createScaledBitmap(base, maxOf(1, w / 10), maxOf(1, h / 10), true)
            val fundo = Bitmap.createScaledBitmap(pequeno, w, h, true)

            val pFrente = IntArray(w * h).also { base.getPixels(it, 0, w, 0, 0, w, h) }
            val pFundo = IntArray(w * h).also { fundo.getPixels(it, 0, w, 0, 0, w, h) }
            val saida = IntArray(w * h)
            for (y in 0 until h) {
                val my = (y * mh / h).coerceIn(0, mh - 1)
                for (x in 0 until w) {
                    val mx = (x * mw / w).coerceIn(0, mw - 1)
                    val a = conf[my * mw + mx]          // 0..1: quanto é pessoa
                    val i = y * w + x
                    val f = pFrente[i]; val b = pFundo[i]
                    val r = ((f shr 16 and 255) * a + (b shr 16 and 255) * (1 - a)).toInt()
                    val g = ((f shr 8 and 255) * a + (b shr 8 and 255) * (1 - a)).toInt()
                    val bl = ((f and 255) * a + (b and 255) * (1 - a)).toInt()
                    saida[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            val resultado = Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
            contexto.contentResolver.openOutputStream(uri, "wt")?.use { resultado.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }
}
