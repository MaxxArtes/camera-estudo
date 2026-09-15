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

/**
 * Tudo o que fala com o MediaStore fica aqui: onde salvar e como listar.
 * As fotos vão para Imagens/CameraEstudo, que aparece na galeria do celular sem permissão
 * de armazenamento (a partir do Android 10 o app só escreve na própria pasta).
 */
object Fotos {
    const val PASTA = "CameraEstudo"

    /** Valores para o ImageCapture gravar direto no MediaStore. */
    fun novaEntrada(): ContentValues {
        val nome = "FOTO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + PASTA)
            }
        }
    }

    /** Uris das fotos deste app, da mais nova para a mais antiga. */
    fun listar(contexto: Context, limite: Int = 200): List<Uri> {
        val colecao = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projecao = arrayOf(MediaStore.Images.Media._ID)
        val (selecao, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%$PASTA%")
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/$PASTA/%")
        }
        val ordem = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val saida = mutableListOf<Uri>()
        contexto.contentResolver.query(colecao, projecao, selecao, args, ordem)?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && saida.size < limite) {
                saida += ContentUris.withAppendedId(colecao, c.getLong(idx))
            }
        }
        return saida
    }

    fun apagar(contexto: Context, uri: Uri): Boolean =
        runCatching { contexto.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}
