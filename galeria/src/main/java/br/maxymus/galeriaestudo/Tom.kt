package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tom e detalhe em CPU, a MESMA função na prévia (bitmap de trabalho ~1024 px) e na exportação (≤4096 px): assim o
 * que se vê é o que se salva. Luminância preservando matiz (escala RGB pela razão L'/L). Tudo -100..100, 0 = neutro.
 */
object Tom {
    data class Parametros(
        val realces: Float = 0f, val sombras: Float = 0f, val brancos: Float = 0f, val pretos: Float = 0f,
        val nitidez: Float = 0f, val vinheta: Float = 0f, val granulacao: Float = 0f
    ) {
        val neutro: Boolean get() = realces == 0f && sombras == 0f && brancos == 0f && pretos == 0f && nitidez == 0f && vinheta == 0f && granulacao == 0f
    }

    private fun suave(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }
    private fun luma(c: Int): Float = ((c shr 16 and 255) * 0.299f + (c shr 8 and 255) * 0.587f + (c and 255) * 0.114f) / 255f

    /** Curva de luminância: sombras/realces por máscaras suaves; pretos/brancos deslocam as pontas. */
    private fun curvaL(l: Float, p: Parametros): Float {
        var v = l
        if (p.sombras != 0f) { val m = suave(1f - v * 1.6f); v += p.sombras / 100f * 0.45f * m * (if (p.sombras > 0f) 1f - v else v) }
        if (p.realces != 0f) { val m = suave((v - 0.35f) * 1.6f); v += p.realces / 100f * 0.45f * m * (if (p.realces > 0f) 1f - v else v) }
        if (p.pretos != 0f) { val k = p.pretos / 100f * 0.25f; v = if (k > 0f) v * (1f - k) + k else v * (1f + k) }   // >0 levanta pretos; <0 aprofunda
        if (p.brancos != 0f) { val k = p.brancos / 100f * 0.25f; v = if (k > 0f) v * (1f + k) else v * (1f + k) + 0f }  // >0 estende brancos; <0 recolhe
        return v.coerceIn(0f, 1f)
    }

    /** Aplica sobre os pixels (ARGB). w/h só importam para nitidez, vinheta e granulação. */
    fun aplicar(px: IntArray, w: Int, h: Int, p: Parametros): IntArray {
        if (p.neutro) return px
        val n = w * h
        var out = px
        // 1) tom por separação de frequências (agy, 20/09): L = base (caixa separável) + detalhe; a curva age só na
        //    base e o detalhe volta intacto, para clarear sombras sem lavar textura. Matiz preservado pela razão L'/L.
        if (p.sombras != 0f || p.realces != 0f || p.pretos != 0f || p.brancos != 0f) {
            val tab = FloatArray(1025) { curvaL(it / 1024f, p) }
            val lum = FloatArray(n) { luma(out[it]) }
            val base = caixaF(lum, w, h, max(2, min(w, h) / 40))
            val o = IntArray(n)
            for (k in 0 until n) {
                val c = out[k]; val l = lum[k]
                if (l <= 0.0005f) { o[k] = c; continue }
                val novaBase = tab[(base[k] * 1024f).toInt().coerceIn(0, 1024)]
                val novoL = (novaBase + (l - base[k])).coerceIn(0f, 1f)
                val g = novoL / l
                val r = ((c shr 16 and 255) * g).toInt().coerceIn(0, 255); val gg = ((c shr 8 and 255) * g).toInt().coerceIn(0, 255); val b = ((c and 255) * g).toInt().coerceIn(0, 255)
                o[k] = (c and (0xFF shl 24)) or (r shl 16) or (gg shl 8) or b
            }
            out = o
        }
        // 2) nitidez: máscara de nitidez (unsharp) 3x3, quantidade pela força; raio 1 px na prévia e proporcional no arquivo
        if (p.nitidez != 0f) {
            val q = (p.nitidez / 100f) * 1.2f
            val src = out; val o = IntArray(n)
            for (y in 0 until h) for (x in 0 until w) {
                val k = y * w + x
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { o[k] = src[k]; continue }
                var sr = 0; var sg = 0; var sb = 0
                for (dy in -1..1) for (dx in -1..1) { val c = src[k + dy * w + dx]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255 }
                val c = src[k]; val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                val rr = (r + (r - sr / 9f) * q).toInt().coerceIn(0, 255); val gg = (g + (g - sg / 9f) * q).toInt().coerceIn(0, 255); val bb = (b + (b - sb / 9f) * q).toInt().coerceIn(0, 255)
                o[k] = (c and (0xFF shl 24)) or (rr shl 16) or (gg shl 8) or bb
            }
            out = o
        }
        // 3) vinheta: escurece (>0) ou clareia (<0) as bordas com queda suave a partir de ~55% do raio
        if (p.vinheta != 0f) {
            val o = if (out === px) IntArray(n) else out
            val cx = (w - 1) / 2f; val cy = (h - 1) / 2f; val rmax = sqrt(cx * cx + cy * cy); val forca = p.vinheta / 100f * 0.7f
            for (y in 0 until h) for (x in 0 until w) {
                val k = y * w + x; val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / rmax
                val m = suave((d - 0.55f) / 0.6f); val g = 1f - forca * m
                val c = out[k]
                val r = ((c shr 16 and 255) * g).toInt().coerceIn(0, 255); val gg = ((c shr 8 and 255) * g).toInt().coerceIn(0, 255); val b = ((c and 255) * g).toInt().coerceIn(0, 255)
                o[k] = (c and (0xFF shl 24)) or (r shl 16) or (gg shl 8) or b
            }
            out = o
        }
        // 4) granulação: ruído determinístico por posição (hash), monocromático, mais forte nos meios-tons
        if (p.granulacao > 0f) {
            val o = if (out === px) IntArray(n) else out
            val amp = p.granulacao / 100f * 28f
            for (y in 0 until h) for (x in 0 until w) {
                val k = y * w + x
                var hsh = (x * 73856093) xor (y * 19349663); hsh = hsh xor (hsh ushr 13); hsh *= 0x5bd1e995.toInt(); hsh = hsh xor (hsh ushr 15)
                val ruido = ((hsh and 0xFFFF) / 65535f - 0.5f) * 2f
                val c = out[k]; val l = luma(c); val peso = 1f - abs(l - 0.5f) * 1.2f
                val d = (ruido * amp * peso.coerceAtLeast(0.2f)).toInt()
                val r = ((c shr 16 and 255) + d).coerceIn(0, 255); val gg = ((c shr 8 and 255) + d).coerceIn(0, 255); val b = ((c and 255) + d).coerceIn(0, 255)
                o[k] = (c and (0xFF shl 24)) or (r shl 16) or (gg shl 8) or b
            }
            out = o
        }
        return out
    }

    /** Média em caixa separável (raio r) sobre floats: duas passadas, custo ∝ pixels. */
    private fun caixaF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(a.size); val o = FloatArray(a.size)
        for (y in 0 until h) { val l = y * w; var s = 0f; var c = 0; for (x in 0 until min(w, r)) { s += a[l + x]; c++ }
            for (x in 0 until w) { if (x + r < w) { s += a[l + x + r]; c++ }; if (x - r - 1 >= 0) { s -= a[l + x - r - 1]; c-- }; t[l + x] = s / c } }
        for (x in 0 until w) { var s = 0f; var c = 0; for (y in 0 until min(h, r)) { s += t[y * w + x]; c++ }
            for (y in 0 until h) { if (y + r < h) { s += t[(y + r) * w + x]; c++ }; if (y - r - 1 >= 0) { s -= t[(y - r - 1) * w + x]; c-- }; o[y * w + x] = s / c } }
        return o
    }

    fun aplicar(b: Bitmap, p: Parametros): Bitmap {
        if (p.neutro) return b
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val out = aplicar(px, w, h, p)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * AUTO: níveis por percentil (0,5% e 99,5% da luminância viram 0 e 1) mais leve S nos meios-tons.
     * Devolve como parâmetros de Tom + Cor, para o usuário poder ajustar depois (nada é "queimado").
     */
    class Auto(val tom: Parametros, val brilho: Float, val contraste: Float, val saturacao: Float)

    fun auto(b: Bitmap): Auto {
        val w = b.width; val h = b.height
        val passo = max(1, (w * h) / 200_000)   // amostra ~200 mil pixels
        val hist = IntArray(256); var total = 0; var somaSat = 0f
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        var k = 0
        while (k < px.size) { val c = px[k]; hist[(luma(c) * 255f).toInt().coerceIn(0, 255)]++; total++
            val r = c shr 16 and 255; val g = c shr 8 and 255; val bb = c and 255; val mx = max(r, max(g, bb)); val mn = min(r, min(g, bb)); somaSat += if (mx == 0) 0f else (mx - mn).toFloat() / mx
            k += passo }
        var acc = 0; var p05 = 0; var p995 = 255
        for (i in 0 until 256) { acc += hist[i]; if (acc >= total * 0.005f) { p05 = i; break } }
        acc = 0; for (i in 255 downTo 0) { acc += hist[i]; if (acc >= total * 0.005f) { p995 = i; break } }
        val faixa = (p995 - p05).coerceAtLeast(32)
        // contraste pelo quanto a faixa está encolhida; brilho pelo desvio da mediana
        val contraste = ((255f / faixa - 1f) * 60f).coerceIn(0f, 30f)
        acc = 0; var mediana = 128; for (i in 0 until 256) { acc += hist[i]; if (acc >= total / 2) { mediana = i; break } }
        val brilho = ((118 - mediana) / 255f * 60f).coerceIn(-20f, 25f)
        val satMedia = somaSat / total.coerceAtLeast(1)
        val saturacao = if (satMedia < 0.25f) 12f else if (satMedia < 0.4f) 6f else 0f
        val sombras = if (p05 > 25) 0f else 14f; val realces = if (p995 < 230) 0f else -10f
        return Auto(Parametros(realces = realces, sombras = sombras), brilho, contraste, saturacao)
    }
}
