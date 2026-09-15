package br.maxymus.cameraestudo

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Galeria das fotos e vídeos do app: grade, tela cheia com Compartilhar, Editar, Informações e
 * Favorito (como no desenho de referência), e apagar no canto. Vídeo abre no player do sistema.
 */
@Composable
fun GaleriaScreen(voltar: () -> Unit) {
    val contexto = LocalContext.current
    var midias by remember { mutableStateOf<List<Midia>>(emptyList()) }
    var favoritos by remember { mutableStateOf(Fotos.favoritos(contexto)) }
    var aberta by remember { mutableStateOf<Midia?>(null) }
    var mostrarInfo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { midias = Fotos.listar(contexto) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E12)).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (aberta != null) aberta = null else voltar() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
            Text(
                if (aberta != null) "" else "Suas fotos (${midias.size})",
                color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            if (aberta != null) {
                IconButton(onClick = {
                    val alvo = aberta ?: return@IconButton
                    if (Fotos.apagar(contexto, alvo.uri)) { midias = midias - alvo; aberta = null }
                    else Toast.makeText(contexto, "Não consegui apagar", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Filled.Delete, contentDescription = "Apagar", tint = Color.White) }
            }
        }

        val atual = aberta
        if (atual != null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(model = atual.uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (atual.ehVideo) {
                    Box(modifier = Modifier.size(72.dp).background(Color(0x99000000), MaterialTheme.shapes.extraLarge).clickable {
                        contexto.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(atual.uri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, contentDescription = "Reproduzir", tint = Color.White, modifier = Modifier.size(40.dp)) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Acao(Icons.Filled.Share, "Compartilhar") {
                    val envio = Intent(Intent.ACTION_SEND).apply {
                        type = if (atual.ehVideo) "video/mp4" else "image/jpeg"; putExtra(Intent.EXTRA_STREAM, atual.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    contexto.startActivity(Intent.createChooser(envio, "Compartilhar"))
                }
                Acao(Icons.Filled.Tune, "Editar") {
                    val editar = Intent(Intent.ACTION_EDIT).apply { setDataAndType(atual.uri, if (atual.ehVideo) "video/*" else "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    runCatching { contexto.startActivity(Intent.createChooser(editar, "Editar com")) }
                        .onFailure { Toast.makeText(contexto, "Nenhum editor instalado", Toast.LENGTH_SHORT).show() }
                }
                Acao(Icons.Filled.Info, "Informações") { mostrarInfo = true }
                val ehFavorita = atual.uri.toString() in favoritos
                Acao(if (ehFavorita) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorito", if (ehFavorita) Color(0xFFF0325A) else Color.White) {
                    Fotos.alternaFavorito(contexto, atual.uri); favoritos = Fotos.favoritos(contexto)
                }
            }
            if (mostrarInfo) {
                val quando = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(atual.data * 1000))
                val tamanho = if (atual.bytes > 1_048_576) String.format("%.1f MB", atual.bytes / 1_048_576.0) else "${atual.bytes / 1024} KB"
                AlertDialog(
                    onDismissRequest = { mostrarInfo = false },
                    confirmButton = { TextButton(onClick = { mostrarInfo = false }) { Text("Fechar") } },
                    title = { Text("Informações") },
                    text = {
                        Column {
                            Text("Arquivo: ${atual.nome}")
                            Text("Data: $quando")
                            Text("Tamanho: $tamanho")
                            if (atual.largura > 0) Text("Dimensões: ${atual.largura} × ${atual.altura}")
                            if (atual.ehVideo) Text("Duração: ${atual.duracaoMs / 1000} s")
                            Text("Pasta: ${if (atual.ehVideo) "Filmes" else "Imagens"}/${Fotos.PASTA}")
                        }
                    }
                )
            }
        } else if (midias.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhuma foto ainda. Volte e tire a primeira.", color = Color.White)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(midias, key = { it.uri.toString() }) { m ->
                    Box(modifier = Modifier.aspectRatio(1f).clickable { aberta = m }) {
                        AsyncImage(model = m.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (m.ehVideo) Icon(Icons.Filled.PlayArrow, contentDescription = "Vídeo", tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                        if (m.uri.toString() in favoritos) Icon(Icons.Filled.Favorite, contentDescription = "Favorita", tint = Color(0xFFF0325A), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Acao(icone: ImageVector, rotulo: String, cor: Color = Color.White, aoTocar: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = aoTocar).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icone, contentDescription = rotulo, tint = cor)
        Text(rotulo, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
