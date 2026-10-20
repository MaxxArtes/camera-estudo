package br.maxymus.tradutor

import java.io.File

// Testes JVM puros do Tradutor 0.33 (no padrao de harness_idioma): faixas e emenda, onomatopeia, faixa no balao (RegraBalao),
// chave do cache compativel com a 0.31, erro de formato do servidor e teto do banco de avaliacao. Compilam contra os fontes
// REAIS (RegrasTexto.kt, Traducao.kt, Avaliacao.kt); nada e copiado. Rodar: tradutor/testes-jvm/roda033.sh

private var falhas = 0
private var total = 0

private fun confere(nome: String, ok: Boolean, detalhe: String = "") {
    total++
    if (!ok) falhas++
    println((if (ok) "  OK     " else "  FALHOU ") + nome + (if (detalhe.isNotEmpty()) "   [$detalhe]" else ""))
}

private fun linha(texto: String, e: Int, t: Int, d: Int, b: Int) = RegraJuncao.Linha(texto, Caixa(e, t, d, b), b - t)

/** A cortada da 0.33 antes desta correcao: qualquer linha que encostasse numa emenda saia da faixa. */
private fun cortadaAntiga(c: Caixa, faixaTopo: Int, faixaBase: Int, alturaImagem: Int, folga: Int = 2): Boolean =
    (faixaTopo > 0 && c.topo <= faixaTopo + folga) || (faixaBase < alturaImagem && c.base >= faixaBase - folga)

/**
 * O que sobra de uma linha real [topo, base) de uma pagina de altura H depois de lida faixa por faixa: o reconhecedor devolve
 * a parte visivel (inteira, ou cortada na borda da faixa, desde que apareca pelo menos 10 px), `descarta` tira o que encosta
 * na emenda, e semRepetidas junta o que se repete.
 */
private fun sobra(topo: Int, base: Int, H: Int, descarta: (Caixa, Int, Int, Int) -> Boolean): List<Caixa> {
    val lidas = ArrayList<Caixa>()
    for ((y0, y1) in RegraFaixas.cortes(H)) {
        val a = maxOf(topo, y0); val b = minOf(base, y1)
        if (b - a < minOf(base - topo, 10)) continue
        val c = Caixa(100, a, 400, b)
        if (!descarta(c, y0, y1, H)) lidas += c
    }
    return RegraFaixas.semRepetidas(lidas) { it }
}

