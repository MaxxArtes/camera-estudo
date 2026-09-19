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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Tela cheia sobre preto: deslizar troca de foto, pinça dá zoom (1x a 6x), toque duplo alterna 1x/2,5x, toque simples
 * mostra ou esconde os controles (somem sozinhos em 2 s). Barra de baixo com Compartilhar, Editar, Favorito, Excluir
 * e Mais (Informações; e "Não é esta pessoa" quando aberto por um álbum). Gestos portados da câmera (validados).
 */
@Composable
fun Visualizador(lista: List<Midia>, inicial: Int, pessoa: Long?, albumManual: Long? = null, fechar: () -> Unit, aoExcluida: (Midia) -> Unit, aoNaoEEsta: (Midia) -> Unit, aoRemovidoDoAlbum: (Midia) -> Unit = {}) {
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
    var menuMais by remember { mutableStateOf(false) }
    var mostrarInfo by remember { mutableStateOf(false) }
    var favorito by remember(atual.id) { mutableStateOf(Favoritos.eh(ctx, atual.uri)) }
    val escopo = rememberCoroutineScope()
    var folhaAlbuns by remember { mutableStateOf(false) }
    var criarAlbumV by remember { mutableStateOf(false) }
    var albunsV by remember { mutableStateOf<List<Indice.AlbumManual>>(emptyList()) }
    var jaContemV by remember { mutableStateOf<Set<Long>>(emptySet()) }
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
    fun editar() {
        val i = Intent(Intent.ACTION_EDIT).apply { setDataAndType(atual.uri, if (atual.ehVideo) "video/*" else "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        runCatching { ctx.startActivity(Intent.createChooser(i, "Editar com")) }.onFailure { Toast.makeText(ctx, "Nenhum editor instalado", Toast.LENGTH_SHORT).show() }
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
                androidx.compose.material3.IconButton(onClick = fechar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(data, color = Color.White, fontSize = 16.sp)
                    if (hora.isNotEmpty()) Text(hora, color = Color(0xFFBBBBBB), fontSize = 12.sp)
                }
            }
        }
        AnimatedVisibility(visible = controles, enter = fadeIn(tween(180)), exit = fadeOut(tween(180)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Acao(Icons.Filled.Share, "Compartilhar") { compartilhar() }
                Acao(Icons.Filled.Edit, "Editar") { editar() }
                Acao(if (favorito) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorito", if (favorito) Tema.Coral else Color.White) {
                    favorito = Favoritos.alterna(ctx, atual.uri); Telemetria.evento("favorito", mapOf("ligou" to favorito))
                }
                Acao(Icons.Filled.Delete, "Excluir") { excluir() }
                Box {
                    Acao(Icons.Filled.MoreVert, "Mais") { menuMais = true }
                    DropdownMenu(expanded = menuMais, onDismissRequest = { menuMais = false }) {
                        DropdownMenuItem(text = { Text("Informações") }, onClick = { menuMais = false; mostrarInfo = true })
                        DropdownMenuItem(text = { Text("Adicionar a um álbum") }, onClick = {
                            menuMais = false
                            escopo.launch { val db = Indice.get(ctx); albunsV = kotlinx.coroutines.withContext(Dispatchers.IO) { db.listarAlbuns() }; jaContemV = kotlinx.coroutines.withContext(Dispatchers.IO) { db.albunsDaFoto(atual.id) }; folhaAlbuns = true }
                        })
                        if (albumManual != null) DropdownMenuItem(text = { Text("Remover deste álbum") }, onClick = {
                            menuMais = false
                            escopo.launch { kotlinx.coroutines.withContext(Dispatchers.IO) { Indice.get(ctx).removerDoAlbum(albumManual, atual.id) }; Toast.makeText(ctx, "Removida do álbum", Toast.LENGTH_SHORT).show(); aoRemovidoDoAlbum(atual) }
                        })
                        if (pessoa != null) DropdownMenuItem(text = { Text("Não é esta pessoa") }, onClick = { menuMais = false; confirmarNaoE = true })
                    }
                }
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
    if (folhaAlbuns) FolhaAlbuns(albunsV, jaContem = jaContemV, aoFechar = { folhaAlbuns = false },
        aoCriar = { folhaAlbuns = false; criarAlbumV = true },
        aoEscolher = { alb -> escopo.launch { val n = kotlinx.coroutines.withContext(Dispatchers.IO) { Indice.get(ctx).adicionarAoAlbum(alb, listOf(atual.id to atual.quando)) }; val nome = albunsV.firstOrNull { it.id == alb }?.nome ?: "álbum"; Toast.makeText(ctx, if (n > 0) "Foto adicionada a \"$nome\"" else "Já estava no álbum", Toast.LENGTH_SHORT).show(); folhaAlbuns = false } })
    if (criarAlbumV) DialogoCriarAlbum(nomesExistentes = albunsV.map { it.nome }, rotuloConfirmar = "Criar e adicionar", aoFechar = { criarAlbumV = false }) { nome ->
        criarAlbumV = false
        escopo.launch { kotlinx.coroutines.withContext(Dispatchers.IO) { val db = Indice.get(ctx); val a = db.criarAlbum(nome); db.adicionarAoAlbum(a, listOf(atual.id to atual.quando)) }; Toast.makeText(ctx, "Foto adicionada a \"$nome\"", Toast.LENGTH_SHORT).show() }
    }
    if (mostrarInfo) {
        val det by produceState<Midias.Detalhe?>(null, atual.id) { value = withContext(Dispatchers.IO) { Midias.detalhe(ctx, atual) } }
        val (data, hora) = Midias.rotuloDataHora(atual.quando)
        AlertDialog(onDismissRequest = { mostrarInfo = false }, confirmButton = { TextButton(onClick = { mostrarInfo = false }) { Text("Fechar") } },
            title = { Text("Informações") },
            text = {
                Column {
                    val d = det
                    if (d != null && d.nome.isNotEmpty()) Text("Arquivo: ${d.nome}")
                    Text("Data: $data" + if (hora.isNotEmpty()) " $hora" else "")
                    if (d != null && d.bytes > 0) Text("Tamanho: ${Midias.tamanho(d.bytes)}")
                    if (d != null && d.largura > 0) Text("Dimensões: ${d.largura} × ${d.altura}")
                    if (atual.ehVideo && atual.duracaoMs > 0) Text("Duração: ${Midias.duracao(atual.duracaoMs)}")
                    if (favorito) Text("Favorito", color = Tema.Coral)
                }
            })
    }
}

@Composable
private fun Acao(icone: ImageVector, rotulo: String, cor: Color = Color.White, aoTocar: () -> Unit) {
    Column(Modifier.clickable(onClick = aoTocar).height(52.dp).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icone, contentDescription = rotulo, tint = cor)
        Text(rotulo, color = Color.White, fontSize = 11.sp)
    }
}
