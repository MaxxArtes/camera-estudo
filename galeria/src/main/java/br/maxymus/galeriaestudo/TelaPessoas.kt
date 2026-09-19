package br.maxymus.galeriaestudo

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aba Pessoas: grupos de rostos (2 colunas, capas circulares), aparições únicas atrás de uma linha, estado da análise. */
@Composable
fun TelaPessoas(estadoGrade: LazyGridState, versao: Int, parcial: Boolean, aoAbrirPessoa: (Long) -> Unit, aoAbrirAlbumManual: (Long) -> Unit, aoMudou: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val estado by Indexador.estado.collectAsStateWithLifecycle()
    var ativada by remember { mutableStateOf(Indexador.ativada(ctx)) }
    var pessoas by remember { mutableStateOf<List<Indice.Resumo>>(emptyList()) }
    var carregou by remember { mutableStateOf(false) }
    var mostrandoUnicas by remember { mutableStateOf(false) }
    var mostrandoOcultas by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmarApagar by remember { mutableStateOf(false) }
    var sobre by remember { mutableStateOf(false) }
    var reexibir by remember { mutableStateOf<Indice.Resumo?>(null) }
    var mostrarConcluida by remember { mutableStateOf(false) }
    var albunsManuais by remember { mutableStateOf<List<Indice.AlbumManual>>(emptyList()) }
    var criarAlbum by remember { mutableStateOf(false) }
    LaunchedEffect(versao) { albunsManuais = withContext(Dispatchers.IO) { Indice.get(ctx).listarAlbuns() } }

    // recarrega ao entrar, quando algo foi editado e a cada ~25 fotos analisadas, sem reordenar o que já está na tela
    LaunchedEffect(versao, estado.feitas / 25, estado.rodando, mostrandoOcultas) {
        val novo = withContext(Dispatchers.IO) { Indice.get(ctx).pessoas(ocultas = mostrandoOcultas) }
        val ordem = pessoas.map { it.id }
        pessoas = if (ordem.isEmpty()) novo else ordem.mapNotNull { id -> novo.firstOrNull { it.id == id } } + novo.filter { n -> n.id !in ordem }
        carregou = true
    }
    LaunchedEffect(estado.concluiuEm) {
        if (estado.concluiuEm > 0L && System.currentTimeMillis() - estado.concluiuEm < 30_000L) { mostrarConcluida = true; delay(4000); mostrarConcluida = false }
    }

    val visiveis = pessoas.filter { it.fotos > 1 }
    val unicas = pessoas.filter { it.fotos == 1 }
    val subtela = mostrandoUnicas || mostrandoOcultas
    Column(Modifier.fillMaxSize().background(Tema.Fundo)) {
        BarraSuperior(when { mostrandoOcultas -> "Álbuns ocultos"; mostrandoUnicas -> "Aparições únicas"; else -> "Álbuns" },
            voltar = if (subtela) ({ mostrandoUnicas = false; mostrandoOcultas = false; pessoas = emptyList() }) else null) {
            if (!subtela) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = Tema.Texto) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Álbuns ocultos") }, onClick = { menu = false; pessoas = emptyList(); mostrandoOcultas = true })
                    DropdownMenuItem(text = { Text("Sobre a análise") }, onClick = { menu = false; sobre = true })
                    if (ativada) DropdownMenuItem(text = { Text("Apagar dados de rostos") }, onClick = { menu = false; confirmarApagar = true })
                }
            }
        }
        if (ativada) when {
            estado.preparando -> FaixaProgresso("Preparando análise", "Contando as fotos do aparelho.", null, null)
            estado.rodando -> FaixaProgresso("Analisando ${Midias.numero(estado.feitas)} de ${Midias.numero(estado.total)} fotos", "Você já pode abrir os álbuns encontrados.", estado, "Pausar") { Indexador.pausar() }
            estado.pausada -> FaixaProgresso("Pausado por você", "${Midias.numero(estado.feitas)} de ${Midias.numero(estado.total)} fotos analisadas.", estado, "Continuar") { Indexador.continuar(ctx) }
            mostrarConcluida -> FaixaProgresso("Análise concluída", "Novas fotos serão verificadas automaticamente.", null, null)
        }
        if (parcial && ativada) Text("Somente fotos permitidas.", color = Tema.Texto2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

        when {
            !ativada -> CartaoInicio { Indexador.ativar(ctx); ativada = true; Telemetria.evento("analise_ativada") }
            !carregou -> Box(Modifier.fillMaxSize())
            mostrandoOcultas && pessoas.isEmpty() -> Vazio("Nenhuma pessoa oculta.")
            pessoas.isEmpty() -> Vazio(if (estado.rodando || estado.preparando || estado.concluiuEm == 0L) "Procurando rostos nas suas fotos…" else "Nenhum rosto encontrado\n\nAnalisamos ${Midias.numero(estado.total)} fotos. Novas fotos serão verificadas automaticamente.")
            else -> {
                val lista = when { mostrandoUnicas -> unicas; mostrandoOcultas -> pessoas; else -> visiveis }
                LazyVerticalGrid(columns = GridCells.Fixed(2), state = estadoGrade, contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                    if (!subtela) {
                        item(key = "tit-meus", span = { GridItemSpan(2) }) { SecaoTitulo("Meus álbuns", "Álbuns que você monta") }
                        item(key = "criar") { CardCriar { criarAlbum = true } }
                        items(albunsManuais, key = { "m" + it.id }) { a -> CardAlbumManual(a) { aoAbrirAlbumManual(a.id) } }
                        item(key = "tit-pessoas", span = { GridItemSpan(2) }) { SecaoTitulo("Pessoas", "Agrupadas automaticamente neste aparelho") }
                    }
                    if (!subtela && visiveis.isEmpty()) item(key = "so-unicas", span = { GridItemSpan(2) }) {
                        Text("Encontramos rostos que aparecem em uma única foto.", color = Tema.Texto2, fontSize = 14.sp)
                    }
                    items(lista, key = { it.id }) { p -> CelulaPessoa(p) { if (mostrandoOcultas) reexibir = p else aoAbrirPessoa(p.id) } }
                    if (!subtela && unicas.isNotEmpty()) item(key = "unicas", span = { GridItemSpan(2) }) {
                        Row(Modifier.fillMaxWidth().clickable { pessoas = emptyList(); mostrandoUnicas = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Aparições únicas · ${unicas.size}", color = Tema.Texto, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Tema.Texto2)
                        }
                    }
                }
            }
        }
    }

    if (criarAlbum) DialogoCriarAlbum(nomesExistentes = albunsManuais.map { it.nome }, aoFechar = { criarAlbum = false }) { nome ->
        criarAlbum = false
        escopo.launch { val a = withContext(Dispatchers.IO) { Indice.get(ctx).criarAlbum(nome) }; aoAbrirAlbumManual(a) }
    }
    if (sobre) AlertDialog(onDismissRequest = { sobre = false }, confirmButton = { TextButton(onClick = { sobre = false }) { Text("Fechar") } },
        title = { Text("Sobre a análise") },
        text = { Text("O app agrupa rostos parecidos neste aparelho e pode errar. Nenhuma imagem sai do celular: os dados ficam em arquivos do app e não entram no backup.\n\nVocê pode dar nome, juntar, ocultar e corrigir com \"Não é esta pessoa\". Em \"Apagar dados de rostos\" tudo isso é removido; as fotos ficam.") })
    if (confirmarApagar) AlertDialog(onDismissRequest = { confirmarApagar = false },
        title = { Text("Apagar dados de rostos?") },
        text = { Text("Remove nomes e agrupamentos, preserva as fotos e desativa a análise até você ativar de novo.") },
        confirmButton = { TextButton(onClick = {
            confirmarApagar = false
            escopo.launch { Indexador.desativar(ctx); withContext(Dispatchers.IO) { Indice.get(ctx).apagarTudo(ctx) }; ativada = false; pessoas = emptyList(); Telemetria.evento("apagar_dados"); aoMudou() }
        }) { Text("Apagar", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { confirmarApagar = false }) { Text("Cancelar") } })
    reexibir?.let { p -> AlertDialog(onDismissRequest = { reexibir = null }, title = { Text(p.nome ?: "Sem nome") }, text = { Text("Mostrar esta pessoa novamente na lista?") },
        confirmButton = { TextButton(onClick = { reexibir = null; escopo.launch { withContext(Dispatchers.IO) { Indice.get(ctx).ocultar(p.id, false) }; pessoas = emptyList(); aoMudou() } }) { Text("Mostrar novamente", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { reexibir = null }) { Text("Cancelar") } }) }
}

@Composable
private fun FaixaProgresso(titulo: String, sub: String, estado: Indexador.Estado?, acao: String?, aoTocar: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(Tema.Superficie).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(titulo, color = Tema.Texto, fontSize = 15.sp)
                Text(sub, color = Tema.Texto2, fontSize = 13.sp)
            }
            if (acao != null) TextButton(onClick = aoTocar) { Text(acao, color = Tema.Coral) }
        }
        if (estado != null && estado.total > 0) LinearProgressIndicator(progress = { estado.feitas.toFloat() / estado.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp).height(4.dp), color = Tema.Coral, trackColor = Tema.Fundo)
    }
}

