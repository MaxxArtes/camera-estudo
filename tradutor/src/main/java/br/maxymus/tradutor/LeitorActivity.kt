package br.maxymus.tradutor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Fundo = Color(0xFF111114)
private val Superficie = Color(0xFF252529)
private val Coral = Color(0xFFFF575F)
private val Texto = Color(0xFFF5F5F5)
private val Texto2 = Color(0xFFA3A3AA)

/**
 * Leitor de capítulo (desenho do Astra, 25/09). O aparelho baixa a página, acha os quadros, baixa as imagens
 * originais e traduz quadro a quadro conforme a leitura avança. O original aparece assim que baixa e a
 * tradução entra no lugar sem mexer na posição da rolagem — continuidade vale mais que transição bonita.
 */
class LeitorActivity : ComponentActivity() {
    companion object {
        @Volatile private var emPrimeiroPlano = 0
        val visivel: Boolean get() = emPrimeiroPlano > 0
    }

    override fun onResume() {
        super.onResume()
        emPrimeiroPlano++
        ServicoTradutor.ativo?.leitorMudou()
    }

    override fun onPause() {
        emPrimeiroPlano = (emPrimeiroPlano - 1).coerceAtLeast(0)
        ServicoTradutor.ativo?.leitorMudou()
        super.onPause()
    }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        Telemetria.iniciar(this)
        Traducao.carregarPreferencias(this)
        Traducao.iniciarCacheDisco(this)
        val endereco = enderecoDo(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Coral, background = Fundo, surface = Superficie, onSurface = Texto)) {
                if (endereco == null) SemEndereco { finish() } else TelaCapitulo(endereco) { finish() }
            }
        }
    }

    /** O compartilhamento do navegador manda título e endereço juntos; aqui só interessa o endereço. */
    private fun enderecoDo(i: Intent?): String? {
        val cru = i?.getStringExtra("endereco")
            ?: i?.getStringExtra(Intent.EXTRA_TEXT)
            ?: i?.data?.toString()
            ?: return null
        return Regex("""https?://\S+""").find(cru)?.value
    }
}

@Composable
private fun SemEndereco(voltar: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Fundo).systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Nenhum endereço", color = Texto, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text("Compartilhe o endereço de um capítulo com o Tradutor, ou cole o endereço na tela inicial.",
            color = Texto2, fontSize = 14.sp)
        Button(onClick = voltar, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo)) { Text("Voltar", fontSize = 15.sp) }
    }
}

