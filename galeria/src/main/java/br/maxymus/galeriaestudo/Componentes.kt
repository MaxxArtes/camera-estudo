package br.maxymus.galeriaestudo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

@Composable
fun BarraSuperior(titulo: String, voltar: (() -> Unit)? = null, acoes: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (voltar != null) IconButton(onClick = voltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Tema.Texto) }
        else Spacer(Modifier.width(12.dp))
        Text(titulo, color = Tema.Texto, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        acoes()
    }
}

@Composable
fun FaixaAviso(texto: String, acao: String?, aoTocar: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(Tema.Superficie).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(texto, color = Tema.Texto, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (acao != null) TextButton(onClick = aoTocar) { Text(acao, color = Tema.Coral) }
    }
}

/** Capa de álbum que preenche o modifier dado (para cartão da grade). */
@Composable
fun CapaAlbumFlex(fotoId: Long?, modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Tema.Superficie), contentAlignment = Alignment.Center) {
        if (fotoId != null) AsyncImage(model = Midias.uriFoto(fotoId), imageLoader = remember { Miniaturas.carregador(ctx) }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.PhotoAlbum, contentDescription = null, tint = Tema.Texto2, modifier = Modifier.size(40.dp))
    }
}

/** Capa quadrada de álbum manual (miniatura da foto de capa) ou ícone neutro quando vazio. */
@Composable
fun CapaQuadrada(fotoId: Long?, tamanho: Dp, cantos: Dp = 12.dp) {
    val ctx = LocalContext.current
    Box(Modifier.size(tamanho).clip(RoundedCornerShape(cantos)).background(Tema.Superficie), contentAlignment = Alignment.Center) {
        if (fotoId != null) AsyncImage(model = Midias.uriFoto(fotoId), imageLoader = remember { Miniaturas.carregador(ctx) }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.PhotoAlbum, contentDescription = null, tint = Tema.Texto2, modifier = Modifier.size(tamanho * 0.4f))
    }
}

/** Rosto circular da pessoa (capa de 160 px gerada pelo indexador); a chave de cache muda quando a capa é trocada. */
@Composable
fun Capa(pessoa: Long, tamanho: Dp) {
    val ctx = LocalContext.current
    val f = Indice.capa(ctx, pessoa)
    AsyncImage(model = ImageRequest.Builder(ctx).data(f).memoryCacheKey("capa:$pessoa:${f.lastModified()}").diskCachePolicy(CachePolicy.DISABLED).build(),
        contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(tamanho).clip(CircleShape).background(Tema.Superficie))
}

/** Fotos consecutivas do mesmo dia (a lista já vem ordenada por data), com a posição da primeira na lista inteira. */
class Grupo(val chave: String, val rotulo: String, val inicio: Int, val itens: List<Midia>)

fun agrupaPorDia(midias: List<Midia>): List<Grupo> {
    val grupos = ArrayList<Grupo>()
    var i = 0
    while (i < midias.size) {
        val dia = midias[i].dia
        var j = i + 1
        while (j < midias.size && midias[j].dia == dia) j++
        grupos += Grupo(dia.toString(), Midias.rotuloDia(dia), i, midias.subList(i, j))
        i = j
    }
    return grupos
}

/** Grade de 3 colunas por dia, cabeçalho do dia preso no topo, opcionalmente com um cabeçalho rolável antes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GradePorDia(midias: List<Midia>, estado: LazyListState, cabecalho: (@Composable () -> Unit)? = null, selecionando: Boolean = false, selecionadas: Set<Long> = emptySet(), aoLongo: (Int) -> Unit = {}, aoAbrir: (Int) -> Unit) {
    val ctx = LocalContext.current
    val carregador = remember { Miniaturas.carregador(ctx) }
    val grupos = remember(midias) { agrupaPorDia(midias) }
    LazyColumn(state = estado, modifier = Modifier.fillMaxSize()) {
        if (cabecalho != null) item(key = "cabecalho") { cabecalho() }
        for (g in grupos) {
            stickyHeader(key = "dia:" + g.chave) {
                Box(Modifier.fillMaxWidth().background(Tema.Fundo).height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text(g.rotulo, color = Tema.Texto, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            val linhas = (g.itens.size + 2) / 3
            items(count = linhas, key = { "linha:" + g.chave + ":" + it }) { l ->
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (k in 0 until 3) {
                        val i = l * 3 + k
                        if (i < g.itens.size) { val idx = g.inicio + i; Ladrilho(g.itens[i], carregador, selecionando, g.itens[i].id in selecionadas, Modifier.weight(1f), aoLongo = { aoLongo(idx) }) { aoAbrir(idx) } }
                        else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Ladrilho(m: Midia, carregador: ImageLoader, selecionando: Boolean, marcada: Boolean, modifier: Modifier, aoLongo: () -> Unit, aoTocar: () -> Unit) {
    val ctx = LocalContext.current
    Box(modifier.aspectRatio(1f).background(Tema.Superficie).combinedClickable(onClick = aoTocar, onLongClick = aoLongo)) {
        AsyncImage(model = ImageRequest.Builder(ctx).data(m.uri).size(400).build(), imageLoader = carregador,
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (marcada) Modifier.padding(10.dp) else Modifier))
        if (m.ehVideo && !selecionando) Row(Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Vídeo", tint = Color.White, modifier = Modifier.size(14.dp))
            Text(Midias.duracao(m.duracaoMs), color = Color.White, fontSize = 11.sp)
        }
        if (selecionando) {
            if (marcada) Box(Modifier.fillMaxSize().border(3.dp, Tema.Coral))
            Icon(if (marcada) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null,
                tint = if (marcada) Tema.Coral else Color(0xCCFFFFFF), modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp))
        }
    }
}
