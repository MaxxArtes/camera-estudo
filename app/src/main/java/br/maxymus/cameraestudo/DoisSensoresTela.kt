package br.maxymus.cameraestudo

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/*
 * Telas do teste de dois sensores (0.79). Desenho revisado pelo Astra em duas rodadas (02/10): o preparo fica dentro do
 * diálogo "Sensores" (um diálogo a menos para uma ação rara), a devolução da câmera é uma fase do teste, o resultado
 * separa origem das imagens, sincronização e arquivo salvo, e nenhum par sai do aparelho sem as prévias na tela.
 * Paleta do app: fundo #0E0E12, painel #16161B, coral #FF5A5F (texto escuro sobre coral, por contraste).
 */
private val CoralDois = Color(0xFFFF5A5F)
private val FundoDois = Color(0xFF0E0E12)
private val PainelDois = Color(0xFF16161B)
private val CinzaDois = Color(0xFFBDBDBD)
private val TecnicoDois = Color(0xFF8A8A8A)

/** Instrução de cena ANTES de a prévia apagar. Texto literal do desenho (D1). */
private const val INSTRUCAO_CENA = "Apoie o celular. Enquadre objetos parados com textura, a 30 cm–1 m, em boa luz. " +
    "A prévia ficará apagada durante o teste, que pode levar cerca de 60 s. Mantenha o app aberto."

