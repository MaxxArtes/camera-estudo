package br.maxymus.cameraestudo

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Auto-máscaras (pedido do dono, 17/09: as máscaras do Lightroom, "selecionar céu / assunto / fundo / pessoas", mas
 * automáticas, sem editor). Aplicadas depois da captura, fora do scanner, com valores conservadores:
 *
 *  - Céu: pixels fora da pessoa, no terço de cima, azulados ou claros e pouco saturados, ligados à borda de cima.
 *    Recebe compressão das altas luzes (acima de 170) e 12% a mais de cor. Só entra se cobrir ≥ 3% da foto.
 *  - Fundo (Radial Gradient automático): escurecimento suave até 22% nas bordas, centrado no rosto (ou no alto da foto),
 *    só fora da pessoa.
 *  - Rosto: clareza local de 10% (luminância menos a própria versão borrada, raio 3% da largura do rosto), na elipse.
 *  - Olhos: nitidez pequena (gaussiana 3x3, quantidade 0,6, teto ±8) e 3% de brilho numa elipse em cada olho.
 * Devolve o que agiu, para a telemetria. Sem pessoa detectada, céu e fundo usam máscara vazia de pessoa.
 */
object AutoMascaras {
    class Relatorio(val ceuPct: Int, val vinheta: Boolean, val rostos: Int, val olhos: Int)

