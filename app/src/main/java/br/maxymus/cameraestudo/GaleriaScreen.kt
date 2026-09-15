package br.maxymus.cameraestudo

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Galeria das fotos tiradas pelo app: grade de miniaturas, toque abre em tela cheia,
 * com compartilhar e apagar. Lê o MediaStore, então também vê o que o app tirou antes.
 */
@Composable
fun GaleriaScreen(voltar: () -> Unit) {
    val contexto = LocalContext.current
    var fotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var aberta by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) { fotos = Fotos.listar(contexto) }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (aberta != null) aberta = null else voltar() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
            Text(
                if (aberta != null) "Foto" else "Suas fotos (${fotos.size})",
                color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            if (aberta != null) {
                IconButton(onClick = {
                    val envio = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, aberta); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    contexto.startActivity(Intent.createChooser(envio, "Compartilhar foto"))
                }) { Icon(Icons.Filled.Share, contentDescription = "Compartilhar", tint = Color.White) }
                IconButton(onClick = {
                    val alvo = aberta ?: return@IconButton
                    if (Fotos.apagar(contexto, alvo)) { fotos = fotos - alvo; aberta = null }
                }) { Icon(Icons.Filled.Delete, contentDescription = "Apagar", tint = Color.White) }
            }
        }

        val emTelaCheia = aberta
        if (emTelaCheia != null) {
            Box(modifier = Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                AsyncImage(model = emTelaCheia, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        } else if (fotos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhuma foto ainda. Volte e tire a primeira.", color = Color.White)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(fotos, key = { it.toString() }) { uri ->
                    AsyncImage(
                        model = uri, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.aspectRatio(1f).clickable { aberta = uri }
                    )
                }
            }
        }
    }
}
