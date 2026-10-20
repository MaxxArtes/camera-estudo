package br.maxymus.tradutor

import kotlin.math.max
import kotlin.math.min

/**
 * Regras do reconhecimento e do pedido de tradução (Tradutor 0.33), sem Android, para rodar na JVM do harness. Ideias
 * estudadas no FrankYomik (leitor de mangá e webtoon de código aberto) e reescritas do zero aqui; nenhum código dele foi
 * copiado (o servidor dele é AGPL e o cliente GPL).
 */

/** Uma caixa na imagem, em pixels, com a base e a direita exclusivas (como android.graphics.Rect). */
internal class Caixa(val esquerda: Int, val topo: Int, val direita: Int, val base: Int) {
    val largura get() = direita - esquerda
    val altura get() = base - topo
    val area get() = max(0, largura).toLong() * max(0, altura)
    fun desloca(dy: Int) = Caixa(esquerda, topo + dy, direita, base + dy)
    fun une(o: Caixa) = Caixa(min(esquerda, o.esquerda), min(topo, o.topo), max(direita, o.direita), max(base, o.base))
    fun intersecao(o: Caixa): Long =
        max(0, min(direita, o.direita) - max(esquerda, o.esquerda)).toLong() * max(0, min(base, o.base) - max(topo, o.topo))
}

/**
 * O quadro alto lido em FAIXAS sobrepostas. O reconhecedor reduz por dentro a imagem muito alta, e numa tira de webtoon
 * de 800 x 12000 a letra fica miúda demais para ele. Cada faixa é lida sozinha e as caixas voltam para a página; a
 * sobreposição é maior que qualquer linha, então a linha cortada numa emenda aparece inteira na faixa vizinha.
 */
internal object RegraFaixas {
    const val ALTURA_FAIXA = 1600
    const val SOBREPOSICAO = 240

    /** Só a imagem alta é cortada: mais alta que 2600 px e que 2,5 vezes a largura. A tela do celular (1080 x 2400) nunca. */
    fun precisaCortar(largura: Int, altura: Int) = largura > 0 && altura > max(2600, (largura * 2.5).toInt())

    /** As faixas, de cima para baixo, como pares (topo, base) com a base exclusiva. A última termina na base da imagem. */
    fun cortes(altura: Int, faixa: Int = ALTURA_FAIXA, sobreposicao: Int = SOBREPOSICAO): List<Pair<Int, Int>> {
        if (altura <= 0) return emptyList()
        if (faixa <= sobreposicao) return listOf(0 to altura)
        val saida = ArrayList<Pair<Int, Int>>()
        var y = 0
        while (true) {
            val base = min(altura, y + faixa)
            saida += y to base
            if (base >= altura) break
            y = base - sobreposicao
        }
        return saida
    }

    /**
     * A linha (já em coordenadas da página) encosta numa emenda da faixa, e não numa borda da imagem? Então ela foi cortada
     * aqui e é descartada: a faixa vizinha a tem inteira.
     */
    fun cortada(c: Caixa, faixaTopo: Int, faixaBase: Int, alturaImagem: Int, folga: Int = 2): Boolean =
        (faixaTopo > 0 && c.topo <= faixaTopo + folga) || (faixaBase < alturaImagem && c.base >= faixaBase - folga)

    /**
     * Junta o que veio de várias leituras (faixas vizinhas, ou a segunda passada) sem repetir: duas caixas que se cobrem
     * (metade da área da união, ou 80% da menor) são a mesma linha, e fica a de maior área. A ordem da entrada é mantida.
     */
    fun <T> semRepetidas(itens: List<T>, caixa: (T) -> Caixa): List<T> {
        val ficam = ArrayList<T>()
        for (it in itens) {
            val c = caixa(it)
            val igual = ficam.indexOfFirst { repetida(caixa(it), c) }
            if (igual < 0) ficam += it
            else if (c.area > caixa(ficam[igual]).area) ficam[igual] = it
        }
        return ficam
    }

    fun repetida(a: Caixa, b: Caixa): Boolean {
        val i = a.intersecao(b)
        if (i <= 0) return false
        val uniao = a.area + b.area - i
        return i * 2 >= uniao || i * 5 >= min(a.area, b.area) * 4
    }
}

/**
 * Onomatopeia e título: letra decorativa muito maior que a das falas. Se entra na junção, contamina o balão vizinho
 * (vira uma fala só com ele); por isso fica de fora, e segue para a tradução marcada como "sfx".
 */
internal object RegraSfx {
    /** Uma linha é grande quando tem pelo menos FATOR vezes a altura mediana das linhas da imagem. */
    const val FATOR = 2.2
    /** Com menos linhas que isto não há mediana confiável e nada é marcado. */
    const val MIN_LINHAS = 3

