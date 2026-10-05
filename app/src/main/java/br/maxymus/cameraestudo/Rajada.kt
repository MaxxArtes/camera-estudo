package br.maxymus.cameraestudo

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Rajada de teste, fase A (0.80): mede o que uma rajada YUV entrega no aparelho real antes de qualquer fusão no app.
 *
 * A tela solta o CameraX, esta classe abre a câmera lógica "0" pela Camera2, espera AE e AF convergirem, trava os dois e
 * faz um captureBurst de QUADROS pedidos YUV_420_888 na maior resolução que o aparelho aceitar. Os quadros são copiados
 * para a memória na chegada (a câmera é liberada logo), a tela devolve o CameraX e tira a foto normal pelo caminho de
 * sempre, e só então tudo é gravado em files/rajada/.tmp_<id> e a pasta inteira é renomeada para o nome final: nada
 * parcial aparece na revisão.
 *
 * Privacidade: as fotos ficam no armazenamento privado do app, fora do backup (extracao_dados.xml, backup_regras.xml), e só
 * saem como .zip por toque do dono depois da revisão. A telemetria passa pelo esquema fechado do DoisSensores.
 */
object Rajada {
    const val QUADROS = 8
    private const val LOGICA = "0"
    private const val PASTA = "rajada"
    private const val PASTA_ZIP = "rajada_zip"
    private const val TMP = ".tmp_"
    private const val GUARDAR = 3                 // rajadas guardadas; em luz baixa (ruído comprime mal) cada uma passa de 100 MB
    private const val MAX_TENTATIVAS = 5          // resoluções tentadas, da maior para a menor
    private const val PRAZO_QUADROS_MS = 10_000L  // da chamada do captureBurst até o último quadro
    private const val NIVEL_PNG = 3               // deflate: sem perda em qualquer nível; 3 é o meio-termo entre tempo e tamanho

    /** Rajada em curso (uma por vez no processo). */
    @Volatile var ativa: Rodada? = null

    private val travaArquivos = Mutex()

    class Rodada internal constructor(val id: String) {
        @Volatile var cancelada = false
        @Volatile var etapa = "inicio"
        internal val fim = AtomicBoolean(false)
    }

    fun novaRodada(): Rodada = Rodada(java.util.UUID.randomUUID().toString().replace("-", "").take(6))

    fun cancelar(r: Rodada) { r.cancelada = true }

    /** ON_STOP: a câmera não fica capturando em segundo plano. Na gravação a câmera já voltou, e a rajada segue. */
    fun aoParar() { ativa?.let { if (it.etapa != "gravar") it.cancelada = true } }

    // ------------------------------------------------------------------ dados da captura

    /** Metadados de um quadro, lidos do TotalCaptureResult do pedido de ordem `ordem` na rajada. */
    class QuadroMeta(
        val ordem: Int, val ts: Long?, val exp: Long?, val iso: Int?, val dur: Long?, val ois: Int?, val nr: Int?, val edge: Int?,
        val ae: Int?, val af: Int?, val foco: Float?
    )

    /** Pixels de um quadro: Y inteiro e o croma com U (w/2 x h/2) em cima e V (w/2 x h/2) embaixo. */
    internal class QuadroPixels(val ts: Long, val chegadaNs: Long, val w: Int, val h: Int, val y: ByteArray, val uv: ByteArray)

    class Tentativa(val w: Int, val h: Int) { var desfecho = "nao_tentou" }

    /** O que a câmera declara, para o meta.json (inclusive o modo de resolução máxima, que não entra na rajada). */
    class InfoCamera(
        val nivel: Int?, val orientacao: Int?, val tamanhosYuv: List<Size>, val tamanhosYuvAltaRes: List<Size>,
        val maxResDeclara: Boolean, val maxResCapacidade: Boolean, val maxResMatriz: Size?,
        val maxResYuv: List<Size>, val maxResRaw: List<Size>, val maxResJpeg: List<Size>,
        val modosOis: List<Int>, val aeLockDisp: Boolean, val focoMin: Float?
    )

    class Travas {
        var aeConvergiu = false
        var afConvergiu = false
        var aeTravado = false
        var afFixo = false
        var focoDioptrias: Float? = null
        var ms3a: Long? = null
    }

    class Captura internal constructor(
        val resultado: String, val motivo: String?, val classe: String?, val etapa: String,
        val w: Int, val h: Int, internal val quadros: List<QuadroPixels>, val metas: List<QuadroMeta>,
        val msTotal: Long?, val fps: Double?, val tentativas: List<Tentativa>, val info: InfoCamera?, val travas: Travas
    )

    /** O que a tela mostra e o que vai para a telemetria. */
    class Resultado internal constructor(
        val rodada: String, val resultado: String, val classe: String?, val quadros: Int, val msTotal: Long?, val fps: Double?,
        val largura: Int?, val altura: Int?, val larguraMax: Int?, val alturaMax: Int?, val exp: Long?, val iso: Int?,
        val ois: Int?, val nr: Int?, val edge: Int?, val normal: Boolean, val pasta: File?
    ) {
        fun caiuResolucao(): Boolean = largura != null && larguraMax != null && (largura != larguraMax || altura != alturaMax)
    }

    // ------------------------------------------------------------------ captura (Camera2)

