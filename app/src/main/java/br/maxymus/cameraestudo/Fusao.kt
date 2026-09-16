package br.maxymus.cameraestudo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fusão de várias fotos, em Kotlin puro (sem OpenCV, GPU ou JNI). Tudo medido em Python antes
 * (scratchpad/fusao/proto.py, rajadas sintéticas com verdade-terreno):
 *
 *  - Alinhamento: MTB de Ward 2003 (bitmap pela mediana, XOR numa pirâmide de 6 níveis, ±1 px por nível),
 *    depois refino por ladrilho de 128 px com busca ±2 px por SAD. Erro medido ≤ 1 px; o refino rendeu +1 dB
 *    contra rotação de até 0,4°.
 *  - Rajada (mesma exposição, N quadros): referência = quadro mais nítido (variância do laplaciano); cada outro
 *    quadro entra com peso exp(-(d/τ)²), d = diferença de luminância para a referência, τ = 2,5 σ, σ estimado
 *    pela mediana da diferença entre dois quadros alinhados (o conteúdo cancela, o ruído fica). Medido: +3 dB
 *    sobre 1 quadro, nitidez igual à da imagem limpa; média simples PIOROU (−5 dB) e "ladrilho mais nítido"
 *    manteve o ruído. É o espírito do merge do HDR+ (Hasinoff 2016) no domínio espacial, sem Fourier.
 *  - HDR: exposure fusion de Mertens 2007 (pesos contraste × saturação × boa exposição, σ = 0,2; pirâmide
 *    laplaciana de 6 níveis, base ~25 px a 1600 px — menos que isso dá halo, apontado pelo agy) feita na LUMINÂNCIA; a cor vem da mistura das exposições pelos mesmos pesos,
 *    reescalada para a luminância fundida. Corta 3x o custo e a memória em relação a fundir R, G e B. Sem
 *    tone mapping; um esticamento leve de contraste no fim porque a fusão pura sai acinzentada.
 *  - Noite (medido em cena escura sintética, ruído nos blocos lisos): foto simples 0,041, Mertens 0,063 (pior: o
 *    quadro longo é ruidoso e a boa-exposição o escolhe para o céu), Mertens com penalidade por ruído 0,055, rajada
 *    de 4 na mesma exposição + gama 0,7 = 0,037. Por isso o HDR mede o brilho do 1º quadro e, escuro, vira Noite.
 */
object Fusao {
    class Plano(val w: Int, val h: Int, val v: FloatArray)

    // ---------- utilidades ----------
    /**
     * JPEG do CameraX → Bitmap com lado maior = ladoMax (inSampleSize em potência de 2 e depois escala exata),
     * SEM girar: gira-se só o resultado fundido. A escala exata importa: na frontal do dono (quadros de 2592 px)
     * "2000" e "2600" davam a mesma imagem de 2592 px e 394 MB de pico com 6 quadros.
     */
    fun decodifica(bytes: ByteArray, ladoMax: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maior = max(bounds.outWidth, bounds.outHeight)
        var amostra = 1; while (maior / (amostra * 2) >= ladoMax) amostra *= 2
        val bruto = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return null
        val lado = max(bruto.width, bruto.height)
        if (lado <= ladoMax * 1.04f) return bruto
        val esc = ladoMax.toFloat() / lado
        val menor = Bitmap.createScaledBitmap(bruto, max(1, (bruto.width * esc).toInt()), max(1, (bruto.height * esc).toInt()), true)
        if (menor !== bruto) bruto.recycle()
        return menor
    }

