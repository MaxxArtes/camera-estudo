package br.maxymus.tradutor

import android.content.Context
import android.graphics.Bitmap

/** Resultado comum às duas fontes de captura; só as falas pintadas entram na voz. */
internal class Pintada(val fala: Falas.Fala, val traduzida: String)
internal class Congelada(
    val camada: Bitmap, val falas: List<Falas.Fala>, val resultado: ResultadoLote, val pintadas: List<Pintada>
)

/** OCR em faixas, tradução e pintura da 0.33. Executar fora da thread principal. */
internal object FluxoCongelado {
    class Processada(
        val congelada: Congelada? = null, val tudoNoIdioma: Boolean = false,
        val fracaoSuspeita: Float? = null, val ocrFalhou: Boolean = false,
        val msOcr: Long = 0, val msTrad: Long = 0, val msPint: Long = 0
    )

    fun processar(ctx: Context, tela: Bitmap, topo: Int, base: Int, ativo: () -> Boolean): Processada {
        val tOcr = System.nanoTime()
        val lidas = Falas.lerOuNulo(tela, topo, base)
        val falas = lidas?.falas.orEmpty()
        val msOcr = (System.nanoTime() - tOcr) / 1_000_000
        if (falas.isEmpty()) {
            val fracao = if (lidas?.algumTexto == false)
                RegraCaptura.fracaoEscura(tela, topo, base).takeIf { RegraCaptura.suspeita(0, false, it) }
            else null
            return Processada(fracaoSuspeita = fracao, ocrFalhou = lidas == null, msOcr = msOcr)
        }
        val tTrad = System.nanoTime()
        val r = Traducao.traduzirLoteDetalhado(ctx, falas.map { it.texto }, ativo = ativo)
        val msTrad = (System.nanoTime() - tTrad) / 1_000_000
        val pintadas = pintadas(falas, r.mapa)
        if (pintadas.isEmpty() && falas.all { it.texto in r.puladas })
            return Processada(tudoNoIdioma = true, msOcr = msOcr, msTrad = msTrad)
        // Sem resultado útil ainda, a revisão posterior do cache completa a camada transparente.
        val tPint = System.nanoTime()
        val camada = pintar(tela, pintadas)
        val msPint = (System.nanoTime() - tPint) / 1_000_000
        return Processada(Congelada(camada, falas, r, pintadas), msOcr = msOcr, msTrad = msTrad, msPint = msPint)
    }

    fun pintadas(falas: List<Falas.Fala>, mapa: Map<String, String>): List<Pintada> =
        falas.filter { (mapa[it.texto] ?: it.texto) != it.texto }
            .map { Pintada(it, mapa[it.texto] ?: it.texto) }

    fun pintar(tela: Bitmap, pintadas: List<Pintada>): Bitmap {
        if (pintadas.isEmpty()) return Bitmap.createBitmap(tela.width, tela.height, Bitmap.Config.ARGB_8888)
        val mapa = pintadas.associate { it.fala.texto to it.traduzida }
        return Pintura.camada(tela, pintadas.map { it.fala }) { mapa[it] ?: it }
    }

    fun ouvir(voz: Voz, pintadas: List<Pintada>, onde: String) {
        if (pintadas.isEmpty()) { voz.semFalas(); return }
        val ordem = RegraVoz.ordemDeLeitura(pintadas.map {
            RegraVoz.PosicaoFala(it.fala.caixa.left, it.fala.caixa.top, it.fala.alturaLinha)
        })
        voz.ouvir(ordem.map { pintadas[it].traduzida }, onde)
    }
}