    /** Os índices das linhas grandes. */
    fun grandes(alturas: List<Int>): Set<Int> {
        if (alturas.size < MIN_LINHAS) return emptySet()
        val ord = alturas.sorted()
        val m = ord.size / 2
        val mediana = if (ord.size % 2 == 1) ord[m].toDouble() else (ord[m - 1] + ord[m]) / 2.0
        if (mediana <= 0) return emptySet()
        return alturas.indices.filter { alturas[it] >= FATOR * mediana }.toSet()
    }
}

/**
 * A junção das linhas do reconhecedor em falas (a regra medida na bancada em 22/09, que estava em Falas.junta): junta duas
 * linhas quando a distância vertical entre elas é menor que 1,6 vez a altura da LINHA e elas se sobrepõem em pelo menos 25%
 * da largura. Agora sem Android, para o banco de avaliação, e com a onomatopeia de fora: linha grande só junta com linha grande.
 */
internal object RegraJuncao {
    class Linha(val texto: String, val caixa: Caixa, val alturaLinha: Int, val sfx: Boolean = false)

    fun juntar(entrada: List<Linha>): List<Linha> {
        val grandes = RegraSfx.grandes(entrada.map { it.alturaLinha })
        val marcadas = entrada.mapIndexed { i, l -> if (i in grandes) Linha(l.texto, l.caixa, l.alturaLinha, true) else l }
        val l = marcadas.sortedWith(compareBy({ it.caixa.topo }, { it.caixa.esquerda })).toMutableList()
        var mudou = true
        while (mudou) {
            mudou = false
            fora@ for (i in l.indices) {
                for (j in i + 1 until l.size) {
                    val a = l[i]; val b = l[j]
                    if (a.sfx != b.sfx) continue
                    // ALTURA DA LINHA, não do bloco: depois de juntar 3 linhas o "bloco" media 150 px, e 1,6 vez isso engolia
                    // o balão vizinho inteiro (captura do dono em 23/09).
                    val alt = max(1, min(a.alturaLinha, b.alturaLinha))
                    val vert = if (b.caixa.topo >= a.caixa.base) b.caixa.topo - a.caixa.base else a.caixa.topo - b.caixa.base
                    val sobrepoe = min(a.caixa.direita, b.caixa.direita) - max(a.caixa.esquerda, b.caixa.esquerda)
                    val largura = min(a.caixa.largura, b.caixa.largura)
                    if (vert < alt * 1.6f && sobrepoe > largura * 0.25f) {
                        val acima = if (a.caixa.topo <= b.caixa.topo) a else b
                        val abaixo = if (acima === a) b else a
                        l[i] = Linha(acima.texto + " " + abaixo.texto, a.caixa.une(b.caixa), (a.alturaLinha + b.alturaLinha) / 2, a.sfx)
                        l.removeAt(j); mudou = true; break@fora
                    }
                }
            }
        }
        return l.filter { it.texto.length >= 3 }
    }
}

/**
 * Segunda passada do reconhecedor, na imagem invertida e com contraste, para a letra clara sobre fundo escuro. Custa outra
 * leitura inteira, então só roda quando a primeira não achou NADA numa imagem escura.
 */
internal object RegraSegundaPassada {
    const val ESCURA = 0.45

    fun precisa(linhasAchadas: Int, luminanciaMedia: Double) = linhasAchadas == 0 && luminanciaMedia < ESCURA

    /** Luminância média de 0 a 1, olhando um pixel a cada `passo` (ARGB). */
    fun luminanciaMedia(px: IntArray, passo: Int = 7): Double {
        if (px.isEmpty()) return 1.0
        var soma = 0.0
        var n = 0
        var i = 0
        val p = max(1, passo)
        while (i < px.size) {
            val c = px[i]
            soma += (0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)) / 255.0
            n++
            i += p
        }
        return soma / n
    }
}

/**
 * O interior do balão em volta da fala, como RETÂNGULO. A faixa da tradução continua quadrada (decisão do dono em 01/10),
 * mas não passa da parede do balão quando ela é clara: a faixa que vazava para fora do balão em quadro escuro era o defeito.
 * De cada lado da caixa do texto, várias retas andam para fora enquanto o pixel é parecido com o fundo; a parede é a
 * mediana das distâncias. Sem parede achada na maioria das retas de algum lado, não há balão claro e devolve nulo (a faixa
 * fica como hoje).
 */
internal object RegraBalao {
    const val RETAS = 7
    const val TOLERANCIA = 60          // soma das diferenças de R, G e B
    const val MAIORIA = 0.6

