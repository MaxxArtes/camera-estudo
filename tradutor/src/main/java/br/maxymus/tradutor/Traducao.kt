package br.maxymus.tradutor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Dois caminhos, escolhidos sozinho conforme a rede (pedido do dono, 23/09):
 *
 *  ONLINE  — MyMemory, SEM CHAVE. Medido na bancada em 23/09: traduz melhor que o motor local
 *            ("Você realmente é digno de ser meu inimigo" contra a versão de máquina), e aceita coreano e japonês
 *            direto para português. Sem chave é o ponto central: nada de segredo dentro do APK.
 *  OFFLINE — ML Kit, pacote baixado uma vez e depois sem internet nenhuma.
 *
 * O idioma de origem é DETECTADO (ML Kit), então serve para qualquer língua que os dois lados conheçam, não só
 * inglês. O destino é escolhido pelo dono.
 *
 * O cache é indexado pelo TEXTO reconhecido, nunca pela imagem: rolar três pixels muda o recorte e o cache nunca
 * acertaria. A chave é normalizada porque o OCR troca "I" por "l" de um quadro para outro.
 */
object Traducao {
    @Volatile var destino: String = TranslateLanguage.PORTUGUESE
    @Volatile var ultimaOrigem: String? = null
    @Volatile var ultimoCaminho: String = "-"
    private val cache = ConcurrentHashMap<String, String>()
    private val tradutores = ConcurrentHashMap<String, Translator>()
    private val identificador by lazy { LanguageIdentification.getClient() }

    private fun chave(origem: String, s: String) = origem + "|" + s.lowercase().filter { it.isLetterOrDigit() }

    fun temRede(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork)
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /** Idioma da fala, detectado no aparelho. Null quando não reconhece. */
    fun origemDe(texto: String): String? = runCatching {
        val c = Tasks.await(identificador.identifyLanguage(texto), 5, TimeUnit.SECONDS)
        if (c == "und") null else c
    }.getOrNull()

