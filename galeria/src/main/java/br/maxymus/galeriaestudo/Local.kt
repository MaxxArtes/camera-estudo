package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Ajustes locais (desenho do Astra, 20/09): até 3 máscaras (Radial, Linear, Pessoa), cada uma com seus sliders.
 * Coordenadas NORMALIZADAS (0..1) sobre a imagem já com geometria: valem em qualquer resolução. Ordem fixa de
 * processamento (a lista); a mesma função na prévia e no arquivo.
 */
object Local {
    enum class Tipo { Radial, Linear, Pessoa }
    const val MAX = 3

    data class Mascara(
        val tipo: Tipo,
        val cx: Float = 0.5f, val cy: Float = 0.5f, val rx: Float = 0.25f, val ry: Float = 0.25f, val suavidade: Float = 60f,   // radial
        val x1: Float = 0.5f, val y1: Float = 0.3f, val x2: Float = 0.5f, val y2: Float = 0.7f,   // linear: (x1,y1) = 100% do efeito, (x2,y2) = 0%
        val invertida: Boolean = false,
        val exposicao: Float = 0f, val contraste: Float = 0f, val saturacao: Float = 0f, val temperatura: Float = 0f, val sombras: Float = 0f, val realces: Float = 0f
    ) {
        val neutra: Boolean get() = exposicao == 0f && contraste == 0f && saturacao == 0f && temperatura == 0f && sombras == 0f && realces == 0f
        fun valor(p: String): Float = when (p) { "Exposição" -> exposicao; "Contraste" -> contraste; "Saturação" -> saturacao; "Temperatura" -> temperatura; "Sombras" -> sombras; else -> realces }
        fun com(p: String, v: Float): Mascara = when (p) {
            "Exposição" -> copy(exposicao = v); "Contraste" -> copy(contraste = v); "Saturação" -> copy(saturacao = v)
            "Temperatura" -> copy(temperatura = v); "Sombras" -> copy(sombras = v); else -> copy(realces = v)
        }
    }
    fun nova(tipo: Tipo, proporcao: Float): Mascara = when (tipo) {   // proporcao = largura/altura da imagem
        Tipo.Radial -> { val r = 0.25f; if (proporcao >= 1f) Mascara(tipo, rx = r / proporcao, ry = r) else Mascara(tipo, rx = r, ry = r * proporcao) }
        Tipo.Linear -> Mascara(tipo, x1 = 0.5f, y1 = 0.25f, x2 = 0.5f, y2 = 0.6f)
        Tipo.Pessoa -> Mascara(tipo)
    }
    val SLIDERS = listOf("Exposição", "Contraste", "Saturação", "Temperatura", "Sombras", "Realces")

    data class Parametros(val mascaras: List<Mascara> = emptyList()) {
        val neutro: Boolean get() = mascaras.all { it.neutra }
        val precisaPessoa: Boolean get() = mascaras.any { it.tipo == Tipo.Pessoa && !it.neutra }
    }

