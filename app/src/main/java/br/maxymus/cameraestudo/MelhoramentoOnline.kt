package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class UsoOnline(val unidadesCobradas: Int, val restantes: Int, val renovacao: String)
class ErroMelhoramento(val codigo: String, val uso: UsoOnline? = null) : Exception(codigo)
data class CapacidadesOnline(val privacidade: String, val url: String, val habilitado: Boolean, val restantes: Int, val renovacao: String)
data class FotoOnline(val previa: Bitmap, val jpeg: ByteArray, val largura: Int, val altura: Int)
data class ResultadoOnline(val bitmap: Bitmap, val restantes: Int, val renovacao: String, val receita: String)

object MelhoramentoOnline {
    const val BASE = "https://pocketlm.maxymus.dev.br"
    private const val MAX_RESPOSTA = 6 * 1024 * 1024
    // receitas que o servidor pode devolver; cada uma é um provedor nomeado na política de privacidade
    val RECEITAS = setOf("snapedit-enhance-v1", "iloveimg-upscale-v1")
    private val executor = Executors.newCachedThreadPool()

    suspend fun preparar(ctx: Context, uri: Uri): FotoOnline = withContext(Dispatchers.IO) {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
        if (limites.outWidth <= 0 || limites.outHeight <= 0) throw ErroMelhoramento("INVALID_IMAGE")
        val exif = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            ?: throw ErroMelhoramento("INVALID_IMAGE")
        if (limites.outWidth.toLong() * limites.outHeight > 60_000_000) throw ErroMelhoramento("IMAGEM_GRANDE")
        val troca = exif.rotationDegrees % 180 != 0
        val largura = if (troca) limites.outHeight else limites.outWidth
        val altura = if (troca) limites.outWidth else limites.outHeight
        val opcoes = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(limites.outWidth, limites.outHeight) / inSampleSize > 2400) inSampleSize *= 2
        }
        val decodificada = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opcoes) }
            ?: throw ErroMelhoramento("INVALID_IMAGE")
        val matriz = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        val orientada = Bitmap.createBitmap(decodificada, 0, 0, decodificada.width, decodificada.height, matriz, true)
        if (orientada !== decodificada) decodificada.recycle()
        val fator = minOf(1.0, 1200.0 / maxOf(orientada.width, orientada.height))
        val previa = Bitmap.createScaledBitmap(orientada, maxOf(1, (orientada.width * fator).toInt()), maxOf(1, (orientada.height * fator).toInt()), true)
        if (previa !== orientada) orientada.recycle()
        val jpeg = ByteArrayOutputStream().use { saida ->
            if (!previa.compress(Bitmap.CompressFormat.JPEG, 92, saida)) throw ErroMelhoramento("INVALID_IMAGE")
            saida.toByteArray()
        }
        if (jpeg.size > 2_097_152) throw ErroMelhoramento("PAYLOAD_TOO_LARGE")
        FotoOnline(previa, jpeg, largura, altura)
    }

    suspend fun capacidades(): CapacidadesOnline {
        val j = JSONObject(requisicao("/camera/capacidades"))
        if (j.getInt("api_version") != 1) throw ErroMelhoramento("RESPOSTA_INVALIDA")
        val f = j.getJSONObject("features").getJSONObject("melhorar")
        if (j.getString("privacy_version").isBlank()) throw ErroMelhoramento("RESPOSTA_INVALIDA")
        return CapacidadesOnline(j.getString("privacy_version"), j.getString("privacy_url"), f.getBoolean("enabled"), f.getInt("remaining"), f.getString("reset_at"))
    }

    suspend fun politica(url: String): String {
        val endereco = URL(url)
        if (endereco.protocol != "https" || endereco.host != URL(BASE).host || endereco.port !in listOf(-1, 443)) throw ErroMelhoramento("RESPOSTA_INVALIDA")
        return requisicao(endereco.file, politica = true)
    }

    suspend fun melhorar(foto: FotoOnline, privacidade: String, aguardando: () -> Unit): ResultadoOnline {
        val id = UUID.randomUUID().toString()
        val corpo = JSONObject().apply {
            put("api_version", 1); put("request_id", id)
            put("consent", JSONObject().put("privacy_version", privacidade).put("accepted", true))
            put("image", JSONObject().put("mime_type", "image/jpeg").put("width", foto.previa.width).put("height", foto.previa.height).put("base64", Base64.encodeToString(foto.jpeg, Base64.NO_WRAP)))
            put("operation", "enhance_v1")
            put("target", JSONObject().put("width", foto.largura).put("height", foto.altura))
        }.toString().toByteArray(Charsets.UTF_8)
        if (corpo.size > 3 * 1024 * 1024) throw ErroMelhoramento("PAYLOAD_TOO_LARGE")
        val j = JSONObject(requisicao("/camera/melhorar", corpo, id, aguardando = aguardando))
        if (j.getInt("api_version") != 1 || j.getString("request_id") != id || j.getString("status") != "ok") throw ErroMelhoramento("RESPOSTA_INVALIDA")
        val resultado = j.getJSONObject("result")
        val receita = resultado.getString("recipe_version")
        if (resultado.getString("kind") != "enhanced" || receita !in RECEITAS) throw ErroMelhoramento("RESPOSTA_INVALIDA")
        val imagem = resultado.getJSONObject("image")
        if (imagem.getString("mime_type") != "image/jpeg") throw ErroMelhoramento("RESPOSTA_INVALIDA")
        val bytes = Base64.decode(imagem.getString("base64"), Base64.DEFAULT)
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, limites)
        if (limites.outMimeType != "image/jpeg" || limites.outWidth != imagem.getInt("width") || limites.outHeight != imagem.getInt("height") || limites.outWidth <= 0 || limites.outHeight <= 0 || limites.outWidth.toLong() * limites.outHeight > 24_000_000) throw ErroMelhoramento("RESPOSTA_INVALIDA")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ErroMelhoramento("RESPOSTA_INVALIDA")
        val uso = j.getJSONObject("usage")
        return ResultadoOnline(bitmap, uso.getInt("remaining"), uso.getString("reset_at"), receita)
    }

    private suspend fun requisicao(caminho: String, corpo: ByteArray? = null, id: String? = null, politica: Boolean = false, aguardando: () -> Unit = {}): String = withTimeout(120_000) {
        suspendCancellableCoroutine { continuacao ->
            val con = (URL(BASE + caminho).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 90_000; instanceFollowRedirects = false
                requestMethod = if (corpo == null) "GET" else "POST"
                setRequestProperty("Accept", if (politica) "text/html, text/plain" else "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "camera-estudo/${BuildConfig.VERSION_NAME}")
                if (!politica) setRequestProperty("Authorization", "Bearer ${BuildConfig.MELHORAR_TOKEN}")
                if (id != null) setRequestProperty("Idempotency-Key", id)
            }
            continuacao.invokeOnCancellation { executor.execute { con.disconnect() } }
            executor.execute {
                try {
                    if (!continuacao.isActive) return@execute
                    if (corpo != null) {
                        con.doOutput = true; con.setFixedLengthStreamingMode(corpo.size)
                        con.outputStream.use { it.write(corpo) }
                        if (continuacao.isActive) aguardando()
                    }
                    val status = con.responseCode
                    val entrada = if (status == 200) con.inputStream else con.errorStream
                    val texto = entrada?.use { fluxo ->
                        ByteArrayOutputStream().use { saida ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuacao.isActive) return@execute
                                val n = fluxo.read(buffer)
                                if (n < 0) break
                                if (saida.size() + n > if (politica) 256 * 1024 else MAX_RESPOSTA) throw ErroMelhoramento("RESPOSTA_INVALIDA")
                                saida.write(buffer, 0, n)
                            }
                            saida.toString("UTF-8")
                        }
                    } ?: ""
                    if (status != 200) {
                        val erro = runCatching { JSONObject(texto) }.getOrNull()
                        val uso = erro?.optJSONObject("usage")?.let {
                            UsoOnline(it.optInt("units_charged", 0), it.optInt("remaining", 0), it.optString("reset_at", ""))
                        }
                        throw ErroMelhoramento(erro?.optJSONObject("error")?.optString("code", "SERVICE_UNAVAILABLE") ?: "SERVICE_UNAVAILABLE", uso)
                    }
                    if (continuacao.isActive) continuacao.resume(texto)
                } catch (e: Exception) {
                    if (continuacao.isActive) continuacao.resumeWithException(e)
                } finally { con.disconnect() }
            }
        }
    }
}
