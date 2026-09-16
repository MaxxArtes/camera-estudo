package br.maxymus.cameraestudo

import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.DisposableEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.HdrAuto
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

// Paleta do ícone: coral → rosa no corpo, fundo quase preto, aro branco, LED verde.
private val Amarelo = Color(0xFFFF5A5F)       // nome mantido no código; a cor é o coral do ícone
private val Rosa = Color(0xFFF0325A)
private val Verde = Color(0xFF22C55E)
private val Fundo = Color(0xFF0E0E12)
private val Painel = Color(0xFF16161B)

/** Modos da linha (naLinha) e do menu "Mais" (LENTA e MACRO). `video` = usa VideoCapture. */
enum class Modo(val rotulo: String, val naLinha: Boolean, val video: Boolean) {
    PRO("PRO", true, false), VIDEO("VÍDEO", true, true), FOTO("FOTO", true, false), RETRATO("RETRATO", true, false),
    DOCUMENTO("DOCUMENTO", true, false), LENTA("LENTA", false, true), MACRO("MACRO", false, false), TELA("TELA", false, false)
}

/**
 * Tela principal no desenho de referência: barra de cima (flash, gaveta, ajustes), visualização
 * com grade e nível, chips de zoom, linha de modos, miniatura + obturador + trocar câmera.
 *
 * CameraX em uma frase: você descreve "casos de uso" (Preview, ImageCapture, VideoCapture),
 * amarra ao ciclo de vida da tela e ele cuida do Camera2 por baixo (abrir, configurar, fechar).
 * Foto e vídeo são ligados separadamente, porque nem todo aparelho aceita os três de uma vez.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(abrirGaleria: () -> Unit) {
    val contexto = LocalContext.current
    val dono = LocalLifecycleOwner.current
    val escopo = rememberCoroutineScope()

    // ---- estado (remember = sobrevive à recomposição; mudou = redesenha) ----
    var modo by remember { mutableStateOf(Modo.FOTO) }
    var lente by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var timer by remember { mutableIntStateOf(0) }            // 0, 3 ou 10 segundos
    var proporcao by remember { mutableIntStateOf(AspectRatio.RATIO_4_3) }
    var grade by remember { mutableStateOf(true) }
    var nivel by remember { mutableStateOf(false) }
    var gaveta by remember { mutableStateOf(false) }
    var ultima by remember { mutableStateOf<Uri?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // foco por toque: anel no ponto, trava por toque longo (AE/AF), régua de luz ao lado do anel
    var focoPonto by remember { mutableStateOf<Offset?>(null) }
    var focoTravado by remember { mutableStateOf(false) }
    var focoEv by remember { mutableIntStateOf(0) }
    var focoSerie by remember { mutableIntStateOf(0) }                 // muda a cada toque: cancela o sumiço anterior
    // processamento do próprio aparelho (CameraX Extensions) e modo de captura
    var extensao by remember { mutableIntStateOf(ExtensionMode.NONE) }
    var extensoesDisponiveis by remember { mutableStateOf(listOf(ExtensionMode.NONE)) }
    var qualidadeMax by remember { mutableStateOf(true) }
    var desfoque by remember { mutableIntStateOf(5) }                   // Retrato por software: 1..10
    var retratoSoftware by remember { mutableStateOf(false) }          // força o nosso retrato mesmo com bokeh do aparelho
    // intensidade da extensão do fabricante (bokeh/HDR/noite): CameraX 1.4 + Android 14 + apoio do fabricante
    var gerenteExt by remember { mutableStateOf<ExtensionsManager?>(null) }
    var forcaDisponivel by remember { mutableStateOf(false) }
    var forca by remember { mutableIntStateOf(50) }              // MAXIMIZE_QUALITY: deixa o HAL fazer o multi-quadro dele
    var ocupado by remember { mutableStateOf(false) }
    var contagem by remember { mutableIntStateOf(0) }
    var gravacao by remember { mutableStateOf<Recording?>(null) }
    var segundosGravando by remember { mutableIntStateOf(0) }
    var bokehNativo by remember { mutableStateOf(false) }      // o aparelho tem retrato de fábrica?
    var menuMais by remember { mutableStateOf(false) }
    var processandoLenta by remember { mutableStateOf(false) }
    // modo Pro: valores manuais (null = automático). ISO e tempo em unidades do Camera2.
    var proEv by remember { mutableIntStateOf(0) }
    var proIso by remember { mutableStateOf<Int?>(null) }
    var proTempoNs by remember { mutableStateOf<Long?>(null) }
    var proFoco by remember { mutableStateOf<Float?>(null) }     // dioptrias: 0 = infinito, maior = mais perto
    var proWb by remember { mutableIntStateOf(CaptureRequest.CONTROL_AWB_MODE_AUTO) }
    var faixaIso by remember { mutableStateOf(100 to 3200) }
    var faixaTempo by remember { mutableStateOf(100_000L to 100_000_000L) }   // 1/10000 s a 1/10 s
    var focoMin by remember { mutableStateOf(0f) }                             // maior dioptria = foco mais perto
    var processandoDoc by remember { mutableStateOf(false) }
    var hdr by remember { mutableStateOf(false) }               // Foto: 3 exposições (−2, 0, +2 EV) fundidas (Fusao.hdr)
    var rajada by remember { mutableStateOf(false) }            // Foto/Pro/Documento/Tela/Macro: 4 quadros fundidos (Fusao.rajada)
    var fase by remember { mutableStateOf<String?>(null) }      // texto de progresso da rajada/HDR
    var conferir by remember { mutableStateOf(true) }            // Documento/Tela: abrir o editor de cantos antes de gravar
    var edicao by remember { mutableStateOf<Edicao?>(null) }
    var processandoRetrato by remember { mutableStateOf(false) }
    val inclinacao by lembrarInclinacao(nivel)
    var novaVersao by remember { mutableStateOf<Atualizador.Versao?>(null) }
    var avisoAtualizacao by remember { mutableStateOf(true) }
    val instalada = remember { Atualizador.versaoInstalada(contexto) }

    // Ao abrir: consulta o canal de atualização em segundo plano (falha em silêncio se estiver sem rede).
    LaunchedEffect(Unit) {
        val v = Atualizador.consultar()
        if (v != null && v.codigo > instalada.second) novaVersao = v
    }

    // ---- objetos do CameraX ----
    val previewView = remember { PreviewView(contexto).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    // rajada/HDR nossos pedem latência mínima (quadros próximos); foto simples com "Qualidade" deixa o HAL processar
    val capturaRapida = !qualidadeMax || rajada || hdr
    val imageCapture = remember(proporcao, capturaRapida) {
        @Suppress("DEPRECATION")
        ImageCapture.Builder().setCaptureMode(if (capturaRapida) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setTargetAspectRatio(proporcao).build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build())
    }
    val pedirAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { ultima = Fotos.listar(contexto, limite = 1).firstOrNull()?.uri }

    // (Re)liga a câmera quando muda lente, modo ou proporção.
    LaunchedEffect(lente, modo, proporcao, capturaRapida, extensao, retratoSoftware) {
        val provider = ProcessCameraProvider.getInstance(contexto).get()
        @Suppress("DEPRECATION")
        val preview = Preview.Builder().setTargetAspectRatio(proporcao).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        var seletor = CameraSelector.Builder().requireLensFacing(lente).build()
        bokehNativo = false
        if (modo == Modo.MACRO && lente == CameraSelector.LENS_FACING_BACK) {
            // Macro: entre as câmeras traseiras, a que foca mais perto (maior distância mínima em dioptrias)
            val melhor = provider.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                .maxByOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f }
            if (melhor != null) seletor = melhor.cameraSelector
        }
        // Extensions do fabricante: Retrato usa BOKEH; Foto usa o que o usuário escolheu na gaveta ("Aparelho")
        val gerente = withContext(Dispatchers.IO) { runCatching { ExtensionsManager.getInstanceAsync(contexto, provider).get() }.getOrNull() }
        gerenteExt = gerente
        var extensaoAtiva = ExtensionMode.NONE
        if (gerente != null) {
            extensoesDisponiveis = listOf(ExtensionMode.NONE) + listOf(ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.FACE_RETOUCH).filter { gerente.isExtensionAvailable(seletor, it) }
            if (modo == Modo.RETRATO && !retratoSoftware && gerente.isExtensionAvailable(seletor, ExtensionMode.BOKEH)) {
                seletor = gerente.getExtensionEnabledCameraSelector(seletor, ExtensionMode.BOKEH); bokehNativo = true; extensaoAtiva = ExtensionMode.BOKEH
            } else if (modo == Modo.FOTO && extensao != ExtensionMode.NONE && gerente.isExtensionAvailable(seletor, extensao)) {
                seletor = gerente.getExtensionEnabledCameraSelector(seletor, extensao); extensaoAtiva = extensao
            }
        } else extensoesDisponiveis = listOf(ExtensionMode.NONE)
        focoPonto = null; focoTravado = false; focoEv = 0
        provider.unbindAll()
        camera = runCatching {
            if (modo.video) provider.bindToLifecycle(dono, seletor, preview, videoCapture)
            else provider.bindToLifecycle(dono, seletor, preview, imageCapture)
        }.onFailure { Telemetria.evento("erro", mapOf("onde" to "abrir_camera", "modo" to modo.name.lowercase(), "msg" to (it.message ?: ""))); Toast.makeText(contexto, "Não consegui abrir a câmera: ${it.message}", Toast.LENGTH_LONG).show() }.getOrNull()
        // intensidade da extensão: o próprio aparelho diz se aceita (Android 14+, fabricante); só então a régua aparece
        forcaDisponivel = false
        if (extensaoAtiva != ExtensionMode.NONE && gerente != null) camera?.let { cam ->
            runCatching {
                val info = gerente.getCameraExtensionsInfo(cam.cameraInfo)
                forcaDisponivel = info.isExtensionStrengthAvailable
                if (forcaDisponivel) { forca = info.extensionStrength?.value ?: 50; gerente.getCameraExtensionsControl(cam.cameraControl)?.setExtensionStrength(forca) }
            }
        }
        Telemetria.evento("camera", mapOf("modo" to modo.name.lowercase(), "lente" to (if (lente == CameraSelector.LENS_FACING_FRONT) "frontal" else "traseira"), "bokeh_nativo" to bokehNativo, "proporcao" to (if (proporcao == AspectRatio.RATIO_16_9) "16:9" else "4:3"),
            "extensao" to nomeExtensao(extensaoAtiva), "extensoes" to extensoesDisponiveis.map { nomeExtensao(it) }, "captura_rapida" to capturaRapida, "forca_disponivel" to forcaDisponivel, "android" to android.os.Build.VERSION.SDK_INT))
        zoom = 1f
        camera?.cameraControl?.setZoomRatio(1f)
        // faixas do sensor para o modo Pro (e foco mais perto para o Macro)
        camera?.let { cam ->
            val c2 = Camera2CameraInfo.from(cam.cameraInfo)
            c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { faixaIso = it.lower to it.upper }
            c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { faixaTempo = maxOf(it.lower, 50_000L) to minOf(it.upper, 500_000_000L) }
            focoMin = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val opts = CaptureRequestOptions.Builder()
            if (modo == Modo.MACRO && focoMin > 0f) {
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focoMin)
            }
            if (modo == Modo.TELA) {
                // tela: sem flash (reflexo) e antibanding automático (faixas do refresh/luz)
                flash = ImageCapture.FLASH_MODE_OFF
                opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            }
            if (modo != Modo.PRO) { proIso = null; proTempoNs = null; proFoco = null; proEv = 0; proWb = CaptureRequest.CONTROL_AWB_MODE_AUTO }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts.build())
            cam.cameraControl.setExposureCompensationIndex(0)
        }
    }
    LaunchedEffect(flash) { imageCapture.flashMode = flash }
    LaunchedEffect(proEv, proIso, proTempoNs, proFoco, proWb, modo) {
        val cam = camera ?: return@LaunchedEffect
        if (modo != Modo.PRO) return@LaunchedEffect
        val faixaEv = cam.cameraInfo.exposureState.exposureCompensationRange
        cam.cameraControl.setExposureCompensationIndex(proEv.coerceIn(faixaEv.lower, faixaEv.upper))
        val opts = CaptureRequestOptions.Builder()
        if (proIso != null && proTempoNs != null) {
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            opts.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, proIso!!)
            opts.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, proTempoNs!!)
        } else opts.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        if (proFoco != null) {
            opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            opts.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, proFoco!!)
        } else opts.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        opts.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, proWb)
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts.build())
    }
    LaunchedEffect(gravacao) { segundosGravando = 0; while (gravacao != null) { delay(1000); segundosGravando++ } }

    // Deslizar para o lado troca o modo (só entre os prontos); para cima abre os ajustes.
    fun trocaModo(passo: Int) {
        if (gravacao != null) return
        val linha = Modo.entries.filter { it.naLinha }
        val i = linha.indexOf(modo).let { if (it < 0) linha.indexOf(Modo.FOTO) else it }
        modo = linha[(i + passo + linha.size) % linha.size]
    }

    fun aplicaZoom(alvo: Float) {
        val cam = camera ?: return
        val estado = cam.cameraInfo.zoomState.value ?: return
        zoom = alvo.coerceIn(estado.minZoomRatio, estado.maxZoomRatio)
        cam.cameraControl.setZoomRatio(zoom)
    }

    // Documento/Tela, passo 2: grava o recorte escolhido (quad null = só realce), registra e libera o botão
    fun concluirDocumento(uri: Uri, d: Documento.Deteccao, tela: Boolean, quad: FloatArray?, conferido: Boolean, quadros: List<ByteArray>? = null, rot: Int = 0) {
        edicao = null; processandoDoc = true
        escopo.launch {
            val t = Telemetria.agora()
            val r = Documento.aplicar(contexto, uri, quad, tela, d.metodo, quadros, rot)
            Telemetria.evento("scanner_aplicar", mapOf("ms" to Telemetria.ms(t), "quadros" to (quadros?.size ?: 1), "recortou" to (r?.recortou ?: false), "modo" to (if (tela) "tela" else "folha")))
            RegistroScanner.anota(contexto, tela, d, quad, conferido, r)
            d.previa.recycle()
            processandoDoc = false; ocupado = false; ultima = uri
            val alvo = if (tela) "tela" else "folha"
            Toast.makeText(contexto, when { r == null -> "Não consegui tratar; salvei a foto."; r.recortou -> "${alvo.replaceFirstChar { it.uppercase() }} recortada (${r.metodo})."; else -> "Sem recorte; salvei a foto tratada." }, Toast.LENGTH_SHORT).show()
        }
    }

    // Documento/Tela, passo 1: detecta e abre o editor (ou grava direto, se o usuário desligou a conferência)
    fun trataDocumento(uri: Uri, quadros: List<ByteArray>? = null, rot: Int = 0) {
        val tela = modo == Modo.TELA
        processandoDoc = true
        escopo.launch {
            val t = Telemetria.agora()
            val d = Documento.detectar(contexto, uri, tela)
            Telemetria.evento("scanner_detectar", mapOf("ms" to Telemetria.ms(t), "achou" to (d?.quad != null), "metodo" to (d?.metodo ?: "falha"), "modo" to (if (tela) "tela" else "folha")))
            processandoDoc = false
            when {
                d == null -> { ocupado = false; ultima = uri; Toast.makeText(contexto, "Não consegui analisar; salvei a foto.", Toast.LENGTH_SHORT).show() }
                conferir -> edicao = Edicao(uri, d, tela, quadros, rot)
                else -> concluirDocumento(uri, d, tela, d.quad, false, quadros, rot)
            }
        }
    }

    /** Uma captura em memória: bytes do JPEG e a rotação que o sensor pede. */
    suspend fun capturaBytes(): Pair<ByteArray, Int>? = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(ContextCompat.getMainExecutor(contexto), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buf = image.planes[0].buffer; val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                val rot = image.imageInfo.rotationDegrees; image.close()
                cont.resume(bytes to rot)
            }
            override fun onError(e: ImageCaptureException) { cont.resume(null) }
        })
    }

    /**
     * Várias fotos em sequência e uma só gravada: HDR (3 exposições por compensação de EV) ou rajada
     * (4 quadros iguais). A foto fundida segue o mesmo caminho da foto simples (inclusive o scanner).
     */
    fun tiraVarias(comHdr: Boolean) {
        ocupado = true
        escopo.launch {
            val cam = camera
            val estadoEv = cam?.cameraInfo?.exposureState
            val evOriginal = estadoEv?.exposureCompensationIndex ?: 0
            // HDR: o 1º quadro (0 EV) decide — cena escura vira Noite (mais 3 iguais + sombras), clara vira bracket −2/+2
            val quadros = ArrayList<Pair<ByteArray, Int>>()
            val tempos = ArrayList<Long>(); val tInicio = Telemetria.agora()
            var noite = false; var brilho = -1
            try {
                fase = (if (comHdr) "HDR" else "Rajada") + " 1/${if (comHdr) 3 else 4}: segure firme"
                var tq = Telemetria.agora()
                val primeiro = capturaBytes()
                tempos += Telemetria.ms(tq)
                if (primeiro != null) {
                    quadros += primeiro
                    val bracket = comHdr && estadoEv != null && estadoEv.isExposureCompensationSupported
                    if (comHdr) brilho = withContext(Dispatchers.Default) { Fusao.brilhoMedio(primeiro.first) }
                    noite = comHdr && brilho < Fusao.LIMIAR_ESCURO
                    val indices: List<Int?> = if (bracket && !noite) {
                        val passo = estadoEv.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 0.5f
                        listOf(-2f, 2f).map { ev -> Math.round(ev / passo).coerceIn(estadoEv.exposureCompensationRange.lower, estadoEv.exposureCompensationRange.upper) }
                    } else List(3) { null }
                    for ((i, idx) in indices.withIndex()) {
                        fase = (if (noite) "Noite" else if (comHdr) "HDR" else "Rajada") + " ${i + 2}/${indices.size + 1}: segure firme"
                        tq = Telemetria.agora()
                        if (idx != null) cam?.cameraControl?.setExposureCompensationIndex(idx)?.let { f -> withContext(Dispatchers.IO) { runCatching { f.get() } } }
                        quadros += capturaBytes() ?: break
                        tempos += Telemetria.ms(tq)
                    }
                }
            } finally { if (comHdr) cam?.cameraControl?.setExposureCompensationIndex(evOriginal) }
            val tipoSeq = when { !comHdr -> "rajada"; noite -> "noite"; else -> "hdr" }
            if (quadros.size < 2) { fase = null; ocupado = false; Telemetria.evento("erro", mapOf("onde" to "captura_" + tipoSeq, "quadros" to quadros.size, "ms_quadros" to tempos)); Toast.makeText(contexto, "Não consegui capturar a sequência.", Toast.LENGTH_SHORT).show(); return@launch }
            val scanner = modo == Modo.DOCUMENTO || modo == Modo.TELA
            val bracketReal = comHdr && !noite && quadros.size == 3
            fase = when { scanner -> "Guardando os quadros..."; bracketReal -> "Fundindo as exposições..."; noite -> "Noite: fundindo e levantando sombras..."; else -> "Fundindo ${quadros.size} quadros..." }
            val tFusao = Telemetria.agora()
            val uri = withContext(Dispatchers.Default) {
                runCatching {
                    // scanner: grava só o 1º quadro agora; a fusão acontece depois do recorte, com os cantos conferidos
                    val fundido = if (scanner) Fusao.decodifica(quadros[0].first, 2400)!!
                        else {
                            // bracket foi capturado na ordem 0, −2, +2; o Mertens alinha tudo à exposição do meio, então reordena para −2, 0, +2
                            val ordem = if (bracketReal) listOf(quadros[1], quadros[0], quadros[2]) else quadros
                            val bitmaps = ordem.mapNotNull { Fusao.decodifica(it.first, if (bracketReal) 1600 else 2000) }
                            when { bracketReal -> Fusao.hdr(bitmaps, reciclar = true); noite -> Fusao.noite(bitmaps, reciclar = true); else -> Fusao.rajada(bitmaps, reciclar = true) }
                        }
                    val pronto = Fusao.gira(fundido, quadros[0].second)
                    val destino = contexto.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Fotos.novaEntrada())
                    if (destino != null) contexto.contentResolver.openOutputStream(destino)?.use { pronto.compress(Bitmap.CompressFormat.JPEG, 93, it) }
                    pronto.recycle()
                    destino
                }.getOrNull()
            }
            fase = null
            Telemetria.evento("sequencia", mapOf("tipo" to tipoSeq, "modo" to modo.name.lowercase(), "quadros" to quadros.size, "ms_quadros" to tempos, "ms_captura" to tempos.sum(),
                "ms_fusao" to Telemetria.ms(tFusao), "ms_total" to Telemetria.ms(tInicio), "brilho" to brilho, "bytes_quadro" to quadros[0].first.size, "ok" to (uri != null),
                "lente" to (if (lente == CameraSelector.LENS_FACING_FRONT) "frontal" else "traseira"), "flash" to flash, "zoom" to zoom))
            when {
                uri == null -> { ocupado = false; Toast.makeText(contexto, "A fusão falhou; nada foi gravado.", Toast.LENGTH_SHORT).show() }
                scanner -> trataDocumento(uri, quadros.map { it.first }, quadros[0].second)
                else -> { ocupado = false; ultima = uri }
            }
        }
    }

    fun tiraFoto() {
        if (modo == Modo.FOTO && hdr) { tiraVarias(true); return }
        if (rajada && !modo.video && modo != Modo.RETRATO) { tiraVarias(false); return }
        ocupado = true
        val tFoto = Telemetria.agora()
        val saida = ImageCapture.OutputFileOptions.Builder(contexto.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Fotos.novaEntrada()).build()
        imageCapture.takePicture(saida, ContextCompat.getMainExecutor(contexto), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                val uri = r.savedUri
                Telemetria.evento("foto", mapOf("modo" to modo.name.lowercase(), "ms" to Telemetria.ms(tFoto), "lente" to (if (lente == CameraSelector.LENS_FACING_FRONT) "frontal" else "traseira"),
                    "flash" to flash, "zoom" to zoom, "bokeh_nativo" to bokehNativo, "iso" to proIso, "tempo_ns" to proTempoNs, "ev" to proEv, "ok" to (uri != null)))
                if ((modo == Modo.DOCUMENTO || modo == Modo.TELA) && uri != null) {
                    trataDocumento(uri)
                } else if (modo == Modo.RETRATO && !bokehNativo && uri != null) {
                    processandoRetrato = true
                    escopo.launch {
                        val tR = Telemetria.agora()
                        val erro = Retrato.aplicar(contexto, uri, desfoque)
                        Telemetria.evento("retrato_software", mapOf("ms" to Telemetria.ms(tR), "achou_pessoa" to (erro == null), "desfoque" to desfoque, "erro" to erro))
                        processandoRetrato = false; ocupado = false; ultima = uri
                        if (erro != null) Toast.makeText(contexto, "Retrato por software falhou ($erro); salvei sem desfoque.", Toast.LENGTH_LONG).show()
                    }
                } else { ocupado = false; ultima = uri }
            }
            override fun onError(e: ImageCaptureException) { ocupado = false; Telemetria.evento("erro", mapOf("onde" to "foto", "msg" to (e.message ?: ""))); Toast.makeText(contexto, "Falhou: ${e.message}", Toast.LENGTH_LONG).show() }
        })
    }

    fun disparar() {
        if (ocupado) return
        if (modo.video) {
            val atual = gravacao
            if (atual != null) { atual.stop(); return }
            val temAudio = ContextCompat.checkSelfPermission(contexto, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!temAudio) pedirAudio.launch(Manifest.permission.RECORD_AUDIO)
            val opcoes = MediaStoreOutputOptions.Builder(contexto.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(Fotos.novoVideo()).build()
            var pendente = videoCapture.output.prepareRecording(contexto, opcoes)
            if (temAudio) pendente = pendente.withAudioEnabled()
            gravacao = pendente.start(ContextCompat.getMainExecutor(contexto)) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    gravacao = null
                    if (ev.hasError()) Toast.makeText(contexto, "Vídeo falhou (${ev.error})", Toast.LENGTH_LONG).show()
                    else if (modo == Modo.LENTA) {
                        processandoLenta = true
                        escopo.launch {
                            val novo = Lenta.esticar(contexto, ev.outputResults.outputUri, 4)
                            processandoLenta = false
                            if (novo != null) { Fotos.apagar(contexto, ev.outputResults.outputUri); ultima = novo }
                            else { ultima = ev.outputResults.outputUri; Toast.makeText(contexto, "Não consegui esticar; salvei o vídeo normal.", Toast.LENGTH_SHORT).show() }
                        }
                    } else ultima = ev.outputResults.outputUri
                }
            }
            return
        }
        if (timer > 0) {
            escopo.launch {
                contagem = timer
                while (contagem > 0) { delay(1000); contagem-- }
                tiraFoto()
            }
        } else tiraFoto()
    }

    // teclas de volume disparam enquanto esta tela está viva
    DisposableEffect(Unit) { Atalhos.aoDisparar = { disparar() }; onDispose { Atalhos.aoDisparar = null } }
    // botão voltar do sistema: fecha o editor de cantos (grava sem recorte) ou o painel aberto, em vez de sair do app
    BackHandler(enabled = edicao != null) { edicao?.let { e -> concluirDocumento(e.uri, e.deteccao, e.tela, null, true, e.quadros, e.rot) } }

    Box(modifier = Modifier.fillMaxSize().background(Fundo)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- barra de cima ----
            // ícones fixos: cada um alterna direto; a engrenagem abre a gaveta com tudo
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { flash = proximoFlash(flash) }) {
                    Icon(iconeFlash(flash), contentDescription = "Flash", tint = if (flash == ImageCapture.FLASH_MODE_OFF) Color.White else Amarelo)
                }
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).clickable { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Timer, contentDescription = "Timer", tint = if (timer > 0) Amarelo else Color.White)
                    if (timer > 0) Text("$timer", color = Amarelo, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 2.dp))
                }
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).clickable { proporcao = if (proporcao == AspectRatio.RATIO_16_9) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9 }, contentAlignment = Alignment.Center) {
                    Text(if (proporcao == AspectRatio.RATIO_16_9) "16:9" else "4:3", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { grade = !grade }) { Icon(Icons.Filled.Grid3x3, contentDescription = "Grade", tint = if (grade) Amarelo else Color.White) }
                IconButton(onClick = { nivel = !nivel }) { Icon(Icons.Filled.Straighten, contentDescription = "Nível", tint = if (nivel) Amarelo else Color.White) }
                IconButton(onClick = { gaveta = true }) { Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = Color.White) }
            }

            // ---- visualização (proporção fixa, como no desenho) ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (proporcao == AspectRatio.RATIO_16_9) 9f / 16f else 3f / 4f)
                    .background(Color.Black)
                    // um detector só: 2 dedos = zoom por pinça; 1 dedo = deslizar (lado: modo; cima: ajustes)
                    .pointerInput(camera) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var dx = 0f; var dy = 0f; var pinca = false
                            do {
                                val ev = awaitPointerEvent()
                                val dedos = ev.changes.count { it.pressed }
                                if (dedos >= 2) {
                                    pinca = true
                                    val z = ev.calculateZoom(); if (z != 1f) aplicaZoom(zoom * z)
                                    ev.changes.forEach { it.consume() }
                                } else if (dedos == 1 && !pinca) {
                                    val pan = ev.calculatePan(); dx += pan.x; dy += pan.y
                                }
                            } while (ev.changes.any { it.pressed })
                            if (!pinca) {
                                if (dy < -90f && abs(dx) < 70f) gaveta = true
                                else if (abs(dx) > 90f && abs(dy) < 70f) trocaModo(if (dx < 0) 1 else -1)
                            }
                        }
                    }
                    .pointerInput(camera) {
                        fun foca(toque: Offset, travar: Boolean) {
                            val cam = camera ?: return
                            val ponto = previewView.meteringPointFactory.createPoint(toque.x, toque.y)
                            val acao = FocusMeteringAction.Builder(ponto, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            if (travar) acao.disableAutoCancel()
                            cam.cameraControl.startFocusAndMetering(acao.build())
                            focoPonto = toque; focoTravado = travar; focoEv = 0; focoSerie++
                            cam.cameraControl.setExposureCompensationIndex(0)
                            Telemetria.evento("foco_toque", mapOf("travado" to travar, "modo" to modo.name.lowercase()))
                        }
                        detectTapGestures(onTap = { foca(it, false) }, onLongPress = { foca(it, true) })
                    }
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                if (grade) Canvas(modifier = Modifier.fillMaxSize()) {
                    val cor = Color.White.copy(alpha = 0.55f)
                    for (i in 1..2) {
                        drawLine(cor, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
                        drawLine(cor, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
                    }
                }
                if (nivel) {
                    val nivelado = abs(inclinacao) < 1.5f
                    Box(modifier = Modifier.align(Alignment.Center).width(140.dp).height(2.dp).rotate(-inclinacao).background(if (nivelado) Verde else Color.White.copy(alpha = 0.8f)))
                }
                // anel de foco com régua de luz (arrastar na vertical muda a compensação de exposição); some em 3 s se não estiver travado
                focoPonto?.let { p ->
                    LaunchedEffect(focoSerie) { if (!focoTravado) { delay(3000); if (!focoTravado) focoPonto = null } }
                    val faixa = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                    val cor = if (focoTravado) Amarelo else Color.White
                    Box(modifier = Modifier.offset { IntOffset((p.x - 40.dp.toPx()).roundToInt(), (p.y - 40.dp.toPx()).roundToInt()) }.size(80.dp).border(1.5.dp, cor, RoundedCornerShape(4.dp)))
                    Box(modifier = Modifier.offset { IntOffset((p.x + 48.dp.toPx()).roundToInt(), (p.y - 70.dp.toPx()).roundToInt()) }.width(44.dp).height(140.dp)
                        .pointerInput(focoSerie) {
                            detectVerticalDragGestures { mudanca, delta ->
                                mudanca.consume()
                                val cam = camera ?: return@detectVerticalDragGestures
                                val f = faixa ?: return@detectVerticalDragGestures
                                val novo = (focoEv - delta / 18f).roundToInt().coerceIn(f.lower, f.upper)
                                if (novo != focoEv) { focoEv = novo; cam.cameraControl.setExposureCompensationIndex(novo) }
                                focoSerie++
                            }
                        }, contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.width(2.dp).height(140.dp).background(cor.copy(alpha = 0.6f)))
                        val fracao = faixa?.let { if (it.upper > it.lower) (focoEv - it.lower).toFloat() / (it.upper - it.lower) else 0.5f } ?: 0.5f
                        Icon(Icons.Filled.WbSunny, contentDescription = "Luz", tint = cor, modifier = Modifier.offset { IntOffset(0, ((0.5f - fracao) * 120.dp.toPx()).roundToInt()) }.size(22.dp).clip(CircleShape).background(Color(0x66000000)).padding(2.dp))
                    }
                    if (focoTravado) Text("AE/AF TRAVADO", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp).clip(RoundedCornerShape(6.dp)).background(Amarelo).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                if (contagem > 0) Text("$contagem", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                // card de progresso: sequência, scanner, retrato por software, lenta
                val textoProcesso = fase ?: when {
                    processandoDoc && modo == Modo.TELA -> "Recortando a tela e tirando o moiré..."
                    processandoDoc -> "Recortando e realçando..."
                    processandoRetrato -> "Desfocando o fundo..."
                    processandoLenta -> "Esticando o vídeo (4x)..."
                    else -> null
                }
                textoProcesso?.let {
                    Column(modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xBB000000)).padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Amarelo, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                        Text(it, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                if (processandoLenta) Text("Esticando o vídeo (4x)...", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp))
                if (modo == Modo.MACRO) Text(if (focoMin > 0f) "Macro: chegue perto (foco no mínimo)" else "Macro: esta lente não informa foco mínimo", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp))
                if (modo == Modo.TELA) Text(if (processandoDoc) "Recortando a tela e tirando o moiré..." else "Tela: encha o quadro com a página, sem reflexo", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp))
                if (modo == Modo.DOCUMENTO) Text(if (processandoDoc) "Recortando e realçando..." else "Documento: folha inteira no quadro", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp))
                if (modo == Modo.RETRATO) Text(
                    if (processandoRetrato) "Desfocando o fundo..." else if (bokehNativo) "Retrato do aparelho" else "Retrato: enquadre uma pessoa",
                    color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp)
                )
                val nv = novaVersao
                if (nv != null && avisoAtualizacao) {
                    Row(
                        modifier = Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE6202020)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = Amarelo)
                        Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                            Text("Versão ${nv.nome} disponível", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (nv.mudou.isNotEmpty()) Text(nv.mudou.first().take(60), color = Color(0xFFBDBDBD), fontSize = 11.sp)
                        }
                        Text("Atualizar", color = Amarelo, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable { Atualizador.baixarEInstalar(contexto, nv) }.padding(6.dp))
                        Text("×", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { avisoAtualizacao = false }.padding(horizontal = 6.dp))
                    }
                }
                if (gravacao != null) {
                    Row(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Rosa.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(String.format("%02d:%02d", segundosGravando / 60, segundosGravando % 60), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                // chips de zoom
                Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x66000000)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val minimo = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
                    val opcoes = buildList { if (minimo < 0.99f) add(0.5f); add(1f); add(2f) }
                    // o chip ativo é sempre o mais próximo do zoom atual: acima de 2x o "2" mostra o valor real (3,4x, 5x...)
                    val maisProximo = opcoes.minByOrNull { abs(zoom - it) } ?: 1f
                    opcoes.forEach { alvo ->
                        val selecionado = alvo == maisProximo
                        Box(
                            modifier = Modifier.size(if (selecionado) 38.dp else 32.dp).clip(CircleShape).background(if (selecionado) Color(0x99000000) else Color.Transparent).clickable { aplicaZoom(alvo) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (selecionado) String.format("%.1fx", zoom).replace(".0x", "x") else (if (alvo == 0.5f) "0,5" else alvo.toInt().toString()),
                                color = if (selecionado) Amarelo else Color.White, fontSize = if (selecionado) 13.sp else 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // ---- modos ----
            if (modo == Modo.RETRATO) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(36.dp)) {
                if (bokehNativo && forcaDisponivel) {
                    Text("Desfoque", color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.width(64.dp))
                    ReguaForca(forca, { v -> forca = v; camera?.let { c -> gerenteExt?.getCameraExtensionsControl(c.cameraControl)?.setExtensionStrength(v) } }, Modifier.weight(1f))
                    Text("$forca", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                    Text("Nosso", color = Amarelo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp).clickable { retratoSoftware = true })
                } else if (bokehNativo) {
                    Text("Desfoque do aparelho sem controle (Android ${android.os.Build.VERSION.RELEASE}; precisa 14+ e apoio do fabricante)", color = Color(0xFFBDBDBD), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("Usar o nosso", color = Amarelo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { retratoSoftware = true })
                } else {
                    Text("Desfoque", color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.width(64.dp))
                    Slider(value = desfoque.toFloat(), onValueChange = { desfoque = it.roundToInt().coerceIn(1, 10) }, valueRange = 1f..10f, steps = 8,
                        colors = SliderDefaults.colors(thumbColor = Amarelo, activeTrackColor = Amarelo, inactiveTrackColor = Color(0x33FFFFFF)), modifier = Modifier.weight(1f))
                    Text("$desfoque", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                    if (retratoSoftware) Text("Aparelho", color = Amarelo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp).clickable { retratoSoftware = false })
                }
            }
            if (modo == Modo.FOTO && extensao != ExtensionMode.NONE && forcaDisponivel) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(36.dp)) {
                Text(nomeExtensao(extensao), color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.width(64.dp))
                ReguaForca(forca, { v -> forca = v; camera?.let { c -> gerenteExt?.getCameraExtensionsControl(c.cameraControl)?.setExtensionStrength(v) } }, Modifier.weight(1f))
                Text("$forca", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(30.dp))
            }
            if (modo == Modo.PRO) PainelPro(
                ev = proEv, faixaEv = camera?.cameraInfo?.exposureState?.exposureCompensationRange?.let { it.lower to it.upper } ?: (-2 to 2),
                passoEv = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0.5f, aoEv = { proEv = it },
                iso = proIso, faixaIso = faixaIso, aoIso = { proIso = it; if (proTempoNs == null) proTempoNs = 8_000_000L },
                tempoNs = proTempoNs, faixaTempo = faixaTempo, aoTempo = { proTempoNs = it; if (proIso == null) proIso = faixaIso.first.coerceAtLeast(100) },
                foco = proFoco, focoMax = focoMin, aoFoco = { proFoco = it },
                wb = proWb, aoWb = { proWb = it },
                aoAuto = { proIso = null; proTempoNs = null; proFoco = null; proEv = 0; proWb = CaptureRequest.CONTROL_AWB_MODE_AUTO }
            )
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                Modo.entries.filter { it.naLinha }.forEach { m ->
                    Text(
                        m.rotulo, color = if (m == modo) Amarelo else Color(0xFFBDBDBD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp).clickable { if (gravacao == null) modo = m }
                    )
                }
                val noMais = !modo.naLinha
                Text(
                    if (noMais) modo.rotulo else "MAIS", color = if (noMais) Amarelo else Color(0xFFBDBDBD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp).clickable { if (gravacao == null) menuMais = true }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // ---- miniatura, obturador, trocar câmera ----
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF222222)).clickable(onClick = abrirGaleria)) {
                    if (ultima != null) AsyncImage(model = ultima, contentDescription = "Última", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                val gravando = gravacao != null
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape).padding(6.dp)
                        .clip(if (gravando) RoundedCornerShape(10.dp) else CircleShape)
                        .background(when { gravando -> Rosa; modo.video -> Rosa; ocupado || processandoLenta || processandoDoc -> Color.Gray; else -> Color.White })
                        .clickable { disparar() }
                )
                Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable {
                    if (gravacao == null) lente = if (lente == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Trocar câmera", tint = Color.White)
                }
            }
        }

        // ---- Documento/Tela: conferência dos cantos antes de gravar ----
        edicao?.let { e ->
            EditorQuad(e.deteccao.previa, e.deteccao.quad, e.tela,
                aoUsar = { q -> concluirDocumento(e.uri, e.deteccao, e.tela, q, true, e.quadros, e.rot) },
                aoSemRecorte = { concluirDocumento(e.uri, e.deteccao, e.tela, null, true, e.quadros, e.rot) },
                aoDescartar = { Fotos.apagar(contexto, e.uri); e.deteccao.previa.recycle(); edicao = null; ocupado = false })
        }

        // ---- menu "Mais": modos que não cabem na linha ----
        if (menuMais) {
            ModalBottomSheet(onDismissRequest = { menuMais = false }, containerColor = Painel) {
                Text("Mais modos", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 36.dp), horizontalArrangement = Arrangement.Center) {
                    Ajuste(Icons.Filled.SlowMotionVideo, "Lenta", "Vídeo 4x mais lento", modo == Modo.LENTA) { modo = Modo.LENTA; menuMais = false }
                    Ajuste(Icons.Filled.CenterFocusStrong, "Macro", "Bem de perto", modo == Modo.MACRO) { modo = Modo.MACRO; menuMais = false }
                    Ajuste(Icons.Filled.Monitor, "Tela", "Scanner de monitor", modo == Modo.TELA) { modo = Modo.TELA; menuMais = false }
                }
            }
        }

        // ---- gaveta de ajustes ----
        if (gaveta) {
            val estadoGaveta = rememberModalBottomSheetState()
            ModalBottomSheet(onDismissRequest = { gaveta = false }, sheetState = estadoGaveta, containerColor = Painel) {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.Center) {
                    item { Ajuste(iconeFlash(flash), "Flash", rotuloFlash(flash), flash != ImageCapture.FLASH_MODE_OFF) { flash = proximoFlash(flash) } }
                    item { Ajuste(Icons.Filled.Timer, "Timer", if (timer == 0) "Desativado" else "${timer} s", timer > 0) { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } } }
                    item { Ajuste(Icons.Filled.Crop, "Recorte", if (conferir) "Conferir cantos" else "Automático", conferir) { conferir = !conferir } }
                    item { var tel by remember { mutableStateOf(Telemetria.ligada) }; Ajuste(Icons.Filled.Timeline, "Telemetria", if (tel) "Enviando" else "Desligada", tel) { tel = Telemetria.alternar() } }
                    item { Ajuste(Icons.Filled.Share, "Registro", "Scanner: ${RegistroScanner.linhas(contexto)}", false) { if (!RegistroScanner.compartilhar(contexto)) Toast.makeText(contexto, "Nenhuma digitalização registrada ainda.", Toast.LENGTH_SHORT).show(); gaveta = false } }
                    item { Ajuste(Icons.Filled.AspectRatio, "Proporção", if (proporcao == AspectRatio.RATIO_16_9) "16:9" else "4:3", true) { proporcao = if (proporcao == AspectRatio.RATIO_16_9) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9 } }
                    item { Ajuste(Icons.Filled.AutoAwesome, "Aparelho", if (extensoesDisponiveis.size == 1) "Sem extensão" else nomeExtensao(extensao), extensao != ExtensionMode.NONE) {
                        val i = extensoesDisponiveis.indexOf(extensao); extensao = extensoesDisponiveis[(i + 1) % extensoesDisponiveis.size]
                        if (extensoesDisponiveis.size == 1) Toast.makeText(contexto, "Este aparelho não expõe HDR/Noite pelo CameraX Extensions.", Toast.LENGTH_SHORT).show()
                    } }
                    item { Ajuste(Icons.Filled.HighQuality, "Qualidade", if (qualidadeMax) "Máxima" else "Rápida", qualidadeMax) { qualidadeMax = !qualidadeMax } }
                    item { Ajuste(Icons.Filled.HdrAuto, "HDR", if (hdr) "3 exposições" else "Desligado", hdr) { hdr = !hdr } }
                    item { Ajuste(Icons.Filled.BurstMode, "Rajada", if (rajada) "4 quadros" else "Desligada", rajada) { rajada = !rajada } }
                    item { Ajuste(Icons.Filled.Grid3x3, "Grade", if (grade) "Ativado" else "Desativado", grade) { grade = !grade } }
                    item { Ajuste(Icons.Filled.Straighten, "Nível", if (nivel) "Ativado" else "Desativado", nivel) { nivel = !nivel } }
                    item { Ajuste(Icons.Filled.Tonality, "Filtro", "Nenhum", false) { Toast.makeText(contexto, "Filtros: em breve", Toast.LENGTH_SHORT).show() } }
                    item {
                        val nv = novaVersao
                        Ajuste(Icons.Filled.SystemUpdate, "Atualizar", if (nv != null) "Nova ${nv.nome}" else "Atual ${instalada.first}", nv != null) {
                            if (nv != null) { gaveta = false; Atualizador.baixarEInstalar(contexto, nv) }
                            else escopo.launch {
                                val v = Atualizador.consultar()
                                if (v == null) Toast.makeText(contexto, "Não consegui consultar o canal (sem rede?)", Toast.LENGTH_SHORT).show()
                                else if (v.codigo > instalada.second) { novaVersao = v; avisoAtualizacao = true; Toast.makeText(contexto, "Versão ${v.nome} disponível", Toast.LENGTH_SHORT).show() }
                                else Toast.makeText(contexto, "Você já está na versão mais recente (${instalada.first})", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    item { Ajuste(Icons.Filled.MoreHoriz, "Mais", "", false) { gaveta = false } }
                }
            }
        }
    }
}