fun main() {
    println("1) faixas e emenda")
    run {
        val c = RegraFaixas.cortes(12000)
        confere("cortes cobrem a pagina de 0 a 12000", c.first().first == 0 && c.last().second == 12000)
        confere("cada faixa tem no maximo 1600 px", c.all { it.second - it.first <= RegraFaixas.ALTURA_FAIXA })
        confere("faixas vizinhas se sobrepoem em 240 px", c.zipWithNext().all { (a, b) -> a.second - b.first == RegraFaixas.SOBREPOSICAO })
        confere("so a imagem alta e cortada", !RegraFaixas.precisaCortar(1080, 2400) && RegraFaixas.precisaCortar(800, 12000))

        val H = 12000
        val limites = RegraFaixas.cortes(H)
        // linhas baixas e altas em toda posicao perto das emendas e longe delas
        var semNada = 0; var semNadaAntiga = 0; var fragmentada = 0; var primeira: String? = null
        for (altura in listOf(30, 120, 200, 400, 700)) {
            for (topo in 0..(H - altura) step 3) {
                val base = topo + altura
                val perto = limites.any { (y0, y1) -> kotlin.math.abs(topo - y0) < 900 || kotlin.math.abs(base - y1) < 900 }
                if (!perto) continue
                val novas = sobra(topo, base, H) { c, a, b, h -> RegraFaixas.cortada(c, a, b, h) }
                val antigas = sobra(topo, base, H) { c, a, b, h -> cortadaAntiga(c, a, b, h) }
                if (novas.isEmpty()) { semNada++; if (primeira == null) primeira = "altura=$altura topo=$topo" }
                if (antigas.isEmpty()) semNadaAntiga++
                if (altura <= 200 && novas.size == 1 && (novas[0].topo != topo || novas[0].base != base)) fragmentada++
                if (altura <= 200 && novas.size != 1) fragmentada++
            }
        }
        confere("nenhuma linha (30 a 700 px) some das duas faixas, em nenhuma posicao perto de emenda", semNada == 0, "sumiram=$semNada primeira=$primeira")
        confere("linha de ate 200 px sai UMA vez e inteira (nao em pedacos)", fragmentada == 0, "fragmentadas=$fragmentada")
        confere("o teste pega o defeito: a regra antiga perdia linha alta na emenda", semNadaAntiga > 0, "sumiam=$semNadaAntiga")

        val ft = limites[0].second
        confere("linha de 400 px cruzando a emenda fica (cortada) na faixa de cima e sai da de baixo, que so a teria cortada no topo",
            !RegraFaixas.cortada(Caixa(0, ft - 300, 100, ft), 0, ft, H) &&
            RegraFaixas.cortada(Caixa(0, limites[1].first, 100, ft + 100), limites[1].first, limites[1].second, H))
        confere("linha curta que encosta na base da faixa sai dela (a de baixo a tem inteira)",
            RegraFaixas.cortada(Caixa(0, ft - 20, 100, ft), 0, ft, H))
        confere("linha que encosta no topo da faixa de baixo sai dela (a de cima a leu)",
            RegraFaixas.cortada(Caixa(0, limites[1].first, 100, limites[1].first + 40), limites[1].first, limites[1].second, H))
        confere("a borda da imagem nao e emenda: linha colada no topo e na base fica",
            !RegraFaixas.cortada(Caixa(0, 0, 100, 30), 0, limites[0].second, H) &&
            !RegraFaixas.cortada(Caixa(0, H - 30, 100, H), limites.last().first, H, H))
        confere("semRepetidas: mesma linha lida em duas faixas vira uma, a de maior area",
            RegraFaixas.semRepetidas(listOf(Caixa(0, 100, 200, 130), Caixa(0, 100, 200, 140))) { it }.let { it.size == 1 && it[0].base == 140 })
    }

    println("2) onomatopeia x balao")
    run {
        // balao de 3 linhas, a do meio 2,5x maior que as outras
        val balao = listOf(
            linha("I TOLD YOU", 100, 100, 300, 120),
            linha("NOT TO", 100, 124, 300, 174),
            linha("COME HERE", 100, 178, 300, 198))
        confere("a linha do meio passa de 2,2x a mediana (o caso exercita o limiar)", RegraSfx.grandes(balao.map { it.alturaLinha }) == setOf(1))
        val r = RegraJuncao.juntar(balao)
        confere("balao de 3 linhas com a do meio 2,5x maior continua UMA fala", r.size == 1 && !r[0].sfx && r[0].texto == "I TOLD YOU NOT TO COME HERE", r.joinToString { it.texto })

        val ao_lado = balao + linha("BOOOM", 500, 110, 900, 190)
        val r2 = RegraJuncao.juntar(ao_lado)
        confere("onomatopeia solta ao lado sai da juncao: 2 falas", r2.size == 2, r2.joinToString(" | ") { it.texto + (if (it.sfx) " [sfx]" else "") })
        confere("a onomatopeia vem marcada sfx e o balao nao", r2.count { it.sfx } == 1 && r2.first { it.sfx }.texto == "BOOOM" && r2.first { !it.sfx }.texto.startsWith("I TOLD"))

        val altaLonge = balao + linha("KRAAAK", 100, -200, 300, -120)
        val r4 = RegraJuncao.juntar(altaLonge)
        confere("onomatopeia solta bem acima: sai (2 falas, uma sfx)", r4.size == 2 && r4.count { it.sfx } == 1, r4.joinToString(" | ") { it.texto + (if (it.sfx) " [sfx]" else "") })

        val titulo = listOf(
            linha("CHAPTER ONE", 100, 10, 500, 100),
            linha("THE BEGINNING", 100, 110, 500, 200),
            linha("fala um aqui", 100, 400, 300, 420),
            linha("outra fala ali", 100, 600, 300, 620),
            linha("mais uma fala", 100, 800, 300, 820))
        val r5 = RegraJuncao.juntar(titulo)
        confere("titulo de 2 linhas grandes juntas, sem comum perto: 1 fala sfx e as 3 comuns separadas",
            r5.size == 4 && r5.count { it.sfx } == 1 && r5.first { it.sfx }.texto == "CHAPTER ONE THE BEGINNING", r5.joinToString(" | ") { it.texto + (if (it.sfx) " [sfx]" else "") })

        confere("com menos de 3 linhas nada e marcado", RegraJuncao.sfx(listOf(linha("AAA", 0, 0, 100, 90), linha("bbb", 500, 0, 600, 20))).isEmpty())
        confere("ligadas: vao menor que 1,6x a altura menor e 25% de largura em comum",
            RegraJuncao.ligadas(Caixa(0, 0, 100, 20), 20, Caixa(0, 40, 100, 90), 50) &&
            !RegraJuncao.ligadas(Caixa(0, 0, 100, 20), 20, Caixa(0, 60, 100, 90), 50) &&
            !RegraJuncao.ligadas(Caixa(0, 0, 100, 20), 20, Caixa(80, 22, 300, 90), 50))
    }

    println("3) faixa no balao (RegraBalao)")
    run {
        val w = 400; val h = 300
        val branco = 0xFFFFFFFF.toInt(); val escuro = 0xFF101010.toInt()
        // arte escura com um balao branco de (100,80) a (300,220)
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 100 until 300 && y in 80 until 220) branco else escuro }
        val texto = Caixa(150, 130, 250, 170)
        val b = RegraBalao.interior(px, w, h, texto, branco, 120)
        confere("acha o interior do balao claro", b != null && b.esquerda == 100 && b.direita == 300 && b.topo == 80 && b.base == 220,
            b?.let { "${it.esquerda},${it.topo},${it.direita},${it.base}" } ?: "nulo")
        val sem = RegraBalao.interior(IntArray(w * h) { escuro }, w, h, texto, escuro, 60)
        confere("sem parede (fundo uniforme) devolve nulo e a faixa fica como estava", sem == null)
        confere("encaixa: faixa que saia pela direita volta para dentro", RegraBalao.encaixa(250, 330, 100, 300) == (220 to 300))
        confere("encaixa: faixa maior que o balao fica como esta", RegraBalao.encaixa(90, 330, 100, 300) == (90 to 330))
        confere("encaixa: faixa que cabe nao se mexe", RegraBalao.encaixa(120, 200, 100, 300) == (120 to 200))
        confere("encaixa: faixa saindo pela esquerda volta", RegraBalao.encaixa(60, 140, 100, 300) == (100 to 180))
        confere("limites invalidos nao quebram", RegraBalao.interior(IntArray(0), 0, 0, texto, branco, 10) == null)
    }

    println("4) cache: chave compativel com a 0.31 e contrato antigo do servidor")
    run {
        val chave = Traducao::class.java.getDeclaredMethod("chave", String::class.java, String::class.java).also { it.isAccessible = true }
        fun k(t: String, d: String) = chave.invoke(Traducao, t, d) as String
        confere("chave da 0.31: destino|texto normalizado", k("Hello, World! 2", "pt") == "pt|hello world 2", k("Hello, World! 2", "pt"))
        confere("a chave nao leva revisao (nada de #r)", !k("Hello", "pt").contains("#") && !k("Hello", "en").contains("#"))
        confere("destino do pedido manda na chave", k("Hello", "en") == "en|hello")
        confere("o texto da chave e o que vem depois do primeiro |", k("Run!", "pt").substringAfter('|') == "run")
        val f = Traducao::class.java.getDeclaredField("CONTRATO_V2").also { it.isAccessible = true }
        confere("o app ainda NAO fala o contrato v2 (sem ids, tipos, contexto nem recuperacao)", f.getBoolean(null) == false)
        confere("400 \"json invalido\" e \"sem falas\" sao erro de formato", RegraPedidoModelo.erroDeFormato(400, "{\"erro\": \"json invalido\"}") &&
            RegraPedidoModelo.erroDeFormato(422, "{\"erro\": \"sem falas\"}"))
        confere("400 de outra causa, 413, 429, 503 e 200 nao desligam nada",
            !RegraPedidoModelo.erroDeFormato(400, "{\"erro\": \"tamanho invalido\"}") && !RegraPedidoModelo.erroDeFormato(400, "limite do modelo") &&
            !RegraPedidoModelo.erroDeFormato(413, "json invalido") && !RegraPedidoModelo.erroDeFormato(429, "") &&
            !RegraPedidoModelo.erroDeFormato(503, "ocupado") && !RegraPedidoModelo.erroDeFormato(400, ""))
        val ids = RegraPedidoModelo.ids(3)
        confere("resposta no formato antigo (lista) alinha por posicao", RegraPedidoModelo.alinhar(ids, null, listOf("a", " ", "c")) == listOf("a", null, "c"))
    }

    println("5) banco de avaliacao")
    run {
        val dir = java.nio.file.Files.createTempDirectory("aval").toFile()
        try {
            File(dir, "ligado").writeText("")
            for (i in 1..5) File(dir, "q$i.jpg").also { it.writeBytes(ByteArray(100)); it.setLastModified(1_000_000L * i) }
            val sobrou = Avaliacao.limitar(dir, 250)
            val restam = dir.list()!!.sorted()
            confere("apaga os mais antigos ate caber no teto e guarda o ligado", sobrou == 200L && restam == listOf("ligado", "q4.jpg", "q5.jpg"), restam.toString())
            confere("dentro do teto nao apaga nada", Avaliacao.limitar(dir, 250) == 200L && dir.list()!!.size == 3)
            confere("o teto e de 200 MB", Avaliacao.TETO_BYTES == 200L * 1024 * 1024)
        } finally { dir.deleteRecursively() }
        val cu = Class.forName("sun.misc.Unsafe")
        val u = cu.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null)
        val ctx = cu.getMethod("allocateInstance", Class::class.java).invoke(u, Class.forName("android.content.ContextWrapper")) as android.content.Context
        confere("ligada() sem aparelho devolve falso, sem lancar e sem criar pasta", !Avaliacao.ligada(ctx))
    }

    println(if (falhas == 0) "TUDO CERTO (0.33): $total verificacoes" else "FALHAS (0.33): $falhas de $total")
    if (falhas != 0) System.exit(1)
}
