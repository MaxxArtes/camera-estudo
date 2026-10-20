package br.maxymus.tradutor

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exportação para o banco de avaliação (tradutor/avaliacao/, Tradutor 0.33). DESLIGADA: só grava quando existe o arquivo
 * `ligado` na pasta do app, criado à mão pelo dono por adb:
 *
 *     adb shell mkdir -p /sdcard/Android/data/br.maxymus.tradutor/files/avaliacao
 *     adb shell touch /sdcard/Android/data/br.maxymus.tradutor/files/avaliacao/ligado
 *
 * Grava, para cada quadro preparado no leitor, a imagem original e o que o app leu (falas, caixas, onomatopeia) e traduziu,
 * na pasta do próprio app no aparelho, de onde sai por `adb pull`. Nada vai para a rede nem para a telemetria. Apagar o
 * arquivo `ligado` (ou a pasta) desliga.
 */
object Avaliacao {
    /** Teto da pasta, em bytes: passando disso as imagens e os json mais antigos são apagados. */
    const val TETO_BYTES = 200L * 1024 * 1024

    /**
     * O arquivo `ligado`, procurado pelo caminho SEM criar nada: getExternalFilesDir cria a pasta do app em todo aparelho, e
     * quem não ligou o banco não deve ganhar pasta nenhuma. `Android/data/<pacote>/files` é do próprio app e pode ser lido.
     */
    @Suppress("DEPRECATION")
    fun ligada(ctx: Context): Boolean = runCatching {
        File(android.os.Environment.getExternalStorageDirectory(), "Android/data/${ctx.packageName}/files/avaliacao/ligado").exists()
    }.getOrDefault(false)

    /** A pasta, criada se preciso: só é chamada com o banco ligado, depois de `ligada`. */
    private fun pasta(ctx: Context): File? = runCatching { ctx.getExternalFilesDir("avaliacao") }.getOrNull()

    /** Apaga os arquivos mais antigos (menos o `ligado`) até a pasta caber no teto. Devolve os bytes que sobraram. */
    internal fun limitar(dir: File, teto: Long = TETO_BYTES): Long {
        val arquivos = dir.listFiles { f -> f.isFile && f.name != "ligado" }?.sortedBy { it.lastModified() } ?: return 0L
        var total = arquivos.sumOf { it.length() }
        for (f in arquivos) {
            if (total <= teto) break
            val tam = f.length()
            if (f.delete()) total -= tam
        }
        return total
    }

    /**
     * `nome` identifica o quadro (capítulo e índice); `imagem` é a decodificada, a MESMA em que as caixas foram medidas (o
     * original pode ter sido reduzido na decodificação). Falha aqui nunca atrapalha a leitura. A pasta tem teto de 200 MB.
     */
    fun gravar(ctx: Context, nome: String, imagem: Bitmap, falas: List<Falas.Fala>, mapa: Map<String, String>) {
        runCatching {
            val dir = pasta(ctx) ?: return
            File(dir, "$nome.jpg").outputStream().use { imagem.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val j = JSONObject().put("arquivo", nome).put("versao_app", BuildConfig.VERSION_CODE)
                .put("largura", imagem.width).put("altura", imagem.height)
                .put("falas", JSONArray(falas.map { f ->
                    JSONObject().put("texto", f.texto).put("traducao", mapa[f.texto] ?: f.texto).put("sfx", f.sfx)
                        .put("caixa", JSONArray(listOf(f.caixa.left, f.caixa.top, f.caixa.right, f.caixa.bottom)))
                }))
            File(dir, "$nome.json").writeText(j.toString(1))
            limitar(dir)
        }
    }
}
