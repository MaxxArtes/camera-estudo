package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pessoas conhecidas (fase 2 dos rostos, 16/09): embedding facial no aparelho (MobileFaceNet TFLite, 112x112 → 192
 * números, BSD-3), cadastro automático e âncora de pele por pessoa.
 *
 * Medido na bancada com 15 fotos do dono e 1 de outra pessoa (medicao/rosto_embedding.py): 11 das 15 acima de 0,78
 * de semelhança com o perfil médio; a outra pessoa em 0,49; as 4 fotos que caíram (0,34 a 0,47) tinham embelezador ou
 * filtro forte. Por isso: reconhecer na imagem CRUA, casar com ≥ 0,65, criar pessoa nova abaixo de 0,50, ignorar o meio.
 *
 * Privacidade: vetores, nomes e miniaturas ficam só em files/ do app; a telemetria recebe contagens e desvios, nunca
 * rosto, vetor ou nome. Tela "Pessoas" na gaveta lista e apaga.
 */
object Pessoas {
    private const val ARQ = "pessoas.json"
    private const val LIMIAR_CASA = 0.65f
    private const val LIMIAR_NOVO = 0.50f
    private const val MAX_VETORES = 12
    private const val MISTURA_PELE = 0.4f       // quanto da diferença para a referência de pele é corrigido

    class Pessoa(val id: String, var nome: String, val vetores: MutableList<FloatArray>, var pele: FloatArray?, var nPele: Int, var fotos: Int, val criado: Long)
    class Reconhecido(val pessoa: Pessoa?, val rosto: Rostos.Rosto, val sim: Float, val novo: Boolean)
    class Resultado(val reconhecidos: List<Reconhecido>, val correcaoPele: Float)

    private val trava = Mutex()
    @Volatile var ligado = true
    fun iniciar(ctx: Context) { ligado = ctx.getSharedPreferences("pessoas", Context.MODE_PRIVATE).getBoolean("ligado", true) }
    fun alternar(ctx: Context, v: Boolean) { ligado = v; ctx.getSharedPreferences("pessoas", Context.MODE_PRIVATE).edit().putBoolean("ligado", v).apply() }
    private var interp: Interpreter? = null
    private var cache: MutableList<Pessoa>? = null

    // ---------- modelo ----------
    private fun modelo(ctx: Context): Interpreter {
        interp?.let { return it }
        val fd = ctx.assets.openFd("mobilefacenet.tflite")
        val mapa = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(mapa, Interpreter.Options().setNumThreads(2)).also { interp = it }
    }

    /** Recorte alinhado pelos olhos (112x112) e vetor normalizado. */
    fun embedding(ctx: Context, b: Bitmap, r: Rostos.Rosto): FloatArray? = runCatching {
        val cx = r.caixa.exactCenterX(); val cy = r.caixa.exactCenterY()
        val lado = max(r.caixa.width(), r.caixa.height()) * 1.15f
        val ang = if (r.olhoEsq != null && r.olhoDir != null) Math.toDegrees(atan2((r.olhoDir.y - r.olhoEsq.y).toDouble(), (r.olhoDir.x - r.olhoEsq.x).toDouble())).toFloat() else 0f
        val m = Matrix().apply { postTranslate(-cx, -cy); postRotate(-ang); postScale(112f / lado, 112f / lado); postTranslate(56f, 56f) }
        val face = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        Canvas(face).drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(112 * 112).also { face.getPixels(it, 0, 112, 0, 0, 112, 112) }; face.recycle()
        val buf = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
        for (c in px) { buf.putFloat(((c shr 16 and 255) - 127.5f) / 128f); buf.putFloat(((c shr 8 and 255) - 127.5f) / 128f); buf.putFloat(((c and 255) - 127.5f) / 128f) }
        buf.rewind()
        val saida = Array(1) { FloatArray(192) }
        synchronized(this) { modelo(ctx).run(buf, saida) }
        val v = saida[0]; var n = 0f; for (x in v) n += x * x; n = sqrt(n) + 1e-6f
        FloatArray(192) { v[it] / n }
    }.getOrNull()

    private fun sim(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }

    // ---------- armazenamento ----------
    private fun arquivo(ctx: Context) = File(ctx.filesDir, ARQ)
    private fun carrega(ctx: Context): MutableList<Pessoa> {
        cache?.let { return it }
        val lista = mutableListOf<Pessoa>()
        runCatching {
            val j = JSONObject(arquivo(ctx).readText())
            val arr = j.getJSONArray("pessoas")
            for (i in 0 until arr.length()) { val p = arr.getJSONObject(i)
                val vs = p.getJSONArray("vetores"); val vetores = MutableList(vs.length()) { k -> val v = vs.getJSONArray(k); FloatArray(v.length()) { v.getDouble(it).toFloat() } }
                val pele = p.optJSONArray("pele")?.let { a -> FloatArray(3) { a.getDouble(it).toFloat() } }
                lista += Pessoa(p.getString("id"), p.getString("nome"), vetores, pele, p.optInt("nPele"), p.optInt("fotos"), p.optLong("criado")) }
        }
        cache = lista; return lista
    }
    private fun salva(ctx: Context, lista: List<Pessoa>) {
        val arr = JSONArray()
        for (p in lista) arr.put(JSONObject().apply {
            put("id", p.id); put("nome", p.nome); put("fotos", p.fotos); put("criado", p.criado); put("nPele", p.nPele)
            put("vetores", JSONArray(p.vetores.map { v -> JSONArray(v.map { Math.round(it * 10000) / 10000.0 }) }))
            p.pele?.let { put("pele", JSONArray(it.map { x -> Math.round(x * 10) / 10.0 })) } })
        arquivo(ctx).writeText(JSONObject().put("pessoas", arr).toString())
    }
    suspend fun listar(ctx: Context): List<Pessoa> = trava.withLock { carrega(ctx).toList() }
    suspend fun renomear(ctx: Context, id: String, nome: String) = trava.withLock { carrega(ctx).firstOrNull { it.id == id }?.let { it.nome = nome; salva(ctx, carrega(ctx)) } }
    suspend fun apagar(ctx: Context, id: String) = trava.withLock { val l = carrega(ctx); l.removeAll { it.id == id }; File(ctx.filesDir, "rosto_$id.jpg").delete(); salva(ctx, l) }
    suspend fun apagarTudo(ctx: Context) = trava.withLock { carrega(ctx).forEach { File(ctx.filesDir, "rosto_${it.id}.jpg").delete() }; cache = mutableListOf(); salva(ctx, emptyList()) }
    fun miniatura(ctx: Context, id: String): File = File(ctx.filesDir, "rosto_$id.jpg")

