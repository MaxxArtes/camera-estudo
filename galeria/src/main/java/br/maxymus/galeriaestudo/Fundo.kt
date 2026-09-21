package br.maxymus.galeriaestudo

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Fundo e retrato, portados do Retrato.kt/Acabamento.kt da câmera: máscara de pessoa pelo segmentador multiclasse
 * (256x256, maior bloco conectado), desfoque em disco NORMALIZADO em luz linear (pessoa com peso zero: sem halo claro
 * em volta dela), composição com alfa suave. A máscara é independente da resolução (sempre 256²), então a prévia e a
 * exportação batem. Modos: desfocar, preto e branco no fundo, cor sólida, remover (alfa) e embelezar a pele.
 */
object Fundo {
    enum class Modo { Nenhum, Desfocar, PretoEBranco, Cor, Remover }
    data class Parametros(val modo: Modo = Modo.Nenhum, val intensidade: Float = 60f, val cor: Int = 0xFFFFFFFF.toInt(), val pele: Float = 0f) {
        val neutro: Boolean get() = modo == Modo.Nenhum && pele == 0f
    }

    /** Máscara bruta 256x256 (1 = pessoa), já limpa; amostrada bilinear em qualquer tamanho. */
    class Mascara(val bruta: FloatArray, val peleBruta: FloatArray) {
        val lado = 256
        fun pessoa(x: Float, y: Float) = bilinear(bruta, x, y)
        fun pele(x: Float, y: Float) = bilinear(peleBruta, x, y)
        private fun bilinear(a: FloatArray, x: Float, y: Float): Float {
            val fx = (x * (lado - 1)).coerceIn(0f, lado - 1f); val fy = (y * (lado - 1)).coerceIn(0f, lado - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(lado - 1, x0 + 1); val y1 = min(lado - 1, y0 + 1); val tx = fx - x0; val ty = fy - y0
            return (a[y0 * lado + x0] * (1 - tx) + a[y0 * lado + x1] * tx) * (1 - ty) + (a[y1 * lado + x0] * (1 - tx) + a[y1 * lado + x1] * tx) * ty
        }
        /** fração da imagem coberta pela pessoa, 0..1 */
        val cobertura: Float get() { var c = 0; for (v in bruta) if (v > 0.5f) c++; return c.toFloat() / bruta.size }
    }

