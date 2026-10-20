package br.maxymus.tradutor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max

/**
 * Acha as falas na tela: OCR do ML Kit e, em seguida, o AGRUPAMENTO — que foi o primeiro defeito medido na bancada
 * em 22/09. O reconhecedor devolve a fala do balão quebrada em linhas ("you truly are" / "worthy of being my" /
 * "nemesis, warrior of light"), e traduzir linha por linha produz lixo. A regra que consertou, e que o agy
 * recomendou de forma independente: junta dois blocos quando a distância vertical entre eles é menor que 1,6 vez a
 * altura da linha E eles se sobrepõem em pelo menos 25% da largura. Assim balões vizinhos deslocados na horizontal
 * continuam separados, o que também foi verificado. A regra está em RegraJuncao (sem Android, para o banco de avaliação).
 */
object Falas {
    /** `sfx`: onomatopeia ou título, letra muito maior que a das falas (RegraSfx); não entra na junção com as falas. */
    class Fala(val texto: String, val caixa: Rect, val alturaLinha: Int, val sfx: Boolean = false)

    class Leitura(val falas: List<Fala>, val algumTexto: Boolean)

    private val leitor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Bloqueante (chamar fora da principal). `topo` e `base` recortam a área útil, fora das barras do sistema. */
    fun ler(b: Bitmap, topo: Int, base: Int): List<Fala> = lerOuNulo(b, topo, base)?.falas.orEmpty()

    /** O que uma leitura do reconhecedor devolve antes da junção: as linhas aceitas e as caixas de todo texto, de qualquer tamanho. */
    private class Bruta(val linhas: List<RegraJuncao.Linha>, val comTexto: List<Caixa>)

    /**
     * Como `ler`, mas devolve NULO quando o reconhecimento FALHOU (erro ou tempo): algumTexto indica texto no conteúdo
     * ANTES dos filtros de tradução, e nulo que não se sabe. Quem diagnostica captura escura precisa dessa diferença.
     *
     * Duas ideias do FrankYomik, reescritas aqui: o quadro ALTO é lido em faixas sobrepostas (RegraFaixas), e a imagem
     * escura em que a primeira leitura não achou nada ganha uma segunda, invertida e com contraste (RegraSegundaPassada).
     */
    fun lerOuNulo(b: Bitmap, topo: Int, base: Int): Leitura? = runCatching<Leitura?> {
        val primeira = lerImagem(b, inverter = false) ?: return@runCatching null
        fun naArea(c: Caixa) = c.topo >= topo && c.base <= base
        var linhas = primeira.linhas.filter { naArea(it.caixa) }
        var algumTexto = primeira.comTexto.any { naArea(it) }
        if (RegraSegundaPassada.precisa(linhas.size, luminancia(b))) {
            // as mesmas faixas do quadro alto: cada faixa é invertida sozinha, em vez de copiar a imagem inteira
            val segunda = lerImagem(b, inverter = true)
            if (segunda != null) {
                val novas = segunda.linhas.filter { naArea(it.caixa) }
                linhas = RegraFaixas.semRepetidas(linhas + novas) { it.caixa }
                algumTexto = algumTexto || segunda.comTexto.any { naArea(it) }
                Telemetria.evento("ocr_segunda", mapOf("linhas" to novas.size))
            }
        }
        Leitura(RegraJuncao.juntar(linhas).map {
            Fala(it.texto, Rect(it.caixa.esquerda, it.caixa.topo, it.caixa.direita, it.caixa.base), it.alturaLinha, it.sfx)
        }, algumTexto)
    }.getOrElse { e ->
        Telemetria.evento("erro", mapOf("onde" to "ocr", "msg" to Telemetria.classe(e)))
        null
    }

    /** O quadro inteiro, ou em faixas se for alto. `inverter`: a segunda passada, com a inversão feita sobre a parte lida. */
    private fun lerImagem(b: Bitmap, inverter: Boolean): Bruta? =
        if (RegraFaixas.precisaCortar(b.width, b.height)) porFaixas(b, inverter) else umaParte(b, 0, inverter)

    /** Lê `parte` (a imagem, ou uma faixa dela), invertendo uma CÓPIA dela quando pedido. */
    private fun umaParte(parte: Bitmap, dy: Int, inverter: Boolean): Bruta? {
        if (!inverter) return uma(parte, dy)
        val copia = invertida(parte)
        return try { uma(copia, dy) } finally { copia.recycle() }
    }

    /** Uma leitura do reconhecedor; as caixas descem `dy` pixels para voltar à página. Nulo quando ela falhou. */
    private fun uma(b: Bitmap, dy: Int): Bruta? {
        val r = com.google.android.gms.tasks.Tasks.await(leitor.process(InputImage.fromBitmap(b, 0)), 20, java.util.concurrent.TimeUnit.SECONDS)
        val brutas = ArrayList<RegraJuncao.Linha>()
        val comTexto = ArrayList<Caixa>()
        for (bloco in r.textBlocks) for (linha in bloco.lines) {
            val c = linha.boundingBox ?: continue
            val caixa = Caixa(c.left, c.top + dy, c.right, c.bottom + dy)
            if (linha.text.isNotBlank()) comTexto += caixa
            if (linha.text.trim().length < 2) continue
            brutas += RegraJuncao.Linha(linha.text.trim(), caixa, max(1, c.height()))
        }
        return Bruta(brutas, comTexto)
    }

    /**
     * O quadro alto, faixa por faixa. A linha que encosta numa emenda foi cortada e sai (a faixa vizinha tem a linha
     * inteira); o que se repete na sobreposição sai também. Uma faixa que falha derruba a leitura toda, como antes.
     */
    private fun porFaixas(b: Bitmap, inverter: Boolean): Bruta? {
        val cortes = RegraFaixas.cortes(b.height)
        val todas = ArrayList<RegraJuncao.Linha>()
        val comTexto = ArrayList<Caixa>()
        for ((y0, y1) in cortes) {
            val faixa = Bitmap.createBitmap(b, 0, y0, b.width, y1 - y0)
            val r = try { umaParte(faixa, y0, inverter) } finally { if (faixa !== b) faixa.recycle() }
            r ?: return null
            comTexto += r.comTexto
            todas += r.linhas.filter { !RegraFaixas.cortada(it.caixa, y0, y1, b.height) }
        }
        if (!inverter) Telemetria.evento("ocr_faixas", mapOf("faixas" to cortes.size, "px_h" to b.height))
        return Bruta(RegraFaixas.semRepetidas(todas) { it.caixa }, comTexto)
    }

    private fun luminancia(b: Bitmap): Double {
        // uma linha a cada 8 basta para saber se a imagem é escura, sem copiar a imagem inteira
        val px = IntArray(b.width)
        var soma = 0.0
        var n = 0
        var y = 0
        while (y < b.height) {
            b.getPixels(px, 0, b.width, 0, y, b.width, 1)
            soma += RegraSegundaPassada.luminanciaMedia(px)
            n++
            y += 8
        }
        return if (n == 0) 1.0 else soma / n
    }

    /** A imagem invertida e com o contraste esticado: a letra clara sobre fundo escuro vira letra escura sobre fundo claro. */
    private fun invertida(b: Bitmap): Bitmap {
        val saida = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val k = 1.4f                      // contraste
        val t = 255f - (255f * k - 255f) / 2f
        val m = ColorMatrix(floatArrayOf(
            -k, 0f, 0f, 0f, t,
            0f, -k, 0f, 0f, t,
            0f, 0f, -k, 0f, t,
            0f, 0f, 0f, 1f, 0f))
        Canvas(saida).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return saida
    }
}
