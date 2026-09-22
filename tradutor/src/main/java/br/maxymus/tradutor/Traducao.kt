package br.maxymus.tradutor

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Tradução no aparelho (ML Kit), sem chave e sem servidor. O pacote de idioma é baixado uma vez.
 *
 * O cache é indexado pelo TEXTO reconhecido, nunca pela imagem: rolar a tela três pixels muda o recorte e o
 * código da imagem, e o cache não acharia nada. Por texto, a mesma fala reaproveita a tradução onde quer que ela
 * apareça — e nome de personagem e bordão se repetem o capítulo inteiro. A chave é normalizada (só letras e
 * números, tudo em minúscula) porque o OCR troca "I" por "l" de um quadro para o outro e isso partiria o cache.
 */
object Traducao {
    @Volatile private var tradutor: Translator? = null
    @Volatile var pacotePronto = false
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun chave(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun motor(): Translator = tradutor ?: synchronized(this) {
        tradutor ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE).build()
        ).also { tradutor = it }
    }

    /** Baixa o pacote de idioma. Bloqueante. True quando está pronto para uso offline. */
    fun prepararPacote(): Boolean = runCatching {
        Tasks.await(motor().downloadModelIfNeeded(DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
        pacotePronto = true
        Telemetria.evento("pacote_pronto")
        true
    }.getOrElse { e ->
        Telemetria.evento("erro", mapOf("onde" to "pacote", "msg" to (e.message ?: e::class.java.simpleName).take(120)))
        false
    }

    /** Bloqueante. Devolve o original quando falha, para a tela nunca ficar vazia. */
    fun traduzir(texto: String): String {
        val k = chave(texto)
        if (k.isEmpty()) return texto
        cache[k]?.let { return it }
        return runCatching { Tasks.await(motor().translate(texto), 15, TimeUnit.SECONDS) }
            .getOrElse { texto }
            .also { cache[k] = it }
    }

    val noCache: Int get() = cache.size
}
