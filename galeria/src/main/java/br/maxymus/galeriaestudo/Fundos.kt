package br.maxymus.galeriaestudo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Busca de imagem de fundo na internet. Fonte: Wikimedia Commons, que responde SEM CHAVE (nada de segredo no APK) e
 * declara licença e autor por arquivo. Medido na bancada em 21/09: 1 s por busca, miniatura pronta em qualquer largura.
 * Cada arquivo tem a sua licença (domínio público, CC BY, CC BY-SA); as CC pedem crédito ao autor, por isso o crédito
 * viaja junto com o achado e é gravado na receita.
 */
object Fundos {
    private const val API = "https://commons.wikimedia.org/w/api.php"
    private const val AGENTE = "GaleriaEstudo/0.24 (app pessoal; magurofg@gmail.com)"
    const val LARGURA_USO = 1600
    private const val LARGURA_MINI = 320
    private const val TETO_BYTES = 20L * 1024 * 1024

    data class Achado(val titulo: String, val miniatura: String, val imagem: String, val licenca: String, val autor: String, val pagina: String) {
        /** Linha de crédito curta; vazia quando a licença não exige atribuição. */
        val credito: String get() = when {
            autor.isBlank() && licenca.isBlank() -> ""
            autor.isBlank() -> licenca
            licenca.isBlank() -> autor
            else -> "$autor · $licenca"
        }
    }

    /** Tira marcação HTML e espaço sobrando dos campos do Commons (o autor vem como link). */
    private fun limpo(s: String?): String =
        (s ?: "").replace(Regex("<[^>]*>"), " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
            .replace(Regex("\\s+"), " ").trim().take(80)

    /**
     * Só entram licenças que permitem usar a imagem DENTRO de outra obra sem contaminar a foto do dono: domínio
     * público, CC0 e CC BY. CC BY-SA fica de fora de propósito (decisão do Astra, 21/09): ela pode exigir publicar a
     * foto editada sob os mesmos termos, o que um crédito não resolve. Licença que não dá para ler também fica fora.
     */
    private fun permitida(etiqueta: String, curta: String): Boolean {
        val e = etiqueta.ifBlank { curta.lowercase() }
        if (e.isBlank()) return false
        if ("sa" in e.split("-", " ", "/")) return false
        return e.startsWith("cc0") || e.startsWith("pd") || "public domain" in e || "domínio público" in e || e.startsWith("cc-by") || e.startsWith("cc by")
    }

    /** Busca bloqueante (chamar em IO). Lista vazia quando não acha; null quando a rede falha. */
    fun buscar(termo: String, quantos: Int = 50): List<Achado>? {
        val q = URLEncoder.encode("filetype:bitmap " + termo.trim(), "UTF-8")
        val url = "$API?action=query&generator=search&gsrsearch=$q&gsrlimit=$quantos&gsrnamespace=6" +
            "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=$LARGURA_MINI&format=json&formatversion=2"
        val texto = try {
            val con = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 20_000; setRequestProperty("User-Agent", AGENTE); setRequestProperty("Accept", "application/json")
            }
            if (con.responseCode != 200) { Telemetria.evento("erro", mapOf("onde" to "fundo_buscar", "http" to con.responseCode)); return null }
            con.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Telemetria.evento("erro", mapOf("onde" to "fundo_buscar", "msg" to (e.message ?: e::class.java.simpleName).take(120))); return null
        }
        return runCatching {
            val paginas = org.json.JSONObject(texto).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
            (0 until paginas.length()).mapNotNull { i ->
                val p = paginas.getJSONObject(i)
                val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
                val mini = ii.optString("thumburl").ifBlank { return@mapNotNull null }
                val em = ii.optJSONObject("extmetadata")
                val etiqueta = (em?.optJSONObject("License")?.optString("value") ?: "").lowercase()
                val curta = limpo(em?.optJSONObject("LicenseShortName")?.optString("value"))
                if (!permitida(etiqueta, curta)) return@mapNotNull null
                val titulo = p.optString("title").removePrefix("File:")
                Achado(
                    titulo = titulo.substringBeforeLast('.').take(60),
                    miniatura = mini,
                    // endereço documentado do MediaWiki para pedir qualquer largura; o thumburl só serve à miniatura
                    imagem = "https://commons.wikimedia.org/wiki/Special:FilePath/" + URLEncoder.encode(titulo.replace(' ', '_'), "UTF-8").replace("+", "%20") + "?width=" + LARGURA_USO,
                    licenca = curta,
                    autor = limpo(em?.optJSONObject("Artist")?.optString("value")),
                    pagina = ii.optString("descriptionurl"))
            }
        }.getOrElse { emptyList() }
    }

    /** Baixa o arquivo para filesDir/fundos e devolve o Uri local (a receita guarda esse endereço). Null se falhar. */
    fun baixar(ctx: Context, a: Achado): Uri? = runCatching {
        val pasta = File(ctx.filesDir, "fundos").apply { mkdirs() }
        val destino = File(pasta, "cw_" + a.imagem.hashCode().toUInt().toString(16) + ".jpg")
        if (!destino.isFile || destino.length() < 1024) {
            val con = (URL(a.imagem).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000; readTimeout = 40_000; setRequestProperty("User-Agent", AGENTE)
            }
            if (con.responseCode != 200) error("HTTP ${con.responseCode}")
            if (con.contentLengthLong > TETO_BYTES) error("imagem grande demais")
            val parcial = File(destino.path + ".part")
            con.inputStream.use { ent -> parcial.outputStream().use { ent.copyTo(it) } }
            if (parcial.length() > TETO_BYTES) { parcial.delete(); error("imagem grande demais") }
            if (!parcial.renameTo(destino)) error("não consegui gravar")
        }
        FileProvider.getUriForFile(ctx, ctx.packageName + ".arquivos", destino)
    }.getOrElse { e ->
        Telemetria.evento("erro", mapOf("onde" to "fundo_baixar", "msg" to (e.message ?: e::class.java.simpleName).take(120))); null
    }
}
