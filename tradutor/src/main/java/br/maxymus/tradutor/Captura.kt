package br.maxymus.tradutor

import android.graphics.Bitmap

/**
 * A suspeita de captura escura ou protegida, sem Android na parte que decide: o que lê o pixel entra como função, e na
 * JVM do harness ele lê um IntArray. É só SUSPEITA, nunca prova (revisão do Astra, 03/10): meia tela quase preta é comum em
 * quadrinho escuro, margem e página sem diálogo, e o OCR também falha em página legítima.
 *
 *  - amostra uma grade de GRADE_X x GRADE_Y pontos DENTRO da área de conteúdo (entre `topo` e `base`, as mesmas faixas que
 *    o OCR usa, fora das barras do sistema e do navegador), nos centros das células;
 *  - o ponto é preto quando a luminância (0 a 255) é menor que LUMA_PRETO;
 *  - há suspeita quando o OCR leu ZERO texto no conteúdo e a fração de pretos é de pelo menos FRACAO_SUSPEITA. "Texto" aqui
 *    é qualquer fala reconhecida ANTES da tradução, inclusive português: não é o filtro `uteis` de quem recebe camada ou
 *    voz. OCR que falhou com erro é inconclusivo e não gera suspeita.
 */
internal object RegraCaptura {
    const val GRADE_X = 64
    const val GRADE_Y = 128
    /** abaixo disto (escala 0 a 255) o ponto conta como preto; o preto de uma captura bloqueada é 0 */
    const val LUMA_PRETO = 8
    /** fração mínima de pontos pretos para haver suspeita, inclusive */
    const val FRACAO_SUSPEITA = 0.50f

    /** Luminância 0 a 255 de um pixel ARGB (pesos do BT.601); o alfa não entra, a captura da tela é opaca. */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** A fração, de 0 a 1, dos pontos da grade com luminância abaixo de LUMA_PRETO. Área sem altura ou sem largura dá 0. */
    fun fracaoEscura(largura: Int, topo: Int, base: Int, pixel: (x: Int, y: Int) -> Int): Float {
        val altura = base - topo
        if (largura <= 0 || altura <= 0) return 0f
        var pretos = 0
        for (j in 0 until GRADE_Y) {
            val y = topo + ((2 * j + 1).toLong() * altura / (2 * GRADE_Y)).toInt()
            for (i in 0 until GRADE_X) {
                val x = ((2 * i + 1).toLong() * largura / (2 * GRADE_X)).toInt()
                if (luma(pixel(x, y)) < LUMA_PRETO) pretos++
            }
        }
        return pretos.toFloat() / (GRADE_X * GRADE_Y)
    }

    /** A mesma conta sobre os pixels ARGB de uma imagem `largura` x `altura` (linha por linha), para rodar sem Bitmap. */
    fun fracaoEscura(pixels: IntArray, largura: Int, altura: Int, topo: Int, base: Int): Float {
        val t = topo.coerceIn(0, altura)
        val b = base.coerceIn(t, altura)
        return fracaoEscura(largura, t, b) { x, y -> pixels[y * largura + x] }
    }

    /** A mesma conta sobre a captura da tela (software, ARGB_8888): lê só os pontos da grade, sem copiar a imagem inteira. */
    fun fracaoEscura(bitmap: Bitmap, topo: Int, base: Int): Float {
        val t = topo.coerceIn(0, bitmap.height)
        val b = base.coerceIn(t, bitmap.height)
        return fracaoEscura(bitmap.width, t, b) { x, y -> bitmap.getPixel(x, y) }
    }

    /** Suspeita só com OCR que rodou (`ocrFalhou` falso), zero falas lidas no conteúdo e fração de pretos de pelo menos FRACAO_SUSPEITA. */
    fun suspeita(falasLidas: Int, ocrFalhou: Boolean, fracao: Float): Boolean =
        !ocrFalhou && falasLidas == 0 && fracao >= FRACAO_SUSPEITA

    /** A fração com duas casas, como vai para a telemetria. */
    fun duasCasas(fracao: Float): Double = Math.round(fracao * 100f) / 100.0
}