    private fun suave(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    /** Peso 0..1 da máscara no ponto normalizado. `pessoa` só é usada pelo tipo Pessoa. */
    fun peso(m: Mascara, nx: Float, ny: Float, pessoa: FloatArray?, pw: Int = 0, ph: Int = 0): Float {
        val w = when (m.tipo) {
            Tipo.Radial -> {
                val dx = (nx - m.cx) / m.rx.coerceAtLeast(0.01f); val dy = (ny - m.cy) / m.ry.coerceAtLeast(0.01f)
                val d = sqrt(dx * dx + dy * dy); val s = (m.suavidade / 100f).coerceIn(0.02f, 1f)
                1f - suave((d - (1f - s)) / s)   // 1 no miolo, cai a 0 na borda da elipse
            }
            Tipo.Linear -> {
                val ax = m.x2 - m.x1; val ay = m.y2 - m.y1; val l2 = ax * ax + ay * ay
                if (l2 < 1e-6f) 1f else { val t = ((nx - m.x1) * ax + (ny - m.y1) * ay) / l2; 1f - suave(t) }
            }
            Tipo.Pessoa -> if (pessoa == null || pw <= 0 || ph <= 0) 0f else suave((pessoa[(ny * (ph - 1)).toInt().coerceIn(0, ph - 1) * pw + (nx * (pw - 1)).toInt().coerceIn(0, pw - 1)] - 0.2f) / 0.6f)
        }
        return if (m.invertida) 1f - w else w
    }

    /** Curva simples de sombras/realces (sem separação de frequências: local, evita halo na borda da máscara). */
    private fun curvaL(l: Float, sombras: Float, realces: Float): Float {
        var v = l
        if (sombras != 0f) { val m = suave(1f - v * 1.6f); v += sombras / 100f * 0.45f * m * (if (sombras > 0f) 1f - v else v) }
        if (realces != 0f) { val m = suave((v - 0.35f) * 1.6f); v += realces / 100f * 0.45f * m * (if (realces > 0f) 1f - v else v) }
        return v.coerceIn(0f, 1f)
    }

    fun aplicar(px: IntArray, w: Int, h: Int, p: Parametros, pessoa: FloatArray?): IntArray {
        if (p.neutro) return px
        var out = px
        for (m in p.mascaras) {
            if (m.neutra) continue
            if (m.tipo == Tipo.Pessoa && pessoa == null) continue
            val mat = Edicao.matriz(Edicao.Cor(contraste = m.contraste, saturacao = m.saturacao, temperatura = m.temperatura, exposicao = m.exposicao)).array
            val tab = if (m.sombras != 0f || m.realces != 0f) FloatArray(1025) { curvaL(it / 1024f, m.sombras, m.realces) } else null
            val o = IntArray(w * h)
            for (y in 0 until h) { val ny = y / (h - 1f)
                for (x in 0 until w) {
                    val k = y * w + x; val c = out[k]
                    val peso = peso(m, x / (w - 1f), ny, pessoa, w, h)
                    if (peso <= 0.002f) { o[k] = c; continue }
                    var r = (c shr 16 and 255).toFloat(); var g = (c shr 8 and 255).toFloat(); var b = (c and 255).toFloat()
                    // matriz 4x5 (linha-maior, deslocamentos em 0..255)
                    val nr = mat[0] * r + mat[1] * g + mat[2] * b + mat[4]; val ng = mat[5] * r + mat[6] * g + mat[7] * b + mat[9]; val nb = mat[10] * r + mat[11] * g + mat[12] * b + mat[14]
                    r = nr.coerceIn(0f, 255f); g = ng.coerceIn(0f, 255f); b = nb.coerceIn(0f, 255f)
                    if (tab != null) { val l = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f; if (l > 0.0005f) { val gan = tab[(l * 1024f).toInt().coerceIn(0, 1024)] / l; r = (r * gan).coerceIn(0f, 255f); g = (g * gan).coerceIn(0f, 255f); b = (b * gan).coerceIn(0f, 255f) } }
                    val r0 = c shr 16 and 255; val g0 = c shr 8 and 255; val b0 = c and 255
                    val rr = (r0 + (r - r0) * peso).toInt().coerceIn(0, 255); val gg = (g0 + (g - g0) * peso).toInt().coerceIn(0, 255); val bb = (b0 + (b - b0) * peso).toInt().coerceIn(0, 255)
                    o[k] = (c and (0xFF shl 24)) or (rr shl 16) or (gg shl 8) or bb
                }
            }
            out = o
        }
        return out
    }

    fun aplicar(b: Bitmap, p: Parametros, pessoa: FloatArray?): Bitmap {
        if (p.neutro) return b
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        return Bitmap.createBitmap(aplicar(px, w, h, p, pessoa), w, h, Bitmap.Config.ARGB_8888)
    }
}
