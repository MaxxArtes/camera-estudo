package br.maxymus.tradutor

/**
 * As regras da leitura em voz alta no leitor de capítulo (Tradutor 0.28), sem Android, para rodar na JVM do harness:
 * os dados de leitura guardados por quadro, a escolha do quadro inicial, o que fazer com cada quadro durante o avanço e
 * quando uma rolagem interrompe a leitura. A tela (LeitorActivity) e a voz (Voz.kt) só aplicam o que sai daqui.
 */
internal object RegraLeituraCapitulo {
    /** Os códigos de `ouvir_terminou` que só o leitor usa; os demais vêm de RegraVoz. */
    const val FIM_CAPITULO = "fim_capitulo"
    const val ROLAGEM = "rolagem"
    const val ORIGINAL = "original"
    const val ERRO_QUADRO = "erro_quadro"

    /** Uma fala traduzida de um quadro: o texto que vai para a voz e a caixa dela na imagem do quadro, em pixels. */
    class FalaQuadro(val texto: String, val esquerda: Int, val topo: Int, val direita: Int, val base: Int, val alturaLinha: Int)

    /**
     * Os dados de leitura de um quadro, gravados junto da imagem pintada: as falas TRADUZIDAS já na ordem de leitura, o
     * tamanho da imagem em que as caixas foram medidas (para levar a caixa à tela), o destino e a versão da tradução.
     */
    class DadosQuadro(
        val destino: String, val versaoTraducao: Int, val largura: Int, val altura: Int, val falas: List<FalaQuadro>
    )

    /** Muda quando o formato do arquivo muda: arquivo de outro formato é ignorado e o quadro é preparado de novo. */
    const val FORMATO = 1

    /** O arquivo de falas leva o destino no nome, como o quadro pintado (RegraLeitor.nomePintado). */
    fun nomeFalas(indice: Int, destino: String) = "${indice}f_$destino.txt"

    /** As falas na ordem de leitura da 0.27 (RegraVoz.ordemDeLeitura): faixas de cima para baixo, na faixa da esquerda para a direita. */
    fun emOrdemDeLeitura(falas: List<FalaQuadro>): List<FalaQuadro> =
        RegraVoz.ordemDeLeitura(falas.map { RegraVoz.PosicaoFala(it.esquerda, it.topo, it.alturaLinha) }).map { falas[it] }

    /**
     * Texto do arquivo: uma linha de cabeçalho e uma por fala, campos separados por tabulação. A ordem das linhas É a
     * ordem de leitura. Tabulação, quebra de linha e barra invertida da fala são escapadas, para a fala caber numa linha.
     */
    fun escrever(d: DadosQuadro): String = buildString {
        append("falas\t").append(FORMATO).append('\t').append(escapa(d.destino)).append('\t').append(d.versaoTraducao)
            .append('\t').append(d.largura).append('\t').append(d.altura).append('\n')
        for (f in d.falas) {
            append(f.esquerda).append('\t').append(f.topo).append('\t').append(f.direita).append('\t').append(f.base)
                .append('\t').append(f.alturaLinha).append('\t').append(escapa(f.texto)).append('\n')
        }
    }

    /** Lê o que `escrever` gravou. Nulo quando o arquivo é de outro formato, está truncado ou não faz sentido. */
    fun ler(texto: String): DadosQuadro? {
        val linhas = texto.split('\n').filter { it.isNotEmpty() }
        val cab = linhas.firstOrNull()?.split('\t') ?: return null
        if (cab.size != 6 || cab[0] != "falas" || cab[1].toIntOrNull() != FORMATO) return null
        val versao = cab[3].toIntOrNull() ?: return null
        val largura = cab[4].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val altura = cab[5].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val falas = ArrayList<FalaQuadro>(linhas.size - 1)
        for (l in linhas.drop(1)) {
            val c = l.split('\t')
            if (c.size != 6) return null
            val n = c.take(5).map { it.toIntOrNull() ?: return null }
            val t = desescapa(c[5])
            if (t.isBlank()) continue
            falas += FalaQuadro(t, n[0], n[1], n[2], n[3], n[4])
        }
        return DadosQuadro(desescapa(cab[2]), versao, largura, altura, falas)
    }

