package br.maxymus.galeriaestudo

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Fundo e retrato, portados do Retrato.kt/Acabamento.kt da câmera: máscara de pessoa pelo segmentador multiclasse
 * (256x256, maior bloco conectado), desfoque em disco NORMALIZADO em luz linear (pessoa com peso zero: sem halo claro
 * em volta dela), composição com alfa suave. A máscara é independente da resolução (sempre 256²), então a prévia e a
 * exportação batem. Modos: desfocar, preto e branco no fundo, cor sólida, remover (alfa) e embelezar a pele.
 */
object Fundo {
    enum class Modo { Nenhum, Desfocar, PretoEBranco, Cor, Remover, Imagem }
    /** Motor do recorte (decisão do dono, 20/09, "os dois"): Leve = multiclasse embutido (256², sempre funciona); Padrão = ML Kit
     *  Subject Segmentation (módulo do Play); Alta = ISNet int8 baixado sob demanda (46 MB, classe remove.bg). */
    enum class Motor(val rotulo: String) { Leve("Leve"), Padrao("Padrão"), Alta("Alta") }
    /** Pincelada de correção da máscara: adiciona (vira pessoa, sai do desfoque) ou remove (vira fundo). Normalizada. */
    data class Traco(val pontos: List<Pair<Float, Float>>, val raio: Float, val adiciona: Boolean)

    data class Parametros(val modo: Modo = Modo.Nenhum, val intensidade: Float = 60f, val cor: Int = 0xFFFFFFFF.toInt(), val pele: Float = 0f, val tracos: List<Traco> = emptyList(), val motor: Motor = Motor.Padrao, val imagem: String = "") {
        val neutro: Boolean get() = modo == Modo.Nenhum && pele == 0f
    }

    /**
     * Máscara de pessoa em qualquer resolução (pw×ph, 1 = pessoa), amostrada bilinear em coordenadas normalizadas, então a
     * prévia (≤1024) e a exportação (≤4096) batem. `fina` = veio de um motor que já entrega borda precisa (ML Kit, ISNet):
     * plena() só reamostra e aplica os traços do usuário. `peleBruta` (256², do multiclasse) alimenta Corrigir>Pele.
     * `pedido` é o motor que a receita pediu; `motor` o que de fato rodou (cai para Leve com `motivo` quando falha).
     */
    class Mascara(val pessoaMapa: FloatArray, val pw: Int, val ph: Int, val peleBruta: FloatArray?, val fina: Boolean,
                  val motor: Motor, val pedido: Motor = motor, val motivo: String? = null) {
        fun pessoa(x: Float, y: Float) = amostra(pessoaMapa, pw, ph, x, y)
        fun pele(x: Float, y: Float) = if (peleBruta == null) 0f else amostra(peleBruta, 256, 256, x, y)
        /** fração da imagem coberta pela pessoa, 0..1 */
        val cobertura: Float get() { var c = 0; for (v in pessoaMapa) if (v > 0.5f) c++; return c.toFloat() / pessoaMapa.size }

        /**
         * Máscara no tamanho da imagem. Motor Leve: pós-processamento MEDIDO na bancada (galeria/medicao/porte2.py, 20/09): a
         * máscara do modelo (256², mole, com buracos em calça escura) vira silhueta limpa em 4 passos numa resolução fixa de
         * 512 px — binariza, fecha frestas, preenche buracos internos, mantém TODOS os blocos ≥ 0,3% (num grupo, cada bloco é
         * uma pessoa) — e só então sobe para o tamanho da foto e passa pelo filtro guiado com eps PEQUENO (0,001), que
         * transfere as bordas reais (cabelo, braço) sem borrar. Motores finos: só reamostra (a borda já vem do modelo; o
         * pós-processamento do Leve a estragaria). Depois, em ambos, as pinceladas do usuário.
         */
        fun plena(px: IntArray, w: Int, h: Int, tracos: List<Traco>): FloatArray {
            val n = w * h
            val m: FloatArray
            if (fina) {
                m = FloatArray(n) { k -> amostra(pessoaMapa, pw, ph, (k % w) / (w - 1f), (k / w) / (h - 1f)) }
            } else {
                val lw = min(512, w); val lh = max(1, (h.toLong() * lw / w).toInt())
                var bin = BooleanArray(lw * lh) { k -> pessoa((k % lw) / (lw - 1f), (k / lw) / (lh - 1f)) > 0.5f }
                val rf = max(2, min(lw, lh) / 60)
                bin = erodeB(dilataB(bin, lw, lh, rf), lw, lh, rf)
                preencheBuracos(bin, lw, lh)
                bin = mantemBlocos(bin, lw, lh, 0.003f)
                val limpa = FloatArray(lw * lh) { if (bin[it]) 1f else 0f }
                m = FloatArray(n) { k -> amostra(limpa, lw, lh, (k % w) / (w - 1f), (k / w) / (h - 1f)) }
                val guia = FloatArray(n) { k -> val c = px[k]; ((c shr 16 and 255) * 0.299f + (c shr 8 and 255) * 0.587f + (c and 255) * 0.114f) / 255f }
                val r = max(3, min(w, h) / 120); val eps = 0.001f
                val mI = caixaF(guia, w, h, r); val mP = caixaF(m, w, h, r)
                val ii = FloatArray(n) { guia[it] * guia[it] }; val ip = FloatArray(n) { guia[it] * m[it] }
                val cI = caixaF(ii, w, h, r); val cIP = caixaF(ip, w, h, r)
                val a = FloatArray(n); val b = FloatArray(n)
                for (k in 0 until n) { val varI = cI[k] - mI[k] * mI[k]; val cov = cIP[k] - mI[k] * mP[k]; a[k] = cov / (varI + eps); b[k] = mP[k] - a[k] * mI[k] }
                val mA = caixaF(a, w, h, r); val mB = caixaF(b, w, h, r)
                for (k in 0 until n) m[k] = (mA[k] * guia[k] + mB[k]).coerceIn(0f, 1f)
            }
            for (t in tracos) {
                val rp = max(1f, t.raio * w); val alvo = if (t.adiciona) 1f else 0f
                for ((nx, ny) in t.pontos) {
                    val cx = nx * (w - 1); val cy = ny * (h - 1)
                    val x0 = max(0, (cx - rp).toInt()); val x1 = min(w - 1, (cx + rp).toInt() + 1); val y0 = max(0, (cy - rp).toInt()); val y1 = min(h - 1, (cy + rp).toInt() + 1)
                    for (y in y0..y1) for (x in x0..x1) {
                        val dx = x - cx; val dy = y - cy; val d = sqrt(dx * dx + dy * dy) / rp
                        if (d > 1f) continue
                        val peso = 1f - suave(d, 0.7f, 1f)   // miolo cheio, borda macia
                        val k = y * w + x; m[k] += (alvo - m[k]) * peso
                    }
                }
            }
            return m
        }
    }