    /** Estado escrito pelos retornos da câmera (fio "rajada") e lido pela corrotina. */
    private class Estado {
        @Volatile var device: CameraDevice? = null
        @Volatile var respondeu = false          // onOpened, onError ou onDisconnected já veio
        @Volatile var desistiu = false           // a captura saiu: um onOpened tardio fecha a câmera na hora
        @Volatile var perdeu: String? = null
        @Volatile var fechou = false
        @Volatile var sessao: CameraCaptureSession? = null
        @Volatile var sessaoFalhou = false
        @Volatile var ae: Int? = null
        @Volatile var af: Int? = null
        @Volatile var foco: Float? = null
        @Volatile var falhas = 0
        @Volatile var falhaRazao: Int? = null
        @Volatile var erroCopia: String? = null
        val quadros = ArrayList<QuadroPixels>()
        val metas = ArrayList<QuadroMeta>()
    }

    private fun lerInfo(c: CameraCharacteristics): InfoCamera {
        val mapa = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuv = mapa?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty().sortedByDescending { it.width.toLong() * it.height }
        val alta = (mapa?.getHighResolutionOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()).sortedByDescending { it.width.toLong() * it.height }
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        var declara = false; var capacidade = false; var matriz: Size? = null
        var mYuv = emptyList<Size>(); var mRaw = emptyList<Size>(); var mJpeg = emptyList<Size>()
        if (Build.VERSION.SDK_INT >= 31) {
            capacidade = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps
            matriz = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            val mapaMax = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            fun tams(f: Int) = (mapaMax?.getOutputSizes(f)?.toList().orEmpty()).sortedByDescending { it.width.toLong() * it.height }
            mYuv = tams(ImageFormat.YUV_420_888); mRaw = tams(ImageFormat.RAW_SENSOR); mJpeg = tams(ImageFormat.JPEG)
            declara = capacidade || mapaMax != null || CaptureRequest.SENSOR_PIXEL_MODE in c.availableCaptureRequestKeys
        }
        return InfoCamera(
            nivel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            orientacao = c.get(CameraCharacteristics.SENSOR_ORIENTATION),
            tamanhosYuv = yuv, tamanhosYuvAltaRes = alta,
            maxResDeclara = declara, maxResCapacidade = capacidade, maxResMatriz = matriz, maxResYuv = mYuv, maxResRaw = mRaw, maxResJpeg = mJpeg,
            modosOis = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty(),
            aeLockDisp = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            focoMin = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        )
    }

    private fun aeOk(ae: Int?) = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED ||
        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED

    private fun afOk(af: Int?) = af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

    /** Copia Y inteiro e U/V (com rowStride e pixelStride do aparelho) para a memória, para devolver o buffer à câmera já. */
    private fun copiar(img: Image, chegadaNs: Long): QuadroPixels {
        val w = img.width; val h = img.height
        val y = ByteArray(w * h)
        val py = img.planes[0]; val yb = py.buffer.duplicate(); val rsY = py.rowStride; val psY = py.pixelStride
        if (psY == 1) for (row in 0 until h) { yb.position(row * rsY); yb.get(y, row * w, w) }
        else for (row in 0 until h) for (col in 0 until w) y[row * w + col] = yb.get(row * rsY + col * psY)
        val cw = w / 2; val ch = h / 2
        val uv = ByteArray(cw * ch * 2)
        fun plano(p: Image.Plane, base: Int) {
            val b = p.buffer.duplicate(); val rs = p.rowStride; val ps = p.pixelStride
            val linha = ByteArray(rs)
            for (row in 0 until ch) {
                val o = row * rs
                val n = min(rs, b.limit() - o)
                if (n <= 0) break
                b.position(o); b.get(linha, 0, n)
                if (ps == 1) System.arraycopy(linha, 0, uv, base + row * cw, min(cw, n))
                else for (col in 0 until cw) { val k = col * ps; if (k < n) uv[base + row * cw + col] = linha[k] }
            }
        }
        plano(img.planes[1], 0)
        plano(img.planes[2], cw * ch)
        return QuadroPixels(img.timestamp, chegadaNs, w, h, y, uv)
    }

    private suspend fun esperar(maxMs: Long, r: Rodada, e: Estado, cond: () -> Boolean): Boolean {
        val fim = SystemClock.elapsedRealtime() + maxMs
        while (SystemClock.elapsedRealtime() < fim) {
            if (cond()) return true
            if (r.cancelada || e.perdeu != null) return false
            delay(10)
        }
        return cond()
    }

