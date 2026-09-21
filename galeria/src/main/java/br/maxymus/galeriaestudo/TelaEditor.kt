package br.maxymus.galeriaestudo

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.FilterTiltShift
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private typealias Receita = Edicao.Receita

/** Grupos da faixa (Astra 20/09): ordem fixa, nome e ícone sempre visíveis. Marcações e Perspectiva entram quando prontos. */
private enum class GrupoEditor(val rotulo: String, val icone: ImageVector, val parametros: List<String>) {
    Luz("Luz", Icons.Filled.WbSunny, listOf("Exposição", "Brilho", "Contraste", "Realces", "Sombras", "Brancos", "Pretos")),
    Cor("Cor", Icons.Filled.Palette, listOf("Temperatura", "Matiz", "Saturação", "Conta-gotas", "HSL")),
    Recortar("Recortar", Icons.Filled.Crop, emptyList()),
    Filtros("Filtros", Icons.Filled.PhotoFilter, emptyList()),
    Detalhe("Detalhe", Icons.Filled.Details, listOf("Nitidez", "Textura", "Clareza", "Vinheta", "Granulação")),
    Fundo("Fundo", Icons.Filled.Portrait, emptyList()),
    Local("Local", Icons.Filled.FilterTiltShift, emptyList()),
    Corrigir("Corrigir", Icons.Filled.Healing, listOf("Pele", "Cicatrizar"))
}
private val PROPORCOES = listOf("Livre" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
/** Paleta fotográfica do Astra (21/09): âmbar, laranja, coral, rosa, magenta, violeta, azul, ciano, teal, verde. */
private val CORES_LOCAL = listOf(0xFFFFC107, 0xFFFF7A1A, 0xFFFF575F, 0xFFFF7EA8, 0xFFE040FB, 0xFF7C4DFF, 0xFF3D6BFF, 0xFF26C6DA, 0xFF1E8E7E, 0xFF4CAF50).map { it.toInt() }
private val CORES_MARCACAO = listOf(0xFFFF575F to "Coral", 0xFF22D3EE to "Ciano", 0xFFA3E635 to "Verde-lima", 0xFFFFFFFF to "Branco", 0xFF111114 to "Preto").map { it.first.toInt() to it.second }
private val CORES_FUNDO = listOf(0xFFFFFFFF, 0xFF111114, 0xFF8A8A92, 0xFFFF575F, 0xFF3D6BFF, 0xFF3D8A4A, 0xFFEFBD45).map { it.toInt() }
private const val LADO_TRABALHO = 1024

private fun valorDe(r: Receita, p: String): Float = when (p) {
    "Brilho" -> r.cor.brilho; "Contraste" -> r.cor.contraste; "Saturação" -> r.cor.saturacao; "Temperatura" -> r.cor.temperatura; "Matiz" -> r.cor.matiz
    "Realces" -> r.tom.realces; "Sombras" -> r.tom.sombras; "Brancos" -> r.tom.brancos; "Pretos" -> r.tom.pretos
    "Nitidez" -> r.tom.nitidez; "Vinheta" -> r.tom.vinheta; "Granulação" -> r.tom.granulacao; "Pele" -> r.fundo.pele
    "Exposição" -> r.cor.exposicao; "Textura" -> r.tom.textura; "Clareza" -> r.tom.clareza; else -> 0f
}
private fun comValor(r: Receita, p: String, v: Float): Receita = when (p) {
    "Brilho" -> r.copy(cor = r.cor.copy(brilho = v)); "Contraste" -> r.copy(cor = r.cor.copy(contraste = v)); "Saturação" -> r.copy(cor = r.cor.copy(saturacao = v))
    "Temperatura" -> r.copy(cor = r.cor.copy(temperatura = v)); "Matiz" -> r.copy(cor = r.cor.copy(matiz = v))
    "Realces" -> r.copy(tom = r.tom.copy(realces = v)); "Sombras" -> r.copy(tom = r.tom.copy(sombras = v)); "Brancos" -> r.copy(tom = r.tom.copy(brancos = v)); "Pretos" -> r.copy(tom = r.tom.copy(pretos = v))
    "Nitidez" -> r.copy(tom = r.tom.copy(nitidez = v)); "Vinheta" -> r.copy(tom = r.tom.copy(vinheta = v)); "Granulação" -> r.copy(tom = r.tom.copy(granulacao = v))
    "Pele" -> r.copy(fundo = r.fundo.copy(pele = v))
    "Exposição" -> r.copy(cor = r.cor.copy(exposicao = v)); "Textura" -> r.copy(tom = r.tom.copy(textura = v)); "Clareza" -> r.copy(tom = r.tom.copy(clareza = v)); else -> r
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
    // motor do recorte: preferência global (só muda quando uma escolha DÁ CERTO) e, dentro da foto, campo da receita
    val prefs = remember { ctx.getSharedPreferences("editor", Context.MODE_PRIVATE) }
    val receitaBase = remember {
        val m = runCatching { Fundo.Motor.valueOf(prefs.getString("motor", Fundo.Motor.Padrao.name)!!) }.getOrDefault(Fundo.Motor.Padrao)
        Receita(fundo = Fundo.Parametros(motor = m))
    }
    var receita by remember { mutableStateOf(receitaBase) }
    val historico = remember { mutableStateOf(listOf(receitaBase)) }
    var posHist by remember { mutableStateOf(0) }
    var grupo by remember { mutableStateOf<GrupoEditor?>(null) }
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
    var avisoMotor by remember { mutableStateOf<String?>(null) }
    var baixaJob by remember { mutableStateOf<Job?>(null) }
    var confirmarDados by remember { mutableStateOf<Fundo.Motor?>(null) }
    val estadoModelo by IsnetOnnx.estado.collectAsState()
    LaunchedEffect(Unit) { IsnetOnnx.conferir(ctx) }
    var autoBase by remember { mutableStateOf<Tom.Auto?>(null) }
    var autoIntensidade by remember { mutableStateOf(100f) }
    var pegandoBranco by remember { mutableStateOf(false) }
    var caixaPrevia by remember { mutableStateOf(IntSize.Zero) }
    var faixaHsl by remember { mutableStateOf(0) }
    var refinando by remember { mutableStateOf(false) }
    var pincelAdiciona by remember { mutableStateOf(true) }
    var pincelDp by remember { mutableStateOf(24f) }
    var tracoAtual by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var mascaraVisual by remember { mutableStateOf<Bitmap?>(null) }
    var plenaTrabalho by remember { mutableStateOf<FloatArray?>(null) }   // máscara de pessoa refinada, no tamanho do trabalho
    var localSel by remember { mutableStateOf(-1) }
    var localAba by remember { mutableStateOf("Forma") }
    var localParam by remember { mutableStateOf(Local.SLIDERS[0]) }
    var verMascara by remember { mutableStateOf(false) }
    var escolhendoTipo by remember { mutableStateOf(false) }
    var localVisual by remember { mutableStateOf<Bitmap?>(null) }
    var escolhendoCor by remember { mutableStateOf<String?>(null) }   // "A", "B" ou "Marcacao"
    var corMarcacao by remember { mutableStateOf(prefs.getInt("cor_marcacao", 0xFFFF575F.toInt())) }
    var curando by remember { mutableStateOf(false) }
    var curaDp by remember { mutableStateOf(24f) }
    var campoHsl by remember { mutableStateOf("Saturação") }

    LaunchedEffect(midia.id) {
        val b = withContext(Dispatchers.IO) { runCatching { Edicao.carregar(ctx, midia.uri, Edicao.LADO_MAX_PREVIA) }.getOrNull() }
        if (b == null) { erro = "Não consegui abrir esta foto para editar."; Telemetria.evento("erro", mapOf("onde" to "editor_carregar")) } else previa = b
    }
    LaunchedEffect(previa, receita.geo.giros, receita.geo.espelhado) {
        val p = previa ?: return@LaunchedEffect
        previaOrientada = withContext(Dispatchers.Default) { Edicao.aplicaGeometria(p, Edicao.Geometria(receita.geo.giros, receita.geo.espelhado)) }
    }
    // geometria muda → recalcula prévia, bitmap de trabalho, miniatura e invalida a máscara (a pessoa mudou de lugar)
    LaunchedEffect(previa, receita.geo, grupo == GrupoEditor.Recortar) {
        val p = previa ?: return@LaunchedEffect
        if (grupo == GrupoEditor.Recortar) return@LaunchedEffect
        val g = withContext(Dispatchers.Default) { Edicao.aplicaGeometria(p, receita.geo) }
        previaGeo = g; mascara = null
        trabalho = withContext(Dispatchers.Default) { val esc = LADO_TRABALHO.toFloat() / maxOf(g.width, g.height); if (esc >= 1f) g else Bitmap.createScaledBitmap(g, (g.width * esc).roundToInt().coerceAtLeast(1), (g.height * esc).roundToInt().coerceAtLeast(1), true) }
        miniatura = withContext(Dispatchers.Default) { val t = trabalho!!; val esc = 160f / maxOf(t.width, t.height); if (esc >= 1f) t else Bitmap.createScaledBitmap(t, (t.width * esc).roundToInt().coerceAtLeast(1), (t.height * esc).roundToInt().coerceAtLeast(1), true) }
    }
    // fundo + tom em CPU, com debounce; máscara sob demanda (uma vez por geometria)
    LaunchedEffect(trabalho, receita.tom, receita.fundo, receita.hsl, receita.local, receita.cura) {
        val t = trabalho ?: return@LaunchedEffect
        val precisaPessoa = !receita.fundo.neutro || receita.local.mascaras.any { it.tipo == Local.Tipo.Pessoa }
        if (receita.tom.neutro && receita.fundo.neutro && receita.hsl.neutro && receita.local.neutro && receita.cura.neutro && !precisaPessoa) { previaCpu = null; return@LaunchedEffect }
        delay(120)
        if (precisaPessoa && mascara?.pedido != receita.fundo.motor) {
            segmentando = true
            val alvo = receita.fundo.motor
            val m = withContext(Dispatchers.Default) { runCatching { Fundo.segmentar(ctx, t, alvo) }.getOrNull() }
            segmentando = false
            if (m == null || m.cobertura < 0.02f) { semPessoa = true; Telemetria.evento("editor_fundo", mapOf("pessoa" to false)) }
            else {
                semPessoa = false; mascara = m
                avisoMotor = if (m.motor != alvo) "Recorte ${alvo.rotulo} indisponível. Usando ${m.motor.rotulo} nesta foto." else null
                if (m.motor == alvo) prefs.edit().putString("motor", alvo.name).apply()   // só uma escolha que deu certo vira preferência
            }
        }
        val m = mascara
        val (resultado, plena) = withContext(Dispatchers.Default) {
            val base = Cura.aplicar(t, receita.cura)
            val pl = if (precisaPessoa && m != null) Fundo.plenaDe(base, m, receita.fundo.tracos) else null
            val comFundo = if (!receita.fundo.neutro && m != null) Fundo.aplicar(base, m, receita.fundo) else base
            if (comFundo !== base && base !== t) base.recycle()
            val comTom = Tom.aplicar(comFundo, receita.tom); if (comTom !== comFundo && comFundo !== t) comFundo.recycle()
            val comHsl = Hsl.aplicar(comTom, receita.hsl); if (comHsl !== comTom && comTom !== t) comTom.recycle()
            val comLocal = Local.aplicar(comHsl, receita.local, pl); if (comLocal !== comHsl && comHsl !== t) comHsl.recycle()
            comLocal to pl
        }
        previaCpu = if (resultado === t) null else resultado; plenaTrabalho = plena
    }
    LaunchedEffect(refinando, trabalho, mascara, receita.fundo.tracos) {
        if (!refinando) { mascaraVisual = null; return@LaunchedEffect }
        val t = trabalho; val m = mascara
        if (t == null || m == null) return@LaunchedEffect
        mascaraVisual = withContext(Dispatchers.Default) { Fundo.visual(t, m, receita.fundo.tracos) }
    }
    LaunchedEffect(grupo, localAba, verMascara, localSel, receita.local, trabalho, plenaTrabalho, corMarcacao) {
        val t = trabalho; val m = receita.local.mascaras.getOrNull(localSel)
        if (grupo != GrupoEditor.Local || m == null || t == null || !(localAba == "Forma" || verMascara)) { localVisual = null; return@LaunchedEffect }
        val pl = plenaTrabalho
        localVisual = withContext(Dispatchers.Default) {
            val w = t.width; val h = t.height
            val pesos = FloatArray(w * h) { k -> Local.peso(m, (k % w) / (w - 1f), (k / w) / (h - 1f), pl, w, h) }
            Fundo.visualDe(pesos, w, h, 0.35f, corMarcacao)
        }
    }
    fun mudou() = receita != receitaBase
    fun registra(nova: Receita) { if (nova == receita) return; val h = historico.value.take(posHist + 1) + nova; historico.value = h; posHist = h.size - 1; receita = nova }
    fun desfazer() { if (posHist > 0) { posHist--; receita = historico.value[posHist] } }
    fun refazer() { if (posHist < historico.value.size - 1) { posHist++; receita = historico.value[posHist] } }
    /** Troca o motor do recorte: entra no histórico como uma operação; o Alta baixa o modelo antes (confirma em dados móveis). */
    fun aplicaMotor(m: Fundo.Motor) {
        avisoMotor = null
        registra(receita.copy(fundo = receita.fundo.copy(motor = m)))
    }
    fun baixaModelo(m: Fundo.Motor) {
        baixaJob = escopo.launch {
            val ok = withContext(Dispatchers.IO) { IsnetOnnx.baixar(ctx) }
            baixaJob = null
            if (ok) aplicaMotor(m) else avisoMotor = (IsnetOnnx.estado.value as? IsnetOnnx.Estado.Erro)?.let { "Não consegui baixar o modelo. Mantido: ${receita.fundo.motor.rotulo}." }
        }
    }
    fun escolheMotor(m: Fundo.Motor) {
        if (m == receita.fundo.motor) { if (avisoMotor == null) return; avisoMotor = null; mascara = null }   // mesmo motor após falha = tentar de novo
        if (m == Fundo.Motor.Alta && !IsnetOnnx.conferir(ctx)) {
            val medida = runCatching { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered }.getOrDefault(true)
            if (medida) confirmarDados = m else baixaModelo(m)
            return
        }
        aplicaMotor(m)
    }
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

    /** Retângulo onde a imagem b é desenhada (Fit) dentro da caixa da prévia: [ox, oy, dw, dh] em px, ou null. */
    fun encaixe(b: Bitmap): FloatArray? {
        val cw = caixaPrevia.width.toFloat(); val ch = caixaPrevia.height.toFloat(); if (cw <= 0f || ch <= 0f) return null
        val esc = min(cw / b.width, ch / b.height); val dw = b.width * esc; val dh = b.height * esc
        return floatArrayOf((cw - dw) / 2f, (ch - dh) / 2f, dw, dh)
    }
    fun fechaCura(pontos: List<Offset>, b: Bitmap) {
        val e = encaixe(b) ?: return; if (pontos.isEmpty()) return
        val raioPx = curaDp * densidade.density / 2f
        val norm = ArrayList<Pair<Float, Float>>(); var ultimo: Offset? = null
        for (p in pontos) { if (ultimo != null && (abs(p.x - ultimo.x) + abs(p.y - ultimo.y)) < raioPx * 0.5f) continue; ultimo = p
            val nx = (p.x - e[0]) / e[2]; val ny = (p.y - e[1]) / e[3]; if (nx in 0f..1f && ny in 0f..1f) norm += nx to ny }
        if (norm.isEmpty()) return
        registra(receita.copy(cura = receita.cura.copy(pinceladas = receita.cura.pinceladas + Cura.Pincelada(norm, raioPx / e[2]))))
        Telemetria.evento("editor_cura", mapOf("pontos" to norm.size))
    }
    fun trocaLocal(i: Int, nova: Local.Mascara) { receita = receita.copy(local = receita.local.copy(mascaras = receita.local.mascaras.toMutableList().also { it[i] = nova })) }

    /** Fecha uma pincelada do refinamento: pontos de tela → normalizados sobre a imagem exibida (Fit); raio em fração da largura. */
    fun fechaTraco(pontos: List<Offset>, b: Bitmap) {
        val cw = caixaPrevia.width.toFloat(); val ch = caixaPrevia.height.toFloat(); if (cw <= 0f || ch <= 0f || pontos.isEmpty()) return
        val esc = min(cw / b.width, ch / b.height); val dw = b.width * esc; val dh = b.height * esc; val ox = (cw - dw) / 2f; val oy = (ch - dh) / 2f
        val raioPx = pincelDp * densidade.density / 2f
        val norm = ArrayList<Pair<Float, Float>>(); var ultimo: Offset? = null
        for (p in pontos) { if (ultimo != null && (abs(p.x - ultimo.x) + abs(p.y - ultimo.y)) < raioPx * 0.35f) continue; ultimo = p
            val nx = (p.x - ox) / dw; val ny = (p.y - oy) / dh; if (nx in -0.05f..1.05f && ny in -0.05f..1.05f) norm += nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f) }
        if (norm.isEmpty()) return
        val t = Fundo.Traco(norm, raioPx / dw, pincelAdiciona)
        registra(receita.copy(fundo = receita.fundo.copy(tracos = receita.fundo.tracos + t)))
        Telemetria.evento("editor_pincel_mascara", mapOf("adiciona" to pincelAdiciona, "pontos" to norm.size))
    }

    /** Conta-gotas: a área tocada deveria ser cinza/branca; acha temperatura e matiz que a neutralizam (mesma matemática dos sliders). */
    fun pegaBranco(nx: Float, ny: Float) {
        val t = trabalho ?: return
        val cx = (nx * (t.width - 1)).roundToInt().coerceIn(0, t.width - 1); val cy = (ny * (t.height - 1)).roundToInt().coerceIn(0, t.height - 1)
        var r = 0f; var g = 0f; var b = 0f; var c = 0
        for (dy in -2..2) for (dx in -2..2) { val x = cx + dx; val y = cy + dy; if (x < 0 || y < 0 || x >= t.width || y >= t.height) continue
            val p = t.getPixel(x, y); r += (p shr 16 and 255); g += (p shr 8 and 255); b += (p and 255); c++ }
        if (c == 0 || g <= 0f) return
        r /= c; g /= c; b /= c
        val tq = ((b - r) / (0.18f * (r + b).coerceAtLeast(1f)))
        val rc = r * (1f + 0.18f * tq); val bc = b * (1f - 0.18f * tq); val alvo = (rc + bc) / 2f
        val mq = (alvo / g - 1f) / 0.12f
        registra(receita.copy(cor = receita.cor.copy(temperatura = (tq * 100f).coerceIn(-100f, 100f).roundToInt().toFloat(), matiz = (mq * 100f).coerceIn(-100f, 100f).roundToInt().toFloat())))
        pegandoBranco = false; Telemetria.evento("editor_conta_gotas")
    }

    fun salvar() {
        if (salvando) return
        salvando = true
        escopo.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cheia = Edicao.carregar(ctx, midia.uri, Edicao.LADO_MAX_COPIA) ?: error("não decodificou")
                    val comGeo = Edicao.aplicaGeometria(cheia, receita.geo); if (comGeo !== cheia) cheia.recycle()
                    val base = Cura.aplicar(comGeo, receita.cura); if (base !== comGeo) comGeo.recycle()
                    val precisaPessoa = !receita.fundo.neutro || receita.local.mascaras.any { it.tipo == Local.Tipo.Pessoa }
                    val m = if (precisaPessoa) Fundo.segmentar(ctx, base, receita.fundo.motor)?.takeIf { it.cobertura >= 0.02f } else null
                    val pl = if (m != null) Fundo.plenaDe(base, m, receita.fundo.tracos) else null
                    val comFundo = if (!receita.fundo.neutro && m != null) Fundo.aplicar(base, m, receita.fundo) else base
                    if (comFundo !== base) base.recycle()
                    val comTom = Tom.aplicar(comFundo, receita.tom); if (comTom !== comFundo) comFundo.recycle()
                    val comHsl = Hsl.aplicar(comTom, receita.hsl); if (comHsl !== comTom) comTom.recycle()
                    val comLocal = Local.aplicar(comHsl, receita.local, pl); if (comLocal !== comHsl) comHsl.recycle()
                    val pronta = Edicao.aplicaCor(comLocal, receita.cor); if (pronta !== comLocal) comLocal.recycle()
                    val png = receita.fundo.modo == Fundo.Modo.Remover
                    val s = Edicao.salvarCopia(ctx, midia, pronta, png); pronta.recycle()
                    val id = s.uri.lastPathSegment?.toLongOrNull() ?: 0L
                    if (id > 0L) runCatching { Indice.get(ctx).guardaEdicao(id, midia.id, receita.toJson()) }
                    s
                }
            }
            salvando = false
            r.onSuccess { s ->
                Telemetria.evento("editor_salvou", mapOf("larg" to s.largura, "alt" to s.altura, "filtro" to receita.cor.filtro, "recorte" to !receita.geo.neutra, "fundo" to receita.fundo.modo.name, "tom" to !receita.tom.neutro, "motor" to receita.fundo.motor.name))
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
                grupo == GrupoEditor.Recortar -> previaOrientada?.let { b -> Recorte(b, receita.geo, proporcao, densidade.density, filtroCor) { novaGeo -> registra(receita.copy(geo = novaGeo)) } }
                else -> {
                    val exibida = if (comparando) previaGeo else (previaCpu ?: previaGeo ?: previa)
                    exibida?.let { b ->
                        Box(Modifier.fillMaxSize().onSizeChanged { caixaPrevia = it }.pointerInput(b, pegandoBranco, refinando, pincelAdiciona, pincelDp, curando, curaDp, grupo, localAba, localSel) {
                            if (pegandoBranco) detectTapGestures { pos ->
                                val cw = caixaPrevia.width.toFloat(); val ch = caixaPrevia.height.toFloat(); if (cw <= 0f || ch <= 0f) return@detectTapGestures
                                val esc = min(cw / b.width, ch / b.height); val dw = b.width * esc; val dh = b.height * esc
                                val nx = (pos.x - (cw - dw) / 2f) / dw; val ny = (pos.y - (ch - dh) / 2f) / dh
                                if (nx in 0f..1f && ny in 0f..1f) pegaBranco(nx, ny)
                            } else if (curando) awaitEachGesture {   // cicatrizar: toque = mancha; arraste = pincelada; solta = um passo
                                val baixo = awaitFirstDown(); var pontos = listOf(baixo.position); tracoAtual = pontos
                                do { val ev = awaitPointerEvent()
                                    if (ev.changes.count { it.pressed } >= 2) { pontos = emptyList(); tracoAtual = pontos; break }
                                    val ch = ev.changes.firstOrNull { it.pressed }; if (ch != null) { pontos = pontos + ch.position; tracoAtual = pontos; ch.consume() }
                                } while (ev.changes.any { it.pressed })
                                if (pontos.isNotEmpty()) fechaCura(pontos, b); tracoAtual = emptyList()
                            } else if (grupo == GrupoEditor.Local && (localAba == "Forma" || localAba == "Cor") && localSel >= 0) awaitEachGesture {   // alças da máscara
                                val baixo = awaitFirstDown(); val e = encaixe(b); val m0 = receita.local.mascaras.getOrNull(localSel)
                                if (e == null || m0 == null || m0.tipo == Local.Tipo.Pessoa) return@awaitEachGesture
                                val toque = 24f * densidade.density
                                fun tela(nx: Float, ny: Float) = Offset(e[0] + nx * e[2], e[1] + ny * e[3])
                                fun perto(o: Offset) = abs(baixo.position.x - o.x) < toque && abs(baixo.position.y - o.y) < toque
                                val alvo = when (m0.tipo) {
                                    Local.Tipo.Radial -> if (perto(tela(m0.cx + m0.rx, m0.cy))) "rx" else if (perto(tela(m0.cx, m0.cy + m0.ry))) "ry" else "mover"
                                    Local.Tipo.Linear -> if (perto(tela(m0.x1, m0.y1))) "p1" else if (perto(tela(m0.x2, m0.y2))) "p2" else "mover"
                                    else -> "mover"
                                }
                                var atual: Local.Mascara = m0
                                do { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.pressed }
                                    if (ch != null) { val d = ch.positionChange(); val dx = d.x / e[2]; val dy = d.y / e[3]
                                        atual = when (alvo) {
                                            "rx" -> atual.copy(rx = (atual.rx + dx).coerceIn(0.03f, 1f)); "ry" -> atual.copy(ry = (atual.ry + dy).coerceIn(0.03f, 1f))
                                            "p1" -> atual.copy(x1 = (atual.x1 + dx).coerceIn(0f, 1f), y1 = (atual.y1 + dy).coerceIn(0f, 1f)); "p2" -> atual.copy(x2 = (atual.x2 + dx).coerceIn(0f, 1f), y2 = (atual.y2 + dy).coerceIn(0f, 1f))
                                            else -> if (atual.tipo == Local.Tipo.Radial) atual.copy(cx = (atual.cx + dx).coerceIn(0f, 1f), cy = (atual.cy + dy).coerceIn(0f, 1f))
                                                    else atual.copy(x1 = atual.x1 + dx, y1 = atual.y1 + dy, x2 = atual.x2 + dx, y2 = atual.y2 + dy)
                                        }
                                        trocaLocal(localSel, atual); ch.consume() }
                                } while (ev.changes.any { it.pressed })
                                registra(receita)
                            } else if (refinando) awaitEachGesture {   // um dedo pinta a máscara; solta = um traço (uma entrada de desfazer)
                                val baixo = awaitFirstDown(); var pontos = listOf(baixo.position); tracoAtual = pontos
                                do { val ev = awaitPointerEvent()
                                    if (ev.changes.count { it.pressed } >= 2) { pontos = emptyList(); tracoAtual = pontos; break }   // segundo dedo cancela o traço
                                    val ch = ev.changes.firstOrNull { it.pressed }; if (ch != null) { pontos = pontos + ch.position; tracoAtual = pontos; ch.consume() }
                                } while (ev.changes.any { it.pressed })
                                if (pontos.isNotEmpty()) fechaTraco(pontos, b); tracoAtual = emptyList()
                            } else awaitEachGesture {   // segurar 350 ms sem mover = comparar com a original
                                val baixo = awaitFirstDown(); var moveu = false; val ini = System.currentTimeMillis()
                                do { val ev = awaitPointerEvent(); if (ev.changes.any { abs(it.position.x - baixo.position.x) > 24f || abs(it.position.y - baixo.position.y) > 24f }) moveu = true
                                    if (!moveu && !comparando && System.currentTimeMillis() - ini >= 350) comparando = true
                                } while (ev.changes.any { it.pressed })
                                comparando = false
                            }
                        }, contentAlignment = Alignment.Center) {
                            if (receita.fundo.modo == Fundo.Modo.Remover && !comparando) Xadrez(Modifier.fillMaxSize())
                            Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, colorFilter = filtroCor, modifier = Modifier.fillMaxSize())
                            if (refinando && !comparando) {
                                mascaraVisual?.let { mv -> Image(bitmap = mv.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                                Canvas(Modifier.fillMaxSize()) { val r = pincelDp * density / 2f; tracoAtual.forEach { drawCircle(if (pincelAdiciona) Color(0x99FF575F) else Color(0x99FFFFFF), r, it) } }
                                Rotulo(if (pincelAdiciona) "Pinte o que deve ficar NÍTIDO" else "Pinte o que deve ir para o DESFOQUE", Modifier.align(Alignment.TopCenter))
                            }
                            if (grupo == GrupoEditor.Local && localSel >= 0 && !comparando) {
                                localVisual?.let { lv -> Image(bitmap = lv.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                                val mk = receita.local.mascaras.getOrNull(localSel); val e = encaixe(b)
                                if (mk != null && e != null && (localAba == "Forma" || localAba == "Cor")) Canvas(Modifier.fillMaxSize()) {
                                    fun tela(nx: Float, ny: Float) = Offset(e[0] + nx * e[2], e[1] + ny * e[3])
                                    val rA = 12f * density
                                    when (mk.tipo) {
                                        Local.Tipo.Radial -> {
                                            drawOval(Color.White, topLeft = tela(mk.cx - mk.rx, mk.cy - mk.ry), size = Size(2 * mk.rx * e[2], 2 * mk.ry * e[3]), style = Stroke(1.5f * density))
                                            drawCircle(Tema.Coral, rA, tela(mk.cx + mk.rx, mk.cy)); drawCircle(Tema.Coral, rA, tela(mk.cx, mk.cy + mk.ry)); drawCircle(Color.White, rA * 0.45f, tela(mk.cx, mk.cy))
                                        }
                                        Local.Tipo.Linear -> {
                                            drawLine(Color.White, tela(mk.x1, mk.y1), tela(mk.x2, mk.y2), 1.5f * density)
                                            drawCircle(Tema.Coral, rA, tela(mk.x1, mk.y1)); drawCircle(Color.White, rA, tela(mk.x2, mk.y2)); drawCircle(Tema.Coral, rA * 0.5f, tela(mk.x2, mk.y2))
                                        }
                                        else -> {}
                                    }
                                }
                            }
                            if (curando && !comparando) {
                                Canvas(Modifier.fillMaxSize()) { val r = curaDp * density / 2f; tracoAtual.forEach { drawCircle(Color(0x99FF575F), r, it) } }
                                Rotulo("Toque ou pinte a mancha", Modifier.align(Alignment.TopCenter))
                            }
                            if (comparando) Rotulo("Original", Modifier.align(Alignment.TopCenter))
                            if (pegandoBranco) Rotulo("Toque numa área que deveria ser branca ou cinza", Modifier.align(Alignment.TopCenter))
                            if (segmentando && mascara == null) Row(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    GrupoEditor.Recortar -> PainelRecorte(receita.geo, proporcao, aoProporcao = { proporcao = it; registra(receita.copy(geo = receita.geo.copy(recorte = recorteCentral(previaOrientada, it)))) },
                        aoGirar = { registra(receita.copy(geo = receita.geo.copy(giros = (receita.geo.giros + 1) % 4, recorte = RectF(0f, 0f, 1f, 1f)))) },
                        aoEspelhar = { registra(receita.copy(geo = receita.geo.copy(espelhado = !receita.geo.espelhado, recorte = RectF(0f, 0f, 1f, 1f)))) },
                        aoEndireitar = { v -> receita = receita.copy(geo = receita.geo.copy(endireitar = v)) }, aoEndireitarFim = { registra(receita) },
                        aoRedefinir = { proporcao = 0f; registra(receita.copy(geo = Edicao.Geometria())) })
                    GrupoEditor.Filtros -> PainelFiltros(miniatura, receita.cor, aoFiltro = { f -> registra(receita.copy(cor = receita.cor.copy(filtro = f, intensidade = 100f))) },
                        aoIntensidade = { v -> receita = receita.copy(cor = receita.cor.copy(intensidade = v)) }, aoIntensidadeFim = { registra(receita) })
                    GrupoEditor.Cor -> PainelCor(receita, parametro, aoParametro = { parametro = it; pegandoBranco = it == "Conta-gotas" },
                        aoValor = { v -> receita = comValor(receita, parametro, v) }, aoValorFim = { registra(receita) }, aoZerar = { registra(comValor(receita, parametro, 0f)) },
                        faixaHsl = faixaHsl, campoHsl = campoHsl, aoFaixaHsl = { faixaHsl = it }, aoCampoHsl = { campoHsl = it },
                        aoHsl = { v -> receita = receita.copy(hsl = receita.hsl.com(faixaHsl, campoHsl, v)) }, aoHslFim = { registra(receita) },
                        aoZerarHsl = { registra(receita.copy(hsl = Hsl.Parametros())) })
                    GrupoEditor.Local -> PainelLocal(receita.local, localSel, localAba, localParam, verMascara, escolhendoTipo,
                        aoSelecionar = { localSel = it; escolhendoTipo = false }, aoAba = { localAba = it }, aoParam = { localParam = it }, aoVerMascara = { verMascara = it }, aoEscolherTipo = { escolhendoTipo = it },
                        aoNova = { tipo -> val t = trabalho; val prop = if (t != null) t.width.toFloat() / t.height else 1f
                            if (receita.local.mascaras.size < Local.MAX) { registra(receita.copy(local = receita.local.copy(mascaras = receita.local.mascaras + Local.nova(tipo, prop)))); localSel = receita.local.mascaras.size - 1; localAba = "Forma" }; escolhendoTipo = false },
                        aoExcluir = { i -> registra(receita.copy(local = receita.local.copy(mascaras = receita.local.mascaras.filterIndexed { j, _ -> j != i }))); localSel = -1 },
                        aoInverter = { i -> val mk = receita.local.mascaras[i]; registra(receita.copy(local = receita.local.copy(mascaras = receita.local.mascaras.toMutableList().also { it[i] = mk.copy(invertida = !mk.invertida) }))) },
                        aoSuavidade = { v -> receita.local.mascaras.getOrNull(localSel)?.let { mk -> trocaLocal(localSel, mk.copy(suavidade = v)) } }, aoSuavidadeFim = { registra(receita) },
                        aoEscolherCor = { escolhendoCor = it },
                        aoForcaCor = { v -> receita.local.mascaras.getOrNull(localSel)?.let { mk -> trocaLocal(localSel, mk.copy(forcaCor = v)) } },
                        aoValor = { v -> receita.local.mascaras.getOrNull(localSel)?.let { mk -> trocaLocal(localSel, mk.com(localParam, v)) } }, aoValorFim = { registra(receita) },
                        aoZerar = { receita.local.mascaras.getOrNull(localSel)?.let { mk -> trocaLocal(localSel, mk.com(localParam, 0f)); registra(receita) } })
                    GrupoEditor.Corrigir -> PainelCorrigir(receita, parametro, curando, curaDp, aoParametro = { parametro = it; curando = it == "Cicatrizar" },
                        aoValor = { v -> receita = comValor(receita, "Pele", v) }, aoValorFim = { registra(receita) }, aoZerar = { registra(comValor(receita, "Pele", 0f)) },
                        aoCuraDp = { curaDp = it }, aoLimparCura = { registra(receita.copy(cura = Cura.Parametros())) })
                    GrupoEditor.Fundo -> PainelFundo(receita.fundo, aoModo = { m -> refinando = false; registra(receita.copy(fundo = receita.fundo.copy(modo = m))) },
                        aplicando = segmentando && mascara != null, estadoModelo = estadoModelo, avisoMotor = avisoMotor,
                        aoMotor = { escolheMotor(it) }, aoCancelarBaixa = { IsnetOnnx.cancelar(); baixaJob?.cancel(); baixaJob = null },
                        aoIntensidade = { v -> receita = receita.copy(fundo = receita.fundo.copy(intensidade = v)) }, aoIntensidadeFim = { registra(receita) },
                        aoCor = { c -> registra(receita.copy(fundo = receita.fundo.copy(modo = Fundo.Modo.Cor, cor = c))) },
                        refinando = refinando, pincelAdiciona = pincelAdiciona, pincelDp = pincelDp,
                        aoRefinar = { refinando = it }, aoPincelModo = { pincelAdiciona = it }, aoPincelDp = { pincelDp = it },
                        aoLimparTracos = { registra(receita.copy(fundo = receita.fundo.copy(tracos = emptyList()))) })
                    null -> {}
                    else -> if (g != null) PainelSliders(g.parametros, receita, parametro, aoParametro = { parametro = it },
                        aoValor = { v -> receita = comValor(receita, parametro, v) }, aoValorFim = { registra(receita) },
                        aoZerar = { registra(comValor(receita, parametro, 0f)) })
                }
            }
        }

        // ---- faixa de grupos (72 dp, rolável) ----
        Row(Modifier.fillMaxWidth().height(72.dp).background(Tema.Fundo).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            GrupoEditor.values().forEach { g ->
                val ativo = grupo == g
                Column(Modifier.width(86.dp).fillMaxSize().clickable { grupo = if (ativo) null else g; pegandoBranco = false; refinando = false; curando = false; verMascara = false; escolhendoTipo = false; if (g.parametros.isNotEmpty() && parametro !in g.parametros) parametro = g.parametros[0] },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(g.icone, contentDescription = null, tint = if (ativo) Tema.Coral else Tema.Texto, modifier = Modifier.size(24.dp))
                    Text(g.rotulo, color = if (ativo) Tema.Coral else Tema.Texto, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    escolhendoCor?.let { alvo ->
        val mk = receita.local.mascaras.getOrNull(localSel)
        FolhaCor(
            titulo = when (alvo) { "Marcacao" -> "Cor da marcação"; "B" -> "Segunda cor"; else -> "Cor da área" },
            cores = if (alvo == "Marcacao") CORES_MARCACAO else CORES_LOCAL.map { it to "" },
            atual = when (alvo) { "Marcacao" -> corMarcacao; "B" -> mk?.corB ?: 0; else -> mk?.corA ?: 0 },
            livre = alvo != "Marcacao",
            podeRemover = alvo == "B" && (mk?.corB ?: 0) != 0,
            podeTrocar = alvo == "B" && (mk?.corB ?: 0) != 0 && (mk?.corA ?: 0) != 0,
            aoFechar = { escolhendoCor = null },
            aoRemover = { mk?.let { trocaLocal(localSel, it.copy(corB = 0)); registra(receita) }; escolhendoCor = null },
            aoTrocar = { mk?.let { trocaLocal(localSel, it.copy(corA = it.corB, corB = it.corA)); registra(receita) }; escolhendoCor = null },
            aoCor = { c ->
                when (alvo) {
                    "Marcacao" -> { corMarcacao = c; prefs.edit().putInt("cor_marcacao", c).apply() }
                    "B" -> mk?.let { trocaLocal(localSel, it.copy(corB = c, forcaCor = if (it.forcaCor == 0f) 60f else it.forcaCor)); registra(receita) }
                    else -> mk?.let { trocaLocal(localSel, it.copy(corA = c, forcaCor = if (it.forcaCor == 0f) 60f else it.forcaCor)); registra(receita) }
                }
            })
    }
    if (confirmarSaida) AlertDialog(onDismissRequest = { confirmarSaida = false }, title = { Text("Descartar edições?") },
        text = { Text("A foto original não foi alterada.") },
        confirmButton = { TextButton(onClick = { confirmarSaida = false; fechar() }) { Text("Descartar", color = Tema.Coral) } },
        dismissButton = { TextButton(onClick = { confirmarSaida = false }) { Text("Continuar editando") } })
    confirmarDados?.let { m ->
        AlertDialog(onDismissRequest = { confirmarDados = null }, title = { Text("Baixar ${IsnetOnnx.MB} MB usando dados móveis?") },
            text = { Text("O recorte Alta precisa de um modelo que fica guardado no aparelho. Depois de baixado, funciona sem internet.") },
            confirmButton = { TextButton(onClick = { confirmarDados = null; baixaModelo(m) }) { Text("Baixar", color = Tema.Coral) } },
            dismissButton = { TextButton(onClick = { confirmarDados = null }) { Text("Agora não") } })
    }
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

/** Cor: Temperatura/Matiz/Saturação por slider; Conta-gotas (toque na prévia); HSL com 8 faixas × Matiz/Saturação/Luminância. */
@Composable
private fun PainelCor(receita: Receita, parametro: String, aoParametro: (String) -> Unit, aoValor: (Float) -> Unit, aoValorFim: () -> Unit, aoZerar: () -> Unit,
                      faixaHsl: Int, campoHsl: String, aoFaixaHsl: (Int) -> Unit, aoCampoHsl: (String) -> Unit, aoHsl: (Float) -> Unit, aoHslFim: () -> Unit, aoZerarHsl: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrupoEditor.Cor.parametros.forEach { p -> Chip(p, parametro == p, marcado = if (p == "HSL") !receita.hsl.neutro else valorDe(receita, p) != 0f) { aoParametro(p) } }
        }
        when (parametro) {
            "Conta-gotas" -> Text("Toque na foto numa área que deveria ser branca ou cinza neutro. A temperatura e o matiz se ajustam sozinhos.", color = Tema.Texto2, fontSize = 13.sp)
            "HSL" -> Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Hsl.CORES_UI.forEachIndexed { i, c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (faixaHsl == i) Tema.Coral else if (receita.hsl.alterada(i)) Color.White else Color(0x33FFFFFF), CircleShape).clickable { aoFaixaHsl(i) })
                    }
                    TextButton(onClick = aoZerarHsl, enabled = !receita.hsl.neutro) { Text("Redefinir", color = if (receita.hsl.neutro) Color.Transparent else Tema.Texto2, fontSize = 12.sp) }
                }
                val v = receita.hsl.valor(faixaHsl, campoHsl)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    listOf("Matiz", "Saturação", "Luminância").forEach { c ->
                        Text(c.take(3), color = if (campoHsl == c) Tema.Coral else Tema.Texto2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { aoCampoHsl(c) }.padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                    Text((if (v > 0) "+" else "") + v.roundToInt().toString(), color = Tema.Texto, fontSize = 13.sp, modifier = Modifier.width(36.dp))
                    Slider(value = v, onValueChange = { aoHsl(it.roundToInt().toFloat()) }, onValueChangeFinished = aoHslFim, valueRange = -100f..100f,
                        modifier = Modifier.weight(1f).height(28.dp), colors = SliderDefaults.colors(thumbColor = Color(Hsl.CORES_UI[faixaHsl]), activeTrackColor = Color(Hsl.CORES_UI[faixaHsl])))
                }
            }
            else -> {
                val v = valorDe(receita, parametro)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (v > 0) "+" else "") + v.roundToInt().toString(), color = Tema.Texto, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                    Slider(value = v, onValueChange = { aoValor(it.roundToInt().toFloat()) }, onValueChangeFinished = aoValorFim, valueRange = -100f..100f,
                        modifier = Modifier.weight(1f).pointerInput(parametro) { detectTapGestures(onDoubleTap = { aoZerar() }) },
                        colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                    TextButton(onClick = aoZerar, enabled = v != 0f) { Text("Redefinir", color = if (v != 0f) Tema.Texto2 else Color.Transparent, fontSize = 13.sp) }
                }
            }
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

/**
 * Fundo (desenho do Astra, 21/09): três linhas de 48 dp. 1 = modos Nenhum·Desfocar·P&B·Cor·Remover; 2 = controle do modo
 * + Refinar (pincel na máscara); 3 = seletor do motor do recorte ("Recorte: Padrão"), que vira download com progresso e
 * Cancelar quando o Alta ainda não está no aparelho, e vira aviso quando o motor pedido falha.
 */
@Composable
private fun PainelFundo(f: Fundo.Parametros, aoModo: (Fundo.Modo) -> Unit,
                        aplicando: Boolean, estadoModelo: IsnetOnnx.Estado, avisoMotor: String?,
                        aoMotor: (Fundo.Motor) -> Unit, aoCancelarBaixa: () -> Unit,
                        aoIntensidade: (Float) -> Unit, aoIntensidadeFim: () -> Unit, aoCor: (Int) -> Unit,
                        refinando: Boolean, pincelAdiciona: Boolean, pincelDp: Float, aoRefinar: (Boolean) -> Unit, aoPincelModo: (Boolean) -> Unit, aoPincelDp: (Float) -> Unit, aoLimparTracos: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Fundo.Modo.Nenhum to "Nenhum", Fundo.Modo.Desfocar to "Desfocar", Fundo.Modo.PretoEBranco to "P&B", Fundo.Modo.Cor to "Cor", Fundo.Modo.Remover to "Remover").forEach { (m, r) ->
                Chip(r, f.modo == m && !refinando) { aoModo(m) }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                refinando -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Adicionar", pincelAdiciona) { aoPincelModo(true) }
                    Chip("Remover", !pincelAdiciona) { aoPincelModo(false) }
                    Text("${pincelDp.roundToInt()}", color = Tema.Texto, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                    Slider(value = pincelDp, onValueChange = { aoPincelDp(it.roundToInt().toFloat()) }, valueRange = 8f..80f, modifier = Modifier.weight(1f).height(28.dp),
                        colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                    TextButton(onClick = aoLimparTracos, enabled = f.tracos.isNotEmpty()) { Text("Limpar", color = if (f.tracos.isNotEmpty()) Tema.Texto2 else Color.Transparent, fontSize = 12.sp) }
                    Chip("Concluir", true) { aoRefinar(false) }
                }
                f.modo == Fundo.Modo.Desfocar -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.intensidade.roundToInt().toString(), color = Tema.Texto, fontSize = 14.sp, modifier = Modifier.width(44.dp))
                    Slider(value = f.intensidade, onValueChange = { aoIntensidade(it.roundToInt().toFloat()) }, onValueChangeFinished = aoIntensidadeFim, valueRange = 0f..100f,
                        modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                    Chip("Refinar", false, marcado = f.tracos.isNotEmpty()) { aoRefinar(true) }
                }
                f.modo == Fundo.Modo.Cor -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CORES_FUNDO.forEach { c -> Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (f.cor == c) Tema.Coral else Color(0x33FFFFFF), CircleShape).clickable { aoCor(c) }) }
                    }
                    Chip("Refinar", false, marcado = f.tracos.isNotEmpty()) { aoRefinar(true) }
                }
                f.modo == Fundo.Modo.Remover -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("A cópia sai em PNG com o fundo transparente.", color = Tema.Texto2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Chip("Refinar", false, marcado = f.tracos.isNotEmpty()) { aoRefinar(true) }
                }
                f.modo == Fundo.Modo.PretoEBranco -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Só a pessoa fica colorida.", color = Tema.Texto2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Chip("Refinar", false, marcado = f.tracos.isNotEmpty()) { aoRefinar(true) }
                }
                else -> Text("Escolha o que fazer com o fundo. A pessoa é separada automaticamente; Refinar corrige com pincel.", color = Tema.Texto2, fontSize = 13.sp)
            }
        }
        LinhaMotor(f.motor, aplicando, estadoModelo, avisoMotor, aoMotor, aoCancelarBaixa)
    }
}

