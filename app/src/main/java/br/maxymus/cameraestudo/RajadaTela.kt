package br.maxymus.cameraestudo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Telas da rajada de teste (0.80), na mesma paleta e no mesmo desenho do teste de dois sensores: explicação antes, véu
 * durante (VeuDoisSensores), resultado com os números, e a revisão com as miniaturas e a lista exata antes de qualquer
 * envio. Nada sai do aparelho sem passar pela revisão.
 */
private val CoralRaj = Color(0xFFFF5A5F)
private val FundoRaj = Color(0xFF0E0E12)
private val PainelRaj = Color(0xFF16161B)
private val CinzaRaj = Color(0xFFBDBDBD)
private val TecnicoRaj = Color(0xFF8A8A8A)

/** Texto literal da instrução da fase A. */
private const val INSTRUCAO_RAJADA = "Apoie o celular ou segure firme, aponte para uma estante ou uma parede com textura, em luz baixa. " +
    "São 8 fotos seguidas e uma normal. As fotos ficam só no aparelho até você compartilhar."

@Composable
private fun AcaoRaj(titulo: String, legenda: String?, ativo: Boolean = true, aoTocar: () -> Unit) {
    TextButton(onClick = aoTocar, enabled = ativo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(titulo, color = if (ativo) CoralRaj else CinzaRaj, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (legenda != null) Text(legenda, color = CinzaRaj, fontSize = 13.sp)
        }
    }
}

@Composable
private fun FecharRaj(aoFechar: () -> Unit) {
    TextButton(onClick = aoFechar, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fechar", color = CoralRaj) }
}

/** Exposição legível: 1/30 s abaixo de um segundo, 1,2 s acima. */
internal fun textoExposicao(ns: Long?): String = when {
    ns == null || ns <= 0L -> "?"
    ns < 1_000_000_000L -> "1/" + Math.round(1e9 / ns) + " s"
    else -> "%.1f s".format(ns / 1e9)
}

/** Item "Rajada de teste" da gaveta: a explicação e o botão. "Ver a última rajada" abre a revisão, nunca compartilha direto. */
@Composable
internal fun DialogoRajada(temUltima: Boolean, aviso: String?, aoIniciar: () -> Unit, aoVerUltima: () -> Unit, aoFechar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoFechar,
        containerColor = PainelRaj,
        title = { Text("Rajada de teste", color = Color.White, fontSize = 22.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(INSTRUCAO_RAJADA, color = Color.White, fontSize = 16.sp)
                if (aviso != null) Text(aviso, color = CinzaRaj, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = aoIniciar,
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRaj, contentColor = FundoRaj),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Fazer a rajada", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                if (temUltima) AcaoRaj("Ver a última rajada", "Mostra as imagens antes de compartilhar", aoTocar = aoVerUltima)
            }
        },
        confirmButton = { FecharRaj(aoFechar) }
    )
}

