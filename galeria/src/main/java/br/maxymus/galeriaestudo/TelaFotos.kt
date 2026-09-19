package br.maxymus.galeriaestudo

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TelaFotos(midias: List<Midia>, estado: LazyListState, parcial: Boolean, aoAbrir: (List<Midia>, Int) -> Unit, aoAbrirAlbum: (Long) -> Unit, aoAbrirAlbumManual: (Long) -> Unit, aoAbrirLixeira: () -> Unit, aoAlterarSelecao: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var sobre by remember { mutableStateOf(false) }
    var novaVersao by remember { mutableStateOf<Atualizador.Versao?>(null) }
    var consulta by remember { mutableStateOf("") }
    var focado by remember { mutableStateOf(false) }
    var filtroData by remember { mutableStateOf<Pair<Int?, Int?>?>(null) }
    var folhaData by remember { mutableStateOf(false) }
    var albunsNome by remember { mutableStateOf<List<Indice.Resumo>>(emptyList()) }
    // seleção múltipla
    var selecionando by remember { mutableStateOf(false) }
    var selecionadas by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var menuSel by remember { mutableStateOf(false) }
    var folhaAlbuns by remember { mutableStateOf(false) }
    var criarAlbum by remember { mutableStateOf(false) }
    var albunsManuais by remember { mutableStateOf<List<Indice.AlbumManual>>(emptyList()) }

    LaunchedEffect(Unit) {
        val v = Atualizador.consultar(); val inst = Atualizador.versaoInstalada(ctx)
        if (v != null && v.codigo > inst.second) novaVersao = v
    }
    LaunchedEffect(Unit) { albunsNome = withContext(Dispatchers.IO) { val db = Indice.get(ctx); (db.pessoas(false) + db.pessoas(true)).filter { it.nome != null } } }

    val texto = consulta.trim()
    val dataBusca = remember(texto) { Midias.casaData(texto) }
    val periodo = dataBusca ?: filtroData
    val midiasPeriodo = remember(midias, periodo) { if (periodo == null) midias else Midias.filtraData(midias, periodo.first, periodo.second) }
    val albunsCasam = remember(texto, albunsNome) { if (texto.isEmpty()) emptyList() else albunsNome.filter { it.nome != null && Midias.semAcento(it.nome!!).contains(Midias.semAcento(texto)) } }
    LaunchedEffect(periodo) { runCatching { estado.scrollToItem(0) }; if (selecionando) { selecionando = false; selecionadas = emptySet() } }

    fun sair() { selecionando = false; selecionadas = emptySet() }
    fun urisSel(): ArrayList<android.net.Uri> = ArrayList(midiasPeriodo.filter { it.id in selecionadas }.map { it.uri })
    fun idsQuando(): List<Pair<Long, Long>> = midiasPeriodo.filter { it.id in selecionadas }.map { it.id to it.quando }
    val excluirVarias = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { Telemetria.evento("lixeira_varias", mapOf("n" to selecionadas.size)); Toast.makeText(ctx, "Movidas para a lixeira", Toast.LENGTH_SHORT).show(); sair() }
    }
    fun compartilharSel() {
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, urisSel()); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        runCatching { ctx.startActivity(Intent.createChooser(i, "Compartilhar")) }
    }
    fun excluirSel() {
        val uris = urisSel(); if (uris.isEmpty()) return
        val pi = Midias.pedidoLixeira(ctx, uris, true)
        if (pi != null) excluirVarias.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        else { uris.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }; Toast.makeText(ctx, "Excluídas", Toast.LENGTH_SHORT).show(); sair() }
    }
    fun adicionarSelAoAlbum(album: Long) {
        val alvo = idsQuando()
        escopo.launch { val n = withContext(Dispatchers.IO) { Indice.get(ctx).adicionarAoAlbum(album, alvo) }; Toast.makeText(ctx, "$n ${if (n == 1) "foto adicionada" else "fotos adicionadas"}", Toast.LENGTH_SHORT).show(); folhaAlbuns = false; sair() }
    }
    fun favoritarSel() {
        val alvo = midiasPeriodo.filter { it.id in selecionadas }
        alvo.forEach { if (!Favoritos.eh(ctx, it.uri)) Favoritos.alterna(ctx, it.uri) }
        Toast.makeText(ctx, "${alvo.size} ${if (alvo.size == 1) "foto favoritada" else "fotos favoritadas"}", Toast.LENGTH_SHORT).show(); sair()
    }
    BackHandler(enabled = selecionando) { sair() }

    Column(Modifier.fillMaxSize().background(Tema.Fundo)) {
        if (selecionando) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { sair() }) { Icon(Icons.Filled.Close, contentDescription = "Cancelar", tint = Tema.Texto) }
                Text("${selecionadas.size} selecionada(s)", color = Tema.Texto, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(enabled = selecionadas.isNotEmpty(), onClick = { excluirSel() }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Excluir", tint = if (selecionadas.isNotEmpty()) Tema.Texto else Tema.Texto2)
                }
                IconButton(onClick = { selecionadas = if (selecionadas.size == midiasPeriodo.size) emptySet() else midiasPeriodo.map { it.id }.toSet() }) {
                    Icon(Icons.Filled.SelectAll, contentDescription = "Selecionar tudo", tint = if (selecionadas.size == midiasPeriodo.size && midiasPeriodo.isNotEmpty()) Tema.Coral else Tema.Texto)
                }
            }
        } else {
            BarraSuperior("Fotos") {
                BotaoData(periodo, temX = dataBusca == null && filtroData != null, aoAbrir = { folhaData = true }, aoLimpar = { filtroData = null })
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = Tema.Texto) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Procurar atualização") }, onClick = {
                            menu = false
                            escopo.launch {
                                val v = Atualizador.consultar(); val inst = Atualizador.versaoInstalada(ctx)
                                when {
                                    v == null -> Toast.makeText(ctx, "Não consegui consultar. Está sem internet?", Toast.LENGTH_SHORT).show()
                                    v.codigo > inst.second -> novaVersao = v
                                    else -> Toast.makeText(ctx, "Você já está na versão mais recente (${inst.first}).", Toast.LENGTH_SHORT).show()
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text("Lixeira") }, onClick = { menu = false; aoAbrirLixeira() })
                        DropdownMenuItem(text = { Text("Sobre") }, onClick = { menu = false; sobre = true })
                    }
                }
            }
            CampoBusca(consulta, { consulta = it }, { focado = it })
            if (focado && texto.length < 2) Text("Nomes dos álbuns, setembro, 2025 ou 09/2025", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            novaVersao?.let { v -> FaixaAviso("Versão ${v.nome} disponível", "Atualizar") { Atualizador.baixarEInstalar(ctx, v); novaVersao = null } }
            if (parcial) FaixaAviso("Mostrando apenas os itens permitidos", "Alterar seleção", aoAlterarSelecao)
        }

        Box(Modifier.weight(1f).imePadding()) {
            when {
                texto.isEmpty() -> when {
                    periodo != null && midiasPeriodo.isEmpty() -> Vazio("Nenhuma foto nesse período.", "Limpar filtro de data") { filtroData = null }
                    midias.isEmpty() -> Vazio("Nenhuma foto encontrada.")
                    else -> GradePorDia(midiasPeriodo, estado, null, selecionando, selecionadas,
                        aoLongo = { i -> selecionando = true; selecionadas = setOf(midiasPeriodo[i].id) }) { i ->
                        if (selecionando) { val fid = midiasPeriodo[i].id; selecionadas = if (fid in selecionadas) selecionadas - fid else selecionadas + fid }
                        else aoAbrir(midiasPeriodo, i)
                    }
                }
                Midias.dataIncompleta(texto) -> Vazio("Complete a data, por exemplo 09/2025.")
                dataBusca == null && albunsCasam.isEmpty() -> Vazio("Nenhum álbum com esse nome.\n\nBusque um nome de álbum ou uma data. Objetos, lugares e textos nas fotos ainda não são pesquisáveis.")
                dataBusca == null -> LazyColumn(Modifier.fillMaxSize()) {
                    item { RotuloSecao("Álbuns") }
                    items(albunsCasam, key = { it.id }) { a -> LinhaAlbum(a) { aoAbrirAlbum(a.id) } }
                }
                midiasPeriodo.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    if (albunsCasam.isNotEmpty()) { RotuloSecao("Álbuns"); albunsCasam.forEach { a -> LinhaAlbum(a) { aoAbrirAlbum(a.id) } } }
                    Vazio("Nenhuma foto nesse período.")
                }
                else -> GradePorDia(midiasPeriodo, rememberLazyListState(), cabecalho = {
                    Column {
                        if (albunsCasam.isNotEmpty()) { RotuloSecao("Álbuns"); albunsCasam.forEach { a -> LinhaAlbum(a) { aoAbrirAlbum(a.id) } } }
                        RotuloSecao("Fotos · " + Midias.escopoData(dataBusca.first, dataBusca.second))
                    }
                }) { i -> aoAbrir(midiasPeriodo, i) }
            }
        }

        if (selecionando) Row(Modifier.fillMaxWidth().background(Color(0xF01A1A1F)).navigationBarsPadding().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val ativo = selecionadas.isNotEmpty()
            AcaoBarra(Icons.Filled.Share, "Compartilhar", ativo) { compartilharSel() }
            AcaoBarra(Icons.Filled.LibraryAdd, "Adicionar a álbum", ativo) { escopo.launch { albunsManuais = withContext(Dispatchers.IO) { Indice.get(ctx).listarAlbuns() }; folhaAlbuns = true } }
            AcaoBarra(Icons.Filled.CreateNewFolder, "Criar álbum", ativo) { criarAlbum = true }
            Box {
                AcaoBarra(Icons.Filled.MoreVert, "Mais", true) { menuSel = true }
                DropdownMenu(expanded = menuSel, onDismissRequest = { menuSel = false }) {
                    DropdownMenuItem(text = { Text("Favoritar") }, onClick = { menuSel = false; favoritarSel() })
                }
            }
        }
    }

    if (folhaData) FolhaData(midias, filtroData, aoFechar = { folhaData = false }) { novo ->
        filtroData = novo; if (dataBusca != null) consulta = ""; folhaData = false
    }
    if (folhaAlbuns) FolhaAlbuns(albunsManuais, jaContem = emptySet(), aoFechar = { folhaAlbuns = false },
        aoCriar = { folhaAlbuns = false; criarAlbum = true }, aoEscolher = { adicionarSelAoAlbum(it) })
    if (criarAlbum) DialogoCriarAlbum(nomesExistentes = albunsManuais.map { it.nome }, rotuloConfirmar = "Criar e adicionar", aoFechar = { criarAlbum = false }) { nome ->
        criarAlbum = false
        val alvo = idsQuando()
        escopo.launch {
            val novo = withContext(Dispatchers.IO) { val db = Indice.get(ctx); val a = db.criarAlbum(nome); db.adicionarAoAlbum(a, alvo); a }
            Toast.makeText(ctx, "Álbum \"$nome\" criado com ${alvo.size} ${if (alvo.size == 1) "foto" else "fotos"}", Toast.LENGTH_SHORT).show()
            sair(); aoAbrirAlbumManual(novo)
        }
    }
    if (sobre) AlertDialog(onDismissRequest = { sobre = false }, confirmButton = { TextButton(onClick = { sobre = false }) { Text("Fechar") } },
        title = { Text("Galeria Estudo ${Atualizador.versaoInstalada(ctx).first}") },
        text = { Text("Fotos por data e pessoas por rosto, tudo no aparelho. Nenhuma imagem sai do celular.\n\nRostos: ML Kit (detecção) e MobileFaceNet (identidade).") })
}

