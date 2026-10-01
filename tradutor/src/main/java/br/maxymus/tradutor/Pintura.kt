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
 *  2. A cobertura era pelo FORMATO DAS LETRAS, ideia do dono em 22/09. Em 01/10, lendo um capítulo inteiro, ele
 *     reverteu vendo o resultado: "mesmo que não fique certinho, fazer só uma faixa quadrada; o importante é ser
 *     rápido, natural e legível". A máscara irregular vazava para fora do balão em quadro escuro e o texto saía
 *     miúdo demais. Agora é FAIXA SÓLIDA, e legibilidade ganha de elegância.
 *  3. A faixa é opaca e de canto reto, na cor do balão, com o texto na cor medida da letra (desenho do Astra,
 *     01/10). Nada de transparência: sobre arte escura ela precisa delimitar a área sozinha.
 *
 * A saída é uma camada transparente do tamanho da tela: só o que foi pintado fica opaco.
 */
object Pintura {

    /** Uma fala já medida e pronta para desenhar. A posição ainda pode mudar na resolução de colisão. */
    private class Faixa(var x0: Int, var y0: Int, var x1: Int, var y1: Int,
                        var linhas: List<String>, val tam: Float, val fundo: Int, val letra: Int) {
        val altura get() = y1 - y0
        fun bate(o: Faixa, folga: Int) = x0 < o.x1 && o.x0 < x1 && y0 < o.y1 + folga && o.y0 < y1 + folga
    }

    fun camada(tela: Bitmap, falas: List<Falas.Fala>, traduz: (String) -> String): Bitmap {
        val w = tela.width; val h = tela.height
        val saida = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(saida)
        val px = IntArray(w * h).also { tela.getPixels(it, 0, w, 0, 0, w, h) }
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }

        // ---- 1. medir todas as falas ANTES de desenhar qualquer uma ----
        val faixas = ArrayList<Faixa>()
        for (f in falas) {
            val texto = traduz(f.texto)
            if (texto.isBlank()) continue
            val alt = f.alturaLinha
            val pad = max(8, (alt * 0.45f).toInt())
            val ax0 = max(0, f.caixa.left - pad); val ay0 = max(0, f.caixa.top - pad)
            val ax1 = min(w, f.caixa.right + pad); val ay1 = min(h, f.caixa.bottom + pad)
            val rw = ax1 - ax0; val rh = ay1 - ay0
            if (rw < 8 || rh < 8) continue

            val fundo = corDoAnel(px, w, ax0, ay0, rw, rh, pad)
            val dist = IntArray(rw * rh)
            for (y in 0 until rh) for (x in 0 until rw) {
                val c = px[(ay0 + y) * w + (ax0 + x)]
                dist[y * rw + x] = abs((c shr 16 and 255) - (fundo shr 16 and 255)) +
                        abs((c shr 8 and 255) - (fundo shr 8 and 255)) + abs((c and 255) - (fundo and 255))
            }
            val letra = corDaLetra(px, w, ax0, ay0, rw, rh, dist, fundo)

            // margens e limites de crescimento, do desenho do Astra: 6% na horizontal, 4% na vertical,
            // cresce até 10% de largura e 30% de altura, e só então encolhe a fonte, no máximo 10%
            val mx = max(4, (f.caixa.width() * 0.06f).toInt())
            val my = max(3, (f.caixa.height() * 0.04f).toInt())
            val larguraUtil = (min(w - 2 * mx, (f.caixa.width() * 1.10f).toInt()) - 2 * mx).coerceAtLeast(24)
            val alturaMax = f.caixa.height() * 1.30f
            val piso = max(14f, alt * 0.90f)
            var tam = alt * 1.05f
            var linhas: List<String>
            while (true) {
                tinta.textSize = tam
                linhas = quebra(texto, tinta, larguraUtil.toFloat())
                if (linhas.size * tam * 1.25f <= alturaMax || tam <= piso) break
                tam -= 1f
            }
            tinta.textSize = tam
            val larguraTexto = linhas.maxOfOrNull { tinta.measureText(it) }?.toInt() ?: 0
            val fw = min(w, max(f.caixa.width(), larguraTexto + 1) + 2 * mx)
            val fh = min(h, (linhas.size * tam * 1.25f).toInt() + 2 * my)
            val fx0 = (f.caixa.centerX() - fw / 2).coerceIn(0, max(0, w - fw))
            val fy0 = (f.caixa.centerY() - fh / 2).coerceIn(0, max(0, h - fh))
            faixas += Faixa(fx0, fy0, fx0 + fw, fy0 + fh, linhas, tam, fundo, letra)
        }

        // ---- 2. resolver colisão ANTES de desenhar: nunca uma tradução por cima da outra ----
        val folga = max(4, (h * 0.004f).toInt())
        faixas.sortBy { it.y0 }
        val postas = ArrayList<Faixa>()
        for (fx in faixas) {
            var voltas = 0
            while (voltas < 40) {
                val choque = postas.firstOrNull { it.bate(fx, folga) } ?: break
                val novoTopo = choque.y1 + folga
                if (novoTopo + fx.altura > h) break
                fx.y1 = novoTopo + fx.altura; fx.y0 = novoTopo
                voltas++
            }
            val resta = postas.firstOrNull { it.bate(fx, folga) }
            if (resta == null) postas += fx
            else {
                // sem espaço para deslocar: junta as duas numa faixa só, em parágrafos na ordem de leitura
                resta.linhas = resta.linhas + fx.linhas
                resta.y1 = min(h, resta.y0 + (resta.linhas.size * resta.tam * 1.25f).toInt() + 8)
                resta.x1 = min(w, max(resta.x1, fx.x1)); resta.x0 = min(resta.x0, fx.x0)
            }
        }

        // ---- 3. desenhar ----
        val tintaFundo = Paint()
        for (fx in postas) {
            tintaFundo.color = fx.fundo
            canvas.drawRect(fx.x0.toFloat(), fx.y0.toFloat(), fx.x1.toFloat(), fx.y1.toFloat(), tintaFundo)
            tinta.color = fx.letra; tinta.textSize = fx.tam
            val linha = fx.tam * 1.25f
            var y = fx.y0 + (fx.altura - fx.linhas.size * linha) / 2f + fx.tam
            val cx = (fx.x0 + fx.x1) / 2f
            for (l in fx.linhas) { canvas.drawText(l, cx, y, tinta); y += linha }
        }
        return saida
    }

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
