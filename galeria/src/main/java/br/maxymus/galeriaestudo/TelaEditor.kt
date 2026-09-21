package br.maxymus.galeriaestudo

import android.graphics.Bitmap
import android.graphics.RectF
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private typealias Receita = Edicao.Receita

/** Grupos da faixa (Astra 20/09): ordem fixa, nome e ícone sempre visíveis. Marcações e Perspectiva entram quando prontos. */
private enum class Grupo(val rotulo: String, val icone: ImageVector, val parametros: List<String>) {
    Luz("Luz", Icons.Filled.WbSunny, listOf("Brilho", "Contraste", "Realces", "Sombras", "Brancos", "Pretos")),
    Cor("Cor", Icons.Filled.Palette, listOf("Temperatura", "Matiz", "Saturação")),
    Recortar("Recortar", Icons.Filled.Crop, emptyList()),
    Filtros("Filtros", Icons.Filled.PhotoFilter, emptyList()),
    Detalhe("Detalhe", Icons.Filled.Details, listOf("Nitidez", "Vinheta", "Granulação")),
    Fundo("Fundo", Icons.Filled.Portrait, emptyList()),
    Corrigir("Corrigir", Icons.Filled.Healing, listOf("Pele"))
}
private val PROPORCOES = listOf("Livre" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
private val CORES_FUNDO = listOf(0xFFFFFFFF, 0xFF111114, 0xFF8A8A92, 0xFFFF575F, 0xFF3D6BFF, 0xFF3D8A4A, 0xFFEFBD45).map { it.toInt() }
private const val LADO_TRABALHO = 1024

private fun valorDe(r: Receita, p: String): Float = when (p) {
    "Brilho" -> r.cor.brilho; "Contraste" -> r.cor.contraste; "Saturação" -> r.cor.saturacao; "Temperatura" -> r.cor.temperatura; "Matiz" -> r.cor.matiz
    "Realces" -> r.tom.realces; "Sombras" -> r.tom.sombras; "Brancos" -> r.tom.brancos; "Pretos" -> r.tom.pretos
    "Nitidez" -> r.tom.nitidez; "Vinheta" -> r.tom.vinheta; "Granulação" -> r.tom.granulacao; "Pele" -> r.fundo.pele; else -> 0f
}
private fun comValor(r: Receita, p: String, v: Float): Receita = when (p) {
    "Brilho" -> r.copy(cor = r.cor.copy(brilho = v)); "Contraste" -> r.copy(cor = r.cor.copy(contraste = v)); "Saturação" -> r.copy(cor = r.cor.copy(saturacao = v))
    "Temperatura" -> r.copy(cor = r.cor.copy(temperatura = v)); "Matiz" -> r.copy(cor = r.cor.copy(matiz = v))
    "Realces" -> r.copy(tom = r.tom.copy(realces = v)); "Sombras" -> r.copy(tom = r.tom.copy(sombras = v)); "Brancos" -> r.copy(tom = r.tom.copy(brancos = v)); "Pretos" -> r.copy(tom = r.tom.copy(pretos = v))
    "Nitidez" -> r.copy(tom = r.tom.copy(nitidez = v)); "Vinheta" -> r.copy(tom = r.tom.copy(vinheta = v)); "Granulação" -> r.copy(tom = r.tom.copy(granulacao = v))
    "Pele" -> r.copy(fundo = r.fundo.copy(pele = v)); else -> r
}
private fun faixa(p: String): ClosedFloatingPointRange<Float> = when (p) { "Granulação", "Pele" -> 0f..100f; else -> -100f..100f }

/**
 * Editor v2. Cadeia FIXA, a mesma na prévia e no arquivo: orientação → geometria → fundo → tom → cor (ColorMatrix) → filtro.
 * Fundo e tom rodam em CPU num bitmap de trabalho (≤1024 px) com debounce; cor/filtro em tempo real na GPU.
 * Salvar grava uma cópia (PNG se o fundo foi removido) e guarda a receita no banco para reedição.
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
    var grupo by remember { mutableStateOf<Grupo?>(null) }
    var parametro by remember { mutableStateOf("Brilho") }
    var proporcao by remember { mutableStateOf(0f) }
    var comparando by remember { mutableStateOf(false) }
    var salvando by remember { mutableStateOf(false) }
    var confirmarSaida by remember { mutableStateOf(false) }
    var previaGeo by remember { mutableStateOf<Bitmap?>(null) }        // geometria aplicada (fora do recorte)
    var previaOrientada by remember { mutableStateOf<Bitmap?>(null) }  // só giros/espelho (dentro do recorte)
    var trabalho by remember { mutableStateOf<Bitmap?>(null) }         // ≤1024 px: base das operações em CPU
    var previaCpu by remember { mutableStateOf<Bitmap?>(null) }        // trabalho + fundo + tom
    var miniatura by remember { mutableStateOf<Bitmap?>(null) }
    var mascara by remember { mutableStateOf<Fundo.Mascara?>(null) }
    var segmentando by remember { mutableStateOf(false) }
    var semPessoa by remember { mutableStateOf(false) }
    var autoBase by remember { mutableStateOf<Tom.Auto?>(null) }
    var autoIntensidade by remember { mutableStateOf(100f) }

    LaunchedEffect(midia.id) {
        val b = withContext(Dispatchers.IO) { runCatching { Edicao.carregar(ctx, midia.uri, Edicao.LADO_MAX_PREVIA) }.getOrNull() }
        if (b == null) { erro = "Não consegui abrir esta foto para editar."; Telemetria.evento("erro", mapOf("onde" to "editor_carregar")) } else previa = b
    }
    LaunchedEffect(previa, receita.geo.giros, receita.geo.espelhado) {
        val p = previa ?: return@LaunchedEffect
        previaOrientada = withContext(Dispatchers.Default) { Edicao.aplicaGeometria(p, Edicao.Geometria(receita.geo.giros, receita.geo.espelhado)) }
    }
    // geometria muda → recalcula prévia, bitmap de trabalho, miniatura e invalida a máscara (a pessoa mudou de lugar)
    LaunchedEffect(previa, receita.geo, grupo == Grupo.Recortar) {
        val p = previa ?: return@LaunchedEffect
        if (grupo == Grupo.Recortar) return@LaunchedEffect
        val g = withContext(Dispatchers.Default) { Edicao.aplicaGeometria(p, receita.geo) }
        previaGeo = g; mascara = null
        trabalho = withContext(Dispatchers.Default) { val esc = LADO_TRABALHO.toFloat() / maxOf(g.width, g.height); if (esc >= 1f) g else Bitmap.createScaledBitmap(g, (g.width * esc).roundToInt().coerceAtLeast(1), (g.height * esc).roundToInt().coerceAtLeast(1), true) }
        miniatura = withContext(Dispatchers.Default) { val t = trabalho!!; val esc = 160f / maxOf(t.width, t.height); if (esc >= 1f) t else Bitmap.createScaledBitmap(t, (t.width * esc).roundToInt().coerceAtLeast(1), (t.height * esc).roundToInt().coerceAtLeast(1), true) }
    }
    // fundo + tom em CPU, com debounce; máscara sob demanda (uma vez por geometria)
    LaunchedEffect(trabalho, receita.tom, receita.fundo) {
        val t = trabalho ?: return@LaunchedEffect
        if (receita.tom.neutro && receita.fundo.neutro) { previaCpu = null; return@LaunchedEffect }
        delay(120)
        val precisaMascara = !receita.fundo.neutro
        if (precisaMascara && mascara == null) {
            segmentando = true
            val m = withContext(Dispatchers.Default) { runCatching { Fundo.segmentar(ctx, t) }.getOrNull() }
            segmentando = false
            if (m == null || m.cobertura < 0.02f) { semPessoa = true; Telemetria.evento("editor_fundo", mapOf("pessoa" to false)) } else { semPessoa = false; mascara = m }
        }
        val m = mascara
        previaCpu = withContext(Dispatchers.Default) {
            val comFundo = if (!receita.fundo.neutro && m != null) Fundo.aplicar(t, m, receita.fundo) else t
            val comTom = Tom.aplicar(comFundo, receita.tom)
            if (comTom !== comFundo && comFundo !== t) comFundo.recycle()
            comTom
        }
    }

    fun mudou() = receita != Receita()
    fun registra(nova: Receita) { if (nova == receita) return; val h = historico.value.take(posHist + 1) + nova; historico.value = h; posHist = h.size - 1; receita = nova }
    fun desfazer() { if (posHist > 0) { posHist--; receita = historico.value[posHist] } }
    fun refazer() { if (posHist < historico.value.size - 1) { posHist++; receita = historico.value[posHist] } }
    fun sair() { if (mudou()) confirmarSaida = true else fechar() }
    BackHandler { sair() }

    fun aplicaAuto(a: Tom.Auto, k: Float): Receita {   // substitui (não acumula) os campos que o Auto controla
        val f = k / 100f
        return receita.copy(
            cor = receita.cor.copy(brilho = (a.brilho * f).roundToInt().toFloat(), contraste = (a.contraste * f).roundToInt().toFloat(), saturacao = (a.saturacao * f).roundToInt().toFloat()),
            tom = receita.tom.copy(realces = (a.tom.realces * f).roundToInt().toFloat(), sombras = (a.tom.sombras * f).roundToInt().toFloat()))
    }
    fun auto() {
        val t = trabalho ?: return
        escopo.launch {
            val a = withContext(Dispatchers.Default) { Tom.auto(t) }
            autoBase = a; autoIntensidade = 100f; registra(aplicaAuto(a, 100f)); Telemetria.evento("editor_auto")
        }
    }

    fun salvar() {
        if (salvando) return
        salvando = true
        escopo.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cheia = Edicao.carregar(ctx, midia.uri, Edicao.LADO_MAX_COPIA) ?: error("não decodificou")
                    val comGeo = Edicao.aplicaGeometria(cheia, receita.geo); if (comGeo !== cheia) cheia.recycle()
                    val comFundo = if (!receita.fundo.neutro) { val m = Fundo.segmentar(ctx, comGeo); if (m != null && m.cobertura >= 0.02f) Fundo.aplicar(comGeo, m, receita.fundo) else comGeo } else comGeo
                    if (comFundo !== comGeo) comGeo.recycle()
                    val comTom = Tom.aplicar(comFundo, receita.tom); if (comTom !== comFundo) comFundo.recycle()
                    val pronta = Edicao.aplicaCor(comTom, receita.cor); if (pronta !== comTom) comTom.recycle()
                    val png = receita.fundo.modo == Fundo.Modo.Remover
                    val s = Edicao.salvarCopia(ctx, midia, pronta, png); pronta.recycle()
                    val id = s.uri.lastPathSegment?.toLongOrNull() ?: 0L
                    if (id > 0L) runCatching { Indice.get(ctx).guardaEdicao(id, midia.id, receita.toJson()) }
                    s
                }
            }
            salvando = false
            r.onSuccess { s ->
                Telemetria.evento("editor_salvou", mapOf("larg" to s.largura, "alt" to s.altura, "filtro" to receita.cor.filtro, "recorte" to !receita.geo.neutra, "fundo" to receita.fundo.modo.name, "tom" to !receita.tom.neutro))
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
    val ocupado = segmentando || salvando

    Column(Modifier.fillMaxSize().background(Tema.Fundo).statusBarsPadding().navigationBarsPadding()) {
        // ---- topo ----
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { sair() }) { Icon(Icons.Filled.Close, contentDescription = "Descartar", tint = Tema.Texto) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { desfazer() }, enabled = posHist > 0) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Desfazer", tint = if (posHist > 0) Tema.Texto else Tema.Texto2) }
            IconButton(onClick = { refazer() }, enabled = posHist < historico.value.size - 1) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Refazer", tint = if (posHist < historico.value.size - 1) Tema.Texto else Tema.Texto2) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { salvar() }, enabled = mudou() && !ocupado && previa != null, modifier = Modifier.padding(end = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White, disabledContainerColor = Tema.Superficie, disabledContentColor = Tema.Texto2)) {
                if (salvando) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("Salvar", fontSize = 14.sp)
            }
        }

        // ---- prévia ----
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).padding(12.dp), contentAlignment = Alignment.Center) {
            when {
                erro != null -> Text(erro!!, color = Tema.Texto2, modifier = Modifier.padding(24.dp))
                previa == null -> CircularProgressIndicator(color = Tema.Coral)
                grupo == Grupo.Recortar -> previaOrientada?.let { b -> Recorte(b, receita.geo, proporcao, densidade.density, filtroCor) { novaGeo -> registra(receita.copy(geo = novaGeo)) } }
                else -> {
                    val exibida = if (comparando) previaGeo else (previaCpu ?: previaGeo ?: previa)
                    exibida?.let { b ->
                        Box(Modifier.fillMaxSize().pointerInput(b) {
                            awaitEachGesture {   // segurar 350 ms sem mover = comparar com a original
                                val baixo = awaitFirstDown(); var moveu = false; val ini = System.currentTimeMillis()
                                do { val ev = awaitPointerEvent(); if (ev.changes.any { abs(it.position.x - baixo.position.x) > 24f || abs(it.position.y - baixo.position.y) > 24f }) moveu = true
                                    if (!moveu && !comparando && System.currentTimeMillis() - ini >= 350) comparando = true
                                } while (ev.changes.any { it.pressed })
                                comparando = false
                            }
                        }, contentAlignment = Alignment.Center) {
                            if (receita.fundo.modo == Fundo.Modo.Remover && !comparando) Xadrez(Modifier.fillMaxSize())
                            Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, colorFilter = filtroCor, modifier = Modifier.fillMaxSize())
                            if (comparando) Rotulo("Original", Modifier.align(Alignment.TopCenter))
                            if (segmentando) Row(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = Tema.Coral, strokeWidth = 2.dp); Text("Separando pessoa…", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                            }
                            if (semPessoa && !receita.fundo.neutro) Rotulo("Não achei pessoa nesta foto", Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }

        // ---- linha de informação + AUTO ----
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (receita.fundo.modo == Fundo.Modo.Remover) "Cópia em PNG com transparência · até 4096 px" else "Cópia em JPEG · até 4096 px · a original fica intacta", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { auto() }, enabled = trabalho != null && !ocupado) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = Tema.Coral, modifier = Modifier.size(18.dp)); Text("Auto", color = Tema.Coral, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
        autoBase?.let { a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Auto aplicado · Intensidade ${autoIntensidade.roundToInt()}", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.width(190.dp))
                Slider(value = autoIntensidade, onValueChange = { v -> autoIntensidade = v.roundToInt().toFloat(); receita = aplicaAuto(a, autoIntensidade) }, onValueChangeFinished = { registra(receita) },
                    valueRange = 0f..100f, modifier = Modifier.weight(1f).height(28.dp), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                TextButton(onClick = { autoBase = null }) { Text("Fechar", color = Tema.Texto2, fontSize = 12.sp) }
            }
        }

        // ---- painel contextual (144 dp) ----
        AnimatedVisibility(visible = grupo != null) {
            Box(Modifier.fillMaxWidth().height(144.dp).background(Tema.Superficie)) {
                when (val g = grupo) {
                    Grupo.Recortar -> PainelRecorte(receita.geo, proporcao, aoProporcao = { proporcao = it; registra(receita.copy(geo = receita.geo.copy(recorte = recorteCentral(previaOrientada, it)))) },
                        aoGirar = { registra(receita.copy(geo = receita.geo.copy(giros = (receita.geo.giros + 1) % 4, recorte = RectF(0f, 0f, 1f, 1f)))) },
                        aoEspelhar = { registra(receita.copy(geo = receita.geo.copy(espelhado = !receita.geo.espelhado, recorte = RectF(0f, 0f, 1f, 1f)))) },
                        aoEndireitar = { v -> receita = receita.copy(geo = receita.geo.copy(endireitar = v)) }, aoEndireitarFim = { registra(receita) },
                        aoRedefinir = { proporcao = 0f; registra(receita.copy(geo = Edicao.Geometria())) })
                    Grupo.Filtros -> PainelFiltros(miniatura, receita.cor, aoFiltro = { f -> registra(receita.copy(cor = receita.cor.copy(filtro = f, intensidade = 100f))) },
                        aoIntensidade = { v -> receita = receita.copy(cor = receita.cor.copy(intensidade = v)) }, aoIntensidadeFim = { registra(receita) })
                    Grupo.Fundo -> PainelFundo(receita.fundo, aoModo = { m -> registra(receita.copy(fundo = receita.fundo.copy(modo = m))) },
                        aoIntensidade = { v -> receita = receita.copy(fundo = receita.fundo.copy(intensidade = v)) }, aoIntensidadeFim = { registra(receita) },
                        aoCor = { c -> registra(receita.copy(fundo = receita.fundo.copy(modo = Fundo.Modo.Cor, cor = c))) })
                    null -> {}
                    else -> if (g != null) PainelSliders(g.parametros, receita, parametro, aoParametro = { parametro = it },
                        aoValor = { v -> receita = comValor(receita, parametro, v) }, aoValorFim = { registra(receita) },
                        aoZerar = { registra(comValor(receita, parametro, 0f)) })
                }
            }
        }

        // ---- faixa de grupos (72 dp, rolável) ----
        Row(Modifier.fillMaxWidth().height(72.dp).background(Tema.Fundo).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Grupo.values().forEach { g ->
                val ativo = grupo == g
                Column(Modifier.width(86.dp).fillMaxSize().clickable { grupo = if (ativo) null else g; if (g.parametros.isNotEmpty() && parametro !in g.parametros) parametro = g.parametros[0] },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(g.icone, contentDescription = null, tint = if (ativo) Tema.Coral else Tema.Texto, modifier = Modifier.size(24.dp))
                    Text(g.rotulo, color = if (ativo) Tema.Coral else Tema.Texto, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (confirmarSaida) AlertDialog(onDismissRequest = { confirmarSaida = false }, title = { Text("Descartar edições?") },
        text = { Text("A foto original não foi alterada.") },
        confirmButton = { TextButton(onClick = { confirmarSaida = false; fechar() }) { Text("Descartar", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { confirmarSaida = false }) { Text("Continuar editando") } })
}

@Composable
private fun Rotulo(texto: String, modifier: Modifier) {
    Text(texto, color = Color.White, fontSize = 13.sp, modifier = modifier.padding(8.dp).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Fundo xadrez (transparência) atrás da prévia no modo Remover. */
@Composable
private fun Xadrez(modifier: Modifier) {
    Canvas(modifier) {
        val lado = 16f * density; var y = 0f; var linha = 0
        while (y < size.height) { var x = 0f; var col = 0
            while (x < size.width) { drawRect(if ((linha + col) % 2 == 0) Color(0xFF2A2A2E) else Color(0xFF3A3A40), Offset(x, y), Size(lado, lado)); x += lado; col++ }
            y += lado; linha++ }
    }
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

/** Painel genérico: chips dos parâmetros do grupo + um slider por vez, valor com sinal, toque duplo ou Redefinir zera. */
@Composable
private fun PainelSliders(parametros: List<String>, receita: Receita, parametro: String, aoParametro: (String) -> Unit, aoValor: (Float) -> Unit, aoValorFim: () -> Unit, aoZerar: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            parametros.forEach { p -> Chip(p, parametro == p, marcado = valorDe(receita, p) != 0f) { aoParametro(p) } }
        }
        val v = valorDe(receita, parametro); val fx = faixa(parametro)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (v > 0 && fx.start < 0f) "+" else "") + v.roundToInt().toString(), color = Tema.Texto, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
            Slider(value = v, onValueChange = { aoValor(it.roundToInt().toFloat()) }, onValueChangeFinished = aoValorFim, valueRange = fx,
                modifier = Modifier.weight(1f).pointerInput(parametro) { detectTapGestures(onDoubleTap = { aoZerar() }) },
                colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
            TextButton(onClick = aoZerar, enabled = v != 0f) { Text("Redefinir", color = if (v != 0f) Tema.Texto2 else Color.Transparent, fontSize = 13.sp) }
        }
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

/** Fundo: Nenhum · Desfocar · P&B · Cor · Remover; intensidade no Desfocar; paleta no Cor. */
@Composable
private fun PainelFundo(f: Fundo.Parametros, aoModo: (Fundo.Modo) -> Unit, aoIntensidade: (Float) -> Unit, aoIntensidadeFim: () -> Unit, aoCor: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Fundo.Modo.Nenhum to "Nenhum", Fundo.Modo.Desfocar to "Desfocar", Fundo.Modo.PretoEBranco to "P&B", Fundo.Modo.Cor to "Cor", Fundo.Modo.Remover to "Remover").forEach { (m, r) ->
                Chip(r, f.modo == m) { aoModo(m) }
            }
        }
        when (f.modo) {
            Fundo.Modo.Desfocar -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.intensidade.roundToInt().toString(), color = Tema.Texto, fontSize = 14.sp, modifier = Modifier.width(44.dp))
                Slider(value = f.intensidade, onValueChange = { aoIntensidade(it.roundToInt().toFloat()) }, onValueChangeFinished = aoIntensidadeFim, valueRange = 0f..100f,
                    modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
            }
            Fundo.Modo.Cor -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CORES_FUNDO.forEach { c -> Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (f.cor == c) Tema.Coral else Color(0x33FFFFFF), CircleShape).clickable { aoCor(c) }) }
            }
            Fundo.Modo.Remover -> Text("A cópia sai em PNG com o fundo transparente.", color = Tema.Texto2, fontSize = 13.sp)
            Fundo.Modo.PretoEBranco -> Text("Só a pessoa fica colorida.", color = Tema.Texto2, fontSize = 13.sp)
            Fundo.Modo.Nenhum -> Text("Escolha o que fazer com o fundo. A pessoa é separada automaticamente.", color = Tema.Texto2, fontSize = 13.sp)
        }
    }
}

