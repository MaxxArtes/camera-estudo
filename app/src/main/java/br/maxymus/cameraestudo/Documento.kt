package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Scanner de documento feito em casa, para a tela ser sempre a nossa. Ideias tomadas do estudo do
 * FairScan, OpenScan e OpenNoteScanner (sem copiar código: eles usam OpenCV e um modelo GPL):
 *
 *  1. Normaliza a iluminação (retinex simplificado): divide cada pixel pelo "fundo" borrado; isso
 *     apaga sombras e deixa a folha uniforme, o que ajuda tanto a detecção quanto o resultado.
 *  2. Dois candidatos para a folha:
 *     a) a maior mancha CLARA (limiar de Otsu) — folha clara em fundo escuro;
 *     b) a maior região LISA fechada por BORDAS (gradiente forte dilatado) — folha branca em fundo
 *        claro, onde só a borda da folha separa as duas.
 *     Cada candidato vira um quadrilátero pelos cantos extremos e recebe um placar (área, quanto
 *     do quadrilátero a mancha preenche, proporção). O melhor vence; sem candidato bom, sem recorte.
 *  3. Endireita a perspectiva com Matrix.setPolyToPoly (Android já sabe fazer).
 *  4. Realce final: fundo branco, contraste esticado por percentis.
 */
object Documento {
    private const val LADO_ANALISE = 640
    private const val LADO_SAIDA = 2000

    data class Resultado(val recortou: Boolean, val metodo: String)

    private class Quad(val tl: FloatArray, val tr: FloatArray, val br: FloatArray, val bl: FloatArray, val areaMancha: Int, val metodo: String) {
        fun areaQuad(): Float {   // fórmula do cadarço
            val xs = floatArrayOf(tl[0], tr[0], br[0], bl[0]); val ys = floatArrayOf(tl[1], tr[1], br[1], bl[1])
            var s = 0f; for (i in 0 until 4) { val j = (i + 1) % 4; s += xs[i] * ys[j] - xs[j] * ys[i] }
            return abs(s) / 2f
        }
        fun placar(areaImagem: Int): Float {
            val aq = areaQuad(); if (aq <= 0f) return 0f
            val fracao = aq / areaImagem
            if (fracao < 0.15f || fracao > 0.97f) return 0f
            val preenchimento = (areaMancha / aq).coerceIn(0f, 1.2f)      // mancha deve encher o quadrilátero
            if (preenchimento < 0.75f) return 0f
            val larg = (hypot(tr[0] - tl[0], tr[1] - tl[1]) + hypot(br[0] - bl[0], br[1] - bl[1])) / 2
            val alt = (hypot(bl[0] - tl[0], bl[1] - tl[1]) + hypot(br[0] - tr[0], br[1] - tr[1])) / 2
            val razao = if (alt > 0) larg / alt else 0f
            if (razao !in 0.3f..3.3f) return 0f
            return preenchimento * 0.6f + fracao * 0.4f
        }
    }

