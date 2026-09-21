package br.maxymus.galeriaestudo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Motor do editor: tudo o que não é tela. Cor por ColorMatrix (tempo real na prévia, o mesmo no arquivo);
 * geometria (girar, espelhar, endireitar, recortar) aplicada só ao salvar, na imagem cheia. Salvar é SEMPRE
 * cópia: a original nunca é tocada. Lado maior da cópia limitado (50 MP em ARGB não cabe na memória).
 */
object Edicao {
    const val LADO_MAX_COPIA = 4096
    const val LADO_MAX_PREVIA = 2048

    /** Parâmetros de cor, todos -100..100 (0 = neutro). */
    data class Cor(
        val brilho: Float = 0f, val contraste: Float = 0f, val saturacao: Float = 0f,
        val temperatura: Float = 0f, val matiz: Float = 0f,
        val filtro: String = "Original", val intensidade: Float = 100f, val exposicao: Float = 0f
    ) {
        val neutra: Boolean get() = brilho == 0f && contraste == 0f && saturacao == 0f && temperatura == 0f && matiz == 0f && filtro == "Original" && exposicao == 0f
    }

    /** Geometria: giros de 90° (0..3), espelho horizontal, endireitar em graus, recorte normalizado sobre a imagem já orientada e endireitada. */
    data class Geometria(
        val giros: Int = 0, val espelhado: Boolean = false, val endireitar: Float = 0f,
        val recorte: RectF = RectF(0f, 0f, 1f, 1f)
    ) {
        val neutra: Boolean get() = giros == 0 && !espelhado && endireitar == 0f && recorte == RectF(0f, 0f, 1f, 1f)
    }

    /** Receita completa e serializável (o histórico e o banco guardam ISSO, nunca bitmaps). */
    data class Receita(val cor: Cor = Cor(), val geo: Geometria = Geometria(), val tom: Tom.Parametros = Tom.Parametros(), val fundo: Fundo.Parametros = Fundo.Parametros(), val hsl: Hsl.Parametros = Hsl.Parametros()) {
        val neutra: Boolean get() = cor.neutra && geo.neutra && tom.neutro && fundo.neutro && hsl.neutro
        fun toJson(): String = org.json.JSONObject().apply {
            put("v", 1)
            put("cor", org.json.JSONObject().apply { put("brilho", cor.brilho); put("contraste", cor.contraste); put("saturacao", cor.saturacao); put("temperatura", cor.temperatura); put("matiz", cor.matiz); put("filtro", cor.filtro); put("intensidade", cor.intensidade); put("exposicao", cor.exposicao) })
            put("geo", org.json.JSONObject().apply { put("giros", geo.giros); put("espelhado", geo.espelhado); put("endireitar", geo.endireitar); put("recorte", org.json.JSONArray(listOf(geo.recorte.left, geo.recorte.top, geo.recorte.right, geo.recorte.bottom))) })
            put("tom", org.json.JSONObject().apply { put("realces", tom.realces); put("sombras", tom.sombras); put("brancos", tom.brancos); put("pretos", tom.pretos); put("nitidez", tom.nitidez); put("vinheta", tom.vinheta); put("granulacao", tom.granulacao); put("textura", tom.textura); put("clareza", tom.clareza) })
            put("hsl", org.json.JSONObject().apply { put("m", org.json.JSONArray(hsl.matiz)); put("s", org.json.JSONArray(hsl.saturacao)); put("l", org.json.JSONArray(hsl.luminancia)) })
            put("fundo", org.json.JSONObject().apply { put("modo", fundo.modo.name); put("intensidade", fundo.intensidade); put("cor", fundo.cor); put("pele", fundo.pele) })
        }.toString()
        companion object {
            private fun hslDe(hj: org.json.JSONObject?): Hsl.Parametros {
                if (hj == null) return Hsl.Parametros()
                fun lista(k: String): List<Float> { val a = hj.getJSONArray(k); return List(8) { i -> a.getDouble(i).toFloat() } }
                return Hsl.Parametros(lista("m"), lista("s"), lista("l"))
            }
            fun fromJson(j: String): Receita? = runCatching {
                val o = org.json.JSONObject(j); val c = o.getJSONObject("cor"); val g = o.getJSONObject("geo"); val t = o.getJSONObject("tom"); val f = o.getJSONObject("fundo"); val r = g.getJSONArray("recorte")
                Receita(
                    Cor(c.getDouble("brilho").toFloat(), c.getDouble("contraste").toFloat(), c.getDouble("saturacao").toFloat(), c.getDouble("temperatura").toFloat(), c.getDouble("matiz").toFloat(), c.getString("filtro"), c.getDouble("intensidade").toFloat(), c.optDouble("exposicao", 0.0).toFloat()),
                    Geometria(g.getInt("giros"), g.getBoolean("espelhado"), g.getDouble("endireitar").toFloat(), RectF(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())),
                    Tom.Parametros(t.getDouble("realces").toFloat(), t.getDouble("sombras").toFloat(), t.getDouble("brancos").toFloat(), t.getDouble("pretos").toFloat(), t.getDouble("nitidez").toFloat(), t.getDouble("vinheta").toFloat(), t.getDouble("granulacao").toFloat(), t.optDouble("textura", 0.0).toFloat(), t.optDouble("clareza", 0.0).toFloat()),
                    Fundo.Parametros(Fundo.Modo.valueOf(f.getString("modo")), f.getDouble("intensidade").toFloat(), f.getInt("cor"), f.getDouble("pele").toFloat()),
                    hslDe(o.optJSONObject("hsl"))
                )
            }.getOrNull()
        }
    }

