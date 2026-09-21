package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import android.graphics.RectF
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Receita de edição (só parâmetros; o histórico guarda isso, nunca bitmaps). */
private data class Receita(val cor: Edicao.Cor = Edicao.Cor(), val geo: Edicao.Geometria = Edicao.Geometria())

private enum class Ferramenta(val rotulo: String) { Recortar("Recortar"), Ajustar("Ajustar"), Filtros("Filtros") }
private val PARAMETROS = listOf("Brilho", "Contraste", "Saturação", "Temperatura", "Matiz")
private val PROPORCOES = listOf("Livre" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)

/**
 * Editor (desenho do Astra, 20/09): prévia em tela cheia sobre preto; faixa de ferramentas de 72 dp; painel contextual
 * de 144 dp; topo com X · Desfazer/Refazer · Salvar. Salvar grava uma CÓPIA e abre no visualizador.
 */
@Composable
fun TelaEditor(midia: Midia, fechar: () -> Unit, aoSalvo: (Midia) -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val densidade = LocalDensity.current
    var previa by remember { mutableStateOf<Bitmap?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var receita by remember { mutableStateOf(Receita()) }
    val historico = remember { mutableStateOf(listOf(Receita())) }
    var posHist by remember { mutableStateOf(0) }
    var ferramenta by remember { mutableStateOf<Ferramenta?>(null) }
    var parametro by remember { mutableStateOf(PARAMETROS[0]) }
    var proporcao by remember { mutableStateOf(0f) }
    var comparando by remember { mutableStateOf(false) }
    var salvando by remember { mutableStateOf(false) }
    var confirmarSaida by remember { mutableStateOf(false) }
    var previaGeo by remember { mutableStateOf<Bitmap?>(null) }        // prévia com geometria aplicada (fora do recorte)
    var previaOrientada by remember { mutableStateOf<Bitmap?>(null) }  // só giros/espelho (dentro do recorte)
    var miniatura by remember { mutableStateOf<Bitmap?>(null) }        // base das miniaturas dos filtros

    LaunchedEffect(midia.id) {
        val b = withContext(Dispatchers.IO) { runCatching { Edicao.carregar(ctx, midia.uri, Edicao.LADO_MAX_PREVIA) }.getOrNull() }
        if (b == null) { erro = "Não consegui abrir esta foto para editar."; Telemetria.evento("erro", mapOf("onde" to "editor_carregar")) } else previa = b
    }
    // prévias derivadas: recalculadas quando a geometria muda (giros/espelho a cada toque; recorte/endireitar ao sair da ferramenta)
    LaunchedEffect(previa, receita.geo.giros, receita.geo.espelhado) {
        val p = previa ?: return@LaunchedEffect
        previaOrientada = withContext(Dispatchers.Default) { Edicao.aplicaGeometria(p, Edicao.Geometria(receita.geo.giros, receita.geo.espelhado)) }
    }
    LaunchedEffect(previa, receita.geo, ferramenta) {
        val p = previa ?: return@LaunchedEffect
        if (ferramenta == Ferramenta.Recortar) return@LaunchedEffect
        val g = withContext(Dispatchers.Default) { Edicao.aplicaGeometria(p, receita.geo) }
        previaGeo = g
        miniatura = withContext(Dispatchers.Default) { val esc = 160f / maxOf(g.width, g.height); if (esc >= 1f) g else Bitmap.createScaledBitmap(g, (g.width * esc).roundToInt().coerceAtLeast(1), (g.height * esc).roundToInt().coerceAtLeast(1), true) }
    }

    fun mudou() = receita != Receita()
    fun registra(nova: Receita) {   // um passo por gesto completo (nunca por atualização de slider)
        if (nova == receita) return
        val h = historico.value.take(posHist + 1) + nova
        historico.value = h; posHist = h.size - 1; receita = nova
    }
    fun desfazer() { if (posHist > 0) { posHist--; receita = historico.value[posHist] } }
    fun refazer() { if (posHist < historico.value.size - 1) { posHist++; receita = historico.value[posHist] } }
    fun sair() { if (mudou()) confirmarSaida = true else fechar() }
    BackHandler { sair() }

    fun salvar() {
        if (salvando) return
        salvando = true
        escopo.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cheia = Edicao.carregar(ctx, midia.uri, Edicao.LADO_MAX_COPIA) ?: error("não decodificou")
                    val comGeo = Edicao.aplicaGeometria(cheia, receita.geo)
                    val pronta = Edicao.aplicaCor(comGeo, receita.cor)
                    val s = Edicao.salvarCopia(ctx, midia, pronta)
                    if (pronta !== comGeo) pronta.recycle(); if (comGeo !== cheia) comGeo.recycle(); cheia.recycle()
                    s
                }
            }
            salvando = false
            r.onSuccess { s ->
                Telemetria.evento("editor_salvou", mapOf("larg" to s.largura, "alt" to s.altura, "filtro" to receita.cor.filtro, "recorte" to !receita.geo.neutra))
                Toast.makeText(ctx, "Cópia salva", Toast.LENGTH_SHORT).show()
                val id = s.uri.lastPathSegment?.toLongOrNull() ?: 0L
                aoSalvo(Midia(id, s.uri, false, if (midia.quando > 0L) midia.quando else System.currentTimeMillis(), 0L, midia.pasta))
            }.onFailure { e ->
                Telemetria.evento("erro", mapOf("onde" to "editor_salvar", "msg" to (e.message ?: e::class.java.simpleName)))
                Toast.makeText(ctx, "Não consegui salvar: ${e.message ?: "erro"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val filtroCor = remember(receita.cor, comparando) { if (comparando) null else ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(Edicao.matriz(receita.cor).array)) }

    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding().navigationBarsPadding()) {
        // ---- topo: X · desfazer/refazer · Salvar ----
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { sair() }) { Icon(Icons.Filled.Close, contentDescription = "Descartar", tint = Tema.Texto) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { desfazer() }, enabled = posHist > 0) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Desfazer", tint = if (posHist > 0) Tema.Texto else Tema.Texto2) }
            IconButton(onClick = { refazer() }, enabled = posHist < historico.value.size - 1) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Refazer", tint = if (posHist < historico.value.size - 1) Tema.Texto else Tema.Texto2) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { salvar() }, enabled = mudou() && !salvando && previa != null, modifier = Modifier.padding(end = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White, disabledContainerColor = Tema.Superficie, disabledContentColor = Tema.Texto2)) {
                if (salvando) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("Salvar", fontSize = 14.sp)
            }
        }

        // ---- prévia ----
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).padding(12.dp), contentAlignment = Alignment.Center) {
            when {
                erro != null -> Text(erro!!, color = Tema.Texto2, modifier = Modifier.padding(24.dp))
                previa == null -> CircularProgressIndicator(color = Tema.Coral)
                ferramenta == Ferramenta.Recortar -> previaOrientada?.let { b ->
                    Recorte(b, receita.geo, proporcao, densidade.density, filtroCor) { novaGeo -> registra(receita.copy(geo = novaGeo)) }
                }
                else -> (previaGeo ?: previa)?.let { b ->
                    Box(Modifier.fillMaxSize().pointerInput(b) {
                        awaitEachGesture {   // segurar 350 ms sem mover = comparar com a original
                            val baixo = awaitFirstDown(); var moveu = false; val ini = System.currentTimeMillis()
                            do { val ev = awaitPointerEvent(); if (ev.changes.any { abs(it.position.x - baixo.position.x) > 24f || abs(it.position.y - baixo.position.y) > 24f }) moveu = true
                                if (!moveu && !comparando && System.currentTimeMillis() - ini >= 350) comparando = true
                            } while (ev.changes.any { it.pressed })
                            comparando = false
                        }
                    }, contentAlignment = Alignment.Center) {
                        Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, colorFilter = filtroCor, modifier = Modifier.fillMaxSize())
                        if (comparando) Text("Original", color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }

        // ---- aviso discreto ----
        Text("Cópia em JPEG · até 4096 px · a original fica intacta", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))

        // ---- painel contextual (144 dp) ----
        AnimatedVisibility(visible = ferramenta != null) {
            Box(Modifier.fillMaxWidth().height(144.dp).background(Tema.Superficie)) {
                when (ferramenta) {
                    Ferramenta.Recortar -> PainelRecorte(receita.geo, proporcao, aoProporcao = { proporcao = it; registra(receita.copy(geo = receita.geo.copy(recorte = recorteCentral(previaOrientada, it)))) },
                        aoGirar = { registra(receita.copy(geo = receita.geo.copy(giros = (receita.geo.giros + 1) % 4, recorte = RectF(0f, 0f, 1f, 1f)))) },
                        aoEspelhar = { registra(receita.copy(geo = receita.geo.copy(espelhado = !receita.geo.espelhado, recorte = RectF(0f, 0f, 1f, 1f)))) },
                        aoEndireitar = { v -> receita = receita.copy(geo = receita.geo.copy(endireitar = v)) }, aoEndireitarFim = { registra(receita) },
                        aoRedefinir = { proporcao = 0f; registra(receita.copy(geo = Edicao.Geometria())) })
                    Ferramenta.Ajustar -> PainelAjustar(receita.cor, parametro, aoParametro = { parametro = it },
                        aoValor = { v -> receita = receita.copy(cor = comValor(receita.cor, parametro, v)) }, aoValorFim = { registra(receita) },
                        aoZerar = { registra(receita.copy(cor = comValor(receita.cor, parametro, 0f))) })
                    Ferramenta.Filtros -> PainelFiltros(miniatura, receita.cor, aoFiltro = { f -> registra(receita.copy(cor = receita.cor.copy(filtro = f, intensidade = 100f))) },
                        aoIntensidade = { v -> receita = receita.copy(cor = receita.cor.copy(intensidade = v)) }, aoIntensidadeFim = { registra(receita) })
                    null -> {}
                }
            }
        }

        // ---- faixa de ferramentas (72 dp) ----
        Row(Modifier.fillMaxWidth().height(72.dp).background(Tema.Fundo), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Ferramenta.values().forEach { f ->
                val ativa = ferramenta == f
                Column(Modifier.weight(1f).fillMaxSize().clickable { ferramenta = if (ativa) null else f }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(when (f) { Ferramenta.Recortar -> Icons.Filled.Crop; Ferramenta.Ajustar -> Icons.Filled.Tune; Ferramenta.Filtros -> Icons.Filled.PhotoFilter }, contentDescription = null, tint = if (ativa) Tema.Coral else Tema.Texto, modifier = Modifier.size(24.dp))
                    Text(f.rotulo, color = if (ativa) Tema.Coral else Tema.Texto, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (confirmarSaida) AlertDialog(onDismissRequest = { confirmarSaida = false }, title = { Text("Descartar edições?") },
        text = { Text("A foto original não foi alterada.") },
        confirmButton = { TextButton(onClick = { confirmarSaida = false; fechar() }) { Text("Descartar", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { confirmarSaida = false }) { Text("Continuar editando") } })
}

private fun comValor(c: Edicao.Cor, p: String, v: Float): Edicao.Cor = when (p) {
    "Brilho" -> c.copy(brilho = v); "Contraste" -> c.copy(contraste = v); "Saturação" -> c.copy(saturacao = v)
    "Temperatura" -> c.copy(temperatura = v); else -> c.copy(matiz = v)
}
private fun valorDe(c: Edicao.Cor, p: String): Float = when (p) {
    "Brilho" -> c.brilho; "Contraste" -> c.contraste; "Saturação" -> c.saturacao; "Temperatura" -> c.temperatura; else -> c.matiz
}

/** Maior recorte centrado com a proporção pedida (0 = livre → tudo). */
private fun recorteCentral(b: Bitmap?, prop: Float): RectF {
    if (b == null || prop <= 0f) return RectF(0f, 0f, 1f, 1f)
    val w = b.width.toFloat(); val h = b.height.toFloat()
    val (cw, ch) = if (w / h > prop) (h * prop) to h else w to (w / prop)
    val l = (w - cw) / 2f / w; val t = (h - ch) / 2f / h
    return RectF(l, t, l + cw / w, t + ch / h)
}

// ---------------- painéis ----------------
@Composable
private fun Chip(rotulo: String, ativo: Boolean, marcado: Boolean = false, aoTocar: () -> Unit) {
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (ativo) Color.Transparent else Tema.Fundo)
        .border(1.dp, if (ativo) Tema.Coral else Color.Transparent, RoundedCornerShape(18.dp)).clickable(onClick = aoTocar).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(rotulo, color = if (ativo) Tema.Coral else Tema.Texto, fontSize = 13.sp)
        if (marcado) Box(Modifier.padding(start = 6.dp).size(4.dp).background(Tema.Coral, CircleShape))
    }
}

@Composable
private fun PainelRecorte(geo: Edicao.Geometria, proporcao: Float, aoProporcao: (Float) -> Unit, aoGirar: () -> Unit, aoEspelhar: () -> Unit,
                          aoEndireitar: (Float) -> Unit, aoEndireitarFim: () -> Unit, aoRedefinir: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PROPORCOES.forEach { (r, v) -> Chip(r, proporcao == v) { aoProporcao(v) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = aoGirar) { Icon(Icons.Filled.Rotate90DegreesCw, contentDescription = "Girar 90°", tint = Tema.Texto) }
            IconButton(onClick = aoEspelhar) { Icon(Icons.Filled.Flip, contentDescription = "Espelhar", tint = if (geo.espelhado) Tema.Coral else Tema.Texto) }
            Text("${"%.1f".format(geo.endireitar)}°", color = Tema.Texto, fontSize = 14.sp, modifier = Modifier.width(56.dp))
            Slider(value = geo.endireitar, onValueChange = { v -> aoEndireitar(if (abs(v) < 0.6f) 0f else (v * 10).roundToInt() / 10f) }, onValueChangeFinished = aoEndireitarFim,
                valueRange = -45f..45f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
            TextButton(onClick = aoRedefinir) { Text("Redefinir", color = Tema.Texto2, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun PainelAjustar(cor: Edicao.Cor, parametro: String, aoParametro: (String) -> Unit, aoValor: (Float) -> Unit, aoValorFim: () -> Unit, aoZerar: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PARAMETROS.forEach { p -> Chip(p, parametro == p, marcado = valorDe(cor, p) != 0f) { aoParametro(p) } }
        }
        val v = valorDe(cor, parametro)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (v > 0) "+" else "") + v.roundToInt().toString(), color = Tema.Texto, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
            Slider(value = v, onValueChange = { aoValor(it.roundToInt().toFloat()) }, onValueChangeFinished = aoValorFim, valueRange = -100f..100f,
                modifier = Modifier.weight(1f).pointerInput(parametro) { detectTapGestures(onDoubleTap = { aoZerar() }) },
                colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
            TextButton(onClick = aoZerar, enabled = v != 0f) { Text("Redefinir", color = if (v != 0f) Tema.Texto2 else Color.Transparent, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun PainelFiltros(base: Bitmap?, cor: Edicao.Cor, aoFiltro: (String) -> Unit, aoIntensidade: (Float) -> Unit, aoIntensidadeFim: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Edicao.FILTROS.forEach { f ->
                val ativo = cor.filtro == f
                Column(Modifier.clickable { aoFiltro(f) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, if (ativo) Tema.Coral else Color.Transparent, RoundedCornerShape(8.dp)).background(Tema.Fundo)) {
                        base?.let { Image(bitmap = it.asImageBitmap(), contentDescription = f, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            colorFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(Edicao.matriz(cor.copy(filtro = f, intensidade = 100f)).array))) }
                    }
                    Text(f, color = if (ativo) Tema.Coral else Tema.Texto2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        val semFiltro = cor.filtro == "Original"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (semFiltro) "" else cor.intensidade.roundToInt().toString(), color = Tema.Texto, fontSize = 14.sp, modifier = Modifier.width(44.dp))
            Slider(value = cor.intensidade, onValueChange = { aoIntensidade(it.roundToInt().toFloat()) }, onValueChangeFinished = aoIntensidadeFim, valueRange = 0f..100f, enabled = !semFiltro,
                modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
        }
    }
}

// ---------------- recorte com alças ----------------
/** coerceIn que não estoura quando os limites se cruzam. */
private fun entre(v: Float, a: Float, b: Float): Float = v.coerceIn(min(a, b), maxOf(a, b))

private enum class Alca { NENHUMA, MOVER, TL, TR, BL, BR, T, B, L, R }

/**
 * Mostra a imagem orientada (giros/espelho já aplicados) com o endireitar por graphicsLayer + zoom que enche, e a
 * moldura de recorte normalizada por cima. Alças redimensionam; arrastar dentro move a moldura. Exterior a 60%.
 */
@Composable
private fun Recorte(b: Bitmap, geo: Edicao.Geometria, proporcao: Float, densidade: Float, filtro: ColorFilter?, aoRecorte: (Edicao.Geometria) -> Unit) {
    var caixa by remember { mutableStateOf(IntSize.Zero) }
    var rc by remember(geo.recorte) { mutableStateOf(geo.recorte) }
    val zoom = Edicao.zoomEndireitar(b.width.toFloat(), b.height.toFloat(), geo.endireitar)
    // retângulo onde a imagem (encaixada) é desenhada, em px da caixa
    val img = remember(caixa, b) {
        if (caixa.width == 0 || caixa.height == 0) Rect.Zero else {
            val esc = min(caixa.width.toFloat() / b.width, caixa.height.toFloat() / b.height)
            val dw = b.width * esc; val dh = b.height * esc
            Rect(Offset((caixa.width - dw) / 2f, (caixa.height - dh) / 2f), Size(dw, dh))
        }
    }
    val toque = 24f * densidade
    fun px(r: RectF) = Rect(img.left + r.left * img.width, img.top + r.top * img.height, img.left + r.right * img.width, img.top + r.bottom * img.height)
    Box(Modifier.fillMaxSize().onSizeChanged { caixa = it }) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, colorFilter = filtro,
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = geo.endireitar; scaleX = zoom; scaleY = zoom })
        }
        Canvas(Modifier.fillMaxSize().pointerInput(img, proporcao) {
            var alca = Alca.NENHUMA
            detectDragGestures(
                onDragStart = { p ->
                    val r = px(rc)
                    val perto = { x: Float, y: Float -> abs(p.x - x) < toque && abs(p.y - y) < toque }
                    alca = when {
                        perto(r.left, r.top) -> Alca.TL; perto(r.right, r.top) -> Alca.TR; perto(r.left, r.bottom) -> Alca.BL; perto(r.right, r.bottom) -> Alca.BR
                        abs(p.y - r.top) < toque && p.x in r.left..r.right -> Alca.T; abs(p.y - r.bottom) < toque && p.x in r.left..r.right -> Alca.B
                        abs(p.x - r.left) < toque && p.y in r.top..r.bottom -> Alca.L; abs(p.x - r.right) < toque && p.y in r.top..r.bottom -> Alca.R
                        r.contains(p) -> Alca.MOVER; else -> Alca.NENHUMA
                    }
                },
                onDragEnd = { if (alca != Alca.NENHUMA) aoRecorte(geo.copy(recorte = RectF(rc))); alca = Alca.NENHUMA },
                onDragCancel = { alca = Alca.NENHUMA }
            ) { _, d ->
                if (alca == Alca.NENHUMA || img.width <= 0f) return@detectDragGestures
                val dx = d.x / img.width; val dy = d.y / img.height
                val minL = 0.12f
                val n = RectF(rc)
                when (alca) {
                    Alca.MOVER -> { n.offset(dx, dy); if (n.left < 0f) n.offset(-n.left, 0f); if (n.top < 0f) n.offset(0f, -n.top); if (n.right > 1f) n.offset(1f - n.right, 0f); if (n.bottom > 1f) n.offset(0f, 1f - n.bottom) }
                    Alca.TL -> { n.left += dx; n.top += dy }; Alca.TR -> { n.right += dx; n.top += dy }
                    Alca.BL -> { n.left += dx; n.bottom += dy }; Alca.BR -> { n.right += dx; n.bottom += dy }
                    Alca.T -> n.top += dy; Alca.B -> n.bottom += dy; Alca.L -> n.left += dx; Alca.R -> n.right += dx
                    else -> {}
                }
                if (alca != Alca.MOVER) {
                    n.left = entre(n.left, 0f, rc.right - minL); n.right = entre(n.right, rc.left + minL, 1f)
                    n.top = entre(n.top, 0f, rc.bottom - minL); n.bottom = entre(n.bottom, rc.top + minL, 1f)
                    if (proporcao > 0f) {   // trava de proporção em px: ajusta a altura pela largura, ancorando no lado oposto
                        val alturaPx = (n.width() * img.width) / proporcao
                        val alturaN = (alturaPx / img.height).coerceAtMost(1f)
                        when (alca) { Alca.TL, Alca.TR, Alca.T -> n.top = n.bottom - alturaN; else -> n.bottom = n.top + alturaN }
                        if (n.top < 0f) { n.top = 0f; n.bottom = alturaN }; if (n.bottom > 1f) { n.bottom = 1f; n.top = 1f - alturaN }
                    }
                }
                rc = n
            }
        }) {
            val r = px(rc); val escuro = Color(0x99000000)
            drawRect(escuro, Offset.Zero, Size(size.width, r.top)); drawRect(escuro, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
            drawRect(escuro, Offset(0f, r.top), Size(r.left, r.height)); drawRect(escuro, Offset(r.right, r.top), Size(size.width - r.right, r.height))
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(1f * densidade))
            for (i in 1..2) {   // terços
                drawLine(Color(0x88FFFFFF), Offset(r.left + r.width * i / 3, r.top), Offset(r.left + r.width * i / 3, r.bottom), 1f)
                drawLine(Color(0x88FFFFFF), Offset(r.left, r.top + r.height * i / 3), Offset(r.right, r.top + r.height * i / 3), 1f)
            }
            val l = 24f * densidade; val g = 3f * densidade   // cantos em L
            listOf(r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f), r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f)).forEach { (c, s) ->
                drawLine(Color.White, c, Offset(c.x + l * s.x, c.y), g); drawLine(Color.White, c, Offset(c.x, c.y + l * s.y), g)
            }
        }
    }
}
