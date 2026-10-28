package br.maxymus.tradutor

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Regras do evento `servico_listado_morto`, sem Android, para rodar na JVM. A tela confere o serviço ao nascer e a cada
 * volta (ON_RESUME), e as duas conferências caíam no mesmo caso: cada falha chegava contada duas vezes (19/10 a 23/10).
 * Agora o evento sai uma vez por processo do app, e leva há quanto tempo o app foi atualizado e há quanto tempo o processo
 * existe, para separar a morte por atualização (o HyperOS nem sempre religa depois de instalar) da morte durante a noite.
 */
internal object RegraServicoMorto {
    /** Deixa passar só a primeira vez. Guardada em campo estático, vale para o processo inteiro; processo novo emite de novo. */
    class UmaVez {
        private val feito = AtomicBoolean(false)
        fun primeira(): Boolean = feito.compareAndSet(false, true)
    }

    /** Minutos inteiros de `desdeMs` até `agoraMs` (relógio de parede); relógio que voltou atrás dá zero. */
    fun minutosDesde(agoraMs: Long, desdeMs: Long): Long = if (agoraMs <= desdeMs) 0L else (agoraMs - desdeMs) / 60_000L

    /** Segundos inteiros de `desdeMs` até `agoraMs` (relógio desde o boot); nunca negativo. */
    fun segundosDesde(agoraMs: Long, desdeMs: Long): Long = if (agoraMs <= desdeMs) 0L else (agoraMs - desdeMs) / 1_000L
}
