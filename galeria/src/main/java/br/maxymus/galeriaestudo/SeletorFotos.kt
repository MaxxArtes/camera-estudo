package br.maxymus.galeriaestudo

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Seletor de várias fotos para adicionar a um álbum. Mostra as que ainda NÃO estão no álbum; toca para marcar. */
@Composable
fun SeletorFotos(album: Long, midias: List<Midia>, aoFechar: () -> Unit, aoConcluido: (Int) -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val estado = rememberLazyListState()
    var jaNoAlbum by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selecionadas by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var adicionando by remember { mutableStateOf(false) }
    LaunchedEffect(album) { jaNoAlbum = withContext(Dispatchers.IO) { Indice.get(ctx).fotosDoAlbum(album).toSet() } }
    val disponiveis = remember(midias, jaNoAlbum) { midias.filter { it.id !in jaNoAlbum } }
    val quandoDe = remember(midias) { midias.associate { it.id to it.quando } }
    BackHandler { aoFechar() }

    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = aoFechar) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = Tema.Texto) }
            Column(Modifier.weight(1f)) {
                Text("Adicionar fotos", color = Tema.Texto, fontSize = 18.sp)
                if (selecionadas.isNotEmpty()) Text(Midias.selecionados(selecionadas.size), color = Tema.Texto2, fontSize = 13.sp)
            }
        }
        Box(Modifier.weight(1f)) {
            if (disponiveis.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Todas as fotos já estão neste álbum.", color = Tema.Texto2) }
            else GradePorDia(disponiveis, estado, selecionando = true, selecionadas = selecionadas,
                aoLongo = { i -> val fid = disponiveis[i].id; selecionadas = if (fid in selecionadas) selecionadas - fid else selecionadas + fid }) { i ->
                val fid = disponiveis[i].id; selecionadas = if (fid in selecionadas) selecionadas - fid else selecionadas + fid
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Button(onClick = {
                if (selecionadas.isEmpty() || adicionando) return@Button
                adicionando = true
                val alvo = selecionadas.toList()
                escopo.launch {
                    val n = withContext(Dispatchers.IO) { Indice.get(ctx).adicionarAoAlbum(album, alvo.map { it to (quandoDe[it] ?: 0L) }) }
                    Toast.makeText(ctx, "$n ${if (n == 1) "foto adicionada" else "fotos adicionadas"}", Toast.LENGTH_SHORT).show()
                    adicionando = false; aoConcluido(n)
                }
            }, enabled = selecionadas.isNotEmpty() && !adicionando, modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White, disabledContainerColor = Tema.Superficie, disabledContentColor = Tema.Texto2)) {
                Text(if (adicionando) "Adicionando…" else if (selecionadas.isEmpty()) "Adicionar fotos" else "Adicionar ${selecionadas.size} ${if (selecionadas.size == 1) "foto" else "fotos"}")
            }
        }
    }
}
