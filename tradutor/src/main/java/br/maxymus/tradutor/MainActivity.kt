package br.maxymus.tradutor

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Fundo = Color(0xFF111114)
private val Superficie = Color(0xFF252529)
private val Coral = Color(0xFFFF575F)
private val Texto = Color(0xFFF5F5F5)
private val Texto2 = Color(0xFFA3A3AA)

/**
 * Preparação (desenho do Astra, 22/09): uma tela só, cada permissão pedida por ação do dono, e a explicação do que
 * a captura alcança dita sem enfeite. Nada de "é totalmente seguro".
 */
class MainActivity : ComponentActivity() {
    private var pedidoCaptura = -1L
    private val consentimento = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        val pedido = pedidoCaptura
        pedidoCaptura = -1
        val dados = resultado.data
        if (!EstadoCaptura.aguardando(pedido)) return@registerForActivityResult
        if (resultado.resultCode != RESULT_OK || dados == null) {
            EstadoCaptura.negar(this, pedido, "Captura não autorizada. O leitor continua pronto.")
        } else {
            lifecycleScope.launch {
                var enviado = false
                try {
                    lifecycle.withResumed {
                        if (EstadoCaptura.aguardando(pedido)) {
                            if (EstadoCaptura.acessibilidadeLigada(this@MainActivity)) {
                                EstadoCaptura.negar(this@MainActivity, pedido, "A acessibilidade está ligada e tem prioridade.")
                            } else runCatching {
                                startForegroundService(Intent(this@MainActivity, ServicoProjecao::class.java)
                                    .setAction(ServicoProjecao.INICIAR).putExtra("pedido", pedido)
                                    .putExtra("resultado", resultado.resultCode).putExtra("consentimento", dados))
                                enviado = true
                            }.onFailure {
                                EstadoCaptura.negar(this@MainActivity, pedido, "Não consegui iniciar a sessão. Tente novamente.")
                            }
                        }
                    }
                } finally {
                    if (!enviado) EstadoCaptura.negar(this@MainActivity, pedido, "Início interrompido. Peça uma nova sessão.")
                }
            }
        }
    }

    fun iniciarCaptura() {
        if (EstadoCaptura.acessibilidadeLigada(this)) {
            EstadoCaptura.avisar("Desligue a acessibilidade avançada para usar a sessão manual.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            EstadoCaptura.avisar("Autorize a sobreposição antes de iniciar a sessão.")
            return
        }
        val pedido = EstadoCaptura.pedir(this) ?: return
        pedidoCaptura = pedido
        runCatching {
            val gestor = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34)
                gestor.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else gestor.createScreenCaptureIntent()
            consentimento.launch(intent)
        }.onFailure { EstadoCaptura.negar(this, pedido, "Não consegui abrir o consentimento de captura.") }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("pedido_captura", pedidoCaptura)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        EstadoCaptura.iniciar(this)
        pedidoCaptura = estado?.getLong("pedido_captura", -1) ?: -1
        Telemetria.iniciar(this)
        Traducao.carregarPreferencias(this)
        Traducao.iniciarCacheDisco(this)
        // sem esta permissão a notificação fixa simplesmente não aparece no Android 13 ou mais novo
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Coral, background = Fundo, surface = Superficie, onSurface = Texto)) {
                Preparacao()
            }
        }
    }
}