/** Régua 0..100 da intensidade de uma extensão do fabricante (bokeh, HDR, noite). */
@Composable
private fun ReguaForca(valor: Int, aoMudar: (Int) -> Unit, modifier: Modifier) {
    Slider(value = valor.toFloat(), onValueChange = { aoMudar(it.roundToInt().coerceIn(0, 100)) }, valueRange = 0f..100f,
        colors = SliderDefaults.colors(thumbColor = Amarelo, activeTrackColor = Amarelo, inactiveTrackColor = Color(0x33FFFFFF)), modifier = modifier)
}

private fun nomeExtensao(m: Int) = when (m) { ExtensionMode.AUTO -> "Auto"; ExtensionMode.HDR -> "HDR"; ExtensionMode.NIGHT -> "Noite"; ExtensionMode.FACE_RETOUCH -> "Retoque"; ExtensionMode.BOKEH -> "Bokeh"; else -> "Desligado" }

/** Foto de Documento/Tela esperando o usuário conferir os cantos. */
private class Edicao(val uri: Uri, val deteccao: Documento.Deteccao, val tela: Boolean, val quadros: List<ByteArray>? = null, val rot: Int = 0)

@Composable
private fun Ajuste(icone: ImageVector, titulo: String, valor: String, ativo: Boolean, aoTocar: () -> Unit) {
    Column(
        modifier = Modifier.width(96.dp).padding(vertical = 12.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = aoTocar).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(if (ativo) Amarelo else Color(0xFF26262C)), contentAlignment = Alignment.Center) {
            Icon(icone, contentDescription = titulo, tint = Color.White)
        }
        Text(titulo, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(valor, color = Color(0xFF9E9E9E), fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp)
    }
}

