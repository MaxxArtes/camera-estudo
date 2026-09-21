package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Misturador de cores (HSL): 8 faixas de matiz, cada uma com deslocamento de matiz, saturação e luminância.
 * Peso por distância angular (largura 45°) e pela saturação do pixel (cinza não muda). CPU nas duas pontas.
 */
object Hsl {
    val NOMES = listOf("Vermelho", "Laranja", "Amarelo", "Verde", "Ciano", "Azul", "Roxo", "Magenta")
    val CENTROS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 300f)
    val CORES_UI = intArrayOf(0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt())

    data class Parametros(val matiz: List<Float> = List(8) { 0f }, val saturacao: List<Float> = List(8) { 0f }, val luminancia: List<Float> = List(8) { 0f }) {
        val neutro: Boolean get() = matiz.all { it == 0f } && saturacao.all { it == 0f } && luminancia.all { it == 0f }
        fun com(faixa: Int, campo: String, v: Float): Parametros = when (campo) {
            "Matiz" -> copy(matiz = matiz.toMutableList().also { it[faixa] = v })
            "Saturação" -> copy(saturacao = saturacao.toMutableList().also { it[faixa] = v })
            else -> copy(luminancia = luminancia.toMutableList().also { it[faixa] = v })
        }
        fun valor(faixa: Int, campo: String): Float = when (campo) { "Matiz" -> matiz[faixa]; "Saturação" -> saturacao[faixa]; else -> luminancia[faixa] }
        fun alterada(faixa: Int) = matiz[faixa] != 0f || saturacao[faixa] != 0f || luminancia[faixa] != 0f
    }

    fun aplicar(px: IntArray, p: Parametros): IntArray {
        if (p.neutro) return px
        val out = IntArray(px.size)
        val w = FloatArray(8)
        for (k in px.indices) {
            val c = px[k]; val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
            if (mx <= 0f || d < 0.02f) { out[k] = c; continue }
            val s = d / mx
            var h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
            if (h < 0f) h += 360f
            var soma = 0f
            for (i in 0 until 8) { var dist = abs(h - CENTROS[i]); if (dist > 180f) dist = 360f - dist; w[i] = max(0f, 1f - dist / 45f); soma += w[i] }
            if (soma <= 0f) { out[k] = c; continue }
            val norm = s / max(1f, soma)   // cinza quase não muda; faixas vizinhas dividem o peso
            var dh = 0f; var ds = 0f; var dl = 0f
            for (i in 0 until 8) { val wi = w[i] * norm; if (wi == 0f) continue; dh += wi * p.matiz[i] / 100f * 30f; ds += wi * p.saturacao[i] / 100f; dl += wi * p.luminancia[i] / 100f * 0.5f }
            var nh = (h + dh) % 360f; if (nh < 0f) nh += 360f
            val ns = (s * (1f + ds)).coerceIn(0f, 1f); val nv = (mx * (1f + dl)).coerceIn(0f, 1f)
            // HSV → RGB
            val cc = nv * ns; val x = cc * (1f - abs((nh / 60f) % 2f - 1f)); val m = nv - cc
            val (r1, g1, b1) = when { nh < 60f -> Triple(cc, x, 0f); nh < 120f -> Triple(x, cc, 0f); nh < 180f -> Triple(0f, cc, x); nh < 240f -> Triple(0f, x, cc); nh < 300f -> Triple(x, 0f, cc); else -> Triple(cc, 0f, x) }
            val rr = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255); val gg = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255); val bb = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[k] = (c and (0xFF shl 24)) or (rr shl 16) or (gg shl 8) or bb
        }
        return out
    }

    fun aplicar(b: Bitmap, p: Parametros): Bitmap {
        if (p.neutro) return b
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        return Bitmap.createBitmap(aplicar(px, p), w, h, Bitmap.Config.ARGB_8888)
    }
}
