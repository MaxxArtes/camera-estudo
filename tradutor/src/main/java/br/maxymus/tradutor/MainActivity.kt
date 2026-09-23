package br.maxymus.tradutor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    var pacote by remember { mutableStateOf(if (Traducao.pacotePronto) "Pronto" else "Baixar pacote") }
    val escopo = androidx.compose.runtime.rememberCoroutineScope()

    fun confere() {
        val nome = ctx.packageName + "/" + ServicoTradutor::class.java.name
        val lista = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        servicoLigado = lista.split(':').any { it.equals(nome, true) } || ServicoTradutor.ativo != null
    }
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) confere() }
        dono.lifecycle.addObserver(obs); onDispose { dono.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { confere() }

    Column(Modifier.fillMaxSize().background(Fundo).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Tradutor de tela", color = Texto, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text("Uma bolha fica por cima do navegador. Ao tocar nela, o app lê a tela, traduz do inglês para o português e mostra a página traduzida. Toque de novo para voltar.",
            color = Texto2, fontSize = 14.sp)

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

        Cartao("Inglês para português", pacote,
            "O pacote de idioma é baixado uma vez e depois a tradução funciona sem internet.",
            pronto = pacote == "Pronto") {
            if (pacote != "Pronto") {
                pacote = "Baixando…"
                escopo.launch {
                    val ok = withContext(Dispatchers.IO) { Traducao.prepararPacote() }
                    pacote = if (ok) "Pronto" else "Falhou, tente de novo"
                }
            }
        }

        if (servicoLigado && pacote == "Pronto")
            Text("Tudo pronto. Abra o quadrinho no navegador e toque na bolha.", color = Coral, fontSize = 14.sp)
        else
            Text("Faltam os passos acima.", color = Texto2, fontSize = 13.sp)

        Text("Primeira versão: traduz a tela parada, uma tela por vez. Não acompanha a rolagem, e onomatopeia desenhada não é traduzida.",
            color = Texto2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
