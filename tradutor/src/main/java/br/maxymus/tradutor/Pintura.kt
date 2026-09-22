package br.maxymus.tradutor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Desenha a tradução por cima da tela. Porte fiel do protótipo validado na bancada em 22/09 sobre capturas reais
 * do dono (scratchpad/p3.py), com três defeitos já corrigidos lá antes de virar Kotlin:
 *
 *  1. Conta-gotas: a cor do fundo vem da mediana do ANEL em volta da caixa do texto, não de um chute.
 *  2. Cobertura pelo FORMATO DAS LETRAS, não por retângulo (ideia do dono): tudo que se afasta da cor do fundo é
 *     letra; esse desenho é engordado e só ele é coberto. No fundo preto o retângulo deixava um cinza visível, e
 *     sobre arte ele apagava desenho que não era texto.
 *  3. A cobertura tem que ser MAIOR que a suavização da borda, senão o texto original vaza e os dois se leem juntos.
 *
 * A saída é uma camada transparente do tamanho da tela: só o que foi pintado fica opaco.
 */
object Pintura {

    fun camada(tela: Bitmap, falas: List<Falas.Fala>, traduz: (String) -> String): Bitmap {
        val w = tela.width; val h = tela.height
        val saida = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(saida)
        val px = IntArray(w * h).also { tela.getPixels(it, 0, w, 0, 0, w, h) }
        for (f in falas) {
            val alt = f.alturaLinha
            val pad = max(10, (alt * 0.45f).toInt())
            val x0 = max(0, f.caixa.left - pad); val y0 = max(0, f.caixa.top - pad)
            val x1 = min(w, f.caixa.right + pad); val y1 = min(h, f.caixa.bottom + pad)
            val rw = x1 - x0; val rh = y1 - y0
            if (rw < 8 || rh < 8) continue

            val fundo = corDoAnel(px, w, x0, y0, rw, rh, pad)
            val dist = IntArray(rw * rh)
            for (y in 0 until rh) for (x in 0 until rw) {
                val c = px[(y0 + y) * w + (x0 + x)]
                dist[y * rw + x] = abs((c shr 16 and 255) - (fundo shr 16 and 255)) +
                        abs((c shr 8 and 255) - (fundo shr 8 and 255)) + abs((c and 255) - (fundo and 255))
            }
            val limiar = max(60, (percentil(dist, 97) * 0.35f).toInt())
            val letra = corDaLetra(px, w, x0, y0, rw, rh, dist, fundo)

            var mascara = BooleanArray(rw * rh) { dist[it] >= limiar }
            val engorda = max(3, (alt * 0.22f).toInt())
            mascara = dilata(mascara, rw, rh, engorda)
            val alfa = suaviza(mascara, rw, rh, max(1, (alt * 0.07f).toInt()))

            val cobre = IntArray(rw * rh) { k -> ((alfa[k] * 255).toInt().coerceIn(0, 255) shl 24) or (fundo and 0x00FFFFFF) }
            canvas.drawBitmap(cobre, 0, rw, x0, y0, rw, rh, true, null)
            escreve(canvas, traduz(f.texto), x0, y0, rw, rh, alt, letra)
        }
        return saida
    }

    /** Conta-gotas: mediana do anel de `pad` px em volta da caixa. É a cor que o balão tem ali. */
    private fun corDoAnel(px: IntArray, w: Int, x0: Int, y0: Int, rw: Int, rh: Int, pad: Int): Int {
        val r = ArrayList<Int>(4 * pad * max(rw, rh) / 2)
        val g = ArrayList<Int>(r.size); val b = ArrayList<Int>(r.size)
        for (y in 0 until rh) for (x in 0 until rw) {
            if (x >= pad && y >= pad && x < rw - pad && y < rh - pad) continue
            val c = px[(y0 + y) * w + (x0 + x)]
            r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
        }
        if (r.isEmpty()) return 0xFF000000.toInt()
        r.sort(); g.sort(); b.sort()
        val m = r.size / 2
        return (0xFF shl 24) or (r[m] shl 16) or (g[m] shl 8) or b[m]
    }