/** Ação em linha inteira, alinhada à esquerda, com alvo de pelo menos 48 dp. */
@Composable
private fun AcaoDois(titulo: String, legenda: String?, ativo: Boolean = true, aoTocar: () -> Unit) {
    TextButton(onClick = aoTocar, enabled = ativo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(titulo, color = if (ativo) CoralDois else CinzaDois, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (legenda != null) Text(legenda, color = CinzaDois, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CampoDois(nome: String, valor: String) {
    Text("$nome: $valor", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
}

/**
 * Item "Sensores" da gaveta: último resultado com data, instrução de cena e as ações. Um diálogo só, sem "Antes de
 * começar". "Ver último par" abre a revisão; nunca compartilha direto daqui. Cancelar virou Fechar (nada em curso).
 */
@Composable
internal fun DialogoSensores(
    ultimoResumo: String?, temUltimoPar: Boolean, frontal: Boolean,
    aoTestar: () -> Unit, aoRelatorio: () -> Unit, aoVerPar: () -> Unit, aoFechar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = aoFechar,
        containerColor = PainelDois,
        title = { Text("Sensores", color = Color.White, fontSize = 22.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (ultimoResumo != null) Text("Último teste: $ultimoResumo", color = CinzaDois, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
                Text(INSTRUCAO_CENA, color = Color.White, fontSize = 16.sp)
                if (frontal) Text("A prévia atrás é da câmera frontal; o teste usa a traseira.", color = CinzaDois, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = aoTestar,
                    colors = ButtonDefaults.buttonColors(containerColor = CoralDois, contentColor = FundoDois),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Testar dois sensores", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(modifier = Modifier.height(8.dp))
                AcaoDois("Compartilhar relatório", "Somente texto, sem imagens", aoTocar = aoRelatorio)
                if (temUltimoPar) AcaoDois("Ver último par", "Mostra as imagens antes de compartilhar", aoTocar = aoVerPar)
            }
        },
        confirmButton = { TextButton(onClick = aoFechar, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fechar", color = CoralDois) } }
    )
}

/**
 * Véu sobre a tela inteira enquanto o teste segura a câmera (e enquanto ela fecha e volta): engole todo toque, explica
 * por que a prévia apagou, mostra a etapa curta e o tempo, e deixa o cancelamento embaixo, perto do polegar. O detalhe
 * técnico (configuração, tamanho, etapa interna) fica numa linha secundária, menor e apagada.
 */
@Composable
internal fun VeuDoisSensores(
    titulo: String, aviso: String?, fase: String?, tecnico: String?, segundos: Int?,
    botao: String?, botaoAtivo: Boolean, aoBotao: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(FundoDois.copy(alpha = 0.9f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PainelDois).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = CoralDois, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Text(titulo, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                if (aviso != null) Text(aviso, color = CinzaDois, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                if (fase != null) Text(fase, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                if (segundos != null) Text("Tempo decorrido: $segundos s", color = CinzaDois, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                if (tecnico != null) Text(tecnico, color = TecnicoDois, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            if (botao != null) OutlinedButton(
                onClick = aoBotao, enabled = botaoAtivo,
                border = BorderStroke(1.5.dp, if (botaoAtivo) CoralDois else CinzaDois),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) { Text(botao, color = if (botaoAtivo) CoralDois else CinzaDois, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** A câmera do app não voltou (ou ainda não foi liberada) depois do teste: cartão na prévia, sem bloquear o resto da tela. */
@Composable
internal fun CartaoCameraNaoVoltou(mensagem: String, aoReabrir: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(PainelDois).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(mensagem, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = aoReabrir,
            colors = ButtonDefaults.buttonColors(containerColor = CoralDois, contentColor = FundoDois),
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text("Reabrir câmera", fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * O pacote exato que vai sair: data e rodada, as imagens inteiras (sem recorte, toque amplia), o rótulo de como foram
 * capturadas, a frase e a lista exata do que será anexado, e o tamanho. O botão só liga com as prévias carregadas.
 * "Apagar este teste" remove a pasta inteira da rodada.
 */
@Composable
private fun BlocoPacote(
    pacote: DoisSensores.Pacote?, carregando: Boolean,
    aoAmpliar: (DoisSensores.Miniatura) -> Unit, aoCompartilhar: (DoisSensores.Pacote) -> Unit, aoApagar: (File) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (pacote == null) {
            Text(if (carregando) "Carregando as imagens..." else "Não consegui abrir as imagens deste teste.", color = CinzaDois, fontSize = 14.sp)
            return@Column
        }
        Text("${pacote.quando} · rodada ${pacote.rodada}", color = CinzaDois, fontSize = 13.sp)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (m in pacote.miniaturas) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = m.bitmap.asImageBitmap(), contentDescription = m.rotulo + ", tocar para ampliar", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp, max = 200.dp).background(FundoDois).clickable { aoAmpliar(m) }
                    )
                    Text(m.rotulo, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Text(pacote.rotuloCaptura, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(pacote.descricao(), color = CinzaDois, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text(pacote.listaExata, color = TecnicoDois, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        if (pacote.previaFalhou()) Text("Não consegui abrir todas as prévias; sem elas o compartilhamento fica desligado.", color = CoralDois, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        AcaoDois(
            "Compartilhar este par",
            if (pacote.pronto()) null else if (pacote.previaFalhou()) "Indisponível sem as prévias" else "Espere as prévias carregarem",
            ativo = pacote.pronto()
        ) { aoCompartilhar(pacote) }
        AcaoDois("Apagar este teste", "Apaga do aparelho as imagens e o resultado deste teste") { aoApagar(pacote.pasta) }
    }
}

/**
 * Resultado do teste: conclusão (frase da tabela do Astra), evidência e ressalvas, os três campos fixos, plano B
 * rotulado, próximo passo e o pacote. A câmera que não voltou ou não foi liberada mostra "Reabrir câmera".
 */
@Composable
internal fun DialogoResultadoDois(
    res: DoisSensores.Resultado, pacote: DoisSensores.Pacote?, carregandoPacote: Boolean, cameraNaoVoltou: String?, fechamentoPendente: Boolean,
    aoReabrir: () -> Unit, aoRelatorio: () -> Unit, aoCompartilharPar: (DoisSensores.Pacote) -> Unit,
    aoAmpliar: (DoisSensores.Miniatura) -> Unit, aoApagar: (File) -> Unit, aoFechar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = aoFechar,
        containerColor = PainelDois,
        title = { Text("Resultado do teste", color = Color.White, fontSize = 22.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(res.titulo(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                val detalhe = res.detalhe()
                if (detalhe.isNotEmpty()) Text(detalhe, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                res.motivoCru()?.let { Text("($it)", color = TecnicoDois, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                if (res.resultado == "recusou_limpo" || res.resultado == "recusou_sem_consulta") {
                    Text(DoisSensores.DECISAO_C, color = CinzaDois, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                CampoDois("Origem das imagens", res.origem())
                CampoDois("Sincronização", res.sincronizacao())
                CampoDois("Par salvo", res.parSalvoTexto())
                res.rotuloSequencial()?.let { Text(it, color = CoralDois, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                res.planoBSemQuadro()?.let { Text(it, color = CinzaDois, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                val passo = res.proximoPasso()
                if (passo.isNotEmpty()) Text("Próximo passo: $passo", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                if (fechamentoPendente) {
                    Text(
                        "A câmera ainda não foi liberada pelo sistema. Novo teste e religar da câmera ficam bloqueados até ela confirmar.",
                        color = CoralDois, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)
                    )
                    AcaoDois("Reabrir câmera", null, aoTocar = aoReabrir)
                } else if (cameraNaoVoltou != null) {
                    Text("A câmera não voltou a funcionar (código $cameraNaoVoltou).", color = CoralDois, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    AcaoDois("Reabrir câmera", null, aoTocar = aoReabrir)
                }
                if (pacote != null || carregandoPacote) BlocoPacote(pacote, carregandoPacote, aoAmpliar, aoCompartilharPar, aoApagar)
                else res.pasta?.let { pasta -> AcaoDois("Apagar este teste", "Apaga do aparelho o resultado deste teste") { aoApagar(pasta) } }
                AcaoDois("Compartilhar relatório", "Somente texto, sem imagens", aoTocar = aoRelatorio)
            }
        },
        confirmButton = { TextButton(onClick = aoFechar, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fechar", color = CoralDois) } }
    )
}

/** "Ver último par": a revisão vem antes do envio, também para um par de outra sessão do app. */
@Composable
internal fun DialogoUltimoPar(
    pacote: DoisSensores.Pacote?, carregando: Boolean,
    aoAmpliar: (DoisSensores.Miniatura) -> Unit, aoCompartilhar: (DoisSensores.Pacote) -> Unit, aoApagar: (File) -> Unit, aoFechar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = aoFechar,
        containerColor = PainelDois,
        title = { Text("Último par", color = Color.White, fontSize = 22.sp) },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { BlocoPacote(pacote, carregando, aoAmpliar, aoCompartilhar, aoApagar) } },
        confirmButton = { TextButton(onClick = aoFechar, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fechar", color = CoralDois) } }
    )
}

/** Imagem inteira, decodificada no aparelho; toque fecha. */
@Composable
internal fun DialogoAmpliarDois(rotulo: String, bitmap: Bitmap?, aoFechar: () -> Unit) {
    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable(onClick = aoFechar), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = rotulo, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else CircularProgressIndicator(color = CoralDois)
            Text(rotulo, color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp))
        }
    }
}