@Composable
private fun Preparacao() {
    val ctx = LocalContext.current
    var servicoLigado by remember { mutableStateOf(false) }   // rodando AGORA
    var servicoListado by remember { mutableStateOf(false) }  // ligado nas configuracoes do Android
    var jaLigou by remember { mutableStateOf(false) }         // a captura já rodou alguma vez neste aparelho
    var paradaOrdenada by remember { mutableStateOf(false) }  // o serviço saiu pelo onDestroy: o dono desligou, não foi o sistema
    var tentouAtivar by remember { mutableStateOf(false) }
    var nova by remember { mutableStateOf<Atualizador.Versao?>(null) }
    // reconfere A CADA VOLTA à tela: antes a busca acontecia uma vez só, quando a tela nascia, então quem
    // deixasse o app aberto nunca mais via versão nova. Foi o que aconteceu com o dono em 22/09.
    var recarrega by remember { mutableStateOf(0) }
    var procurando by remember { mutableStateOf(false) }
    LaunchedEffect(recarrega) {
        procurando = true
        val v = Atualizador.consultar(); val inst = Atualizador.versaoInstalada(ctx)
        nova = if (v != null && v.codigo > inst.second) v else null
        procurando = false
    }
    val escopo = androidx.compose.runtime.rememberCoroutineScope()

    var bolhaNaTela by remember { mutableStateOf(false) }

    fun confere() {
        val nome = android.content.ComponentName(ctx, ServicoTradutor::class.java)
        val lista = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        // Estas DUAS perguntas não são a mesma, e tratá-las como uma foi o defeito: com um "ou" entre elas o app
        // dizia "Ativada" para serviço listado mas morto, e todo botão falava com ninguém, em silêncio.
        // Acontece de verdade: instalar atualização mata o processo, e o HyperOS nem sempre religa o serviço.
        servicoListado = lista.split(':').any { android.content.ComponentName.unflattenFromString(it) == nome }
        servicoLigado = ServicoTradutor.ativo != null
        jaLigou = ctx.getSharedPreferences("tradutor", android.content.Context.MODE_PRIVATE)
            .getBoolean(ServicoTradutor.MARCA_JA_LIGOU, false) || servicoLigado
        paradaOrdenada = !servicoLigado && !servicoListado &&
            ctx.getSharedPreferences("tradutor", android.content.Context.MODE_PRIVATE).getBoolean(ServicoTradutor.MARCA_PARADA_ORDENADA, false)
        bolhaNaTela = ServicoTradutor.bolhaNaTela
        if (servicoLigado) {
            // a notificação fixa pode ser dispensada com um arrasto no Android 14 e acima, e não voltava sozinha
            ServicoTradutor.ativo?.let { runCatching { it.reafirmaNotificacao() } }
        } else if (servicoListado) {
            Telemetria.evento("servico_listado_morto", mapOf("android" to android.os.Build.VERSION.SDK_INT))
        } else if (jaLigou && !paradaOrdenada) {
            // o HyperOS tirou o serviço da lista depois de encerrar o app: antes a tela voltava a parecer a de primeira vez
            Telemetria.evento("servico_desligado_pelo_sistema", mapOf("android" to android.os.Build.VERSION.SDK_INT))
        }
    }
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { confere(); recarrega++ } }
        dono.lifecycle.addObserver(obs); onDispose { dono.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { confere() }

    Column(Modifier.fillMaxSize().background(Fundo).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Tradutor de tela", color = Texto, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        nova?.let { v ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Versão ${v.nome} disponível", color = Texto, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (v.mudou.isNotEmpty()) Text(v.mudou.first().take(180), color = Texto2, fontSize = 13.sp)
                Button(onClick = { Atualizador.baixarEInstalar(ctx, v); nova = null },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo)) { Text("Atualizar", fontSize = 15.sp) }
            }
        }
        Text("Use o leitor de capítulo para traduzir e ouvir sem acessibilidade. Para outros apps, inicie uma sessão de captura manual.",
            color = Texto2, fontSize = 14.sp)

        CartaoLeitor()
        CartaoSessao(servicoListado || servicoLigado, recarrega)

        Cartao("Acessibilidade (avançado)", if (servicoLigado || servicoListado) "Configurar acessibilidade" else "Ativar opção avançada",
            (if (servicoLigado) "Ativa. " else if (servicoListado) "Ligada nas configurações, mas sem serviço conectado. " else "Desligada. ") +
            "Opcional: oferece modo contínuo, preparo durante a rolagem e gestos. " +
            "Quando ligada, tem prioridade e encerra a sessão manual. O leitor funciona sem esta opção." +
            (if (Build.VERSION.SDK_INT < 30) " A captura por acessibilidade exige Android 11 ou superior; use a sessão manual." else ""),
            pronto = false) {
            tentouAtivar = true
            abrirAcessibilidade(ctx)
        }

        // O Android bloqueia acessibilidade para app instalado fora da loja ("configurações restritas"). O caminho
        // para liberar NÃO fica na tela de acessibilidade, então o app diz onde é em vez de deixar o dono procurando.
        if (tentouAtivar && !servicoListado && !jaLigou) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A2026)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Apareceu \"Controlada pelas configurações restritas\"?", color = Texto, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("É uma proteção do Android para app que não veio da loja, não é erro deste app. Para liberar:\n\n" +
                 "1. Configurações, Apps, Gerenciar apps, e abra o Tradutor.\n" +
                 "2. Toque nos três pontos, no canto superior direito.\n" +
                 "3. Escolha Permitir configurações restritas.\n" +
                 "4. Volte para Acessibilidade e ligue o Tradutor de tela.",
                 color = Texto2, fontSize = 13.sp)
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.packageName)))
            }, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo)) {
                Text("Abrir as informações do app", fontSize = 15.sp)
            }
        }

        EscolhaIdiomas()
        ListaIdiomas()

        if (paradaOrdenada) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Acessibilidade avançada desligada", color = Texto, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("O leitor e a sessão manual continuam disponíveis.", color = Texto2, fontSize = 13.sp)
        } else if (!servicoLigado && servicoListado) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A2026)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A acessibilidade consta ligada, mas não está rodando",
                color = Texto, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            // Medido em 01/10: o dono fechou o app pelos recentes e tentou religar 4 vezes em 30 s, sem efeito.
            // No HyperOS, fechar pelos recentes equivale a forçar a parada, e com a inicialização automática
            // desligada o sistema RECUSA religar o serviço — desligar e ligar na Acessibilidade sozinho não basta.
            Text("Para usar a sessão manual, desligue esta opção nas configurações. Para recuperar a acessibilidade, " +
                 "Faça os três passos, na ordem. Os dois primeiros são uma vez só.",
                 color = Texto2, fontSize = 13.sp)
            Passo("1. Ligue a Inicialização automática do Tradutor") {
                Telemetria.evento("ajuda_reativar", mapOf("passo" to "autostart"))
                val miui = Intent().setComponent(android.content.ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                runCatching { ctx.startActivity(miui) }.onFailure {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + ctx.packageName)))
                }
            }
            Passo("2. Bateria do Tradutor: Sem restrições") {
                Telemetria.evento("ajuda_reativar", mapOf("passo" to "bateria"))
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + ctx.packageName)))
            }
            Passo(if (servicoListado) "3. Desligue e ligue o Tradutor em Acessibilidade" else "3. Ligue o Tradutor em Acessibilidade") {
                Telemetria.evento("ajuda_reativar", mapOf("passo" to "acessibilidade"))
                abrirAcessibilidade(ctx)
            }
            Text("Para não acontecer de novo, o Tradutor não aparece mais nos recentes: assim ele não é fechado por engano " +
                 "ao limpar a lista. Para voltar a ele, use o ícone.", color = Texto2, fontSize = 12.sp)
        }

        // Controle exclusivo da bolha avançada; a sessão manual tem seus próprios estados acima.
        if (servicoLigado) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bolha da acessibilidade", color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(if (bolhaNaTela) "A bolha está na tela. Toque nela para traduzir, arraste para mudar de lugar."
                 else "A bolha está escondida. Traga ela de volta para traduzir por cima do navegador.",
                 color = Texto2, fontSize = 13.sp)
            Button(onClick = {
                val s = ServicoTradutor.ativo
                if (s == null) { servicoLigado = false; servicoListado = true }
                else bolhaNaTela = s.alternarBolha()
            }, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo)) {
                Text(if (bolhaNaTela) "Esconder bolha" else "Mostrar bolha", fontSize = 15.sp)
            }
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Versão instalada " + Atualizador.versaoInstalada(ctx).first, color = Texto2, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { recarrega++ }, enabled = !procurando) {
                Text(if (procurando) "Procurando…" else if (nova == null) "Procurar atualização" else "Atualização disponível",
                    color = if (nova == null) Texto2 else Coral, fontSize = 12.sp)
            }
        }
        Text("Traduz a tela parada, uma tela por vez. Não acompanha a rolagem, e onomatopeia desenhada não é traduzida.",
            color = Texto2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CartaoSessao(acessibilidade: Boolean, revisaoPermissao: Int) {
    val ctx = LocalContext.current
    val estado by EstadoCaptura.estado.collectAsState()
    val mensagem by EstadoCaptura.mensagem.collectAsState()
    val sobreposicao = remember(revisaoPermissao, estado) { Settings.canDrawOverlays(ctx) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sessão de captura manual", color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(when (estado) {
            EstadoSessao.OCIOSA -> "Ociosa"
            EstadoSessao.PEDINDO -> "Aguardando autorização e início da sessão"
            EstadoSessao.ATIVA -> "Ativa · toque na bolha para capturar, traduzir e ouvir"
            EstadoSessao.PARADA_PELO_SISTEMA -> "Interrompida · inicie novamente para dar um novo consentimento"
        }, color = Coral, fontSize = 14.sp)
        Text("A sessão compartilha a tela inteira com o Tradutor. Só um toque na bolha processa uma imagem; " +
            "o resultado fica congelado e não acompanha a rolagem. As imagens não são gravadas. " +
            "Com internet, o texto reconhecido é enviado para tradução. Encerre a sessão ao terminar.",
            color = Texto2, fontSize = 13.sp)
        if (mensagem.isNotBlank()) Text(mensagem, color = Texto2, fontSize = 13.sp)
        if (acessibilidade) Text("A acessibilidade avançada tem prioridade. Desligue-a nas configurações para usar este modo.",
            color = Texto2, fontSize = 13.sp)
        if (!sobreposicao) Button(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName))) }.onFailure {
                EstadoCaptura.avisar("Abra as configurações do app e autorize aparecer sobre outros aplicativos.")
            }
        }) { Text("Autorizar sobreposição") }
        if (estado == EstadoSessao.ATIVA || estado == EstadoSessao.PEDINDO) {
            Button(onClick = {
                val servico = ServicoProjecao.ativo
                if (servico != null) servico.encerrar(false, "Sessão encerrada.")
                else {
                    EstadoCaptura.encerrar(ctx, false, "Pedido de captura cancelado.")
                    ctx.stopService(Intent(ctx, ServicoProjecao::class.java))
                }
            }) { Text(if (estado == EstadoSessao.ATIVA) "Encerrar sessão" else "Cancelar pedido") }
        } else {
            Button(onClick = { (ctx as MainActivity).iniciarCaptura() }, enabled = sobreposicao && !acessibilidade) {
                Text(if (estado == EstadoSessao.PARADA_PELO_SISTEMA) "Iniciar nova sessão" else "Iniciar sessão")
            }
        }
    }
}