    /**
     * Mede a cena num JPEG pequeno: luminância média (0..255), % de pixels estourados (> 250) e % de sombras
     * fechadas (< 8). Decide o caminho do HDR: escuro = Noite; com estouro/sombra a recuperar = bracket;
     * cena "comportada" = só rajada (medido 16/09 no auditório: bracket numa cena com 0,4% de estouro
     * clareou 50% e saturou 43% sem ter nada a recuperar).
     */
    fun medeCena(bytes: ByteArray): IntArray {
        val b = decodifica(bytes, 160) ?: return intArrayOf(128, 0, 0)
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }; b.recycle()
        val lu = luminancia(px); var soma = 0L; var claros = 0; var escuros = 0
        for (v in lu) { soma += v; if (v > 250) claros++; if (v < 8) escuros++ }
        val n = max(1, lu.size)
        return intArrayOf((soma / n).toInt(), claros * 1000 / n, escuros * 1000 / n)   // % x10
    }
    const val ESTOURO_MIN_PERMIL = 20    // 2% estourado ou...
    const val SOMBRA_MIN_PERMIL = 100    // ...10% de sombra fechada justificam o bracket
    const val LIMIAR_ESCURO = 70   // abaixo disso o bracket só traz ruído (medido: Mertens 0,063 vs rajada 0,037 de ruído nos lisos)
    const val LIMIAR_MUITO_ESCURO = 35   // abaixo: 8 quadros, soma 2x2 e dessaturação maior ("Olho": bastonetes não veem cor)

    /**
     * Noite: N quadros na MESMA exposição fundidos (rajada) e sombras levantadas (gama 0,7 na luminância,
     * cor preservada pela razão). É o caminho do HDR+: à noite o bracket só traz o ruído do quadro longo.
     */
    suspend fun noite(quadros: List<Bitmap>, reciclar: Boolean = false, gama: Float = 0.7f, dessatura: Float = 0.1f): Bitmap = withContext(Dispatchers.Default) {
        val fundido = rajada(quadros, reciclar)
        val w = fundido.width; val h = fundido.height
        val px = IntArray(w * h).also { fundido.getPixels(it, 0, w, 0, 0, w, h) }; fundido.recycle()
        val tabela = FloatArray(256) { 255f * Math.pow(it / 255.0, gama.toDouble()).toFloat() }
        for (k in px.indices) {
            val c = px[k]; val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            val y = (r * 54 + g * 183 + b * 19) shr 8
            val f = if (y > 0) tabela[y] / y else 1f
            // dessaturação parcial: o ruído colorido é o mais visível à noite (e o olho também abre mão da cor no escuro)
            val rr = (r + (y - r) * dessatura) * f; val gg = (g + (y - g) * dessatura) * f; val bb = (b + (y - b) * dessatura) * f
            px[k] = (0xFF shl 24) or ((rr + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((gg + 0.5f).toInt().coerceIn(0, 255) shl 8) or (bb + 0.5f).toInt().coerceIn(0, 255)
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Máscara de nitidez só na luminância (raio 1 px, quantidade q): depois de fundir, a imagem fica limpa
     * mas macia. Medido 16/09 na selfie fundida do dono: q 0,6 deu 0,0004 de nitidez (Xiaomi 0,0019); q 2,2 deu
     * 0,0006 com ruído 0,0033. A varredura em Python (q 2,0 sobre a foto que já tinha q 0,6, efetivo ~3,8) chegou a
     * 0,0012 com ruído 0,0037, ainda abaixo do 0,0038 da Xiaomi; por isso q 3,5. Na luminância não cria franja
     * colorida; o ganho é limitado a ±60 níveis para não virar halo. q 3,5 medido na v0.36: nitidez 0,0010, ruído 0,0041 (passou
     * o 0,0038 da Xiaomi) → q 3,0.
     */
    fun nitidezLeve(b: Bitmap, q: Float = 3.0f): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val lu = luminancia(px)
        val saida = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val k = y * w + x
            val c = px[k]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) { saida[k] = c; continue }
            val media = (lu[k - w - 1] + lu[k - w] + lu[k - w + 1] + lu[k - 1] + lu[k] + lu[k + 1] + lu[k + w - 1] + lu[k + w] + lu[k + w + 1]) / 9f
            val delta = ((lu[k] - media) * q).coerceIn(-60f, 60f)
            val f = if (lu[k] > 0) (lu[k] + delta) / lu[k] else 1f
            saida[k] = (0xFF shl 24) or ((r * f + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((g * f + 0.5f).toInt().coerceIn(0, 255) shl 8) or (bl * f + 0.5f).toInt().coerceIn(0, 255)
        }
        b.recycle()
        return Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
    }

    fun gira(b: Bitmap, graus: Int): Bitmap {
        if (graus == 0) return b
        val g = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(graus.toFloat()) }, true)
        if (g !== b) b.recycle()
        return g
    }

    fun luminancia(px: IntArray): IntArray = IntArray(px.size) { val c = px[it]; ((c shr 16 and 255) * 54 + (c shr 8 and 255) * 183 + (c and 255) * 19) shr 8 }

    private fun metade(g: IntArray, w: Int, h: Int): Triple<IntArray, Int, Int> {
        val w2 = max(1, w / 2); val h2 = max(1, h / 2)
        val s = IntArray(w2 * h2)
        for (y in 0 until h2) { val y0 = min(h - 1, y * 2); val y1 = min(h - 1, y * 2 + 1)
            for (x in 0 until w2) { val x0 = min(w - 1, x * 2); val x1 = min(w - 1, x * 2 + 1)
                s[y * w2 + x] = (g[y0 * w + x0] + g[y0 * w + x1] + g[y1 * w + x0] + g[y1 * w + x1]) shr 2 } }
        return Triple(s, w2, h2)
    }

    private fun mediana(g: IntArray): Int {
        val hist = IntArray(256); for (v in g) hist[v]++
        var acc = 0; for (i in 0..255) { acc += hist[i]; if (acc * 2 >= g.size) return i }
        return 128
    }

    /** Variância do laplaciano, amostrada a cada 2 px: mede nitidez (e ruído) para escolher a referência. */
    fun nitidez(g: IntArray, w: Int, h: Int): Double {
        var s = 0.0; var s2 = 0.0; var n = 0
        var y = 1; while (y < h - 1) { var x = 1; while (x < w - 1) {
            val l = (-4 * g[y * w + x] + g[(y - 1) * w + x] + g[(y + 1) * w + x] + g[y * w + x - 1] + g[y * w + x + 1]).toDouble()
            s += l; s2 += l * l; n++; x += 2 }; y += 2 }
        return if (n == 0) 0.0 else s2 / n - (s / n) * (s / n)
    }

    // ---------- MTB (Ward 2003) ----------
    /** Desloca img para casar com ref: devolve (dx, dy) tal que img[y+dy, x+dx] ≈ ref[y, x]. */
    fun alinhaMtb(ref: IntArray, img: IntArray, w: Int, h: Int, niveis: Int = 6): IntArray {
        val pr = ArrayList<Triple<IntArray, Int, Int>>(); val pi = ArrayList<Triple<IntArray, Int, Int>>()
        pr += Triple(ref, w, h); pi += Triple(img, w, h)
        for (n in 1 until niveis) { pr += metade(pr.last().first, pr.last().second, pr.last().third); pi += metade(pi.last().first, pi.last().second, pi.last().third) }
        var dx = 0; var dy = 0
        for (n in niveis - 1 downTo 0) {
            val (gr, lw, lh) = pr[n]; val (gi, _, _) = pi[n]
            val mr = mediana(gr); val mi = mediana(gi)
            dx *= 2; dy *= 2
            var melhorErro = Int.MAX_VALUE; var bx = dx; var by = dy
            for (oy in -1..1) for (ox in -1..1) {
                val sx = dx + ox; val sy = dy + oy
                var erro = 0
                val y0 = max(0, -sy); val y1 = min(lh, lh - sy); val x0 = max(0, -sx); val x1 = min(lw, lw - sx)
                var y = y0
                while (y < y1) {
                    val lr = y * lw; val li = (y + sy) * lw + sx
                    var x = x0
                    while (x < x1) {
                        val a = gr[lr + x]; val b = gi[li + x]
                        if (abs(a - mr) > 4 && abs(b - mi) > 4 && ((a > mr) != (b > mi))) erro++
                        x++
                    }
                    y++
                }
                if (erro < melhorErro) { melhorErro = erro; bx = sx; by = sy }
            }
            dx = bx; dy = by
        }
        return intArrayOf(dx, dy)
    }

    /**
     * Refino por ladrilho: para cada bloco de `lado` px, busca ±busca px em volta do deslocamento global
     * minimizando a soma das diferenças absolutas (amostrada a cada 2 px). Devolve dx,dy por ladrilho.
     */
    fun refinaLadrilhos(ref: IntArray, img: IntArray, w: Int, h: Int, dx: Int, dy: Int, lado: Int = 128, busca: Int = 2): IntArray {
        val nx = (w + lado - 1) / lado; val ny = (h + lado - 1) / lado
        val saida = IntArray(nx * ny * 2)
        for (ty in 0 until ny) for (tx in 0 until nx) {
            val x0 = tx * lado; val y0 = ty * lado; val x1 = min(w, x0 + lado); val y1 = min(h, y0 + lado)
            var melhor = Long.MAX_VALUE; var bx = dx; var by = dy
            for (oy in -busca..busca) for (ox in -busca..busca) {
                val sx = dx + ox; val sy = dy + oy
                var sad = 0L; var n = 0
                var y = y0; while (y < y1) { val yy = y + sy
                    if (yy in 0 until h) { var x = x0; while (x < x1) { val xx = x + sx
                        if (xx in 0 until w) { sad += abs(ref[y * w + x] - img[yy * w + xx]); n++ }; x += 2 } }
                    y += 2 }
                if (n > 0) { val m = sad * 4096 / n; if (m < melhor) { melhor = m; bx = sx; by = sy } }
            }
            saida[(ty * nx + tx) * 2] = bx; saida[(ty * nx + tx) * 2 + 1] = by
        }
        return saida
    }

    // ---------- rajada ----------
    /** Funde N quadros da mesma exposição (mesmo tamanho). Devolve bitmap novo; não recicla as entradas. */
    suspend fun rajada(quadros: List<Bitmap>, reciclar: Boolean = false, aoProgresso: (Int) -> Unit = {}): Bitmap = withContext(Dispatchers.Default) {
        val w = quadros[0].width; val h = quadros[0].height
        val pxs = quadros.map { b -> IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h); if (reciclar) b.recycle() } }
        val lums = pxs.map { luminancia(it) }
        val r = lums.indices.maxByOrNull { nitidez(lums[it], w, h) } ?: 0
        val ref = pxs[r]; val lref = lums[r]
        val accR = FloatArray(w * h); val accG = FloatArray(w * h); val accB = FloatArray(w * h); val peso = FloatArray(w * h)
        for (i in 0 until w * h) { val c = ref[i]; accR[i] = (c shr 16 and 255).toFloat(); accG[i] = (c shr 8 and 255).toFloat(); accB[i] = (c and 255).toFloat(); peso[i] = 1f }
        var tau = -1f
        val lado = 128; val nx = (w + lado - 1) / lado
        for (i in pxs.indices) {
            if (i == r) continue
            val (dx, dy) = alinhaMtb(lref, lums[i], w, h).let { it[0] to it[1] }
            val lad = refinaLadrilhos(lref, lums[i], w, h, dx, dy, lado)
            if (tau < 0f) {
                // σ do ruído pela mediana da |diferença| entre os dois quadros alinhados (MAD → σ; a diferença tem 2σ²)
                val hist = IntArray(256); var n = 0
                var y = 0; while (y < h) { var x = 0; while (x < w) {
                    val t = ((y / lado) * nx + x / lado) * 2; val xx = x + lad[t]; val yy = y + lad[t + 1]
                    if (xx in 0 until w && yy in 0 until h) { hist[abs(lref[y * w + x] - lums[i][yy * w + xx])]++; n++ }; x += 3 }; y += 3 }
                var acc = 0; var mad = 0; for (k in 0..255) { acc += hist[k]; if (acc * 2 >= n) { mad = k; break } }
                val sigma = 1.4826f * mad / sqrt(2f)
                tau = 2.5f * sigma + 1.5f
            }
            val img = pxs[i]; val lim = lums[i]
            for (y in 0 until h) {
                val ty = y / lado
                for (x in 0 until w) {
                    val t = (ty * nx + x / lado) * 2; val xx = x + lad[t]; val yy = y + lad[t + 1]
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val k = y * w + x; val j = yy * w + xx
                    val d = (lref[k] - lim[j]) / tau
                    val wgt = exp(-d * d)
                    val c = img[j]
                    accR[k] += (c shr 16 and 255) * wgt; accG[k] += (c shr 8 and 255) * wgt; accB[k] += (c and 255) * wgt; peso[k] += wgt
                }
            }
            aoProgresso(i + 1)
        }
        val saida = IntArray(w * h) { k ->
            val p = peso[k]
            (0xFF shl 24) or ((accR[k] / p + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((accG[k] / p + 0.5f).toInt().coerceIn(0, 255) shl 8) or (accB[k] / p + 0.5f).toInt().coerceIn(0, 255)
        }
        Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- pirâmides (kernel binomial 1 4 6 4 1) ----------
    private fun borra(p: Plano): Plano {
        val w = p.w; val h = p.h; val a = p.v; val t = FloatArray(w * h); val o = FloatArray(w * h)
        for (y in 0 until h) { val l = y * w
            for (x in 0 until w) {
                val x0 = max(0, x - 2); val x1 = max(0, x - 1); val x3 = min(w - 1, x + 1); val x4 = min(w - 1, x + 2)
                t[l + x] = (a[l + x0] + 4 * a[l + x1] + 6 * a[l + x] + 4 * a[l + x3] + a[l + x4]) / 16f } }
        for (y in 0 until h) {
            val y0 = max(0, y - 2) * w; val y1 = max(0, y - 1) * w; val y2 = y * w; val y3 = min(h - 1, y + 1) * w; val y4 = min(h - 1, y + 2) * w
            for (x in 0 until w) o[y2 + x] = (t[y0 + x] + 4 * t[y1 + x] + 6 * t[y2 + x] + 4 * t[y3 + x] + t[y4 + x]) / 16f }
        return Plano(w, h, o)
    }
    private fun reduz(p: Plano): Plano {
        val b = borra(p); val w2 = max(1, p.w / 2); val h2 = max(1, p.h / 2)
        return Plano(w2, h2, FloatArray(w2 * h2) { val y = it / w2; val x = it % w2; b.v[(y * 2) * p.w + x * 2] })
    }
    private fun amplia(p: Plano, w: Int, h: Int): Plano {
        val z = FloatArray(w * h)
        for (y in 0 until p.h) for (x in 0 until p.w) { val yy = y * 2; val xx = x * 2; if (yy < h && xx < w) z[yy * w + xx] = p.v[y * p.w + x] * 4f }
        return borra(Plano(w, h, z))
    }
    private fun gauss(p: Plano, n: Int): List<Plano> { val l = arrayListOf(p); for (i in 1 until n) l += reduz(l.last()); return l }
    private fun laplace(p: Plano, n: Int): List<Plano> {
        val g = gauss(p, n)
        return List(n) { i -> if (i == n - 1) g[i] else { val u = amplia(g[i + 1], g[i].w, g[i].h); Plano(g[i].w, g[i].h, FloatArray(g[i].v.size) { k -> g[i].v[k] - u.v[k] }) } }
    }

    // ---------- HDR (Mertens 2007, na luminância) ----------
    suspend fun hdr(exposicoes: List<Bitmap>, niveis: Int = 7, reciclar: Boolean = false, aoProgresso: (Int) -> Unit = {}): Bitmap = withContext(Dispatchers.Default) {
        val w = exposicoes[0].width; val h = exposicoes[0].height; val n = w * h
        val pxs = exposicoes.map { b -> IntArray(n).also { b.getPixels(it, 0, w, 0, 0, w, h); if (reciclar) b.recycle() } }
        val lums = pxs.map { luminancia(it) }
        // alinha tudo à exposição do meio (MTB é invariante à exposição por construção)
        val meio = pxs.size / 2
        val desl = pxs.indices.map { if (it == meio) intArrayOf(0, 0) else alinhaMtb(lums[meio], lums[it], w, h) }
        // ruído de cada exposição (MAD do laplaciano, amostrado): o quadro mais ruidoso (o longo, à noite) perde peso
        val sigmas = lums.map { lu ->
            val hist = IntArray(1024); var cnt = 0
            var y = 1; while (y < h - 1) { var x = 1; while (x < w - 1) { val j = y * w + x
                hist[min(1023, abs(-4 * lu[j] + lu[j - w] + lu[j + w] + lu[j - 1] + lu[j + 1]))]++; cnt++; x += 4 }; y += 4 }
            var acc = 0; var mad = 0; for (k in 0 until 1024) { acc += hist[k]; if (acc * 2 >= cnt) { mad = k; break } }
            max(1f, mad.toFloat())
        }
        val sigMin = sigmas.minOrNull() ?: 1f
        val penal = sigmas.map { (sigMin / it) * (sigMin / it) }
        // luminância suavizada 3x3 para o contraste: ruído fino não vira "detalhe" com peso alto
        val suaves = lums.map { lu -> IntArray(n) { k -> val x = k % w; val y = k / w
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) lu[k] else (lu[k - w - 1] + lu[k - w] + lu[k - w + 1] + lu[k - 1] + lu[k] + lu[k + 1] + lu[k + w - 1] + lu[k + w] + lu[k + w + 1]) / 9 } }
        // pesos de Mertens por exposição
        val pesos = pxs.indices.map { i ->
            val px = pxs[i]; val lu = suaves[i]; val (dx, dy) = desl[i].let { it[0] to it[1] }; val pi = penal[i]
            FloatArray(n) { k ->
                val x = k % w + dx; val y = k / w + dy
                if (x !in 1 until w - 1 || y !in 1 until h - 1) 1e-6f else {
                    val j = y * w + x
                    val contraste = abs(-4 * lu[j] + lu[j - w] + lu[j + w] + lu[j - 1] + lu[j + 1]) / 255f
                    val c = px[j]; val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
                    val m = (r + g + b) / 3f
                    val sat = sqrt(((r - m) * (r - m) + (g - m) * (g - m) + (b - m) * (b - m)) / 3f)
                    val exp_ = exp(-((r - .5f) * (r - .5f) + (g - .5f) * (g - .5f) + (b - .5f) * (b - .5f)) / (2 * .2f * .2f))
                    contraste * sat * exp_ * pi + 1e-6f
                }
            }
        }
        for (k in 0 until n) { var s = 0f; for (p in pesos) s += p[k]; for (p in pesos) p[k] /= s }
        aoProgresso(1)
        // luminância: pirâmide laplaciana de cada exposição × gaussiana do peso, acumulada
        var acc: List<Plano>? = null
        val corR = FloatArray(n); val corG = FloatArray(n); val corB = FloatArray(n)
        for (i in pxs.indices) {
            val (dx, dy) = desl[i].let { it[0] to it[1] }
            val plano = Plano(w, h, FloatArray(n) { k -> val x = (k % w + dx).coerceIn(0, w - 1); val y = (k / w + dy).coerceIn(0, h - 1); lums[i][y * w + x].toFloat() })
            val lp = laplace(plano, niveis); val gp = gauss(Plano(w, h, pesos[i]), niveis)
            val termos = List(niveis) { l -> Plano(lp[l].w, lp[l].h, FloatArray(lp[l].v.size) { k -> lp[l].v[k] * gp[l].v[k] }) }
            acc = if (acc == null) termos else acc!!.mapIndexed { l, a -> Plano(a.w, a.h, FloatArray(a.v.size) { k -> a.v[k] + termos[l].v[k] }) }
            val px = pxs[i]
            for (k in 0 until n) {
                val x = (k % w + dx).coerceIn(0, w - 1); val y = (k / w + dy).coerceIn(0, h - 1); val c = px[y * w + x]; val p = pesos[i][k]
                corR[k] += (c shr 16 and 255) * p; corG[k] += (c shr 8 and 255) * p; corB[k] += (c and 255) * p
            }
            aoProgresso(2 + i)
        }
        // colapsa a pirâmide
        var y = acc!![niveis - 1]
        for (l in niveis - 2 downTo 0) { val u = amplia(y, acc!![l].w, acc!![l].h); y = Plano(u.w, u.h, FloatArray(u.v.size) { k -> u.v[k] + acc!![l].v[k] }) }
        // Sem esticamento. Duas âncoras na exposição do meio (medido 16/09: o bracket clareou 50% e saturou 43%
        // uma cena que não precisava): o brilho médio do resultado segue o do ev0 (ganho entre 0,85 e 1,25),
        // e a saturação de cada pixel não passa de 1,1x a do ev0 no mesmo ponto.
        var somaF = 0.0; var soma0 = 0.0; val lu0 = lums[meio]
        for (k in 0 until n) { somaF += y.v[k]; soma0 += lu0[k] }
        val ganho = (if (somaF > 0) soma0 / somaF else 1.0).toFloat().coerceIn(0.85f, 1.25f)
        val px0 = pxs[meio]
        val saida = IntArray(n) { k ->
            val yf = (y.v[k] * ganho).coerceIn(0f, 255f)
            val yc = (corR[k] * 54 + corG[k] * 183 + corB[k] * 19) / 256f
            val f = if (yc > 1f) yf / yc else 1f
            var r = corR[k] * f; var g = corG[k] * f; var b = corB[k] * f
            val c0 = px0[k]; val sat0 = (max(c0 shr 16 and 255, max(c0 shr 8 and 255, c0 and 255)) - min(c0 shr 16 and 255, min(c0 shr 8 and 255, c0 and 255))) * 1.1f + 2f
            val satF = max(r, max(g, b)) - min(r, min(g, b))
            if (satF > sat0) { val t = sat0 / satF; r = yf + (r - yf) * t; g = yf + (g - yf) * t; b = yf + (b - yf) * t }
            (0xFF shl 24) or ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)
        }
        Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
    }
}
