package br.maxymus.tradutor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        Telemetria.iniciar(this)
        Traducao.carregarPreferencias(this)
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
    var servicoLigado by remember { mutableStateOf(false) }
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
        val nome = ctx.packageName + "/" + ServicoTradutor::class.java.name
        val lista = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        servicoLigado = lista.split(':').any { it.equals(nome, true) } || ServicoTradutor.ativo != null
        bolhaNaTela = ServicoTradutor.bolhaNaTela
        // a notificação fixa pode ser dispensada com um arrasto no Android 14 e acima, e não voltava sozinha:
        // abrir o app a recoloca, porque ela é o outro caminho para a bolha
        if (servicoLigado) ctx.sendBroadcast(Intent(ServicoTradutor.ACAO_NOTIFICAR).setPackage(ctx.packageName))
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
        Text("Uma bolha fica por cima do navegador. Ao tocar nela, o app lê a tela, traduz e mostra a página traduzida. Toque de novo para voltar.",
            color = Texto2, fontSize = 14.sp)

        CartaoLeitor()

        Cartao("Captura da tela", if (servicoLigado) "Ativada" else "Ativar",
            "Para funcionar, o app precisa capturar o que está na tela quando você toca na bolha. " +
            "A captura acontece só nesse momento, não fica guardada, e ela alcança qualquer coisa visível " +
            "na hora, inclusive o que estiver em outros aplicativos. Isso é ligado em Acessibilidade, nas " +
            "configurações do Android, e você desliga no mesmo lugar quando quiser.",
            pronto = servicoLigado) {
            tentouAtivar = true
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // O Android bloqueia acessibilidade para app instalado fora da loja ("configurações restritas"). O caminho
        // para liberar NÃO fica na tela de acessibilidade, então o app diz onde é em vez de deixar o dono procurando.
        if (tentouAtivar && !servicoLigado) Column(
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

        // Sem este cartão a bolha escondida só voltava pela notificação fixa — e ela pode ser dispensada.
        // Era um beco sem saída: o botão da captura fica desabilitado quando o serviço já está ligado.
        if (servicoLigado) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Superficie).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bolha", color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(if (bolhaNaTela) "A bolha está na tela. Toque nela para traduzir, arraste para mudar de lugar."
                 else "A bolha está escondida. Traga ela de volta para traduzir por cima do navegador.",
                 color = Texto2, fontSize = 13.sp)
            Button(onClick = {
                ctx.sendBroadcast(Intent(ServicoTradutor.ACAO_BOLHA).setPackage(ctx.packageName))
                escopo.launch { kotlinx.coroutines.delay(300); bolhaNaTela = ServicoTradutor.bolhaNaTela }
            }, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Fundo)) {
                Text(if (bolhaNaTela) "Esconder bolha" else "Mostrar bolha", fontSize = 15.sp)
            }
        }

        if (servicoLigado)
            Text("Tudo pronto. Abra o quadrinho no navegador e toque na bolha.", color = Coral, fontSize = 14.sp)
        else
            Text("Falta ligar a captura, acima.", color = Texto2, fontSize = 13.sp)

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
        Text("Leitor de capítulo", color = Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
