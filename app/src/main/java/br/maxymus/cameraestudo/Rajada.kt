package br.maxymus.cameraestudo

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
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
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import kotlin.math.pow

/**
 * Rajada de teste, fase A (0.80): mede o que uma rajada YUV entrega no aparelho real antes de qualquer fusão no app.
 *
 * A tela solta o CameraX, esta classe abre a câmera lógica "0" pela Camera2, espera AE e AF convergirem, trava os dois e
 * faz um captureBurst de QUADROS pedidos YUV_420_888 na maior resolução que o aparelho aceitar. Os quadros são copiados
 * para a memória na chegada (a câmera é liberada logo), a tela devolve o CameraX e tira a foto normal pelo caminho de
 * sempre, e só então tudo é gravado em files/rajada/.tmp_<id> e a pasta inteira é renomeada para o nome final: nada
 * parcial aparece na revisão.
 *
 * 0.81: a mesma rajada em três modos (Modo). "processada" é a 0.80. "sem_processamento" pede NOISE_REDUCTION_MODE_OFF e
 * EDGE_MODE_OFF em cada quadro e confere no resultado. "raw" troca o YUV grande por um ImageReader RAW_SENSOR: as Image ficam
 * retidas no próprio leitor (sem cópia para o heap, 8 RAW são ~200 MB) e viram quadro_i.dng e previa_i.png ainda com a
 * câmera aberta, depois do stopRepeating; só então a câmera é solta. Em todos os modos a OIS é pedida quando existe e a
 * faixa de fps do AE vai até 30 com o menor mínimo disponível, para o AE poder alongar a exposição em luz baixa.
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
    var ativa: Rodada? by mutableStateOf(null)
        private set
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A tarefa pertence ao processo; descartar a tela não interrompe o fechamento nem a finalização. */
    fun lancar(r: Rodada, bloco: suspend () -> Unit) {
        check(ativa == null && DoisSensores.ativa == null && DoisSensores.retida == null)
        ativa = r
        val tarefa = escopo.launch(start = CoroutineStart.UNDISPATCHED) {
            try { bloco() }
            catch (x: Throwable) { if (!r.fim.get()) falhou(r, null, x) }
            finally { if (ativa === r) ativa = null }
        }
        // Como no dois sensores, a vigia independe da tela e da thread que conversa com o HAL.
        escopo.launch {
            val limite = SystemClock.elapsedRealtime() + 90_000
            var cancelarEm = 0L
            while (!tarefa.isCompleted) {
                if (r.cancelada && cancelarEm == 0L) cancelarEm = SystemClock.elapsedRealtime()
                if ((cancelarEm != 0L && SystemClock.elapsedRealtime() - cancelarEm >= 5_000) ||
                    (r.etapa != "gravar" && SystemClock.elapsedRealtime() >= limite)) {
                    cancelar(r)
                    tarefa.cancel()
                    if (!r.fim.get()) cancelada(r, null)
                    r.segura = false
                    if (ativa === r) ativa = null
                    break
                }
                delay(50)
            }
        }
    }

    private val travaArquivos = Mutex()

    class Rodada internal constructor(val id: String, val modo: Modo) {
        @Volatile var cancelada = false
        @Volatile var etapa = "inicio"
        var segura by mutableStateOf(true)
        @Volatile internal var posse: DoisSensores.Rodada? = null
        internal val fim = AtomicBoolean(false)
    }

    fun novaRodada(modo: Modo): Rodada = Rodada(java.util.UUID.randomUUID().toString().replace("-", "").take(6), modo)

    fun cancelar(r: Rodada) {
        synchronized(r) { r.cancelada = true }
        r.posse?.let { DoisSensores.fecharPorFora(it, "botao") }
    }

    /** ON_STOP: a câmera não fica capturando em segundo plano. Na gravação a câmera já voltou, e a rajada segue. */
    fun aoParar() { ativa?.let { if (it.etapa != "gravar") cancelar(it) } }

    // ------------------------------------------------------------------ modos (0.81)

    /** Os três modos da rajada. `numero` é o código da telemetria; `codigo` vai para o meta.json e para as preferências. */
    enum class Modo(val codigo: String, val numero: Int, val nome: String) {
        PROCESSADA("processada", 0, "Processada pelo celular"),
        SEM_PROCESSAMENTO("sem_processamento", 1, "Sem processamento do celular"),
        RAW("raw", 2, "RAW (DNG)");

        companion object { fun de(codigo: String?): Modo? = values().firstOrNull { it.codigo == codigo } }
    }

    /** Se o modo roda neste aparelho e, se não, por quê (código curto). */
    class Disponibilidade(val modo: Modo, val ok: Boolean, val motivo: String?)

    private fun tamanhosRaw(c: CameraCharacteristics): List<Size> =
        (c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty())
            .sortedByDescending { it.width.toLong() * it.height }

    private fun disponibilidadeDe(c: CameraCharacteristics, modo: Modo): Disponibilidade = when (modo) {
        Modo.PROCESSADA -> Disponibilidade(modo, true, null)
        Modo.SEM_PROCESSAMENTO -> {
            val nr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty()
            val edge = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty()
            when {
                CaptureRequest.NOISE_REDUCTION_MODE_OFF !in nr -> Disponibilidade(modo, false, "sem_nr_off")
                CaptureRequest.EDGE_MODE_OFF !in edge -> Disponibilidade(modo, false, "sem_edge_off")
                else -> Disponibilidade(modo, true, null)
            }
        }
        Modo.RAW -> {
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            when {
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps -> Disponibilidade(modo, false, "sem_capacidade_raw")
                tamanhosRaw(c).isEmpty() -> Disponibilidade(modo, false, "sem_tamanho_raw")
                else -> Disponibilidade(modo, true, null)
            }
        }
    }

    /** Lê as CameraCharacteristics da "0" SEM abrir a câmera e diz, para cada modo, se está disponível. Rodar em IO. */
    fun disponibilidade(ctx: Context): Map<Modo, Disponibilidade> = try {
        val c = (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(LOGICA)
        Modo.values().associateWith { disponibilidadeDe(c, it) }
    } catch (x: Exception) {
        // sem as características, só a 0.80 fica de pé (e ela mesma vai dizer o que falhou ao abrir)
        Modo.values().associateWith { Disponibilidade(it, it == Modo.PROCESSADA, if (it == Modo.PROCESSADA) null else "caracteristicas") }
    }

    // o modo escolhido fica nas preferências da tela de testes (dois_sensores.xml, já fora do backup)
    private const val PREFS_MODO = "dois_sensores"
    private const val CHAVE_MODO = "rajada_modo"

    /** O modo guardado, se ainda disponível; senão "sem_processamento" quando disponível; senão "processada". */
    fun modoGuardado(ctx: Context, disp: Map<Modo, Disponibilidade>): Modo {
        val salvo = try { Modo.de(ctx.getSharedPreferences(PREFS_MODO, Context.MODE_PRIVATE).getString(CHAVE_MODO, null)) } catch (x: Exception) { null }
        return when {
            salvo != null && disp[salvo]?.ok == true -> salvo
            disp[Modo.SEM_PROCESSAMENTO]?.ok == true -> Modo.SEM_PROCESSAMENTO
            else -> Modo.PROCESSADA
        }
    }

    fun guardarModo(ctx: Context, m: Modo) {
        try { ctx.getSharedPreferences(PREFS_MODO, Context.MODE_PRIVATE).edit().putString(CHAVE_MODO, m.codigo).apply() } catch (x: Exception) { }
    }

    /** Resultados em que os quadros são gravados. */
    fun salvavel(resultado: String): Boolean = resultado == "ok" || resultado == "variou" || resultado == "modo_nao_aplicado"

    // ------------------------------------------------------------------ dados da captura

    /** Metadados de um quadro, lidos do TotalCaptureResult do pedido de ordem `ordem` na rajada. */
    class QuadroMeta(
        val ordem: Int, val ts: Long?, val exp: Long?, val iso: Int?, val dur: Long?, val ois: Int?, val nr: Int?, val edge: Int?,
        val ae: Int?, val af: Int?, val foco: Float?,
        val faixaFps: List<Int>?, val ruido: List<List<Double>>?, val pretoDin: List<Float>?, val brancoDin: Int?,
        val neutro: List<Double>?, val ganhosWb: List<Float>?, val temMapa: Boolean
    )

    private fun metaDe(ordem: Int, res: CaptureResult): QuadroMeta = QuadroMeta(
        ordem = ordem,
        ts = res.get(CaptureResult.SENSOR_TIMESTAMP), exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        iso = res.get(CaptureResult.SENSOR_SENSITIVITY), dur = res.get(CaptureResult.SENSOR_FRAME_DURATION),
        ois = res.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE), nr = res.get(CaptureResult.NOISE_REDUCTION_MODE),
        edge = res.get(CaptureResult.EDGE_MODE), ae = res.get(CaptureResult.CONTROL_AE_STATE),
        af = res.get(CaptureResult.CONTROL_AF_STATE), foco = res.get(CaptureResult.LENS_FOCUS_DISTANCE),
        faixaFps = res.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { listOf(it.lower, it.upper) },
        ruido = res.get(CaptureResult.SENSOR_NOISE_PROFILE)?.map { listOf(it.first, it.second) },
        pretoDin = if (Build.VERSION.SDK_INT >= 28) res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.toList() else null,
        brancoDin = if (Build.VERSION.SDK_INT >= 28) res.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) else null,
        neutro = res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() },
        ganhosWb = res.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) },
        temMapa = res.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) != null
    )

    /** Pixels de um quadro: Y inteiro e o croma com U (w/2 x h/2) em cima e V (w/2 x h/2) embaixo. */
    internal class QuadroPixels(val ts: Long, val chegadaNs: Long, val w: Int, val h: Int, val y: ByteArray, val uv: ByteArray)

    /** Quadro RAW já gravado na temporária (o DNG e a prévia), com a câmera ainda aberta. */
    internal class QuadroRaw(val ordem: Int, val ts: Long, val chegadaNs: Long, val w: Int, val h: Int, val dng: String, val previa: String)

    /** Image RAW retida no próprio ImageReader até virar DNG (nunca copiada para o heap). */
    private class RawPreso(val img: Image, val chegadaNs: Long) { @Volatile var fechada = false }

    class Tentativa(val w: Int, val h: Int) { var desfecho = "nao_tentou" }

    /** O que a câmera declara, para o meta.json (inclusive o modo de resolução máxima, que não entra na rajada). */
    class InfoCamera(
        val nivel: Int?, val orientacao: Int?, val tamanhosYuv: List<Size>, val tamanhosYuvAltaRes: List<Size>,
        val maxResDeclara: Boolean, val maxResCapacidade: Boolean, val maxResMatriz: Size?,
        val maxResYuv: List<Size>, val maxResRaw: List<Size>, val maxResJpeg: List<Size>,
        val modosOis: List<Int>, val aeLockDisp: Boolean, val focoMin: Float?,
        val modosNr: List<Int>, val modosEdge: List<Int>, val rawCapacidade: Boolean, val tamanhosRaw: List<Size>,
        val faixasFps: List<Range<Int>>, val modosMapa: List<Int>, val cfa: Int?, val pretoPadrao: List<Int>?, val branco: Int?,
        val matrizPixels: Size?, val areaAtivaPre: android.graphics.Rect?
    )

    /** O que esta rajada pediu além da 0.80, e o que o aparelho declara para o tamanho usado. */
    class Pedido {
        var faixaFps: Range<Int>? = null
        var ois = false
        var mapaSombreamento = false
        var nrEdgeOff = false
        var duracaoMinNs: Long? = null
        var stallNs: Long? = null
        var orientacaoDng: Int? = null
        var orientacaoFonte: String? = null
    }

    class Travas {
        var aeConvergiu = false
        var afConvergiu = false
        var aeTravado = false
        var afFixo = false
        var focoDioptrias: Float? = null
        var ms3a: Long? = null
    }

    class Captura internal constructor(
        val resultado: String, val motivo: String?, val classe: String?, val etapa: String, val modo: Modo,
        val w: Int, val h: Int, internal val quadros: List<QuadroPixels>, internal val raws: List<QuadroRaw>, val metas: List<QuadroMeta>,
        val msTotal: Long?, val fps: Double?, val tentativas: List<Tentativa>, val info: InfoCamera?, val travas: Travas, val pedido: Pedido
    ) {
        val nQuadros: Int get() = if (modo == Modo.RAW) raws.size else quadros.size
    }

    /** O que a tela mostra e o que vai para a telemetria. */
    class Resultado internal constructor(
        val rodada: String, val modo: Modo, val resultado: String, val classe: String?, val quadros: Int, val msTotal: Long?, val fps: Double?,
        val largura: Int?, val altura: Int?, val larguraMax: Int?, val alturaMax: Int?, val exp: Long?, val iso: Int?,
        val ois: Int?, val nr: Int?, val edge: Int?, val normal: Boolean, val pasta: File?,
        /** OIS ligada em todos os quadros (true), desligada em algum (false), ou não informada (null). */
        val oisLigada: Boolean?,
        /** Em "sem_processamento": NR e EDGE desligados em todos os quadros, conferido nos resultados. */
        val nrEdgeDesligados: Boolean?,
        /** O primeiro quadro em que NR ou EDGE não saiu desligado (valores do resultado), para a tela dizer qual. */
        val nrVisto: Int?, val edgeVisto: Int?
    ) {
        fun caiuResolucao(): Boolean = largura != null && larguraMax != null && (largura != larguraMax || altura != alturaMax)
    }

    // ------------------------------------------------------------------ captura (Camera2)

    /** Estado escrito pelos retornos da câmera (fio "rajada") e lido pela corrotina. */
    private class Estado(val id: Int = 0) {
        @Volatile var device: CameraDevice? = null
        @Volatile var respondeu = false          // onOpened, onError ou onDisconnected já veio
        @Volatile var desistiu = false           // a captura saiu: um onOpened tardio fecha a câmera na hora
        @Volatile var perdeu: String? = null
        @Volatile var fechou = false
        @Volatile var tentativa: Estado? = null
        @Volatile var valida = true
        @Volatile var sessao: CameraCaptureSession? = null
        @Volatile var sessaoFalhou = false
        @Volatile var ae: Int? = null
        @Volatile var af: Int? = null
        @Volatile var foco: Float? = null
        @Volatile var modoAf: Int? = null
        @Volatile var lente: Int? = null
        @Volatile var fase3a = "convergir"
        @Volatile var falhas = 0
        @Volatile var falhaRazao: Int? = null
        @Volatile var erroCopia: String? = null
        @Volatile var erroFormato = false        // RAW que não veio RAW_SENSOR com 2 bytes por pixel
        val quadros = ArrayList<QuadroPixels>()
        val metas = ArrayList<QuadroMeta>()
        val presos = ArrayList<RawPreso>()                     // RAW: as Image retidas, até virarem DNG
        val resultados = HashMap<Long, TotalCaptureResult>()   // RAW: o resultado de cada quadro, pelo SENSOR_TIMESTAMP
        val gravados = ArrayList<QuadroRaw>()                  // RAW: os quadros já gravados na temporária
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
        val preto = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> IntArray(4).also { p.copyTo(it, 0) }.toList() }
        return InfoCamera(
            nivel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            orientacao = c.get(CameraCharacteristics.SENSOR_ORIENTATION),
            tamanhosYuv = yuv, tamanhosYuvAltaRes = alta,
            maxResDeclara = declara, maxResCapacidade = capacidade, maxResMatriz = matriz, maxResYuv = mYuv, maxResRaw = mRaw, maxResJpeg = mJpeg,
            modosOis = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty(),
            aeLockDisp = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            focoMin = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            modosNr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty(),
            modosEdge = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty(),
            rawCapacidade = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
            tamanhosRaw = tamanhosRaw(c),
            faixasFps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
            modosMapa = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)?.toList().orEmpty(),
            cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            pretoPadrao = preto, branco = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            matrizPixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            areaAtivaPre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        )
    }

    /**
     * Faixa de fps do AE: máximo 30 e o MENOR mínimo disponível (por exemplo [15,30]). Fixar [30,30] levaria o ISO ao extremo
     * em luz baixa; com o mínimo mais baixo o AE pode alongar a exposição. Sem faixa com máximo 30, não pede nenhuma.
     */
    private fun faixaAe(faixas: List<Range<Int>>): Range<Int>? = faixas.filter { it.upper == 30 }.minByOrNull { it.lower }

    /** Orientação do DNG: a da foto normal do app, que roda travado em retrato (rotação da tela 0 = SENSOR_ORIENTATION). */
    private fun orientacaoExif(sensor: Int?): Pair<Int, String> = when (sensor) {
        0 -> ExifInterface.ORIENTATION_NORMAL to "sensor_orientation_retrato"
        90 -> ExifInterface.ORIENTATION_ROTATE_90 to "sensor_orientation_retrato"
        180 -> ExifInterface.ORIENTATION_ROTATE_180 to "sensor_orientation_retrato"
        270 -> ExifInterface.ORIENTATION_ROTATE_270 to "sensor_orientation_retrato"
        else -> ExifInterface.ORIENTATION_NORMAL to "desconhecida_normal"
    }

    /**
     * Prévia em cinza de um quadro RAW, lida do MESMO buffer da Image (sem copiar a imagem inteira): para cada pixel de
     * saída, só o bloco 2x2 do Bayer que cai nele é lido e tirada a média; menos o preto, sobre (branco - preto), gama
     * 1/2,2. O passo entre blocos deixa o lado maior em ~256 px. Exige pixelStride 2 (conferido na chegada).
     */
    private fun previaRaw(img: Image, preto: Double, branco: Double): Triple<ByteArray, Int, Int> {
        val w = img.width; val h = img.height
        val plano = img.planes[0]
        val b = plano.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val rs = plano.rowStride
        val passo = max(1, (max(w, h) + 511) / 512)     // em blocos 2x2
        val ow = max(1, (w / 2) / passo); val oh = max(1, (h / 2) / passo)
        val px = ByteArray(ow * oh)
        val faixa = max(1.0, branco - preto)
        val gama = 1.0 / 2.2
        for (oy in 0 until oh) {
            val l0 = (oy * passo * 2) * rs
            val l1 = l0 + rs
            for (ox in 0 until ow) {
                val x = ox * passo * 2 * 2     // bytes: 2 por pixel
                val soma = (b.getShort(l0 + x).toInt() and 0xFFFF) + (b.getShort(l0 + x + 2).toInt() and 0xFFFF) +
                    (b.getShort(l1 + x).toInt() and 0xFFFF) + (b.getShort(l1 + x + 2).toInt() and 0xFFFF)
                val v = ((soma / 4.0 - preto) / faixa).coerceIn(0.0, 1.0)
                px[oy * ow + ox] = (v.pow(gama) * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        return Triple(px, ow, oh)
    }

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

    /**
     * RAW: para cada Image retida, na ordem do pedido, grava previa_i.png (do mesmo buffer, antes de fechar) e quadro_i.dng
     * com o resultado do mesmo SENSOR_TIMESTAMP, e fecha a Image logo depois. Roda com a câmera aberta e o repeating parado.
     * Devolve null quando gravou tudo (ou foi cancelada no meio), ou (motivo, classe) do erro. O que ficou pela metade na
     * temporária some com ela: rodada sem pasta é descartada. OutOfMemoryError sobe para capturar ("memoria").
     * Nunca chama setLocation nem setDescription.
     */
    private suspend fun gravarRaws(
        c: CameraCharacteristics, inf: InfoCamera, pedido: Pedido, r: Rodada, e: Estado, metas: List<QuadroMeta>, tmp: File,
        aoFase: (String) -> Unit
    ): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val presos = synchronized(e.presos) { e.presos.toList() }
        val resultados = synchronized(e.resultados) { HashMap(e.resultados) }
        val porTs = HashMap<Long, QuadroMeta>().apply { for (m in metas) m.ts?.let { put(it, m) } }
        val fila = presos.sortedBy { porTs[it.img.timestamp]?.ordem ?: Int.MAX_VALUE }
        for ((n, p) in fila.withIndex()) {
            if (r.cancelada) return@withContext null
            aoFase("Gravando DNG ${n + 1} de $QUADROS.")
            val ts = p.img.timestamp
            val w = p.img.width; val h = p.img.height
            val m = porTs[ts]
            val res = resultados[ts]
            if (m == null || res == null) return@withContext "raw_sem_resultado" to null
            val dng = "quadro_${m.ordem}.dng"
            val previa = "previa_${m.ordem}.png"
            try {
                // preto e branco do próprio quadro (API 28+) quando vêm; senão os declarados pelo sensor
                val preto = m.pretoDin?.takeIf { it.size == 4 }?.average() ?: inf.pretoPadrao?.average() ?: 0.0
                val branco = (m.brancoDin ?: inf.branco)?.toDouble() ?: 1023.0
                val (px, pw, ph) = previaRaw(p.img, preto, branco)
                Png.cinza8(File(tmp, previa), px, pw, ph, NIVEL_PNG)
            } catch (x: CancellationException) { throw x } catch (x: Exception) { return@withContext "previa" to x.javaClass.simpleName }
            try {
                val dc = DngCreator(c, res)
                try {
                    dc.setOrientation(pedido.orientacaoDng ?: ExifInterface.ORIENTATION_NORMAL)
                    BufferedOutputStream(FileOutputStream(File(tmp, dng)), 1 shl 16).use { out -> dc.writeImage(out, p.img) }
                } finally { dc.close() }
            } catch (x: CancellationException) { throw x } catch (x: Exception) { return@withContext "dng" to x.javaClass.simpleName }
            p.fechada = true
            p.img.close()
            synchronized(e.gravados) { e.gravados += QuadroRaw(m.ordem, ts, p.chegadaNs, w, h, dng, previa) }
        }
        null
    }

    private suspend fun esperar(maxMs: Long, r: Rodada, e: Estado, cond: () -> Boolean): Boolean {
        val fim = SystemClock.elapsedRealtime() + maxMs
        while (SystemClock.elapsedRealtime() < fim) {
            if (cond()) return true
            if (r.cancelada || e.perdeu != null || e.erroCopia != null) return false
            delay(10)
        }
        return cond()
    }

    /**
     * Abre a "0", trava 3A e faz a rajada no modo da rodada: YUV da maior resolução para baixo até uma ser aceita, ou RAW
     * no maior tamanho RAW_SENSOR. No RAW, os DNG e as prévias são gravados em `tmp` aqui mesmo, com a câmera aberta.
     * Fecha a câmera e espera o onClosed (até 3 s) antes de voltar; sem confirmação, mantém a retenção e o fio vivos.
     * Rodar fora da Main.
     */
    @Suppress("DEPRECATION")
    suspend fun capturar(ctx: Context, r: Rodada, tmp: File, aoFase: (String) -> Unit): Captura {
        val modo = r.modo
        val raw = modo == Modo.RAW
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val fio = HandlerThread("rajada").apply { start() }
        val h = Handler(fio.looper)
        val e = Estado()
        val travas = Travas()
        val tentativas = ArrayList<Tentativa>()
        val pedido = Pedido()
        val posse = DoisSensores.Rodada(r.id, ctx.applicationContext).also { r.posse = it; it.fio = fio }
        fun guarda(bloco: () -> Unit) {
            try { bloco() } catch (x: Throwable) {
                r.cancelada = true
                e.erroCopia = x.javaClass.simpleName
                // Até a reação à falha é protegida: uma segunda falha não pode escapar pelo callback.
                try { DoisSensores.fecharPorFora(posse, "fim_da_rodada") } catch (_: Throwable) { }
            }
        }
        fun fecharSessao(sessao: CameraCaptureSession) {
            guarda { sessao.abortCaptures() }
            guarda { sessao.close() }
        }
        var info: InfoCamera? = null
        var pediuAbertura = false
        fun fim(res: String, motivo: String?, classe: String? = null, w: Int = 0, hh: Int = 0, ms: Long? = null, fps: Double? = null): Captura {
            val atual = e.tentativa ?: e
            val qs = if (salvavel(res)) synchronized(atual.quadros) { ArrayList(atual.quadros) } else emptyList()
            val rs = if (salvavel(res)) synchronized(atual.gravados) { ArrayList(atual.gravados) } else emptyList()
            val ms2 = synchronized(atual.metas) { atual.metas.sortedBy { it.ordem } }
            return Captura(res, motivo, classe, r.etapa, modo, w, hh, qs, rs, ms2, ms, fps, tentativas, info, travas, pedido)
        }
        try {
            r.etapa = "caracteristicas"
            val c = cm.getCameraCharacteristics(LOGICA)
            val inf = lerInfo(c)
            info = inf
            val disp = disponibilidadeDe(c, modo)
            if (!disp.ok) return fim("erro", "modo_indisponivel:" + disp.motivo)
            if (inf.tamanhosYuv.isEmpty()) return fim("recusou_resolucao", "sem_yuv")
            // RAW: só o maior tamanho RAW_SENSOR do modo padrão; YUV: da maior resolução para baixo
            val candidatos = if (raw) inf.tamanhosRaw.take(1) else inf.tamanhosYuv.take(MAX_TENTATIVAS)
            if (candidatos.isEmpty()) return fim("recusou_resolucao", if (raw) "sem_raw" else "sem_yuv")
            val formato = if (raw) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
            val mapa = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            pedido.faixaFps = faixaAe(inf.faixasFps)
            pedido.ois = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in inf.modosOis
            pedido.mapaSombreamento = raw && CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON in inf.modosMapa
            pedido.nrEdgeOff = modo == Modo.SEM_PROCESSAMENTO
            if (raw) orientacaoExif(inf.orientacao).let { (o, f) -> pedido.orientacaoDng = o; pedido.orientacaoFonte = f }
            /** OIS e faixa de fps do AE, nos pedidos do 3A e nos da rajada, em todos os modos. */
            fun CaptureRequest.Builder.comuns() {
                pedido.faixaFps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                if (pedido.ois) set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
            // fluxo pequeno para o 3A rodar antes da rajada: PRIV/YUV pequeno + YUV máximo é combinação garantida
            val pequeno = inf.tamanhosYuv.filter { it.width.toLong() * it.height <= 1280L * 960 }.maxByOrNull { it.width.toLong() * it.height }
                ?: inf.tamanhosYuv.last()
            val afModos = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            val focoManual = CaptureRequest.CONTROL_AF_MODE_OFF in afModos &&
                CaptureRequest.LENS_FOCUS_DISTANCE in c.availableCaptureRequestKeys
            val afAuto = CaptureRequest.CONTROL_AF_MODE_AUTO in afModos
            val fixa = inf.focoMin == 0f

            r.etapa = "abrir"; aoFase("Abrindo a câmera.")
            synchronized(r) {
                if (r.cancelada) return fim("cancelado", null)
                DoisSensores.abriu(posse)
            }
            try { cm.openCamera(LOGICA, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = guarda {
                    posse.dispositivo = camera
                    val fechar = synchronized(e) { e.respondeu = true; if (r.cancelada || e.desistiu) true else { e.device = camera; false } }
                    if (fechar) { DoisSensores.fechando(posse, "onopened_tardio"); camera.close() }
                }
                override fun onDisconnected(camera: CameraDevice) = guarda {
                    posse.dispositivo = camera; e.perdeu = "desconectada"; e.tentativa?.perdeu = e.perdeu; e.respondeu = true
                    DoisSensores.fechando(posse, "desconectada"); camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) = guarda {
                    posse.dispositivo = camera; e.perdeu = "erro_$error"; e.tentativa?.perdeu = e.perdeu; e.respondeu = true
                    DoisSensores.fechando(posse, "erro_camera"); camera.close()
                }
                override fun onClosed(camera: CameraDevice) = guarda {
                    e.fechou = true; DoisSensores.liberou(posse, "onclosed")
                }
            }, h) } catch (x: Throwable) {
                DoisSensores.liberou(posse, "falha_abertura")
                throw x
            }
            pediuAbertura = true
            if (!esperar(3_000, r, e) { e.device != null }) {
                return if (r.cancelada) fim("cancelado", null) else fim("perdeu_camera", e.perdeu ?: "abrir_prazo")
            }
            val dev = e.device!!

            for ((indice, tam) in candidatos.withIndex()) {
                if (r.cancelada) return fim("cancelado", null)
                if (e.perdeu != null) return fim("perdeu_camera", e.perdeu)
                val pai = e
                val e = Estado(indice + 1)
                pai.tentativa = e
                fun guardaTentativa(bloco: () -> Unit) = guarda {
                    synchronized(e) {
                        if (e.valida && pai.tentativa?.id == e.id) bloco()
                    }
                }
                val leitores = ArrayList<ImageReader>()
                val t = Tentativa(tam.width, tam.height).also { tentativas += it }
                try {
                    val prev = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = guardaTentativa {
                            if (!e.valida || request.tag != e.fase3a) return@guardaTentativa
                            e.modoAf = result.get(CaptureResult.CONTROL_AF_MODE)
                            e.lente = result.get(CaptureResult.LENS_STATE)
                            e.ae = result.get(CaptureResult.CONTROL_AE_STATE); e.af = result.get(CaptureResult.CONTROL_AF_STATE)
                            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { e.foco = it }
                        }
                    }
                    val rajadaCb = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = guardaTentativa {
                            if (!e.valida) return@guardaTentativa
                            val m = metaDe(request.tag as? Int ?: -1, result)
                            // o DngCreator precisa do resultado do MESMO quadro: guardado pelo SENSOR_TIMESTAMP ANTES do meta,
                            // porque a espera olha os metas; ao ver o oitavo meta, o resultado dele já está guardado
                            if (raw) m.ts?.let { ts -> synchronized(e.resultados) { if (e.valida) e.resultados[ts] = result } }
                            synchronized(e.metas) {
                                if (e.valida && m.ordem in 0 until QUADROS && e.metas.none { it.ordem == m.ordem }) e.metas += m
                            }
                        }
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) = guardaTentativa {
                            if (!e.valida) return@guardaTentativa
                            e.falhas++; e.falhaRazao = failure.reason
                        }
                    }

                    r.etapa = "sessao"; aoFase("Preparando ${tam.width}x${tam.height}.")
                    pedido.duracaoMinNs = runCatching { mapa?.getOutputMinFrameDuration(formato, tam) }.getOrNull()
                    pedido.stallNs = runCatching { mapa?.getOutputStallDuration(formato, tam) }.getOrNull()
                    // RAW: maxImages = QUADROS, e as Image ficam presas no leitor até virarem DNG; sem o YUV grande
                    // RAW retém os QUADROS até virarem DNG: 2 vagas de folga para o HAL nunca ficar sem buffer em voo
                    val grande = ImageReader.newInstance(tam.width, tam.height, formato, if (raw) QUADROS + 2 else QUADROS).also { leitores += it }
                    val peq = ImageReader.newInstance(pequeno.width, pequeno.height, ImageFormat.YUV_420_888, 2).also { leitores += it }
                    if (raw) grande.setOnImageAvailableListener({ rd ->
                        guardaTentativa {
                            if (!e.valida) return@guardaTentativa
                            val chegada = SystemClock.elapsedRealtimeNanos()
                            val img = rd.acquireNextImage() ?: return@guardaTentativa
                            if (img.format != ImageFormat.RAW_SENSOR || img.planes.isEmpty() || img.planes[0].pixelStride != 2) {
                                img.close(); e.erroFormato = true; return@guardaTentativa
                            }
                            val guardou = synchronized(e.presos) {
                                if (e.presos.size < QUADROS && e.presos.none { it.img.timestamp == img.timestamp }) { e.presos += RawPreso(img, chegada); true }
                                else false
                            }
                            if (!guardou) img.close()
                        }
                    }, h) else grande.setOnImageAvailableListener({ rd ->
                        guardaTentativa {
                            if (!e.valida) return@guardaTentativa
                            val chegada = SystemClock.elapsedRealtimeNanos()
                            rd.acquireNextImage()?.let { img ->
                                try {
                                    val q = copiar(img, chegada)
                                    synchronized(e.quadros) {
                                        if (e.quadros.size < QUADROS && e.quadros.none { it.ts == q.ts }) e.quadros += q
                                    }
                                } finally { img.close() }
                            }
                        }
                    }, h)
                    peq.setOnImageAvailableListener({ rd -> guardaTentativa { if (e.valida) rd.acquireLatestImage()?.close() } }, h)
                    val alvos: List<Surface> = listOf(peq.surface, grande.surface)
                    try {
                        dev.createCaptureSession(alvos, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) = guarda {
                                synchronized(e) { if (!e.valida || r.cancelada) session.close() else e.sessao = session }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) = guarda {
                                synchronized(e) { if (e.valida) e.sessaoFalhou = true }
                                session.close()
                            }
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
                    pv.comuns()
                    if (!inf.aeLockDisp || (!afAuto && !(fixa && focoManual))) return fim("sem_convergencia", "controles_indisponiveis")
                    pv.set(CaptureRequest.CONTROL_AF_MODE, if (afAuto) CaptureRequest.CONTROL_AF_MODE_AUTO else CaptureRequest.CONTROL_AF_MODE_OFF)
                    if (fixa && focoManual) pv.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    pv.setTag(e.fase3a)
                    sess.setRepeatingRequest(pv.build(), prev, h)
                    if (afAuto) {
                        pv.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        sess.capture(pv.build(), prev, h)
                        pv.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    }
                    val convergiu = esperar(4_000, r, e) { synchronized(e) {
                        e.ae == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                            (fixa || e.af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
                    } }
                    travas.aeConvergiu = e.ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                    travas.afConvergiu = fixa || e.af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    if (r.cancelada) return fim("cancelado", null)
                    if (!convergiu) return fim("sem_convergencia", "convergencia_prazo")
                    val foco = if (focoManual) e.foco?.takeIf { it.isFinite() && it >= 0f } ?: if (fixa) 0f else null else null
                    pv.set(CaptureRequest.CONTROL_AE_LOCK, true)
                    if (foco != null) {
                        pv.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        pv.set(CaptureRequest.LENS_FOCUS_DISTANCE, foco)
                    }
                    synchronized(e) { e.fase3a = "travar"; e.ae = null }
                    pv.setTag(e.fase3a)
                    sess.setRepeatingRequest(pv.build(), prev, h)
                    val travou = esperar(1_500, r, e) { synchronized(e) {
                        e.ae == CaptureResult.CONTROL_AE_STATE_LOCKED &&
                            if (foco != null) e.modoAf == CaptureResult.CONTROL_AF_MODE_OFF && e.foco == foco &&
                                (fixa || e.lente == CaptureResult.LENS_STATE_STATIONARY)
                            else e.modoAf == CaptureResult.CONTROL_AF_MODE_AUTO && e.af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    } }
                    travas.aeTravado = travou
                    travas.afFixo = travou
                    travas.focoDioptrias = foco
                    travas.ms3a = SystemClock.elapsedRealtime() - t3a
                    if (r.cancelada) return fim("cancelado", null)
                    if (!travou) return fim("sem_convergencia", "trava_prazo")

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
                            } else set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                            comuns()
                            if (pedido.nrEdgeOff) {
                                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                            }
                            // o DngCreator inclui o mapa de sombreamento quando ele vem no resultado
                            if (pedido.mapaSombreamento) set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                            setTag(i)
                        }.build()
                    }
                    val tBurst = SystemClock.elapsedRealtimeNanos()
                    try { sess.captureBurst(pedidos, rajadaCb, h) }
                    catch (x: IllegalArgumentException) { t.desfecho = "requisicao:${x.javaClass.simpleName}"; continue }
                    fun chegaram(): Int = if (raw) synchronized(e.presos) { e.presos.size } else synchronized(e.quadros) { e.quadros.size }
                    esperar(PRAZO_QUADROS_MS, r, e) {
                        val nq = chegaram()
                        val nm = synchronized(e.metas) { e.metas.size }
                        (nq >= QUADROS && nm >= QUADROS) || e.falhas > 0 || e.erroCopia != null || e.erroFormato
                    }
                    if (r.cancelada) return fim("cancelado", null)
                    if (e.perdeu != null) return fim("perdeu_camera", e.perdeu)
                    e.erroCopia?.let { cl -> t.desfecho = "copia:$cl"; return fim("erro", "copia", cl, tam.width, tam.height) }
                    if (e.erroFormato) { t.desfecho = "raw_formato"; return fim("erro", "raw_formato", null, tam.width, tam.height) }
                    val nq = chegaram()
                    val nm = synchronized(e.metas) { e.metas.size }
                    val metas = synchronized(e.metas) { e.metas.toList() }
                    val carimbos = if (raw) synchronized(e.presos) { e.presos.map { it.img.timestamp }.toSet() }
                        else synchronized(e.quadros) { e.quadros.map { it.ts }.toSet() }
                    val pareados = metas.size == QUADROS && metas.mapNotNull { it.ts }.toSet() == carimbos
                    if (nq == QUADROS && nm == QUADROS && pareados && e.falhas == 0) {
                        val chegadas = if (raw) synchronized(e.presos) { e.presos.map { it.chegadaNs } } else synchronized(e.quadros) { e.quadros.map { it.chegadaNs } }
                        val msTotal = (chegadas.max() - tBurst) / 1_000_000
                        val tsMetas = metas.mapNotNull { it.ts }.sorted()
                        val fps = if (tsMetas.size >= 2 && tsMetas.last() > tsMetas.first()) (tsMetas.size - 1) * 1e9 / (tsMetas.last() - tsMetas.first()) else null
                        guarda { sess.stopRepeating() }
                        if (r.cancelada) return fim("cancelado", null)
                        val iguais = metas.all { it.exp != null && it.iso != null } &&
                            metas.map { it.exp }.distinct().size == 1 && metas.map { it.iso }.distinct().size == 1
                        // sem_processamento: um quadro com NR ou EDGE diferente de OFF (ou sem dizer) e o modo não valeu
                        val naoAplicado = if (pedido.nrEdgeOff) metas.sortedBy { it.ordem }.firstOrNull {
                            it.nr != CaptureResult.NOISE_REDUCTION_MODE_OFF || it.edge != CaptureResult.EDGE_MODE_OFF
                        } else null
                        val desfecho = when { naoAplicado != null -> "modo_nao_aplicado"; iguais -> "ok"; else -> "variou" }
                        val motivo = listOfNotNull(
                            naoAplicado?.let { "nr_${it.nr}_edge_${it.edge}" },
                            if (iguais) null else "exposicao_iso_divergentes_ou_ausentes"
                        ).joinToString(",").ifEmpty { null }
                        if (raw) {
                            // DNG com a câmera ainda aberta e o repeating parado; só depois o finally solta leitor e câmera
                            r.etapa = "dng"
                            val erro = gravarRaws(c, inf, pedido, r, e, metas, tmp, aoFase)
                            if (r.cancelada) return fim("cancelado", null)
                            if (erro != null) { t.desfecho = erro.first; return fim("erro", erro.first, erro.second, tam.width, tam.height) }
                        }
                        t.desfecho = desfecho
                        return fim(desfecho, motivo, null, tam.width, tam.height, msTotal, fps)
                    }
                    // esta resolução não entregou: anota, solta a sessão e tenta a próxima
                    t.desfecho = if (e.falhas > 0) "falhas:${e.falhas}:${e.falhaRazao}" else "incompleto:$nq:$nm"
                } finally {
                    // Invalida antes de soltar superfícies: retornos antigos nunca entram na próxima tentativa.
                    val sessao = synchronized(e) { e.valida = false; e.sessao }
                    sessao?.let { fecharSessao(it) }
                    // RAW: cada Image retida é fechada antes do leitor (erro, cancelamento ou quadro que sobrou)
                    val presos = synchronized(e.presos) { e.presos.toList().also { e.presos.clear() } }
                    for (p in presos) if (!p.fechada) guarda { p.fechada = true; p.img.close() }
                    for (leitor in leitores) guarda { leitor.close() }
                    synchronized(e.quadros) { e.quadros.clear() }
                    synchronized(e.metas) { e.metas.clear() }
                    synchronized(e.resultados) { e.resultados.clear() }
                }
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
        } catch (x: Throwable) {
            return fim("erro", null, x.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                synchronized(e) { e.desistiu = true }
                DoisSensores.fecharPorFora(posse, "fim_da_rodada")
                val limite = SystemClock.elapsedRealtime() + 3_000
                while (pediuAbertura && !posse.liberada && SystemClock.elapsedRealtime() < limite) delay(10)
                posse.execucaoTerminou = true
                DoisSensores.encerrarFio(posse)
            }
        }
    }

    // ------------------------------------------------------------------ resultado e telemetria

    /** Telemetria do fim, uma vez por rodada, pelo esquema fechado do DoisSensores (só números e códigos). */
    private fun emitir(r: Rodada, res: Resultado, cap: Captura?, msGravar: Long?) {
        if (!r.fim.compareAndSet(false, true)) return
        r.posse?.fim?.set(true)
        DoisSensores.ev("rajada_teste", linkedMapOf(
            "rodada" to r.id, "modo" to r.modo.numero, "resultado" to res.resultado, "classe" to res.classe, "etapa" to (cap?.etapa ?: r.etapa),
            "quadros" to res.quadros, "ms_total" to res.msTotal, "fps" to res.fps, "largura" to res.largura, "altura" to res.altura,
            "largura_max" to res.larguraMax, "altura_max" to res.alturaMax, "tentativas" to cap?.tentativas?.size,
            "exp_ns" to res.exp, "iso" to res.iso, "ois" to res.ois, "nr" to res.nr, "edge" to res.edge,
            "ae_travado" to cap?.travas?.aeTravado, "af_fixo" to cap?.travas?.afFixo, "max_res_disp" to cap?.info?.maxResDeclara,
            "normal" to res.normal, "gravou" to (res.pasta != null), "ms_gravar" to msGravar
        ))
    }

    private fun resultadoDe(r: Rodada, resultado: String, classe: String?, cap: Captura?, normal: Boolean, pasta: File?): Resultado {
        val metas = cap?.metas.orEmpty()
        val m0 = metas.firstOrNull()
        val maior = (if (r.modo == Modo.RAW) cap?.info?.tamanhosRaw else cap?.info?.tamanhosYuv)?.firstOrNull()
        val ok = salvavel(resultado)
        val oisLigada = when {
            metas.isEmpty() || metas.all { it.ois == null } -> null
            else -> metas.all { it.ois == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON }
        }
        val fora = metas.firstOrNull { it.nr != CaptureResult.NOISE_REDUCTION_MODE_OFF || it.edge != CaptureResult.EDGE_MODE_OFF }
        val nrEdge = if (r.modo == Modo.SEM_PROCESSAMENTO && metas.isNotEmpty()) fora == null else null
        return Resultado(
            r.id, r.modo, resultado, classe, if (ok) cap?.nQuadros ?: 0 else 0, if (ok) cap?.msTotal else null, if (ok) cap?.fps else null,
            if (ok) cap?.w else null, if (ok) cap?.h else null, maior?.width, maior?.height,
            m0?.exp, m0?.iso, m0?.ois, m0?.nr, m0?.edge, normal, pasta,
            oisLigada, nrEdge, if (nrEdge == false) fora?.nr else null, if (nrEdge == false) fora?.edge else null
        )
    }

    /** Saída sem quadros para gravar (recusa, perda, cancelamento, erro): emite e devolve o resultado. */
    fun semQuadros(r: Rodada, cap: Captura): Resultado =
        resultadoDe(r, cap.resultado, cap.classe, cap, false, null).also { emitir(r, it, cap, null) }

    fun cameraxNaoFechou(r: Rodada): Resultado =
        resultadoDe(r, "camerax_nao_fechou", null, null, false, null).also { emitir(r, it, null, null) }

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
    suspend fun limparTemporarias(ctx: Context) { travaArquivos.withLock {
        limparTemporariasSemTrava(ctx, ativa?.id)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val antiga = raizZip(ctx)
                if (antiga.exists() && !antiga.deleteRecursively()) throw java.io.IOException("limpar_zip_interno")
            } catch (x: Exception) { erroDownloads("limpa_downloads", x) }
            limparDownloads(ctx)
        }
    } }

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
     * em paralelo (2 de cada vez: cada um segura ~25 MB a mais enquanto comprime). No RAW, os DNG e as prévias já estão na
     * temporária (gravados por capturar com a câmera aberta): aqui só entram o meta.json e a renomeação.
     * Devolve o resultado final e já emite a telemetria. Rodar em IO.
     */
    suspend fun gravar(ctx: Context, r: Rodada, cap: Captura, tmp: File, normalOk: Boolean, aoQuadro: (Int) -> Unit): Resultado {
        r.etapa = "gravar"
        val t0 = SystemClock.elapsedRealtime()
        var pasta: File? = null
        var resultado = "ok"
        var classe: String? = null
        try {
            val metas = cap.metas
            val quadrosJson = ArrayList<JSONObject>()
            val metasUsadas = ArrayList<QuadroMeta?>()
            if (cap.modo == Modo.RAW) {
                for (q in cap.raws.sortedBy { it.ordem }) {
                    if (!File(tmp, q.dng).isFile || !File(tmp, q.previa).isFile) throw java.io.IOException("dng_sumiu")
                    val m = metas.firstOrNull { it.ordem == q.ordem }
                    metasUsadas += m
                    quadrosJson += metaQuadro(JSONObject()
                        .poe("ordem", q.ordem).poe("arquivo_dng", q.dng).poe("arquivo_previa", q.previa)
                        .poe("largura", q.w).poe("altura", q.h).poe("carimbo_imagem_ns", q.ts), m)
                }
                aoQuadro(cap.raws.size)
            } else {
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
                for ((i, q, m) in pares) {
                    metasUsadas += m
                    quadrosJson += metaQuadro(JSONObject()
                        .poe("ordem", i).poe("arquivo_y", "y_$i.png").poe("arquivo_uv", "uv_$i.png")
                        .poe("largura", q.w).poe("altura", q.h).poe("carimbo_imagem_ns", q.ts), m)
                }
            }
            val normal = File(tmp, "normal.jpg")
            val temNormal = normalOk && normal.isFile && normal.length() > 0
            if (!temNormal) normal.delete()
            File(tmp, "meta.json").writeText(meta(r, cap, quadrosJson, metasUsadas, temNormal).toString(2))
            if (r.cancelada) throw Cancelada()
            travaArquivos.withLock {
                val nome = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + r.id
                val destino = File(raiz(ctx), nome)
                if (!tmp.renameTo(destino)) throw java.io.IOException("renomear")
                pasta = destino
                podar(ctx)
            }
            return resultadoDe(r, cap.resultado, null, cap, temNormal, pasta).also { emitir(r, it, cap, SystemClock.elapsedRealtime() - t0) }
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

    /** Campos por quadro, iguais em todos os modos (0.80 e o que a 0.81 acrescentou). */
    private fun metaQuadro(o: JSONObject, m: QuadroMeta?): JSONObject = o
        .poe("sensor_timestamp_ns", m?.ts).poe("sensor_exposure_time_ns", m?.exp).poe("sensor_sensitivity", m?.iso)
        .poe("sensor_frame_duration_ns", m?.dur).poe("lens_optical_stabilization_mode", m?.ois)
        .poe("noise_reduction_mode", m?.nr).poe("edge_mode", m?.edge)
        .poe("ae_estado", m?.ae).poe("af_estado", m?.af).poe("foco_dioptrias", m?.foco?.toDouble())
        .poe("faixa_fps_ae", m?.faixaFps?.let { JSONArray(it) })
        .poe("perfil_ruido", m?.ruido?.let { l -> JSONArray(l.map { p -> JSONArray(p.map { numero(it) }) }) })
        .poe("preto_dinamico", m?.pretoDin?.let { l -> JSONArray(l.map { numero(it.toDouble()) }) })
        .poe("branco_dinamico", m?.brancoDin)
        .poe("ponto_neutro", m?.neutro?.let { l -> JSONArray(l.map { numero(it) }) })
        .poe("ganhos_wb", m?.ganhosWb?.let { l -> JSONArray(l.map { numero(it.toDouble()) }) })
        .poe("tem_mapa_sombreamento", m?.temMapa)

    /** Número para dentro de um JSONArray: não finito vira null (o JSONArray do Android recusa NaN). */
    private fun numero(v: Double): Any = if (v.isNaN() || v.isInfinite()) JSONObject.NULL else v

    private fun meta(r: Rodada, cap: Captura, quadros: List<JSONObject>, metasQuadros: List<QuadroMeta?>, temNormal: Boolean): JSONObject {
        val inf = cap.info
        val pd = cap.pedido
        val raw = cap.modo == Modo.RAW
        val tsOrd = metasQuadros.mapNotNull { it?.ts }.sorted()
        val intervalos = tsOrd.zipWithNext { a, b -> (b - a) / 1e6 }
        val maxRes = JSONObject()
            .poe("declara", inf?.maxResDeclara).poe("capacidade_ultra_high_resolution", inf?.maxResCapacidade)
            .poe("matriz_pixels", inf?.maxResMatriz?.let { "${it.width}x${it.height}" })
            .poe("tamanhos_yuv", inf?.let { tamanhos(it.maxResYuv) }).poe("tamanhos_raw", inf?.let { tamanhos(it.maxResRaw) })
            .poe("tamanhos_jpeg", inf?.let { tamanhos(it.maxResJpeg) })
            .poe("observacao", "modo SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION so registrado; a rajada usa o modo padrao")
        val tent = JSONArray()
        for (t in cap.tentativas) tent.put(JSONObject().poe("largura", t.w).poe("altura", t.h).poe("desfecho", t.desfecho))
        val tv = cap.travas
        val expIguais = metasQuadros.all { it?.exp != null } && metasQuadros.map { it?.exp }.distinct().size == 1
        val isoIguais = metasQuadros.all { it?.iso != null } && metasQuadros.map { it?.iso }.distinct().size == 1
        val o = JSONObject()
            .poe("resultado", cap.resultado).poe("motivo", cap.motivo)
            .poe("formato_meta", 2).poe("app", "camera-estudo").poe("versao_app", BuildConfig.VERSION_NAME).poe("rodada", r.id)
            .poe("modo", cap.modo.codigo)
            .poe("quando", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            .poe("modelo", Build.MANUFACTURER + " " + Build.MODEL).poe("android", Build.VERSION.SDK_INT)
            .poe("camera", LOGICA).poe("nivel_hardware", inf?.nivel).poe("orientacao_sensor", inf?.orientacao)
            .poe("formato", if (raw) "RAW_SENSOR" else "YUV_420_888").poe("modelo_pedido", "TEMPLATE_STILL_CAPTURE").poe("quadros_pedidos", QUADROS)
            .poe("largura", cap.w).poe("altura", cap.h)
        if (raw) o
            .poe("dng", "um quadro_i.dng por quadro (DngCreator, sem localizacao nem descricao)")
            .poe("previa_png", "cinza 8 bits: media de cada bloco 2x2 do Bayer, menos o preto, sobre (branco - preto), gama 1/2,2, ~256 px no lado maior")
            .poe("orientacao_dng", pd.orientacaoDng).poe("orientacao_dng_fonte", pd.orientacaoFonte)
        else o
            .poe("y_png", "cinza 8 bits sem perda, largura x altura")
            .poe("uv_png", "cinza 8 bits sem perda, (largura/2) x altura: U nas primeiras altura/2 linhas, V nas ultimas altura/2")
        return o
            .poe("tentativas", tent)
            .poe("tamanhos_yuv", inf?.let { tamanhos(it.tamanhosYuv) }).poe("tamanhos_yuv_alta_resolucao", inf?.let { tamanhos(it.tamanhosYuvAltaRes) })
            .poe("resolucao_maxima", maxRes)
            .poe("modos_ois_disponiveis", inf?.let { JSONArray(it.modosOis) })
            .poe("ae_lock_disponivel", inf?.aeLockDisp).poe("foco_minimo_dioptrias", inf?.focoMin?.toDouble())
            // declarações do aparelho (0.81)
            .poe("modos_nr_disponiveis", inf?.let { JSONArray(it.modosNr) }).poe("modos_edge_disponiveis", inf?.let { JSONArray(it.modosEdge) })
            .poe("raw_capacidade", inf?.rawCapacidade).poe("tamanhos_raw", inf?.let { tamanhos(it.tamanhosRaw) })
            .poe("raw_duracao_min_ns", if (raw) pd.duracaoMinNs else null).poe("raw_stall_ns", if (raw) pd.stallNs else null)
            .poe("yuv_duracao_min_ns", if (raw) null else pd.duracaoMinNs)
            .poe("faixas_fps_ae", inf?.let { JSONArray(it.faixasFps.map { f -> JSONArray(listOf(f.lower, f.upper)) }) })
            .poe("faixa_fps_ae_pedida", pd.faixaFps?.let { JSONArray(listOf(it.lower, it.upper)) })
            .poe("ois_pedido", pd.ois).poe("nr_edge_off_pedidos", pd.nrEdgeOff).poe("mapa_sombreamento_pedido", pd.mapaSombreamento)
            .poe("modos_mapa_sombreamento", inf?.let { JSONArray(it.modosMapa) })
            // sensor, para o RAW
            .poe("cfa", inf?.cfa).poe("preto_padrao", inf?.pretoPadrao?.let { JSONArray(it) }).poe("branco", inf?.branco)
            .poe("matriz_pixels", inf?.matrizPixels?.let { "${it.width}x${it.height}" })
            .poe("area_ativa_pre_correcao", inf?.areaAtivaPre?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
            .poe("ae_convergiu", tv.aeConvergiu).poe("af_convergiu", tv.afConvergiu).poe("ae_travado", tv.aeTravado)
            .poe("af_fixo", tv.afFixo).poe("foco_fixo_dioptrias", tv.focoDioptrias?.toDouble()).poe("ms_3a", tv.ms3a)
            .poe("exposicao_iguais", expIguais).poe("iso_iguais", isoIguais)
            .poe("ms_total", cap.msTotal).poe("fps_medido", cap.fps).poe("intervalos_ms", JSONArray(intervalos))
            .poe("ordem", "ordem = posicao do pedido no captureBurst; quadros casados pelo SENSOR_TIMESTAMP")
            .poe("quadros", JSONArray(quadros))
            .poe("normal", if (temNormal) "normal.jpg" else null)
    }

    /** Guarda as GUARDAR rajadas mais novas. Só chamar com a travaArquivos. */
    private fun podar(ctx: Context) {
        val pastas = raiz(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(TMP) }?.sortedByDescending { it.name } ?: return
        for (velha in pastas.drop(GUARDAR)) try { velha.deleteRecursively() } catch (x: Exception) { }
        if (Build.VERSION.SDK_INT >= 29) limparDownloads(ctx)
    }

    class Guardada(val pasta: File, val quando: String, val modo: String, val bytes: Long) {
        val linha: String get() = "$quando · $modo · %.1f MB".format(bytes / 1048576.0)
    }

    private fun quandoPasta(pasta: File): String = try {
        SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(pasta.name.substringBeforeLast('_')) ?: Date(pasta.lastModified()))
    } catch (x: Exception) { "?" }

    /** Pastas definitivas, inclusive com meta ilegível. Leitura em IO, protegida contra rotação e exclusão. */
    suspend fun guardadas(ctx: Context): List<Guardada> = travaArquivos.withLock {
        raiz(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(TMP) }
            ?.sortedByDescending { it.name }?.take(GUARDAR)?.map { pasta ->
                val modo = try {
                    when (Modo.de(JSONObject(File(pasta, "meta.json").readText()).optString("modo"))) {
                        Modo.PROCESSADA -> "Processada"
                        Modo.SEM_PROCESSAMENTO -> "Sem processamento"
                        Modo.RAW -> "RAW (DNG)"
                        null -> "modo ?"
                    }
                } catch (x: Exception) { "modo ?" }
                Guardada(pasta, quandoPasta(pasta), modo, anexos(ctx, pasta)?.sumOf { it.length() } ?: 0L)
            }.orEmpty()
    }

    /** "Apagar esta rajada": só filha direta de files/rajada pelo caminho canônico. Leva junto o .zip, se houver. */
    suspend fun apagar(ctx: Context, pasta: File): Boolean = travaArquivos.withLock {
        val ok = try {
            val p = pasta.canonicalFile
            p.parentFile == raiz(ctx).canonicalFile && !p.name.startsWith(TMP) && p.isDirectory && p.deleteRecursively()
        } catch (x: Exception) { false }
        if (Build.VERSION.SDK_INT >= 29) limparDownloads(ctx)
        else try { raizZip(ctx).listFiles()?.forEach { it.delete() } } catch (x: Exception) { }
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

    private val NOME_ANEXO = Regex("^(y|uv|previa)_[0-9]\\.png$|^quadro_[0-9]\\.dng$|^meta\\.json$|^normal\\.jpg$")

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

    /** Pacote para a revisão, com as miniaturas dos quadros (Y, ou a prévia do DNG) e da foto normal decodificadas. Rodar em IO. */
    fun pacote(ctx: Context, pasta: File): Pacote? = try {
        val lista = anexos(ctx, pasta)
        if (lista == null || lista.none { it.name == "meta.json" }) {
            DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to "revisar", "motivo" to "pasta_invalida"))
            null
        } else {
            val imagens = lista.filter { it.name.startsWith("y_") || it.name.startsWith("previa_") }.sortedBy { it.name } +
                lista.filter { it.name == "normal.jpg" }
            val minis = imagens.mapNotNull { a ->
                val rotulo = when {
                    a.name == "normal.jpg" -> "normal"
                    a.name.startsWith("previa_") -> "quadro " + a.name.removePrefix("previa_").removeSuffix(".png") + " (DNG)"
                    else -> "Y " + a.name.removePrefix("y_").removeSuffix(".png")
                }
                decodifica(a, 320)?.let { Miniatura(rotulo, it) }
            }
            val quando = quandoPasta(pasta)
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

    private val CAMINHO_DOWNLOADS = Environment.DIRECTORY_DOWNLOADS + "/Câmera Estudo"
    private val NOME_ZIP_DOWNLOADS = Regex("""^rajada_(\d{8}_\d{6}_[0-9a-f]+)(?: \(\d+\))?\.zip$""")
    private class EntradaZip(val uri: Uri, val pasta: String, val pendente: Boolean, val bytes: Long)
    class ZipPronto(val uri: Uri, val bytes: Long, val destino: String, val msZip: Long, val reaproveitado: Boolean)

    private fun erroDownloads(acao: String, x: Exception) {
        DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to acao, "classe" to x.javaClass.simpleName))
    }

    /** Só nossas entradas: sem permissão de armazenamento, incluindo as pendentes. Chamar com a trava, em API 29+. */
    @Suppress("DEPRECATION")
    private fun entradasDownloads(ctx: Context): List<EntradaZip> {
        val colecao = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val campos = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE)
        // O MediaProvider normaliza RELATIVE_PATH acrescentando a barra final.
        val selecao = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
        val args = arrayOf(CAMINHO_DOWNLOADS, "$CAMINHO_DOWNLOADS/")
        val cursor = if (Build.VERSION.SDK_INT >= 30) ctx.contentResolver.query(colecao, campos, Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selecao)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }, null) else ctx.contentResolver.query(MediaStore.setIncludePending(colecao), campos, selecao, args, null)
        return (cursor ?: throw java.io.IOException("consulta_downloads")).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val nome = c.getString(1) ?: continue
                    val pasta = NOME_ZIP_DOWNLOADS.matchEntire(nome)?.groupValues?.get(1) ?: continue
                    add(EntradaZip(ContentUris.withAppendedId(colecao, c.getLong(0)), pasta, c.getInt(2) != 0, c.getLong(3)))
                }
            }
        }
    }

    /** Um zip publicado só sai quando não há mais sua pasta. Pendentes órfãs seguem a mesma regra. Com a trava. */
    private fun limparDownloads(ctx: Context) {
        try {
            val raiz = raiz(ctx)
            val pastas = if (!raiz.exists()) emptySet() else
                (raiz.listFiles() ?: throw java.io.IOException("listar_rajadas"))
                    .filter { it.isDirectory && !it.name.startsWith(TMP) }.map { it.name }.toSet()
            for (entrada in entradasDownloads(ctx)) if (entrada.pasta !in pastas) {
                try { ctx.contentResolver.delete(entrada.uri, null, null) }
                catch (x: Exception) { erroDownloads("limpa_downloads", x) }
            }
        } catch (x: Exception) { erroDownloads("limpa_downloads", x) }
    }

    private fun escreverZip(saida: OutputStream, nome: String, arquivos: List<File>) {
        ZipOutputStream(BufferedOutputStream(saida, 1 shl 16)).use { z ->
            z.setLevel(Deflater.BEST_SPEED)   // PNG e JPEG já vêm comprimidos; o DNG é grande e o tempo pesa mais que o tamanho
            val buf = ByteArray(1 shl 16)
            for (a in arquivos) {
                z.putNextEntry(ZipEntry(nome + "/" + a.name))
                a.inputStream().use { inp -> while (true) { val n = inp.read(buf); if (n < 0) break; z.write(buf, 0, n) } }
                z.closeEntry()
            }
        }
    }

    /** Mesma lista fechada da revisão. API 26–28 conserva o zip interno e o FileProvider. Rodar em IO. */
    suspend fun montarZip(ctx: Context, pc: Pacote): ZipPronto? = travaArquivos.withLock {
        val inicio = SystemClock.elapsedRealtime()
        val downloads = Build.VERSION.SDK_INT >= 29
        var pendente: Uri? = null
        try {
            val agora = anexos(ctx, pc.pasta)
            if (agora == null || agora.map { it.name } != pc.anexos.map { it.name } || agora.any { !it.isFile }) {
                DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to "zip", "motivo" to (if (agora == null) "arquivo_sumiu" else "lista_mudou")))
                return@withLock null
            }
            val nome = "rajada_" + pc.pasta.name
            if (downloads) {
                val entradas = entradasDownloads(ctx).filter { it.pasta == pc.pasta.name }
                val pronta = entradas.filter { !it.pendente && it.bytes > 0 }.maxByOrNull { it.bytes }
                if (pronta != null) return@withLock ZipPronto(pronta.uri, pronta.bytes, "downloads", SystemClock.elapsedRealtime() - inicio, true)
                val resolver = ctx.contentResolver
                // Incompletas não são reutilizadas; uma falha ao apagar não impede criar a nova.
                for (entrada in entradas) {
                    try { resolver.delete(entrada.uri, null, null) }
                    catch (x: Exception) { erroDownloads("limpa_downloads", x) }
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, CAMINHO_DOWNLOADS)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$nome.zip")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: throw java.io.IOException("inserir_downloads")
                pendente = uri
                val nomeReal = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: throw java.io.IOException("nome_downloads")
                if (NOME_ZIP_DOWNLOADS.matchEntire(nomeReal)?.groupValues?.get(1) != pc.pasta.name)
                    throw java.io.IOException("nome_downloads_invalido")
                escreverZip(resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("abrir_downloads"), nome, agora)
                // O stream (inclusive o diretório final do ZIP) já foi fechado antes de publicar.
                val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: throw java.io.IOException("tamanho_downloads")
                if (bytes <= 0) throw java.io.IOException("zip_vazio")
                if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                    throw java.io.IOException("publicar_downloads")
                pendente = null
                ZipPronto(uri, bytes, "downloads", SystemClock.elapsedRealtime() - inicio, false)
            } else {
                val dir = raizZip(ctx)
                if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("mkdirs")
                dir.listFiles()?.forEach { it.delete() }
                val tmp = File(dir, "$nome.parcial")
                escreverZip(FileOutputStream(tmp), nome, agora)
                val zip = File(dir, "$nome.zip")
                if (!tmp.renameTo(zip)) { tmp.delete(); throw java.io.IOException("renomear") }
                ZipPronto(FileProvider.getUriForFile(ctx, ctx.packageName + ".arquivos", zip), zip.length(), "interno", SystemClock.elapsedRealtime() - inicio, false)
            }
        } catch (x: Exception) {
            DoisSensores.ev("erro", linkedMapOf("onde" to "rajada", "acao" to (if (downloads) "zip_downloads" else "zip"), "classe" to x.javaClass.simpleName))
            null
        } finally {
            pendente?.let { uri ->
                try { ctx.contentResolver.delete(uri, null, null) }
                catch (x: Exception) { erroDownloads("limpa_downloads", x) }
            }
        }
    }

    /** Envia a URI do .zip, com concessão só de leitura. Só por toque do dono, depois da revisão. */
    fun compartilharZip(ctx: Context, uri: Uri, rodada: String, arquivos: Int, bytes: Long, destino: String, msZip: Long, reaproveitado: Boolean) {
        try {
            val envio = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(ctx.contentResolver, "rajada", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(envio, "Rajada de teste").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            DoisSensores.ev("rajada_compartilhar", linkedMapOf("rodada" to rodada, "arquivos" to arquivos, "bytes" to bytes,
                "destino" to destino, "ms_zip" to msZip, "reaproveitado" to reaproveitado))
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
