package br.maxymus.cameraestudo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@Composable
fun TelaMelhorar() {
    val estado by FluxoMelhorar.estado.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val rede by lembrarRedeValidada()
    var negouPermissao by remember { mutableStateOf(false) }
    val permissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { aceita ->
        negouPermissao = !aceita
        if (aceita) FluxoMelhorar.salvar(ctx)
    }
    when (val atual = estado) {
        EstadoMelhorar.Ocioso -> Unit
        is EstadoMelhorar.Consentimento -> AlertDialog(
            onDismissRequest = FluxoMelhorar::fechar,
            title = { Text("Melhorar foto com IA online?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Somente esta foto será enviada, em uma cópia reduzida e sem metadados EXIF ou GPS, ao servidor do Camera Estudo e a um dos provedores de melhoria nomeados na política (SnapEdit/SilverAI ou iLoveIMG/iLovePDF). O conteúdo visível na imagem continuará presente. O tratamento e a retenção seguem a política de privacidade disponível abaixo. A detecção de rostos acontece no aparelho.")
                    Text("A IA pode alterar detalhes, texturas e traços de pessoas. Confira a comparação antes de salvar. A original será preservada e só será criada uma cópia se você escolher Salvar cópia.")
                    Text(when (atual.rostos) {
                        Rostos.Presenca.Detectado -> "Detectamos rosto nesta foto. O rosto também será enviado e a IA pode alterar sua aparência. Envie apenas se você tiver autorização das pessoas retratadas."
                        Rostos.Presenca.NaoDetectado -> "Não detectamos rostos, mas a detecção pode falhar. Verifique se há pessoas ou informações pessoais antes de enviar."
                        Rostos.Presenca.Falha -> "Não foi possível verificar se há rostos nesta foto. Ela pode conter pessoas ou informações pessoais. Confira a imagem antes de autorizar o envio."
                    })
                    Text("Melhorias disponíveis: ${atual.capacidades.restantes}. Renovação: ${dataRenovacao(atual.capacidades.renovacao)}.")
                    if (atual.politica != null) {
                        Text("Política atualizada (${atual.capacidades.privacidade})")
                        Text(remember(atual.politica) { HtmlCompat.fromHtml(atual.politica, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() })
                    }
                    TextButton(onClick = {
                        val url = atual.capacidades.url
                        if (url.startsWith(MelhoramentoOnline.BASE + "/")) runCatching {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                    }) { Text("Ler política de privacidade") }
                    if (!rede) Text(textoErroMelhorar("SEM_REDE"))
                }
            },
            confirmButton = { TextButton(enabled = rede, onClick = { FluxoMelhorar.aceitar(ctx) }) { Text("Aceitar e enviar esta foto") } },
            dismissButton = { TextButton(onClick = FluxoMelhorar::fechar) { Text("Cancelar") } }
        )
        is EstadoMelhorar.Resultado -> {
            var original by remember(atual.bitmap) { mutableStateOf(false) }
            var pressionada by remember(atual.bitmap) { mutableStateOf(false) }
            var zoom by remember(atual.bitmap) { mutableFloatStateOf(1f) }
            var deslocamento by remember(atual.bitmap) { mutableStateOf(Offset.Zero) }
            val mostrarOriginal = original || pressionada
            Dialog(onDismissRequest = FluxoMelhorar::fechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (mostrarOriginal) "Original" else "Melhorada", style = MaterialTheme.typography.titleLarge)
                        Text("Toque para alternar. Pressione para ver a original. Use dois dedos para ampliar.")
                        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
                            .pointerInput(atual.bitmap) {
                                detectTapGestures(onTap = { original = !original }, onPress = {
                                    pressionada = true
                                    try { tryAwaitRelease() } finally { pressionada = false }
                                })
                            }
                            .pointerInput(atual.bitmap) {
                                detectTransformGestures { _, pan, escala, _ ->
                                    zoom = (zoom * escala).coerceIn(1f, 6f)
                                    val limiteX = size.width * (zoom - 1f) / 2f
                                    val limiteY = size.height * (zoom - 1f) / 2f
                                    deslocamento = Offset((deslocamento.x + pan.x).coerceIn(-limiteX, limiteX), (deslocamento.y + pan.y).coerceIn(-limiteY, limiteY))
                                }
                            }) {
                            val imagem = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = zoom; scaleY = zoom; translationX = deslocamento.x; translationY = deslocamento.y; clip = true
                            }
                            if (mostrarOriginal) AsyncImage(model = atual.original, contentDescription = "Foto original", contentScale = ContentScale.Fit, modifier = imagem)
                            else Image(atual.bitmap.asImageBitmap(), "Foto melhorada", modifier = imagem, contentScale = ContentScale.Fit)
                        }
                        Text("Melhorias restantes: ${atual.restantes}. Renovação: ${dataRenovacao(atual.renovacao)}.")
                        atual.erroSalvar?.let { Text(it) }
                        if (negouPermissao) Text("Autorize o armazenamento para salvar a cópia no Android 8 ou 9.")
                        if (atual.salvando) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (atual.copia == null) {
                            Button(enabled = !atual.salvando, onClick = {
                                negouPermissao = false
                                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) permissao.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                else FluxoMelhorar.salvar(ctx)
                            }) { Text(if (atual.salvando) "Salvando cópia…" else "Salvar cópia") }
                            TextButton(enabled = !atual.salvando, onClick = FluxoMelhorar::fechar) { Text("Descartar") }
                        } else {
                            Text("Cópia salva em Imagens/CameraEstudo. A original foi preservada.")
                            Row {
                                TextButton(enabled = !atual.salvando, onClick = { FluxoMelhorar.desfazer(ctx) }) { Text("Desfazer") }
                                TextButton(enabled = !atual.salvando, onClick = FluxoMelhorar::fechar) { Text("Concluir") }
                            }
                        }
                    }
                }
            }
        }
        else -> {
            val ocupado = atual == EstadoMelhorar.Preparando || atual == EstadoMelhorar.Enviando || atual == EstadoMelhorar.Aguardando || atual == EstadoMelhorar.Aplicando
            val texto = when (atual) {
                EstadoMelhorar.Preparando -> "Preparando foto e verificando disponibilidade…"
                EstadoMelhorar.Enviando -> "Enviando foto…"
                EstadoMelhorar.Aguardando -> "Melhorando foto no SnapEdit…"
                EstadoMelhorar.Aplicando -> "Preparando comparação…"
                EstadoMelhorar.SemDiferenca -> "Não foi encontrada diferença na imagem devolvida. Nenhuma cópia foi salva."
                is EstadoMelhorar.Cota -> "Sua cota de melhorias terminou." + if (atual.renovacao.isBlank()) " Aguarde a renovação." else " Renovação: ${dataRenovacao(atual.renovacao)}."
                is EstadoMelhorar.Falha -> textoErroMelhorar(atual.codigo)
                else -> ""
            }
            AlertDialog(onDismissRequest = FluxoMelhorar::fechar, title = { Text("Melhorar foto") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(texto)
                    if (ocupado) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Cancelar interrompe a conexão. Se o processamento já começou, a cota pode ser consumida.")
                    }
                }
            }, confirmButton = { TextButton(onClick = FluxoMelhorar::fechar) { Text(if (ocupado) "Cancelar" else "Fechar") } })
        }
    }
}

private fun dataRenovacao(valor: String): String = runCatching {
    java.time.Instant.parse(valor).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
}.getOrDefault("horário indisponível")