/** Terceira linha do Fundo: escolha do motor, progresso do download do modelo Alta ou aviso de fallback. */
@Composable
private fun LinhaMotor(motor: Fundo.Motor, aplicando: Boolean, estado: IsnetOnnx.Estado, aviso: String?,
                       aoMotor: (Fundo.Motor) -> Unit, aoCancelar: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val baixando = estado as? IsnetOnnx.Estado.Baixando
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            baixando != null -> {
                Text("Baixando ${IsnetOnnx.MB} MB · ${(baixando.fracao * 100).roundToInt()}%", color = Tema.Texto, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = aoCancelar) { Text("Cancelar", color = Tema.Coral, fontSize = 13.sp) }
            }
            else -> {
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.height(48.dp)) {
                        Text("Recorte: ${motor.rotulo}", color = Tema.Texto, fontSize = 13.sp)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Escolher o motor do recorte", tint = Tema.Texto2, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.width(280.dp)) {
                        listOf(
                            Fundo.Motor.Leve to "Imediato, contorno básico",
                            Fundo.Motor.Padrao to "Melhor contorno, sem download",
                            Fundo.Motor.Alta to if (estado is IsnetOnnx.Estado.Pronto) "Mais detalhes, já está no aparelho" else "Mais detalhes, baixa ${IsnetOnnx.MB} MB uma vez"
                        ).forEach { (m, desc) ->
                            DropdownMenuItem(
                                modifier = Modifier.heightIn(min = 64.dp),
                                text = { Column {
                                    Text(m.rotulo, color = if (m == motor) Tema.Coral else Tema.Texto, fontSize = 15.sp)
                                    Text(desc, color = Tema.Texto2, fontSize = 12.sp)
                                } },
                                onClick = { menu = false; aoMotor(m) })
                        }
                    }
                }
                when {
                    aplicando -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Tema.Coral, strokeWidth = 2.dp)
                        Text("Aplicando recorte ${motor.rotulo}…", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                    aviso != null -> Text(aviso, color = Tema.Coral, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    estado is IsnetOnnx.Estado.Erro -> Text("Não consegui baixar o modelo.", color = Tema.Coral, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    else -> Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Local (Astra): chips Local 1/2/3 + "+", abas Forma/Ajustes/Cor/Inverter, um slider por vez. Pessoa só uma vez.
 * A aba Cor tinge a área: uma cor some junto com a máscara (filtro graduado); a segunda cor transforma em gradiente
 * que cobre a foto (cor A onde a máscara vale 1, cor B onde vale 0), que é o gradiente do Photoshop.
 */
@Composable
private fun PainelLocal(p: Local.Parametros, sel: Int, aba: String, param: String, verMascara: Boolean, escolhendoTipo: Boolean,
                        aoSelecionar: (Int) -> Unit, aoAba: (String) -> Unit, aoParam: (String) -> Unit, aoVerMascara: (Boolean) -> Unit, aoEscolherTipo: (Boolean) -> Unit,
                        aoNova: (Local.Tipo) -> Unit, aoExcluir: (Int) -> Unit, aoInverter: (Int) -> Unit, aoSuavidade: (Float) -> Unit, aoSuavidadeFim: () -> Unit,
                        aoEscolherCor: (String) -> Unit, aoForcaCor: (Float) -> Unit,
                        aoValor: (Float) -> Unit, aoValorFim: () -> Unit, aoZerar: () -> Unit) {
    val m = p.mascaras.getOrNull(sel)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            p.mascaras.forEachIndexed { i, mk -> Chip("Local ${i + 1} · ${when (mk.tipo) { Local.Tipo.Radial -> "Radial"; Local.Tipo.Linear -> "Linear"; else -> "Pessoa" }}", sel == i, marcado = !mk.neutra) { aoSelecionar(i) } }
            if (p.mascaras.size < Local.MAX) Chip("+", escolhendoTipo) { aoEscolherTipo(!escolhendoTipo) }
            if (m != null) { TextButton(onClick = { aoExcluir(sel) }) { Text("Excluir", color = Tema.Texto2, fontSize = 12.sp) } }
        }
        when {
            escolhendoTipo || m == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Radial", false) { aoNova(Local.Tipo.Radial) }; Chip("Linear", false) { aoNova(Local.Tipo.Linear) }
                if (p.mascaras.none { it.tipo == Local.Tipo.Pessoa }) Chip("Pessoa", false) { aoNova(Local.Tipo.Pessoa) }
                if (m == null) Text("Crie uma máscara e ajuste só aquela área.", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
            else -> Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("Forma", aba == "Forma") { aoAba("Forma") }
                    Chip("Ajustes", aba == "Ajustes") { aoAba("Ajustes") }
                    Chip("Cor", aba == "Cor", marcado = m.temCor) { aoAba("Cor") }
                    Chip("Inverter", m.invertida) { aoInverter(sel) }
                    if (aba == "Ajustes") Chip("Ver máscara", verMascara) { aoVerMascara(!verMascara) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    when (aba) {
                        "Forma" -> {
                            if (m.tipo == Local.Tipo.Radial) {
                                Text("Suavidade ${m.suavidade.roundToInt()}", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.width(100.dp))
                                Slider(value = m.suavidade, onValueChange = { aoSuavidade(it.roundToInt().toFloat()) }, onValueChangeFinished = aoSuavidadeFim, valueRange = 0f..100f, modifier = Modifier.weight(1f).height(28.dp),
                                    colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                            } else Text(if (m.tipo == Local.Tipo.Linear) "Arraste as alças: coral = 100% do efeito, branca = 0%." else "Máscara da pessoa, separada automaticamente.",
                                color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { aoEscolherCor("Marcacao") }, modifier = Modifier.height(48.dp)) { Text("Marcação", color = Tema.Texto2, fontSize = 12.sp) }
                        }
                        "Cor" -> {
                            Amostra(m.corA, "A") { aoEscolherCor("A") }
                            if (m.corB != 0) Amostra(m.corB, "B") { aoEscolherCor("B") }
                            else TextButton(onClick = { aoEscolherCor("B") }, enabled = m.corA != 0, modifier = Modifier.height(48.dp)) {
                                Text("+ Cor B", color = if (m.corA != 0) Tema.Texto2 else Color(0x33FFFFFF), fontSize = 12.sp)
                            }
                            if (m.corA != 0) {
                                Text(m.forcaCor.roundToInt().toString(), color = Tema.Texto, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
                                Slider(value = m.forcaCor, onValueChange = { aoForcaCor(it.roundToInt().toFloat()) }, onValueChangeFinished = aoValorFim, valueRange = 0f..100f,
                                    modifier = Modifier.weight(1f).height(28.dp), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                            } else Text("Escolha uma cor para tingir a área.", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        }
                        else -> {
                            Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Local.SLIDERS.forEach { s2 -> Chip(s2, param == s2, marcado = m.valor(s2) != 0f) { aoParam(s2) } } }
                                val v = m.valor(param)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text((if (v > 0) "+" else "") + v.roundToInt().toString(), color = Tema.Texto, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp))
                                    Slider(value = v, onValueChange = { aoValor(it.roundToInt().toFloat()) }, onValueChangeFinished = aoValorFim, valueRange = -100f..100f, modifier = Modifier.weight(1f).height(28.dp),
                                        colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                                    TextButton(onClick = aoZerar, enabled = v != 0f) { Text("Redefinir", color = if (v != 0f) Tema.Texto2 else Color.Transparent, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Amostra de cor de 28 dp dentro de um alvo de 48 dp; vazia mostra só o contorno com a letra. */
@Composable
private fun Amostra(cor: Int, letra: String, aoTocar: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = aoTocar), contentAlignment = Alignment.Center) {
        if (cor == 0) Box(Modifier.size(28.dp).clip(CircleShape).border(1.dp, Tema.Texto2, CircleShape), contentAlignment = Alignment.Center) {
            Text(letra, color = Tema.Texto2, fontSize = 11.sp)
        } else Box(Modifier.size(28.dp).clip(CircleShape).background(Color(cor)).border(2.dp, Color(0x55FFFFFF), CircleShape))
    }
}

/** Folha inferior de escolha de cor: paleta em 2 linhas de 5 e, quando `livre`, matiz/saturação/brilho. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolhaCor(titulo: String, cores: List<Pair<Int, String>>, atual: Int, livre: Boolean, podeRemover: Boolean, podeTrocar: Boolean,
                     aoFechar: () -> Unit, aoRemover: () -> Unit, aoTrocar: () -> Unit, aoCor: (Int) -> Unit) {
    val hsv = remember(atual) { FloatArray(3).also { if (atual != 0) android.graphics.Color.colorToHSV(atual, it) else { it[0] = 200f; it[1] = 0.7f; it[2] = 0.9f } } }
    var matiz by remember(atual) { mutableStateOf(hsv[0]) }
    var sat by remember(atual) { mutableStateOf(hsv[1]) }
    var brilho by remember(atual) { mutableStateOf(hsv[2]) }
    ModalBottomSheet(onDismissRequest = aoFechar, containerColor = Tema.Superficie, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(titulo, color = Tema.Texto, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            cores.chunked(5).forEach { linha ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    linha.forEach { (c, _) ->
                        Box(Modifier.size(48.dp).clickable { aoCor(c) }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(if (c == atual) 3.dp else 1.dp, if (c == atual) Tema.Texto else Color(0x55FFFFFF), CircleShape))
                        }
                    }
                    repeat(5 - linha.size) { Spacer(Modifier.size(48.dp)) }
                }
            }
            if (livre) {
                Text("Cor livre", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                val corLivre = android.graphics.Color.HSVToColor(floatArrayOf(matiz, sat, brilho))
                listOf(Triple("Matiz", matiz / 360f, 0), Triple("Saturação", sat, 1), Triple("Brilho", brilho, 2)).forEach { (rot, v, i) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rot, color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.width(78.dp))
                        Slider(value = v, onValueChange = { nv -> when (i) { 0 -> matiz = nv * 360f; 1 -> sat = nv; else -> brilho = nv } },
                            modifier = Modifier.weight(1f).height(28.dp), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(corLivre)).border(1.dp, Color(0x55FFFFFF), CircleShape))
                    Button(onClick = { aoCor(corLivre) }, colors = ButtonDefaults.buttonColors(containerColor = Tema.Coral, contentColor = Color.White)) { Text("Usar esta cor", fontSize = 13.sp) }
                }
            }
            if (podeTrocar || podeRemover) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (podeTrocar) TextButton(onClick = aoTrocar, modifier = Modifier.height(48.dp)) { Text("Trocar A e B", color = Tema.Texto2, fontSize = 13.sp) }
                if (podeRemover) TextButton(onClick = aoRemover, modifier = Modifier.height(48.dp)) { Text("Remover segunda cor", color = Tema.Coral, fontSize = 13.sp) }
            }
        }
    }
}

/** Corrigir: Pele (slider) e Cicatrizar (pincel de cura para manchas pequenas). */
@Composable
private fun PainelCorrigir(receita: Receita, parametro: String, curando: Boolean, curaDp: Float, aoParametro: (String) -> Unit, aoValor: (Float) -> Unit, aoValorFim: () -> Unit, aoZerar: () -> Unit, aoCuraDp: (Float) -> Unit, aoLimparCura: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Pele", parametro == "Pele", marcado = receita.fundo.pele != 0f) { aoParametro("Pele") }
            Chip("Cicatrizar", curando, marcado = receita.cura.pinceladas.isNotEmpty()) { aoParametro("Cicatrizar") }
        }
        if (curando) Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pincel ${curaDp.roundToInt()}", color = Tema.Texto2, fontSize = 12.sp, modifier = Modifier.width(76.dp))
                Slider(value = curaDp, onValueChange = { aoCuraDp(it.roundToInt().toFloat()) }, valueRange = 8f..60f, modifier = Modifier.weight(1f).height(28.dp), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                TextButton(onClick = aoLimparCura, enabled = receita.cura.pinceladas.isNotEmpty()) { Text("Limpar", color = if (receita.cura.pinceladas.isNotEmpty()) Tema.Texto2 else Color.Transparent, fontSize = 12.sp) }
            }
            Text("Para manchas pequenas. Se borrar ou repetir textura, desfaça, amplie e use um pincel menor.", color = Tema.Texto2, fontSize = 12.sp)
        } else {
            val v = receita.fundo.pele
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.roundToInt().toString(), color = Tema.Texto, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                Slider(value = v, onValueChange = { aoValor(it.roundToInt().toFloat()) }, onValueChangeFinished = aoValorFim, valueRange = 0f..100f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Tema.Coral, activeTrackColor = Tema.Coral))
                TextButton(onClick = aoZerar, enabled = v != 0f) { Text("Redefinir", color = if (v != 0f) Tema.Texto2 else Color.Transparent, fontSize = 13.sp) }
            }
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
