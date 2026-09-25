package br.maxymus.tradutor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lê um capítulo a partir do endereço dele. Tudo acontece NO APARELHO DO DONO, com o endereço e a sessão dele,
 * exatamente como quando ele navega — o servidor nosso não busca nada. Foi a condição que separou este caminho
 * do navegador sem cabeça rodando na bancada.
 *
 * Medido em 24/09 na página real: o HTML tem 128 KB e traz os 153 quadros do capítulo em TEXTO PURO, sem
 * JavaScript montando a lista. Por isso não é preciso navegador embutido nenhum — basta uma requisição e uma
 * expressão regular.
 */
object Capitulo {
    private const val AGENTE = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36"
    /** o que NÃO é quadro do capítulo: miniatura de episódio, ícone, banner */
    private val LIXO = listOf("thumb", "favicon", "static", "profile", "banner", "logo", "ico_", "btn_", "sprite")
    private val IMAGEM = Regex("""https?://[A-Za-z0-9./_-]*phinf[A-Za-z0-9./_%?=&-]+?\.(?:jpg|jpeg|png)""", RegexOption.IGNORE_CASE)

    fun ehEndereco(t: String) = t.trim().startsWith("http", true)

    /**
     * Endereços dos quadros, na ordem em que aparecem. Bloqueante. Lista vazia quando não reconhece a página.
     *
     * O aviso do Astra que virou código: achar imagem não é achar o capítulo. Por isso a busca é feita PRIMEIRO
     * dentro do contêiner da lista de quadros; só se ele não existir é que varre a página inteira, onde banner e
     * miniatura de "próximo episódio" moram.
     */
    fun quadros(endereco: String): List<String> = runCatching {
        val t0 = System.nanoTime()
        val con = (URL(endereco.trim()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", AGENTE)
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        }
        if (con.responseCode != 200) {
            Telemetria.evento("erro", mapOf("onde" to "capitulo_html", "http" to con.responseCode)); return emptyList()
        }
        val html = con.inputStream.bufferedReader().use { it.readText() }
        val ini = html.indexOf("_imageList")
        val trecho = if (ini > 0) html.substring(ini) else html
        val achados = IMAGEM.findAll(trecho).map { it.value }
            .filter { u -> LIXO.none { u.contains(it, true) } }
            .distinct().toList()
        Telemetria.evento("capitulo_lista", mapOf("quadros" to achados.size, "html_kb" to html.length / 1024,
            "lista_propria" to (ini > 0), "ms" to (System.nanoTime() - t0) / 1_000_000))
        achados
    }.getOrElse {
        Telemetria.evento("erro", mapOf("onde" to "capitulo_html", "msg" to (it.message ?: "").take(90)))
        emptyList()
    }

    /** Baixa os bytes do quadro para o disco, SEM decodificar. Separado de propósito: decodificar é o que
     *  custa memória, e o adiantamento roda para vários quadros à frente do que está na tela. */
    fun baixarPara(endereco: String, destino: File): Int = runCatching {
        val con = (URL(endereco).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("User-Agent", AGENTE)
            setRequestProperty("Referer", "https://m.webtoons.com/")
        }
        if (con.responseCode != 200) return 0
        val tmp = File(destino.parentFile, destino.name + ".parte")
        con.inputStream.use { e -> tmp.outputStream().use { s -> e.copyTo(s) } }
        if (tmp.length() < 512) { tmp.delete(); return 0 }
        tmp.renameTo(destino)
        destino.length().toInt()
    }.getOrElse {
        Telemetria.evento("erro", mapOf("onde" to "capitulo_quadro", "msg" to (it.message ?: "").take(90))); 0
    }

    /**
     * Decodifica reduzindo pela LARGURA, nunca pelo lado maior. O risco que o Astra apontou é real e o
     * `inSampleSize` encolhe os dois lados juntos: uma tira de 800 x 8000 reduzida pelo lado maior viraria
     * 200 px de largura e o OCR não leria mais nada. Aqui a largura nunca cai abaixo de `larguraMin`.
     * Se ainda assim a imagem for grande demais para a memória, reduz e ANOTA — para sabermos se acontece.
     */
    fun decodificar(f: File, larguraMax: Int = 1200, larguraMin: Int = 800, pixelsMax: Int = 4_000_000): Bitmap? = runCatching {
        val m = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, m)
        if (m.outWidth <= 0) return null
        var amostra = 1
        while (m.outWidth / (amostra * 2) >= larguraMax) amostra *= 2
        while (m.outWidth.toLong() * m.outHeight / (amostra.toLong() * amostra) > pixelsMax &&
               m.outWidth / (amostra * 2) >= larguraMin) amostra *= 2
        val sobra = m.outWidth.toLong() * m.outHeight / (amostra.toLong() * amostra)
        if (sobra > pixelsMax) Telemetria.evento("quadro_grande",
            mapOf("w" to m.outWidth, "h" to m.outHeight, "amostra" to amostra, "mpix" to sobra / 1_000_000))
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = amostra; inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()
}
