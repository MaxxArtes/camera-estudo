package br.maxymus.galeriaestudo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Provedor de figurinhas do WhatsApp. As 4 rotas, os nomes de coluna e a permissão de leitura vêm do repositório
 * oficial WhatsApp/stickers (ver galeria/docs/PESQUISA_FIGURINHAS_21-09.md, seção 5). Só entrega o que está em
 * filesDir/figurinhas; nada da galeria do dono passa por aqui.
 */
class FigurinhasProvider : ContentProvider() {
    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val a = Figurinha.AUTORIDADE
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(a, "metadata", 1)
            addURI(a, "metadata/*", 2)
            addURI(a, "stickers/*", 3)
            addURI(a, "stickers_asset/*/*", 4)
        }
        return true
    }

    override fun query(uri: Uri, proj: Array<out String>?, sel: String?, args: Array<out String>?, ord: String?): Cursor? {
        val ctx = context ?: return null
        return when (matcher.match(uri)) {
            1, 2 -> cursorPacote(uri)
            3 -> {
                val c = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
                Figurinha.itens(ctx).forEach { c.addRow(arrayOf(it.arquivo, it.emoji, "Figurinha feita na Galeria Estudo")) }
                c
            }
            else -> null
        }
    }

    private fun cursorPacote(uri: Uri): Cursor {
        val ctx = context!!
        val c = MatrixCursor(arrayOf(
            "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
            "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
            "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website", "image_data_version",
            "whatsapp_will_not_cache_stickers", "animated_sticker_pack"))
        c.addRow(arrayOf(Figurinha.PACOTE_ID, Figurinha.PACOTE_NOME, "Galeria Estudo", "bandeja.png",
            "", "", "", "", "", "", Figurinha.versaoDados(ctx), 0, 0))
        c.setNotificationUri(ctx.contentResolver, uri)
        return c
    }

    override fun openAssetFile(uri: Uri, modo: String): AssetFileDescriptor? {
        val ctx = context ?: return null
        if (matcher.match(uri) != 4) return null
        val nome = uri.lastPathSegment ?: return null
        if (nome.contains('/') || nome.contains("..")) return null          // só arquivo da própria pasta
        val f = if (nome == "bandeja.png") Figurinha.bandeja(ctx) else Figurinha.arquivo(ctx, nome)
        if (f == null || !f.isFile) return null
        return AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length())
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        1 -> "vnd.android.cursor.dir/vnd.${Figurinha.AUTORIDADE}.metadata"
        2 -> "vnd.android.cursor.item/vnd.${Figurinha.AUTORIDADE}.metadata"
        3 -> "vnd.android.cursor.dir/vnd.${Figurinha.AUTORIDADE}.stickers"
        4 -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
        else -> "application/octet-stream"
    }

    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, sel: String?, args: Array<out String>?): Int = 0
}
