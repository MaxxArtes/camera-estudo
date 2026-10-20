package br.maxymus.tradutor

/** A configuração ligada tem prioridade mesmo enquanto o serviço de acessibilidade reconecta. */
internal enum class ModoCaptura { NENHUM, ACESSIBILIDADE, PROJECAO }
internal fun modoCaptura(acessibilidadeLigada: Boolean, projecaoAtiva: Boolean): ModoCaptura = when {
    acessibilidadeLigada -> ModoCaptura.ACESSIBILIDADE
    projecaoAtiva -> ModoCaptura.PROJECAO
    else -> ModoCaptura.NENHUM
}

internal enum class EstadoSessao { OCIOSA, PEDINDO, ATIVA, PARADA_PELO_SISTEMA }

internal enum class FimSessao(val codigo: String, val peloSistema: Boolean) {
    USUARIO("usuario", false), SISTEMA("sistema", true),
    ACESSIBILIDADE("acessibilidade", false), ERRO("erro", true)
}

internal enum class FalhaCaptura(val codigo: String) {
    SEM_QUADRO("sem_quadro"), LEITURA("leitura"), ROTACAO("rotacao"), OUTRO("outro")
}

internal class ErroCapturaSessao(val motivo: FalhaCaptura, mensagem: String, causa: Throwable? = null) :
    IllegalStateException(mensagem, causa)

internal fun motivoErroCaptura(erro: Throwable): String =
    ((erro as? ErroCapturaSessao)?.motivo ?: FalhaCaptura.OUTRO).codigo

/** Token local invalida consentimentos atrasados; nunca permite reutilizar uma sessão encerrada. */
internal class SessaoCaptura(interrompida: Boolean = false) {
    var estado = if (interrompida) EstadoSessao.PARADA_PELO_SISTEMA else EstadoSessao.OCIOSA
        private set
    private var geracao = 0L

    fun pedir(): Long? {
        if (estado == EstadoSessao.ATIVA || estado == EstadoSessao.PEDINDO) return null
        estado = EstadoSessao.PEDINDO
        return ++geracao
    }

    fun aguardando(pedido: Long) = estado == EstadoSessao.PEDINDO && pedido == geracao
    fun ativar(pedido: Long): Boolean {
        if (!aguardando(pedido)) return false
        estado = EstadoSessao.ATIVA
        return true
    }
    fun negar(pedido: Long) {
        if (aguardando(pedido)) encerrar()
    }
    fun encerrar(peloSistema: Boolean = false) {
        ++geracao
        estado = if (peloSistema) EstadoSessao.PARADA_PELO_SISTEMA else EstadoSessao.OCIOSA
    }
}

/**
 * Após esconder a janela e esperar o compositor, drenamos o ImageReader. O primeiro quadro posterior
 * ainda pode estar em trânsito: também é descartado, salvo se a superfície foi substituída.
 * Uma superfície nova não tem quadros anteriores à ocultação. Só aceitamos timestamp crescente, uma vez.
 * Não comparamos relógios de origens diferentes: o marco vem do próprio ImageReader.
 */
internal class PortaoQuadro(private var ultimoDescartado: Long, superficieNova: Boolean = false) {
    private var descartouEmTransito = superficieNova
    private var entregue = false
    fun aceitar(timestamp: Long): Boolean {
        if (entregue || timestamp <= ultimoDescartado) return false
        ultimoDescartado = timestamp
        if (!descartouEmTransito) { descartouEmTransito = true; return false }
        entregue = true
        return true
    }
}

/** Preto quase uniforme no conteúdo é erro de captura, sem afirmar que a tela é protegida. */
internal object QuadroPreto {
    fun rejeitar(largura: Int, topo: Int, base: Int, pixel: (Int, Int) -> Int): Boolean {
        if (largura <= 0 || base <= topo) return true
        var claros = 0
        val amostras = 64 * 128
        for (y in 0 until 128) for (x in 0 until 64) {
            val cor = pixel(((2L * x + 1) * largura / 128).toInt(),
                topo + ((2L * y + 1) * (base - topo) / 256).toInt())
            if ((cor ushr 16 and 255) >= 8 || (cor ushr 8 and 255) >= 8 || (cor and 255) >= 8) claros++
        }
        return claros * 1000 <= amostras // pelo menos 99,9% preto, não uma página apenas escura
    }
}
