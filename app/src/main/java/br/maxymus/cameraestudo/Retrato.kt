package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Retrato por software, na direção do retrato da GCam (revisão do Codex gpt-6-astra em 17/09, medicao/codex_retrato_resposta.md,
 * sobre Wadhwa et al. 2018 "Synthetic Depth-of-Field" e Portrait Light):
 *
 *  1. Máscara do ML Kit em resolução bruta (é a do modelo, ~256 px, não a da foto): ampliada BILINEAR para a saída, sem a
 *     média 5x5 que antes valia ~47 px na foto; curva suave só nas pontas (0,10..0,90) para não perder fio de cabelo.
 *  2. Fundo por CONVOLUÇÃO NORMALIZADA com kernel de DISCO em luz linear, numa cópia de 600 px: a pessoa entra com peso
 *     zero, então cabelo/roupa não vazam para o borrão; onde o peso some, cai para um disco 3x maior. Raio 8 a 40 px na
 *     saída conforme a régua de desfoque.
 *  3. Rosto: preenchimento suave das sombras (Portrait Light aproximado): ganho 2^(EV·máscara·sombra) na luminância de
 *     baixa frequência, EV 0,35, só dentro de uma elipse do rosto e só na pessoa.
 *  Ficou de fora, por decisão do Codex: profundidade por gradiente, luzes em "bola", descontaminação de cabelo, HDR próprio.
 */
