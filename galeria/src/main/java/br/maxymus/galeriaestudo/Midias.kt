package br.maxymus.galeriaestudo

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.os.Bundle
import android.content.ContentResolver
import android.app.PendingIntent
import androidx.exifinterface.media.ExifInterface
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    fun selecionados(n: Int): String = if (n == 1) "1 selecionado" else "$n selecionados"

    fun numero(n: Int): String = String.format(PT, "%,d", n)

    private val MESES = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

    /** Minúsculas sem acento e sem espaço nas pontas, para casar busca ("São" == "sao", "Março" == "marco"). */
    fun semAcento(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(PT)

    /** Meses presentes na biblioteca, do mais novo ao mais antigo, com a contagem. */
    fun mesesPresentes(midias: List<Midia>): List<Pair<YearMonth, Int>> {
        val m = LinkedHashMap<YearMonth, Int>()
        for (x in midias) { val d = x.dia; if (d != LocalDate.MIN) { val ym = YearMonth.from(d); m[ym] = (m[ym] ?: 0) + 1 } }
        return m.entries.sortedByDescending { it.key }.map { it.key to it.value }
    }

    /** Anos presentes, do mais novo ao mais antigo. Alimenta a folha do filtro. */
    fun anosPresentes(midias: List<Midia>): List<Int> = midias.mapNotNull { val d = it.dia; if (d != LocalDate.MIN) d.year else null }.distinct().sortedDescending()

    fun rotuloMes(ym: YearMonth): String = MESES[ym.monthValue - 1].replaceFirstChar { it.uppercase() } + " " + ym.year
    fun mesNome(mes: Int): String = MESES[mes - 1].replaceFirstChar { it.uppercase() }
    fun mesAbrev(mes: Int): String = MESES[mes - 1].take(3).replaceFirstChar { it.uppercase() }

    /** Rótulo do período no botão de filtro: "Set. 2025", "2025", "Setembro"; vazio quando não há filtro. */
    fun rotuloPeriodo(ano: Int?, mes: Int?): String = when {
        ano != null && mes != null -> mesAbrev(mes) + ". " + ano
        ano != null -> ano.toString()
        mes != null -> mesNome(mes)
        else -> ""
    }

    /** Escopo por extenso, para o rótulo "Fotos · ...". */
    fun escopoData(ano: Int?, mes: Int?): String = when {
        ano != null && mes != null -> MESES[mes - 1] + " de " + ano
        ano != null -> "de " + ano
        mes != null -> MESES[mes - 1] + " de todos os anos"
        else -> ""
    }

    /** Interpreta o texto de busca como data. Devolve (ano?, mês?) ou null. Mês sozinho = todos os anos. */
    fun casaData(texto: String): Pair<Int?, Int?>? {
        val t = semAcento(texto).trim()
        if (t.isEmpty()) return null
        Regex("""(\d{1,2})[/-](\d{4})""").find(t)?.let { return it.groupValues[2].toInt() to it.groupValues[1].toInt().coerceIn(1, 12) }
        Regex("""(\d{4})[-/](\d{1,2})""").find(t)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt().coerceIn(1, 12) }
        val ano = Regex("""\b(19|20)\d{2}\b""").find(t)?.value?.toInt()
        val soLetras = t.replace(Regex("[^a-z]"), "")
        if (soLetras.length >= 3) {
            val mes = MESES.indexOfFirst { semAcento(it).startsWith(soLetras.take(4)) }
            if (mes >= 0) return ano to (mes + 1)
        }
        if (ano != null && Regex("""^(19|20)\d{2}$""").matches(t)) return ano to null
        return null
    }

    /** Texto que parece começo de data mas ainda não é uma (ex.: "09/", "09/20"). */
    fun dataIncompleta(texto: String): Boolean {
        val t = texto.trim()
        return casaData(t) == null && Regex("""^\d{1,2}[/-]\d{0,3}$""").matches(t)
    }

    /** Fotos do período (ano/mês, cada um opcional). Sem filtro devolve a lista inteira. */
    fun filtraData(midias: List<Midia>, ano: Int?, mes: Int?): List<Midia> =
        if (ano == null && mes == null) midias
        else midias.filter { val d = it.dia; d != LocalDate.MIN && (ano == null || d.year == ano) && (mes == null || d.monthValue == mes) }

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

    class Detalhe(val nome: String, val bytes: Long, val largura: Int, val altura: Int)

    /** Nome, tamanho e dimensões da mídia, consultados sob demanda (para "Informações"). */
    fun detalhe(ctx: Context, m: Midia): Detalhe = runCatching {
        val proj = arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.WIDTH, android.provider.MediaStore.MediaColumns.HEIGHT)
        ctx.contentResolver.query(m.uri, proj, null, null, null)?.use { c ->
            if (c.moveToFirst()) return@runCatching Detalhe(
                c.getString(0) ?: "", if (c.isNull(1)) 0L else c.getLong(1),
                if (c.isNull(2)) 0 else c.getInt(2), if (c.isNull(3)) 0 else c.getInt(3))
        }
        Detalhe("", 0L, 0, 0)
    }.getOrDefault(Detalhe("", 0L, 0, 0))

    fun uriFoto(id: Long): android.net.Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    fun tamanho(bytes: Long): String = if (bytes > 1_048_576) String.format(PT, "%.1f MB", bytes / 1_048_576.0) else "${bytes / 1024} KB"

        /** Um item na lixeira: a mídia e o instante de expiração (epoch em segundos; 0 = desconhecido). */
    class ItemLixeira(val midia: Midia, val expiraEm: Long)

    fun rotuloExpira(expiraEm: Long): String {
        if (expiraEm <= 0L) return "Prazo indisponível"
        val dias = ((expiraEm * 1000L - System.currentTimeMillis()) / 86_400_000L).toInt()
        return when { dias <= 0 -> "Expira em breve"; dias == 1 -> "≈ 1 dia"; else -> "≈ $dias dias" }
    }

    /** Itens na lixeira do sistema (API 30+), ordenados por vencimento mais próximo. Vazio em versões antigas. */
    fun listarLixeira(ctx: Context): List<ItemLixeira> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val colecao = MediaStore.Files.getContentUri("external")
        val cId = MediaStore.Files.FileColumns._ID; val cTipo = MediaStore.Files.FileColumns.MEDIA_TYPE
        val cMod = MediaStore.Files.FileColumns.DATE_MODIFIED; val cAdd = MediaStore.Files.FileColumns.DATE_ADDED; val cDur = "duration"
        val cExp = MediaStore.MediaColumns.DATE_EXPIRES
        val proj = arrayOf(cId, cTipo, "datetaken", cMod, cAdd, cDur, cExp)
        val tImg = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(); val tVid = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "$cTipo IN (?,?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(tImg, tVid))
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val saida = ArrayList<ItemLixeira>()
        ctx.contentResolver.query(colecao, proj, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(cId); val iTipo = c.getColumnIndexOrThrow(cTipo)
            val iTaken = c.getColumnIndex("datetaken"); val iMod = c.getColumnIndex(cMod); val iAdd = c.getColumnIndex(cAdd); val iDur = c.getColumnIndex(cDur); val iExp = c.getColumnIndex(cExp)
            while (c.moveToNext()) {
                val id = c.getLong(iId); val video = c.getInt(iTipo) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val taken = if (iTaken < 0 || c.isNull(iTaken)) 0L else c.getLong(iTaken)
                val mod = if (iMod < 0 || c.isNull(iMod)) 0L else c.getLong(iMod) * 1000
                val add = if (iAdd < 0 || c.isNull(iAdd)) 0L else c.getLong(iAdd) * 1000
                val quando = when { taken > 0L -> taken; mod > 0L -> mod; else -> add }
                val uri = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val dur = if (iDur < 0 || c.isNull(iDur)) 0L else c.getLong(iDur)
                val exp = if (iExp < 0 || c.isNull(iExp)) 0L else c.getLong(iExp)
                saida += ItemLixeira(Midia(id, uri, video, quando, dur), exp)
            }
        }
        saida.sortWith(compareBy { if (it.expiraEm <= 0L) Long.MAX_VALUE else it.expiraEm })
        return saida
    }

    /** PendingIntent para MOVER para a lixeira (paraLixeira=true) ou RESTAURAR (false). Null em API < 30. */
    fun pedidoLixeira(ctx: Context, uris: List<android.net.Uri>, paraLixeira: Boolean): PendingIntent? =
        if (android.os.Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) runCatching { MediaStore.createTrashRequest(ctx.contentResolver, uris, paraLixeira) }.getOrNull() else null

    /** PendingIntent para excluir de vez (permanente). API 30+. */
    fun pedidoExcluir(ctx: Context, uris: List<android.net.Uri>): PendingIntent? =
        if (android.os.Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) runCatching { MediaStore.createDeleteRequest(ctx.contentResolver, uris) }.getOrNull() else null

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
