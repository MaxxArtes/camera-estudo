package br.maxymus.galeriaestudo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * Motor "Alta" do recorte: ISNet (DIS, Apache-2.0) em int8 dinâmico — 46 MB, IoU 0,998 contra o fp32 medido na bancada
 * (galeria/medicao, 20/09). Baixado sob demanda do R2 para filesDir/modelos com sha256 conferido; a sessão ORT fica em
 * cache. Entrada 1024x1024 NCHW com (x/255 − 0,5), saída [1,1,1024,1024] normalizada min-max (mesmo pré/pós do rembg).
 * Alguns segundos por foto no aparelho. Bloqueante: chamar fora da thread principal.
 */
object IsnetOnnx {
    const val URL_MODELO = "https://pub-520120b0b03b4d3f8c94c5c9ba10d569.r2.dev/galeria-estudo/modelos/isnet-general-use-dyn8.onnx"
    const val SHA256 = "f1b1c6f7656e532627697afc989d953be1e7ef8f55a718f3611e8c9fd50cdef7"
    const val BYTES = 46_360_717L
    const val MB = 46
    private const val N = 1024
    private const val ARQUIVO = "isnet-general-use-dyn8.onnx"

    sealed class Estado {
        object Ausente : Estado()
        data class Baixando(val fracao: Float) : Estado()
        object Pronto : Estado()
        data class Erro(val msg: String) : Estado()
    }
    private val _estado = MutableStateFlow<Estado>(Estado.Ausente)
    val estado: StateFlow<Estado> = _estado
    @Volatile var ultimoMs = -1L
    @Volatile var ultimoErro: String? = null
    private var sessao: OrtSession? = null
    private val travaBaixa = Any()
    @Volatile private var cancelado = false

    /** Interrompe o download em andamento (o laço de leitura confere a cada bloco). */
    fun cancelar() { cancelado = true }

    private fun arquivo(ctx: Context) = File(File(ctx.filesDir, "modelos"), ARQUIVO)

    /** Confere no disco e alinha o estado. True se o modelo está pronto para uso. */
    fun conferir(ctx: Context): Boolean {
        val ok = arquivo(ctx).let { it.isFile && it.length() == BYTES }
        val e = _estado.value
        if (ok && e !is Estado.Pronto) _estado.value = Estado.Pronto
        else if (!ok && e is Estado.Pronto) _estado.value = Estado.Ausente
        return ok
    }

    /** Baixa com progresso e confere o sha256. Bloqueante (IO). True se o modelo ficou pronto. */
    fun baixar(ctx: Context): Boolean = synchronized(travaBaixa) {
        if (conferir(ctx)) return true
        cancelado = false
        val destino = arquivo(ctx); destino.parentFile?.mkdirs()
        val parcial = File(destino.path + ".part")
        try {
            _estado.value = Estado.Baixando(0f)
            val con = (URL(URL_MODELO).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
            if (con.responseCode != 200) error("HTTP ${con.responseCode}")
            val total = con.contentLengthLong.takeIf { it > 0 } ?: BYTES
            val md = MessageDigest.getInstance("SHA-256")
            var lidos = 0L; var ultimoAviso = 0L
            con.inputStream.use { ent -> parcial.outputStream().use { sai ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ent.read(buf); if (n < 0) break
                    sai.write(buf, 0, n); md.update(buf, 0, n); lidos += n
                    if (cancelado) error("cancelado")
                    if (lidos - ultimoAviso > 512 * 1024) { ultimoAviso = lidos; _estado.value = Estado.Baixando((lidos.toFloat() / total).coerceIn(0f, 0.99f)) }
                }
            } }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != SHA256 || lidos != BYTES) error("arquivo veio corrompido ($lidos bytes)")
            if (!parcial.renameTo(destino)) error("não consegui gravar o modelo")
            _estado.value = Estado.Pronto
            Telemetria.evento("modelo_baixado", mapOf("modelo" to ARQUIVO, "bytes" to lidos))
            true
        } catch (e: Exception) {
            parcial.delete()
            if (cancelado) { cancelado = false; _estado.value = Estado.Ausente; return false }
            val msg = (e.message ?: e::class.java.simpleName).take(120)
            _estado.value = Estado.Erro(msg)
            Telemetria.evento("erro", mapOf("onde" to "modelo_baixar", "msg" to msg))
            false
        }
    }

    private fun sessao(ctx: Context): OrtSession {
        sessao?.let { return it }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return OrtEnvironment.getEnvironment().createSession(arquivo(ctx).path, opts).also { sessao = it }
    }

    class Resultado(val mapa: FloatArray, val w: Int, val h: Int)

    /** Máscara 1024x1024 (0..1) do assunto. Null se o modelo não está no aparelho ou se a inferência falhar. */
    fun segmentar(ctx: Context, b: Bitmap): Resultado? = runCatching {
        if (!conferir(ctx)) error("modelo ausente")
        val t = System.nanoTime()
        val peq = Bitmap.createScaledBitmap(b, N, N, true)
        val px = IntArray(N * N).also { peq.getPixels(it, 0, N, 0, 0, N, N) }; if (peq !== b) peq.recycle()
        val plano = N * N
        val entrada = FloatBuffer.allocate(3 * plano)
        for (k in 0 until plano) {
            val c = px[k]
            entrada.put(k, (c shr 16 and 255) / 255f - 0.5f)
            entrada.put(plano + k, (c shr 8 and 255) / 255f - 0.5f)
            entrada.put(2 * plano + k, (c and 255) / 255f - 0.5f)
        }
        val mapa = FloatArray(plano)
        synchronized(this) {
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, entrada, longArrayOf(1, 3, N.toLong(), N.toLong())).use { tensor ->
                sessao(ctx).run(mapOf("input_image" to tensor)).use { saida ->
                    val fb = (saida[0] as OnnxTensor).floatBuffer
                    fb.get(mapa, 0, plano)
                }
            }
        }
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in mapa) { if (v < mn) mn = v; if (v > mx) mx = v }
        val esc = if (mx > mn) 1f / (mx - mn) else 0f
        for (k in 0 until plano) mapa[k] = (mapa[k] - mn) * esc
        ultimoMs = (System.nanoTime() - t) / 1_000_000; ultimoErro = null
        Resultado(mapa, N, N)
    }.getOrElse { e -> ultimoErro = (e::class.java.simpleName + ": " + (e.message ?: "")).take(200); null }
}