    /** Cor da letra: mediana do 1% de pixels mais distantes do fundo; sem contraste, cai para preto ou branco. */
    private fun corDaLetra(px: IntArray, w: Int, x0: Int, y0: Int, rw: Int, rh: Int, dist: IntArray, fundo: Int): Int {
        val corte = percentil(dist, 99)
        val r = ArrayList<Int>(); val g = ArrayList<Int>(); val b = ArrayList<Int>()
        for (y in 0 until rh) for (x in 0 until rw) {
            if (dist[y * rw + x] < corte) continue
            val c = px[(y0 + y) * w + (x0 + x)]
            r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
        }
        val lf = 0.299f * (fundo shr 16 and 255) + 0.587f * (fundo shr 8 and 255) + 0.114f * (fundo and 255)
        if (r.isEmpty()) return if (lf > 127) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        r.sort(); g.sort(); b.sort(); val m = r.size / 2
        val cl = (0xFF shl 24) or (r[m] shl 16) or (g[m] shl 8) or b[m]
        val ll = 0.299f * r[m] + 0.587f * g[m] + 0.114f * b[m]
        return if (abs(ll - lf) < 60f) (if (lf > 127) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()) else cl
    }

    private fun percentil(v: IntArray, p: Int): Int {
        if (v.isEmpty()) return 0
        val c = v.copyOf(); c.sort()
        return c[((c.size - 1) * p / 100).coerceIn(0, c.size - 1)]
    }

    /** Dilatação em caixa separável: engorda o desenho das letras para pegar contorno e sombra. */
    private fun dilata(b: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val t = BooleanArray(w * h); val o = BooleanArray(w * h)
        for (y in 0 until h) { val l = y * w; var c = 0
            for (x in 0 until min(w, r)) if (b[l + x]) c++
            for (x in 0 until w) { if (x + r < w && b[l + x + r]) c++; if (x - r - 1 >= 0 && b[l + x - r - 1]) c--; t[l + x] = c > 0 } }
        for (x in 0 until w) { var c = 0
            for (y in 0 until min(h, r)) if (t[y * w + x]) c++
            for (y in 0 until h) { if (y + r < h && t[(y + r) * w + x]) c++; if (y - r - 1 >= 0 && t[(y - r - 1) * w + x]) c--; o[y * w + x] = c > 0 } }
        return o
    }

    /** Borda macia: média em caixa sobre a máscara, para a cobertura não ter serrilhado. */
    private fun suaviza(b: BooleanArray, w: Int, h: Int, r: Int): FloatArray {
        val a = FloatArray(w * h) { if (b[it]) 1f else 0f }
        val t = FloatArray(w * h); val o = FloatArray(w * h)
        for (y in 0 until h) { val l = y * w; var s = 0f; var n = 0
            for (x in 0 until min(w, r)) { s += a[l + x]; n++ }
            for (x in 0 until w) { if (x + r < w) { s += a[l + x + r]; n++ }; if (x - r - 1 >= 0) { s -= a[l + x - r - 1]; n-- }; t[l + x] = s / n } }
        for (x in 0 until w) { var s = 0f; var n = 0
            for (y in 0 until min(h, r)) { s += t[y * w + x]; n++ }
            for (y in 0 until h) { if (y + r < h) { s += t[(y + r) * w + x]; n++ }; if (y - r - 1 >= 0) { s -= t[(y - r - 1) * w + x]; n-- }
                o[y * w + x] = min(1f, t[y * w + x] * 0.35f + (s / n) * 1.3f) } }
        return o
    }

    /** Escreve centralizado, quebrando por palavra e encolhendo até caber (português é mais longo que inglês). */
    private fun escreve(canvas: Canvas, texto: String, x0: Int, y0: Int, rw: Int, rh: Int, alt: Int, cor: Int) {
        if (texto.isBlank()) return
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = cor; textAlign = Paint.Align.CENTER
        }
        var tam = max(12f, alt * 1.05f)
        var linhas: List<String> = emptyList()
        while (tam > 10f) {
            tinta.textSize = tam
            linhas = quebra(texto, tinta, rw * 0.92f)
            if (linhas.size * tam * 1.2f <= rh * 0.96f) break
            tam -= 2f
        }
        tinta.textSize = tam
        var y = y0 + (rh - linhas.size * tam * 1.2f) / 2f + tam
        val cx = x0 + rw / 2f
        for (l in linhas) { canvas.drawText(l, cx, y, tinta); y += tam * 1.2f }
    }

    private fun quebra(texto: String, tinta: Paint, largura: Float): List<String> {
        val saida = ArrayList<String>(); var atual = StringBuilder()
        for (p in texto.split(" ").filter { it.isNotBlank() }) {
            val teste = if (atual.isEmpty()) p else "$atual $p"
            if (tinta.measureText(teste) <= largura || atual.isEmpty()) atual = StringBuilder(teste)
            else { saida += atual.toString(); atual = StringBuilder(p) }
        }
        if (atual.isNotEmpty()) saida += atual.toString()
        return saida
    }
}