private fun proximoFlash(f: Int) = when (f) {
    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
    else -> ImageCapture.FLASH_MODE_OFF
}
private fun rotuloFlash(f: Int) = when (f) { ImageCapture.FLASH_MODE_AUTO -> "Auto"; ImageCapture.FLASH_MODE_ON -> "Ligado"; else -> "Desligado" }
private fun iconeFlash(f: Int) = when (f) { ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto; ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn; else -> Icons.Filled.FlashOff }


/** Controles manuais do modo Pro. null = automático. */
/**
 * Painel Pro no formato das câmeras de celular: faixa horizontal de chips (ISO, S, EV, WB, MF) com o valor
 * atual; tocar num chip abre só o controle daquele parâmetro logo abaixo; tocar de novo fecha.
 */
@Composable
private fun PainelPro(
    ev: Int, faixaEv: Pair<Int, Int>, passoEv: Float, aoEv: (Int) -> Unit,
    iso: Int?, faixaIso: Pair<Int, Int>, aoIso: (Int) -> Unit,
    tempoNs: Long?, faixaTempo: Pair<Long, Long>, aoTempo: (Long) -> Unit,
    foco: Float?, focoMax: Float, aoFoco: (Float) -> Unit,
    wb: Int, aoWb: (Int) -> Unit, aoAuto: () -> Unit
) {
    var aberto by remember { mutableStateOf<String?>(null) }
    val corSlider = SliderDefaults.colors(thumbColor = Amarelo, activeTrackColor = Amarelo, inactiveTrackColor = Color(0x33FFFFFF))
    val wbs = listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO to "Auto", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "2800K", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "4000K",
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "5500K", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "6500K")
    val valorEv = if (ev == 0) "0.0" else String.format(java.util.Locale.US, "%+.1f", ev * passoEv)
    val valorTempo = tempoNs?.let { "1/${(1_000_000_000L / it).coerceAtLeast(1)}" } ?: "Auto"
    val valorFoco = when { focoMax <= 0f -> "—"; foco == null -> "Auto"; foco < 0.05f -> "∞"; else -> String.format(java.util.Locale.US, "%.0f cm", 100f / foco) }
    fun alterna(nome: String) { aberto = if (aberto == nome) null else nome }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            ChipPro("ISO", iso?.toString() ?: "Auto", aberto == "iso", iso != null) { alterna("iso") }
            DivisorPro()
            ChipPro("S", valorTempo, aberto == "s", tempoNs != null) { alterna("s") }
            DivisorPro()
            ChipPro("EV", valorEv, aberto == "ev", ev != 0) { alterna("ev") }
            DivisorPro()
            ChipPro("WB", wbs.firstOrNull { it.first == wb }?.second ?: "Auto", aberto == "wb", wb != CaptureRequest.CONTROL_AWB_MODE_AUTO) { alterna("wb") }
            DivisorPro()
            ChipPro("MF", valorFoco, aberto == "mf", foco != null) { if (focoMax > 0f) alterna("mf") }
            DivisorPro()
            Icon(if (aberto == null) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, contentDescription = "Abrir ou fechar", tint = Color(0xFFBDBDBD),
                modifier = Modifier.size(28.dp).clickable { aberto = if (aberto == null) "iso" else null })
        }
        aberto?.let { qual ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(36.dp)) {
                when (qual) {
                    "iso" -> Slider(value = (iso ?: faixaIso.first).toFloat().coerceIn(faixaIso.first.toFloat(), faixaIso.second.toFloat()), onValueChange = { aoIso(it.toInt()) },
                        valueRange = faixaIso.first.toFloat()..faixaIso.second.toFloat(), colors = corSlider, modifier = Modifier.weight(1f))
                    "s" -> {
                        val t = tempoNs ?: 8_000_000L
                        val faixa = Math.log10(faixaTempo.first.toDouble()).toFloat()..Math.log10(faixaTempo.second.toDouble()).toFloat()
                        Slider(value = Math.log10(t.toDouble()).toFloat().coerceIn(faixa.start, faixa.endInclusive), onValueChange = { aoTempo(Math.pow(10.0, it.toDouble()).toLong()) },
                            valueRange = faixa, colors = corSlider, modifier = Modifier.weight(1f))
                    }
                    "ev" -> Slider(value = ev.toFloat().coerceIn(faixaEv.first.toFloat(), faixaEv.second.toFloat()), onValueChange = { aoEv(Math.round(it)) },
                        valueRange = faixaEv.first.toFloat()..faixaEv.second.toFloat(), steps = (faixaEv.second - faixaEv.first - 1).coerceAtLeast(0), colors = corSlider, modifier = Modifier.weight(1f))
                    "wb" -> Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                        wbs.forEach { (v, r) ->
                            Text(r, color = if (wb == v) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (wb == v) Amarelo else Color(0x22FFFFFF)).clickable { aoWb(v) }.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    "mf" -> Slider(value = (foco ?: 0f).coerceIn(0f, focoMax), onValueChange = { aoFoco(it) }, valueRange = 0f..focoMax, colors = corSlider, modifier = Modifier.weight(1f))
                }
                Text("Auto", color = Color(0xFFBDBDBD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp).clickable(onClick = aoAuto))
            }
        }
    }
}

@Composable
private fun ChipPro(nome: String, valor: String, aberto: Boolean, manual: Boolean, aoTocar: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = aoTocar).padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(nome, color = if (aberto) Amarelo else Color(0xFFBDBDBD), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(valor, color = if (aberto || manual) Amarelo else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun DivisorPro() {
    Box(modifier = Modifier.width(1.dp).height(26.dp).background(Color(0x33FFFFFF)))
}
