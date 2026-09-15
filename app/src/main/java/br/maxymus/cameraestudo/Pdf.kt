package br.maxymus.cameraestudo

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF de várias páginas a partir das fotos escolhidas (uma foto por página, A4 em retrato ou
 * paisagem conforme a foto), gravado em Documentos/CameraEstudo pelo MediaStore.
 * Usa só o PdfDocument do Android, sem biblioteca.
 */
object Pdf {
    private const val A4_W = 595; private const val A4_H = 842   // pontos (72 por polegada)

    suspend fun gerar(contexto: Context, fotos: List<Uri>): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val doc = PdfDocument()
            fotos.forEachIndexed { i, uri ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }   // metade da resolução: PDF leve e nítido
                val b0 = contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@forEachIndexed
                val rot = contexto.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
                val b = if (rot != 0) android.graphics.Bitmap.createBitmap(b0, 0, 0, b0.width, b0.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else b0
                val paisagem = b.width > b.height
                val pw = if (paisagem) A4_H else A4_W; val ph = if (paisagem) A4_W else A4_H
                val pagina = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val esc = minOf((pw - 40f) / b.width, (ph - 40f) / b.height)
                val m = Matrix().apply { postScale(esc, esc); postTranslate((pw - b.width * esc) / 2, (ph - b.height * esc) / 2) }
                pagina.canvas.drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(pagina)
            }
            val nome = "Digitalizacao_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pdf"
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nome); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + Fotos.PASTA)
            }
            val colecao = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Files.getContentUri("external")
            val destino = contexto.contentResolver.insert(colecao, valores) ?: return@runCatching null
            contexto.contentResolver.openOutputStream(destino)?.use { doc.writeTo(it) } ?: return@runCatching null
            doc.close()
            destino
        }.getOrNull()
    }
}