// ---------------- recorte com alças ----------------
/** coerceIn que não estoura quando os limites se cruzam. */
private fun entre(v: Float, a: Float, b: Float): Float = v.coerceIn(min(a, b), maxOf(a, b))

private enum class Alca { NENHUMA, MOVER, TL, TR, BL, BR, T, B, L, R }

@Composable
private fun Recorte(b: Bitmap, geo: Edicao.Geometria, proporcao: Float, densidade: Float, filtro: ColorFilter?, aoRecorte: (Edicao.Geometria) -> Unit) {
    var caixa by remember { mutableStateOf(IntSize.Zero) }
    var rc by remember(geo.recorte) { mutableStateOf(geo.recorte) }
    val zoom = Edicao.zoomEndireitar(b.width.toFloat(), b.height.toFloat(), geo.endireitar)
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
                val dx = d.x / img.width; val dy = d.y / img.height; val minL = 0.12f
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
                    if (proporcao > 0f) {
                        val alturaN = ((n.width() * img.width) / proporcao / img.height).coerceAtMost(1f)
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
            for (i in 1..2) {
                drawLine(Color(0x88FFFFFF), Offset(r.left + r.width * i / 3, r.top), Offset(r.left + r.width * i / 3, r.bottom), 1f)
                drawLine(Color(0x88FFFFFF), Offset(r.left, r.top + r.height * i / 3), Offset(r.right, r.top + r.height * i / 3), 1f)
            }
            val l = 24f * densidade; val g = 3f * densidade
            listOf(r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f), r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f)).forEach { (c, s) ->
                drawLine(Color.White, c, Offset(c.x + l * s.x, c.y), g); drawLine(Color.White, c, Offset(c.x, c.y + l * s.y), g)
            }
        }
    }
}
