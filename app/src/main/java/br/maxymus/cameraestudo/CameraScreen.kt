package br.maxymus.cameraestudo

import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage

/**
 * Tela principal: visualização ao vivo, botão de foto, troca de câmera, flash, zoom por pinça,
 * foco por toque e a miniatura da última foto (abre a galeria).
 *
 * Como o CameraX funciona, em uma frase: você descreve "casos de uso" (Preview, ImageCapture),
 * amarra ao ciclo de vida da tela e ele cuida do Camera2 por baixo (abrir, configurar, fechar).
 */
@Composable
fun CameraScreen(abrirGaleria: () -> Unit) {
    val contexto = LocalContext.current
    val dono = LocalLifecycleOwner.current

    // Estado da tela. `remember` guarda entre recomposições; muda de valor → a tela redesenha.
    var lente by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var flash by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var ultimaFoto by remember { mutableStateOf<Uri?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capturando by remember { mutableStateOf(false) }

    // Objetos do CameraX que sobrevivem às recomposições.
    val previewView = remember { PreviewView(contexto).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    // Miniatura inicial: a última foto que já existir na pasta do app.
    LaunchedEffect(Unit) { ultimaFoto = Fotos.listar(contexto, limite = 1).firstOrNull() }

    // (Re)liga a câmera sempre que a lente muda. O provider é único por processo.
    LaunchedEffect(lente) {
        val provider = ProcessCameraProvider.getInstance(contexto).get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val seletor = CameraSelector.Builder().requireLensFacing(lente).build()
        provider.unbindAll()
        camera = runCatching { provider.bindToLifecycle(dono, seletor, preview, imageCapture) }
            .onFailure { Toast.makeText(contexto, "Não consegui abrir a câmera: ${it.message}", Toast.LENGTH_LONG).show() }
            .getOrNull()
        zoom = 1f
    }
    LaunchedEffect(flash) { imageCapture.flashMode = flash }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                // pinça = zoom; a razão do gesto multiplica o zoom atual, limitada ao que a lente aceita
                .pointerInput(camera) {
                    detectTransformGestures { _, _, escala, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val estado = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        zoom = (zoom * escala).coerceIn(estado.minZoomRatio, estado.maxZoomRatio)
                        cam.cameraControl.setZoomRatio(zoom)
                    }
                }
                // toque = foco e exposição naquele ponto
                .pointerInput(camera) {
                    detectTapGestures { toque ->
                        val cam = camera ?: return@detectTapGestures
                        val ponto = previewView.meteringPointFactory.createPoint(toque.x, toque.y)
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(ponto, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
                        )
                    }
                }
        )

        // Barra de cima: flash e troca de câmera.
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                flash = when (flash) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }) {
                Icon(
                    when (flash) {
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
                        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                        else -> Icons.Filled.FlashOff
                    },
                    contentDescription = "Flash", tint = Color.White
                )
            }
            Text(
                text = if (zoom >= 1.05f) String.format("%.1fx", zoom) else "",
                color = Color.White, modifier = Modifier.padding(top = 12.dp)
            )
            IconButton(onClick = {
                lente = if (lente == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            }) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Trocar câmera", tint = Color.White)
            }
        }

        // Barra de baixo: miniatura, obturador.
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(10.dp))
                    .clickable(onClick = abrirGaleria),
                contentAlignment = Alignment.Center
            ) {
                if (ultimaFoto != null) {
                    AsyncImage(model = ultimaFoto, contentDescription = "Última foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }

            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(if (capturando) Color.Gray else Color.White)
                    .border(4.dp, Color(0xFFBDBDBD), CircleShape)
                    .clickable(enabled = !capturando) {
                        capturando = true
                        val saida = ImageCapture.OutputFileOptions.Builder(
                            contexto.contentResolver,
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            Fotos.novaEntrada()
                        ).build()
                        imageCapture.takePicture(saida, ContextCompat.getMainExecutor(contexto), object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                                capturando = false
                                ultimaFoto = r.savedUri
                                Toast.makeText(contexto, "Foto salva em Imagens/${Fotos.PASTA}", Toast.LENGTH_SHORT).show()
                            }
                            override fun onError(e: ImageCaptureException) {
                                capturando = false
                                Toast.makeText(contexto, "Falhou: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        })
                    }
            )

            // espaço simétrico à miniatura, para o obturador ficar no centro
            Box(modifier = Modifier.size(56.dp))
        }
    }
}
