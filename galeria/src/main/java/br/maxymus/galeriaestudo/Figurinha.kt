package br.maxymus.galeriaestudo

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Figurinha (sticker) a partir do recorte da pessoa: 512x512 com fundo transparente, em WebP.
 * Medido na bancada em 21/09 sobre recortes reais: na qualidade 95 o arquivo fica entre 45 e 62 KB, bem abaixo do
 * teto de 100 KB do WhatsApp; sem perda passaria de 130 KB, por isso a compressão é com perda e a qualidade cai em
 * degraus só se algum caso extremo estourar o teto.
 */
object Figurinha {
    const val LADO = 512
    const val MARGEM = 8          // o WhatsApp recomenda borda transparente em volta
    const val TETO_BYTES = 100 * 1024
    private val QUALIDADES = intArrayOf(95, 90, 85, 80, 75, 70)

    /**
     * Bitmap 512x512 pronto: assunto recortado pela máscara, aparado, enquadrado e com contorno branco de adesivo.
     * `zoom` 1 = pessoa inteira dentro do quadrado; `dx`/`dy` em fração do lado deslocam o enquadramento.
     * `contorno` em pixels da saída (0 a 16), desenhado por trás do assunto. Null quando não há assunto na máscara.
     */
    /**
     * Resultado da montagem: o quadrado pronto e a transformação que o gerou, para converter um toque no quadrado
     * de volta em coordenada da FOTO (é lá que vivem os traços de refino da máscara).
     */
    class Montagem(val bitmap: Bitmap, private val x0: Int, private val y0: Int, private val esc: Float,
                   private val ex: Float, private val ey: Float, private val fw: Int, private val fh: Int) {
        fun paraFoto(sx: Float, sy: Float): Pair<Float, Float> =
            (((sx * LADO - ex) / esc + x0) / fw) to (((sy * LADO - ey) / esc + y0) / fh)
        /** Raio dado em fração do lado do quadrado vira fração da largura da foto. */
        fun raioParaFoto(r: Float): Float = (r * LADO / esc) / fw
    }