object Retrato {
    private const val LADO_MAX = 2400
    @Volatile var ultimoDiag: String? = null   // fração da máscara em meia certeza e nº de manchas descartadas (telemetria)
    private const val LADO_FUNDO = 600
    private val paraLinear = FloatArray(256) { val c = it / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
    private val paraSrgb = IntArray(4097) { val l = it / 4096f; val c = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f; (c * 255f + 0.5f).toInt().coerceIn(0, 255) }
    private fun srgb(l: Float) = paraSrgb[(l.coerceIn(0f, 1f) * 4096f).toInt()]
    private fun suave(v: Float, a: Float, b: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Devolve null quando deu certo; senão o motivo (vai para a telemetria). intensidade 1..10. */
    suspend fun aplicar(contexto: Context, uri: Uri, intensidade: Int = 5): String? = withContext(Dispatchers.Default) {
        runCatching {
            var certa: Bitmap? = null
            for (tentativa in 0 until 4) { certa = Documento.decodeReduzido(contexto, uri, LADO_MAX * 2); if (certa != null) break; delay(250) }
            if (certa == null) {
                val diag = runCatching {
                    val fd = contexto.contentResolver.openFileDescriptor(uri, "r"); val bytes = fd?.statSize ?: -1L; fd?.close()
                    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
                    "bytes=$bytes w=${b.outWidth}"
                }.getOrElse { "diag: " + it::class.java.simpleName }
                return@runCatching "decode nulo ($diag)"
            }
            val escala = min(1f, LADO_MAX.toFloat() / max(certa.width, certa.height))
            val base = if (escala < 1f) Bitmap.createScaledBitmap(certa, (certa.width * escala).toInt(), (certa.height * escala).toInt(), true) else certa
            if (base !== certa) certa.recycle()
            val w = base.width; val h = base.height; val n = w * h

            // 1) máscara bruta do modelo, ampliada bilinear na hora de usar
            // 1) máscara: segmentador multiclasse (com cabelo, Apache) → ML Kit se falhar → localizador de assunto se não houver pessoa
            var fonteMascara = "multiclasse"
            val mapa = Segmentos.segmentar(contexto, base)
            var mw: Int; var mh: Int; var bruta: FloatArray
            if (mapa != null) { mw = 256; mh = 256; bruta = FloatArray(mw * mh) { k -> 1f - mapa.cats[k * 6 + Segmentos.FUNDO] } }
            else {
                fonteMascara = "mlkit"
                val segmentador = Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build())
                val mascara = Tasks.await(segmentador.process(InputImage.fromBitmap(base, 0))); segmentador.close()
                mw = mascara.width; mh = mascara.height; val bb = mascara.buffer; bb.rewind(); bruta = FloatArray(mw * mh) { bb.float }
            }
            var cobertura = 0; for (v in bruta) if (v > 0.5f) cobertura++
            if (cobertura < bruta.size / 100) {
                // sem pessoa: máscara de instância do YOLO (pet, objeto); se falhar, elipse suave na caixa do localizador
                val det = Yolo.detectar(contexto, base).firstOrNull()
                if (det != null) { fonteMascara = "yolo:" + det.nome
                    for (k in bruta.indices) bruta[k] = det.mascara((k % mw) / (mw - 1f), (k / mw) / (mh - 1f)) }
                val cx0 = if (det == null) Assunto.principal(contexto, base) else null
                if (cx0 != null) { fonteMascara = "assunto"
                    val r = cx0.ret; val ccx = r.centerX(); val ccy = r.centerY(); val rx = r.width() / 2 * 1.05f; val ry = r.height() / 2 * 1.05f
                    for (k in bruta.indices) { val x = (k % mw) / (mw - 1f); val y = (k / mw) / (mh - 1f); val d = ((x - ccx) / rx).let { it * it } + ((y - ccy) / ry).let { it * it }
                        bruta[k] = 1f - suave(d, 0.7f, 1.05f) } }
            }
            // limpeza (dono, 17/09: partes do fundo ficavam sem desfoque): só o maior bloco conectado de confiança > 0,5 é pessoa;
            // manchas soltas (cadeira, tela, parede) viram fundo. Conta a fração em meia certeza para a telemetria.
            val manchas = limpaMascara(bruta, mw, mh)
            var meio = 0; for (v in bruta) if (v > 0.15f && v < 0.85f) meio++
            ultimoDiag = "meio=${meio * 100 / bruta.size}% manchas=$manchas fonte=$fonteMascara seg_ms=${Segmentos.ultimoMs}"
            fun conf(x: Float, y: Float): Float {   // bilinear em coordenadas 0..1
                val fx = (x * (mw - 1)).coerceIn(0f, mw - 1f); val fy = (y * (mh - 1)).coerceIn(0f, mh - 1f)
                val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(mw - 1, x0 + 1); val y1 = min(mh - 1, y0 + 1); val tx = fx - x0; val ty = fy - y0
                return (bruta[y0 * mw + x0] * (1 - tx) + bruta[y0 * mw + x1] * tx) * (1 - ty) + (bruta[y1 * mw + x0] * (1 - tx) + bruta[y1 * mw + x1] * tx) * ty
            }
            val pFrente = IntArray(n).also { base.getPixels(it, 0, w, 0, 0, w, h) }

            // 3) rosto: preenchimento suave das sombras, só na pessoa, dentro de uma elipse
            val rostos = Rostos.detectar(base, 1000)
            for (r in rostos) {
                val cx = r.caixa.exactCenterX(); val cy = r.caixa.exactCenterY(); val rx = r.caixa.width() * 0.62f; val ry = r.caixa.height() * 0.78f
                val x0 = max(0, (cx - rx).toInt()); val x1 = min(w, (cx + rx).toInt() + 1); val y0 = max(0, (cy - ry).toInt()); val y1 = min(h, (cy + ry).toInt() + 1)
                if (x1 <= x0 || y1 <= y0) continue
                // luminância linear de baixa frequência na região (caixa separável, raio 10% da largura do rosto)
                val rw = x1 - x0; val rh = y1 - y0; val raio = max(2, (r.caixa.width() * 0.10f).toInt())
                val lum = FloatArray(rw * rh) { k -> val c = pFrente[(y0 + k / rw) * w + x0 + k % rw]; 0.2126f * paraLinear[c shr 16 and 255] + 0.7152f * paraLinear[c shr 8 and 255] + 0.0722f * paraLinear[c and 255] }
                val baixa = caixaF(lum, rw, rh, raio)
                for (yy in 0 until rh) for (xx in 0 until rw) {
                    val k = yy * rw + xx; val X = x0 + xx; val Y = y0 + yy
                    val dx = (X - cx) / rx; val dy = (Y - cy) / ry; val d = dx * dx + dy * dy
                    if (d >= 1f) continue
                    val elipse = 1f - suave(d, 0.55f, 1f)
                    val pessoa = conf(X / (w - 1f), Y / (h - 1f))
                    val sombra = 1f - suave(baixa[k], 0.15f, 0.55f)
                    val ganho = 2f.pow(0.35f * elipse * pessoa * sombra)
                    if (ganho <= 1.002f) continue
                    val c = pFrente[Y * w + X]
                    val lr = paraLinear[c shr 16 and 255] * ganho; val lg = paraLinear[c shr 8 and 255] * ganho; val lb = paraLinear[c and 255] * ganho
                    pFrente[Y * w + X] = (0xFF shl 24) or (srgb(lr) shl 16) or (srgb(lg) shl 8) or srgb(lb)
                }
            }

            // 2) fundo: disco normalizado em luz linear numa cópia de 600 px, pessoa com peso zero
            val escF = min(1f, LADO_FUNDO.toFloat() / max(w, h)); val fw = max(1, (w * escF).toInt()); val fh = max(1, (h * escF).toInt())
            val lr = FloatArray(fw * fh); val lg = FloatArray(fw * fh); val lb = FloatArray(fw * fh); val peso = FloatArray(fw * fh)
            for (y in 0 until fh) for (x in 0 until fw) {
                val sx = min(w - 1, (x / escF).toInt()); val sy = min(h - 1, (y / escF).toInt()); val c = pFrente[sy * w + sx]
                val pf = 1f - suave(conf(x / (fw - 1f), y / (fh - 1f)), 0.05f, 0.30f)
                val k = y * fw + x; peso[k] = pf; lr[k] = paraLinear[c shr 16 and 255] * pf; lg[k] = paraLinear[c shr 8 and 255] * pf; lb[k] = paraLinear[c and 255] * pf
            }
            val raio = (2f + intensidade.coerceIn(1, 10) * 0.8f).toInt().coerceIn(2, 10)   // 8..40 px na saída de 2400
            val dR = disco(lr, fw, fh, raio); val dG = disco(lg, fw, fh, raio); val dB = disco(lb, fw, fh, raio); val dP = disco(peso, fw, fh, raio)
            val rGrande = min(3 * raio, 30)
            val gR = disco(lr, fw, fh, rGrande); val gG = disco(lg, fw, fh, rGrande); val gB = disco(lb, fw, fh, rGrande); val gP = disco(peso, fw, fh, rGrande)
            val fundoR = FloatArray(fw * fh); val fundoG = FloatArray(fw * fh); val fundoB = FloatArray(fw * fh)
            for (k in 0 until fw * fh) {
                if (dP[k] > 0.02f) { fundoR[k] = dR[k] / dP[k]; fundoG[k] = dG[k] / dP[k]; fundoB[k] = dB[k] / dP[k] }
                else if (gP[k] > 0.005f) { fundoR[k] = gR[k] / gP[k]; fundoG[k] = gG[k] / gP[k]; fundoB[k] = gB[k] / gP[k] }
                else { fundoR[k] = lr[k]; fundoG[k] = lg[k]; fundoB[k] = lb[k] }
            }
            fun fundo(arr: FloatArray, x: Float, y: Float): Float {   // bilinear na cópia pequena
                val fx = (x * (fw - 1)).coerceIn(0f, fw - 1f); val fy = (y * (fh - 1)).coerceIn(0f, fh - 1f)
                val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(fw - 1, x0 + 1); val y1 = min(fh - 1, y0 + 1); val tx = fx - x0; val ty = fy - y0
                return (arr[y0 * fw + x0] * (1 - tx) + arr[y0 * fw + x1] * tx) * (1 - ty) + (arr[y1 * fw + x0] * (1 - tx) + arr[y1 * fw + x1] * tx) * ty
            }

            // composição: alfa = confiança com curva suave só nas pontas
            val saida = IntArray(n)
            for (y in 0 until h) { val ny = y / (h - 1f)
                for (x in 0 until w) {
                    val i = y * w + x; val nx = x / (w - 1f)
                    val a = suave(conf(nx, ny), 0.20f, 0.80f)   // curva mais apertada: menos fundo "meio nítido" na faixa incerta
                    val f = pFrente[i]
                    if (a >= 0.995f) { saida[i] = f; continue }
                    val br = srgb(fundo(fundoR, nx, ny)); val bg = srgb(fundo(fundoG, nx, ny)); val bl = srgb(fundo(fundoB, nx, ny))
                    val r = ((f shr 16 and 255) * a + br * (1 - a)).toInt(); val g = ((f shr 8 and 255) * a + bg * (1 - a)).toInt(); val b = ((f and 255) * a + bl * (1 - a)).toInt()
                    saida[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            base.recycle()
            val resultado = Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
            contexto.contentResolver.openOutputStream(uri, "wt")?.use { resultado.compress(Bitmap.CompressFormat.JPEG, 93, it) } ?: return@runCatching "não consegui gravar"
            resultado.recycle()
            Fotos.gravaExif(contexto, uri, "Camera Estudo " + (runCatching { contexto.packageManager.getPackageInfo(contexto.packageName, 0).versionName }.getOrNull() ?: "") + " (retrato software, desfoque $intensidade, rostos ${rostos.size})")
            null
        }.getOrElse { e -> (e::class.java.simpleName + ": " + (e.message ?: "")).take(300) }
    }

    /** Zera os blocos de confiança > 0,5 que não são o maior (pessoa principal). Devolve quantas manchas descartou. */
    private fun limpaMascara(m: FloatArray, w: Int, h: Int): Int {
        val rotulo = IntArray(w * h); var k = 0; val tamanhos = ArrayList<Int>()
        val fila = IntArray(w * h)
        for (i in 0 until w * h) {
            if (m[i] <= 0.5f || rotulo[i] != 0) continue
            k++; var ini = 0; var fim = 0; fila[fim++] = i; rotulo[i] = k; var n = 0
            while (ini < fim) { val p = fila[ini++]; n++; val x = p % w; val y = p / w
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) { val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) { val q = yy * w + xx; if (m[q] > 0.5f && rotulo[q] == 0) { rotulo[q] = k; fila[fim++] = q } } } }
            tamanhos += n
        }
        if (tamanhos.size <= 1) return 0
        val maior = tamanhos.indices.maxByOrNull { tamanhos[it] }!! + 1
        for (i in 0 until w * h) if (rotulo[i] != 0 && rotulo[i] != maior) m[i] = 0f
        return tamanhos.size - 1
    }

    /** Média em disco de raio r por prefixos de linha (custo ∝ diâmetro, não área). Borda: só o que cabe. */
    private fun disco(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val pref = FloatArray((w + 1) * h)
        for (y in 0 until h) { var s = 0f; val l = y * (w + 1); val la = y * w; pref[l] = 0f; for (x in 0 until w) { s += a[la + x]; pref[l + x + 1] = s } }
        val dxs = IntArray(2 * r + 1) { val dy = it - r; sqrt((r * r - dy * dy).toFloat()).toInt() }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var cnt = 0
            for (dy in -r..r) { val yy = y + dy; if (yy < 0 || yy >= h) continue
                val dx = dxs[dy + r]; val x0 = max(0, x - dx); val x1 = min(w - 1, x + dx)
                s += pref[yy * (w + 1) + x1 + 1] - pref[yy * (w + 1) + x0]; cnt += x1 - x0 + 1 }
            out[y * w + x] = if (cnt > 0) s / cnt else 0f
        }
        return out
    }
    private fun caixaF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(a.size); val o = FloatArray(a.size)
        for (y in 0 until h) { val l = y * w; var s = 0f; var c = 0; for (x in 0 until min(w, r)) { s += a[l + x]; c++ }
            for (x in 0 until w) { if (x + r < w) { s += a[l + x + r]; c++ }; if (x - r - 1 >= 0) { s -= a[l + x - r - 1]; c-- }; t[l + x] = s / c } }
        for (x in 0 until w) { var s = 0f; var c = 0; for (y in 0 until min(h, r)) { s += t[y * w + x]; c++ }
            for (y in 0 until h) { if (y + r < h) { s += t[(y + r) * w + x]; c++ }; if (y - r - 1 >= 0) { s -= t[(y - r - 1) * w + x]; c-- }; o[y * w + x] = s / c } }
        return o
    }
}
