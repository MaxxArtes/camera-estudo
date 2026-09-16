package br.maxymus.cameraestudo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Pessoas cadastradas pelo reconhecimento: miniatura, nome (toque para renomear), fotos, referência de pele; apagar uma ou todas; ligar/desligar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaPessoas(aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    var lista by remember { mutableStateOf<List<Pessoas.Pessoa>>(emptyList()) }
    var ligado by remember { mutableStateOf(Pessoas.ligado) }
    var renomeando by remember { mutableStateOf<Pessoas.Pessoa?>(null) }
    var confirmarTudo by remember { mutableStateOf(false) }
    var versao by remember { mutableStateOf(0) }
    LaunchedEffect(versao) { lista = Pessoas.listar(contexto) }

    ModalBottomSheet(onDismissRequest = aoFechar, containerColor = Color(0xFF16161B)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pessoas (${lista.size})", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (ligado) "Reconhecendo" else "Desligado", color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
            Switch(checked = ligado, onCheckedChange = { ligado = it; Pessoas.alternar(contexto, it) })
        }
        Text("Rostos são reconhecidos no aparelho e ficam só aqui. A referência de pele de cada pessoa corrige a cor do rosto nas próximas fotos.",
            color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (lista.isEmpty()) Text("Ninguém cadastrado ainda. Tire uma foto com um rosto.", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(lista, key = { it.id }) { p ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF333333))) {
                        AsyncImage(model = Pessoas.miniatura(contexto, p.id), contentDescription = p.nome, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp))
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 14.dp).clickable { renomeando = p }) {
                        Text(p.nome, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${p.fotos} foto(s), ${p.vetores.size} amostra(s)" + (if (p.pele != null) ", pele aprendida (${p.nPele})" else ", sem referência de pele"), color = Color(0xFFBDBDBD), fontSize = 11.sp)
                    }
                    IconButton(onClick = { escopo.launch { Pessoas.apagar(contexto, p.id); versao++ } }) { Icon(Icons.Filled.Delete, contentDescription = "Apagar", tint = Color.White) }
                }
            }
            if (lista.isNotEmpty()) item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirmarTudo = true }) { Text("Apagar todas", color = Color(0xFFFF5A5F)) }
                }
            }
        }
    }
    renomeando?.let { p ->
        var nome by remember(p.id) { mutableStateOf(p.nome) }
        AlertDialog(onDismissRequest = { renomeando = null }, title = { Text("Nome") },
            text = { OutlinedTextField(value = nome, onValueChange = { nome = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { escopo.launch { Pessoas.renomear(contexto, p.id, nome.trim().ifEmpty { p.nome }); versao++ }; renomeando = null }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { renomeando = null }) { Text("Cancelar") } })
    }
    if (confirmarTudo) AlertDialog(onDismissRequest = { confirmarTudo = false }, title = { Text("Apagar todas as pessoas?") }, text = { Text("Vetores, miniaturas e referências de pele. Não dá para desfazer.") },
        confirmButton = { TextButton(onClick = { confirmarTudo = false; escopo.launch { Pessoas.apagarTudo(contexto); versao++ } }) { Text("Apagar", color = Color(0xFFFF5A5F)) } },
        dismissButton = { TextButton(onClick = { confirmarTudo = false }) { Text("Cancelar") } })
}
