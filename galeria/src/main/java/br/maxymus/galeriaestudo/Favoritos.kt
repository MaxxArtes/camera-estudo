package br.maxymus.galeriaestudo

import android.content.Context
import android.net.Uri

/** Favoritos ficam só no app (SharedPreferences), por URI. Marcar no MediaStore exigiria diálogo do sistema a cada vez. */
object Favoritos {
    private const val PREFS = "favoritos"
    fun todos(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("uris", emptySet()) ?: emptySet()
    fun eh(ctx: Context, uri: Uri): Boolean = uri.toString() in todos(ctx)
    fun alterna(ctx: Context, uri: Uri): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val atual = (prefs.getStringSet("uris", emptySet()) ?: emptySet()).toMutableSet()
        val chave = uri.toString()
        val agora = if (chave in atual) { atual.remove(chave); false } else { atual.add(chave); true }
        prefs.edit().putStringSet("uris", atual).apply()
        return agora
    }
}
