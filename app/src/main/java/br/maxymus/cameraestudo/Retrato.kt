package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

    /** intensidade 1..10: 5 é o padrão antigo (fundo reduzido a 1/10); 1 quase não desfoca, 10 desfoca muito. */
    /** Devolve null quando deu certo; senão o motivo (vai para a telemetria). */
    suspend fun aplicar(contexto: Context, uri: Uri, intensidade: Int = 5): String? = withContext(Dispatchers.Default) {
        runCatching {
            val certa = Documento.decodeReduzido(contexto, uri, LADO_MAX * 2) ?: return@runCatching "decode nulo"
            val escala = minOf(1f, LADO_MAX.toFloat() / maxOf(certa.width, certa.height))
            val base = if (escala < 1f) Bitmap.createScaledBitmap(certa, (certa.width * escala).toInt(), (certa.height * escala).toInt(), true) else certa
            if (base !== certa) certa.recycle()
            val w = base.width; val h = base.height

            // enableRawSizeMask: máscara no tamanho da imagem em vez de 256x256; o contorno da pessoa fica bem mais fiel
            val segmentador = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build())
            val mascara = Tasks.await(segmentador.process(InputImage.fromBitmap(base, 0)))
            segmentador.close()
            val mw = mascara.width; val mh = mascara.height
            val bb = mascara.buffer; bb.rewind()
            val conf = FloatArray(mw * mh) { bb.float }   // o ML Kit entrega a máscara como floats dentro de um ByteBuffer

            // fundo desfocado: reduz por (2 x intensidade) e volta, duas vezes para o desfoque ficar redondo, não quadriculado
            val divisor = (intensidade.coerceIn(1, 10) * 2)
            val pequeno = Bitmap.createScaledBitmap(base, maxOf(1, w / divisor), maxOf(1, h / divisor), true)
            val meio = Bitmap.createScaledBitmap(pequeno, maxOf(1, w / 2), maxOf(1, h / 2), true)
            val fundo = Bitmap.createScaledBitmap(meio, w, h, true); meio.recycle()

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
            pequeno.recycle(); fundo.recycle(); base.recycle()
            val resultado = Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
            contexto.contentResolver.openOutputStream(uri, "wt")?.use { resultado.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching "não consegui gravar"
            resultado.recycle()
            null
        }.getOrElse { e -> (e::class.java.simpleName + ": " + (e.message ?: "")).take(300) }
    }
}