    private fun escapa(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun desescapa(s: String) = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); else -> append(s[i + 1]) }
                i += 2
            } else { append(c); i++ }
        }
    }

    /** Um quadro na tela: o índice e quantos pixels dele aparecem na área útil (entre as barras). */
    class QuadroVisivel(val indice: Int, val alturaVisivel: Int)

    /**
     * Por onde o "Ouvir" começa, e o que o "Quadro X de N" mostra: o quadro com MAIOR área visível. Os quadros têm a largura
     * da tela, então a área visível é proporcional à altura visível. No empate, o primeiro na ordem do capítulo. Nulo sem
     * quadro visível.
     */
    fun quadroInicial(visiveis: List<QuadroVisivel>): Int? =
        visiveis.filter { it.alturaVisivel > 0 }
            .minWithOrNull(compareBy({ -it.alturaVisivel }, { it.indice }))?.indice

    /** Quanto de [inicio, fim] o intervalo [topo, base) cobre, em pixels; zero quando não se tocam. */
    fun alturaVisivel(topo: Int, base: Int, inicio: Int, fim: Int): Int = maxOf(0, minOf(base, fim) - maxOf(topo, inicio))

    /** O estado de um quadro, só com o que a leitura precisa. */
    enum class Situacao { PREPARANDO, COM_FALAS, SEM_FALAS, ERRO }

    /** O que fazer com o quadro da vez durante a leitura. */
    enum class Passo { LER, PULAR, ESPERAR, PARAR_ERRO }

    /**
     * Quadro confirmado sem fala traduzida (sem texto, ou só falas que voltaram iguais) é pulado; quadro com erro NÃO é
     * pulado: a leitura para em `erro_quadro` e o "Tentar tradução" continua lá. Quadro ainda em preparo espera.
     */
    fun passo(s: Situacao): Passo = when (s) {
        Situacao.COM_FALAS -> Passo.LER
        Situacao.SEM_FALAS -> Passo.PULAR
        Situacao.PREPARANDO -> Passo.ESPERAR
        Situacao.ERRO -> Passo.PARAR_ERRO
    }

    /**
     * Converte o estado do leitor em Situacao. `paraDestinoAtual` diz se o pronto (ou sem texto) foi decidido para o destino
     * de agora; `temDados` se os dados de leitura do quadro estão carregados. Pronto sem dados ainda está em preparo.
     */
    fun situacao(pronto: Boolean, semTexto: Boolean, erro: Boolean, paraDestinoAtual: Boolean, temDados: Boolean, falas: Int): Situacao = when {
        erro -> Situacao.ERRO
        semTexto && paraDestinoAtual -> Situacao.SEM_FALAS
        pronto && paraDestinoAtual && temDados -> if (falas > 0) Situacao.COM_FALAS else Situacao.SEM_FALAS
        else -> Situacao.PREPARANDO
    }

    /** O que vem depois de terminar as falas de um quadro. */
    sealed class Depois {
        /** Sem "Avançar automaticamente": acabou (motivo fim). */
        object Fim : Depois()
        /** Era o último quadro: acabou o capítulo (motivo fim_capitulo). */
        object FimCapitulo : Depois()
        /** Segue para o quadro `indice`. */
        class Proximo(val indice: Int) : Depois()
    }

    fun depoisDoQuadro(indice: Int, total: Int, avancar: Boolean): Depois = when {
        !avancar -> Depois.Fim
        indice + 1 >= total -> Depois.FimCapitulo
        else -> Depois.Proximo(indice + 1)
    }

    /**
     * Quantos pixels rolar para mostrar a fala que vai ser lida, com folga `margem`, dentro da área útil [inicio, fim] (já
     * sem as barras): zero se ela já aparece inteira; positivo rola para baixo; negativo para cima. Fala maior que a área
     * fica com o TOPO à mostra, que é por onde se lê. Rola só o necessário.
     */
    fun deslocamento(topoFala: Int, baseFala: Int, inicio: Int, fim: Int, margem: Int): Int {
        if (fim <= inicio) return 0
        if (topoFala >= inicio && baseFala <= fim) return 0
        val util = fim - inicio
        return if (topoFala < inicio || baseFala - topoFala + 2 * margem > util) topoFala - inicio - margem
        else baseFala - fim + margem
    }

    /** Leva uma coordenada vertical da imagem do quadro (em pixels da imagem) para a tela, com o quadro desenhado na largura dada. */
    fun naTela(yImagem: Int, larguraImagem: Int, larguraTela: Int, topoQuadroNaTela: Int): Int =
        if (larguraImagem <= 0) topoQuadroNaTela else topoQuadroNaTela + (yImagem.toLong() * larguraTela / larguraImagem).toInt()

    /**
     * A rolagem interrompe a leitura? Só a do PRÓPRIO app não interrompe. Arrasto do dedo interrompe sempre, mesmo no meio de
     * uma rolagem do app (ela é cancelada pelo arrasto); rolagem que não é do app (acessibilidade, teclado, comando de
     * navegação) também. Fora da leitura nenhuma rolagem interrompe nada.
     */
    fun rolagemInterrompe(lendo: Boolean, rolando: Boolean, doApp: Boolean, arrasto: Boolean): Boolean =
        lendo && (arrasto || (rolando && !doApp))
}