/**
 * Abre a Acessibilidade já no Tradutor quando o sistema deixa: a tela de detalhes do serviço costuma exigir permissão de
 * sistema, então cai para a lista com o item destacado (aceito pelas configurações do Android puro e por várias variantes)
 * e, por fim, para a lista simples.
 */
private fun abrirAcessibilidade(ctx: android.content.Context) {
    val componente = android.content.ComponentName(ctx, ServicoTradutor::class.java).flattenToString()
    val detalhes = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, componente)
    if (runCatching { ctx.startActivity(detalhes) }.isSuccess) return
    val destacada = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", componente)
        .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", componente) })
    if (runCatching { ctx.startActivity(destacada) }.isSuccess) return
    runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

/** De qual idioma e para qual idioma. Automático detecta sozinho, que é o que serve para quadrinho. */
@Composable
private fun EscolhaIdiomas() {
    val ctx = LocalContext.current
    var de by remember { mutableStateOf(Traducao.origemFixa) }
    var para by remember { mutableStateOf(Traducao.destino) }
    var abrindo by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Idiomas", color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Traduzir de", color = Texto2, fontSize = 14.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { abrindo = if (abrindo == "de") null else "de" }) {
                Text(if (de.isBlank()) "Automático" else Idiomas.nome(de), color = Coral, fontSize = 14.sp)
            }
        }
        if (abrindo == "de") Column {
            (listOf("" to "Automático (detecta sozinho)") + Idiomas.LISTA.map { it.first to Idiomas.nome(it.first) }).forEach { (tag, nome) ->
                Text(nome, color = if (tag == de) Coral else Texto, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().height(44.dp).clickable {
                        de = tag; Traducao.origemFixa = tag; Traducao.guardarPreferencias(ctx); abrindo = null
                    }.padding(top = 12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Traduzir para", color = Texto2, fontSize = 14.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { abrindo = if (abrindo == "para") null else "para" }) {
                Text(Idiomas.nome(para), color = Coral, fontSize = 14.sp)
            }
        }
        if (abrindo == "para") Column {
            Idiomas.LISTA.forEach { (tag, _) ->
                Text(Idiomas.nome(tag), color = if (tag == para) Coral else Texto, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().height(44.dp).clickable {
                        para = tag; Traducao.destino = tag; Traducao.guardarPreferencias(ctx); abrindo = null
                    }.padding(top = 12.dp))
            }
        }
        Text("Automático serve para quadrinho: ele descobre o idioma de cada tela. Fixe só se ele errar.",
            color = Texto2, fontSize = 12.sp)
    }
}

