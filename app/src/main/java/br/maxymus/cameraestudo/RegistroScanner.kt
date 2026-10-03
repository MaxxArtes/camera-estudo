package br.maxymus.cameraestudo

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * Registro de cada digitalização (uma linha JSON por foto, em files/scanner.jsonl): condição de luz,
 * candidato vencedor, quadrilátero detectado, quadrilátero usado e quanto o usuário moveu os cantos.
 * É a matéria-prima para ajustar os limiares do scanner fora do app (porte.py) e, com volume, para
 * escolher o candidato por condição no próprio aparelho. Não sai do aparelho sem o usuário mandar.
 */
object RegistroScanner {
    private const val NOME = "scanner.jsonl"
    private const val EXPORTAR = "exportar"

    fun anota(contexto: Context, tela: Boolean, d: Documento.Deteccao, usado: FloatArray?, conferido: Boolean, r: Documento.Resultado?) {
        runCatching {
            val desloc = if (d.quad != null && usado != null) (0 until 4).map { hypot(d.quad[it * 2] - usado[it * 2], d.quad[it * 2 + 1] - usado[it * 2 + 1]) }.average() else null
            val linha = JSONObject().apply {
                put("quando", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                put("versao", runCatching { contexto.packageManager.getPackageInfo(contexto.packageName, 0).versionName }.getOrDefault("?"))
                put("modo", if (tela) "tela" else "folha")
                put("metodo", d.metodo)
                put("brilho", d.brilho); put("contraste", d.contraste); put("frac_clara", d.fracClara)
                put("largura", d.previa.width); put("altura", d.previa.height)
                put("detectado", d.quad?.let { JSONArray(it.map { v -> Math.round(v * 1000) / 1000.0 }) } ?: JSONObject.NULL)
                put("usado", usado?.let { JSONArray(it.map { v -> Math.round(v * 1000) / 1000.0 }) } ?: JSONObject.NULL)
                put("desloc", desloc?.let { Math.round(it * 1000) / 1000.0 } ?: JSONObject.NULL)
                put("conferido", conferido)
                put("recortou", r?.recortou ?: false)
            }
            File(contexto.filesDir, NOME).appendText(linha.toString() + "\n")
            Telemetria.evento("scanner", mapOf("modo" to (if (tela) "tela" else "folha"), "metodo" to d.metodo, "brilho" to d.brilho, "contraste" to d.contraste,
                "frac_clara" to d.fracClara, "desloc" to desloc, "conferido" to conferido, "recortou" to (r?.recortou ?: false), "detectado" to d.quad, "usado" to usado))
        }
    }

    fun linhas(contexto: Context): Int = runCatching { File(contexto.filesDir, NOME).useLines { it.count() } }.getOrDefault(0)

    /**
     * Compartilha uma CÓPIA do registro, feita em files/exportar/. O FileProvider deixou de cobrir o filesDir inteiro
     * (caminhos.xml só declara downloads/, estereo/ e exportar/), então o arquivo vivo não pode mais virar content://.
     * Devolve false só quando não há registro; se a cópia falhar, avisa aqui mesmo e devolve true para o chamador não
     * mostrar o aviso de "nenhuma digitalização", que seria falso.
     */
    fun compartilhar(contexto: Context): Boolean {
        val arquivo = File(contexto.filesDir, NOME)
        if (!arquivo.exists()) return false
        val copia = try {
            val pasta = File(contexto.filesDir, EXPORTAR)
            if (!pasta.isDirectory && !pasta.mkdirs()) throw java.io.IOException("mkdirs")
            File(pasta, NOME).also { arquivo.copyTo(it, overwrite = true) }
        } catch (e: Exception) {
            Telemetria.evento("erro", mapOf("onde" to "registro_scanner", "acao" to "copiar", "classe" to e.javaClass.simpleName))
            Toast.makeText(contexto, "Não consegui preparar o registro para compartilhar.", Toast.LENGTH_SHORT).show()
            return true
        }
        val uri = FileProvider.getUriForFile(contexto, contexto.packageName + ".arquivos", copia)
        val envio = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        contexto.startActivity(Intent.createChooser(envio, "Registro do scanner").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
