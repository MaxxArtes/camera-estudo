package br.maxymus.galeriaestudo

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tela de um álbum manual: grade por dia, adicionar/remover fotos, renomear, excluir o álbum (não as fotos). */
@Composable
fun TelaAlbum(id: Long, porId: Map<Long, Midia>, versao: Int, voltar: () -> Unit, aoAdicionar: () -> Unit, aoAbrir: (List<Midia>, Int) -> Unit, aoMudou: () -> Unit, aoSumiu: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val estado = rememberLazyListState()
    var resumo by remember { mutableStateOf<Indice.AlbumManual?>(null) }
    var fotos by remember { mutableStateOf<List<Midia>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    var renomeando by remember { mutableStateOf(false) }
    var confirmarExcluir by remember { mutableStateOf(false) }
    var selecionando by remember { mutableStateOf(false) }
    var selecionadas by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(id, versao) {
        withContext(Dispatchers.IO) {
            val db = Indice.get(ctx)
            resumo = db.albumManual(id); fotos = db.fotosDoAlbum(id).mapNotNull { porId[it] }
        }
    }
    fun sair() { selecionando = false; selecionadas = emptySet() }
    BackHandler(enabled = selecionando) { sair() }

    val r = resumo
    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding()) {
        if (selecionando) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { sair() }) { Icon(Icons.Filled.Close, contentDescription = "Cancelar", tint = Tema.Texto) }
                Text("${selecionadas.size} selecionada(s)", color = Tema.Texto, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { selecionadas = if (selecionadas.size == fotos.size) emptySet() else fotos.map { it.id }.toSet() }) {
                    Icon(Icons.Filled.SelectAll, contentDescription = "Selecionar tudo", tint = if (selecionadas.size == fotos.size && fotos.isNotEmpty()) Tema.Coral else Tema.Texto)
                }
            }
        } else {
            BarraSuperior(r?.nome ?: "Álbum", voltar = voltar) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = Tema.Texto) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Adicionar fotos") }, onClick = { menu = false; aoAdicionar() })
                        if (fotos.isNotEmpty()) DropdownMenuItem(text = { Text("Selecionar fotos") }, onClick = { menu = false; selecionando = true })
                        DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; renomeando = true })
                        DropdownMenuItem(text = { Text("Excluir álbum") }, onClick = { menu = false; confirmarExcluir = true })
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (fotos.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Seu álbum está pronto", color = Tema.Texto, fontSize = 20.sp)
                Text("Escolha fotos para adicionar a este álbum.", color = Tema.Texto2, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                Button(onClick = aoAdicionar, colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White)) { Text("Adicionar fotos") }
            } else GradePorDia(fotos, estado, cabecalho = {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(r?.nome ?: "Álbum", color = Tema.Texto, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text("${Midias.numero(fotos.size)} ${if (fotos.size == 1) "foto" else "fotos"} · Álbum manual", color = Tema.Texto2, fontSize = 14.sp)
                    if (!selecionando) TextButton(onClick = aoAdicionar, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.padding(top = 4.dp)) { Text("Adicionar fotos", color = Tema.Coral) }
                }
            }, selecionando = selecionando, selecionadas = selecionadas,
                aoLongo = { i -> selecionando = true; selecionadas = setOf(fotos[i].id) }) { i ->
                if (selecionando) { val fid = fotos[i].id; selecionadas = if (fid in selecionadas) selecionadas - fid else selecionadas + fid }
                else aoAbrir(fotos, i)
            }
            if (selecionando) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xF01A1A1F)).navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                AcaoBarra(Icons.Filled.PlaylistRemove, "Remover do álbum", selecionadas.isNotEmpty()) {
                    val alvo = selecionadas.toList()
                    escopo.launch {
                        withContext(Dispatchers.IO) { val db = Indice.get(ctx); alvo.forEach { db.removerDoAlbum(id, it) } }
                        Toast.makeText(ctx, "${alvo.size} ${if (alvo.size == 1) "foto removida" else "fotos removidas"} do álbum", Toast.LENGTH_SHORT).show()
                        sair(); aoMudou()
                    }
                }
            }
        }
    }

    if (renomeando && r != null) DialogoRenomear(r.nome) { novo ->
        renomeando = false
        if (novo != null) escopo.launch { withContext(Dispatchers.IO) { Indice.get(ctx).renomearAlbum(id, novo) }; aoMudou() }
    }
    if (confirmarExcluir) AlertDialog(onDismissRequest = { confirmarExcluir = false },
        title = { Text("Excluir o álbum \"${r?.nome ?: ""}\"?") },
        text = { Text("As fotos continuarão no aparelho e nos outros álbuns.") },
        confirmButton = { TextButton(onClick = { confirmarExcluir = false; escopo.launch { withContext(Dispatchers.IO) { Indice.get(ctx).apagarAlbum(id) }; aoSumiu() } }) { Text("Excluir álbum", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { confirmarExcluir = false }) { Text("Cancelar") } })
}

@Composable
fun AcaoBarra(icone: androidx.compose.ui.graphics.vector.ImageVector, rotulo: String, ativo: Boolean, corAtivo: Color = Color.Unspecified, aoTocar: () -> Unit) {
    val cor = if (!ativo) Tema.Texto2 else if (corAtivo != Color.Unspecified) corAtivo else Tema.Texto
    val base = Modifier.height(56.dp)
    Column((if (ativo) base.clickable(onClick = aoTocar) else base).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icone, contentDescription = rotulo, tint = cor)
        Text(rotulo, color = cor, fontSize = 11.sp)
    }
}

@Composable
fun DialogoRenomear(nomeAtual: String, aoConcluir: (String?) -> Unit) {
    var nome by remember { mutableStateOf(nomeAtual) }
    AlertDialog(onDismissRequest = { aoConcluir(null) }, title = { Text("Renomear") },
        text = { androidx.compose.material3.OutlinedTextField(value = nome, onValueChange = { if (it.length <= 60) nome = it }, singleLine = true, label = { Text("Nome do álbum") }) },
        confirmButton = { TextButton(enabled = nome.trim().isNotEmpty(), onClick = { aoConcluir(nome.trim()) }) { Text("Salvar", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { aoConcluir(null) }) { Text("Cancelar") } })
}
