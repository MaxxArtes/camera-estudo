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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Scanner de documento feito em casa, para a tela ser sempre a nossa (sem a tela do Google):
 *  1. reduz a foto, converte em cinza e separa "claro" de "escuro" (limiar de Otsu);
 *  2. pega a maior mancha clara (a folha) e os 4 cantos dela (pontos extremos);
 *  3. endireita a perspectiva com Matrix.setPolyToPoly (Android já sabe fazer);
 *  4. realça: estica o contraste e clareia o fundo, para parecer digitalizado.
 * Se não achar uma folha convincente, devolve a foto inteira só com o realce.
 *
 * Limite honesto: funciona bem com folha clara sobre fundo mais escuro; folha branca sobre
 * mesa branca engana o passo 2. Detecção robusta de bordas é assunto para OpenCV.
 */
object Documento {
    private const val LADO_ANALISE = 600
    private const val LADO_SAIDA = 2000

    data class Resultado(val recortou: Boolean)

    suspend fun processar(contexto: Context, uri: Uri): Resultado? = withContext(Dispatchers.Default) {
        runCatching {
            val bruto = contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
            val rot = contexto.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            val foto = if (rot != 0) Bitmap.createBitmap(bruto, 0, 0, bruto.width, bruto.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bruto

            // 1) versão pequena em cinza
            val esc = LADO_ANALISE.toFloat() / max(foto.width, foto.height)
            val pw = max(1, (foto.width * esc).toInt()); val ph = max(1, (foto.height * esc).toInt())
            val pequena = Bitmap.createScaledBitmap(foto, pw, ph, true)
            val px = IntArray(pw * ph).also { pequena.getPixels(it, 0, pw, 0, 0, pw, ph) }
            val cinza = IntArray(pw * ph) { val c = px[it]; ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100 }
            val limiar = otsu(cinza)

            // 2) maior mancha clara e seus cantos
            val cantos = maiorManchaCantos(cinza, pw, ph, limiar)
            val areaMinima = 0.18f * pw * ph
            var recortou = false
            var saida: Bitmap = foto
            if (cantos != null && cantos.area >= areaMinima) {
                val f = 1f / esc
                val tl = cantos.tl.map { it * f }; val tr = cantos.tr.map { it * f }; val br = cantos.br.map { it * f }; val bl = cantos.bl.map { it * f }
                val larg = ((hypot(tr[0] - tl[0], tr[1] - tl[1]) + hypot(br[0] - bl[0], br[1] - bl[1])) / 2).toInt().coerceIn(200, 6000)
                val alt = ((hypot(bl[0] - tl[0], bl[1] - tl[1]) + hypot(br[0] - tr[0], br[1] - tr[1])) / 2).toInt().coerceIn(200, 6000)
                val razao = larg.toFloat() / alt
                if (razao in 0.3f..3.3f) {
                    val escS = min(1f, LADO_SAIDA.toFloat() / max(larg, alt))
                    val w = (larg * escS).toInt(); val h = (alt * escS).toInt()
                    val m = Matrix()
                    val ok = m.setPolyToPoly(
                        floatArrayOf(tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]), 0,
                        floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4
                    )
                    if (ok) {
                        val plano = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(plano).drawBitmap(foto, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                        saida = plano; recortou = true
                    }
                }
            }

            // 4) realce "digitalizado"
            val realcada = realca(saida)
            contexto.contentResolver.openOutputStream(uri, "wt")?.use { realcada.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching null
            Resultado(recortou)
        }.getOrNull()
    }

    private fun otsu(cinza: IntArray): Int {
        val hist = IntArray(256); for (v in cinza) hist[v]++
        val total = cinza.size; var soma = 0L; for (i in 0..255) soma += i.toLong() * hist[i]
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

    private class Cantos(val tl: FloatArray, val tr: FloatArray, val br: FloatArray, val bl: FloatArray, val area: Int)

    /** Rotula as manchas claras (varredura em largura) e devolve os cantos extremos da maior. */
    private fun maiorManchaCantos(cinza: IntArray, w: Int, h: Int, limiar: Int): Cantos? {
        val claro = BooleanArray(w * h) { cinza[it] > limiar }
        val visto = BooleanArray(w * h)
        val fila = IntArray(w * h)
        var melhorArea = 0; var melhorLista: IntArray? = null
        for (inicio in 0 until w * h) {
            if (!claro[inicio] || visto[inicio]) continue
            var ini = 0; var fim = 0; fila[fim++] = inicio; visto[inicio] = true
            while (ini < fim) {
                val p = fila[ini++]; val x = p % w; val y = p / w
                if (x > 0) { val q = p - 1; if (claro[q] && !visto[q]) { visto[q] = true; fila[fim++] = q } }
                if (x < w - 1) { val q = p + 1; if (claro[q] && !visto[q]) { visto[q] = true; fila[fim++] = q } }
                if (y > 0) { val q = p - w; if (claro[q] && !visto[q]) { visto[q] = true; fila[fim++] = q } }
                if (y < h - 1) { val q = p + w; if (claro[q] && !visto[q]) { visto[q] = true; fila[fim++] = q } }
            }
            if (fim > melhorArea) { melhorArea = fim; melhorLista = fila.copyOf(fim) }
        }
        val lista = melhorLista ?: return null
        // cantos: mínimos e máximos de (x+y) e (x-y)
        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var minS = Int.MAX_VALUE; var maxS = Int.MIN_VALUE; var minD = Int.MAX_VALUE; var maxD = Int.MIN_VALUE
        for (p in lista) {
            val x = p % w; val y = p / w; val s = x + y; val d = x - y
            if (s < minS) { minS = s; tl = p }; if (s > maxS) { maxS = s; br = p }
            if (d > maxD) { maxD = d; tr = p }; if (d < minD) { minD = d; bl = p }
        }
        val f = { p: Int -> floatArrayOf((p % w).toFloat(), (p / w).toFloat()) }
        return Cantos(f(tl), f(tr), f(br), f(bl), melhorArea)
    }

    /** Estica o contraste: o 2% mais escuro vira preto e o 8% mais claro vira branco (fundo limpo). */
    private fun realca(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val hist = IntArray(256)
        for (c in px) hist[((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100]++
        val n = px.size; var acc = 0; var lo = 0; var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * 0.02) { lo = i; break } }
        acc = 0; for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * 0.08) { hi = i; break } }
        if (hi - lo < 40) return b
        val tabela = IntArray(256) { ((it - lo) * 255 / (hi - lo)).coerceIn(0, 255) }
        for (i in px.indices) {
            val c = px[i]
            px[i] = (0xFF shl 24) or (tabela[c shr 16 and 255] shl 16) or (tabela[c shr 8 and 255] shl 8) or tabela[c and 255]
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
