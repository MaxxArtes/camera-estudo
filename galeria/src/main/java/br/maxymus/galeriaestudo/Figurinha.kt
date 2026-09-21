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

    fun montar(base: Bitmap, plena: FloatArray, zoom: Float = 1f, dx: Float = 0f, dy: Float = 0f, contorno: Int = 8): Montagem? {
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
        return Montagem(saida, x0, y0, esc, ex, ey, w, h)
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

    /** Figurinhas já feitas, da mais nova para a mais antiga. */
    fun acervo(ctx: Context): List<File> =
        File(ctx.filesDir, "figurinhas").listFiles { f -> f.isFile && f.name.endsWith(".webp") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun suave(v: Float): Float { val t = ((v - 0.2f) / 0.6f).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
}