    fun montar(base: Bitmap, plena: FloatArray, zoom: Float = 1f, dx: Float = 0f, dy: Float = 0f, contorno: Int = 8, texto: Texto? = null): Montagem? {
        val w = base.width; val h = base.height
        if (plena.size != w * h) return null
        val px = IntArray(w * h).also { base.getPixels(it, 0, w, 0, 0, w, h) }
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (plena[y * w + x] > 0.03f) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        }
        if (x1 < x0 || y1 < y0) return null
        val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
        val corte = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            val k = (y + y0) * w + (x + x0)
            val a = (suave(plena[k]) * 255f).toInt().coerceIn(0, 255)
            corte[y * cw + x] = (a shl 24) or (px[k] and 0x00FFFFFF)
        }
        val recortado = Bitmap.createBitmap(corte, cw, ch, Bitmap.Config.ARGB_8888)
        val livre = LADO - 2 * (MARGEM + contorno)
        val esc = min(livre.toFloat() / cw, livre.toFloat() / ch) * zoom.coerceIn(0.5f, 3f)
        val nw = max(1, (cw * esc).toInt()); val nh = max(1, (ch * esc).toInt())
        val menor = Bitmap.createScaledBitmap(recortado, nw, nh, true)
        if (menor !== recortado) recortado.recycle()
        val ex = (LADO - nw) / 2f + dx * LADO
        val ey = (LADO - nh) / 2f + dy * LADO
        val saida = Bitmap.createBitmap(LADO, LADO, Bitmap.Config.ARGB_8888)
        val tela = android.graphics.Canvas(saida)
        if (contorno > 0) contornoDe(menor, nw, nh, contorno)?.let { c ->
            tela.drawBitmap(c, ex - contorno, ey - contorno, null); c.recycle()
        }
        tela.drawBitmap(menor, ex, ey, null)
        menor.recycle()
        if (texto != null && !texto.vazio) desenhaTexto(tela, texto)
        return Montagem(saida, x0, y0, esc, ex, ey, w, h)
    }

    /**
     * Quebra o texto na largura útil. Devolve null quando não cabe em 2 linhas — nesse caso a tela pede para
     * encurtar em vez de diminuir a letra sozinha ou cortar calado (decisão do Astra).
     */
    fun linhasDe(t: Texto): List<String>? {
        if (t.vazio) return emptyList()
        val tinta = tinta(t)
        val util = LADO - 2f * TEXTO_MARGEM - (if (t.tarja) 24f else 0f)
        val saida = ArrayList<String>(); var atual = StringBuilder()
        for (palavra in t.conteudo.trim().split(" ").filter { it.isNotBlank() }) {
            val teste = if (atual.isEmpty()) palavra else "$atual $palavra"
            if (tinta.measureText(teste) <= util || atual.isEmpty()) atual = StringBuilder(teste)
            else { saida += atual.toString(); atual = StringBuilder(palavra); if (saida.size > MAX_LINHAS) return null }
        }
        if (atual.isNotEmpty()) saida += atual.toString()
        if (saida.size > MAX_LINHAS) return null
        if (saida.any { tinta.measureText(it) > util }) return null   // palavra única maior que a linha
        return saida
    }

    private fun tinta(t: Texto) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = t.tamanho.coerceIn(TEXTO_MIN, TEXTO_MAX)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
        strokeJoin = android.graphics.Paint.Join.ROUND; strokeCap = android.graphics.Paint.Cap.ROUND
    }

    /**
     * Escreve o texto: tarja preta opaca OU contorno preto. O traço vai a 8 px porque ele é centrado na letra,
     * então sobram os 4 px visíveis que o Astra pediu para a saída de 512.
     */
    private fun desenhaTexto(tela: android.graphics.Canvas, t: Texto) {
        val linhas = linhasDe(t) ?: return
        if (linhas.isEmpty()) return
        val tinta = tinta(t)
        val tam = tinta.textSize
        val alturaLinha = tam * 1.12f
        val cx = t.x * LADO
        var y = t.y * LADO - (linhas.size - 1) * alturaLinha / 2f
        if (t.tarja) {
            val larg = linhas.maxOf { tinta.measureText(it) } + 24f
            val alt = linhas.size * alturaLinha + 16f
            val topo = y - tam * 0.82f - 8f
            val r = android.graphics.RectF(cx - larg / 2f, topo, cx + larg / 2f, topo + alt)
            tela.drawRoundRect(r, 14f, 14f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        }
        for (l in linhas) {
            if (!t.tarja) {
                tinta.style = android.graphics.Paint.Style.STROKE; tinta.strokeWidth = 8f; tinta.color = 0xFF000000.toInt()
                tela.drawText(l, cx, y, tinta)
            }
            tinta.style = android.graphics.Paint.Style.FILL; tinta.color = t.cor
            tela.drawText(l, cx, y, tinta)
            y += alturaLinha
        }
    }

    /** Silhueta branca do assunto, engordada `contorno` px em volta (dilatação em caixa separável sobre o alfa). */
    private fun contornoDe(b: Bitmap, w: Int, h: Int, raio: Int = 8): Bitmap? {
        if (raio <= 0) return null
        val cw = w + 2 * raio; val ch = h + 2 * raio
        val alfa = ByteArray(cw * ch)
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 40) alfa[(y + raio) * cw + (x + raio)] = 1
        val t = ByteArray(cw * ch)
        for (y in 0 until ch) { val l = y * cw
            for (x in 0 until cw) { var v: Byte = 0
                var i = max(0, x - raio); val f = min(cw - 1, x + raio)
                while (i <= f) { if (alfa[l + i].toInt() == 1) { v = 1; break }; i++ }
                t[l + x] = v } }
        val o = IntArray(cw * ch)
        for (x in 0 until cw) for (y in 0 until ch) {
            var v = false; var i = max(0, y - raio); val f = min(ch - 1, y + raio)
            while (i <= f) { if (t[i * cw + x].toInt() == 1) { v = true; break }; i++ }
            if (v) o[y * cw + x] = -0x1   // branco opaco
        }
        return Bitmap.createBitmap(o, cw, ch, Bitmap.Config.ARGB_8888)
    }

    /** Codifica em WebP respeitando o teto de 100 KB; devolve os bytes ou null se nem na menor qualidade couber. */
    fun webp(b: Bitmap): ByteArray? {
        @Suppress("DEPRECATION")
        val formato = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        for (q in QUALIDADES) {
            val saida = java.io.ByteArrayOutputStream()
            if (!b.compress(formato, q, saida)) continue
            val bytes = saida.toByteArray()
            if (bytes.size <= TETO_BYTES) { Telemetria.evento("figurinha", mapOf("q" to q, "bytes" to bytes.size)); return bytes }
        }
        Telemetria.evento("erro", mapOf("onde" to "figurinha_webp"))
        return null
    }

    /** Grava no acervo do app (filesDir/figurinhas) e devolve o arquivo. */
    fun guardar(ctx: Context, bytes: ByteArray, nome: String = "fig_" + System.currentTimeMillis() + ".webp"): File {
        val pasta = File(ctx.filesDir, "figurinhas").apply { mkdirs() }
        return File(pasta, nome).also { it.writeBytes(bytes) }
    }

    // ---- pacote do WhatsApp ----
    // Contrato lido do repositório oficial WhatsApp/stickers (ver galeria/docs/PESQUISA_FIGURINHAS_21-09.md):
    // pacote de 3 a 30 figurinhas, emoji OBRIGATÓRIO (1 a 3 por figurinha), bandeja PNG 96x96 até 50 KB.
    const val AUTORIDADE = "br.maxymus.galeriaestudo.figurinhas"
    const val PACOTE_ID = "galeria-estudo"
    const val PACOTE_NOME = "Minhas figurinhas"
    const val MAX_PACOTE = 30
    const val MIN_PACOTE = 3
    private const val ARQ_DADOS = "pacote.json"
    private const val ARQ_BANDEJA = "bandeja.png"

    data class Item(val arquivo: String, val emoji: String)

    /**
     * Texto escrito sobre a figurinha. Posição e tamanho em fração do lado de 512, então a conta não muda se o
     * lado mudar. O contorno existe porque a figurinha é transparente e cai sobre conversa clara ou escura.
     */
    data class Texto(val conteudo: String = "", val x: Float = 0.5f, val y: Float = 0.86f,
                     val tamanho: Float = 56f, val cor: Int = 0xFFFFFFFF.toInt(), val tarja: Boolean = false) {
        val vazio: Boolean get() = conteudo.isBlank()
    }
    /** Cores do texto (Astra, 21/09). O contorno e a tarja são sempre pretos: é o que se lê em conversa clara e escura. */
    val CORES_TEXTO = listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFFFF80AB, 0xFF80D8FF, 0xFFB9F6CA, 0xFFFFAB40).map { it.toInt() }
    const val TEXTO_MIN = 40f
    const val TEXTO_MAX = 88f
    const val TEXTO_MARGEM = 24f
    const val MAX_LINHAS = 2

    private fun pasta(ctx: Context) = File(ctx.filesDir, "figurinhas").apply { mkdirs() }

    /** Itens do pacote, do mais novo para o mais antigo, limitado ao teto do WhatsApp. */
    fun itens(ctx: Context): List<Item> {
        val dados = File(pasta(ctx), ARQ_DADOS)
        val emojis = HashMap<String, String>()
        if (dados.isFile) runCatching {
            val o = org.json.JSONObject(dados.readText()).optJSONArray("itens")
            if (o != null) for (i in 0 until o.length()) { val it2 = o.getJSONObject(i); emojis[it2.getString("arquivo")] = it2.optString("emoji", "😀") }
        }
        return acervo(ctx).take(MAX_PACOTE).map { Item(it.name, emojis[it.name] ?: "😀") }
    }

    /** Guarda o emoji escolhido para uma figurinha e sobe a versão dos dados (o WhatsApp recarrega por ela). */
    fun registrar(ctx: Context, arquivo: String, emoji: String) {
        val dados = File(pasta(ctx), ARQ_DADOS)
        val raiz = runCatching { org.json.JSONObject(dados.readText()) }.getOrElse { org.json.JSONObject() }
        val lista = raiz.optJSONArray("itens") ?: org.json.JSONArray()
        var achou = false
        for (i in 0 until lista.length()) if (lista.getJSONObject(i).optString("arquivo") == arquivo) { lista.getJSONObject(i).put("emoji", emoji); achou = true }
        if (!achou) lista.put(org.json.JSONObject().put("arquivo", arquivo).put("emoji", emoji))
        raiz.put("itens", lista).put("versao", raiz.optInt("versao", 0) + 1)
        dados.writeText(raiz.toString())
    }

    /** Versão dos dados: muda quando o pacote muda, e é o que faz o WhatsApp reler as figurinhas. */
    fun versaoDados(ctx: Context): String =
        runCatching { org.json.JSONObject(File(pasta(ctx), ARQ_DADOS).readText()).optInt("versao", 1) }.getOrDefault(1).toString()

    /** Ícone de bandeja 96x96 PNG (teto de 50 KB), refeito a partir da figurinha mais nova. */
    fun bandeja(ctx: Context): File? {
        val nova = acervo(ctx).firstOrNull() ?: return null
        val destino = File(pasta(ctx), ARQ_BANDEJA)
        if (destino.isFile && destino.lastModified() >= nova.lastModified()) return destino
        val b = android.graphics.BitmapFactory.decodeFile(nova.path) ?: return null
        val peq = Bitmap.createScaledBitmap(b, 96, 96, true)
        if (peq !== b) b.recycle()
        val saida = java.io.ByteArrayOutputStream()
        peq.compress(Bitmap.CompressFormat.PNG, 100, saida); peq.recycle()
        if (saida.size() > 50 * 1024) return null
        destino.writeBytes(saida.toByteArray())
        return destino
    }

    fun arquivo(ctx: Context, nome: String): File = File(pasta(ctx), nome)

    /** Figurinhas já feitas, da mais nova para a mais antiga. */
    fun acervo(ctx: Context): List<File> =
        File(ctx.filesDir, "figurinhas").listFiles { f -> f.isFile && f.name.endsWith(".webp") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun suave(v: Float): Float { val t = ((v - 0.2f) / 0.6f).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
}
