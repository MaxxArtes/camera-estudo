package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pincel de cura para manchas PEQUENAS (Astra/agy, 20/09): preenchimento por vizinhança, "descascando" a região da borda
 * para dentro (média dos vizinhos já conhecidos). Determinístico, sem IA, escala com a resolução (coordenadas e raio
 * normalizados pela largura). Não remove objetos: em textura repetida ou borda de objeto, borra — a ajuda na tela avisa.
 */
object Cura {
    data class Pincelada(val pontos: List<Pair<Float, Float>>, val raio: Float)   // normalizados; raio em fração da largura
    data class Parametros(val pinceladas: List<Pincelada> = emptyList()) { val neutro: Boolean get() = pinceladas.isEmpty() }

    fun aplicar(b: Bitmap, p: Parametros): Bitmap {
        if (p.neutro) return b
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        for (pin in p.pinceladas) cura(px, w, h, pin)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Aplica UMA pincelada no lugar (para a prévia incremental: só a caixa da pincelada é recalculada). */
    fun cura(px: IntArray, w: Int, h: Int, pin: Pincelada) {
        val r = max(1, (pin.raio * w).roundToInt())
        // caixa da pincelada com margem
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for ((nx, ny) in pin.pontos) { val cx = (nx * (w - 1)).roundToInt(); val cy = (ny * (h - 1)).roundToInt()
            x0 = min(x0, cx - r - 1); y0 = min(y0, cy - r - 1); x1 = max(x1, cx + r + 1); y1 = max(y1, cy + r + 1) }
        x0 = max(0, x0); y0 = max(0, y0); x1 = min(w - 1, x1); y1 = min(h - 1, y1)
        if (x1 < x0 || y1 < y0) return
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val buraco = BooleanArray(bw * bh)
        for ((nx, ny) in pin.pontos) { val cx = (nx * (w - 1)).roundToInt(); val cy = (ny * (h - 1)).roundToInt()
            for (yy in max(y0, cy - r)..min(y1, cy + r)) for (xx in max(x0, cx - r)..min(x1, cx + r)) {
                val dx = xx - cx; val dy = yy - cy; if (dx * dx + dy * dy <= r * r) buraco[(yy - y0) * bw + (xx - x0)] = true } }
        var restantes = buraco.count { it }
        if (restantes == 0) return
        // descasca: a cada volta, pixels do buraco com vizinho conhecido recebem a média (8 vizinhos conhecidos)
        val pronto = BooleanArray(bw * bh) { !buraco[it] }
        val novo = IntArray(bw * bh); val marca = BooleanArray(bw * bh)
        var voltas = 0
        while (restantes > 0 && voltas < 4 * r + 8) {
            voltas++; var mudou = 0
            for (yy in 0 until bh) for (xx in 0 until bw) {
                val k = yy * bw + xx; if (pronto[k]) continue
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (dy in -1..1) for (dx in -1..1) { if (dx == 0 && dy == 0) continue
                    val vx = xx + dx; val vy = yy + dy; if (vx < 0 || vy < 0 || vx >= bw || vy >= bh) continue
                    val vk = vy * bw + vx; if (!pronto[vk]) continue
                    val c = px[(y0 + vy) * w + (x0 + vx)]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; n++ }
                if (n >= 2) { novo[k] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n); marca[k] = true; mudou++ }
            }
            if (mudou == 0) break
            for (k in 0 until bw * bh) if (marca[k]) { px[(y0 + k / bw) * w + (x0 + k % bw)] = novo[k]; pronto[k] = true; marca[k] = false; restantes-- }
        }
        // alisa o remendo (média 3x3 só dentro do buraco) para não sobrar risca da descascada
        val copia = IntArray(bw * bh) { k -> px[(y0 + k / bw) * w + (x0 + k % bw)] }
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val k = yy * bw + xx; if (!buraco[k]) continue
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) { val vx = xx + dx; val vy = yy + dy; if (vx < 0 || vy < 0 || vx >= bw || vy >= bh) continue
                val c = copia[vy * bw + vx]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; n++ }
            px[(y0 + yy) * w + (x0 + xx)] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
    }
}
