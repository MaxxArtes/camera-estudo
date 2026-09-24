package br.maxymus.tradutor

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.concurrent.TimeUnit

/**
 * Pacotes de idioma para o uso SEM INTERNET. Cada idioma é um arquivo separado no aparelho, na casa de algumas
 * dezenas de MB, e o dono escolhe quais quer guardar — por isso dá para apagar, não só baixar.
 *
 * Com internet nada disso é preciso: o caminho online entende qualquer idioma. Isto existe para quando não há rede.
 * O português entra sempre, porque é o destino e sem ele nenhum par funciona.
 */
object Idiomas {
    /** Os que aparecem em quadrinho, na frente; o resto em ordem. */
    val LISTA: List<Pair<String, String>> = listOf(
        TranslateLanguage.PORTUGUESE to "Português (destino)",
        TranslateLanguage.ENGLISH to "Inglês",
        TranslateLanguage.KOREAN to "Coreano",
        TranslateLanguage.JAPANESE to "Japonês",
        TranslateLanguage.CHINESE to "Chinês",
        TranslateLanguage.SPANISH to "Espanhol",
        TranslateLanguage.FRENCH to "Francês",
        TranslateLanguage.ITALIAN to "Italiano",
        TranslateLanguage.GERMAN to "Alemão",
        TranslateLanguage.RUSSIAN to "Russo",
        TranslateLanguage.INDONESIAN to "Indonésio",
        TranslateLanguage.THAI to "Tailandês",
        TranslateLanguage.VIETNAMESE to "Vietnamita",
        TranslateLanguage.TURKISH to "Turco",
        TranslateLanguage.ARABIC to "Árabe",
        TranslateLanguage.HINDI to "Hindi",
        TranslateLanguage.POLISH to "Polonês",
        TranslateLanguage.DUTCH to "Holandês"
    )

    private val gerente by lazy { RemoteModelManager.getInstance() }
    private fun modelo(tag: String) = TranslateRemoteModel.Builder(tag).build()

    /** Tags já baixadas. Bloqueante. */
    fun baixados(): Set<String> = runCatching {
        Tasks.await(gerente.getDownloadedModels(TranslateRemoteModel::class.java), 10, TimeUnit.SECONDS)
            .map { it.language }.toSet()
    }.getOrDefault(emptySet())

    /** Baixa um pacote. Bloqueante, pode demorar. */
    fun baixar(tag: String): Boolean = runCatching {
        Tasks.await(gerente.download(modelo(tag), DownloadConditions.Builder().build()), 15, TimeUnit.MINUTES)
        Telemetria.evento("idioma_baixado", mapOf("idioma" to tag)); true
    }.getOrElse {
        Telemetria.evento("erro", mapOf("onde" to "idioma_baixar", "idioma" to tag, "msg" to (it.message ?: "").take(90))); false
    }

    /** Apaga um pacote para liberar espaço. */
    fun apagar(tag: String): Boolean = runCatching {
        Tasks.await(gerente.deleteDownloadedModel(modelo(tag)), 60, TimeUnit.SECONDS)
        Telemetria.evento("idioma_apagado", mapOf("idioma" to tag)); true
    }.getOrDefault(false)

    fun nome(tag: String): String = LISTA.firstOrNull { it.first == tag }?.second?.removeSuffix(" (destino)") ?: tag

    /** nome por extenso, para o pedido ao modelo de linguagem ("traduza para X") */
    fun nomeCheio(tag: String): String = when (tag) {
        TranslateLanguage.PORTUGUESE -> "português do Brasil"
        else -> nome(tag)
    }
}
