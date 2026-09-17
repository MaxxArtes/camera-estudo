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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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
 *  3. O usuário confere os cantos (EditorQuad) — detectar() devolve a prévia e o quadrilátero, aplicar() grava.
 *  4. Endireita a perspectiva com Matrix.setPolyToPoly (Android já sabe fazer).
 *  5. Realce final: fundo branco, contraste esticado por percentis.
 */
object Documento {
    private const val LADO_ANALISE = 640
    private const val LADO_SAIDA = 2000

    data class Resultado(val recortou: Boolean, val metodo: String, val largura: Int = 0, val altura: Int = 0, val fonteLado: Int = 0)

    private class Quad(val tl: FloatArray, val tr: FloatArray, val br: FloatArray, val bl: FloatArray, val areaMancha: Int, val metodo: String) {
        var cantosNaBorda = 0
        fun marcaBordas(w: Int, h: Int): Quad { val m = 0.03f * max(w, h); cantosNaBorda = listOf(tl, tr, br, bl).count { it[0] < m || it[1] < m || it[0] > w - 1 - m || it[1] > h - 1 - m }; return this }
        fun areaQuad(): Float {   // fórmula do cadarço
            val xs = floatArrayOf(tl[0], tr[0], br[0], bl[0]); val ys = floatArrayOf(tl[1], tr[1], br[1], bl[1])
            var s = 0f; for (i in 0 until 4) { val j = (i + 1) % 4; s += xs[i] * ys[j] - xs[j] * ys[i] }
            return abs(s) / 2f
        }
        fun placar(areaImagem: Int): Float {
            val aq = areaQuad(); if (aq <= 0f) return 0f
            val fracao = aq / areaImagem
            if (fracao < 0.15f || fracao > 0.97f) return 0f
            // quadro colado em 3+ bordas da imagem é a imagem inteira, não um documento (conta em mesa escura, 16/09)
            if (cantosNaBorda > 0 && cantosNaBorda >= 3) return 0f
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

    /** Tela sem borrão: só tira 40% da cor (o moiré colorido), luminância intacta. */
    private fun dessaturaLeve(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        for (i in px.indices) { val c = px[i]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255; val y = (r * 30 + g * 59 + bl * 11) / 100
            px[i] = (0xFF shl 24) or ((y + (r - y) * 6 / 10).coerceIn(0, 255) shl 16) or ((y + (g - y) * 6 / 10).coerceIn(0, 255) shl 8) or (y + (bl - y) * 6 / 10).coerceIn(0, 255) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
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

    /** Cantos normalizados (0..1 da foto já girada pelo EXIF), ordem tl, tr, br, bl; null = nada achado. */
    class Deteccao(val quad: FloatArray?, val metodo: String, val previa: Bitmap, val brilho: Int, val contraste: Int, val fracClara: Int)

    /**
     * Passo 1: acha o quadrilátero e devolve uma prévia (até 1200 px) para o usuário conferir os cantos.
     * Não grava nada; quem grava é aplicar().
     */
    suspend fun detectar(contexto: Context, uri: Uri, tela: Boolean): Deteccao? = withContext(Dispatchers.Default) {
        runCatching {
            val previa = decodeReduzido(contexto, uri, 1200) ?: return@runCatching null
            val esc = LADO_ANALISE.toFloat() / max(previa.width, previa.height)
            val pw = max(1, (previa.width * esc).toInt()); val ph = max(1, (previa.height * esc).toInt())
            val pequena = Bitmap.createScaledBitmap(previa, pw, ph, true)
            val (melhor, estat) = if (tela) analisaTela(pequena) else analisaFolha(pequena)
            pequena.recycle()
            val quad = melhor?.let { q -> floatArrayOf(q.tl[0] / pw, q.tl[1] / ph, q.tr[0] / pw, q.tr[1] / ph, q.br[0] / pw, q.br[1] / ph, q.bl[0] / pw, q.bl[1] / ph) }
            Deteccao(quad, melhor?.metodo ?: "nenhum", previa, estat[0], estat[1], estat[2])
        }.getOrNull()
    }

    /**
     * Detecção AO VIVO (fluxo de análise do CameraX, quadro em cinza já reduzido): mesmo detector, sem gravar nada.
     * Devolve o quadrilátero normalizado (0..1) no referencial do quadro recebido, ou null.
     */
    fun detectarVivo(cinza: IntArray, w: Int, h: Int, tela: Boolean): FloatArray? = runCatching {
        val px = IntArray(w * h) { val v = cinza[it].coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val (melhor, _) = if (tela) analisaTela(b) else analisaFolha(b)
        b.recycle()
        melhor?.let { q -> floatArrayOf(q.tl[0] / w, q.tl[1] / h, q.tr[0] / w, q.tr[1] / h, q.br[0] / w, q.br[1] / h, q.bl[0] / w, q.bl[1] / h) }
    }.getOrNull()

    /** Folha: retinex por divisão pelo fundo, Otsu e três candidatos (mancha clara, mancha escura, região lisa por bordas). */
    private fun analisaFolha(pequena: Bitmap): Pair<Quad?, IntArray> {
        val pw = pequena.width; val ph = pequena.height
        val px = IntArray(pw * ph).also { pequena.getPixels(it, 0, pw, 0, 0, pw, ph) }
        val cinza = IntArray(pw * ph) { luma(px[it]) }
        val fundo = fundoBorrado(pequena, 40)
        val norm = IntArray(pw * ph) { (cinza[it] * 200 / max(1, luma(fundo[it]))).coerceIn(0, 255) }   // 200 ≈ "branco" após dividir
        val candidatos = mutableListOf<Quad>()
        val lim = otsu(norm)
        maiorMancha(BooleanArray(pw * ph) { norm[it] > lim }, pw, ph, "claro")?.let { candidatos += it }
        maiorMancha(BooleanArray(pw * ph) { norm[it] <= lim }, pw, ph, "escuro")?.let { candidatos += it }
        // sem normalizar: em mesa escura a normalização iguala papel e mesa (medido 16/09: normalizado = imagem inteira,
        // cru = a conta com 91% de preenchimento)
        val limCru = otsu(cinza)
        maiorMancha(BooleanArray(pw * ph) { cinza[it] > limCru }, pw, ph, "claro_cru")?.let { candidatos += it }
        maiorMancha(BooleanArray(pw * ph) { cinza[it] <= limCru }, pw, ph, "escuro_cru")?.let { candidatos += it }
        maiorMancha(regiaoLisa(cinza, pw, ph), pw, ph, "bordas")?.let { candidatos += it }
        linhasHough(cinza, pw, ph)?.let { candidatos += it }
        candidatos.forEach { it.marcaBordas(pw, ph) }
        val melhor = candidatos.maxByOrNull { it.placar(pw * ph) }?.takeIf { it.placar(pw * ph) > 0f }
        return melhor to estatisticas(cinza, lim)
    }

    /**
     * Tela (monitor, notebook, outro celular): a tela é o retângulo mais claro e uniforme; sem retinex (ela emite luz).
     * Suaviza antes do limiar (o moiré quebra a mancha em tiras) e fecha a máscara (6 px) para o texto não furar.
     */
    private fun analisaTela(pequena: Bitmap): Pair<Quad?, IntArray> {
        val pw = pequena.width; val ph = pequena.height
        val terco = Bitmap.createScaledBitmap(pequena, max(1, pw / 3), max(1, ph / 3), true)
        val suave = Bitmap.createScaledBitmap(terco, pw, ph, true)
        val px = IntArray(pw * ph).also { suave.getPixels(it, 0, pw, 0, 0, pw, ph) }
        terco.recycle(); if (suave !== terco) suave.recycle()
        val cinza = IntArray(pw * ph) { luma(px[it]) }
        val lim = otsu(cinza)
        val mascara = fecha(BooleanArray(pw * ph) { cinza[it] > lim }, pw, ph, 6)
        val melhor = maiorMancha(mascara, pw, ph, "monitor")?.takeIf { it.placar(pw * ph) > 0f }
        return melhor to estatisticas(cinza, lim)
    }

    /** Brilho médio, contraste (p95 − p5) e fração clara em %: o registro do scanner agrupa as condições de luz por eles. */
    private fun estatisticas(cinza: IntArray, lim: Int): IntArray {
        val hist = IntArray(256); var soma = 0L; var claros = 0
        for (v in cinza) { hist[v]++; soma += v; if (v > lim) claros++ }
        val n = cinza.size; var acc = 0; var p5 = 0; var p95 = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * 0.05) { p5 = i; break } }
        acc = 0; for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * 0.05) { p95 = i; break } }
        return intArrayOf((soma / n).toInt(), p95 - p5, claros * 100 / n)
    }