@Composable
private fun CartaoInicio(aoComecar: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Organizar por rostos", color = Tema.Texto, fontSize = 22.sp, textAlign = TextAlign.Center)
        Text("O app agrupa rostos parecidos neste aparelho e pode errar. A primeira análise pode levar dezenas de minutos.", color = Tema.Texto2, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        Button(onClick = aoComecar, colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White)) { Text("Começar análise") }
    }
}

@Composable
private fun Vazio(texto: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(texto, color = Tema.Texto2, fontSize = 15.sp, textAlign = TextAlign.Center) }
}

@Composable
fun CelulaPessoa(p: Indice.Resumo, aoTocar: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = aoTocar), horizontalAlignment = Alignment.CenterHorizontally) {
        Capa(p.id, 112.dp)
        Text(p.nome ?: "Sem nome", color = if (p.nome == null) Tema.Texto2 else Tema.Texto, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text("${Midias.numero(p.fotos)} ${if (p.fotos == 1) "foto" else "fotos"}", color = Tema.Texto2, fontSize = 13.sp)
    }
}

@Composable
private fun SecaoTitulo(titulo: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(titulo, color = Tema.Texto, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(sub, color = Tema.Texto2, fontSize = 14.sp)
    }
}

@Composable
private fun CardCriar(aoTocar: () -> Unit) {
    Column(Modifier.clickable(onClick = aoTocar), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Tema.Superficie).border(1.dp, Tema.Texto2, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Tema.Coral, modifier = Modifier.size(40.dp))
        }
        Text("Criar um álbum", color = Tema.Texto, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CardAlbumManual(a: Indice.AlbumManual, aoTocar: () -> Unit) {
    Column(Modifier.clickable(onClick = aoTocar)) {
        CapaAlbumFlex(a.capa, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(a.nome, color = Tema.Texto, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text("${Midias.numero(a.fotos)} ${if (a.fotos == 1) "foto" else "fotos"}", color = Tema.Texto2, fontSize = 13.sp)
    }
}
