package br.maxymus.cameraestudo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.HdrAuto
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// Paleta do ícone: coral → rosa no corpo, fundo quase preto, aro branco, LED verde.
private val Amarelo = Color(0xFFFF5A5F)       // nome mantido no código; a cor é o coral do ícone
private val Rosa = Color(0xFFF0325A)
private val Verde = Color(0xFF22C55E)
private val Fundo = Color(0xFF0E0E12)
private val Painel = Color(0xFF16161B)

enum class Modo(val rotulo: String, val pronto: Boolean) {
    LENTA("LENTA", false), VIDEO("VÍDEO", true), FOTO("FOTO", true), RETRATO("RETRATO", false), MAIS("MAIS", false)
}

/**
 * Tela principal no desenho de referência: barra de cima (flash, gaveta, ajustes), visualização
 * com grade e nível, chips de zoom, linha de modos, miniatura + obturador + trocar câmera.
 *
 * CameraX em uma frase: você descreve "casos de uso" (Preview, ImageCapture, VideoCapture),
 * amarra ao ciclo de vida da tela e ele cuida do Camera2 por baixo (abrir, configurar, fechar).
 * Foto e vídeo são ligados separadamente, porque nem todo aparelho aceita os três de uma vez.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var ocupado by remember { mutableStateOf(false) }
    var contagem by remember { mutableIntStateOf(0) }
    var gravacao by remember { mutableStateOf<Recording?>(null) }
    var segundosGravando by remember { mutableIntStateOf(0) }
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
    val imageCapture = remember(proporcao) {
        @Suppress("DEPRECATION")
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setTargetAspectRatio(proporcao).build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build())
    }
    val pedirAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { ultima = Fotos.listar(contexto, limite = 1).firstOrNull()?.uri }

    // (Re)liga a câmera quando muda lente, modo ou proporção.
    LaunchedEffect(lente, modo, proporcao) {
        val provider = ProcessCameraProvider.getInstance(contexto).get()
        @Suppress("DEPRECATION")
        val preview = Preview.Builder().setTargetAspectRatio(proporcao).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val seletor = CameraSelector.Builder().requireLensFacing(lente).build()
        provider.unbindAll()
        camera = runCatching {
            if (modo == Modo.VIDEO) provider.bindToLifecycle(dono, seletor, preview, videoCapture)
            else provider.bindToLifecycle(dono, seletor, preview, imageCapture)
        }.onFailure { Toast.makeText(contexto, "Não consegui abrir a câmera: ${it.message}", Toast.LENGTH_LONG).show() }.getOrNull()
        zoom = 1f
        camera?.cameraControl?.setZoomRatio(1f)
    }
    LaunchedEffect(flash) { imageCapture.flashMode = flash }
    LaunchedEffect(gravacao) { segundosGravando = 0; while (gravacao != null) { delay(1000); segundosGravando++ } }

    fun aplicaZoom(alvo: Float) {
        val cam = camera ?: return
        val estado = cam.cameraInfo.zoomState.value ?: return
        zoom = alvo.coerceIn(estado.minZoomRatio, estado.maxZoomRatio)
        cam.cameraControl.setZoomRatio(zoom)
    }

    fun tiraFoto() {
        ocupado = true
        val saida = ImageCapture.OutputFileOptions.Builder(contexto.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Fotos.novaEntrada()).build()
        imageCapture.takePicture(saida, ContextCompat.getMainExecutor(contexto), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { ocupado = false; ultima = r.savedUri }
            override fun onError(e: ImageCaptureException) { ocupado = false; Toast.makeText(contexto, "Falhou: ${e.message}", Toast.LENGTH_LONG).show() }
        })
    }

    fun disparar() {
        if (ocupado) return
        if (modo == Modo.VIDEO) {
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
                    else ultima = ev.outputResults.outputUri
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

    Box(modifier = Modifier.fillMaxSize().background(Fundo)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- barra de cima ----
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { flash = proximoFlash(flash) }) {
                    Icon(iconeFlash(flash), contentDescription = "Flash", tint = if (flash == ImageCapture.FLASH_MODE_OFF) Color.White else Amarelo)
                }
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable { gaveta = true },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Mais ajustes", tint = Color.White) }
                IconButton(onClick = { gaveta = true }) { Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = Color.White) }
            }

            // ---- visualização (proporção fixa, como no desenho) ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (proporcao == AspectRatio.RATIO_16_9) 9f / 16f else 3f / 4f)
                    .background(Color.Black)
                    .pointerInput(camera) { detectTransformGestures { _, _, escala, _ -> aplicaZoom(zoom * escala) } }
                    .pointerInput(camera) {
                        detectTapGestures { toque ->
                            val cam = camera ?: return@detectTapGestures
                            val ponto = previewView.meteringPointFactory.createPoint(toque.x, toque.y)
                            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(ponto, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build())
                        }
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
                if (contagem > 0) Text("$contagem", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
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
                    opcoes.forEach { alvo ->
                        val selecionado = abs(zoom - alvo) < 0.15f
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
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
                Modo.entries.forEach { m ->
                    Text(
                        m.rotulo, color = if (m == modo) Amarelo else Color(0xFFBDBDBD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp).clickable {
                            if (gravacao != null) return@clickable
                            if (m.pronto) modo = m else Toast.makeText(contexto, "${m.rotulo}: em breve", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
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
                        .background(when { gravando -> Rosa; modo == Modo.VIDEO -> Rosa; ocupado -> Color.Gray; else -> Color.White })
                        .clickable { disparar() }
                )
                Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable {
                    if (gravacao == null) lente = if (lente == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Trocar câmera", tint = Color.White)
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
                    item { Ajuste(Icons.Filled.AspectRatio, "Proporção", if (proporcao == AspectRatio.RATIO_16_9) "16:9" else "4:3", true) { proporcao = if (proporcao == AspectRatio.RATIO_16_9) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9 } }
                    item { Ajuste(Icons.Filled.HdrAuto, "HDR", "Auto", false) { Toast.makeText(contexto, "HDR: o aparelho decide (CameraX Extensions em breve)", Toast.LENGTH_SHORT).show() } }
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

@Composable
private fun Ajuste(icone: ImageVector, titulo: String, valor: String, ativo: Boolean, aoTocar: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 14.dp).clickable(onClick = aoTocar), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(58.dp).clip(CircleShape).background(if (ativo) Amarelo else Color(0xFF26262C)), contentAlignment = Alignment.Center) {
            Icon(icone, contentDescription = titulo, tint = Color.White)
        }
        Text(titulo, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text(valor, color = Color(0xFF9E9E9E), fontSize = 11.sp)
    }
}

private fun proximoFlash(f: Int) = when (f) {
    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
    else -> ImageCapture.FLASH_MODE_OFF
}
private fun rotuloFlash(f: Int) = when (f) { ImageCapture.FLASH_MODE_AUTO -> "Auto"; ImageCapture.FLASH_MODE_ON -> "Ligado"; else -> "Desligado" }
private fun iconeFlash(f: Int) = when (f) { ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto; ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn; else -> Icons.Filled.FlashOff }
