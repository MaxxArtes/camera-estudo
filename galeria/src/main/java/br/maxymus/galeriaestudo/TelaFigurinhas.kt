package br.maxymus.galeriaestudo

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File

/**
 * Minhas figurinhas (desenho do Astra, 21/09): coleção fixa, não aba nova. Salvar uma figurinha já é resultado
 * completo; o texto explica o mínimo de 3 do WhatsApp sem transformar isso em fracasso.
 */
@Composable
fun TelaFigurinhas(aoFechar: () -> Unit, aoCriar: () -> Unit) {
    val ctx = LocalContext.current
    var arquivos by remember { mutableStateOf<List<File>>(emptyList()) }
    var versao by remember { mutableStateOf(0) }
    var apagar by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(versao) { arquivos = Figurinha.acervo(ctx) }
    BackHandler { aoFechar() }

    fun uriDe(f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".arquivos", f)

    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding()) {
        BarraSuperior("Minhas figurinhas", voltar = aoFechar)
        val n = arquivos.size
        Text(
            when (n) {
                0 -> "Nenhuma figurinha ainda. Abra uma foto e use Criar figurinha."
                1 -> "Figurinha salva. Crie mais 2 para montar seu primeiro pacote no WhatsApp."
                2 -> "Você tem 2 figurinhas. Crie mais 1 para montar seu primeiro pacote no WhatsApp."
                else -> "$n figurinhas. Toque para compartilhar; segure para apagar."
            }, color = Tema.Texto2, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(arquivos) { f ->
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Tema.Superficie).clickable {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "image/webp"; putExtra(Intent.EXTRA_STREAM, uriDe(f)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(i, "Compartilhar figurinha"))
                }) {
                    AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                        TextButton(onClick = { apagar = f }) { Text("×", color = Tema.Texto2, fontSize = 16.sp) }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            if (n in 1..2) Text("Um pacote do WhatsApp precisa de pelo menos 3 figurinhas. Até lá, dá para compartilhar cada uma como imagem.",
                color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Button(onClick = aoCriar, modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White)) {
                Text(if (n == 0) "Escolher uma foto" else "Criar outra")
            }
        }
    }
    apagar?.let { f ->
        AlertDialog(onDismissRequest = { apagar = null }, title = { Text("Apagar figurinha?") },
            text = { Text("A foto original não é afetada.") },
            confirmButton = { TextButton(onClick = { f.delete(); apagar = null; versao++ }) { Text("Apagar", color = Tema.Coral) } },
            dismissButton = { TextButton(onClick = { apagar = null }) { Text("Cancelar") } })
    }
}