    val FILTROS = listOf("Original", "Vívido", "Quente", "Frio", "P&B", "Sépia", "Fade", "Contraste")

    // ---------- cor ----------
    private fun escala(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    private fun brilhoM(v: Float): ColorMatrix { val t = v * 0.9f; return ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, t, 0f, 1f, 0f, 0f, t, 0f, 0f, 1f, 0f, t, 0f, 0f, 0f, 1f, 0f)) }
    private fun contrasteM(v: Float): ColorMatrix { val s = (1f + v / 100f).coerceAtLeast(0.2f); val t = 128f * (1f - s); return ColorMatrix(floatArrayOf(s, 0f, 0f, 0f, t, 0f, s, 0f, 0f, t, 0f, 0f, s, 0f, t, 0f, 0f, 0f, 1f, 0f)) }
    private fun saturacaoM(v: Float) = ColorMatrix().apply { setSaturation((1f + v / 100f).coerceAtLeast(0f)) }
    private fun temperaturaM(v: Float) = escala(1f + 0.18f * v / 100f, 1f, 1f - 0.18f * v / 100f)
    private fun matizM(v: Float) = escala(1f, 1f + 0.12f * v / 100f, 1f)
    private fun exposicaoM(v: Float): ColorMatrix { val g = Math.pow(2.0, (v / 100f * 1.5f).toDouble()).toFloat(); return escala(g, g, g) }

    private fun preset(nome: String): ColorMatrix = when (nome) {
        "Vívido" -> ColorMatrix().apply { postConcat(saturacaoM(30f)); postConcat(contrasteM(12f)) }
        "Quente" -> ColorMatrix().apply { postConcat(temperaturaM(45f)); postConcat(saturacaoM(8f)) }
        "Frio" -> ColorMatrix().apply { postConcat(temperaturaM(-45f)) }
        "P&B" -> saturacaoM(-100f)
        "Sépia" -> ColorMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        "Fade" -> ColorMatrix().apply { postConcat(contrasteM(-18f)); postConcat(brilhoM(14f)); postConcat(saturacaoM(-15f)) }
        "Contraste" -> contrasteM(35f)
        "Filme" -> ColorMatrix().apply { postConcat(escala(1.05f, 1f, 0.94f)); postConcat(contrasteM(10f)); postConcat(saturacaoM(-10f)) }
        else -> ColorMatrix()
    }

    /** Matriz final: filtro (com intensidade) e depois os ajustes. Afim, então interpolar com a identidade é válido. */
    fun matriz(c: Cor): ColorMatrix {
        val m = ColorMatrix()
        m.postConcat(exposicaoM(c.exposicao)); m.postConcat(brilhoM(c.brilho)); m.postConcat(contrasteM(c.contraste)); m.postConcat(saturacaoM(c.saturacao))
        m.postConcat(temperaturaM(c.temperatura)); m.postConcat(matizM(c.matiz))
        val f = preset(c.filtro).array; val id = ColorMatrix().array; val k = (c.intensidade / 100f).coerceIn(0f, 1f)
        m.postConcat(ColorMatrix(FloatArray(20) { id[it] * (1f - k) + f[it] * k }))
        return m
    }

    fun aplicaCor(b: Bitmap, c: Cor): Bitmap {
        if (c.neutra) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matriz(c)) })
        return out
    }

    // ---------- geometria ----------
    /** Fator de zoom (>=1) que esconde os cantos vazios ao endireitar: maior retângulo de mesma proporção inscrito na imagem girada. */
    fun zoomEndireitar(w: Float, h: Float, graus: Float): Float {
        if (graus == 0f || w <= 0f || h <= 0f) return 1f
        val r = Math.toRadians(graus.toDouble()); val c = abs(cos(r)).toFloat(); val s = abs(sin(r)).toFloat()
        val k = min(w / (w * c + h * s), h / (w * s + h * c))
        return 1f / k.coerceIn(0.05f, 1f)
    }

    /** Aplica giros/espelho/endireitar (com zoom para encher) e recorte. Devolve bitmap novo (ou o mesmo, se tudo neutro). */
    fun aplicaGeometria(b: Bitmap, g: Geometria): Bitmap {
        if (g.neutra) return b
        // 1) giros de 90° + espelho
        var atual = b
        if (g.giros != 0 || g.espelhado) {
            val m = Matrix().apply { if (g.espelhado) preScale(-1f, 1f); postRotate(90f * g.giros) }
            val r = Bitmap.createBitmap(atual, 0, 0, atual.width, atual.height, m, true)
            if (r !== atual && atual !== b) atual.recycle()
            atual = r
        }
        // 2) endireitar: gira em torno do centro e amplia para encher (sem cantos vazios)
        if (g.endireitar != 0f) {
            val w = atual.width; val h = atual.height; val z = zoomEndireitar(w.toFloat(), h.toFloat(), g.endireitar)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                translate(w / 2f, h / 2f); rotate(g.endireitar); scale(z, z); translate(-w / 2f, -h / 2f)
                drawBitmap(atual, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            if (atual !== b) atual.recycle()
            atual = out
        }
        // 3) recorte normalizado
        val rc = g.recorte
        if (rc.left > 0f || rc.top > 0f || rc.right < 1f || rc.bottom < 1f) {
            val x = (rc.left * atual.width).roundToInt().coerceIn(0, atual.width - 1)
            val y = (rc.top * atual.height).roundToInt().coerceIn(0, atual.height - 1)
            val cw = ((rc.right - rc.left) * atual.width).roundToInt().coerceIn(1, atual.width - x)
            val ch = ((rc.bottom - rc.top) * atual.height).roundToInt().coerceIn(1, atual.height - y)
            val r = Bitmap.createBitmap(atual, x, y, cw, ch)   // pode devolver a própria fonte no recorte-identidade
            if (r !== atual && atual !== b) atual.recycle()
            atual = r
        }
        return atual
    }

    // ---------- carregar ----------
    /** Decodifica com o lado maior limitado e a orientação do EXIF aplicada (o editor trabalha sobre a imagem "em pé"). */
    fun carregar(ctx: Context, uri: Uri, ladoMax: Int): Bitmap? {
        val cr = ctx.contentResolver
        val lim = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, lim) }
        if (lim.outWidth <= 0 || lim.outHeight <= 0) return null
        var amostra = 1
        while (maxOf(lim.outWidth, lim.outHeight) / (amostra * 2) >= ladoMax) amostra *= 2
        val op = BitmapFactory.Options().apply { inSampleSize = amostra; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bruto = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) } ?: return null
        val orient = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.preScale(-1f, 1f); m.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.preScale(-1f, 1f); m.postRotate(90f) }
            else -> return bruto
        }
        val emPe = Bitmap.createBitmap(bruto, 0, 0, bruto.width, bruto.height, m, true)
        if (emPe !== bruto) bruto.recycle()
        return emPe
    }

    // ---------- salvar ----------
    class Salva(val uri: Uri, val nome: String, val largura: Int, val altura: Int)

    /**
     * Grava a cópia na mesma pasta da original (ou Pictures/Galeria Estudo), JPEG 95, com data/hora do EXIF copiadas.
     * Exige Android 10+ (MediaStore com RELATIVE_PATH e IS_PENDING; sem permissão de escrita legada).
     */
    fun salvarCopia(ctx: Context, original: Midia, pronta: Bitmap, png: Boolean = false): Salva {
        require(Build.VERSION.SDK_INT >= 29) { "Salvar cópia exige Android 10 ou mais novo" }
        val cr = ctx.contentResolver
        val nomeOrig = runCatching {
            cr.query(original.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "foto"
        val base = nomeOrig.substringBeforeLast('.').take(60)
        val nome = "${base}_edit_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}." + (if (png) "png" else "jpg")
        val pasta = original.pasta.ifBlank { "Pictures/Galeria Estudo/" }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome); put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, pasta); put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (original.quando > 0L) put(MediaStore.Images.Media.DATE_TAKEN, original.quando)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("MediaStore recusou a cópia")
        try {
            cr.openOutputStream(uri)!!.use { if (png) pronta.compress(Bitmap.CompressFormat.PNG, 100, it) else pronta.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            // EXIF: só data/hora e marca do app; sem GPS e sem orientação (a imagem já está em pé)
            if (!png) runCatching {
                val dataOrig = cr.openInputStream(original.uri)?.use { ExifInterface(it).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) }
                cr.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val ex = ExifInterface(pfd.fileDescriptor)
                    if (dataOrig != null) { ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dataOrig); ex.setAttribute(ExifInterface.TAG_DATETIME, dataOrig) }
                    ex.setAttribute(ExifInterface.TAG_SOFTWARE, "Galeria Estudo (editada)")
                    ex.saveAttributes()
                }
            }
            v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, v, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        return Salva(uri, nome, pronta.width, pronta.height)
    }
}
