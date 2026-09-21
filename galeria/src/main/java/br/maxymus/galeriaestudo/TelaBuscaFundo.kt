package br.maxymus.galeriaestudo

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Busca de imagem de fundo (desenho do Astra, 21/09): tela inteira, campo de 56 dp, grade de 2 colunas.
 * Só sai daqui com a imagem já baixada; voltar cancela e a edição continua intacta. A busca manda apenas o texto
 * digitado — a foto da pessoa nunca sai do aparelho. O acervo é o Wikimedia Commons, filtrado a domínio público e
 * CC BY (ver Fundos.permitida).
 */
@Composable
fun TelaBuscaFundo(aoFechar: () -> Unit, aoEscolher: (Fundos.Achado, android.net.Uri) -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val grade = rememberLazyGridState()
    var termo by remember { mutableStateOf("") }
    var consulta by remember { mutableStateOf("") }
    var achados by remember { mutableStateOf<List<Fundos.Achado>>(emptyList()) }
    var buscando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf(false) }
    var baixando by remember { mutableStateOf<String?>(null) }
    var demorando by remember { mutableStateOf(false) }
    BackHandler { if (baixando != null) baixando = null else aoFechar() }

    LaunchedEffect(consulta) {
        if (consulta.isBlank()) return@LaunchedEffect
        buscando = true; erro = false
        val r = withContext(Dispatchers.IO) { Fundos.buscar(consulta) }
        buscando = false
        if (r == null) { erro = true; achados = emptyList() } else achados = r
        Telemetria.evento("fundo_busca", mapOf("achados" to (r?.size ?: -1)))
    }
    LaunchedEffect(baixando) { demorando = false; if (baixando != null) { kotlinx.coroutines.delay(10_000); demorando = baixando != null } }

    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = aoFechar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Tema.Texto) }
            OutlinedTextField(
                value = termo, onValueChange = { termo = it },
                placeholder = { Text("Buscar um fundo: praia, cidade…", color = Tema.Texto2, fontSize = 14.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth().height(56.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { consulta = termo.trim() }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Tema.Texto, unfocusedTextColor = Tema.Texto,
                    focusedBorderColor = Tema.Coral, unfocusedBorderColor = Tema.Superficie, cursorColor = Tema.Coral))
        }
        Box(Modifier.weight(1f)) {
            when {
                buscando -> LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(List(6) { it }) { Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Tema.Superficie)) }
                }
                erro -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Não consegui buscar agora. Verifique a conexão.", color = Tema.Texto2, fontSize = 14.sp)
                    TextButton(onClick = { val c = consulta; consulta = ""; consulta = c }) { Text("Tentar de novo", color = Tema.Coral) }
                }
                consulta.isBlank() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Digite o que você quer de fundo e toque em buscar. Só aparecem imagens de domínio público ou com crédito ao autor.",
                        color = Tema.Texto2, fontSize = 14.sp)
                }
                achados.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhuma imagem encontrada. Tente outras palavras.", color = Tema.Texto2, fontSize = 14.sp)
                }
                else -> LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = 16.dp), grade,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(achados) { a ->
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Tema.Superficie).clickable(enabled = baixando == null) {
                            baixando = a.imagem
                            escopo.launch {
                                val u = withContext(Dispatchers.IO) { Fundos.baixar(ctx, a) }
                                if (baixando == a.imagem) { baixando = null; if (u != null) aoEscolher(a, u) else erro = true }
                            }
                        }) {
                            AsyncImage(model = a.miniatura, contentDescription = a.titulo, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text(a.licenca, color = Color.White, fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                }
            }
            if (baixando != null) Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Tema.Coral, strokeWidth = 3.dp)
                    Text(if (demorando) "Continua baixando… a rede está lenta." else "Baixando a imagem…", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { baixando = null }) { Text("Cancelar", color = Tema.Coral) }
                }
            }
        }
        Text("Acervo do Wikimedia Commons. O crédito do autor fica guardado com a foto.",
            color = Tema.Texto2, fontSize = 11.sp, modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