    private fun suave(v: Float, a: Float, b: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Máscara de pessoa no tamanho da imagem (vizinho mais próximo), ou null se a segmentação falhar. */
    fun mascaraPessoa(b: Bitmap): FloatArray? = runCatching {
        val seg = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
        val m = Tasks.await(seg.process(InputImage.fromBitmap(b, 0))); seg.close()
        val mw = m.width; val mh = m.height; val bb = m.buffer; bb.rewind(); val conf = FloatArray(mw * mh) { bb.float }
        val w = b.width; val h = b.height
        FloatArray(w * h) { k -> conf[(k / w * mh / h).coerceIn(0, mh - 1) * mw + (k % w * mw / w).coerceIn(0, mw - 1)] }
    }.getOrNull()

    fun aplicar(b: Bitmap, pessoa: FloatArray?, rostos: List<Rostos.Rosto>, assunto: android.graphics.RectF? = null): Pair<Bitmap, Relatorio> {
        val w = b.width; val h = b.height; val n = w * h
        val px = IntArray(n).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val lum = Fusao.luminancia(px)

        // ---- céu: candidatos numa grade 1/4, componente ligado à borda de cima, feather por borrão
        val gw = max(1, w / 4); val gh = max(1, h / 4)
        val cand = BooleanArray(gw * gh)
        for (gy in 0 until (gh * 0.7f).toInt()) for (gx in 0 until gw) {
            val k = min(h - 1, gy * 4) * w + min(w - 1, gx * 4); val c = px[k]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            val y = lum[k]; val sat = max(r, max(g, bl)) - min(r, min(g, bl))
            // 17/09: parede interna clara passava como céu (7 a 28% numa sala) e virava manchas; azul exige B bem acima de R e G, claro exige quase branco
            val azul = bl >= r + 22 && bl >= g + 2; val claro = y > 225 && sat < 18
            cand[gy * gw + gx] = (pessoa?.get(k) ?: 0f) < 0.3f && y > 110 && (azul || claro)
        }
        val ceu = BooleanArray(gw * gh); val fila = IntArray(gw * gh); var fim = 0
        for (gx in 0 until gw) if (cand[gx]) { ceu[gx] = true; fila[fim++] = gx }
        var ini = 0
        while (ini < fim) { val p = fila[ini++]; val x = p % gw; val y = p / gw
            for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) { val xx = x + dx; val yy = y + dy
                if (xx in 0 until gw && yy in 0 until gh) { val q = yy * gw + xx; if (cand[q] && !ceu[q]) { ceu[q] = true; fila[fim++] = q } } } }
        val ceuCont = ceu.count { it }; val ceuPct = ceuCont * 100 / (gw * gh)
        // dilata 3 células antes do feather: a transição fica dentro da pessoa (já excluída por 1-p), não como orla clara no céu ao redor dela
        val ceuF = if (ceuPct >= 5) borraBool(dilata(ceu, gw, gh, 3), gw, gh, 3) else null

        // ---- centro do radial e elipses do rosto
        // centro do radial: rosto; sem rosto, o assunto principal do localizador; sem nada, o alto da foto
        val cx = rostos.firstOrNull()?.caixa?.exactCenterX() ?: (assunto?.let { it.centerX() * w } ?: (w / 2f))
        val cy = rostos.firstOrNull()?.caixa?.exactCenterY() ?: (assunto?.let { it.centerY() * h } ?: (h * 0.42f))
        val raioMax = 0.75f * hypot(w / 2f, h / 2f)

        // ---- passa única por pixel: céu + vinheta
        var vinheta = false
        for (y in 0 until h) for (x in 0 until w) {
            val k = y * w + x; val c = px[k]; var r = (c shr 16 and 255).toFloat(); var g = (c shr 8 and 255).toFloat(); var bl = (c and 255).toFloat()
            val p = pessoa?.get(k) ?: 0f
            var mudou = false
            if (ceuF != null) {
                val m = ceuF[min(gh - 1, y / 4) * gw + min(gw - 1, x / 4)] * (1f - p)
                if (m > 0.02f) {
                    val yv = lum[k].toFloat()
                    if (yv > 170f) { val alvo = 170f + (yv - 170f) * 0.75f; val f = alvo / yv; val f2 = 1f + (f - 1f) * m; r *= f2; g *= f2; bl *= f2 }
                    val y2 = (r * 54 + g * 183 + bl * 19) / 256f; val s = 1f + 0.12f * m
                    r = y2 + (r - y2) * s; g = y2 + (g - y2) * s; bl = y2 + (bl - y2) * s; mudou = true
                }
            }
            val d = hypot(x - cx, y - cy) / raioMax
            // 17/09 tarde: 16% a partir de 45% do raio, centrado no rosto, virava um foco de luz atrás da cabeça em parede lisa (selfies na sala);
            // agora é vinheta de canto: 10% e só a partir de 65% do raio
            val v = 1f - 0.10f * suave(d, 0.65f, 1.0f) * (1f - p)
            if (v < 0.999f) { r *= v; g *= v; bl *= v; mudou = true; vinheta = true }
            if (mudou) px[k] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or bl.toInt().coerceIn(0, 255)
        }

        // ---- rosto: clareza local na elipse; olhos: nitidez + brilho
        var olhos = 0
        for (rst in rostos) {
            val fx = rst.caixa.exactCenterX(); val fy = rst.caixa.exactCenterY(); val rx = rst.caixa.width() * 0.62f; val ry = rst.caixa.height() * 0.78f
            val x0 = max(0, (fx - rx).toInt()); val x1 = min(w, (fx + rx).toInt() + 1); val y0 = max(0, (fy - ry).toInt()); val y1 = min(h, (fy + ry).toInt() + 1)
            if (x1 <= x0 || y1 <= y0) continue
            val rw = x1 - x0; val rh = y1 - y0; val raio = max(2, (rst.caixa.width() * 0.03f).toInt())
            val lu = IntArray(rw * rh) { k -> luma(px[(y0 + k / rw) * w + x0 + k % rw]) }
            val base = caixaI(lu, rw, rh, raio)
            for (yy in 0 until rh) for (xx in 0 until rw) {
                val dx = (x0 + xx - fx) / rx; val dy = (y0 + yy - fy) / ry; val dd = dx * dx + dy * dy
                if (dd >= 1f) continue
                val m = 1f - suave(dd, 0.6f, 1f); val k = yy * rw + xx
                val delta = ((lu[k] - base[k]) * 0.10f * m).coerceIn(-12f, 12f)
                if (abs(delta) < 0.5f) continue
                val idx = (y0 + yy) * w + x0 + xx; val c = px[idx]; val f = if (lu[k] > 0) (lu[k] + delta) / lu[k] else 1f
                px[idx] = (0xFF shl 24) or (((c shr 16 and 255) * f).toInt().coerceIn(0, 255) shl 16) or (((c shr 8 and 255) * f).toInt().coerceIn(0, 255) shl 8) or ((c and 255) * f).toInt().coerceIn(0, 255)
            }
            for (olho in listOfNotNull(rst.olhoEsq, rst.olhoDir)) {
                val ex = olho.x; val ey = olho.y; val erx = rst.caixa.width() * 0.13f; val ery = rst.caixa.width() * 0.09f
                val ox0 = max(1, (ex - erx).toInt()); val ox1 = min(w - 1, (ex + erx).toInt() + 1); val oy0 = max(1, (ey - ery).toInt()); val oy1 = min(h - 1, (ey + ery).toInt() + 1)
                if (ox1 <= ox0 || oy1 <= oy0) continue
                val copia = px.copyOf()   // vizinhança original para o borrão 3x3
                for (yy in oy0 until oy1) for (xx in ox0 until ox1) {
                    val dx = (xx - ex) / erx; val dy = (yy - ey) / ery; val dd = dx * dx + dy * dy
                    if (dd >= 1f) continue
                    val m = 1f - suave(dd, 0.5f, 1f); val k = yy * w + xx
                    val l0 = luma(copia[k])
                    val g = (luma(copia[k - w - 1]) + 2 * luma(copia[k - w]) + luma(copia[k - w + 1]) + 2 * luma(copia[k - 1]) + 4 * l0 + 2 * luma(copia[k + 1]) + luma(copia[k + w - 1]) + 2 * luma(copia[k + w]) + luma(copia[k + w + 1])) / 16f
                    val delta = ((l0 - g) * 0.6f).coerceIn(-8f, 8f) * m
                    val f = (if (l0 > 0) (l0 + delta) / l0 else 1f) * (1f + 0.03f * m)
                    val c = copia[k]
                    px[k] = (0xFF shl 24) or (((c shr 16 and 255) * f).toInt().coerceIn(0, 255) shl 16) or (((c shr 8 and 255) * f).toInt().coerceIn(0, 255) shl 8) or ((c and 255) * f).toInt().coerceIn(0, 255)
                }
                olhos++
            }
        }
        b.recycle()
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) to Relatorio(if (ceuF != null) ceuPct else 0, vinheta, rostos.size, olhos)
    }

    private fun luma(c: Int) = ((c shr 16 and 255) * 54 + (c shr 8 and 255) * 183 + (c and 255) * 19) shr 8
    private fun dilata(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val o = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) { if (!m[y * w + x]) continue
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) o[yy * w + xx] = true }
        return o
    }
    private fun borraBool(m: BooleanArray, w: Int, h: Int, r: Int): FloatArray {
        val f = FloatArray(m.size) { if (m[it]) 1f else 0f }
        return caixaF(f, w, h, r)
    }
    private fun caixaF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(a.size); val o = FloatArray(a.size)
        for (y in 0 until h) { val l = y * w; var s = 0f; var c = 0; for (x in 0 until min(w, r)) { s += a[l + x]; c++ }
            for (x in 0 until w) { if (x + r < w) { s += a[l + x + r]; c++ }; if (x - r - 1 >= 0) { s -= a[l + x - r - 1]; c-- }; t[l + x] = s / c } }
        for (x in 0 until w) { var s = 0f; var c = 0; for (y in 0 until min(h, r)) { s += t[y * w + x]; c++ }
            for (y in 0 until h) { if (y + r < h) { s += t[(y + r) * w + x]; c++ }; if (y - r - 1 >= 0) { s -= t[(y - r - 1) * w + x]; c-- }; o[y * w + x] = s / c } }
        return o
    }
    private fun caixaI(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val f = caixaF(FloatArray(a.size) { a[it].toFloat() }, w, h, r); return IntArray(a.size) { (f[it] + 0.5f).toInt() }
    }
}
