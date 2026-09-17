package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Acabamento da foto (como o "Embelezador" e os "Filtros" da câmera da Xiaomi), aplicado depois da captura,
 * fora do scanner.
 *
 *  - Filtros: matriz de cor 4x5. A MESMA matriz colore a miniatura ao vivo (Compose ColorFilter) e a foto gravada
 *    (Paint com ColorMatrixColorFilter), então o que se vê é o que sai.
 *  - Embelezador (0..100): suavização de pele que preserva bordas. Máscara = pessoa (ML Kit, para não alisar piso e
 *    parede cor de pele) x tom de pele (faixa Cb/Cr) x "não é borda" (diferença para o borrão pequena). O pixel
 *    caminha em direção ao borrão na medida da força. Sem modelo de rosto; é o "surface blur" clássico.
 */
object Acabamento {
    /** matriz: cor por 4x5 (miniatura e foto). vibrancia > 0: antes da matriz, reforça as cores fracas mais que as fortes e
     *  protege a pele (medido 16/09: Vívido por saturação pura deixou a pele 0,69/0,39/0,29, laranja; na miniatura a
     *  vibrância é aproximada por uma saturação leve dentro da própria matriz). */
    class Filtro(val nome: String, val matriz: FloatArray, val vibrancia: Float = 0f)

