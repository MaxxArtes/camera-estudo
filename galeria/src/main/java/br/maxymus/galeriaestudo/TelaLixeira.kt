package br.maxymus.galeriaestudo

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lixeira do sistema (Android 11+): itens excluídos que o SO apaga sozinho após ~30 dias. Restaurar ou excluir de vez. */
@Composable
fun TelaLixeira(parcial: Boolean, voltar: () -> Unit, aoMudou: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val estado = rememberLazyGridState()
    val carregador = remember { Miniaturas.carregador(ctx) }
    var itens by remember { mutableStateOf<List<Midias.ItemLixeira>>(emptyList()) }
    var carregado by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf(false) }
    var selecionadas by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val semLixeira = android.os.Build.VERSION.SDK_INT < 30

    fun recarrega() {
        escopo.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Midias.listarLixeira(ctx) } }
            itens = r.getOrDefault(emptyList()); erro = r.isFailure; carregado = true
        }
    }
    LaunchedEffect(Unit) { if (!semLixeira) recarrega() else carregado = true }
    fun uris(ids: Set<Long>) = itens.filter { it.midia.id in ids }.map { it.midia.uri }
    val lancRestaurar = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { Telemetria.evento("restaurar", mapOf("n" to selecionadas.size)); selecionadas = emptySet(); aoMudou() }
        recarrega()
    }
    val lancExcluir = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { Telemetria.evento("excluir_definitivo", mapOf("n" to selecionadas.size)); selecionadas = emptySet(); aoMudou() }
        recarrega()
    }
    fun restaurar() { val pi = Midias.pedidoLixeira(ctx, uris(selecionadas), false) ?: return; lancRestaurar.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
    fun excluir() { val pi = Midias.pedidoExcluir(ctx, uris(selecionadas)) ?: return; lancExcluir.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }

    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding()) {
        BarraSuperior(if (selecionadas.isEmpty()) "Lixeira" else Midias.selecionados(selecionadas.size), voltar = voltar) {
            if (itens.isNotEmpty()) { val todasSel = selecionadas.size == itens.size
                TextButton(onClick = { selecionadas = if (todasSel) emptySet() else itens.map { it.midia.id }.toSet() }) { Text(if (todasSel) "Desmarcar tudo" else "Selecionar tudo", color = Tema.Coral) }
            }
        }
        if (!semLixeira) FaixaAviso("O Android apaga os itens sozinho, em geral após cerca de 30 dias. O prazo pode variar.", null)
        if (parcial) FaixaAviso("Acesso limitado: alguns itens podem não aparecer.", "Gerenciar acesso") { }

        Box(Modifier.weight(1f)) {
            when {
                semLixeira -> Aviso("A lixeira existe a partir do Android 11.\n\nNeste aparelho, a exclusão é permanente.")
                !carregado -> Box(Modifier.fillMaxSize())
                erro -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Não foi possível carregar a lixeira.", color = Tema.Texto2, textAlign = TextAlign.Center)
                    TextButton(onClick = { recarrega() }, modifier = Modifier.padding(top = 8.dp)) { Text("Tentar novamente", color = Tema.Coral) }
                }
                itens.isEmpty() -> Aviso(if (parcial) "Nenhum item disponível com o acesso atual." else "Lixeira vazia\n\nOs itens movidos para a lixeira aparecem aqui.")
                else -> LazyVerticalGrid(columns = GridCells.Fixed(3), state = estado, modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(itens, key = { it.midia.id }) { it2 ->
                        val marcada = it2.midia.id in selecionadas
                        Box(Modifier.aspectRatio(1f).background(Tema.Superficie).clickable {
                            selecionadas = if (marcada) selecionadas - it2.midia.id else selecionadas + it2.midia.id
                        }) {
                            AsyncImage(model = ImageRequest.Builder(ctx).data(it2.midia.uri).size(400).build(), imageLoader = carregador,
                                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().then(if (marcada) Modifier.padding(10.dp) else Modifier))
                            if (it2.midia.ehVideo) Icon(Icons.Filled.PlayArrow, contentDescription = "Vídeo", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp))
                            Text(Midias.rotuloExpira(it2.expiraEm), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                            Icon(if (marcada) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
                                tint = if (marcada) Tema.Coral else Color(0xCCFFFFFF), modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp))
                        }
                    }
                }
            }
        }
        if (selecionadas.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Color(0xF01A1A1F)).navigationBarsPadding().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            AcaoBarra(Icons.Filled.Restore, "Restaurar", true) { restaurar() }
            AcaoBarra(Icons.Filled.DeleteForever, "Excluir definitivamente", true, Tema.Coral) { excluir() }
        }
    }
}

@Composable
private fun Aviso(texto: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(texto, color = Tema.Texto2, fontSize = 15.sp, textAlign = TextAlign.Center) }
}
