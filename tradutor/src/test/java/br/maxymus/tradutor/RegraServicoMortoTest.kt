package br.maxymus.tradutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegraServicoMortoTest {
    @Test fun umaVezSoPorInstancia() {
        val u = RegraServicoMorto.UmaVez()
        assertTrue(u.primeira())
        assertFalse(u.primeira())
        assertFalse(u.primeira())
    }

    @Test fun instanciaNovaEProcessoNovo() {
        val velho = RegraServicoMorto.UmaVez()
        assertTrue(velho.primeira())
        assertTrue(RegraServicoMorto.UmaVez().primeira())
    }

    @Test fun umaVezEntreThreads() {
        val u = RegraServicoMorto.UmaVez()
        val passaram = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = List(8) { Thread { repeat(100) { if (u.primeira()) passaram.incrementAndGet() } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, passaram.get())
    }

    @Test fun minutosDesdeAtualizacao() {
        assertEquals(0L, RegraServicoMorto.minutosDesde(1_000L, 1_000L))
        assertEquals(0L, RegraServicoMorto.minutosDesde(59_999L, 0L))
        assertEquals(1L, RegraServicoMorto.minutosDesde(60_000L, 0L))
        assertEquals(600L, RegraServicoMorto.minutosDesde(36_000_000L + 5_000L, 5_000L))
        assertEquals(0L, RegraServicoMorto.minutosDesde(0L, 60_000L))     // relógio voltou atrás
    }

    @Test fun segundosDesdeInicioDoProcesso() {
        assertEquals(0L, RegraServicoMorto.segundosDesde(999L, 0L))
        assertEquals(42L, RegraServicoMorto.segundosDesde(50_500L, 8_000L))
        assertEquals(0L, RegraServicoMorto.segundosDesde(1L, 2L))
    }
}