    // ---------- pele ----------
    /** Cor média da pele no centro do rosto (40% central da caixa), só pixels na faixa de tom de pele; null se poucos. */
    private fun peleDoRosto(px: IntArray, w: Int, h: Int, c: Rect): FloatArray? {
        val x0 = max(0, c.left + c.width() * 3 / 10); val x1 = min(w, c.right - c.width() * 3 / 10)
        val y0 = max(0, c.top + c.height() * 3 / 10); val y1 = min(h, c.bottom - c.height() * 3 / 10)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
        var y = y0; while (y < y1) { var x = x0; while (x < x1) {
            val p = px[y * w + x]; val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
            val cb = 128 - 0.1687f * r - 0.3313f * g + 0.5f * b; val cr = 128 + 0.5f * r - 0.4187f * g - 0.0813f * b
            if (cb in 77f..127f && cr in 133f..173f && r > 40) { sr += r; sg += g; sb += b; n++ }
            x += 2 }; y += 2 }
        return if (n < 200) null else floatArrayOf((sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat())
    }
    /** Cena "neutra" para aprender a pele: cor média da imagem sem dominante forte e brilho médio. */
    private fun cenaNeutra(px: IntArray): Boolean {
        var sr = 0L; var sg = 0L; var sb = 0L; var i = 0
        while (i < px.size) { val p = px[i]; sr += p shr 16 and 255; sg += p shr 8 and 255; sb += p and 255; i += 7 }
        val n = (px.size + 6) / 7; val r = sr / n; val g = sg / n; val b = sb / n; val y = (r * 54 + g * 183 + b * 19) / 256
        return y in 60..200 && kotlin.math.abs(r - b) < 45 && kotlin.math.abs(r - g) < 45
    }

    // ---------- pipeline ----------
    /**
     * Na imagem CRUA (antes de embelezador/filtro): detecta, reconhece, cadastra quem é novo, aprende a pele em cena
     * neutra e corrige a pele de quem tem referência (40% da diferença de croma, só na área do rosto e em tom de pele).
     * Modifica o bitmap no lugar quando corrige. Devolve o que achou.
     */
    suspend fun processar(ctx: Context, b: Bitmap): Resultado {
        if (!ligado) return Resultado(emptyList(), 0f)
        val rostos = Rostos.detectar(b, 1000).filter { it.caixa.width() >= 70 && it.caixa.height() >= 70 }
        if (rostos.isEmpty()) return Resultado(emptyList(), 0f)
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val neutra = cenaNeutra(px)
        val saida = ArrayList<Reconhecido>(); var correcao = 0f; var mudou = false
        trava.withLock {
            val lista = carrega(ctx)
            for (r in rostos) {
                val v = embedding(ctx, b, r) ?: continue
                var melhor: Pessoa? = null; var melhorSim = -1f
                for (p in lista) { val s = p.vetores.maxOfOrNull { sim(it, v) } ?: -1f; if (s > melhorSim) { melhorSim = s; melhor = p } }
                val peleAgora = peleDoRosto(px, w, h, r.caixa)
                when {
                    melhor != null && melhorSim >= LIMIAR_CASA -> {
                        val p = melhor; p.fotos++
                        if (p.vetores.size < MAX_VETORES && melhorSim < 0.9f) p.vetores += v      // guarda só o que acrescenta variedade
                        if (neutra && peleAgora != null) { val ref = p.pele
                            p.pele = if (ref == null) peleAgora else FloatArray(3) { ref[it] + (peleAgora[it] - ref[it]) / (p.nPele + 1).coerceAtMost(8) }; p.nPele++ }
                        else if (peleAgora != null && p.pele != null) correcao = max(correcao, corrigePele(px, w, h, r.caixa, peleAgora, p.pele!!))
                        saida += Reconhecido(p, r, melhorSim, false)
                    }
                    melhorSim < LIMIAR_NOVO || lista.isEmpty() -> {
                        val p = Pessoa(UUID.randomUUID().toString().take(8), "Pessoa ${lista.size + 1}", mutableListOf(v), if (neutra) peleAgora else null, if (neutra && peleAgora != null) 1 else 0, 1, System.currentTimeMillis())
                        lista += p; guardaMiniatura(ctx, b, r, p.id)
                        saida += Reconhecido(p, r, melhorSim, true)
                    }
                    else -> saida += Reconhecido(null, r, melhorSim, false)    // faixa ambígua: não cadastra nem casa
                }
            }
            salva(ctx, lista)
        }
        if (correcao > 0f) { b.setPixels(px, 0, w, 0, 0, w, h); mudou = true }
        return Resultado(saida, if (mudou) correcao else 0f)
    }

    /** Puxa a pele da caixa (expandida 1,4x) na direção da referência: 40% da diferença de croma, só em tom de pele, com borda suave. */
    private fun corrigePele(px: IntArray, w: Int, h: Int, c: Rect, agora: FloatArray, ref: FloatArray): Float {
        fun cbcr(r: Float, g: Float, b: Float) = floatArrayOf(128 - 0.1687f * r - 0.3313f * g + 0.5f * b, 128 + 0.5f * r - 0.4187f * g - 0.0813f * b)
        val a = cbcr(agora[0], agora[1], agora[2]); val f = cbcr(ref[0], ref[1], ref[2])
        val dCb = (f[0] - a[0]) * MISTURA_PELE; val dCr = (f[1] - a[1]) * MISTURA_PELE
        val forca = sqrt(dCb * dCb + dCr * dCr)
        if (forca < 1f) return 0f
        val ex = (c.width() * 0.2f).toInt(); val ey = (c.height() * 0.3f).toInt()
        val x0 = max(0, c.left - ex); val x1 = min(w, c.right + ex); val y0 = max(0, c.top - ey); val y1 = min(h, c.bottom + ey)
        for (y in y0 until y1) for (x in x0 until x1) {
            val k = y * w + x; val p = px[k]; val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
            val cb = 128 - 0.1687f * r - 0.3313f * g + 0.5f * b; val cr = 128 + 0.5f * r - 0.4187f * g - 0.0813f * b
            if (cb !in 77f..127f || cr !in 133f..173f || r < 40) continue
            // borda suave: peso cai a zero na margem da área
            val mx = min(x - x0, x1 - 1 - x).toFloat() / max(1, ex); val my = min(y - y0, y1 - 1 - y).toFloat() / max(1, ey)
            val peso = min(1f, min(mx, my))
            val ncb = cb + dCb * peso; val ncr = cr + dCr * peso
            val yv = 0.299f * r + 0.587f * g + 0.114f * b
            val rr = yv + 1.402f * (ncr - 128); val gg = yv - 0.344f * (ncb - 128) - 0.714f * (ncr - 128); val bb = yv + 1.772f * (ncb - 128)
            px[k] = (0xFF shl 24) or (rr.toInt().coerceIn(0, 255) shl 16) or (gg.toInt().coerceIn(0, 255) shl 8) or bb.toInt().coerceIn(0, 255)
        }
        return forca
    }

    private fun guardaMiniatura(ctx: Context, b: Bitmap, r: Rostos.Rosto, id: String) {
        runCatching {
            val lado = (max(r.caixa.width(), r.caixa.height()) * 1.3f).toInt()
            val x = (r.caixa.exactCenterX() - lado / 2).toInt().coerceIn(0, max(0, b.width - lado)); val y = (r.caixa.exactCenterY() - lado / 2).toInt().coerceIn(0, max(0, b.height - lado))
            val rec = Bitmap.createBitmap(b, x, y, min(lado, b.width - x), min(lado, b.height - y))
            val peq = Bitmap.createScaledBitmap(rec, 160, 160, true); if (peq !== rec) rec.recycle()
            miniatura(ctx, id).outputStream().use { peq.compress(Bitmap.CompressFormat.JPEG, 85, it) }; peq.recycle()
        }
    }
}
