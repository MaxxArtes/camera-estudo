package br.maxymus.cameraestudo

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val Coral = Color(0xFFFF5A5F)
private val FundoEditor = Color(0xFF0E0E12)

/**
 * Conferência do recorte: a prévia da foto com os 4 cantos detectados como alças arrastáveis, lupa sobre
 * o canto em movimento e três saídas (Descartar, Sem recorte, Usar). Só aceita quadrilátero convexo no
 * sentido horário (Documento.convexo): um arraste que cruzaria os lados é ignorado.
 */
@Composable
fun EditorQuad(previa: Bitmap, quadInicial: FloatArray?, tela: Boolean, aoUsar: (FloatArray) -> Unit, aoSemRecorte: () -> Unit, aoDescartar: () -> Unit) {
    val imagem = remember(previa) { previa.asImageBitmap() }
    var quad by remember { mutableStateOf(quadInicial?.copyOf() ?: floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)) }
    var arrastando by remember { mutableIntStateOf(-1) }
    val alvo = if (tela) "a tela" else "a folha"

    Column(modifier = Modifier.fillMaxSize().background(FundoEditor).statusBarsPadding().navigationBarsPadding()) {
        Text(
            if (quadInicial == null) "Não achei $alvo: arraste os cantos até as bordas" else "Confira os cantos; arraste para ajustar",
            color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Canvas(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .pointerInput(imagem) {
                    // encaixe da prévia no canvas: mesma conta do desenho, para tocar e desenhar baterem
                    fun encaixe(): FloatArray {
                        val esc = min(size.width.toFloat() / previa.width, size.height.toFloat() / previa.height)
                        val dw = previa.width * esc; val dh = previa.height * esc
                        return floatArrayOf((size.width - dw) / 2, (size.height - dh) / 2, dw, dh)
                    }
                    val alcance = 40.dp.toPx()
                    detectDragGestures(
                        onDragStart = { toque ->
                            val (ox, oy, dw, dh) = encaixe()
                            var perto = -1; var menor = alcance
                            for (i in 0 until 4) {
                                val d = hypot(ox + quad[i * 2] * dw - toque.x, oy + quad[i * 2 + 1] * dh - toque.y)
                                if (d < menor) { menor = d; perto = i }
                            }
                            arrastando = perto
                        },
                        onDragEnd = { arrastando = -1 }, onDragCancel = { arrastando = -1 }
                    ) { mudanca, delta ->
                        val i = arrastando; if (i < 0) return@detectDragGestures
                        mudanca.consume()
                        val (_, _, dw, dh) = encaixe()
                        val novo = quad.copyOf()
                        novo[i * 2] = (novo[i * 2] + delta.x / dw).coerceIn(0f, 1f)
                        novo[i * 2 + 1] = (novo[i * 2 + 1] + delta.y / dh).coerceIn(0f, 1f)
                        if (Documento.convexo(novo)) quad = novo
                    }
                }
        ) {
            val esc = min(size.width / previa.width, size.height / previa.height)
            val dw = previa.width * esc; val dh = previa.height * esc
            val ox = (size.width - dw) / 2; val oy = (size.height - dh) / 2
            drawImage(imagem, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))

            val pts = List(4) { Offset(ox + quad[it * 2] * dw, oy + quad[it * 2 + 1] * dh) }
            val contorno = Path().apply { moveTo(pts[0].x, pts[0].y); for (k in 1..3) lineTo(pts[k].x, pts[k].y); close() }
            drawPath(contorno, Coral.copy(alpha = 0.18f))
            drawPath(contorno, Coral, style = Stroke(width = 2.dp.toPx()))
            pts.forEachIndexed { k, p ->
                val r = if (k == arrastando) 13.dp.toPx() else 9.dp.toPx()
                drawCircle(Color.White, r, p); drawCircle(Coral, r, p, style = Stroke(2.dp.toPx()))
            }

            // lupa: 2,5x em volta do canto arrastado, acima do dedo (ou abaixo, se não couber)
            if (arrastando >= 0) {
                val p = pts[arrastando]
                val raio = 64.dp.toPx(); val ampl = 2.5f
                val meio = raio / (ampl * esc)                         // meia-largura, em pixels da prévia
                val cx = quad[arrastando * 2] * previa.width; val cy = quad[arrastando * 2 + 1] * previa.height
                val centro = if (p.y - raio * 2 - 16.dp.toPx() > 0) Offset(p.x, p.y - raio - 24.dp.toPx()) else Offset(p.x, p.y + raio + 24.dp.toPx())
                val sx0 = max(0f, cx - meio); val sy0 = max(0f, cy - meio)
                val sx1 = min(previa.width.toFloat(), cx + meio); val sy1 = min(previa.height.toFloat(), cy + meio)
                val k = raio / meio
                val oval = Path().apply { addOval(Rect(centro.x - raio, centro.y - raio, centro.x + raio, centro.y + raio)) }
                drawPath(oval, Color.Black)
                clipPath(oval) {
                    if (sx1 > sx0 && sy1 > sy0) drawImage(
                        imagem, srcOffset = IntOffset(sx0.toInt(), sy0.toInt()), srcSize = IntSize((sx1 - sx0).toInt(), (sy1 - sy0).toInt()),
                        dstOffset = IntOffset((centro.x - raio + (sx0 - (cx - meio)) * k).toInt(), (centro.y - raio + (sy0 - (cy - meio)) * k).toInt()),
                        dstSize = IntSize(((sx1 - sx0) * k).toInt(), ((sy1 - sy0) * k).toInt())
                    )
                    drawLine(Coral, Offset(centro.x - raio, centro.y), Offset(centro.x + raio, centro.y), 1.5.dp.toPx())
                    drawLine(Coral, Offset(centro.x, centro.y - raio), Offset(centro.x, centro.y + raio), 1.5.dp.toPx())
                }
                drawCircle(Color.White, raio, centro, style = Stroke(2.dp.toPx()))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Botao("Descartar", Color(0x22FFFFFF), Modifier.weight(1f), aoDescartar)
            Botao("Sem recorte", Color(0x22FFFFFF), Modifier.weight(1f), aoSemRecorte)
            Botao("Usar", Coral, Modifier.weight(1f)) { aoUsar(quad) }
        }
    }
}

@Composable
private fun Botao(rotulo: String, fundo: Color, modifier: Modifier, aoTocar: () -> Unit) {
    Box(modifier = modifier.clip(RoundedCornerShape(14.dp)).background(fundo).clickable(onClick = aoTocar).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(rotulo, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