@Composable
private fun CampoBusca(valor: String, aoMudar: (String) -> Unit, aoFoco: (Boolean) -> Unit) {
    TextField(
        value = valor, onValueChange = aoMudar, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).onFocusChanged { aoFoco(it.isFocused) },
        placeholder = { Text("Buscar pessoas ou datas", color = Tema.Texto2) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Tema.Texto2) },
        trailingIcon = { if (valor.isNotEmpty()) IconButton(onClick = { aoMudar("") }) { Icon(Icons.Filled.Close, contentDescription = "Limpar", tint = Tema.Texto2) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Tema.Superficie, unfocusedContainerColor = Tema.Superficie,
            focusedTextColor = Tema.Texto, unfocusedTextColor = Tema.Texto, cursorColor = Tema.Coral,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent
        )
    )
}

@Composable
private fun BotaoData(periodo: Pair<Int?, Int?>?, temX: Boolean, aoAbrir: () -> Unit, aoLimpar: () -> Unit) {
    val ativo = periodo != null
    val forma = RoundedCornerShape(20.dp)
    Row(
        Modifier.padding(end = 4.dp).height(40.dp).clip(forma).background(if (ativo) Color.Transparent else Tema.Superficie)
            .then(if (ativo) Modifier.border(1.dp, Tema.Coral, forma) else Modifier)
            .clickable(onClick = aoAbrir).padding(start = 12.dp, end = if (ativo && temX) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = "Filtrar por data", tint = if (ativo) Tema.Coral else Tema.Texto, modifier = Modifier.height(18.dp))
        Text(if (ativo) Midias.rotuloPeriodo(periodo!!.first, periodo.second) else "Data", color = if (ativo) Tema.Coral else Tema.Texto, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
        if (ativo && temX) IconButton(onClick = aoLimpar, modifier = Modifier.height(36.dp)) { Icon(Icons.Filled.Close, contentDescription = "Limpar filtro", tint = Tema.Coral, modifier = Modifier.height(18.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FolhaData(midias: List<Midia>, atual: Pair<Int?, Int?>?, aoFechar: () -> Unit, aoAplicar: (Pair<Int?, Int?>?) -> Unit) {
    val anos = remember(midias) { Midias.anosPresentes(midias) }
    var ano by remember { mutableStateOf(atual?.first) }
    var mes by remember { mutableStateOf(atual?.second) }
    ModalBottomSheet(onDismissRequest = aoFechar, containerColor = Tema.Superficie, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Filtrar por data", color = Tema.Texto, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
            Text("Ano", color = Tema.Texto2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Todos os anos", ano == null) { ano = null }
                anos.forEach { a -> Chip(a.toString(), ano == a) { ano = a } }
            }
            Text("Mês", color = Tema.Texto2, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Todos os meses", mes == null) { mes = null }
                (1..12).forEach { m -> Chip(Midias.mesAbrev(m), mes == m) { mes = m } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { aoAplicar(null) }) { Text("Limpar filtro", color = Tema.Texto2) }
                TextButton(onClick = { aoAplicar(if (ano == null && mes == null) null else ano to mes) }) { Text("Aplicar", color = Tema.Coral) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chip(rotulo: String, marcado: Boolean, aoTocar: () -> Unit) {
    FilterChip(selected = marcado, onClick = aoTocar, label = { Text(rotulo) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Tema.Coral, selectedLabelColor = Color.White, containerColor = Tema.Fundo, labelColor = Tema.Texto))
}

@Composable
private fun RotuloSecao(texto: String) {
    Text(texto, color = Tema.Texto, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().background(Tema.Fundo).padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun LinhaAlbum(a: Indice.Resumo, aoTocar: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = aoTocar).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Capa(a.id, 48.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(a.nome ?: "Sem nome", color = Tema.Texto, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Pessoa · ${Midias.numero(a.fotos)} ${if (a.fotos == 1) "foto" else "fotos"}", color = Tema.Texto2, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Vazio(texto: String, acao: String? = null, aoTocar: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(texto, color = Tema.Texto2, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (acao != null) TextButton(onClick = aoTocar, modifier = Modifier.padding(top = 8.dp)) { Text(acao, color = Tema.Coral) }
    }
}