    private fun tradutorLocal(origem: String): Translator? = runCatching {
        val de = TranslateLanguage.fromLanguageTag(origem) ?: return null
        tradutores.getOrPut(de + destino) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(de).setTargetLanguage(destino).build())
        }
    }.getOrNull()

    /** Baixa o pacote do par. Bloqueante, pode demorar. */
    fun prepararPacote(origem: String = TranslateLanguage.ENGLISH): Boolean = runCatching {
        val t = tradutorLocal(origem) ?: return false
        Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES)
        Telemetria.evento("pacote_pronto", mapOf("de" to origem, "para" to destino)); true
    }.getOrElse {
        Telemetria.evento("erro", mapOf("onde" to "pacote", "msg" to (it.message ?: "").take(100))); false
    }

    fun pacotePronto(origem: String = TranslateLanguage.ENGLISH): Boolean = runCatching {
        val t = tradutorLocal(origem) ?: return false
        Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()), 1, TimeUnit.SECONDS); true
    }.getOrDefault(false)

    @Volatile var ultimoOnline = 0
    @Volatile var ultimoOffline = 0

    /**
     * Traduz a tela INTEIRA numa requisição só, numerando as falas. Medido em 23/09: o serviço devolve a
     * numeração intacta e cada linha traduzida. Mandar uma requisição por fala estourava o limite de uso e o app
     * caía para o motor local no meio da leitura, que foi o que a telemetria mostrou acontecendo.
     */
    fun traduzirLote(ctx: Context, textos: List<String>): Map<String, String> {
        ultimoOnline = 0; ultimoOffline = 0
        val saida = HashMap<String, String>()
        val origem = origemDe(textos.joinToString(" ").take(300)) ?: "en"
        ultimaOrigem = origem
        if (origem == destino) { textos.forEach { saida[it] = it }; return saida }
        val faltando = ArrayList<String>()
        for (t in textos) {
            val c = cache[chave(origem, t)]
            if (c != null) saida[t] = c else if (t !in faltando) faltando += t
        }
        if (faltando.isEmpty()) return saida
        if (temRede(ctx)) {
            // 1º o NOSSO serviço (modelo de linguagem, melhor qualidade e vê a tela inteira de uma vez)
            porModelo(faltando, origem)?.let { lista ->
                for ((i, t) in faltando.withIndex()) lista.getOrNull(i)?.let { if (it.isNotBlank() && it != t) { saida[t] = it; cache[chave(origem, t)] = it; ultimoOnline++ } }
            }
        }
        val restantes = faltando.filter { it !in saida }
        if (restantes.isNotEmpty() && temRede(ctx)) {
            val junto = restantes.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
            val r = porRede(junto, origem)
            if (r != null) {
                val linhas = r.split("\n").mapNotNull { l ->
                    val m = Regex("^\\s*(\\d+)[.)]\\s*(.+)$").find(l.trim()) ?: return@mapNotNull null
                    m.groupValues[1].toIntOrNull()?.minus(1) to m.groupValues[2].trim()
                }.filter { it.first != null }.associate { it.first!! to it.second }
                if (linhas.size >= restantes.size * 0.6) {
                    for ((i, t) in restantes.withIndex()) linhas[i]?.let { saida[t] = it; cache[chave(origem, t)] = it; ultimoOnline++ }
                }
            }
        }
        for (t in faltando) if (t !in saida) {
            val l = local(t, origem)
            if (l != null) { saida[t] = l; cache[chave(origem, t)] = l; ultimoOffline++ } else saida[t] = t
        }
        ultimoCaminho = when { ultimoOnline > 0 && ultimoOffline == 0 -> "online"; ultimoOnline == 0 && ultimoOffline > 0 -> "offline"; ultimoOnline > 0 -> "misto"; else -> "cache" }
        return saida
    }

    /** Traduz uma fala. Bloqueante. Devolve o original quando tudo falha, para a tela nunca ficar vazia. */
    fun traduzir(ctx: Context, texto: String): String {
        val origem = origemDe(texto) ?: "en"
        ultimaOrigem = origem
        if (origem == destino) return texto
        val k = chave(origem, texto)
        if (k.length <= origem.length + 1) return texto
        cache[k]?.let { return it }
        val online = if (temRede(ctx)) porRede(texto, origem) else null
        val r = online ?: local(texto, origem) ?: texto
        ultimoCaminho = if (online != null) "online" else "offline"
        if (r != texto) cache[k] = r
        return r
    }

    /**
     * NOSSO serviço: modelo de linguagem, a tela inteira numa chamada. A chave do provedor fica NO SERVIDOR; o app
     * manda só um token que dá direito a traduzir dentro de uma cota. Foi por isso que ele existe: o APK é
     * instalado fora da loja e qualquer um consegue abrir e ler o que está dentro.
     * Medido em 23/09: acerta "Você é o Sr. Seonghyeon Han?", que os outros dois caminhos erravam.
     */
    private fun porModelo(falas: List<String>, origem: String): List<String>? {
        if (BuildConfig.TRADUTOR_TOKEN.isEmpty() || falas.isEmpty()) return null
        return runCatching {
            val corpo = JSONObject().put("de", origem).put("para", "português do Brasil")
                .put("falas", org.json.JSONArray(falas)).toString().toByteArray()
            val con = (URL("https://pocketlm.maxymus.dev.br/tradutor/traduzir").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 8_000; readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer " + BuildConfig.TRADUTOR_TOKEN)
                setRequestProperty("Content-Type", "application/json")
            }
            con.outputStream.use { it.write(corpo) }
            if (con.responseCode != 200) { ultimoModelo = "http " + con.responseCode; return null }
            val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            ultimoModelo = j.optString("modelo", "?")
            val a = j.getJSONArray("falas")
            List(a.length()) { a.getString(it) }
        }.getOrElse { ultimoModelo = "falhou"; null }
    }

    @Volatile var ultimoModelo: String = "-"

    /** MyMemory, sem chave. Reserva para quando o nosso serviço não responde. */
    private fun porRede(texto: String, origem: String): String? = runCatching {
        val par = URLEncoder.encode("$origem|${destino.lowercase()}", "UTF-8")
        val q = URLEncoder.encode(texto.take(900), "UTF-8")
        val con = (URL("https://api.mymemory.translated.net/get?q=$q&langpair=$par").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 12_000
            setRequestProperty("User-Agent", "TradutorDeTela/0.5 (app pessoal)")
        }
        if (con.responseCode != 200) return null
        val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        if (j.optBoolean("quotaFinished", false)) { Telemetria.evento("cota_online"); return null }
        val t = j.getJSONObject("responseData").optString("translatedText").trim()
        // a API devolve aviso em MAIÚSCULAS quando não traduz de verdade
        if (t.isBlank() || t.startsWith("NO QUERY", true) || t.startsWith("QUERY LENGTH", true) || t.equals(texto, true)) null else t
    }.getOrNull()

    private fun local(texto: String, origem: String): String? = runCatching {
        val t = tradutorLocal(origem) ?: return null
        Tasks.await(t.translate(texto), 15, TimeUnit.SECONDS)
    }.getOrNull()

    val noCache: Int get() = cache.size
}