/**
 * Idiomas guardados para uso sem internet. Com internet nada disso é preciso, e é o que o texto diz: isto existe
 * para o modo sem rede. Dá para APAGAR também, porque cada pacote ocupa espaço real no aparelho.
 */
@Composable
private fun ListaIdiomas() {
    val escopo = androidx.compose.runtime.rememberCoroutineScope()
    var baixados by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ocupado by remember { mutableStateOf<String?>(null) }
    var versao by remember { mutableStateOf(0) }
    LaunchedEffect(versao) { baixados = withContext(Dispatchers.IO) { Idiomas.baixados() } }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Idiomas sem internet", color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("Com internet o app entende qualquer idioma e não precisa de nada baixado. Esta lista é para quando " +
             "você estiver sem rede. Cada pacote ocupa espaço no aparelho, então baixe só o que usa. " +
             "O português precisa estar baixado para qualquer par funcionar.",
             color = Texto2, fontSize = 13.sp)
        Idiomas.LISTA.forEach { (tag, nome) ->
            val tem = tag in baixados
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(nome, color = if (tem) Texto else Texto2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                when {
                    ocupado == tag -> Text("…", color = Coral, fontSize = 14.sp)
                    tem -> androidx.compose.material3.TextButton(onClick = {
                        ocupado = tag
                        escopo.launch { withContext(Dispatchers.IO) { Idiomas.apagar(tag) }; ocupado = null; versao++ }
                    }) { Text("Apagar", color = Texto2, fontSize = 13.sp) }
                    else -> androidx.compose.material3.TextButton(onClick = {
                        ocupado = tag
                        escopo.launch { withContext(Dispatchers.IO) { Idiomas.baixar(tag) }; ocupado = null; versao++ }
                    }) { Text("Baixar", color = Coral, fontSize = 13.sp) }
                }
            }
        }
    }
}