    private val paraLinear = FloatArray(256) { val c = it / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }
    private val paraSrgb = IntArray(4097) { val l = it / 4096f; val c = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f; (c * 255f + 0.5f).toInt().coerceIn(0, 255) }
    private fun srgb(l: Float) = paraSrgb[(l.coerceIn(0f, 1f) * 4096f).toInt()]
    private fun suave(v: Float, a: Float, b: Float): Float { val t = ((v - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Motor Leve: multiclasse 256² (entrada reduzida a ≤512 px). Null se o modelo falhar. */
    private fun segmentarLeve(ctx: Context, b: Bitmap): Mascara? {
        val esc = min(1f, 512f / max(b.width, b.height))
        val peq = if (esc < 1f) Bitmap.createScaledBitmap(b, max(1, (b.width * esc).toInt()), max(1, (b.height * esc).toInt()), true) else b
        val mapa = try { Segmentos.segmentar(ctx, peq) } finally { if (peq !== b) peq.recycle() }
        mapa ?: return null
        val n = 256 * 256
        val pessoa = FloatArray(n) { k -> 1f - mapa.cats[k * 6 + Segmentos.FUNDO] }
        val pele = FloatArray(n) { k -> (mapa.cats[k * 6 + Segmentos.PELE_CORPO] + mapa.cats[k * 6 + Segmentos.PELE_ROSTO]).coerceIn(0f, 1f) }
        return Mascara(pessoa, 256, 256, pele, false, Motor.Leve)   // limpeza fica em plena(): num grupo, blocos separados são pessoas
    }

    /**
     * Roda o motor pedido. O multiclasse roda SEMPRE (dá a pele para Corrigir>Pele e é o plano B); os motores finos recebem
     * a foto reduzida a ≤1024 px (o ISNet é 1024² fixo; o ML Kit devolve a máscara no tamanho da entrada). Se o motor
     * pedido falhar (módulo do Play ausente, modelo ainda não baixado), volta a máscara Leve marcada com `motivo`.
     * Null só se nem o multiclasse rodar.
     */
    fun segmentar(ctx: Context, b: Bitmap, motor: Motor = Motor.Leve): Mascara? {
        val t0 = System.nanoTime()
        val leve = segmentarLeve(ctx, b)
        if (motor == Motor.Leve) return leve
        val esc = min(1f, 1024f / max(b.width, b.height))
        val peq = if (esc < 1f) Bitmap.createScaledBitmap(b, max(1, (b.width * esc).toInt()), max(1, (b.height * esc).toInt()), true) else b
        var mapa: FloatArray? = null; var mw = 0; var mh = 0; var motivo: String? = null
        try {
            when (motor) {
                Motor.Padrao -> { val r = MlKitAssunto.segmentar(peq); if (r != null) { mapa = r.mapa; mw = r.w; mh = r.h } else motivo = MlKitAssunto.ultimoErro }
                Motor.Alta -> { val r = IsnetOnnx.segmentar(ctx, peq); if (r != null) { mapa = r.mapa; mw = r.w; mh = r.h } else motivo = IsnetOnnx.ultimoErro }
                Motor.Leve -> {}
            }
        } finally { if (peq !== b) peq.recycle() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val m = mapa
        if (m == null) {
            Telemetria.evento("editor_motor", mapOf("motor" to motor.name, "ok" to false, "ms" to ms, "msg" to (motivo ?: "")))
            return leve?.let { Mascara(it.pessoaMapa, it.pw, it.ph, it.peleBruta, false, Motor.Leve, motor, motivo ?: "falhou") }
        }
        Telemetria.evento("editor_motor", mapOf("motor" to motor.name, "ok" to true, "ms" to ms, "larg" to mw, "alt" to mh))
        return Mascara(m, mw, mh, leve?.peleBruta, true, motor)
    }

    /** Média em disco de raio r por prefixos de linha (custo ∝ diâmetro). */
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

    private fun amostra(a: FloatArray, aw: Int, ah: Int, x: Float, y: Float): Float {
        val fx = (x * (aw - 1)).coerceIn(0f, aw - 1f); val fy = (y * (ah - 1)).coerceIn(0f, ah - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(aw - 1, x0 + 1); val y1 = min(ah - 1, y0 + 1); val tx = fx - x0; val ty = fy - y0
        return (a[y0 * aw + x0] * (1 - tx) + a[y0 * aw + x1] * tx) * (1 - ty) + (a[y1 * aw + x0] * (1 - tx) + a[y1 * aw + x1] * tx) * ty
    }

    /** Dilatação em caixa (raio r), separável: linha e depois coluna. */
    private fun dilataB(b: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val t = BooleanArray(w * h); val o = BooleanArray(w * h)
        for (y in 0 until h) { val l = y * w; var c = 0; for (x in 0 until min(w, r)) if (b[l + x]) c++
            for (x in 0 until w) { if (x + r < w && b[l + x + r]) c++; if (x - r - 1 >= 0 && b[l + x - r - 1]) c--; t[l + x] = c > 0 } }
        for (x in 0 until w) { var c = 0; for (y in 0 until min(h, r)) if (t[y * w + x]) c++
            for (y in 0 until h) { if (y + r < h && t[(y + r) * w + x]) c++; if (y - r - 1 >= 0 && t[(y - r - 1) * w + x]) c--; o[y * w + x] = c > 0 } }
        return o
    }
    private fun erodeB(b: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val inv = BooleanArray(b.size) { !b[it] }; val d = dilataB(inv, w, h, r); return BooleanArray(b.size) { !d[it] }
    }
    /** Buraco totalmente cercado por pessoa (calça escura, camisa) vira pessoa: inunda o fundo a partir da borda. */
    private fun preencheBuracos(b: BooleanArray, w: Int, h: Int) {
        val fora = BooleanArray(w * h); val fila = IntArray(w * h); var ini = 0; var fim = 0
        fun semeia(k: Int) { if (!b[k] && !fora[k]) { fora[k] = true; fila[fim++] = k } }
        for (x in 0 until w) { semeia(x); semeia((h - 1) * w + x) }
        for (y in 0 until h) { semeia(y * w); semeia(y * w + w - 1) }
        while (ini < fim) { val p = fila[ini++]; val x = p % w; val y = p / w
            if (x > 0) semeia(p - 1); if (x < w - 1) semeia(p + 1); if (y > 0) semeia(p - w); if (y < h - 1) semeia(p + w) }
        for (k in 0 until w * h) if (!b[k] && !fora[k]) b[k] = true
    }
    /** Mantém todos os blocos conectados com área ≥ minFrac (não só o maior: num grupo, cada bloco é uma pessoa). */
    private fun mantemBlocos(b: BooleanArray, w: Int, h: Int, minFrac: Float): BooleanArray {
        val rotulo = IntArray(w * h); val fila = IntArray(w * h); var k = 0; val tamanhos = ArrayList<Int>()
        for (i in 0 until w * h) {
            if (!b[i] || rotulo[i] != 0) continue
            k++; var ini = 0; var fim = 0; fila[fim++] = i; rotulo[i] = k; var n = 0
            while (ini < fim) { val p = fila[ini++]; n++; val x = p % w; val y = p / w
                if (x > 0 && b[p - 1] && rotulo[p - 1] == 0) { rotulo[p - 1] = k; fila[fim++] = p - 1 }
                if (x < w - 1 && b[p + 1] && rotulo[p + 1] == 0) { rotulo[p + 1] = k; fila[fim++] = p + 1 }
                if (y > 0 && b[p - w] && rotulo[p - w] == 0) { rotulo[p - w] = k; fila[fim++] = p - w }
                if (y < h - 1 && b[p + w] && rotulo[p + w] == 0) { rotulo[p + w] = k; fila[fim++] = p + w } }
            tamanhos += n
        }
        val minimo = (minFrac * w * h).toInt()
        return BooleanArray(w * h) { i -> rotulo[i] != 0 && tamanhos[rotulo[i] - 1] >= minimo }
    }

    /** Média em caixa separável (raio r) sobre floats. */
    private fun caixaF(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(a.size); val o = FloatArray(a.size)
        for (y in 0 until h) { val l = y * w; var s = 0f; var c = 0; for (x in 0 until min(w, r)) { s += a[l + x]; c++ }
            for (x in 0 until w) { if (x + r < w) { s += a[l + x + r]; c++ }; if (x - r - 1 >= 0) { s -= a[l + x - r - 1]; c-- }; t[l + x] = s / c } }
        for (x in 0 until w) { var s = 0f; var c = 0; for (y in 0 until min(h, r)) { s += t[y * w + x]; c++ }
            for (y in 0 until h) { if (y + r < h) { s += t[(y + r) * w + x]; c++ }; if (y - r - 1 >= 0) { s -= t[(y - r - 1) * w + x]; c-- }; o[y * w + x] = s / c } }
        return o
    }

    private fun caixa(px: IntArray, w: Int, h: Int, r: Int, desloc: Int): IntArray {
        val integ = LongArray((w + 1) * (h + 1))
        for (y in 1..h) { var linha = 0L; for (x in 1..w) { linha += (px[(y - 1) * w + x - 1] shr desloc and 255); integ[y * (w + 1) + x] = integ[(y - 1) * (w + 1) + x] + linha } }
        return IntArray(w * h) { k -> val x = k % w; val y = k / w
            val x0 = max(0, x - r); val y0 = max(0, y - r); val x1 = min(w, x + r + 1); val y1 = min(h, y + r + 1)
            val s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
            (s / ((x1 - x0) * (y1 - y0))).toInt() }
    }

    /** Máscara plena (refinada + traços) no tamanho do bitmap: para o Local e para sobreposições. */
    fun plenaDe(b: Bitmap, m: Mascara, tracos: List<Traco>): FloatArray {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        return m.plena(px, w, h, tracos)
    }

    /** Sobreposição coral translúcida de uma máscara qualquer (FloatArray w×h). */
    fun visualDe(plena: FloatArray, w: Int, h: Int, forca: Float = 0.5f, cor: Int = 0xFF575F): Bitmap {
        val rgb = cor and 0x00FFFFFF
        val out = IntArray(w * h) { k -> val a = (plena[k].coerceIn(0f, 1f) * 255f * forca).toInt().coerceIn(0, 255); (a shl 24) or rgb }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Sobreposição coral translúcida da área de PESSOA (o que fica fora do desfoque), no tamanho do bitmap dado. */
    fun visual(b: Bitmap, m: Mascara, tracos: List<Traco>): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val plena = m.plena(px, w, h, tracos)
        val out = IntArray(w * h) { k -> val a = (suave(plena[k], 0.2f, 0.8f) * 140f).toInt().coerceIn(0, 255); (a shl 24) or 0x00FF575F }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Escala a imagem de fundo para COBRIR w×h e corta o excesso pelo centro (nunca deforma). */
    fun prepararFundo(src: Bitmap, w: Int, h: Int): Bitmap {
        val esc = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val nw = max(w, (src.width * esc).toInt()); val nh = max(h, (src.height * esc).toInt())
        val esticado = if (nw != src.width || nh != src.height) Bitmap.createScaledBitmap(src, nw, nh, true) else src
        val corte = Bitmap.createBitmap(esticado, (nw - w) / 2, (nh - h) / 2, w, h)
        if (esticado !== src && esticado !== corte) esticado.recycle()   // createBitmap devolve a fonte quando o corte é a imagem toda
        return corte
    }

    /** Aplica o modo escolhido. Devolve bitmap novo (com alfa no modo Remover). `fundoBmp` já vem no tamanho de `b`. */
    fun aplicar(b: Bitmap, m: Mascara, p: Parametros, fundoBmp: Bitmap? = null): Bitmap {
        if (p.neutro) return b
        val w = b.width; val h = b.height; val n = w * h
        var px = IntArray(n).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        if (p.pele > 0f) px = embelezar(px, w, h, m, p.pele)
        val plena = if (p.modo == Modo.Nenhum) FloatArray(0) else m.plena(px, w, h, p.tracos)
        val saida = when (p.modo) {
            Modo.Nenhum -> px
            Modo.Desfocar -> desfocar(px, w, h, plena, p.intensidade)
            Modo.PretoEBranco -> IntArray(n) { k -> val c = px[k]; val a = suave(plena[k], 0.2f, 0.8f)
                val l = ((c shr 16 and 255) * 54 + (c shr 8 and 255) * 183 + (c and 255) * 19) shr 8
                val r = ((c shr 16 and 255) * a + l * (1 - a)).toInt(); val g = ((c shr 8 and 255) * a + l * (1 - a)).toInt(); val bl = ((c and 255) * a + l * (1 - a)).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or bl }
            Modo.Cor -> { val cr = p.cor shr 16 and 255; val cg = p.cor shr 8 and 255; val cb = p.cor and 255
                IntArray(n) { k -> val c = px[k]; val a = suave(plena[k], 0.2f, 0.8f)
                    val r = ((c shr 16 and 255) * a + cr * (1 - a)).toInt(); val g = ((c shr 8 and 255) * a + cg * (1 - a)).toInt(); val bl = ((c and 255) * a + cb * (1 - a)).toInt()
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or bl } }
            Modo.Remover -> IntArray(n) { k -> val a = suave(plena[k], 0.2f, 0.8f)
                (px[k] and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24) }
            Modo.Imagem -> if (fundoBmp == null || fundoBmp.width != w || fundoBmp.height != h) px else {
                val fp = IntArray(n).also { fundoBmp.getPixels(it, 0, w, 0, 0, w, h) }
                IntArray(n) { k -> val c = px[k]; val f = fp[k]; val a = suave(plena[k], 0.2f, 0.8f)
                    val r = ((c shr 16 and 255) * a + (f shr 16 and 255) * (1 - a)).toInt(); val g = ((c shr 8 and 255) * a + (f shr 8 and 255) * (1 - a)).toInt(); val bl = ((c and 255) * a + (f and 255) * (1 - a)).toInt()
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or bl }
            }
        }
        return Bitmap.createBitmap(saida, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Desfoque do fundo: disco normalizado em luz linear numa cópia de 600 px (pessoa com peso 0), composto por alfa suave. */
    private fun desfocar(pFrente: IntArray, w: Int, h: Int, plena: FloatArray, intensidade: Float): IntArray {
        val escF = min(1f, 600f / max(w, h)); val fw = max(1, (w * escF).toInt()); val fh = max(1, (h * escF).toInt())
        val lr = FloatArray(fw * fh); val lg = FloatArray(fw * fh); val lb = FloatArray(fw * fh); val peso = FloatArray(fw * fh)
        for (y in 0 until fh) for (x in 0 until fw) {
            val sx = min(w - 1, (x / escF).toInt()); val sy = min(h - 1, (y / escF).toInt()); val c = pFrente[sy * w + sx]
            val pf = 1f - suave(plena[sy * w + sx], 0.05f, 0.30f)
            val k = y * fw + x; peso[k] = pf; lr[k] = paraLinear[c shr 16 and 255] * pf; lg[k] = paraLinear[c shr 8 and 255] * pf; lb[k] = paraLinear[c and 255] * pf
        }
        val raio = (2f + (intensidade / 100f) * 8f).toInt().coerceIn(2, 10)
        val dR = disco(lr, fw, fh, raio); val dG = disco(lg, fw, fh, raio); val dB = disco(lb, fw, fh, raio); val dP = disco(peso, fw, fh, raio)
        val rG = min(3 * raio, 30)
        val gR = disco(lr, fw, fh, rG); val gG = disco(lg, fw, fh, rG); val gB = disco(lb, fw, fh, rG); val gP = disco(peso, fw, fh, rG)
        val fR = FloatArray(fw * fh); val fG = FloatArray(fw * fh); val fB = FloatArray(fw * fh)
        for (k in 0 until fw * fh) {
            if (dP[k] > 0.02f) { fR[k] = dR[k] / dP[k]; fG[k] = dG[k] / dP[k]; fB[k] = dB[k] / dP[k] }
            else if (gP[k] > 0.005f) { fR[k] = gR[k] / gP[k]; fG[k] = gG[k] / gP[k]; fB[k] = gB[k] / gP[k] }
            else { fR[k] = lr[k]; fG[k] = lg[k]; fB[k] = lb[k] }
        }
        fun fundo(arr: FloatArray, x: Float, y: Float): Float {
            val fx = (x * (fw - 1)).coerceIn(0f, fw - 1f); val fy = (y * (fh - 1)).coerceIn(0f, fh - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(fw - 1, x0 + 1); val y1 = min(fh - 1, y0 + 1); val tx = fx - x0; val ty = fy - y0
            return (arr[y0 * fw + x0] * (1 - tx) + arr[y0 * fw + x1] * tx) * (1 - ty) + (arr[y1 * fw + x0] * (1 - tx) + arr[y1 * fw + x1] * tx) * ty
        }
        val saida = IntArray(w * h)
        for (y in 0 until h) { val ny = y / (h - 1f)
            for (x in 0 until w) {
                val i = y * w + x; val nx = x / (w - 1f)
                val a = suave(plena[i], 0.20f, 0.80f)
                val f = pFrente[i]
                if (a >= 0.995f) { saida[i] = f; continue }
                val br = srgb(fundo(fR, nx, ny)); val bg = srgb(fundo(fG, nx, ny)); val bl = srgb(fundo(fB, nx, ny))
                val r = ((f shr 16 and 255) * a + br * (1 - a)).toInt(); val g = ((f shr 8 and 255) * a + bg * (1 - a)).toInt(); val b = ((f and 255) * a + bl * (1 - a)).toInt()
                saida[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return saida
    }

    /** Suaviza a pele (máscara do modelo) preservando detalhe forte (olho, boca, cabelo). forca 0..100. */
    private fun embelezar(px: IntArray, w: Int, h: Int, m: Mascara, forca: Float): IntArray {
        val raio = (max(w, h) / 800f * (2f + forca / 25f)).toInt().coerceIn(2, 12)
        val bR = caixa(px, w, h, raio, 16); val bG = caixa(px, w, h, raio, 8); val bB = caixa(px, w, h, raio, 0)
        val f = forca / 100f
        return IntArray(w * h) { k ->
            val c = px[k]; val r = c shr 16 and 255; val g = c shr 8 and 255; val bl = c and 255
            val lum = (r * 54 + g * 183 + bl * 19) shr 8; val lb = (bR[k] * 54 + bG[k] * 183 + bB[k] * 19) shr 8
            val borda = 1f - ((abs(lum - lb) - 6f) / 18f).coerceIn(0f, 1f)
            val wgt = f * m.pele((k % w) / (w - 1f), (k / w) / (h - 1f)) * borda
            if (wgt <= 0.001f) c else {
                val rr = (r + (bR[k] - r) * wgt).toInt(); val gg = (g + (bG[k] - g) * wgt).toInt(); val bb2 = (bl + (bB[k] - bl) * wgt).toInt()
                (0xFF shl 24) or (rr.coerceIn(0, 255) shl 16) or (gg.coerceIn(0, 255) shl 8) or bb2.coerceIn(0, 255)
            }
        }
    }
}
