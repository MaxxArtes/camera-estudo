package br.maxymus.galeriaestudo

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Tela cheia sobre preto: deslizar troca de foto, pinça dá zoom (1x a 6x), toque duplo alterna 1x/2,5x, toque simples
 * mostra ou esconde os controles (somem sozinhos em 2 s). Gestos portados do visualizador da câmera (validados no aparelho).
 */
@Composable
fun Visualizador(lista: List<Midia>, inicial: Int, pessoa: Long?, fechar: () -> Unit, aoExcluida: (Midia) -> Unit, aoNaoEEsta: (Midia) -> Unit) {
    val ctx = LocalContext.current
    if (lista.isEmpty()) { LaunchedEffect(Unit) { fechar() }; return }
    var indice by remember { mutableIntStateOf(inicial.coerceIn(0, lista.size - 1)) }
    if (indice > lista.size - 1) indice = lista.size - 1
    val atual = lista[indice]
    var escala by remember { mutableFloatStateOf(1f) }
    var desloc by remember { mutableStateOf(Offset.Zero) }
    var controles by remember { mutableStateOf(true) }
    var confirmarExcluir by remember { mutableStateOf(false) }
    var confirmarNaoE by remember { mutableStateOf(false) }
    LaunchedEffect(indice) { escala = 1f; desloc = Offset.Zero }
    LaunchedEffect(controles, indice) { if (controles) { delay(2000); controles = false } }
    val excluirSistema = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { Telemetria.evento("excluir"); aoExcluida(atual) }
    }
    fun excluir() {
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(atual.uri))
            excluirSistema.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else confirmarExcluir = true
    }
    fun compartilhar() {
        val envio = Intent(Intent.ACTION_SEND).apply { type = if (atual.ehVideo) "video/*" else "image/*"; putExtra(Intent.EXTRA_STREAM, atual.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { ctx.startActivity(Intent.createChooser(envio, "Compartilhar")) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize()
            .pointerInput(atual.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false); var dx = 0f; var dy = 0f; var pinca = false
                    do {
                        val ev = awaitPointerEvent(); val dedos = ev.changes.count { it.pressed }
                        if (dedos >= 2 || escala > 1f) {
                            pinca = true
                            val zoom = ev.calculateZoom(); val pan = ev.calculatePan(); val centro = ev.calculateCentroid()
                            val nova = (escala * zoom).coerceIn(1f, 6f)
                            val lim = Offset(size.width * (nova - 1) / 2, size.height * (nova - 1) / 2)
                            val d = if (nova == 1f) Offset.Zero else (desloc + pan + (centro - Offset(size.width / 2f, size.height / 2f)) * (escala - nova))
                            escala = nova; desloc = Offset(d.x.coerceIn(-lim.x, lim.x), d.y.coerceIn(-lim.y, lim.y))
                            ev.changes.forEach { it.consume() }
                        } else { val pan = ev.calculatePan(); dx += pan.x; dy += pan.y }
                    } while (ev.changes.any { it.pressed })
                    if (!pinca && abs(dx) > 90f && abs(dy) < 80f) {
                        val j = if (dx < 0) indice + 1 else indice - 1
                        if (j in lista.indices) indice = j
                    }
                }
            }
            .pointerInput(atual.id) { detectTapGestures(onTap = { controles = !controles }, onDoubleTap = { if (escala > 1f) { escala = 1f; desloc = Offset.Zero } else escala = 2.5f }) },
            contentAlignment = Alignment.Center) {
            AsyncImage(model = atual.uri, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = escala; scaleY = escala; translationX = desloc.x; translationY = desloc.y })
            if (atual.ehVideo) Box(Modifier.size(72.dp).background(Color(0x99000000), CircleShape).clickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(atual.uri, "video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
                    .onFailure { Toast.makeText(ctx, "Nenhum player disponível", Toast.LENGTH_SHORT).show() }
            }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, contentDescription = "Reproduzir", tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
        AnimatedVisibility(visible = controles, enter = fadeIn(tween(180)), exit = fadeOut(tween(180)), modifier = Modifier.align(Alignment.TopCenter)) {
            val (data, hora) = Midias.rotuloDataHora(atual.quando)
            Row(Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = fechar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(data, color = Color.White, fontSize = 16.sp)
                    if (hora.isNotEmpty()) Text(hora, color = Color(0xFFBBBBBB), fontSize = 12.sp)
                }
            }
        }
        AnimatedVisibility(visible = controles, enter = fadeIn(tween(180)), exit = fadeOut(tween(180)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Acao(Icons.Filled.Share, "Compartilhar") { compartilhar() }
                Acao(Icons.Filled.Delete, "Excluir") { excluir() }
                if (pessoa != null) Acao(Icons.Filled.PersonOff, "Não é esta pessoa") { confirmarNaoE = true }
            }
        }
    }
    if (confirmarExcluir) AlertDialog(onDismissRequest = { confirmarExcluir = false }, title = { Text("Excluir esta foto?") }, text = { Text("Não dá para desfazer.") },
        confirmButton = { TextButton(onClick = {
            confirmarExcluir = false
            if (runCatching { ctx.contentResolver.delete(atual.uri, null, null) > 0 }.getOrDefault(false)) aoExcluida(atual) else Toast.makeText(ctx, "Não consegui excluir", Toast.LENGTH_SHORT).show()
        }) { Text("Excluir", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { confirmarExcluir = false }) { Text("Cancelar") } })
    if (confirmarNaoE) AlertDialog(onDismissRequest = { confirmarNaoE = false }, title = { Text("Não é esta pessoa?") },
        text = { Text("A foto sai deste álbum e a correção é lembrada na próxima análise. A foto continua na galeria.") },
        confirmButton = { TextButton(onClick = { confirmarNaoE = false; aoNaoEEsta(atual) }) { Text("Remover daqui", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { confirmarNaoE = false }) { Text("Cancelar") } })
}

@Composable
private fun Acao(icone: ImageVector, rotulo: String, aoTocar: () -> Unit) {
    Column(Modifier.clickable(onClick = aoTocar).height(48.dp).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icone, contentDescription = rotulo, tint = Color.White)
        Text(rotulo, color = Color.White, fontSize = 11.sp)
    }
}
