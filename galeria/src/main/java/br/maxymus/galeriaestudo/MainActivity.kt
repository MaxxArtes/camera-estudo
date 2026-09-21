package br.maxymus.galeriaestudo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ponto de entrada: permissão de fotos, depois as duas abas (Fotos, Pessoas), a pessoa aberta e o visualizador. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Telemetria.iniciar(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Tema.Coral, secondary = Tema.Coral, background = Tema.Fundo, surface = Tema.Superficie,
                onBackground = Tema.Texto, onSurface = Tema.Texto, onPrimary = Color.White, surfaceVariant = Tema.Superficie, onSurfaceVariant = Tema.Texto2)) {
                App()
            }
        }
    }
}

enum class Acesso { Nenhum, Parcial, Total }

fun acessoAtual(ctx: Context): Acesso {
    fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 -> when {
            ok(Manifest.permission.READ_MEDIA_IMAGES) || ok(Manifest.permission.READ_MEDIA_VIDEO) -> Acesso.Total
            ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Acesso.Parcial
            else -> Acesso.Nenhum
        }
        Build.VERSION.SDK_INT >= 33 -> if (ok(Manifest.permission.READ_MEDIA_IMAGES)) Acesso.Total else Acesso.Nenhum
        else -> if (ok(Manifest.permission.READ_EXTERNAL_STORAGE)) Acesso.Total else Acesso.Nenhum
    }
}

