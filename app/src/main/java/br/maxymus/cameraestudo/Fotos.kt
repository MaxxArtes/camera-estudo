package br.maxymus.cameraestudo

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uma foto ou vídeo do app, como vem do MediaStore. */
data class Midia(
    val uri: Uri,
    val ehVideo: Boolean,
    val nome: String,
    val bytes: Long,
    val largura: Int,
    val altura: Int,
    val data: Long,           // segundos desde 1970 (DATE_ADDED)
    val duracaoMs: Long = 0
)

/**
 * Tudo o que fala com o MediaStore fica aqui: onde salvar, como listar, apagar e favoritar.
 * Fotos vão para Imagens/CameraEstudo e vídeos para Filmes/CameraEstudo, pastas que aparecem na
 * galeria do celular sem permissão de armazenamento (o app só escreve na própria pasta).
 */
object Fotos {
    const val PASTA = "CameraEstudo"
    private const val PREFS = "favoritos"

    private fun carimbo() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Valores para o ImageCapture gravar direto no MediaStore. */
    fun novaEntrada(): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "FOTO_${carimbo()}.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + PASTA)
        }
    }

    /** Valores para o VideoCapture gravar direto no MediaStore. */
    fun novoVideo(): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "VIDEO_${carimbo()}.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + PASTA)
        }
    }

    private fun consulta(contexto: Context, colecao: Uri, ehVideo: Boolean, limite: Int): List<Midia> {
        val projecao = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION
        )
        val (selecao, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("%$PASTA%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/$PASTA/%")
        }
        val saida = mutableListOf<Midia>()
        contexto.contentResolver.query(colecao, projecao, selecao, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iNome = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iTam = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            while (c.moveToNext() && saida.size < limite) {
                saida += Midia(
                    uri = ContentUris.withAppendedId(colecao, c.getLong(iId)),
                    ehVideo = ehVideo,
                    nome = c.getString(iNome) ?: "",
                    bytes = c.getLong(iTam),
                    largura = c.getInt(iW),
                    altura = c.getInt(iH),
                    data = c.getLong(iData),
                    duracaoMs = if (c.isNull(iDur)) 0 else c.getLong(iDur)
                )
            }
        }
        return saida
    }

    /** Fotos e vídeos do app, dos mais novos para os mais antigos. */
    fun listar(contexto: Context, limite: Int = 300): List<Midia> {
        val fotos = consulta(contexto, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, limite)
        val videos = consulta(contexto, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, limite)
        return (fotos + videos).sortedByDescending { it.data }.take(limite)
    }

    fun apagar(contexto: Context, uri: Uri): Boolean =
        runCatching { contexto.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    // Favoritos ficam no app (SharedPreferences): marcar no MediaStore exige diálogo do sistema a cada vez.
    fun favoritos(contexto: Context): Set<String> =
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("uris", emptySet()) ?: emptySet()

    fun alternaFavorito(contexto: Context, uri: Uri): Boolean {
        val prefs = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val atual = (prefs.getStringSet("uris", emptySet()) ?: emptySet()).toMutableSet()
        val chave = uri.toString()
        val agoraFavorito = if (chave in atual) { atual.remove(chave); false } else { atual.add(chave); true }
        prefs.edit().putStringSet("uris", atual).apply()
        return agoraFavorito
    }
}
