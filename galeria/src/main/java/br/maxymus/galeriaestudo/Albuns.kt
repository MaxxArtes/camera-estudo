package br.maxymus.galeriaestudo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Diálogo de criar/nomear álbum, com foco no campo e checagem de nome duplicado. */
@Composable
fun DialogoCriarAlbum(nomesExistentes: List<String>, rotuloConfirmar: String = "Criar", aoFechar: () -> Unit, aoCriar: (String) -> Unit) {
    var nome by remember { mutableStateOf("") }
    val foco = remember { FocusRequester() }
    val teclado = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { foco.requestFocus(); teclado?.show() }
    val limpo = nome.trim()
    val duplicado = limpo.isNotEmpty() && nomesExistentes.any { Midias.semAcento(it) == Midias.semAcento(limpo) }
    AlertDialog(onDismissRequest = aoFechar,
        title = { Text("Criar álbum") },
        text = {
            Column {
                OutlinedTextField(value = nome, onValueChange = { if (it.length <= 60) nome = it }, singleLine = true,
                    label = { Text("Nome do álbum") }, modifier = Modifier.focusRequester(foco))
                if (duplicado) Text("Já existe um álbum com esse nome.", color = Tema.Coral, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(enabled = limpo.isNotEmpty() && !duplicado, onClick = { aoCriar(limpo) }) { Text(rotuloConfirmar, color = if (limpo.isNotEmpty() && !duplicado) Tema.Coral else Tema.Texto2) } },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } })
}

/** Painel inferior "Adicionar a um álbum": criar novo, ou escolher um manual (marca os que já contêm a foto). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolhaAlbuns(albuns: List<Indice.AlbumManual>, jaContem: Set<Long>, aoFechar: () -> Unit, aoCriar: () -> Unit, aoEscolher: (Long) -> Unit) {
    ModalBottomSheet(onDismissRequest = aoFechar, containerColor = Tema.Superficie, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Adicionar a um álbum", color = Tema.Texto, fontSize = 18.sp, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = aoCriar).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Tema.Coral)
                Text("Criar um álbum", color = Tema.Coral, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
            }
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(albuns, key = { it.id }) { a ->
                    val tem = a.id in jaContem
                    Row(Modifier.fillMaxWidth().clickable(enabled = !tem) { aoEscolher(a.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CapaQuadrada(a.capa, 48.dp, 10.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(a.nome, color = if (tem) Tema.Texto2 else Tema.Texto, fontSize = 16.sp)
                            Text("${Midias.numero(a.fotos)} ${if (a.fotos == 1) "foto" else "fotos"}" + if (tem) " · Já adicionada" else "", color = Tema.Texto2, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