@Composable
private fun TelaCapitulo(endereco: String, voltar: () -> Unit) {
    val ctx = LocalContext.current
    val leitor = remember { Leitor(ctx, endereco) }
    val lista = rememberLazyListState()
    var barras by remember { mutableStateOf(true) }
    val escopo = rememberCoroutineScope()
    val leitura = remember { LeituraNoLeitor(ctx, leitor, lista, escopo) }

    LaunchedEffect(Unit) { leitor.abrir(); withContext(Dispatchers.IO) { Leitor.limparAntigos(ctx) } }
    DisposableEffect(Unit) { onDispose { leitura.desligar(); leitor.fechar() } }

    // sair do leitor (ou a tela apagar) para a leitura; o desligamento da tela também chega pela própria voz
    val ciclo = LocalLifecycleOwner.current
    DisposableEffect(ciclo) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) leitura.aoSairDeVista() }
        ciclo.lifecycle.addObserver(obs)
        onDispose { ciclo.lifecycle.removeObserver(obs) }
    }

    // os dois motores morrem junto com a tela
    LaunchedEffect(leitor.quadros.size) { if (leitor.quadros.isNotEmpty()) leitor.motorRede() }
    LaunchedEffect(leitor.quadros.size) { if (leitor.quadros.isNotEmpty()) leitor.motorPreparo(ctx) }

    // retomar onde parou, uma vez só, depois que a lista existe
    var retomou by remember { mutableStateOf(false) }
    LaunchedEffect(leitor.quadros.size) {
        if (!retomou && leitor.quadros.isNotEmpty()) {
            retomou = true
            val i = leitor.ondeParou()
            if (i in 1 until leitor.quadros.size) lista.scrollToItem(i)
        }
    }
    // a chave do efeito É o valor observado: muda o quadro do topo, o efeito roda de novo
    LaunchedEffect(lista.firstVisibleItemIndex) {
        leitor.visivel = lista.firstVisibleItemIndex
        leitor.anotarPosicao(lista.firstVisibleItemIndex)
    }
    // Durante a leitura as barras ficam à mostra (o "Parar" está nelas), e só a rolagem do próprio app não interrompe:
    // arrasto, acessibilidade, teclado ou comando de navegação param a voz e o avanço.
    LaunchedEffect(Unit) {
        snapshotFlow { lista.isScrollInProgress }.collect { rolando ->
            // relido agora, e não o valor do fluxo: a rolagem do app pode ter acabado entre a mudança e esta coleta
            if (RegraLeituraCapitulo.rolagemInterrompe(leitura.ativa, lista.isScrollInProgress, leitura.rolandoPeloApp, false))
                leitura.interromper(RegraLeituraCapitulo.ROLAGEM)
            if (rolando && !leitura.ativa && !leitura.rolandoPeloApp) barras = false
        }
    }
    LaunchedEffect(Unit) {
        lista.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Start && RegraLeituraCapitulo.rolagemInterrompe(leitura.ativa, true, leitura.rolandoPeloApp, true))
                leitura.interromper(RegraLeituraCapitulo.ROLAGEM)
        }
    }
    // o quadro de maior área visível entre as barras: é por ele que o "Ouvir" começa, e é ele que o indicador mostra
    val mostrarBarras = barras || leitura.ativa
    val inicial by remember {
        derivedStateOf {
            val li = lista.layoutInfo
            val comBarras = barras || leitura.ativa
            // O início do viewport inclui o padding negativo; a barra é descontada só uma vez.
            val inicio = li.viewportStartOffset + (if (comBarras) leitura.alturaCima else 0)
            val fim = li.viewportEndOffset - (if (comBarras) leitura.alturaBaixo else 0)
            RegraLeituraCapitulo.quadroInicial(li.visibleItemsInfo.map {
                RegraLeituraCapitulo.QuadroVisivel(it.index, RegraLeituraCapitulo.alturaVisivel(it.offset, it.offset + it.size, inicio, fim))
            })
        }
    }

    when {
        leitor.carregando -> Preparando(endereco, voltar)
        leitor.falhouLista -> NaoReconheci(endereco, voltar)
        else -> Box(Modifier.fillMaxSize().background(Fundo)
            .pointerInput(Unit) { detectTapGestures { barras = !barras } }) {
            LazyColumn(state = lista, modifier = Modifier.fillMaxSize(),
                contentPadding = with(LocalDensity.current) {
                    PaddingValues(top = leitura.alturaCima.toDp(), bottom = leitura.alturaBaixo.toDp())
                }) {
                items(leitor.quadros, key = { it.indice }) { q -> QuadroNaTela(q) { leitor.tentarDeNovo(q) } }
            }
            if (mostrarBarras) {
                BarraDeCima(endereco, voltar, Modifier.align(Alignment.TopCenter).onSizeChanged { leitura.alturaCima = it.height })
                BarraDeBaixo(leitor, leitura, if (leitura.ativa) leitura.quadroDaVez else inicial ?: lista.firstVisibleItemIndex,
                    { leitura.ouvir(inicial) }, Modifier.align(Alignment.BottomCenter).onSizeChanged { leitura.alturaBaixo = it.height })
            }
        }
    }
    if (leitura.pedirVoz) SemVoz(leitura)
}

/** Sem voz pt-BR instalada que funcione sem internet: os mesmos dois botões da bolha. Quem voltar toca em Ouvir de novo. */
@Composable
private fun SemVoz(leitura: LeituraNoLeitor) =
    AlertDialog(onDismissRequest = { leitura.pedirVoz = false },
        text = { Text("Não encontramos uma voz de português do Brasil disponível sem internet. Instale uma voz para ouvir.") },
        confirmButton = { TextButton(onClick = { leitura.instalarVoz() }) { Text("Instalar voz", color = Coral) } },
        dismissButton = { TextButton(onClick = { leitura.pedirVoz = false }) { Text("Agora não", color = Texto) } })

/** Um quadro. Enquanto não há imagem, o espaço é um marcador COMPACTO: inventar altura faria a lista pular. */
@Composable
private fun QuadroNaTela(q: Leitor.Quadro, tentarDeNovo: () -> Unit) {
    val b = q.imagem
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val altura = if (q.proporcao > 0f) maxWidth * q.proporcao else 180.dp
        Box(Modifier.fillMaxWidth().height(altura)) {
            if (b != null) {
                Image(b.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            } else {
                Box(Modifier.fillMaxSize().background(Superficie), contentAlignment = Alignment.Center) {
                    when (q.estado) {
                        Leitor.Estado.ERRO_REDE -> Coluna("Não foi possível carregar este quadro", "Tentar novamente", tentarDeNovo)
                        else -> Text("Carregando quadro…", color = Texto2, fontSize = 13.sp)
                    }
                }
            }
            if (q.estado == Leitor.Estado.TRADUZINDO)
                Etiqueta("Traduzindo…", Modifier.align(Alignment.TopStart))
            if (q.estado == Leitor.Estado.ERRO_REDE && b != null)
                TextButton(onClick = tentarDeNovo, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Text("Tentar novamente", color = Coral, fontSize = 12.sp)
                }
            if (q.estado == Leitor.Estado.ERRO_TRAD && b != null)
                Row(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Etiqueta("Sem tradução", Modifier)
                    TextButton(onClick = tentarDeNovo) { Text("Tentar tradução", color = Coral, fontSize = 12.sp) }
                }
        }
    }
}