    /**
     * Abre a "0", trava 3A e faz a rajada, da maior resolução YUV para baixo até uma ser aceita. Fecha a câmera e espera o
     * onClosed (até 3 s) antes de voltar, em qualquer saída, cancelamento da corrotina inclusive. Rodar fora da Main.
     */
    @Suppress("DEPRECATION")
    suspend fun capturar(ctx: Context, r: Rodada, aoFase: (String) -> Unit): Captura {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val fio = HandlerThread("rajada").apply { start() }
        val h = Handler(fio.looper)
        val e = Estado()
        val travas = Travas()
        val tentativas = ArrayList<Tentativa>()
        val leitores = ArrayList<ImageReader>()
        var info: InfoCamera? = null
        var pediuAbertura = false
        fun fim(res: String, motivo: String?, classe: String? = null, w: Int = 0, hh: Int = 0, ms: Long? = null, fps: Double? = null): Captura {
            val qs = synchronized(e.quadros) { ArrayList(e.quadros) }
            val ms2 = synchronized(e.metas) { e.metas.sortedBy { it.ordem } }
            return Captura(res, motivo, classe, r.etapa, w, hh, if (res == "ok") qs else emptyList(), ms2, ms, fps, tentativas, info, travas)
        }
        try {
            r.etapa = "caracteristicas"
            val c = cm.getCameraCharacteristics(LOGICA)
            val inf = lerInfo(c)
            info = inf
            val candidatos = inf.tamanhosYuv.take(MAX_TENTATIVAS)
            if (candidatos.isEmpty()) return fim("recusou_resolucao", "sem_yuv")
            // fluxo pequeno para o 3A rodar antes da rajada: PRIV/YUV pequeno + YUV máximo é combinação garantida
            val pequeno = inf.tamanhosYuv.filter { it.width.toLong() * it.height <= 1280L * 960 }.maxByOrNull { it.width.toLong() * it.height }
                ?: inf.tamanhosYuv.last()
            val afModos = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            val focoManual = (inf.focoMin ?: 0f) > 0f && CaptureRequest.CONTROL_AF_MODE_OFF in afModos
            val afContinuo = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModos

            r.etapa = "abrir"; aoFase("Abrindo a câmera.")
            if (r.cancelada) return fim("cancelado", null)
            cm.openCamera(LOGICA, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val fechar = synchronized(e) { e.respondeu = true; if (r.cancelada || e.desistiu) true else { e.device = camera; false } }
                    if (fechar) camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) { e.perdeu = "desconectada"; e.respondeu = true; camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { e.perdeu = "erro_$error"; e.respondeu = true; camera.close() }
                override fun onClosed(camera: CameraDevice) { e.fechou = true }
            }, h)
            pediuAbertura = true
            if (!esperar(3_000, r, e) { e.device != null }) {
                return if (r.cancelada) fim("cancelado", null) else fim("perdeu_camera", e.perdeu ?: "abrir_prazo")
            }
            val dev = e.device!!

            val prev = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    e.ae = result.get(CaptureResult.CONTROL_AE_STATE); e.af = result.get(CaptureResult.CONTROL_AF_STATE)
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { e.foco = it }
                }
            }
            val rajadaCb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val m = QuadroMeta(
                        ordem = request.tag as? Int ?: -1,
                        ts = result.get(CaptureResult.SENSOR_TIMESTAMP), exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY), dur = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                        ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE), nr = result.get(CaptureResult.NOISE_REDUCTION_MODE),
                        edge = result.get(CaptureResult.EDGE_MODE), ae = result.get(CaptureResult.CONTROL_AE_STATE),
                        af = result.get(CaptureResult.CONTROL_AF_STATE), foco = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    )
                    synchronized(e.metas) { e.metas += m }
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    e.falhas++; e.falhaRazao = failure.reason
                }
            }

            for (tam in candidatos) {
                if (r.cancelada) return fim("cancelado", null)
                if (e.perdeu != null) return fim("perdeu_camera", e.perdeu)
                val t = Tentativa(tam.width, tam.height).also { tentativas += it }
                synchronized(e.quadros) { e.quadros.clear() }
                synchronized(e.metas) { e.metas.clear() }
                e.falhas = 0; e.falhaRazao = null; e.erroCopia = null; e.sessao = null; e.sessaoFalhou = false
                r.etapa = "sessao"; aoFase("Preparando ${tam.width}x${tam.height}.")
                val grande = ImageReader.newInstance(tam.width, tam.height, ImageFormat.YUV_420_888, QUADROS).also { leitores += it }
                val peq = ImageReader.newInstance(pequeno.width, pequeno.height, ImageFormat.YUV_420_888, 2).also { leitores += it }
                grande.setOnImageAvailableListener({ rd ->
                    val chegada = SystemClock.elapsedRealtimeNanos()
                    try {
                        rd.acquireNextImage()?.let { img -> try { val q = copiar(img, chegada); synchronized(e.quadros) { e.quadros += q } } finally { img.close() } }
                    } catch (x: Throwable) { e.erroCopia = x.javaClass.simpleName }
                }, h)
                peq.setOnImageAvailableListener({ rd -> try { rd.acquireLatestImage()?.close() } catch (x: Exception) { } }, h)
                val alvos: List<Surface> = listOf(peq.surface, grande.surface)
                try {
                    dev.createCaptureSession(alvos, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) { e.sessao = session }
                        override fun onConfigureFailed(session: CameraCaptureSession) { e.sessaoFalhou = true }
                    }, h)
                } catch (x: IllegalArgumentException) { t.desfecho = "sessao:${x.javaClass.simpleName}"; continue }
                esperar(3_000, r, e) { e.sessao != null || e.sessaoFalhou }
                val sess = e.sessao
                if (sess == null) {
                    if (r.cancelada) return fim("cancelado", null)
                    if (e.perdeu != null) return fim("perdeu_camera", e.perdeu)
                    t.desfecho = if (e.sessaoFalhou) "configure_failed" else "sessao_prazo"
                    continue
                }

                // 3A: converge com o fluxo pequeno, depois trava AE (CONTROL_AE_LOCK) e fixa o foco onde o AF parou
                r.etapa = "3a"; aoFase("Medindo luz e foco.")
                val t3a = SystemClock.elapsedRealtime()
                val pv = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                pv.addTarget(peq.surface)
                pv.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                pv.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (afContinuo) pv.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                e.ae = null; e.af = null
                sess.setRepeatingRequest(pv.build(), prev, h)
                travas.aeConvergiu = esperar(4_000, r, e) { aeOk(e.ae) && (!afContinuo || afOk(e.af)) } || aeOk(e.ae)
                travas.afConvergiu = !afContinuo || afOk(e.af)
                if (r.cancelada) return fim("cancelado", null)
                if (e.perdeu != null) return fim("perdeu_camera", e.perdeu)
                val focoLido = e.foco
                val foco: Float? = if (focoManual && focoLido != null) focoLido else null
                if (inf.aeLockDisp) pv.set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (foco != null) {
                    pv.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    pv.set(CaptureRequest.LENS_FOCUS_DISTANCE, foco)
                    travas.afFixo = true; travas.focoDioptrias = foco
                }
                sess.setRepeatingRequest(pv.build(), prev, h)
                if (inf.aeLockDisp) travas.aeTravado = esperar(1_500, r, e) { e.ae == CaptureResult.CONTROL_AE_STATE_LOCKED }
                travas.ms3a = SystemClock.elapsedRealtime() - t3a
                if (r.cancelada) return fim("cancelado", null)
                if (e.perdeu != null) return fim("perdeu_camera", e.perdeu)

                // a rajada: os mesmos controles nos QUADROS pedidos; exposição e ISO ficam com o AE travado
                r.etapa = "rajada"; aoFase("Rajada de $QUADROS fotos.")
                val pedidos = (0 until QUADROS).map { i ->
                    dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(grande.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        if (inf.aeLockDisp) set(CaptureRequest.CONTROL_AE_LOCK, true)
                        if (foco != null) {
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, foco)
                        } else if (afContinuo) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        setTag(i)
                    }.build()
                }
                val tBurst = SystemClock.elapsedRealtimeNanos()
                try { sess.captureBurst(pedidos, rajadaCb, h) }
                catch (x: IllegalArgumentException) { t.desfecho = "requisicao:${x.javaClass.simpleName}"; fecharSessao(sess); continue }
                esperar(PRAZO_QUADROS_MS, r, e) {
                    val nq = synchronized(e.quadros) { e.quadros.size }
                    val nm = synchronized(e.metas) { e.metas.size }
                    (nq >= QUADROS && nm >= QUADROS) || e.falhas > 0 || e.erroCopia != null
                }
                if (r.cancelada) return fim("cancelado", null)
                if (e.perdeu != null) return fim("perdeu_camera", e.perdeu)
                e.erroCopia?.let { cl -> t.desfecho = "copia:$cl"; return fim("erro", "copia", cl, tam.width, tam.height) }
                val nq = synchronized(e.quadros) { e.quadros.size }
                val nm = synchronized(e.metas) { e.metas.size }
                if (nq >= QUADROS && nm >= QUADROS && e.falhas == 0) {
                    t.desfecho = "ok"
                    val qs = synchronized(e.quadros) { e.quadros.sortedBy { it.ts } }
                    val msTotal = (qs.maxOf { it.chegadaNs } - tBurst) / 1_000_000
                    val tsMetas = synchronized(e.metas) { e.metas.mapNotNull { it.ts } }.sorted()
                    val fps = if (tsMetas.size >= 2 && tsMetas.last() > tsMetas.first()) (tsMetas.size - 1) * 1e9 / (tsMetas.last() - tsMetas.first()) else null
                    try { sess.stopRepeating() } catch (x: Exception) { }
                    return fim("ok", null, null, tam.width, tam.height, msTotal, fps)
                }
                // esta resolução não entregou: anota, solta a sessão e tenta a próxima
                t.desfecho = if (e.falhas > 0) "falhas:${e.falhas}:${e.falhaRazao}" else "incompleto:$nq:$nm"
                fecharSessao(sess)
            }
            return fim("recusou_resolucao", tentativas.lastOrNull()?.desfecho)
        } catch (x: CancellationException) {
            throw x   // é IllegalStateException: sem esta linha o cancelamento da corrotina viraria "erro"
        } catch (x: CameraAccessException) {
            return if (e.perdeu != null || r.cancelada) fim(if (r.cancelada) "cancelado" else "perdeu_camera", e.perdeu)
            else fim("erro", "acesso_${x.reason}", x.javaClass.simpleName)
        } catch (x: IllegalStateException) {
            // sessão ou câmera fechada no meio (cancelamento, perda): a causa real está no estado
            return when {
                r.cancelada -> fim("cancelado", null)
                e.perdeu != null -> fim("perdeu_camera", e.perdeu)
                else -> fim("erro", null, x.javaClass.simpleName)
            }
        } catch (x: SecurityException) {
            return fim("erro", null, x.javaClass.simpleName)
        } catch (x: OutOfMemoryError) {
            synchronized(e.quadros) { e.quadros.clear() }
            return fim("erro", "memoria", x.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                val d = synchronized(e) { e.desistiu = true; e.device }
                val t0 = SystemClock.elapsedRealtime()
                if (d != null) try { d.close() } catch (x: Exception) { }
                // abertura sem resposta ainda: o fio fica vivo um pouco para o onOpened tardio fechar a câmera
                else if (pediuAbertura) while (!e.respondeu && SystemClock.elapsedRealtime() - t0 < 2_000) delay(10)
                if (d != null || e.respondeu) while (!e.fechou && SystemClock.elapsedRealtime() - t0 < 3_000) delay(10)
                // leitores só depois do device: fechar a superfície com a sessão viva gera erro de buffer no HAL
                for (l in leitores) try { l.close() } catch (x: Exception) { }
                fio.quitSafely()
            }
        }
    }

    private fun fecharSessao(s: CameraCaptureSession) {
        try { s.abortCaptures() } catch (x: Exception) { }
        try { s.close() } catch (x: Exception) { }
    }

    // ------------------------------------------------------------------ resultado e telemetria

    /** Telemetria do fim, uma vez por rodada, pelo esquema fechado do DoisSensores (só números e códigos). */
    private fun emitir(r: Rodada, res: Resultado, cap: Captura?, msGravar: Long?) {
        if (!r.fim.compareAndSet(false, true)) return
        DoisSensores.ev("rajada_teste", linkedMapOf(
            "rodada" to r.id, "resultado" to res.resultado, "classe" to res.classe, "etapa" to (cap?.etapa ?: r.etapa),
            "quadros" to res.quadros, "ms_total" to res.msTotal, "fps" to res.fps, "largura" to res.largura, "altura" to res.altura,
            "largura_max" to res.larguraMax, "altura_max" to res.alturaMax, "tentativas" to cap?.tentativas?.size,
            "exp_ns" to res.exp, "iso" to res.iso, "ois" to res.ois, "nr" to res.nr, "edge" to res.edge,
            "ae_travado" to cap?.travas?.aeTravado, "af_fixo" to cap?.travas?.afFixo, "max_res_disp" to cap?.info?.maxResDeclara,
            "normal" to res.normal, "gravou" to (res.pasta != null), "ms_gravar" to msGravar
        ))
    }

    private fun resultadoDe(r: Rodada, resultado: String, classe: String?, cap: Captura?, normal: Boolean, pasta: File?): Resultado {
        val m0 = cap?.metas?.firstOrNull()
        val maior = cap?.info?.tamanhosYuv?.firstOrNull()
        val ok = resultado == "ok"
        return Resultado(
            r.id, resultado, classe, if (ok) cap?.quadros?.size ?: 0 else 0, if (ok) cap?.msTotal else null, if (ok) cap?.fps else null,
            if (ok) cap?.w else null, if (ok) cap?.h else null, maior?.width, maior?.height,
            m0?.exp, m0?.iso, m0?.ois, m0?.nr, m0?.edge, normal, pasta
        )
    }

    /** Saída sem quadros para gravar (recusa, perda, cancelamento, erro): emite e devolve o resultado. */
    fun semQuadros(r: Rodada, cap: Captura): Resultado =
        resultadoDe(r, cap.resultado, cap.classe, cap, false, null).also { emitir(r, it, cap, null) }

    fun cancelada(r: Rodada, cap: Captura?): Resultado =
        resultadoDe(r, "cancelado", null, cap, false, null).also { emitir(r, it, cap, null) }

    fun falhou(r: Rodada, cap: Captura?, x: Throwable): Resultado =
        resultadoDe(r, "erro", x.javaClass.simpleName, cap, false, null).also { emitir(r, it, cap, null) }

    // ------------------------------------------------------------------ arquivos

    private fun raiz(ctx: Context) = File(ctx.filesDir, PASTA)
    private fun raizZip(ctx: Context) = File(ctx.filesDir, PASTA_ZIP)

    /** Pasta temporária da rodada; a foto normal é gravada nela pelo CameraX antes dos quadros. Rodar em IO. */
    suspend fun prepararTemporaria(ctx: Context, r: Rodada): File = travaArquivos.withLock {
        limparTemporariasSemTrava(ctx, r.id)
        val t = File(raiz(ctx), TMP + r.id)
        t.deleteRecursively()
        if (!t.mkdirs()) throw java.io.IOException("mkdirs")
        t
    }

    /** Apaga temporárias órfãs (gravação que o processo não terminou), menos a da rodada viva. */
    suspend fun limparTemporarias(ctx: Context) { travaArquivos.withLock { limparTemporariasSemTrava(ctx, ativa?.id) } }

    private fun limparTemporariasSemTrava(ctx: Context, viva: String?) {
        raiz(ctx).listFiles()?.filter { it.isDirectory && it.name.startsWith(TMP) && it.name != TMP + viva }?.forEach {
            try { it.deleteRecursively() } catch (x: Exception) { }
        }
    }

    /** Descarta a temporária de uma rodada que não chegou ao fim. */
    suspend fun descartar(ctx: Context, r: Rodada) {
        travaArquivos.withLock { try { File(raiz(ctx), TMP + r.id).deleteRecursively() } catch (x: Exception) { } }
    }

    private fun tamanhos(l: List<Size>) = JSONArray(l.map { "${it.width}x${it.height}" })

    private fun JSONObject.poe(k: String, v: Any?): JSONObject {
        put(k, when (v) {
            null -> JSONObject.NULL
            is Double -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v
            is Float -> if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v.toDouble()
            else -> v
        })
        return this
    }

    /**
     * Grava os PNG (Y e croma), o meta.json e renomeia a temporária para files/rajada/<data>_<id>. Os quadros são codificados
     * em paralelo (2 de cada vez: cada um segura ~25 MB a mais enquanto comprime). Devolve o resultado final e já emite a telemetria. Rodar em IO.
     */
    suspend fun gravar(ctx: Context, r: Rodada, cap: Captura, tmp: File, normalOk: Boolean, aoQuadro: (Int) -> Unit): Resultado {
        r.etapa = "gravar"
        val t0 = SystemClock.elapsedRealtime()
        var pasta: File? = null
        var resultado = "ok"
        var classe: String? = null
        try {
            val metas = cap.metas
            // cada quadro vai para a ordem do pedido cujo SENSOR_TIMESTAMP bate com o da imagem; sem casamento, a ordem de chegada
            val porTs = cap.quadros.associateBy { it.ts }
            val ordenados = cap.quadros.sortedBy { it.ts }
            val usados = HashSet<Long>()
            val pares = (0 until cap.quadros.size).map { i ->
                val m = metas.firstOrNull { it.ordem == i }
                val q = m?.ts?.let { porTs[it] }?.takeIf { usados.add(it.ts) } ?: ordenados.first { usados.add(it.ts) }
                Triple(i, q, m)
            }
            var feitos = 0
            coroutineScope {
                pares.chunked(2).forEach { lote ->
                    lote.map { (i, q, _) ->
                        async(Dispatchers.Default) {
                            Png.cinza8(File(tmp, "y_$i.png"), q.y, q.w, q.h, NIVEL_PNG)
                            Png.cinza8(File(tmp, "uv_$i.png"), q.uv, q.w / 2, q.h, NIVEL_PNG)
                            synchronized(usados) { feitos++; aoQuadro(feitos) }
                        }
                    }.awaitAll()
                    if (r.cancelada) throw Cancelada()
                }
            }
            val normal = File(tmp, "normal.jpg")
            val temNormal = normalOk && normal.isFile && normal.length() > 0
            if (!temNormal) normal.delete()
            File(tmp, "meta.json").writeText(meta(r, cap, pares, temNormal).toString(2))
            if (r.cancelada) throw Cancelada()
            travaArquivos.withLock {
                val nome = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + r.id
                val destino = File(raiz(ctx), nome)
                if (!tmp.renameTo(destino)) throw java.io.IOException("renomear")
                pasta = destino
                podar(ctx)
            }
            return resultadoDe(r, "ok", null, cap, temNormal, pasta).also { emitir(r, it, cap, SystemClock.elapsedRealtime() - t0) }
        } catch (x: CancellationException) {
            throw x
        } catch (x: Cancelada) {
            resultado = "cancelado"
        } catch (x: Exception) {
            resultado = "erro"; classe = x.javaClass.simpleName
        } catch (x: OutOfMemoryError) {
            resultado = "erro"; classe = x.javaClass.simpleName
        } finally {
            if (pasta == null) withContext(NonCancellable) { descartar(ctx, r) }
        }
        return resultadoDe(r, resultado, classe, cap, false, null).also { emitir(r, it, cap, SystemClock.elapsedRealtime() - t0) }
    }

    private class Cancelada : Exception()

    private fun meta(r: Rodada, cap: Captura, pares: List<Triple<Int, QuadroPixels, QuadroMeta?>>, temNormal: Boolean): JSONObject {
        val inf = cap.info
        val tsOrd = pares.mapNotNull { it.third?.ts }.sorted()
        val intervalos = tsOrd.zipWithNext { a, b -> (b - a) / 1e6 }
        val quadros = JSONArray()
        for ((i, q, m) in pares) quadros.put(JSONObject()
            .poe("ordem", i).poe("arquivo_y", "y_$i.png").poe("arquivo_uv", "uv_$i.png")
            .poe("largura", q.w).poe("altura", q.h).poe("carimbo_imagem_ns", q.ts)
            .poe("sensor_timestamp_ns", m?.ts).poe("sensor_exposure_time_ns", m?.exp).poe("sensor_sensitivity", m?.iso)
            .poe("sensor_frame_duration_ns", m?.dur).poe("lens_optical_stabilization_mode", m?.ois)
            .poe("noise_reduction_mode", m?.nr).poe("edge_mode", m?.edge)
            .poe("ae_estado", m?.ae).poe("af_estado", m?.af).poe("foco_dioptrias", m?.foco?.toDouble()))
        val maxRes = JSONObject()
            .poe("declara", inf?.maxResDeclara).poe("capacidade_ultra_high_resolution", inf?.maxResCapacidade)
            .poe("matriz_pixels", inf?.maxResMatriz?.let { "${it.width}x${it.height}" })
            .poe("tamanhos_yuv", inf?.let { tamanhos(it.maxResYuv) }).poe("tamanhos_raw", inf?.let { tamanhos(it.maxResRaw) })
            .poe("tamanhos_jpeg", inf?.let { tamanhos(it.maxResJpeg) })
            .poe("observacao", "modo SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION so registrado; a rajada usa o modo padrao")
        val tent = JSONArray()
        for (t in cap.tentativas) tent.put(JSONObject().poe("largura", t.w).poe("altura", t.h).poe("desfecho", t.desfecho))
        val tv = cap.travas
        val expIguais = pares.mapNotNull { it.third?.exp }.distinct().size <= 1
        val isoIguais = pares.mapNotNull { it.third?.iso }.distinct().size <= 1
        return JSONObject()
            .poe("formato_meta", 1).poe("app", "camera-estudo").poe("versao_app", BuildConfig.VERSION_NAME).poe("rodada", r.id)
            .poe("quando", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            .poe("modelo", Build.MANUFACTURER + " " + Build.MODEL).poe("android", Build.VERSION.SDK_INT)
            .poe("camera", LOGICA).poe("nivel_hardware", inf?.nivel).poe("orientacao_sensor", inf?.orientacao)
            .poe("formato", "YUV_420_888").poe("modelo_pedido", "TEMPLATE_STILL_CAPTURE").poe("quadros_pedidos", QUADROS)
            .poe("largura", cap.w).poe("altura", cap.h)
            .poe("y_png", "cinza 8 bits sem perda, largura x altura")
            .poe("uv_png", "cinza 8 bits sem perda, (largura/2) x altura: U nas primeiras altura/2 linhas, V nas ultimas altura/2")
            .poe("tentativas", tent)
            .poe("tamanhos_yuv", inf?.let { tamanhos(it.tamanhosYuv) }).poe("tamanhos_yuv_alta_resolucao", inf?.let { tamanhos(it.tamanhosYuvAltaRes) })
            .poe("resolucao_maxima", maxRes)
            .poe("modos_ois_disponiveis", inf?.let { JSONArray(it.modosOis) })
            .poe("ae_lock_disponivel", inf?.aeLockDisp).poe("foco_minimo_dioptrias", inf?.focoMin?.toDouble())
            .poe("ae_convergiu", tv.aeConvergiu).poe("af_convergiu", tv.afConvergiu).poe("ae_travado", tv.aeTravado)
            .poe("af_fixo", tv.afFixo).poe("foco_fixo_dioptrias", tv.focoDioptrias?.toDouble()).poe("ms_3a", tv.ms3a)
            .poe("exposicao_iguais", expIguais).poe("iso_iguais", isoIguais)
            .poe("ms_total", cap.msTotal).poe("fps_medido", cap.fps).poe("intervalos_ms", JSONArray(intervalos))
            .poe("ordem", "ordem = posicao do pedido no captureBurst; quadros casados pelo SENSOR_TIMESTAMP")
            .poe("quadros", quadros)
            .poe("normal", if (temNormal) "normal.jpg" else null)
    }

    /** Guarda as GUARDAR rajadas mais novas. Só chamar com a travaArquivos. */
    private fun podar(ctx: Context) {
        val pastas = raiz(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(TMP) }?.sortedByDescending { it.name } ?: return
        for (velha in pastas.drop(GUARDAR)) try { velha.deleteRecursively() } catch (x: Exception) { }
    }

    /** Rajada mais nova gravada (com meta.json). Rodar em IO. */
    fun ultimaPasta(ctx: Context): File? = try {
        raiz(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(TMP) && File(it, "meta.json").isFile }?.maxByOrNull { it.name }
    } catch (x: Exception) { null }

    /** "Apagar esta rajada": só filha direta de files/rajada pelo caminho canônico. Leva junto o .zip, se houver. */
    suspend fun apagar(ctx: Context, pasta: File): Boolean = travaArquivos.withLock {
        val ok = try {
            val p = pasta.canonicalFile
            p.parentFile == raiz(ctx).canonicalFile && !p.name.startsWith(TMP) && p.isDirectory && p.deleteRecursively()
        } catch (x: Exception) { false }
        try { raizZip(ctx).listFiles()?.forEach { it.delete() } } catch (x: Exception) { }
        if (!ok) DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to "apagar", "motivo" to "pasta_invalida"))
        ok
    }

    // ------------------------------------------------------------------ revisão e compartilhamento

    class Miniatura(val rotulo: String, val bitmap: Bitmap)

    class Pacote internal constructor(
        val pasta: File, val rodada: String, val quando: String, val miniaturas: List<Miniatura>, val anexos: List<File>, private val esperadas: Int
    ) {
        val bytes: Long = anexos.sumOf { it.length() }
        fun pronto(): Boolean = esperadas > 0 && miniaturas.size >= esperadas
        fun previaFalhou(): Boolean = esperadas > 0 && miniaturas.size < esperadas
        val listaExata: List<String> = anexos.map { a ->
            val b = a.length()
            a.name + " (" + (if (b >= 1048576L) "%.1f MB".format(b / 1048576.0) else "%d KB".format(maxOf(1L, b / 1024))) + ")"
        }
        fun descricao(): String = "Vai um arquivo .zip com estes ${anexos.size} arquivos. Total: %.1f MB.".format(bytes / 1048576.0)
    }

    private val NOME_ANEXO = Regex("^(y|uv)_[0-9]\\.png$|^meta\\.json$|^normal\\.jpg$")

    /**
     * Lista fechada: a pasta tem de ser filha direta de files/rajada pelo caminho canônico e não ser temporária; cada
     * arquivo tem de ser regular, ter um dos nomes que a rajada grava e estar nessa pasta também pelo caminho canônico.
     */
    private fun anexos(ctx: Context, pasta: File): List<File>? {
        val p = pasta.canonicalFile
        if (p.parentFile != raiz(ctx).canonicalFile || p.name.startsWith(TMP) || !p.isDirectory) return null
        val arquivos = p.listFiles()?.filter { it.isFile } ?: return null
        return arquivos.filter { NOME_ANEXO.matches(it.name) && it.canonicalFile == File(p, it.name) }.sortedBy { it.name }
    }

    /** Pacote para a revisão, com as miniaturas dos quadros Y e da foto normal decodificadas. Rodar em IO. */
    fun pacote(ctx: Context, pasta: File): Pacote? = try {
        val lista = anexos(ctx, pasta)
        if (lista == null || lista.none { it.name == "meta.json" }) {
            DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to "revisar", "motivo" to "pasta_invalida"))
            null
        } else {
            val imagens = lista.filter { it.name.startsWith("y_") }.sortedBy { it.name } + lista.filter { it.name == "normal.jpg" }
            val minis = imagens.mapNotNull { a ->
                decodifica(a, 320)?.let { Miniatura(if (a.name == "normal.jpg") "normal" else "Y " + a.name.removePrefix("y_").removeSuffix(".png"), it) }
            }
            val quando = try {
                SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(pasta.name.substringBeforeLast('_')) ?: Date(pasta.lastModified()))
            } catch (x: Exception) { "?" }
            Pacote(pasta, pasta.name.substringAfterLast('_'), quando, minis, lista, imagens.size)
        }
    } catch (x: Exception) {
        DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to "revisar", "classe" to x.javaClass.simpleName))
        null
    }

    private fun decodifica(arq: File, alvo: Int): Bitmap? {
        var intermediario: Bitmap? = null
        var devolvido: Bitmap? = null
        try {
            val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(arq.absolutePath, medidas)
            var amostra = 1
            while (max(medidas.outWidth, medidas.outHeight) / (amostra * 2) >= alvo) amostra *= 2
            val bmp = BitmapFactory.decodeFile(arq.absolutePath, BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return null
            intermediario = bmp
            val graus = if (arq.name.endsWith(".jpg")) when (ExifInterface(arq.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            } else 0f
            devolvido = if (graus == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(graus) }, true)
            return devolvido
        } catch (x: Exception) {
            return null
        } finally {
            val i = intermediario
            if (i != null && i !== devolvido) i.recycle()
        }
    }

    /**
     * Monta o .zip em files/rajada_zip (a única raiz do FileProvider para a rajada) com a MESMA lista que a revisão mostrou.
     * Lista diferente ou arquivo sumido: não monta. Só um .zip existe por vez. Rodar em IO.
     */
    suspend fun montarZip(ctx: Context, pc: Pacote): File? = travaArquivos.withLock {
        try {
            val agora = anexos(ctx, pc.pasta)
            if (agora == null || agora.map { it.name } != pc.anexos.map { it.name } || agora.any { !it.isFile }) {
                DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to "zip", "motivo" to (if (agora == null) "arquivo_sumiu" else "lista_mudou")))
                return@withLock null
            }
            val dir = raizZip(ctx)
            if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("mkdirs")
            dir.listFiles()?.forEach { it.delete() }
            val nome = "rajada_" + pc.pasta.name
            val tmp = File(dir, "$nome.parcial")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { z ->
                z.setLevel(Deflater.BEST_SPEED)   // PNG e JPEG já vêm comprimidos
                val buf = ByteArray(1 shl 16)
                for (a in agora) {
                    z.putNextEntry(ZipEntry(nome + "/" + a.name))
                    a.inputStream().use { inp -> while (true) { val n = inp.read(buf); if (n < 0) break; z.write(buf, 0, n) } }
                    z.closeEntry()
                }
            }
            val zip = File(dir, "$nome.zip")
            if (!tmp.renameTo(zip)) { tmp.delete(); throw java.io.IOException("renomear") }
            zip
        } catch (x: Exception) {
            DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to "zip", "classe" to x.javaClass.simpleName))
            null
        }
    }

    /** Envia o .zip pelo FileProvider, com concessão só de leitura. Só por toque do dono, depois da revisão. */
    fun compartilharZip(ctx: Context, zip: File, rodada: String, arquivos: Int) {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".arquivos", zip)
            val envio = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(ctx.contentResolver, "rajada", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(envio, "Rajada de teste").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            DoisSensores.ev("rajada_compartilhar", linkedMapOf("rodada" to rodada, "arquivos" to arquivos, "bytes" to zip.length()))
        } catch (x: Exception) {
            DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to "compartilhar", "classe" to x.javaClass.simpleName))
            Toast.makeText(ctx, "Não consegui compartilhar a rajada: ${x.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * PNG em escala de cinza, 8 bits, sem perda: IHDR tipo de cor 0, um IDAT com as linhas no filtro Sub e IEND. Escrito à mão
 * porque o Bitmap.compress do Android não grava PNG de um canal só.
 */
internal object Png {
    private val ASSINATURA = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun cinza8(arq: File, px: ByteArray, w: Int, h: Int, nivel: Int) {
        require(px.size >= w * h)
        val comp = ByteArrayOutputStream(w * h / 2 + 1024)
        val def = Deflater(nivel)
        try {
            DeflaterOutputStream(comp, def, 1 shl 16).use { z ->
                val linha = ByteArray(w + 1)
                linha[0] = 1   // filtro Sub: cada byte menos o da esquerda
                for (y in 0 until h) {
                    val o = y * w
                    linha[1] = px[o]
                    for (x in 1 until w) linha[x + 1] = (px[o + x] - px[o + x - 1]).toByte()
                    z.write(linha)
                }
            }
        } finally { def.end() }
        DataOutputStream(BufferedOutputStream(FileOutputStream(arq), 1 shl 16)).use { out ->
            out.write(ASSINATURA)
            val ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put(8.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte()).array()
            bloco(out, "IHDR", ihdr)
            bloco(out, "IDAT", comp.toByteArray())
            bloco(out, "IEND", ByteArray(0))
        }
    }

    private fun bloco(out: DataOutputStream, tipo: String, dados: ByteArray) {
        val t = tipo.toByteArray(Charsets.US_ASCII)
        out.writeInt(dados.size)
        out.write(t)
        out.write(dados)
        val crc = CRC32().apply { update(t); update(dados) }
        out.writeInt(crc.value.toInt())
    }
}