/**
 * Leitor de capítulo. Caminho que não depende de nenhuma permissão: o app baixa a página, acha os quadros,
 * baixa as imagens originais e traduz quadro a quadro. O endereço entra por ação do dono — a área de
 * transferência NÃO é lida sozinha, "Colar" é um toque dele (desenho do Astra, 25/09).
 */
@Composable
private fun CartaoLeitor() {
    val ctx = LocalContext.current
    val prancheta = androidx.compose.ui.platform.LocalClipboardManager.current
    var endereco by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Leitor de capítulo · sempre pronto", color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("Cole o endereço de um capítulo para ler com tradução. O app baixa os quadros no seu aparelho e " +
             "troca o texto pelo traduzido. Quando há internet, o texto das falas é enviado para tradução; " +
             "sem internet, a tradução acontece aqui mesmo, com o idioma baixado.",
             color = Texto2, fontSize = 13.sp)
        androidx.compose.material3.OutlinedTextField(
            value = endereco, onValueChange = { endereco = it }, singleLine = true,
            label = { Text("Endereço do capítulo", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Texto, unfocusedTextColor = Texto,
                focusedBorderColor = Coral, unfocusedBorderColor = Color(0xFF3A3A42),
                focusedLabelColor = Coral, unfocusedLabelColor = Texto2, cursorColor = Coral))
        val ultimo = remember { ctx.getSharedPreferences("leitor", android.content.Context.MODE_PRIVATE).getString("ultimo", null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.TextButton(onClick = {
                prancheta.getText()?.text?.let { t -> Regex("https?://\\S+").find(t)?.let { endereco = it.value } }
            }) { Text("Colar", color = Coral, fontSize = 14.sp) }
            if (ultimo != null) androidx.compose.material3.TextButton(onClick = { endereco = ultimo }) {
                Text("Último capítulo", color = Coral, fontSize = 14.sp)
            }
        }
        Button(onClick = {
            ctx.startActivity(Intent(ctx, LeitorActivity::class.java).putExtra("endereco", endereco.trim()))
        }, enabled = Capitulo.ehEndereco(endereco),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo,
                disabledContainerColor = Color(0xFF2F2F35), disabledContentColor = Texto2)) {
            Text("Abrir capítulo", fontSize = 15.sp)
        }
    }
}

@Composable
private fun Passo(texto: String, aoTocar: () -> Unit) {
    Button(onClick = aoTocar, modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo)) {
        Text(texto, fontSize = 14.sp)
    }
}

@Composable
private fun Cartao(titulo: String, acao: String, explicacao: String, pronto: Boolean, aoTocar: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(explicacao, color = Texto2, fontSize = 13.sp)
        Button(onClick = aoTocar, enabled = !pronto, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo,
                disabledContainerColor = Color(0xFF2F2F35), disabledContentColor = Texto2)) {
            Text(acao, fontSize = 15.sp)
        }
    }
}
