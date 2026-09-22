package br.maxymus.galeriaestudo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Conjunto próprio de ícones do editor (sistema do Astra, 22/09): grade 24x24, margem interna de 2, traço de
 * 1,75, pontas e junções arredondadas, desenho vazado. O motivo de não usar os prontos do Material é que as
 * metáforas de rosto e de ajuste não existem lá, e misturar famílias foi justamente o que o dono notou.
 * Tudo em vetor: escala sem perder nitidez e o tint do tema pinta o traço.
 */
object Icones {
    private fun icone(nome: String, corpo: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = nome, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply { path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = corpo) }
            .build()

    /** Círculo completo desenhado com dois semiarcos (o construtor de vetor não tem primitiva de círculo). */
    private fun PathBuilder.circulo(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
    }

    /** Circunferência tracejada: `n` arcos curtos com folga entre eles. */
    private fun PathBuilder.circuloTracejado(cx: Float, cy: Float, r: Float, n: Int, ocupa: Float = 0.62f) {
        val passo = 360f / n
        for (i in 0 until n) {
            val a0 = Math.toRadians((i * passo).toDouble())
            val a1 = Math.toRadians((i * passo + passo * ocupa).toDouble())
            moveTo(cx + r * cos(a0).toFloat(), cy + r * sin(a0).toFloat())
            arcTo(r, r, 0f, false, true, cx + r * cos(a1).toFloat(), cy + r * sin(a1).toFloat())
        }
    }

    private fun PathBuilder.linha(x0: Float, y0: Float, x1: Float, y1: Float) { moveTo(x0, y0); lineTo(x1, y1) }

    /** Retângulo tracejado: cantos em L, que leem como moldura sem precisar de traço pontilhado. */
    private fun PathBuilder.molduraCantos(x0: Float, y0: Float, x1: Float, y1: Float, p: Float) {
        linha(x0, y0 + p, x0, y0); linha(x0, y0, x0 + p, y0)
        linha(x1 - p, y0, x1, y0); linha(x1, y0, x1, y0 + p)
        linha(x1, y1 - p, x1, y1); linha(x1, y1, x1 - p, y1)
        linha(x0 + p, y1, x0, y1); linha(x0, y1, x0, y1 - p)
    }

    // ---------------- os 8 grupos ----------------