    /**
     * Modo TELA (monitor, notebook, outro celular): a tela é o retângulo mais claro e uniforme da foto;
     * não há sombra a tirar (a tela emite luz), o fundo pode ser escuro de propósito, e o inimigo é o
     * moiré. Passos: detecção sem retinex (claro + bordas), recorte, reamostragem a 1400 px (mata a trama
     * de alta frequência), realce só de contraste leve na luminância, sem clarear o fundo.
     */
    suspend fun processarTela(contexto: Context, uri: Uri): Resultado? = withContext(Dispatchers.Default) {
        runCatching {
            val foto = decodeReduzido(contexto, uri, 2400) ?: return@runCatching null
            val esc = LADO_ANALISE.toFloat() / max(foto.width, foto.height)
            val pw = max(1, (foto.width * esc).toInt()); val ph = max(1, (foto.height * esc).toInt())
            val pequena = Bitmap.createScaledBitmap(foto, pw, ph, true)
            // suaviza antes do limiar (o moiré quebra a mancha em tiras): reduz a 1/3 e volta
            val suave = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(pequena, max(1, pw / 3), max(1, ph / 3), true), pw, ph, true)
            val px = IntArray(pw * ph).also { suave.getPixels(it, 0, pw, 0, 0, pw, ph) }; pequena.recycle(); suave.recycle()
            val cinza = IntArray(pw * ph) { luma(px[it]) }
            val lim = otsu(cinza)
            // monitor = maior mancha clara, com fechamento (dilata+erode 6 px) para o texto não furar a mancha
            val mascara = fecha(BooleanArray(pw * ph) { cinza[it] > lim }, pw, ph, 6)
            val melhor = maiorMancha(mascara, pw, ph, "monitor")?.takeIf { it.placar(pw * ph) > 0f }
            var saida: Bitmap = foto; var recortou = false
            if (melhor != null) {
                val f = 1f / esc
                val tl = melhor.tl.map { it * f }; val tr = melhor.tr.map { it * f }; val br = melhor.br.map { it * f }; val bl = melhor.bl.map { it * f }
                var larg = max(hypot(tr[0] - tl[0], tr[1] - tl[1]), hypot(br[0] - bl[0], br[1] - bl[1])).toInt().coerceIn(200, 6000)
                var alt = max(hypot(bl[0] - tl[0], bl[1] - tl[1]), hypot(br[0] - tr[0], br[1] - tr[1])).toInt().coerceIn(200, 6000)
                val razao = larg.toFloat() / alt
                for (tela in floatArrayOf(16f / 9f, 9f / 16f, 16f / 10f, 10f / 16f, 4f / 3f, 3f / 4f)) if (abs(razao / tela - 1f) < 0.1f) { if (tela > 1f) larg = (alt * tela).toInt() else alt = (larg / tela).toInt(); break }
                val escS = min(1f, 1400f / max(larg, alt))       // 1400 px: reamostrar aqui já derruba boa parte do moiré
                val w = (larg * escS).toInt(); val h = (alt * escS).toInt()
                val m = Matrix()
                if (m.setPolyToPoly(floatArrayOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]), 0, floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)) {
                    val plano = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(plano).drawBitmap(foto, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                    saida = plano; recortou = true; foto.recycle()
                }
            }
            val semMoire = suavizaMoire(saida); if (semMoire !== saida) saida.recycle()
            val realcada = realcaTela(semMoire); if (realcada !== semMoire) semMoire.recycle()
            contexto.contentResolver.openOutputStream(uri, "wt")?.use { realcada.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching null
            realcada.recycle()
            Resultado(recortou, melhor?.metodo ?: "nenhum")
        }.getOrNull()
    }

    /**
     * Moiré (medido na foto do dono, monitor com PDF): reamostra a 70% e volta (tira a trama fina), filtro de
     * caixa 4×4 (tira as listras da grade de pixels) e dessatura a 15% (tira as faixas coloridas). O texto
     * de tela segue legível a 1400–1600 px.
     */
    private fun suavizaMoire(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val menor = Bitmap.createScaledBitmap(b, max(1, (w * 0.7f).toInt()), max(1, (h * 0.7f).toInt()), true)
        val volta = Bitmap.createScaledBitmap(menor, w, h, true); menor.recycle()
        val px = IntArray(w * h).also { volta.getPixels(it, 0, w, 0, 0, w, h) }; volta.recycle()
        val out = caixa(px, w, h, 2)          // janela 4 (raio 2) em x e em y
        for (i in out.indices) {               // dessatura: cor = cinza + 15% da diferença
            val c = out[i]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255; val y = (r * 30 + g * 59 + bl * 11) / 100
            out[i] = (0xFF shl 24) or ((y + (r - y) * 15 / 100).coerceIn(0, 255) shl 16) or ((y + (g - y) * 15 / 100).coerceIn(0, 255) shl 8) or (y + (bl - y) * 15 / 100).coerceIn(0, 255)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Filtro de caixa separável por canal (somas acumuladas): O(n), independe do raio. */
    private fun caixa(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        fun passa(src: IntArray, horizontal: Boolean): IntArray {
            val dst = IntArray(src.size)
            val n = if (horizontal) w else h; val m = if (horizontal) h else w
            val soma = IntArray(3)
            for (linha in 0 until m) {
                fun idx(k: Int) = if (horizontal) linha * w + k else k * w + linha
                soma.fill(0); var cnt = 0
                for (k in 0 until min(r, n - 1)) { val c = src[idx(k)]; soma[0] += c shr 16 and 255; soma[1] += c shr 8 and 255; soma[2] += c and 255; cnt++ }
                for (k in 0 until n) {
                    val entra = k + r; if (entra < n) { val c = src[idx(entra)]; soma[0] += c shr 16 and 255; soma[1] += c shr 8 and 255; soma[2] += c and 255; cnt++ }
                    val sai = k - r - 1; if (sai >= 0) { val c = src[idx(sai)]; soma[0] -= c shr 16 and 255; soma[1] -= c shr 8 and 255; soma[2] -= c and 255; cnt-- }
                    dst[idx(k)] = (0xFF shl 24) or ((soma[0] / cnt) shl 16) or ((soma[1] / cnt) shl 8) or (soma[2] / cnt)
                }
            }
            return dst
        }
        return passa(passa(px, true), false)
    }

    /** Fechamento morfológico (dilata e erode) separável, para a mancha da folha/tela ficar sólida apesar do texto. */
    private fun fecha(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        fun dilata(src: BooleanArray): BooleanArray {
            val a = BooleanArray(src.size)
            for (y in 0 until h) for (x in 0 until w) { var v = false; var k = -r; while (!v && k <= r) { val xx = x + k; if (xx in 0 until w && src[y * w + xx]) v = true; k++ }; a[y * w + x] = v }
            val b = BooleanArray(src.size)
            for (y in 0 until h) for (x in 0 until w) { var v = false; var k = -r; while (!v && k <= r) { val yy = y + k; if (yy in 0 until h && a[yy * w + x]) v = true; k++ }; b[y * w + x] = v }
            return b
        }
        val inv = { s: BooleanArray -> BooleanArray(s.size) { !s[it] } }
        return inv(dilata(inv(dilata(m))))
    }

    /** Tela: só um estiramento leve de contraste na luminância (0,5% preto, 0,5% branco), cores intactas, fundo escuro respeitado. */
    private fun realcaTela(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val y = IntArray(w * h) { luma(px[it]) }
        val hist = IntArray(256); for (v in y) hist[v]++
        val n = px.size; var acc = 0; var lo = 0; var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * 0.005) { lo = i; break } }
        acc = 0; for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * 0.005) { hi = i; break } }
        if (hi - lo < 40 || (lo < 8 && hi > 247)) return b
        val tabela = IntArray(256) { ((it - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
        for (i in px.indices) {
            val c = px[i]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            val alvo = tabela[y[i]]; val base = max(1, y[i])
            px[i] = (0xFF shl 24) or ((r * alvo / base).coerceIn(0, 255) shl 16) or ((g * alvo / base).coerceIn(0, 255) shl 8) or (bl * alvo / base).coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    suspend fun processar(contexto: Context, uri: Uri): Resultado? = withContext(Dispatchers.Default) {
        runCatching {
            val foto = decodeReduzido(contexto, uri, 2400) ?: return@runCatching null

            // 1) pequena, cinza, iluminação normalizada
            val esc = LADO_ANALISE.toFloat() / max(foto.width, foto.height)
            val pw = max(1, (foto.width * esc).toInt()); val ph = max(1, (foto.height * esc).toInt())
            val pequena = Bitmap.createScaledBitmap(foto, pw, ph, true)
            val px = IntArray(pw * ph).also { pequena.getPixels(it, 0, pw, 0, 0, pw, ph) }
            val cinza = IntArray(pw * ph) { luma(px[it]) }
            val fundo = fundoBorrado(pequena, 40)
            pequena.recycle()
            val norm = IntArray(pw * ph) { (cinza[it] * 200 / max(1, luma(fundo[it]))).coerceIn(0, 255) }   // 200 ≈ "branco" após dividir

            // 2) candidatos: folha clara, folha ESCURA (recibo amarelado em mesa branca — apontado pelo agy) e região lisa por bordas
            val candidatos = mutableListOf<Quad>()
            val lim = otsu(norm)
            maiorMancha(BooleanArray(pw * ph) { norm[it] > lim }, pw, ph, "claro")?.let { candidatos += it }
            maiorMancha(BooleanArray(pw * ph) { norm[it] <= lim }, pw, ph, "escuro")?.let { candidatos += it }
            maiorMancha(regiaoLisa(cinza, pw, ph), pw, ph, "bordas")?.let { candidatos += it }
            val melhor = candidatos.maxByOrNull { it.placar(pw * ph) }?.takeIf { it.placar(pw * ph) > 0f }

            // 3) recorte com perspectiva
            var saida: Bitmap = foto; var recortou = false
            if (melhor != null) {
                val f = 1f / esc
                val tl = melhor.tl.map { it * f }; val tr = melhor.tr.map { it * f }; val br = melhor.br.map { it * f }; val bl = melhor.bl.map { it * f }
                // lado = MAIOR dos dois opostos (o menor é o que a perspectiva encurtou); se a razão fica perto de A4/Carta, encaixa
                var larg = max(hypot(tr[0] - tl[0], tr[1] - tl[1]), hypot(br[0] - bl[0], br[1] - bl[1])).toInt().coerceIn(200, 6000)
                var alt = max(hypot(bl[0] - tl[0], bl[1] - tl[1]), hypot(br[0] - tr[0], br[1] - tr[1])).toInt().coerceIn(200, 6000)
                val razao = larg.toFloat() / alt
                for (papel in floatArrayOf(1.414f, 1f / 1.414f, 1.294f, 1f / 1.294f)) if (abs(razao / papel - 1f) < 0.12f) { if (papel > 1f) larg = (alt * papel).toInt() else alt = (larg / papel).toInt(); break }
                val escS = min(1f, LADO_SAIDA.toFloat() / max(larg, alt))
                val w = (larg * escS).toInt(); val h = (alt * escS).toInt()
                val m = Matrix()
                if (m.setPolyToPoly(floatArrayOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]), 0, floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)) {
                    val plano = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(plano).drawBitmap(foto, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                    saida = plano; recortou = true; foto.recycle()
                }
            }

            // 4) realce: sombras fora, fundo branco, contraste
            val realcada = realca(saida)
            if (realcada !== saida) saida.recycle()
            contexto.contentResolver.openOutputStream(uri, "wt")?.use { realcada.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching null
            realcada.recycle()
            Resultado(recortou, melhor?.metodo ?: "nenhum")
        }.getOrNull()
    }

    private fun luma(c: Int) = ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100

    /** "Fundo" da imagem = versão muito borrada (reduz a 1/fator e amplia com filtro). Barato e suficiente. */
    private fun fundoBorrado(b: Bitmap, fator: Int): IntArray {
        val w = b.width; val h = b.height
        val peq = Bitmap.createScaledBitmap(b, max(1, w / fator), max(1, h / fator), true)
        val grande = Bitmap.createScaledBitmap(peq, w, h, true)
        return IntArray(w * h).also { grande.getPixels(it, 0, w, 0, 0, w, h) }
    }

    private fun otsu(v: IntArray): Int {
        val hist = IntArray(256); for (x in v) hist[x]++
        val total = v.size; var soma = 0L; for (i in 0..255) soma += i.toLong() * hist[i]
        var somaB = 0L; var wB = 0; var melhor = 0.0; var limiar = 128
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            somaB += i.toLong() * hist[i]
            val mB = somaB.toDouble() / wB; val mF = (soma - somaB).toDouble() / wF
            val entre = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (entre > melhor) { melhor = entre; limiar = i }
        }
        return limiar
    }

    /** Região "lisa": pixels sem borda forte por perto (gradiente de Sobel abaixo do limiar, dilatado 2 px). */
    private fun regiaoLisa(cinza: IntArray, w: Int, h: Int): BooleanArray {
        val grad = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -cinza[i - w - 1] - 2 * cinza[i - 1] - cinza[i + w - 1] + cinza[i - w + 1] + 2 * cinza[i + 1] + cinza[i + w + 1]
            val gy = -cinza[i - w - 1] - 2 * cinza[i - w] - cinza[i - w + 1] + cinza[i + w - 1] + 2 * cinza[i + w] + cinza[i + w + 1]
            grad[i] = abs(gx) + abs(gy)
        }
        // limiar de borda: pega os ~6% pixels de gradiente mais alto (adaptativo à foto)
        val ordenado = grad.copyOf(); ordenado.sort(); val limiar = max(40, ordenado[(ordenado.size * 0.94).toInt()])
        val borda = BooleanArray(w * h) { grad[it] >= limiar }
        val lisa = BooleanArray(w * h) { true }
        for (y in 0 until h) for (x in 0 until w) if (borda[y * w + x]) {
            for (dy in -2..2) for (dx in -2..2) { val yy = y + dy; val xx = x + dx; if (yy in 0 until h && xx in 0 until w) lisa[yy * w + xx] = false }
        }
        // a moldura da imagem conta como borda, senão o "fundo" vira uma região lisa gigante
        for (x in 0 until w) { lisa[x] = false; lisa[(h - 1) * w + x] = false }
        for (y in 0 until h) { lisa[y * w] = false; lisa[y * w + w - 1] = false }
        return lisa
    }

    /** Maior componente conexo de `marca` e os 4 cantos extremos dele. */
    private fun maiorMancha(marca: BooleanArray, w: Int, h: Int, metodo: String): Quad? {
        val visto = BooleanArray(w * h); val fila = IntArray(w * h)
        var melhorArea = 0; var melhorLista: IntArray? = null
        for (inicio in 0 until w * h) {
            if (!marca[inicio] || visto[inicio]) continue
            var ini = 0; var fim = 0; fila[fim++] = inicio; visto[inicio] = true
            while (ini < fim) {
                val p = fila[ini++]; val x = p % w; val y = p / w
                if (x > 0) { val q = p - 1; if (marca[q] && !visto[q]) { visto[q] = true; fila[fim++] = q } }
                if (x < w - 1) { val q = p + 1; if (marca[q] && !visto[q]) { visto[q] = true; fila[fim++] = q } }
                if (y > 0) { val q = p - w; if (marca[q] && !visto[q]) { visto[q] = true; fila[fim++] = q } }
                if (y < h - 1) { val q = p + w; if (marca[q] && !visto[q]) { visto[q] = true; fila[fim++] = q } }
            }
            if (fim > melhorArea) { melhorArea = fim; melhorLista = fila.copyOf(fim) }
        }
        val lista = melhorLista ?: return null
        // só os pixels de BORDA da mancha entram na geometria (mancha de 100 mil pixels → uns 2 mil de contorno)
        val marcaDaMancha = BooleanArray(w * h); for (p in lista) marcaDaMancha[p] = true
        val contorno = ArrayList<FloatArray>()
        for (p in lista) {
            val x = p % w; val y = p / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1 || !marcaDaMancha[p - 1] || !marcaDaMancha[p + 1] || !marcaDaMancha[p - w] || !marcaDaMancha[p + w]) contorno += floatArrayOf(x.toFloat(), y.toFloat())
        }
        val casco = fechoConvexo(contorno)
        val quatro = reduzA4(casco)
        val cantos = quatro?.let { ordena(it) } ?: run {
            // reserva: extremos x±y (falha com a folha girada ~45°)
            var tl = 0; var tr = 0; var br = 0; var bl = 0
            var minS = Int.MAX_VALUE; var maxS = Int.MIN_VALUE; var minD = Int.MAX_VALUE; var maxD = Int.MIN_VALUE
            for (p in lista) { val x = p % w; val y = p / w; val s = x + y; val d = x - y
                if (s < minS) { minS = s; tl = p }; if (s > maxS) { maxS = s; br = p }; if (d > maxD) { maxD = d; tr = p }; if (d < minD) { minD = d; bl = p } }
            val f = { p: Int -> floatArrayOf((p % w).toFloat(), (p / w).toFloat()) }
            listOf(f(tl), f(tr), f(br), f(bl))
        }
        return Quad(cantos[0], cantos[1], cantos[2], cantos[3], melhorArea, metodo)
    }

    /** Fecho convexo (cadeia monótona de Andrew), O(n log n). */
    private fun fechoConvexo(pts: List<FloatArray>): List<FloatArray> {
        if (pts.size < 4) return pts
        val p = pts.sortedWith(compareBy({ it[0] }, { it[1] }))
        fun cruz(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val baixo = ArrayList<FloatArray>(); for (q in p) { while (baixo.size >= 2 && cruz(baixo[baixo.size - 2], baixo[baixo.size - 1], q) <= 0) baixo.removeAt(baixo.size - 1); baixo += q }
        val cima = ArrayList<FloatArray>(); for (q in p.asReversed()) { while (cima.size >= 2 && cruz(cima[cima.size - 2], cima[cima.size - 1], q) <= 0) cima.removeAt(cima.size - 1); cima += q }
        return baixo.dropLast(1) + cima.dropLast(1)
    }

    /** Douglas-Peucker em polígono fechado, aumentando a tolerância até sobrar 4 vértices (como o approxPolyDP do OpenCV). */
    private fun reduzA4(casco: List<FloatArray>): List<FloatArray>? {
        if (casco.size < 4) return null
        if (casco.size == 4) return casco
        // perímetro para calibrar a tolerância
        var per = 0f; for (i in casco.indices) { val a = casco[i]; val b = casco[(i + 1) % casco.size]; per += hypot(b[0] - a[0], b[1] - a[1]) }
        var eps = per * 0.01f
        repeat(12) {
            val r = dp(casco, eps)
            if (r.size == 4) return r
            if (r.size < 4) return null
            eps *= 1.5f
        }
        return null
    }

    private fun dp(poli: List<FloatArray>, eps: Float): List<FloatArray> {
        // abre o polígono no par de vértices mais distante entre si (âncoras) e simplifica as duas metades
        var a = 0; var b = 0; var melhor = -1f
        for (i in poli.indices) for (j in i + 1 until poli.size) { val d = hypot(poli[i][0] - poli[j][0], poli[i][1] - poli[j][1]); if (d > melhor) { melhor = d; a = i; b = j } }
        val m1 = ArrayList<FloatArray>(); var i = a; while (true) { m1 += poli[i]; if (i == b) break; i = (i + 1) % poli.size }
        val m2 = ArrayList<FloatArray>(); i = b; while (true) { m2 += poli[i]; if (i == a) break; i = (i + 1) % poli.size }
        val s1 = simplifica(m1, eps); val s2 = simplifica(m2, eps)
        return s1.dropLast(1) + s2.dropLast(1)
    }

    private fun simplifica(pts: List<FloatArray>, eps: Float): List<FloatArray> {
        if (pts.size < 3) return pts
        val a = pts.first(); val b = pts.last()
        var maxD = -1f; var idx = 0
        val len = hypot(b[0] - a[0], b[1] - a[1]).coerceAtLeast(1e-3f)
        for (k in 1 until pts.size - 1) { val p = pts[k]; val d = abs((b[0] - a[0]) * (a[1] - p[1]) - (a[0] - p[0]) * (b[1] - a[1])) / len; if (d > maxD) { maxD = d; idx = k } }
        return if (maxD > eps) simplifica(pts.subList(0, idx + 1), eps).dropLast(1) + simplifica(pts.subList(idx, pts.size), eps) else listOf(a, b)
    }

    /** Ordena 4 pontos como TL, TR, BR, BL (soma e diferença das coordenadas). */
    private fun ordena(q: List<FloatArray>): List<FloatArray> {
        val tl = q.minByOrNull { it[0] + it[1] }!!; val br = q.maxByOrNull { it[0] + it[1] }!!
        val tr = q.maxByOrNull { it[0] - it[1] }!!; val bl = q.minByOrNull { it[0] - it[1] }!!
        return listOf(tl, tr, br, bl)
    }

    /** Decodifica reduzido (inSampleSize) e já girado pelo EXIF: 12 Mpx inteiros estouram a memória de celular de entrada. */
    fun decodeReduzido(contexto: Context, uri: Uri, ladoMax: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var amostra = 1; while (max(bounds.outWidth, bounds.outHeight) / (amostra * 2) >= ladoMax) amostra *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = amostra }
        val bruto = contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = contexto.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        if (rot == 0) return bruto
        val girado = Bitmap.createBitmap(bruto, 0, 0, bruto.width, bruto.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        if (girado !== bruto) bruto.recycle()
        return girado
    }

    /**
     * Realce SÓ na luminância (agy: por canal corrompe o matiz sob luz amarela e estoura o fundo de RG/CNH):
     * retinex simplificado na luma (Y ÷ fundo borrado de Y) e estiramento por percentis; R, G e B são
     * escalados pela mesma razão Y'/Y, então a cor fica. Documento colorido (muita saturação) recebe
     * branco mais brando (1%) do que folha de texto (8%).
     */
    private fun realca(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val fundo = fundoBorrado(b, 24)
        val y0 = IntArray(w * h); val y1 = IntArray(w * h)
        var satAcum = 0L
        for (i in px.indices) {
            val c = px[i]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            y0[i] = (r * 30 + g * 59 + bl * 11) / 100
            y1[i] = (y0[i] * 235 / max(1, luma(fundo[i]))).coerceIn(0, 255)
            satAcum += (max(r, max(g, bl)) - min(r, min(g, bl)))
        }
        val colorido = satAcum / px.size > 28          // saturação média: RG, recibo colorido, foto de cartão
        val hist = IntArray(256); for (v in y1) hist[v]++
        val n = px.size; var acc = 0; var lo = 0; var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * 0.01) { lo = i; break } }
        val corteBranco = if (colorido) 0.01 else 0.08
        acc = 0; for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * corteBranco) { hi = i; break } }
        val tabela = if (hi - lo >= 40) IntArray(256) { ((it - lo) * 255 / (hi - lo)).coerceIn(0, 255) } else IntArray(256) { it }
        for (i in px.indices) {
            val c = px[i]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            val alvo = tabela[y1[i]]; val base = max(1, y0[i])
            val rr = (r * alvo / base).coerceIn(0, 255); val gg = (g * alvo / base).coerceIn(0, 255); val bb = (bl * alvo / base).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
