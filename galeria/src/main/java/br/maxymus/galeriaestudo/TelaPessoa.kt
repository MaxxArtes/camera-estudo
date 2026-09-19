package br.maxymus.galeriaestudo

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Álbum de uma pessoa: cabeçalho com rosto, nome e contagem; grade por dia; dar nome, juntar, ocultar, desfazer. */
@Composable
fun TelaPessoa(id: Long, porId: Map<Long, Midia>, versao: Int, voltar: () -> Unit, aoAbrir: (List<Midia>, Int) -> Unit, aoMudou: () -> Unit, aoSumiu: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val estadoLista = rememberLazyListState()
    var resumo by remember { mutableStateOf<Indice.Resumo?>(null) }
    var fotos by remember { mutableStateOf<List<Midia>>(emptyList()) }
    var ultimaJuncao by remember { mutableStateOf<Indice.Juncao?>(null) }
    var menu by remember { mutableStateOf(false) }
    var nomeando by remember { mutableStateOf(false) }
    var nome by remember { mutableStateOf("") }
    var escolhendo by remember { mutableStateOf(false) }
    var candidatos by remember { mutableStateOf<List<Indice.Resumo>>(emptyList()) }
    var confirmando by remember { mutableStateOf<Indice.Resumo?>(null) }
    var desfazer by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(id, versao) {
        withContext(Dispatchers.IO) {
            val db = Indice.get(ctx)
            resumo = db.resumo(id); fotos = db.fotosDaPessoa(id).mapNotNull { porId[it] }; ultimaJuncao = db.ultimaJuncao(id)
        }
    }
    LaunchedEffect(desfazer) { if (desfazer != null) { delay(8000); desfazer = null } }

    fun junta(outra: Indice.Resumo, nomeFinal: String?) {
        confirmando = null; escolhendo = false
        escopo.launch {
            val j = withContext(Dispatchers.IO) { Indice.get(ctx).juntar(outra.id, id, nomeFinal) }
            Telemetria.evento("juntar"); desfazer = j; aoMudou()
        }
    }

    val r = resumo
    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding().navigationBarsPadding()) {
        BarraSuperior(r?.nome ?: "Sem nome", voltar = voltar) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = Tema.Texto) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Juntar com outra pessoa") }, onClick = {
                        menu = false
                        escopo.launch {
                            candidatos = withContext(Dispatchers.IO) { val db = Indice.get(ctx); (db.pessoas(false) + db.pessoas(true)).filter { it.id != id } }
                            escolhendo = true
                        }
                    })
                    DropdownMenuItem(text = { Text(if (r?.oculta == true) "Mostrar novamente" else "Ocultar pessoa") }, onClick = {
                        menu = false
                        val ocultar = r?.oculta != true
                        escopo.launch { withContext(Dispatchers.IO) { Indice.get(ctx).ocultar(id, ocultar) }; Telemetria.evento(if (ocultar) "ocultar" else "mostrar"); aoSumiu() }
                    })
                    val j = ultimaJuncao
                    if (j != null) DropdownMenuItem(text = { Text("Desfazer última junção") }, onClick = {
                        menu = false
                        escopo.launch { withContext(Dispatchers.IO) { Indice.get(ctx).desfazJuncao(j.id) }; desfazer = null; aoMudou() }
                    })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            GradePorDia(fotos, estadoLista, cabecalho = {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Capa(id, 80.dp)
                    Text(r?.nome ?: "Sem nome", color = if (r?.nome == null) Tema.Texto2 else Tema.Texto, fontSize = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    Text("${Midias.numero(r?.fotos ?: fotos.size)} fotos", color = Tema.Texto2, fontSize = 14.sp)
                    TextButton(onClick = { nome = r?.nome ?: ""; nomeando = true }, modifier = Modifier.height(48.dp)) { Text(if (r?.nome == null) "Dar nome" else "Renomear", color = Tema.Coral) }
                }
            }, aoAbrir = { i -> aoAbrir(fotos, i) })
            val d = desfazer
            if (d != null) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).background(Tema.Superficie).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pessoas juntadas", color = Tema.Texto, modifier = Modifier.weight(1f))
                TextButton(onClick = { escopo.launch { withContext(Dispatchers.IO) { Indice.get(ctx).desfazJuncao(d) }; desfazer = null; aoMudou() } }) { Text("Desfazer", color = Tema.Coral) }
            }
        }
    }

    if (nomeando) AlertDialog(onDismissRequest = { nomeando = false },
        title = { Text(if (r?.nome == null) "Dar nome" else "Renomear") },
        text = { OutlinedTextField(value = nome, onValueChange = { nome = it }, singleLine = true, label = { Text("Nome") }) },
        confirmButton = { TextButton(onClick = {
            nomeando = false
            escopo.launch { withContext(Dispatchers.IO) { Indice.get(ctx).renomear(id, nome) }; Telemetria.evento("pessoa_nome", mapOf("vazio" to nome.isBlank())); aoMudou() }
        }) { Text("Salvar", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { nomeando = false }) { Text("Cancelar") } })

    if (escolhendo) AlertDialog(onDismissRequest = { escolhendo = false }, confirmButton = { TextButton(onClick = { escolhendo = false }) { Text("Cancelar") } },
        title = { Text("Juntar com quem?") },
        text = {
            if (candidatos.isEmpty()) Text("Não há outra pessoa para juntar.", color = Tema.Texto2)
            else LazyColumn(Modifier.height(360.dp)) {
                items(candidatos, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { confirmando = c }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Capa(c.id, 44.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(c.nome ?: "Sem nome", color = if (c.nome == null) Tema.Texto2 else Tema.Texto, fontSize = 16.sp)
                            Text("${Midias.numero(c.fotos)} ${if (c.fotos == 1) "foto" else "fotos"}${if (c.oculta) " · oculta" else ""}", color = Tema.Texto2, fontSize = 13.sp)
                        }
                    }
                }
            }
        })

    val c = confirmando
    if (c != null) {
        val nomeAqui = r?.nome; val nomeLa = c.nome
        val doisNomes = nomeAqui != null && nomeLa != null && nomeAqui != nomeLa
        AlertDialog(onDismissRequest = { confirmando = null },
            title = { Text("Juntar estas pessoas?") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Capa(id, 64.dp); Text(nomeAqui ?: "Sem nome", color = Tema.Texto2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Capa(c.id, 64.dp); Text(nomeLa ?: "Sem nome", color = Tema.Texto2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                    if (doisNomes) Text("As duas têm nome. Escolha qual fica:", color = Tema.Texto2, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                }
            },
            confirmButton = {
                if (doisNomes) Row {
                    TextButton(onClick = { junta(c, nomeAqui) }) { Text(nomeAqui ?: "", color = Tema.Coral) }
                    TextButton(onClick = { junta(c, nomeLa) }) { Text(nomeLa ?: "", color = Tema.Coral) }
                } else TextButton(onClick = { junta(c, nomeAqui ?: nomeLa) }) { Text("Juntar", color = Tema.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmando = null }) { Text("Cancelar") } })
    }
}
