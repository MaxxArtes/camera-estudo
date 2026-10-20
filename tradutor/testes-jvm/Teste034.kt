package br.maxymus.tradutor

private var total = 0
private var falhas = 0
private fun confere(nome: String, ok: Boolean) {
    total++
    if (!ok) falhas++
    println((if (ok) "  OK     " else "  FALHOU ") + nome)
}

fun main() {
    println("1) apenas um modo ativo")
    confere("sem serviços não há captura", modoCaptura(false, false) == ModoCaptura.NENHUM)
    confere("sessão sem acessibilidade usa projeção", modoCaptura(false, true) == ModoCaptura.PROJECAO)
    confere("acessibilidade ligada manda", modoCaptura(true, false) == ModoCaptura.ACESSIBILIDADE)
    confere("acessibilidade ganha também durante a troca", modoCaptura(true, true) == ModoCaptura.ACESSIBILIDADE)

    println("2) sessão e consentimento")
    val s = SessaoCaptura()
    confere("começa ociosa", s.estado == EstadoSessao.OCIOSA)
    confere("consentimento não solicitado não ativa", !s.ativar(0))
    val primeiro = s.pedir()!!
    confere("pedindo consentimento", s.estado == EstadoSessao.PEDINDO)
    confere("duplo toque não abre outro pedido", s.pedir() == null)
    confere("resultado estranho não ativa", !s.ativar(primeiro + 1))
    confere("consentimento correto ativa", s.ativar(primeiro) && s.estado == EstadoSessao.ATIVA)
    confere("sessão ativa não pede de novo", s.pedir() == null)
    confere("token é usado uma vez", !s.ativar(primeiro))
    s.encerrar(peloSistema = true)
    confere("onStop deixa parada pelo sistema", s.estado == EstadoSessao.PARADA_PELO_SISTEMA)
    confere("onStop invalida consentimento anterior", !s.ativar(primeiro))
    val segundo = s.pedir()!!
    confere("retomar pede novo consentimento", segundo != primeiro && s.estado == EstadoSessao.PEDINDO)
    s.negar(primeiro)
    confere("negação atrasada não cancela o novo pedido", s.aguardando(segundo))
    s.negar(segundo)
    confere("negação volta a ociosa", s.estado == EstadoSessao.OCIOSA)
    val terceiro = s.pedir()!!
    s.encerrar()
    confere("encerrar durante o diálogo invalida o resultado", !s.ativar(terceiro))
    val quarto = s.pedir()!!
    s.ativar(quarto); s.encerrar()
    confere("encerramento explícito volta a ociosa", s.estado == EstadoSessao.OCIOSA)
    val recuperada = SessaoCaptura(interrompida = true)
    confere("morte do processo não restaura sessão ativa", recuperada.estado == EstadoSessao.PARADA_PELO_SISTEMA)
    confere("processo novo rejeita token antigo", !recuperada.ativar(quarto))
    confere("processo novo precisa pedir novamente", recuperada.pedir() != null)

    println("3) descarte após esconder a bolha")
    val portao = PortaoQuadro(100)
    confere("quadro anterior é descartado", !portao.aceitar(90))
    confere("último quadro drenado é descartado", !portao.aceitar(100))
    confere("primeiro em trânsito também sai", !portao.aceitar(101))
    confere("duplicado em trânsito também sai", !portao.aceitar(101))
    confere("só o novo quadro é aceito", portao.aceitar(102))
    confere("um toque entrega uma única imagem", !portao.aceitar(103))
    val semQuadro = PortaoQuadro(100)
    confere("fila antiga inteira nunca satisfaz o pedido", (0L..100L).none { semQuadro.aceitar(it) })
    val seguinte = PortaoQuadro(200)
    confere("captura seguinte não reutiliza quadro anterior", !seguinte.aceitar(102))
    confere("sem timestamp válido não entrega", !PortaoQuadro(0).aceitar(0))
    val novaSuperficie = PortaoQuadro(300, superficieNova = true)
    confere("superfície nova também recusa timestamp antigo", !novaSuperficie.aceitar(299))
    confere("primeiro quadro da superfície nova serve mesmo numa página estática", novaSuperficie.aceitar(301))
    confere("superfície nova também entrega uma única vez", !novaSuperficie.aceitar(302))

    println("4) preto e ausência de conteúdo")
    fun rejeita(pixel: (Int, Int) -> Int) = QuadroPreto.rejeitar(640, 100, 1380, pixel)
    confere("preto opaco é rejeitado", rejeita { _, _ -> 0xFF000000.toInt() })
    confere("buffer zerado é rejeitado", rejeita { _, _ -> 0 })
    confere("quase preto uniforme é rejeitado", rejeita { _, _ -> 0xFF070707.toInt() })
    confere("área inválida é rejeitada", QuadroPreto.rejeitar(0, 0, 0) { _, _ -> error("não deve ler") })
    confere("página branca passa", !rejeita { _, _ -> -1 })
    confere("página escura com conteúdo passa", !rejeita { x, _ -> if (x < 320) 0 else -1 })
    confere("cor escura legítima passa", !rejeita { _, _ -> 0xFF001000.toInt() })
    confere("barras claras fora do conteúdo não escondem captura preta",
        rejeita { _, y -> if (y < 100 || y >= 1380) -1 else 0 })
    println("5) motivos seguros para telemetria da sessão")
    confere("usuário encerra sem pedir recuperação", FimSessao.USUARIO.codigo == "usuario" && !FimSessao.USUARIO.peloSistema)
    confere("sistema exige nova sessão", FimSessao.SISTEMA.codigo == "sistema" && FimSessao.SISTEMA.peloSistema)
    confere("troca de modo não é interrupção do sistema",
        FimSessao.ACESSIBILIDADE.codigo == "acessibilidade" && !FimSessao.ACESSIBILIDADE.peloSistema)
    confere("erro exige nova sessão", FimSessao.ERRO.codigo == "erro" && FimSessao.ERRO.peloSistema)
    confere("timeout é sem quadro",
        motivoErroCaptura(ErroCapturaSessao(FalhaCaptura.SEM_QUADRO, "teste")) == "sem_quadro")
    confere("falha de leitura preserva causa e motivo", run {
        val causa = IllegalArgumentException("teste")
        val erro = ErroCapturaSessao(FalhaCaptura.LEITURA, "teste", causa)
        erro.cause === causa && motivoErroCaptura(erro) == "leitura"
    })
    confere("rotação tem motivo próprio",
        motivoErroCaptura(ErroCapturaSessao(FalhaCaptura.ROTACAO, "teste")) == "rotacao")
    confere("falha inesperada não é classificada pela mensagem",
        motivoErroCaptura(IllegalStateException("leitura rotacao sem_quadro")) == "outro")
    println(if (falhas == 0) "TUDO CERTO (0.34): $total verificações" else "FALHAS (0.34): $falhas de $total")
    check(falhas == 0)
}