    private val paraLinear = FloatArray(256) { val c = it / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
    private val paraSrgb = IntArray(4097) { val l = it / 4096f; val c = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f; (c * 255f + 0.5f).toInt().coerceIn(0, 255) }
    private fun srgb(l: Float) = paraSrgb[(l.coerceIn(0f, 1f) * 4096f).toInt()]
    private fun suave(v: Float, a: Float, b: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Roda o segmentador (entrada reduzida a ≤512 px: o modelo é 256²) e limpa a máscara. Null se o modelo falhar. */
    fun segmentar(ctx: Context, b: Bitmap): Mascara? {
        val esc = min(1f, 512f / max(b.width, b.height))
        val peq = if (esc < 1f) Bitmap.createScaledBitmap(b, max(1, (b.width * esc).toInt()), max(1, (b.height * esc).toInt()), true) else b
        val mapa = try { Segmentos.segmentar(ctx, peq) } finally { if (peq !== b) peq.recycle() }
        mapa ?: return null
        val n = 256 * 256
        val pessoa = FloatArray(n) { k -> 1f - mapa.cats[k * 6 + Segmentos.FUNDO] }
        val pele = FloatArray(n) { k -> (mapa.cats[k * 6 + Segmentos.PELE_CORPO] + mapa.cats[k * 6 + Segmentos.PELE_ROSTO]).coerceIn(0f, 1f) }
        limpaMascara(pessoa, 256, 256)
        return Mascara(pessoa, pele)
    }

    /** Só o maior bloco conectado (>0,5) é pessoa; manchas soltas viram fundo (câmera, 17/09). */
    private fun limpaMascara(m: FloatArray, w: Int, h: Int) {
        val rotulo = IntArray(w * h); var k = 0; val tamanhos = ArrayList<Int>(); val fila = IntArray(w * h)
        for (i in 0 until w * h) {
            if (m[i] <= 0.5f || rotulo[i] != 0) continue
            k++; var ini = 0; var fim = 0; fila[fim++] = i; rotulo[i] = k; var n = 0
            while (ini < fim) { val p = fila[ini++]; n++; val x = p % w; val y = p / w
                for (d in 0 until 4) { val xx = x + (if (d == 0) -1 else if (d == 1) 1 else 0); val yy = y + (if (d == 2) -1 else if (d == 3) 1 else 0)
                    if (xx in 0 until w && yy in 0 until h) { val q = yy * w + xx; if (m[q] > 0.5f && rotulo[q] == 0) { rotulo[q] = k; fila[fim++] = q } } } }
            tamanhos += n
        }
        if (tamanhos.size <= 1) return
        val maior = tamanhos.indices.maxByOrNull { tamanhos[it] }!! + 1
        for (i in 0 until w * h) if (rotulo[i] != 0 && rotulo[i] != maior) m[i] = 0f
    }

    /** Média em disco de raio r por prefixos de linha (custo ∝ diâmetro). */
    private fun disco(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val pref = FloatArray((w + 1) * h)
        for (y in 0 until h) { var s = 0f; val l = y * (w + 1); val la = y * w; pref[l] = 0f; for (x in 0 until w) { s += a[la + x]; pref[l + x + 1] = s } }
        val dxs = IntArray(2 * r + 1) { val dy = it - r; sqrt((r * r - dy * dy).toFloat()).toInt() }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var cnt = 0
            for (dy in -r..r) { val yy = y + dy; if (yy < 0 || yy >= h) continue
                val dx = dxs[dy + r]; val x0 = max(0, x - dx); val x1 = min(w - 1, x + dx)
                s += pref[yy * (w + 1) + x1 + 1] - pref[yy * (w + 1) + x0]; cnt += x1 - x0 + 1 }
            out[y * w + x] = if (cnt > 0) s / cnt else 0f
        }
        return out
    }

    private fun caixa(px: IntArray, w: Int, h: Int, r: Int, desloc: Int): IntArray {
        val integ = LongArray((w + 1) * (h + 1))
        for (y in 1..h) { var linha = 0L; for (x in 1..w) { linha += (px[(y - 1) * w + x - 1] shr desloc and 255); integ[y * (w + 1) + x] = integ[(y - 1) * (w + 1) + x] + linha } }
        return IntArray(w * h) { k -> val x = k % w; val y = k / w
            val x0 = max(0, x - r); val y0 = max(0, y - r); val x1 = min(w, x + r + 1); val y1 = min(h, y + r + 1)
            val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
            (s / ((x1 - x0) * (y1 - y0))).toInt() }
    }

    /** Aplica o modo escolhido. Devolve bitmap novo (com alfa no modo Remover). */
    fun aplicar(b: Bitmap, m: Mascara, p: Parametros): Bitmap {
        if (p.neutro) return b
        val w = b.width; val h = b.height; val n = w * h
        var px = IntArray(n).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        if (p.pele > 0f) px = embelezar(px, w, h, m, p.pele)
        val saida = when (p.modo) {
            Modo.Nenhum -> px
            Modo.Desfocar -> desfocar(px, w, h, m, p.intensidade)
            Modo.PretoEBranco -> IntArray(n) { k -> val c = px[k]; val a = suave(m.pessoa((k % w) / (w - 1f), (k / w) / (h - 1f)), 0.2f, 0.8f)
                val l = ((c shr 16 and 255) * 54 + (c shr 8 and 255) * 183 + (c and 255) * 19) shr 8
                val r = ((c shr 16 and 255) * a + l * (1 - a)).toInt(); val g = ((c shr 8 and 255) * a + l * (1 - a)).toInt(); val bl = ((c and 255) * a + l * (1 - a)).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or bl }
            Modo.Cor -> { val cr = p.cor shr 16 and 255; val cg = p.cor shr 8 and 255; val cb = p.cor and 255
                IntArray(n) { k -> val c = px[k]; val a = suave(m.pessoa((k % w) / (w - 1f), (k / w) / (h - 1f)), 0.2f, 0.8f)
                    val r = ((c shr 16 and 255) * a + cr * (1 - a)).toInt(); val g = ((c shr 8 and 255) * a + cg * (1 - a)).toInt(); val bl = ((c and 255) * a + cb * (1 - a)).toInt()
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or bl } }
            Modo.Remover -> IntArray(n) { k -> val a = suave(m.pessoa((k % w) / (w - 1f), (k / w) / (h - 1f)), 0.2f, 0.8f)
                (px[k] and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24) }
        }
        return Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Desfoque do fundo: disco normalizado em luz linear numa cópia de 600 px (pessoa com peso 0), composto por alfa suave. */
    private fun desfocar(pFrente: IntArray, w: Int, h: Int, m: Mascara, intensidade: Float): IntArray {
        val escF = min(1f, 600f / max(w, h)); val fw = max(1, (w * escF).toInt()); val fh = max(1, (h * escF).toInt())
        val lr = FloatArray(fw * fh); val lg = FloatArray(fw * fh); val lb = FloatArray(fw * fh); val peso = FloatArray(fw * fh)
        for (y in 0 until fh) for (x in 0 until fw) {
            val sx = min(w - 1, (x / escF).toInt()); val sy = min(h - 1, (y / escF).toInt()); val c = pFrente[sy * w + sx]
            val pf = 1f - suave(m.pessoa(x / (fw - 1f), y / (fh - 1f)), 0.05f, 0.30f)
            val k = y * fw + x; peso[k] = pf; lr[k] = paraLinear[c shr 16 and 255] * pf; lg[k] = paraLinear[c shr 8 and 255] * pf; lb[k] = paraLinear[c and 255] * pf
        }
        val raio = (2f + (intensidade / 100f) * 8f).toInt().coerceIn(2, 10)
        val dR = disco(lr, fw, fh, raio); val dG = disco(lg, fw, fh, raio); val dB = disco(lb, fw, fh, raio); val dP = disco(peso, fw, fh, raio)
        val rG = min(3 * raio, 30)
        val gR = disco(lr, fw, fh, rG); val gG = disco(lg, fw, fh, rG); val gB = disco(lb, fw, fh, rG); val gP = disco(peso, fw, fh, rG)
        val fR = FloatArray(fw * fh); val fG = FloatArray(fw * fh); val fB = FloatArray(fw * fh)
        for (k in 0 until fw * fh) {
            if (dP[k] > 0.02f) { fR[k] = dR[k] / dP[k]; fG[k] = dG[k] / dP[k]; fB[k] = dB[k] / dP[k] }
            else if (gP[k] > 0.005f) { fR[k] = gR[k] / gP[k]; fG[k] = gG[k] / gP[k]; fB[k] = gB[k] / gP[k] }
            else { fR[k] = lr[k]; fG[k] = lg[k]; fB[k] = lb[k] }
        }
        fun fundo(arr: FloatArray, x: Float, y: Float): Float {
            val fx = (x * (fw - 1)).coerceIn(0f, fw - 1f); val fy = (y * (fh - 1)).coerceIn(0f, fh - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(fw - 1, x0 + 1); val y1 = min(fh - 1, y0 + 1); val tx = fx - x0; val ty = fy - y0
            return (arr[y0 * fw + x0] * (1 - tx) + arr[y0 * fw + x1] * tx) * (1 - ty) + (arr[y1 * fw + x0] * (1 - tx) + arr[y1 * fw + x1] * tx) * ty
        }
        val saida = IntArray(w * h)
        for (y in 0 until h) { val ny = y / (h - 1f)
            for (x in 0 until w) {
                val i = y * w + x; val nx = x / (w - 1f)
                val a = suave(m.pessoa(nx, ny), 0.20f, 0.80f)
                val f = pFrente[i]
                if (a >= 0.995f) { saida[i] = f; continue }
                val br = srgb(fundo(fR, nx, ny)); val bg = srgb(fundo(fG, nx, ny)); val bl = srgb(fundo(fB, nx, ny))
                val r = ((f shr 16 and 255) * a + br * (1 - a)).toInt(); val g = ((f shr 8 and 255) * a + bg * (1 - a)).toInt(); val b = ((f and 255) * a + bl * (1 - a)).toInt()
                saida[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return saida
    }

    /** Suaviza a pele (máscara do modelo) preservando detalhe forte (olho, boca, cabelo). forca 0..100. */
    private fun embelezar(px: IntArray, w: Int, h: Int, m: Mascara, forca: Float): IntArray {
        val raio = (max(w, h) / 800f * (2f + forca / 25f)).toInt().coerceIn(2, 12)
        val bR = caixa(px, w, h, raio, 16); val bG = caixa(px, w, h, raio, 8); val bB = caixa(px, w, h, raio, 0)
        val f = forca / 100f
        return IntArray(w * h) { k ->
            val c = px[k]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            val lum = (r * 54 + g * 183 + bl * 19) shr 8; val lb = (bR[k] * 54 + bG[k] * 183 + bB[k] * 19) shr 8
            val borda = 1f - ((abs(lum - lb) - 6f) / 18f).coerceIn(0f, 1f)
            val wgt = f * m.pele((k % w) / (w - 1f), (k / w) / (h - 1f)) * borda
            if (wgt <= 0.001f) c else {
                val rr = (r + (bR[k] - r) * wgt).toInt(); val gg = (g + (bG[k] - g) * wgt).toInt(); val bb2 = (bl + (bB[k] - bl) * wgt).toInt()
                (0xFF shl 24) or (rr.coerceIn(0, 255) shl 16) or (gg.coerceIn(0, 255) shl 8) or bb2.coerceIn(0, 255)
            }
        }
    }
}
