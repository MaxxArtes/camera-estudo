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

    suspend fun processar(contexto: Context, uri: Uri): Resultado? = withContext(Dispatchers.Default) {
        runCatching {
            val bruto = contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
            val rot = contexto.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            val foto = if (rot != 0) Bitmap.createBitmap(bruto, 0, 0, bruto.width, bruto.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bruto

            // 1) pequena, cinza, iluminação normalizada
            val esc = LADO_ANALISE.toFloat() / max(foto.width, foto.height)
            val pw = max(1, (foto.width * esc).toInt()); val ph = max(1, (foto.height * esc).toInt())
            val pequena = Bitmap.createScaledBitmap(foto, pw, ph, true)
            val px = IntArray(pw * ph).also { pequena.getPixels(it, 0, pw, 0, 0, pw, ph) }
            val cinza = IntArray(pw * ph) { luma(px[it]) }
            val fundo = fundoBorrado(pequena, 40)
            val norm = IntArray(pw * ph) { (cinza[it] * 200 / max(1, luma(fundo[it]))).coerceIn(0, 255) }   // 200 ≈ "branco" após dividir

            // 2) candidatos
            val candidatos = mutableListOf<Quad>()
            maiorMancha(BooleanArray(pw * ph) { norm[it] > otsu(norm) }, pw, ph, "claro")?.let { candidatos += it }
            maiorMancha(regiaoLisa(cinza, pw, ph), pw, ph, "bordas")?.let { candidatos += it }
            val melhor = candidatos.maxByOrNull { it.placar(pw * ph) }?.takeIf { it.placar(pw * ph) > 0f }

            // 3) recorte com perspectiva
            var saida: Bitmap = foto; var recortou = false
            if (melhor != null) {
                val f = 1f / esc
                val tl = melhor.tl.map { it * f }; val tr = melhor.tr.map { it * f }; val br = melhor.br.map { it * f }; val bl = melhor.bl.map { it * f }
                val larg = ((hypot(tr[0] - tl[0], tr[1] - tl[1]) + hypot(br[0] - bl[0], br[1] - bl[1])) / 2).toInt().coerceIn(200, 6000)
                val alt = ((hypot(bl[0] - tl[0], bl[1] - tl[1]) + hypot(br[0] - tr[0], br[1] - tr[1])) / 2).toInt().coerceIn(200, 6000)
                val escS = min(1f, LADO_SAIDA.toFloat() / max(larg, alt))
                val w = (larg * escS).toInt(); val h = (alt * escS).toInt()
                val m = Matrix()
                if (m.setPolyToPoly(floatArrayOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]), 0, floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)) {
                    val plano = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(plano).drawBitmap(foto, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                    saida = plano; recortou = true
                }
            }

            // 4) realce: sombras fora, fundo branco, contraste
            val realcada = realca(saida)
            contexto.contentResolver.openOutputStream(uri, "wt")?.use { realcada.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching null
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
        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var minS = Int.MAX_VALUE; var maxS = Int.MIN_VALUE; var minD = Int.MAX_VALUE; var maxD = Int.MIN_VALUE
        for (p in lista) {
            val x = p % w; val y = p / w; val s = x + y; val d = x - y
            if (s < minS) { minS = s; tl = p }; if (s > maxS) { maxS = s; br = p }
            if (d > maxD) { maxD = d; tr = p }; if (d < minD) { minD = d; bl = p }
        }
        val f = { p: Int -> floatArrayOf((p % w).toFloat(), (p / w).toFloat()) }
        return Quad(f(tl), f(tr), f(br), f(bl), melhorArea, metodo)
    }

    /** Retinex simplificado por canal (pixel ÷ fundo borrado) + contraste por percentis: sombras somem, fundo fica branco. */
    private fun realca(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val fundo = fundoBorrado(b, 24)
        val lum = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]; val f = fundo[i]
            val r = ((c shr 16 and 255) * 235 / max(1, f shr 16 and 255)).coerceIn(0, 255)
            val g = ((c shr 8 and 255) * 235 / max(1, f shr 8 and 255)).coerceIn(0, 255)
            val bl = ((c and 255) * 235 / max(1, f and 255)).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            lum[i] = (r * 30 + g * 59 + bl * 11) / 100
        }
        val hist = IntArray(256); for (v in lum) hist[v]++
        val n = px.size; var acc = 0; var lo = 0; var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * 0.02) { lo = i; break } }
        acc = 0; for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * 0.10) { hi = i; break } }
        if (hi - lo >= 40) {
            val tabela = IntArray(256) { ((it - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
            for (i in px.indices) { val c = px[i]; px[i] = (0xFF shl 24) or (tabela[c shr 16 and 255] shl 16) or (tabela[c shr 8 and 255] shl 8) or tabela[c and 255] }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