/** Resultado: "8 quadros em X ms (Y fps)", a resolução, a exposição e o ISO; depois ver/compartilhar, apagar e fechar. */
@Composable
internal fun DialogoResultadoRajada(
    res: Rajada.Resultado, cameraNaoVoltou: Boolean, aoReabrir: () -> Unit, aoVer: () -> Unit, aoApagar: () -> Unit, aoFechar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = aoFechar,
        containerColor = PainelRaj,
        title = { Text("Rajada de teste", color = Color.White, fontSize = 22.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (res.resultado == "ok" || res.resultado == "variou") {
                    if (res.resultado == "variou") Text("Exposição ou ISO variaram; quadros salvos para análise.", color = CoralRaj, fontSize = 15.sp)
                    val fps = res.fps?.let { "%.1f fps".format(it) } ?: "fps ?"
                    Text("${res.quadros} quadros em ${res.msTotal ?: "?"} ms ($fps)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Resolução: ${res.largura}x${res.altura}", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    if (res.caiuResolucao()) Text(
                        "A maior (${res.larguraMax}x${res.alturaMax}) não aceitou a rajada; esta foi a maior que aceitou.",
                        color = CinzaRaj, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                    Text("Exposição: ${textoExposicao(res.exp)} · ISO ${res.iso ?: "?"}", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(if (res.normal) "Foto normal: salva junto." else "Foto normal: não saiu; os quadros foram salvos assim mesmo.",
                        color = CinzaRaj, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text(when (res.resultado) {
                        "camerax_nao_fechou" -> "A câmera do app não confirmou o fechamento. A rajada não começou."
                        "sem_convergencia" -> "Luz ou foco não convergiram e travaram no prazo. Nada foi capturado."
                        "recusou_resolucao" -> "A câmera não aceitou a rajada em nenhuma resolução tentada."
                        "perdeu_camera" -> "O sistema tirou a câmera do app no meio da rajada."
                        "cancelado" -> "Rajada cancelada. Nada foi salvo."
                        else -> "A rajada falhou. Nada foi salvo."
                    }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    res.classe?.let { Text("($it)", color = TecnicoRaj, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                }
                if (cameraNaoVoltou) {
                    Text("A câmera do app não voltou a funcionar.", color = CoralRaj, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    AcaoRaj("Reabrir câmera", null, aoTocar = aoReabrir)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (res.pasta != null) {
                    AcaoRaj("Ver e compartilhar", "Mostra as imagens e a lista dos arquivos antes do envio", aoTocar = aoVer)
                    AcaoRaj("Apagar esta rajada", "Apaga do aparelho os quadros, a foto normal e os metadados", aoTocar = aoApagar)
                }
            }
        },
        confirmButton = { FecharRaj(aoFechar) }
    )
}

/**
 * Revisão: data e rodada, as miniaturas de cada quadro Y e da foto normal, a lista exata dos arquivos com o tamanho e o
 * total. O compartilhamento (um .zip dessa lista) só liga com todas as prévias carregadas.
 */
@Composable
internal fun DialogoRevisaoRajada(
    pacote: Rajada.Pacote?, carregando: Boolean, zipando: Boolean,
    aoCompartilhar: (Rajada.Pacote) -> Unit, aoApagar: (Rajada.Pacote) -> Unit, aoFechar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = aoFechar,
        containerColor = PainelRaj,
        title = { Text("Rajada de teste", color = Color.White, fontSize = 22.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (pacote == null) {
                    Text(if (carregando) "Carregando as imagens..." else "Não consegui abrir esta rajada.", color = CinzaRaj, fontSize = 14.sp)
                    return@Column
                }
                Text("${pacote.quando} · rodada ${pacote.rodada}", color = CinzaRaj, fontSize = 13.sp)
                for (linha in pacote.miniaturas.chunked(3)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in linha) {
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(
                                    bitmap = m.bitmap.asImageBitmap(), contentDescription = m.rotulo, contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 120.dp).background(FundoRaj)
                                )
                                Text(m.rotulo, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        repeat(3 - linha.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
                Text(pacote.descricao(), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                for (l in pacote.listaExata) Text(l, color = TecnicoRaj, fontSize = 12.sp)
                if (pacote.previaFalhou()) Text("Não consegui abrir todas as prévias; sem elas o compartilhamento fica desligado.",
                    color = CoralRaj, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                AcaoRaj(
                    if (zipando) "Preparando o .zip..." else "Compartilhar .zip",
                    if (pacote.pronto()) null else if (pacote.previaFalhou()) "Indisponível sem as prévias" else "Espere as prévias carregarem",
                    ativo = pacote.pronto() && !zipando
                ) { aoCompartilhar(pacote) }
                AcaoRaj("Apagar esta rajada", "Apaga do aparelho os quadros, a foto normal e os metadados", ativo = !zipando) { aoApagar(pacote) }
            }
        },
        confirmButton = { FecharRaj(aoFechar) }
    )
}
