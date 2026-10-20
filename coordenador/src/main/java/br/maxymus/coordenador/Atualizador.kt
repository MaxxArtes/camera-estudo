package br.maxymus.coordenador

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização como nos apps irmãos: a CI publica coordenador/releases.json no R2. A consulta roda em segundo plano a
 * cada abertura e guarda o manifesto; a abertura seguinte decide, sem esperar rede, se pergunta antes de abrir o painel.
 * O APK abre no navegador (sem REQUEST_INSTALL_PACKAGES no app, que dispara o Play Protect no sideload).
 */
object Atualizador {
    private const val URL_MANIFESTO = RegraAtualizacao.PREFIXO_APK + "releases.json"

    class Versao(val nome: String, val codigo: Long, val apk: String, val mudou: List<String>)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("atualizador", Context.MODE_PRIVATE)

    private fun instalado(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun pendente(ctx: Context): Versao? {
        val p = prefs(ctx)
        val v = p.getString("manifesto", null)?.let { ler(it) } ?: return null
        val oferecer = RegraAtualizacao.oferecer(instalado(ctx), v.codigo, p.getLong("adiada_codigo", -1),
            p.getLong("adiada_em", 0), System.currentTimeMillis())
        return v.takeIf { oferecer }
    }

    fun adiar(ctx: Context, v: Versao) {
        prefs(ctx).edit().putLong("adiada_codigo", v.codigo).putLong("adiada_em", System.currentTimeMillis()).apply()
    }

    fun consultarEmSegundoPlano(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            runCatching {
                val con = (URL("$URL_MANIFESTO?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; setRequestProperty("Cache-Control", "no-cache")
                }
                val texto = try { con.inputStream.bufferedReader().use { it.readText() } } finally { con.disconnect() }
                if (ler(texto) != null) prefs(app).edit().putString("manifesto", texto).apply()
            }
        }.start()
    }

    private fun ler(texto: String): Versao? = runCatching {
        val j = JSONObject(texto)
        val mudou = mutableListOf<String>()
        j.optJSONArray("releases")?.optJSONObject(0)?.optJSONArray("mudou")?.let { m ->
            for (i in 0 until m.length()) mudou += m.getString(i)
        }
        Versao(j.getString("versionName"), j.getLong("versionCode"), j.getString("apk"), mudou)
            .takeIf { RegraAtualizacao.apkConfiavel(it.apk) }
    }.getOrNull()
}