    fun interior(px: IntArray, w: Int, h: Int, caixa: Caixa, fundo: Int, limite: Int, tol: Int = TOLERANCIA): Caixa? {
        if (w <= 0 || h <= 0 || px.size < w * h || caixa.largura <= 0 || caixa.altura <= 0) return null
        fun parecido(x: Int, y: Int): Boolean {
            val c = px[y * w + x]
            return Math.abs((c shr 16 and 255) - (fundo shr 16 and 255)) + Math.abs((c shr 8 and 255) - (fundo shr 8 and 255)) +
                Math.abs((c and 255) - (fundo and 255)) <= tol
        }
        // distância até o primeiro pixel diferente do fundo, andando de (x, y) no passo (dx, dy); nulo sem parede no limite
        fun parede(x0: Int, y0: Int, dx: Int, dy: Int): Int? {
            var x = x0; var y = y0
            for (d in 0..limite) {
                if (x < 0 || y < 0 || x >= w || y >= h) return null
                if (!parecido(x, y)) return d
                x += dx; y += dy
            }
            return null
        }
        fun lado(retas: List<Int?>): Int? {
            val achadas = retas.filterNotNull().sorted()
            return if (achadas.size >= Math.ceil(retas.size * MAIORIA).toInt()) achadas[achadas.size / 2] else null
        }
        val xs = (1..RETAS).map { caixa.esquerda + caixa.largura * it / (RETAS + 1) }
        val ys = (1..RETAS).map { caixa.topo + caixa.altura * it / (RETAS + 1) }
        val cima = lado(xs.map { parede(it.coerceIn(0, w - 1), caixa.topo - 1, 0, -1) }) ?: return null
        val baixo = lado(xs.map { parede(it.coerceIn(0, w - 1), caixa.base, 0, 1) }) ?: return null
        val esq = lado(ys.map { parede(caixa.esquerda - 1, it.coerceIn(0, h - 1), -1, 0) }) ?: return null
        val dir = lado(ys.map { parede(caixa.direita, it.coerceIn(0, h - 1), 1, 0) }) ?: return null
        return Caixa(caixa.esquerda - esq, caixa.topo - cima, caixa.direita + dir, caixa.base + baixo)
    }

    /**
     * Encaixa o intervalo [a, b) dentro de [de, ate) sem mudar o tamanho, se couber; não cabendo, devolve como está (a
     * tradução precisa do espaço mais do que de ficar dentro do balão).
     */
    fun encaixa(a: Int, b: Int, de: Int, ate: Int): Pair<Int, Int> {
        val t = b - a
        if (t > ate - de) return a to b
        val na = a.coerceIn(de, ate - t)
        return na to na + t
    }
}

/**
 * O contexto que vai junto do pedido ao modelo: as falas dos quadros ANTERIORES do capítulo, só para o modelo manter nome,
 * tratamento e pronome de um quadro para o outro. Limites como os do FrankYomik: até 4 quadros, 4000 caracteres no total e
 * 2000 por quadro; sobrando, sai o mais antigo.
 */
internal object RegraContexto {
    const val MAX_ITENS = 4
    const val MAX_CARACTERES = 4000
    const val MAX_CAMPO = 2000

    /** `anteriores` do mais antigo para o mais recente; cada item são as falas de um quadro. Devolve no mesmo sentido. */
    fun montar(anteriores: List<List<String>>): List<String> {
        val itens = anteriores.map { q -> q.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ").take(MAX_CAMPO) }
            .filter { it.isNotEmpty() }.takeLast(MAX_ITENS).toMutableList()
        while (itens.isNotEmpty() && itens.sumOf { it.length } > MAX_CARACTERES) itens.removeAt(0)
        return itens
    }
}

/**
 * O pedido ao nosso serviço de tradução. O contrato antigo (lista "falas" ida e volta) continua valendo; o novo manda
 * também um id por fala, o tipo (fala ou sfx) e o contexto, e aceita "traducoes" por id na resposta. A resposta que vem
 * faltando fala é completada pedindo SÓ as que faltaram, no máximo duas vezes, a segunda em duas metades.
 */
internal object RegraPedidoModelo {
    const val VERSAO = 2
    const val FALA = "fala"
    const val SFX = "sfx"
    /** pedidos extras de recuperação, além do primeiro */
    const val MAX_RECUPERACOES = 2

    fun ids(n: Int): List<String> = List(n) { "f$it" }

    /**
     * Alinha a resposta com as falas pedidas: prefere o mapa por id (contrato novo) e cai para a lista posicional (contrato
     * antigo). Id desconhecido é ignorado; o que não veio, ou veio em branco, fica nulo.
     */
    fun alinhar(ids: List<String>, porId: Map<String, String>?, lista: List<String?>?): List<String?> =
        ids.mapIndexed { i, id ->
            val t = if (porId != null) porId[id] else lista?.getOrNull(i)
            t?.trim()?.takeIf { it.isNotEmpty() }
        }

    fun faltando(r: List<String?>): List<Int> = r.indices.filter { r[it] == null }

    /** Os pedidos da recuperação `tentativa` (0 é a primeira): todas as que faltam; depois em duas metades; depois nada. */
    fun recuperacao(faltando: List<Int>, tentativa: Int): List<List<Int>> = when {
        faltando.isEmpty() || tentativa >= MAX_RECUPERACOES -> emptyList()
        tentativa == 0 || faltando.size == 1 -> listOf(faltando)
        else -> listOf(faltando.subList(0, faltando.size / 2), faltando.subList(faltando.size / 2, faltando.size))
    }
}