    private fun sat(s: Float): FloatArray {   // matriz de saturação (luma Rec.601)
        val ir = 0.299f * (1 - s); val ig = 0.587f * (1 - s); val ib = 0.114f * (1 - s)
        return floatArrayOf(ir + s, ig, ib, 0f, 0f, ir, ig + s, ib, 0f, 0f, ir, ig, ib + s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }
    private fun mult(a: FloatArray, b: FloatArray): FloatArray = ColorMatrix(a).apply { postConcat(ColorMatrix(b)) }.array
    private fun ganho(r: Float, g: Float, b: Float, c: Float = 1f, desloc: Float = 0f): FloatArray {
        // contraste c em torno de 128 e ganho por canal; desloc levanta o preto (efeito "filme")
        val o = 128f * (1 - c) + desloc
        return floatArrayOf(r * c, 0f, 0f, 0f, o, 0f, g * c, 0f, 0f, o, 0f, 0f, b * c, 0f, o, 0f, 0f, 0f, 1f, 0f)
    }

    val FILTROS: List<Filtro> = listOf(
        Filtro("Original", floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
        // 16/09: o dono achou que "não aplicava" — o Vívido a 1,3 de saturação era sutil demais perto dos filtros da Xiaomi. Reforçados.
        Filtro("Vívido", mult(sat(1.15f), ganho(1f, 1f, 1f, 1.06f)), vibrancia = 0.9f),
        Filtro("Natural", mult(sat(0.78f), ganho(1.04f, 1f, 0.94f, 0.94f, 12f))),   // suave: menos cor, morno, preto levantado
        Filtro("Quente", mult(sat(1.1f), ganho(1.15f, 1.0f, 0.8f))),
        Filtro("Frio", mult(sat(1.05f), ganho(0.85f, 0.97f, 1.18f))),
        Filtro("Cinema", mult(sat(0.75f), ganho(1.1f, 0.96f, 0.85f, 0.88f, 22f))),
        Filtro("Positivo", mult(sat(1.05f), ganho(1.05f, 1f, 0.92f, 0.85f, 34f)), vibrancia = 0.6f),
        Filtro("P&B", mult(sat(0f), ganho(1f, 1f, 1f, 1.2f))),
        Filtro("Sépia", mult(sat(0f), ganho(1.15f, 1.0f, 0.78f, 0.95f, 12f)))
    )
    fun filtro(nome: String): Filtro = FILTROS.firstOrNull { it.nome == nome } ?: FILTROS[0]

    /** Aplica a matriz do filtro; devolve o mesmo bitmap se for "Original". */
    fun aplicaFiltro(b: Bitmap, f: Filtro): Bitmap {
        if (f.nome == "Original") return b
        val w = b.width; val h = b.height
        val original = if (f.vibrancia > 0f) IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } else null
        val fonte = if (f.vibrancia > 0f) vibrancia(b, f.vibrancia) else b
        val saida = Bitmap.createBitmap(fonte.width, fonte.height, Bitmap.Config.ARGB_8888)
        Canvas(saida).drawBitmap(fonte, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(f.matriz)) })
        fonte.recycle()
        // Codex 17/09: a pele era protegida na vibrância mas a matriz final (saturação 1,15) desfazia; agora a pele volta 70% ao original
        if (original != null) {
            val px = IntArray(w * h).also { saida.getPixels(it, 0, w, 0, 0, w, h) }
            for (k in px.indices) {
                val o = original[k]; val r = o shr 16 and 255; val g = o shr 8 and 255; val bl = o and 255
                val cb = 128 - 0.1687f * r - 0.3313f * g + 0.5f * bl; val cr = 128 + 0.5f * r - 0.4187f * g - 0.0813f * bl
                val pele = suave(cb, 77f, 85f, 120f, 130f) * suave(cr, 130f, 138f, 170f, 178f) * 0.7f
                if (pele <= 0.01f) continue
                val c = px[k]
                val rr = ((c shr 16 and 255) + (r - (c shr 16 and 255)) * pele).toInt(); val gg = ((c shr 8 and 255) + (g - (c shr 8 and 255)) * pele).toInt(); val bb = ((c and 255) + (bl - (c and 255)) * pele).toInt()
                px[k] = (0xFF shl 24) or (rr.coerceIn(0, 255) shl 16) or (gg.coerceIn(0, 255) shl 8) or bb.coerceIn(0, 255)
            }
            saida.setPixels(px, 0, w, 0, 0, w, h)
        }
        return saida
    }

    /** Vibrância: ganho de croma = v x (1 − saturação atual) x (1 − 0,75 x pele). Cinza fica cinza; cor já forte quase não muda. */
    private fun vibrancia(b: Bitmap, v: Float): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }; b.recycle()
        for (k in px.indices) {
            val c = px[k]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl)); if (mx == 0) continue
            val s = (mx - mn) / mx.toFloat()
            val cb = 128 - 0.1687f * r - 0.3313f * g + 0.5f * bl; val cr = 128 + 0.5f * r - 0.4187f * g - 0.0813f * bl
            val pele = suave(cb, 77f, 85f, 120f, 130f) * suave(cr, 130f, 138f, 170f, 178f)
            val ganho = 1f + v * (1f - s) * (1f - 0.75f * pele)
            val y = (r * 54 + g * 183 + bl * 19) shr 8
            val rr = (y + (r - y) * ganho).toInt().coerceIn(0, 255); val gg = (y + (g - y) * ganho).toInt().coerceIn(0, 255); val bb = (y + (bl - y) * ganho).toInt().coerceIn(0, 255)
            px[k] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Suavização de pele. forca 0..100. Usa a segmentação de pessoa quando disponível (falha em silêncio sem ela). */
    fun embelezar(b: Bitmap, forca: Int): Bitmap {
        if (forca <= 0) return b
        val w = b.width; val h = b.height; val n = w * h
        val px = IntArray(n).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        // máscara de pessoa (ML Kit), amostrada no tamanho da imagem
        val pessoa: FloatArray? = runCatching {
            val seg = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
            val m = Tasks.await(seg.process(InputImage.fromBitmap(b, 0))); seg.close()
            val mw = m.width; val mh = m.height; val bb = m.buffer; bb.rewind(); val conf = FloatArray(mw * mh) { bb.float }
            FloatArray(n) { k -> conf[(k / w * mh / h).coerceIn(0, mh - 1) * mw + (k % w * mw / w).coerceIn(0, mw - 1)] }
        }.getOrNull()
        // borrão por integral (caixa) com raio pela força: 3 px a 100 px de lado... escala com a imagem
        val raio = (max(w, h) / 800f * (2f + forca / 25f)).toInt().coerceIn(2, 12)
        val lum = Fusao.luminancia(px)
        val bR = caixa(px, w, h, raio, 16); val bG = caixa(px, w, h, raio, 8); val bB = caixa(px, w, h, raio, 0)
        val f = forca / 100f
        val saida = IntArray(n) { k ->
            val c = px[k]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            // tom de pele em YCbCr (faixa clássica), com transição suave nas bordas da faixa
            val cb = 128 - 0.1687f * r - 0.3313f * g + 0.5f * bl; val cr = 128 + 0.5f * r - 0.4187f * g - 0.0813f * bl
            val pele = suave(cb, 77f, 85f, 120f, 130f) * suave(cr, 130f, 138f, 170f, 178f) * (if (lum[k] > 40) 1f else 0f)
            val lb = (bR[k] * 54 + bG[k] * 183 + bB[k] * 19) shr 8
            val borda = 1f - ((abs(lum[k] - lb) - 6f) / 18f).coerceIn(0f, 1f)     // detalhe forte (olho, boca, cabelo) fica
            val p = pessoa?.get(k) ?: 1f
            val wgt = f * pele * borda * p
            if (wgt <= 0.001f) c else {
                val rr = (r + (bR[k] - r) * wgt).toInt(); val gg = (g + (bG[k] - g) * wgt).toInt(); val bb2 = (bl + (bB[k] - bl) * wgt).toInt()
                (0xFF shl 24) or (rr.coerceIn(0, 255) shl 16) or (gg.coerceIn(0, 255) shl 8) or bb2.coerceIn(0, 255)
            }
        }
        b.recycle()
        return Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
    }
    private fun suave(v: Float, a0: Float, a1: Float, b0: Float, b1: Float): Float {
        val sobe = ((v - a0) / (a1 - a0)).coerceIn(0f, 1f); val desce = 1f - ((v - b0) / (b1 - b0)).coerceIn(0f, 1f); return min(sobe, desce)
    }
    /** média em caixa (2r+1)² de um canal, por imagem integral. */
    private fun caixa(px: IntArray, w: Int, h: Int, r: Int, desloc: Int): IntArray {
        val integ = LongArray((w + 1) * (h + 1))
        for (y in 1..h) { var linha = 0L; for (x in 1..w) { linha += (px[(y - 1) * w + x - 1] shr desloc and 255); integ[y * (w + 1) + x] = integ[(y - 1) * (w + 1) + x] + linha } }
        return IntArray(w * h) { k -> val x = k % w; val y = k / w
            val x0 = max(0, x - r); val y0 = max(0, y - r); val x1 = min(w, x + r + 1); val y1 = min(h, y + r + 1)
            val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
            (s / ((x1 - x0) * (y1 - y0))).toInt() }
    }

    /** EXIF só aceita ASCII no campo Software ("Vívido" virava "V?vido"). */
    fun semAcento(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("[^\\p{ASCII}]"), "")

    /** Pipeline completo sobre um bitmap: embelezador e depois filtro. */
    fun aplicar(b: Bitmap, filtro: String, forca: Int): Bitmap = aplicaFiltro(embelezar(b, forca), filtro(filtro))

    /**
     * Sobre uma foto já gravada (caminho simples): decodifica, reconhece pessoas na imagem crua (cadastro + âncora de
     * pele), aplica embelezador e filtro, regrava se algo mudou. Devolve o tempo em ms ou -1 se nada a fazer.
     */
    suspend fun aplicarEmArquivo(contexto: Context, uri: android.net.Uri, filtro: String, forca: Int): Long {
        if (forca <= 0 && filtro == "Original" && !Pessoas.ligado) return -1
        val t = System.nanoTime()
        val b = Documento.decodeReduzido(contexto, uri, 2400) ?: return -1
        val rec = Pessoas.processar(contexto, b)
        Telemetria.evento("rostos", mapOf("n" to rec.reconhecidos.size, "reconhecidos" to rec.reconhecidos.count { it.pessoa != null && !it.novo }, "novos" to rec.reconhecidos.count { it.novo },
            "sim_max" to (rec.reconhecidos.maxOfOrNull { it.sim }?.let { Math.round(it * 100) / 100.0 }), "pele_correcao" to Math.round(rec.correcaoPele * 10) / 10.0,
            "erro_detector" to Rostos.ultimoErro, "lado" to max(b.width, b.height)))
        if (forca <= 0 && filtro == "Original" && rec.correcaoPele <= 0f) { b.recycle(); return (System.nanoTime() - t) / 1_000_000 }
        val pronto = aplicar(b, filtro, forca)
        contexto.contentResolver.openOutputStream(uri, "wt")?.use { pronto.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        pronto.recycle()
        Fotos.gravaExif(contexto, uri, "Camera Estudo (filtro ${semAcento(filtro)}, embelezador $forca)")
        return (System.nanoTime() - t) / 1_000_000
    }
}