    /**
     * Passo 2: recorta pelo quadrilátero (normalizado; null = sem recorte), realça e SOBRESCREVE o JPEG.
     * Lado de saída = MAIOR dos dois lados opostos (o menor é o que a perspectiva encurtou); razão perto de
     * papel (A4/Carta) ou de tela (16:9, 16:10, 4:3) é encaixada.
     */
    /** estilo: "aprimorado" (padrão: sombras fora, fundo branco), "original" (só recorte) ou "pb" (aprimorado em preto e branco). */
    suspend fun aplicar(contexto: Context, uri: Uri, quad: FloatArray?, tela: Boolean, metodo: String, rajada: List<ByteArray>? = null, rotRajada: Int = 0, estilo: String = "aprimorado", ladoSaida: Int = LADO_SAIDA): Resultado? = withContext(Dispatchers.Default) {
        runCatching {
            // rajada (agy): fundir DEPOIS do recorte — as folhas retificadas no mesmo retângulo já saem alinhadas
            // a origem é decodificada um pouco acima do alvo (letra miúda do DACTE ilegível a 1400/2000 px, 17/09)
            val ladoFonte = max(2400, ladoSaida * 5 / 4)
            val fontes: List<() -> Bitmap?> = if (rajada != null && rajada.size >= 2) rajada.map { bytes -> { Fusao.decodifica(bytes, min(ladoFonte, 2600))?.let { Fusao.gira(it, rotRajada) } } }
                else listOf({ decodeReduzido(contexto, uri, ladoFonte) })
            val foto = fontes[0]() ?: return@runCatching null
            val fonteLado = max(foto.width, foto.height)
            var saida: Bitmap = foto; var recortou = false
            if (quad != null && convexo(quad)) {
                val p = FloatArray(8) { quad[it] * (if (it % 2 == 0) foto.width else foto.height) }
                var larg = max(hypot(p[2] - p[0], p[3] - p[1]), hypot(p[4] - p[6], p[5] - p[7])).toInt().coerceIn(200, 6000)
                var alt = max(hypot(p[6] - p[0], p[7] - p[1]), hypot(p[4] - p[2], p[5] - p[3])).toInt().coerceIn(200, 6000)
                val razao = larg.toFloat() / alt
                val padroes = if (tela) floatArrayOf(16f / 9f, 9f / 16f, 16f / 10f, 10f / 16f, 4f / 3f, 3f / 4f) else floatArrayOf(1.414f, 1f / 1.414f, 1.294f, 1f / 1.294f)
                val tolerancia = if (tela) 0.1f else 0.12f
                for (r in padroes) if (abs(razao / r - 1f) < tolerancia) { if (r > 1f) larg = (alt * r).toInt() else alt = (larg / r).toInt(); break }
                val escS = min(1f, ladoSaida.toFloat() / max(larg, alt))   // Codex 17/09: teto de 2000 no Tela apagava a letra miúda
                val w = (larg * escS).toInt(); val h = (alt * escS).toInt()
                val m = Matrix()
                if (m.setPolyToPoly(p, 0, floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)) {
                    val pincel = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    val planos = ArrayList<Bitmap>()
                    for ((i, fonte) in fontes.withIndex()) {
                        val origem = if (i == 0) foto else fonte() ?: continue
                        val plano = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(plano).drawBitmap(origem, m, pincel)
                        origem.recycle(); planos += plano
                    }
                    saida = if (planos.size >= 2) Fusao.rajada(planos, reciclar = true) else planos[0]
                    recortou = true
                }
            } else if (fontes.size >= 2) {
                // sem recorte: funde os quadros inteiros (alinhamento por MTB + ladrilhos)
                val todos = arrayListOf(foto); for (i in 1 until fontes.size) fontes[i]()?.let { todos += it }
                if (todos.size >= 2) saida = Fusao.rajada(todos, reciclar = true)
            }
            // Codex (gpt-6-astra, 17/09) nas duas imagens do DACTE: o anti-moiré (reduz 70% + caixa 5x5) apagava traços de 1-2 px
            // e nitidez depois não recupera. Agora o borrão só entra no estilo "semmoire"; o padrão do Tela tira só a cor do moiré.
            val realcada = if (estilo == "original") saida else if (tela) {
                val base = if (estilo == "semmoire") { val s = suavizaMoire(saida); if (s !== saida) saida.recycle(); s } else { val s = dessaturaLeve(saida); if (s !== saida) saida.recycle(); s }
                val r = realcaTela(base); if (r !== base) base.recycle(); r
            } else {
                val r = realca(saida, texto = estilo == "texto"); if (r !== saida) saida.recycle(); r
            }
            // nitidez gaussiana pequena com limiar de ruído (Codex: sigma 0,7, quantidade 0,8, ignora resíduo < 3, teto ±20), não a caixa 3x3 das selfies
            val realcadaNitida = if (estilo == "original") realcada else Fusao.nitidezTexto(realcada)
            val pronta = if (estilo == "pb") {
                val w = realcadaNitida.width; val h = realcadaNitida.height
                val px = IntArray(w * h).also { realcadaNitida.getPixels(it, 0, w, 0, 0, w, h) }; realcadaNitida.recycle()
                for (i in px.indices) { val v = luma(px[i]); px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            } else realcadaNitida
            val pw2 = pronta.width; val ph2 = pronta.height
            contexto.contentResolver.openOutputStream(uri, "wt")?.use { pronta.compress(Bitmap.CompressFormat.JPEG, 94, it) } ?: return@runCatching null
            pronta.recycle()
            Resultado(recortou, if (recortou) metodo else "nenhum", pw2, ph2, fonteLado)
        }.getOrNull()
    }

    /**
     * Os 4 cantos (tl, tr, br, bl) formam um quadrilátero convexo no sentido horário da tela: todos os
     * produtos vetoriais positivos. Rejeita "gravata borboleta" e cantos trocados de lado (saída espelhada).
     */
    fun convexo(q: FloatArray): Boolean {
        for (i in 0 until 4) {
            val a = i * 2; val b = ((i + 1) % 4) * 2; val c = ((i + 2) % 4) * 2
            val cruz = (q[b] - q[a]) * (q[c + 1] - q[b + 1]) - (q[b + 1] - q[a + 1]) * (q[c] - q[b])
            if (cruz <= 1e-5f) return false
        }
        return true
    }

    /** Sauvola suave: T = m(1 + k(s/128 − 1)); u = clamp((L − T + d)/2d); S = 255·u²(3−2u); saída = (L + S)/2. Somas deslizantes separáveis em Int. */
    private fun sauvolaSuave(l: IntArray, w: Int, h: Int, r: Int): IntArray {
        val n = w * h
        val q = IntArray(n) { l[it] * l[it] }
        val soma = somaCaixa(l, w, h, r); val soma2 = somaCaixa(q, w, h, r); val cont = somaCaixa(IntArray(n) { 1 }, w, h, r)
        val k = 0.2f; val d = 16f
        return IntArray(n) { i ->
            val c = cont[i].toFloat(); val m = soma[i] / c; val v = soma2[i] / c - m * m; val s = if (v > 0f) sqrt(v) else 0f
            val t = m * (1f + k * (s / 128f - 1f))
            val u = ((l[i] - t + d) / (2 * d)).coerceIn(0f, 1f); val sv = 255f * u * u * (3f - 2f * u)
            ((l[i] + sv) / 2f).toInt().coerceIn(0, 255)
        }
    }
    /** Soma em janela (2r+1)² por passagens separáveis (janela 41 x 255² cabe em Int). */
    private fun somaCaixa(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val t = IntArray(a.size); val o = IntArray(a.size)
        for (y in 0 until h) { val l = y * w; var s = 0; for (x in 0 until min(w, r)) s += a[l + x]
            for (x in 0 until w) { if (x + r < w) s += a[l + x + r]; if (x - r - 1 >= 0) s -= a[l + x - r - 1]; t[l + x] = s } }
        for (x in 0 until w) { var s = 0; for (y in 0 until min(h, r)) s += t[y * w + x]
            for (y in 0 until h) { if (y + r < h) s += t[(y + r) * w + x]; if (y - r - 1 >= 0) s -= t[(y - r - 1) * w + x]; o[y * w + x] = s } }
        return o
    }

    private fun luma(c: Int) = ((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100

    /** "Fundo" da imagem = versão muito borrada (reduz a 1/fator e amplia com filtro). Barato e suficiente. */
    /**
     * Estimativa do fundo (papel) em luminância: média por blocos de `fator` px só dos pixels acima da média local
     * menos 8 (exclui texto), depois ampliação bilinear. Equivale a borrar (y·w)/(w) com w = "é claro".
     */
    private fun fundoClaro(y: IntArray, w: Int, h: Int, fator: Int): IntArray {
        val sw = max(1, w / fator); val sh = max(1, h / fator)
        val somaTudo = FloatArray(sw * sh); val cont = FloatArray(sw * sh)
        for (yy in 0 until h) { val by = min(sh - 1, yy / fator); for (xx in 0 until w) { val k = by * sw + min(sw - 1, xx / fator); somaTudo[k] += y[yy * w + xx]; cont[k]++ } }
        val mediaBloco = FloatArray(sw * sh) { if (cont[it] > 0) somaTudo[it] / cont[it] else 128f }
        val soma = FloatArray(sw * sh); val peso = FloatArray(sw * sh)
        for (yy in 0 until h) { val by = min(sh - 1, yy / fator); for (xx in 0 until w) { val k = by * sw + min(sw - 1, xx / fator); val v = y[yy * w + xx]
            if (v >= mediaBloco[k] - 8) { soma[k] += v; peso[k]++ } } }
        val peq = FloatArray(sw * sh) { if (peso[it] > 0) soma[it] / peso[it] else mediaBloco[it] }
        // suaviza a grade pequena (3x3) e amplia bilinear
        val suave = FloatArray(sw * sh) { k -> val bx = k % sw; val by = k / sw; var s = 0f; var c = 0
            for (dy in -1..1) for (dx in -1..1) { val x = bx + dx; val yv = by + dy; if (x in 0 until sw && yv in 0 until sh) { s += peq[yv * sw + x]; c++ } }; s / c }
        return IntArray(w * h) { k -> val x = k % w; val yy = k / w
            val fx = ((x + 0.5f) / fator - 0.5f).coerceIn(0f, sw - 1f); val fy = ((yy + 0.5f) / fator - 0.5f).coerceIn(0f, sh - 1f)
            val x0 = fx.toInt(); val y0i = fy.toInt(); val x1 = min(sw - 1, x0 + 1); val y1i = min(sh - 1, y0i + 1); val tx = fx - x0; val ty = fy - y0i
            val v = (suave[y0i * sw + x0] * (1 - tx) + suave[y0i * sw + x1] * tx) * (1 - ty) + (suave[y1i * sw + x0] * (1 - tx) + suave[y1i * sw + x1] * tx) * ty
            v.roundToInt().coerceIn(1, 255) }
    }

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

    /**
     * Candidato por LINHAS (Hough): folha branca em piso claro não vira mancha (Otsu junta folha e chão; a região
     * lisa é quebrada pelo texto), mas as quatro bordas da folha são retas fortes. Medido 16/09 na foto do dono
     * (TRE, folha branca em piso cinza-claro): claro/lisa/textura deram fração 0,95-1,0 (imagem inteira); as linhas
     * acharam a folha (fração 0,65, apoio 0,81). Pixels de borda votam só nos ângulos próximos da normal do próprio
     * gradiente (±20°); picos quase verticais e quase horizontais; entre os pares, vence apoio (fração do perímetro
     * com borda forte a ±2 px) + fração da imagem — a linha de texto tem apoio parcial, a borda da folha é contínua.
     */
    private fun linhasHough(cinza: IntArray, w: Int, h: Int): Quad? {
        val n = w * h; val gx = IntArray(n); val gy = IntArray(n); val mag = IntArray(n)
        for (y in 1 until h - 1) for (x in 1 until w - 1) { val k = y * w + x
            gx[k] = cinza[k + 1] - cinza[k - 1]; gy[k] = cinza[k + w] - cinza[k - w]; mag[k] = sqrt((gx[k] * gx[k] + gy[k] * gy[k]).toFloat()).toInt() }
        val limiar = 51                                  // 0,10 x 255 x 2 (gradiente central soma dois vizinhos)
        val diag = hypot(w.toFloat(), h.toFloat()).toInt(); val largRho = 2 * diag + 1
        val cosT = FloatArray(180) { cos(Math.toRadians(it - 90.0)).toFloat() }; val sinT = FloatArray(180) { sin(Math.toRadians(it - 90.0)).toFloat() }
        val acc = IntArray(180 * largRho)
        for (y in 1 until h - 1) for (x in 1 until w - 1) { val k = y * w + x
            if (mag[k] < limiar) continue
            val ang = Math.toDegrees(atan2(gy[k].toFloat(), gx[k].toFloat()).toDouble()).roundToInt()   // normal da borda = direção do gradiente
            for (dt in -20..20) { var t = ang + dt; while (t < -90) t += 180; while (t >= 90) t -= 180
                val ti = t + 90; val rho = (x * cosT[ti] + y * sinT[ti]).roundToInt() + diag
                if (rho in 0 until largRho) acc[ti * largRho + rho]++ } }
        // picos por faixa de ângulo, com supressão de vizinhança
        fun picos(faixas: List<IntRange>, nMax: Int): List<FloatArray> {
            val a = acc.copyOf(); val saida = ArrayList<FloatArray>()
            repeat(nMax) {
                var melhor = 0; var mi = -1; var mr = -1
                for (f in faixas) for (t in f) { val ti = t + 90; val base = ti * largRho
                    for (r in 0 until largRho) { val v = a[base + r]; if (v > melhor) { melhor = v; mi = ti; mr = r } } }
                if (melhor < 30) return saida
                saida += floatArrayOf((mi - 90).toFloat(), (mr - diag).toFloat(), melhor.toFloat())
                for (ti in max(0, mi - 8) until min(180, mi + 8)) for (r in max(0, mr - 12) until min(largRho, mr + 12)) a[ti * largRho + r] = 0
            }
            return saida
        }
        val vert = picos(listOf(-25..25), 8); val horiz = picos(listOf(65..89, -90..-65), 8)
        if (vert.size < 2 || horiz.size < 2) return null
        fun xEm(l: FloatArray, y: Float): Float { val t = Math.toRadians(l[0].toDouble()); return ((l[1] - y * sin(t)) / cos(t)).toFloat() }
        fun yEm(l: FloatArray, x: Float): Float { val t = Math.toRadians(l[0].toDouble()); val s = sin(t); return ((l[1] - x * cos(t)) / (if (abs(s) > 1e-6) s else 1e-6)).toFloat() }
        fun inter(l1: FloatArray, l2: FloatArray): FloatArray? {
            val t1 = Math.toRadians(l1[0].toDouble()); val t2 = Math.toRadians(l2[0].toDouble())
            val a11 = cos(t1); val a12 = sin(t1); val a21 = cos(t2); val a22 = sin(t2); val det = a11 * a22 - a12 * a21
            if (abs(det) < 1e-6) return null
            return floatArrayOf(((l1[1] * a22 - a12 * l2[1]) / det).toFloat(), ((a11 * l2[1] - l1[1] * a21) / det).toFloat())
        }
        var melhorPlacar = 0f; var melhorQuad: List<FloatArray>? = null; var melhorApoio = 0f
        for (i in vert.indices) for (j in i + 1 until vert.size) {
            val (l, r) = if (xEm(vert[i], h / 2f) < xEm(vert[j], h / 2f)) vert[i] to vert[j] else vert[j] to vert[i]
            if (xEm(r, h / 2f) - xEm(l, h / 2f) < 0.3f * w) continue
            for (a in horiz.indices) for (b in a + 1 until horiz.size) {
                val (tp, bt) = if (yEm(horiz[a], w / 2f) < yEm(horiz[b], w / 2f)) horiz[a] to horiz[b] else horiz[b] to horiz[a]
                if (yEm(bt, w / 2f) - yEm(tp, w / 2f) < 0.3f * h) continue
                val q = listOf(inter(tp, l), inter(tp, r), inter(bt, r), inter(bt, l))
                if (q.any { it == null || it[0] < -5f || it[0] > w + 5f || it[1] < -5f || it[1] > h + 5f }) continue
                val pts = q.map { floatArrayOf(it!![0].coerceIn(0f, w - 1f), it[1].coerceIn(0f, h - 1f)) }
                val quad = Quad(pts[0], pts[1], pts[2], pts[3], 0, "linhas"); val fr = quad.areaQuad() / n
                if (fr < 0.15f || fr > 0.97f) continue
                // apoio: perímetro com borda forte a ±2 px
                var ok = 0; var tot = 0
                for (k in 0 until 4) { val p0 = pts[k]; val p1 = pts[(k + 1) % 4]; val passos = max(2, (max(abs(p1[0] - p0[0]), abs(p1[1] - p0[1])) / 2).toInt())
                    for (s in 0..passos) { val x = (p0[0] + (p1[0] - p0[0]) * s / passos).roundToInt(); val y = (p0[1] + (p1[1] - p0[1]) * s / passos).roundToInt()
                        if (x !in 0 until w || y !in 0 until h) continue
                        tot++; var forte = false
                        for (dy in -2..2) for (dx in -2..2) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h && mag[yy * w + xx] > 30) forte = true }
                        if (forte) ok++ } }
                val apoio = if (tot > 0) ok.toFloat() / tot else 0f
                val placar = apoio + fr
                if (placar > melhorPlacar) { melhorPlacar = placar; melhorQuad = pts; melhorApoio = apoio }
            }
        }
        val q = melhorQuad ?: return null
        if (melhorApoio < 0.6f) return null
        val quad = Quad(q[0], q[1], q[2], q[3], 0, "linhas")
        // preenchimento do placar geral = apoio (borda da folha contínua)
        return Quad(q[0], q[1], q[2], q[3], (melhorApoio * quad.areaQuad()).toInt(), "linhas")
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
        // Com inJustDecodeBounds o decodeStream devolve null POR DEFINIÇÃO: um "?: return null" aqui fazia a função
        // falhar sempre (15/09 a 16/09: retrato por software e scanner mudos; achado pela telemetria, "decode nulo").
        val medida = contexto.contentResolver.openInputStream(uri) ?: return null
        medida.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var amostra = 1; while (max(bounds.outWidth, bounds.outHeight) / (amostra * 2) >= ladoMax) amostra *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = amostra; inMutable = true }   // Pessoas.processar grava de volta (setPixels); imutável estourava (Codex 17/09)
        val bruto = contexto.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = contexto.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        // teto exato (Codex 17/09: com só inSampleSize, fonte de 4608 px pedida a 2400 ficava em 4608 = memória e tempo)
        val ladoBruto = max(bruto.width, bruto.height)
        val certo = if (ladoBruto > ladoMax * 1.04f) { val e = ladoMax.toFloat() / ladoBruto
            Bitmap.createScaledBitmap(bruto, max(1, (bruto.width * e).toInt()), max(1, (bruto.height * e).toInt()), true).also { if (it !== bruto) bruto.recycle() } } else bruto
        if (rot == 0) return certo
        val girado = Bitmap.createBitmap(certo, 0, 0, certo.width, certo.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        if (girado !== certo) certo.recycle()
        return girado
    }

    /**
     * Realce SÓ na luminância (agy: por canal corrompe o matiz sob luz amarela e estoura o fundo de RG/CNH):
     * retinex simplificado na luma (Y ÷ fundo borrado de Y) e estiramento por percentis; R, G e B são
     * escalados pela mesma razão Y'/Y, então a cor fica. Documento colorido (muita saturação) recebe
     * branco mais brando (1%) do que folha de texto (8%).
     */
    private fun realca(b: Bitmap, texto: Boolean = false): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val y0 = IntArray(w * h); var satAcum = 0L
        for (i in px.indices) { val c = px[i]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            y0[i] = (r * 30 + g * 59 + bl * 11) / 100; satAcum += (max(r, max(g, bl)) - min(r, min(g, bl))) }
        // fundo por convolução normalizada só com pixels claros: o texto não puxa a estimativa para baixo
        // (halo branco em volta das letras e papel manchado, visto na folha do TRE em 16/09; medido: mancha 0,059 → 0,055,
        // contraste do texto 0,70 → 0,73)
        val fundo = fundoClaro(y0, w, h, 24)
        val y1 = IntArray(w * h) { (y0[it] * 235 / max(1, fundo[it])).coerceIn(0, 255) }
        val colorido = satAcum / px.size > 28          // saturação média: RG, recibo colorido, foto de cartão
        val hist = IntArray(256); for (v in y1) hist[v]++
        val n = px.size; var acc = 0; var lo = 0; var hi = 255
        for (i in 0..255) { acc += hist[i]; if (acc >= n * 0.01) { lo = i; break } }
        val corteBranco = if (colorido) 0.01 else 0.08
        acc = 0; for (i in 255 downTo 0) { acc += hist[i]; if (acc >= n * corteBranco) { hi = i; break } }
        val tabela = if (hi - lo >= 40) IntArray(256) { ((it - lo) * 255 / (hi - lo)).coerceIn(0, 255) } else IntArray(256) { it }
        // estilo Texto (Codex): limiar local suave de Sauvola misturado meio a meio com a luminância corrigida; preserva cinzas
        // nas bordas (não é binarização). Janela 41 px a 3200 px, k 0,2, transição d 16, alfa 0,5 — pontos de partida para A/B.
        val textoY: IntArray? = if (texto) sauvolaSuave(y1, w, h, max(7, (20f * max(w, h) / 3200f).toInt())) else null
        for (i in px.indices) {
            val c = px[i]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            val alvo = if (textoY != null) tabela[textoY[i]] else tabela[y1[i]]; val base = max(1, y0[i])
            var rr = (r * alvo / base).coerceIn(0, 255); var gg = (g * alvo / base).coerceIn(0, 255); var bb = (bl * alvo / base).coerceIn(0, 255)
            // papel claro vai para o neutro: a sombra tem cor diferente da luz (nota do BK, 16/09: rosado onde havia sombra);
            // acima de 180 de luminância a saturação cai até 75%. Texto e logos escuros não mudam; documento colorido, menos.
            if (alvo > 180) {
                val t = ((alvo - 180) / 75f).coerceIn(0f, 1f) * (if (colorido) 0.35f else 0.75f)
                val y = (rr * 30 + gg * 59 + bb * 11) / 100
                rr = (rr + (y - rr) * t).toInt(); gg = (gg + (y - gg) * t).toInt(); bb = (bb + (y - bb) * t).toInt()
            }
            px[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