    /** Luz: sol de miolo pequeno com oito raios curtos. */
    val Luz = icone("luz") {
        circulo(12f, 12f, 4f)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            linha(12f + 6.4f * c, 12f + 6.4f * s, 12f + 9f * c, 12f + 9f * s)
        }
    }

    /** Cor: três círculos sobrepostos em triângulo, a mistura dos canais. */
    val Cor = icone("cor") {
        circulo(12f, 8.6f, 4.2f); circulo(8.8f, 14.2f, 4.2f); circulo(15.2f, 14.2f, 4.2f)
    }

    /** Recortar: as duas réguas em L que se cruzam. */
    val Recortar = icone("recortar") {
        linha(7.5f, 2.5f, 7.5f, 16.5f); linha(7.5f, 16.5f, 21.5f, 16.5f)
        linha(2.5f, 7.5f, 16.5f, 7.5f); linha(16.5f, 7.5f, 16.5f, 21.5f)
    }

    /** Filtros: três cartões deslocados, com um ponto no da frente. */
    val Filtros = icone("filtros") {
        moveTo(9.5f, 4.5f); lineTo(19.5f, 4.5f); arcTo(1.5f, 1.5f, 0f, false, true, 21f, 6f); lineTo(21f, 14f)
        moveTo(7f, 7f); lineTo(17.5f, 7f)
        moveTo(3.5f, 11f); arcTo(1.5f, 1.5f, 0f, false, true, 5f, 9.5f); lineTo(16f, 9.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 17.5f, 11f); lineTo(17.5f, 19f)
        arcTo(1.5f, 1.5f, 0f, false, true, 16f, 20.5f); lineTo(5f, 20.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 19f); close()
        circulo(10.5f, 15f, 1.6f)
    }

    /** Detalhe: lupa com fios de textura dentro, a inspeção do grão. */
    val Detalhe = icone("detalhe") {
        circulo(10.5f, 10.5f, 6.5f)
        linha(15.3f, 15.3f, 20.5f, 20.5f)
        linha(8f, 8.4f, 8f, 12.6f); linha(9.7f, 6.8f, 9.7f, 14.2f); linha(11.4f, 8.4f, 11.4f, 12.6f); linha(13.1f, 7.6f, 13.1f, 13.4f)
    }

    /** Fundo: a moldura atrás e o assunto na frente. */
    val Fundo = icone("fundo") {
        molduraCantos(2.8f, 3.2f, 21.2f, 20.8f, 3.2f)
        moveTo(6.5f, 17.5f); lineTo(11f, 10.5f); lineTo(14f, 15f); lineTo(16f, 12.2f); lineTo(19.5f, 17.5f); close()
    }

    /** Local: a região tracejada com o centro marcado. */
    val Local = icone("local") {
        circuloTracejado(12f, 12f, 8f, 8)
        circulo(12f, 12f, 1.7f)
    }

    /** Corrigir: o risco interrompido pela borracha. */
    val Corrigir = icone("corrigir") {
        moveTo(3.5f, 17.5f); curveTo(6.5f, 13.5f, 8.5f, 19f, 11f, 14.5f)
        moveTo(19.5f, 6f); lineTo(13f, 12.5f); lineTo(16.5f, 16f); lineTo(23f, 9.5f); close()
        linha(15f, 10.5f, 18.5f, 14f)
    }

    // ---------------- controles anatômicos (Esculpir) ----------------

    /** Afinar: o oval do rosto com as setas empurrando para dentro. */
    val Afinar = icone("afinar") {
        moveTo(12f, 3.5f)
        curveTo(16f, 3.5f, 17.8f, 7f, 17.8f, 11f); curveTo(17.8f, 16f, 15f, 20.5f, 12f, 20.5f)
        curveTo(9f, 20.5f, 6.2f, 16f, 6.2f, 11f); curveTo(6.2f, 7f, 8f, 3.5f, 12f, 3.5f); close()
        linha(2.5f, 12f, 4.6f, 12f); linha(3.4f, 10.8f, 4.6f, 12f); linha(3.4f, 13.2f, 4.6f, 12f)
        linha(21.5f, 12f, 19.4f, 12f); linha(20.6f, 10.8f, 19.4f, 12f); linha(20.6f, 13.2f, 19.4f, 12f)
    }

    /** Queixo: o contorno de baixo do rosto e a seta que o encurta. */
    val Queixo = icone("queixo") {
        moveTo(5.5f, 6.5f); curveTo(5.5f, 14f, 8.5f, 18.5f, 12f, 18.5f); curveTo(15.5f, 18.5f, 18.5f, 14f, 18.5f, 6.5f)
        linha(12f, 21.5f, 12f, 20f); linha(10.8f, 21.2f, 12f, 20f); linha(13.2f, 21.2f, 12f, 20f)
    }

    /** Olhos: amêndoa, pupila e um brilho. */
    val Olhos = icone("olhos") {
        moveTo(2.8f, 12f); curveTo(6f, 7f, 18f, 7f, 21.2f, 12f); curveTo(18f, 17f, 6f, 17f, 2.8f, 12f); close()
        circulo(12f, 12f, 2.8f)
        linha(18.5f, 6.5f, 19.8f, 5.2f); circulo(20.9f, 6.9f, 0.5f)   // o brilho
    }

    /** Nariz: o perfil, com a asa. */
    val Nariz = icone("nariz") {
        moveTo(12.5f, 3.5f); curveTo(11.5f, 8f, 10f, 11.5f, 8.5f, 14f)
        curveTo(7.6f, 15.6f, 8.6f, 17.2f, 10.4f, 17.2f); lineTo(12f, 17.2f)
        moveTo(12.5f, 17.2f); curveTo(14.5f, 17.2f, 16f, 16f, 16.2f, 14.2f)
    }

    /** Lábios: os dois contornos e a linha do meio. */
    val Labios = icone("labios") {
        moveTo(3.5f, 11.5f); curveTo(6f, 7.5f, 9f, 7.5f, 12f, 10f); curveTo(15f, 7.5f, 18f, 7.5f, 20.5f, 11.5f)
        curveTo(17.5f, 17.5f, 6.5f, 17.5f, 3.5f, 11.5f); close()
        moveTo(3.5f, 11.5f); curveTo(8f, 13f, 16f, 13f, 20.5f, 11.5f)
    }

    /** Ícone do item de escultura pelo nome usado na régua. */
    fun deEscultura(nome: String): ImageVector = when (nome) {
        "Afinar" -> Afinar; "Queixo" -> Queixo; "Olhos" -> Olhos; "Nariz" -> Nariz; else -> Labios
    }
}