@Composable
private fun Coluna(aviso: String, acao: String, ao: () -> Unit) =
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(aviso, color = Texto2, fontSize = 13.sp, textAlign = TextAlign.Center)
        TextButton(onClick = ao) { Text(acao, color = Coral, fontSize = 13.sp) }
    }

@Composable
private fun Etiqueta(texto: String, m: Modifier) =
    Text(texto, color = Texto, fontSize = 11.sp,
        modifier = m.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC111114)).padding(horizontal = 8.dp, vertical = 4.dp))

@Composable
private fun BarraDeCima(endereco: String, voltar: () -> Unit, m: Modifier) =
    Row(m.fillMaxWidth().background(Color(0xF2111114)).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = voltar) { Text("Voltar", color = Texto, fontSize = 14.sp) }
        Text(titulo(endereco), color = Texto, fontSize = 14.sp, maxLines = 1,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(Idiomas.nome(Traducao.destino), color = Texto2, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
    }

/**
 * "Ver original", "Ouvir" (que vira "Parar") e "Quadro X de N"; com fonte grande, o indicador desce para a segunda linha.
 * Abaixo, a opção "Avançar automaticamente" e a linha de aviso. Todo alvo de toque tem pelo menos 48 dp.
 */
@Composable
private fun BarraDeBaixo(leitor: Leitor, leitura: LeituraNoLeitor, atual: Int, ouvir: () -> Unit, m: Modifier) =
    Column(m.fillMaxWidth().background(Color(0xF2111114)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
        val fonteGrande = LocalDensity.current.fontScale >= 1.3f
        val botoes = @Composable {
            TextButton(onClick = {
                // ver o original interrompe a voz: o que se lê é o traduzido
                if (!leitor.mostrarOriginal) leitura.interromper(RegraLeituraCapitulo.ORIGINAL)
                leitor.alternarOriginal()
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (leitor.mostrarOriginal) "Ver traduzido" else "Ver original", color = Coral, fontSize = 14.sp)
            }
            TextButton(onClick = { if (leitura.ativa) leitura.parar() else ouvir() }, enabled = !leitor.mostrarOriginal,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = if (leitura.ativa) "Parar leitura" else "Ouvir o quadro"
                }) {
                Text(if (leitura.ativa) "Parar" else "Ouvir", color = if (leitor.mostrarOriginal) Texto2 else Coral, fontSize = 14.sp)
            }
        }
        val indicador = "Quadro ${atual + 1} de ${leitor.quadros.size}"
        if (fonteGrande) {
            Row(verticalAlignment = Alignment.CenterVertically) { botoes() }
            Text(indicador, color = Texto2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            botoes()
            Text(indicador, color = Texto2, fontSize = 13.sp,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(end = 8.dp))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = leitura.avancar, role = Role.Switch, onValueChange = { leitura.avancar = it })
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text("Avançar automaticamente", color = Texto, fontSize = 14.sp)
                Text("Ao terminar um quadro, passa para o próximo.", color = Texto2, fontSize = 12.sp)
            }
            // o interruptor só mostra o estado: o toque é da linha inteira, que é o alvo acessível
            Switch(checked = leitura.avancar, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = Coral, checkedThumbColor = Fundo))
        }
        val aviso = leitura.aviso ?: if (leitor.mostrarOriginal) LeituraNoLeitor.AVISO_ORIGINAL else null
        if (aviso != null)
            Text(aviso, color = Texto2, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }

@Composable
private fun Preparando(endereco: String, voltar: () -> Unit) =
    Column(Modifier.fillMaxSize().background(Fundo).systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Buscando quadros…", color = Texto, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(endereco, color = Texto2, fontSize = 12.sp, maxLines = 3)
        TextButton(onClick = voltar) { Text("Voltar", color = Coral, fontSize = 14.sp) }
    }

@Composable
private fun NaoReconheci(endereco: String, voltar: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Fundo).systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Não encontramos quadros neste endereço", color = Texto, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text("Este site pode carregar as imagens de uma forma que o leitor ainda não reconhece.",
            color = Texto2, fontSize = 14.sp)
        Text(endereco, color = Texto2, fontSize = 12.sp, maxLines = 3)
        Button(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endereco))) } },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo)) {
            Text("Abrir no navegador", fontSize = 15.sp)
        }
        TextButton(onClick = voltar) { Text("Editar endereço", color = Texto, fontSize = 14.sp) }
        Text("Você também pode usar a bolha de tradução por cima do navegador.", color = Texto2, fontSize = 13.sp)
    }
}

/** Título curto tirado do próprio endereço; não há nome do capítulo em lugar nenhum além dele. */
private fun titulo(endereco: String): String = runCatching {
    val partes = Uri.parse(endereco).pathSegments.filter { it.isNotBlank() && it != "viewer" && it.length > 1 }
    partes.takeLast(2).joinToString(" · ") { it.replace('-', ' ').replace('_', ' ') }.ifBlank { "Capítulo" }
}.getOrElse { "Capítulo" }
