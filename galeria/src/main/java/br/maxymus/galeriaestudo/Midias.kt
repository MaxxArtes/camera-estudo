package br.maxymus.galeriaestudo

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/** Uma foto ou vídeo do aparelho, como vem do MediaStore. */
data class Midia(val id: Long, val uri: Uri, val ehVideo: Boolean, val quando: Long, val duracaoMs: Long) {
    /** Dia local da captura; LocalDate.MIN quando não há data utilizável ("Sem data", no fim). */
    val dia: LocalDate get() = if (quando <= 0L) LocalDate.MIN else Instant.ofEpochMilli(quando).atZone(ZoneId.systemDefault()).toLocalDate()
}

object Midias {
    private val PT = Locale("pt", "BR")
    private val FMT_DIA = DateTimeFormatter.ofPattern("EEE, d 'de' MMMM", PT)
    private val FMT_ANO = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", PT)
    private val FMT_HORA = DateTimeFormatter.ofPattern("HH:mm", PT)

    /** "Hoje", "Ontem", "Sáb., 19 de setembro", "19 de setembro de 2025", "Sem data". */
    fun rotuloDia(d: LocalDate): String {
        if (d == LocalDate.MIN) return "Sem data"
        val hoje = LocalDate.now()
        return when {
            d == hoje -> "Hoje"
            d == hoje.minusDays(1) -> "Ontem"
            d.year == hoje.year -> FMT_DIA.format(d).replaceFirstChar { it.uppercase() }
            else -> FMT_ANO.format(d)
        }
    }

    fun rotuloDataHora(ms: Long): Pair<String, String> {
        if (ms <= 0L) return "Sem data" to ""
        val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return FMT_ANO.format(z) to FMT_HORA.format(z)
    }

    fun numero(n: Int): String = String.format(PT, "%,d", n)

    fun duracao(ms: Long): String {
        val s = ms / 1000; val m = s / 60
        return if (m >= 60) String.format(PT, "%d:%02d:%02d", m / 60, m % 60, s % 60) else String.format(PT, "%d:%02d", m, s % 60)
    }

    /** Todas as fotos (e vídeos, se pedido) acessíveis, mais recentes primeiro. Uma consulta só, na tabela Files. */
    fun listar(ctx: Context, soFotos: Boolean = false): List<Midia> {
        val colecao = MediaStore.Files.getContentUri("external")
        val cId = MediaStore.Files.FileColumns._ID; val cTipo = MediaStore.Files.FileColumns.MEDIA_TYPE
        val cTaken = "datetaken"; val cMod = MediaStore.Files.FileColumns.DATE_MODIFIED; val cAdd = MediaStore.Files.FileColumns.DATE_ADDED; val cDur = "duration"
        val proj = arrayOf(cId, cTipo, cTaken, cMod, cAdd, cDur)
        val tImg = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(); val tVid = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        val sel = if (soFotos) "$cTipo=?" else "$cTipo IN (?,?)"
        val args = if (soFotos) arrayOf(tImg) else arrayOf(tImg, tVid)
        val saida = ArrayList<Midia>(2048)
        runCatching {
            ctx.contentResolver.query(colecao, proj, sel, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(cId); val iTipo = c.getColumnIndexOrThrow(cTipo)
                val iTaken = c.getColumnIndex(cTaken); val iMod = c.getColumnIndex(cMod); val iAdd = c.getColumnIndex(cAdd); val iDur = c.getColumnIndex(cDur)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val video = c.getInt(iTipo) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val taken = if (iTaken < 0 || c.isNull(iTaken)) 0L else c.getLong(iTaken)
                    val mod = if (iMod < 0 || c.isNull(iMod)) 0L else c.getLong(iMod) * 1000
                    val add = if (iAdd < 0 || c.isNull(iAdd)) 0L else c.getLong(iAdd) * 1000
                    val quando = when { taken > 0L -> taken; mod > 0L -> mod; else -> add }
                    val uri = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val dur = if (iDur < 0 || c.isNull(iDur)) 0L else c.getLong(iDur)
                    saida += Midia(id, uri, video, quando, dur)
                }
            }
        }
        saida.sortWith(compareByDescending<Midia> { it.quando }.thenByDescending { it.id })
        return saida
    }

    /** Decodifica com inSampleSize até o lado ≤ ladoMax e corrige a rotação do EXIF. Null se não deu. */
    fun decodeReduzido(ctx: Context, uri: Uri, ladoMax: Int): Bitmap? {
        return try {
            val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, medidas) }
            if (medidas.outWidth <= 0 || medidas.outHeight <= 0) return null
            var amostra = 1
            while (max(medidas.outWidth, medidas.outHeight) / (amostra * 2) >= ladoMax) amostra *= 2
            val op = BitmapFactory.Options().apply { inSampleSize = amostra; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) } ?: return null
            val rot = runCatching { ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
            if (rot == 0) b else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val g = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                if (g !== b) b.recycle()
                g
            }
        } catch (e: Throwable) { null }
    }
}
