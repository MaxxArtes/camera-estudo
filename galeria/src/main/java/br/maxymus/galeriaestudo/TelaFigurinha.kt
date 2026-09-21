package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Criar figurinha (desenho do Astra, 21/09): prévia quadrada com xadrez e painel de 144 dp com Enquadrar · Refinar ·
 * Contorno. Começa pelo motor Padrão; "Melhorar recorte" tenta o Alta. Salvar grava em Minhas figurinhas — o
 * WhatsApp só aceita pacote com 3 ou mais, então a coleção é onde a adição acontece, não aqui.
 */
@Composable
fun TelaFigurinha(midia: Midia, fechar: () -> Unit, aoSalva: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val densidade = LocalDensity.current
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var mascara by remember { mutableStateOf<Fundo.Mascara?>(null) }
    var tracos by remember { mutableStateOf<List<Fundo.Traco>>(emptyList()) }
    var montagem by remember { mutableStateOf<Figurinha.Montagem?>(null) }
    var ocupado by remember { mutableStateOf(true) }
    var semPessoa by remember { mutableStateOf(false) }
    var aba by remember { mutableStateOf("Enquadrar") }
    var zoom by remember { mutableStateOf(1f) }
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    var contorno by remember { mutableStateOf(8f) }
    var pincelAdiciona by remember { mutableStateOf(true) }
    var pincelDp by remember { mutableStateOf(24f) }
    var tracoAtual by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var salvando by remember { mutableStateOf(false) }
    var motorUsado by remember { mutableStateOf<Fundo.Motor?>(null) }
    BackHandler { fechar() }

    suspend fun segmenta(motor: Fundo.Motor) {
        val b = base ?: return
        ocupado = true
        val m = withContext(Dispatchers.Default) { runCatching { Fundo.segmentar(ctx, b, motor) }.getOrNull() }
        ocupado = false
        if (m == null || m.cobertura < 0.01f) { semPessoa = true; mascara = null } else { semPessoa = false; mascara = m; motorUsado = m.motor }
    }

    LaunchedEffect(midia.id) {
        val b = withContext(Dispatchers.IO) { runCatching { Edicao.carregar(ctx, midia.uri, 1024) }.getOrNull() }
        if (b == null) { Toast.makeText(ctx, "Não consegui abrir esta foto.", Toast.LENGTH_LONG).show(); fechar(); return@LaunchedEffect }
        base = b
        segmenta(Fundo.Motor.Padrao)
    }
    // prévia com atraso curto: enquadrar e contorno mexem muito
    LaunchedEffect(base, mascara, tracos, zoom, dx, dy, contorno) {
        val b = base; val m = mascara
        if (b == null || m == null) { montagem = null; return@LaunchedEffect }
        delay(90)
        montagem = withContext(Dispatchers.Default) {
            val plena = Fundo.plenaDe(b, m, tracos)
            Figurinha.montar(b, plena, zoom, dx, dy, contorno.roundToInt())
        }
    }

    fun fechaTraco(lado: Float) {
        val mt = montagem
        if (tracoAtual.size < 2 || mt == null) { tracoAtual = emptyList(); return }
        // o traço é feito no quadrado, mas a máscara vive nas coordenadas da FOTO: desfaz o enquadramento
        val norm = tracoAtual.map { mt.paraFoto(it.x / lado, it.y / lado) }
        tracos = tracos + Fundo.Traco(norm, mt.raioParaFoto((pincelDp * densidade.density) / lado), pincelAdiciona)
        tracoAtual = emptyList()
    }

    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = fechar) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = Tema.Texto) }
            Text("Criar figurinha", color = Tema.Texto, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Button(onClick = {
                val p = montagem?.bitmap ?: return@Button
                if (salvando) return@Button
                salvando = true
                escopo.launch {
                    val ok = withContext(Dispatchers.IO) {
                        val bytes = Figurinha.webp(p) ?: return@withContext false
                        Figurinha.guardar(ctx, bytes); true
                    }
                    salvando = false
                    if (ok) { Toast.makeText(ctx, "Figurinha salva", Toast.LENGTH_SHORT).show(); aoSalva() }
                    else Toast.makeText(ctx, "Não consegui gerar a figurinha.", Toast.LENGTH_LONG).show()
                }
            }, enabled = montagem != null && !salvando, modifier = Modifier.padding(end = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White, disabledContainerColor = Tema.Superficie, disabledContentColor = Tema.Texto2)) {
                if (salvando) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("Salvar", fontSize = 14.sp)
            }
        }
        Box(Modifier.weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                .pointerInput(aba, pincelAdiciona, pincelDp, montagem) {
                    if (aba == "Refinar") detectDragGestures(
                        onDragStart = { o -> tracoAtual = listOf(o) },
                        onDragEnd = { fechaTraco(size.width.toFloat()) },
                        onDragCancel = { tracoAtual = emptyList() }) { mudanca, _ -> tracoAtual = tracoAtual + mudanca.position }
                    else if (aba == "Enquadrar") detectTransformGestures { _, pan, z, _ ->
                        zoom = (zoom * z).coerceIn(0.5f, 3f)
                        dx = (dx + pan.x / size.width).coerceIn(-0.5f, 0.5f)
                        dy = (dy + pan.y / size.height).coerceIn(-0.5f, 0.5f)
                    }
                }) {
                Xadrez(Modifier.fillMaxSize())
                montagem?.bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (aba == "Refinar" && tracoAtual.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                    val r = pincelDp * density / 2f
                    tracoAtual.forEach { drawCircle(if (pincelAdiciona) Color(0x9900E676) else Color(0x99FF5252), r, it) }
                }
                if (ocupado) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Tema.Coral, strokeWidth = 2.dp)
                        Text("Separando a pessoa…", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                if (semPessoa && !ocupado) Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Não achei uma pessoa nesta foto.", color = Color.White, fontSize = 14.sp)
                    TextButton(onClick = { escopo.launch { segmenta(Fundo.Motor.Alta) } }) { Text("Tentar o recorte Alta", color = Tema.Coral) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().height(144.dp).background(Tema.Fundo).padding(horizontal = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Enquadrar", "Refinar", "Contorno").forEach { a -> ChipFig(a, aba == a) { aba = a; tracoAtual = emptyList() } }
                if (motorUsado != null && motorUsado != Fundo.Motor.Alta) TextButton(onClick = { escopo.launch { segmenta(Fundo.Motor.Alta) } }, enabled = !ocupado) {
                    Text("Melhorar recorte", color = Tema.Texto2, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                when (aba) {
                    "Enquadrar" -> {
                        Text("Arraste e use dois dedos para ampliar.", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { zoom = 1f; dx = 0f; dy = 0f }) { Text("Pessoa inteira", color = Tema.Coral, fontSize = 12.sp) }
                    }
                    "Refinar" -> {
                        ChipFig("Adicionar", pincelAdiciona) { pincelAdiciona = true }
                        ChipFig("Apagar", !pincelAdiciona) { pincelAdiciona = false }
                        Text("${pincelDp.roundToInt()}", color = Tema.Texto, fontSize = 13.sp, modifier = Modifier.width(30.dp).padding(start = 6.dp))
                        Slider(value = pincelDp, onValueChange = { pincelDp = it.roundToInt().toFloat() }, valueRange = 8f..80f, modifier = Modifier.weight(1f).height(28.dp),
                            colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                        TextButton(onClick = { tracos = emptyList() }, enabled = tracos.isNotEmpty()) { Text("Limpar", color = if (tracos.isNotEmpty()) Tema.Texto2 else Color.Transparent, fontSize = 12.sp) }
                    }
                    else -> {
                        Text("Contorno ${contorno.roundToInt()}", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.width(94.dp))
                        Slider(value = contorno, onValueChange = { contorno = it.roundToInt().toFloat() }, valueRange = 0f..16f, modifier = Modifier.weight(1f).height(28.dp),
                            colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipFig(rotulo: String, ativo: Boolean, aoTocar: () -> Unit) {
    Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
        .background(if (ativo) Tema.Coral else Tema.Superficie)
        .clickable(onClick = aoTocar)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(rotulo, color = if (ativo) Color.White else Tema.Texto, fontSize = 13.sp)
    }
}
