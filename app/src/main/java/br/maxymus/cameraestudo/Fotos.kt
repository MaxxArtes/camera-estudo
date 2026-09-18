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

    /** EXIF nas fotos que o app monta (fusão, retrato): fabricante, modelo, software e data. Sem isso a origem se perde. */
    fun gravaExif(contexto: Context, uri: Uri, software: String) {
        runCatching {
            contexto.contentResolver.openFileDescriptor(uri, "rw")?.use { fd ->
                val exif = androidx.exifinterface.media.ExifInterface(fd.fileDescriptor)
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, Build.MODEL)
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, software)
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
                exif.saveAttributes()
            }
        }
    }

    suspend fun salvarMelhorada(contexto: Context, original: Midia, bitmap: android.graphics.Bitmap, largura: Int, altura: Int): Uri =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (largura <= 0 || altura <= 0 || largura.toLong() * altura > 60_000_000) throw ErroMelhoramento("IMAGEM_GRANDE")
            val resolver = contexto.contentResolver
            val valores = novaEntrada().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, original.nome.substringBeforeLast('.', original.nome) + "_ia.jpg")
                put(MediaStore.MediaColumns.WIDTH, largura)
                put(MediaStore.MediaColumns.HEIGHT, altura)
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            // No Android 8/9, reserva um caminho único para nunca substituir a original.
            val arquivo = if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                val pasta = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), PASTA)
                if (!pasta.isDirectory && !pasta.mkdirs()) throw java.io.IOException()
                val base = original.nome.substringBeforeLast('.', original.nome).replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
                var candidato = java.io.File(pasta, "${base}_ia.jpg")
                var indice = 1
                while (!candidato.createNewFile()) { candidato = java.io.File(pasta, "${base}_${indice++}_ia.jpg") }
                @Suppress("DEPRECATION")
                valores.put(MediaStore.MediaColumns.DATA, candidato.absolutePath)
                valores.put(MediaStore.MediaColumns.DISPLAY_NAME, candidato.name)
                candidato
            } else null
            var uri: Uri? = null
            try {
                val destino = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores) ?: throw java.io.IOException()
                uri = destino
                val ajustado = android.graphics.Bitmap.createScaledBitmap(bitmap, largura, altura, true)
                try {
                    resolver.openOutputStream(destino, "w")?.use {
                        if (!ajustado.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) throw java.io.IOException()
                    } ?: throw java.io.IOException()
                } finally { if (ajustado !== bitmap) ajustado.recycle() }
                resolver.openFileDescriptor(destino, "rw")?.use {
                    val exif = androidx.exifinterface.media.ExifInterface(it.fileDescriptor)
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "Camera Estudo ${BuildConfig.VERSION_NAME} (melhorado online, snapedit-enhance-v1)")
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "1")
                    exif.saveAttributes()
                } ?: throw java.io.IOException()
                if (Build.VERSION.SDK_INT >= 29) {
                    val publicado = resolver.update(destino, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    if (publicado != 1) throw java.io.IOException()
                }
                destino
            } catch (erro: Throwable) {
                uri?.let { apagar(contexto, it) }
                arquivo?.delete()
                throw erro
            }
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