fun permissoesDeFotos(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** O que está aberto em tela cheia: a coleção de origem (todas ou de uma pessoa) e a posição. */
class Visualizacao(val lista: List<Midia>, val indice: Int, val pessoa: Long?, val albumManual: Long? = null)

@Composable
private fun App() {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    var acesso by remember { mutableStateOf(acessoAtual(ctx)) }
    var pediu by remember { mutableStateOf(false) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { acesso = acessoAtual(ctx); pediu = true }
    var midias by remember { mutableStateOf<List<Midia>>(emptyList()) }
    var versaoMidias by remember { mutableIntStateOf(0) }
    var versaoPessoas by remember { mutableIntStateOf(0) }
    var aba by rememberSaveable { mutableIntStateOf(0) }
    var pessoaAberta by remember { mutableStateOf<Long?>(null) }
    var visual by remember { mutableStateOf<Visualizacao?>(null) }
    var albumAberto by remember { mutableStateOf<Long?>(null) }
    var seletorAlbum by remember { mutableStateOf<Long?>(null) }
    var versaoAlbuns by remember { mutableIntStateOf(0) }
    var lixeiraAberta by remember { mutableStateOf(false) }
    var pastaAberta by remember { mutableStateOf<String?>(null) }
    var editando by remember { mutableStateOf<Midia?>(null) }
    var figurinhaDe by remember { mutableStateOf<Midia?>(null) }
    var figurinhasAbertas by remember { mutableStateOf(false) }
    val estadoFotos = rememberLazyListState()
    val estadoPessoas = rememberLazyGridState()

    // ao voltar ao app: reconfere a permissão (pode ter mudado nas configurações) e recarrega o acervo
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { acesso = acessoAtual(ctx); versaoMidias++ } }
        dono.lifecycle.addObserver(obs)
        onDispose { dono.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(acesso, versaoMidias) {
        if (acesso != Acesso.Nenhum) {
            midias = withContext(Dispatchers.IO) { Midias.listar(ctx) }
            Indexador.iniciar(ctx)
        }
    }

    if (acesso == Acesso.Nenhum) { BoasVindas(pediu, aoPedir = { pedir.launch(permissoesDeFotos()) }); return }

    val v = visual
    val p = pessoaAberta
    val alb = albumAberto; val sel = seletorAlbum; val pasta = pastaAberta; val ed = editando; val fig = figurinhaDe
    BackHandler(enabled = ed == null && fig == null && !figurinhasAbertas && (v != null || sel != null || alb != null || lixeiraAberta || pasta != null || p != null)) { when { v != null -> visual = null; sel != null -> seletorAlbum = null; alb != null -> albumAberto = null; lixeiraAberta -> lixeiraAberta = false; pasta != null -> pastaAberta = null; else -> pessoaAberta = null } }
    val porId = remember(midias) { midias.associateBy { it.id } }

    when {
        fig != null -> TelaFigurinha(midia = fig, fechar = { figurinhaDe = null }, aoSalva = { figurinhaDe = null; figurinhasAbertas = true })
        figurinhasAbertas -> TelaFigurinhas(aoFechar = { figurinhasAbertas = false },
            aoCriar = { figurinhasAbertas = false; aba = 0 })
        ed != null -> TelaEditor(midia = ed, fechar = { editando = null }, aoSalvo = { copia ->
            editando = null; versaoMidias++; versaoAlbuns++
            visual = Visualizacao(listOf(copia), 0, null)
        })
        v != null -> Visualizador(lista = v.lista, inicial = v.indice, pessoa = v.pessoa, albumManual = v.albumManual, fechar = { visual = null },
            aoExcluida = { m -> midias = midias.filter { it.id != m.id }; visual = null; versaoPessoas++; versaoAlbuns++ },
            aoNaoEEsta = { m ->
                val pid = v.pessoa
                if (pid != null) escopo.launch {
                    withContext(Dispatchers.IO) { Indice.get(ctx).naoEEstaPessoa(m.id, pid) }
                    Telemetria.evento("nao_e_esta_pessoa"); versaoPessoas++; visual = null
                }
            },
            aoRemovidoDoAlbum = { visual = null; versaoAlbuns++ },
            aoEditar = { editando = it }, aoFigurinha = { visual = null; figurinhaDe = it })
        sel != null -> SeletorFotos(album = sel, midias = midias, aoFechar = { seletorAlbum = null }, aoConcluido = { seletorAlbum = null; versaoAlbuns++ })
        alb != null -> TelaAlbum(id = alb, porId = porId, versao = versaoAlbuns, voltar = { albumAberto = null },
            aoAdicionar = { seletorAlbum = alb }, aoAbrir = { lista, i -> visual = Visualizacao(lista, i, null, alb) },
            aoMudou = { versaoAlbuns++ }, aoSumiu = { albumAberto = null; versaoAlbuns++ })
        pasta != null -> TelaPasta(titulo = pasta, midias = remember(midias, pasta) { Midias.dePasta(midias, pasta) }, voltar = { pastaAberta = null },
            aoAbrir = { lista, i -> visual = Visualizacao(lista, i, null) })
        lixeiraAberta -> TelaLixeira(parcial = acesso == Acesso.Parcial, voltar = { lixeiraAberta = false }, aoMudou = { versaoMidias++; versaoPessoas++; versaoAlbuns++ })
        p != null -> TelaPessoa(id = p, porId = porId, versao = versaoPessoas, voltar = { pessoaAberta = null },
            aoAbrir = { lista, i -> visual = Visualizacao(lista, i, p) }, aoMudou = { versaoPessoas++ }, aoSumiu = { pessoaAberta = null; versaoPessoas++ })
        else -> {
            val cores = NavigationBarItemDefaults.colors(selectedIconColor = Tema.Coral, selectedTextColor = Tema.Coral, indicatorColor = Tema.Superficie, unselectedIconColor = Tema.Texto2, unselectedTextColor = Tema.Texto2)
            Scaffold(containerColor = Tema.Fundo, bottomBar = {
                NavigationBar(containerColor = Tema.Fundo, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = aba == 0, onClick = { aba = 0 }, icon = { Icon(Icons.Filled.Photo, contentDescription = null) }, label = { Text("Fotos") }, colors = cores)
                    NavigationBarItem(selected = aba == 1, onClick = { aba = 1; Telemetria.evento("aba_pessoas") }, icon = { Icon(Icons.Filled.Face, contentDescription = null) }, label = { Text("Álbuns") }, colors = cores)
                }
            }) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    if (aba == 0) TelaFotos(midias, estadoFotos, acesso == Acesso.Parcial, aoAbrir = { lista, i -> visual = Visualizacao(lista, i, null) }, aoAbrirAlbum = { pessoaAberta = it }, aoAbrirAlbumManual = { albumAberto = it }, aoAbrirLixeira = { lixeiraAberta = true }, aoAlterarSelecao = { pedir.launch(permissoesDeFotos()) })
                    else TelaPessoas(estadoPessoas, versaoPessoas + versaoAlbuns, acesso == Acesso.Parcial, porId = porId, aoAbrirPessoa = { pessoaAberta = it }, aoAbrirAlbumManual = { albumAberto = it }, aoAbrirSeletor = { seletorAlbum = it }, aoAbrirLixeira = { lixeiraAberta = true }, aoAbrirFigurinhas = { figurinhasAbertas = true }, aoAbrirPasta = { pastaAberta = it; Telemetria.evento("abriu_pasta", mapOf("pasta" to it)) }, aoMudou = { versaoPessoas++; versaoAlbuns++ })
                }
            }
        }
    }
}

@Composable
private fun BoasVindas(jaPediu: Boolean, aoPedir: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Tema.Fundo).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Suas fotos, no seu aparelho", color = Tema.Texto, fontSize = 24.sp, textAlign = TextAlign.Center)
        Text("Veja suas fotos por data. Em Álbuns, cada pessoa vira um álbum, organizado no aparelho sem enviar imagens para servidores.", color = Tema.Texto2, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))
        Button(onClick = aoPedir, colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White)) { Text("Permitir acesso às fotos") }
        if (jaPediu) TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Abrir configurações", color = Tema.Coral) }
    }
}
