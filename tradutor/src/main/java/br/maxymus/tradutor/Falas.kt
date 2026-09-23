package br.maxymus.tradutor

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Acha as falas na tela: OCR do ML Kit e, em seguida, o AGRUPAMENTO — que foi o primeiro defeito medido na bancada
 * em 22/09. O reconhecedor devolve a fala do balão quebrada em linhas ("you truly are" / "worthy of being my" /
 * "nemesis, warrior of light"), e traduzir linha por linha produz lixo. A regra que consertou, e que o agy
 * recomendou de forma independente: junta dois blocos quando a distância vertical entre eles é menor que 1,6 vez a
 * altura da linha E eles se sobrepõem em pelo menos 25% da largura. Assim balões vizinhos deslocados na horizontal
 * continuam separados, o que também foi verificado.
 */
object Falas {
    class Fala(val texto: String, val caixa: Rect, val alturaLinha: Int)

    private val leitor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Bloqueante (chamar fora da principal). `topo` e `base` recortam a área útil, fora das barras do sistema. */
    fun ler(b: Bitmap, topo: Int, base: Int): List<Fala> = runCatching {
        val r = com.google.android.gms.tasks.Tasks.await(leitor.process(InputImage.fromBitmap(b, 0)), 20, java.util.concurrent.TimeUnit.SECONDS)
        val brutas = ArrayList<Fala>()
        for (bloco in r.textBlocks) for (linha in bloco.lines) {
            val c = linha.boundingBox ?: continue
            if (c.top < topo || c.bottom > base) continue
            if (linha.text.trim().length < 2) continue
            brutas += Fala(linha.text.trim(), Rect(c), max(1, c.height()))
        }
        junta(brutas)
    }.getOrElse { e ->
        Telemetria.evento("erro", mapOf("onde" to "ocr", "msg" to (e.message ?: e::class.java.simpleName).take(120)))
        emptyList()
    }

    private fun junta(entrada: List<Fala>): List<Fala> {
        val l = entrada.sortedWith(compareBy({ it.caixa.top }, { it.caixa.left })).toMutableList()
        var mudou = true
        while (mudou) {
            mudou = false
            fora@ for (i in l.indices) {
                for (j in i + 1 until l.size) {
                    val a = l[i]; val b = l[j]
                    // ALTURA DA LINHA, não do bloco. Usar a altura do bloco era um defeito: depois de juntar
                    // 3 linhas o "bloco" media 150 px, e aí 1,6 vez isso engolia o BALÃO VIZINHO inteiro. Foi o
                    // que aconteceu na captura do dono em 23/09, com dois balões virando um.
                    val alt = max(1, min(a.alturaLinha, b.alturaLinha))
                    val vert = if (b.caixa.top >= a.caixa.bottom) b.caixa.top - a.caixa.bottom else a.caixa.top - b.caixa.bottom
                    val sobrepoe = min(a.caixa.right, b.caixa.right) - max(a.caixa.left, b.caixa.left)
                    val largura = min(a.caixa.width(), b.caixa.width())
                    if (vert < alt * 1.6f && sobrepoe > largura * 0.25f) {
                        val caixa = Rect(a.caixa); caixa.union(b.caixa)
                        val acima = if (a.caixa.top <= b.caixa.top) a else b
                        val abaixo = if (acima === a) b else a
                        l[i] = Fala(acima.texto + " " + abaixo.texto, caixa, (a.alturaLinha + b.alturaLinha) / 2)
                        l.removeAt(j); mudou = true; break@fora
                    }
                }
            }
        }
        return l.filter { it.texto.length >= 3 }
    }
}
