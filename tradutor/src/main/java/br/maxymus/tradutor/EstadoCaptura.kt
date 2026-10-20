package br.maxymus.tradutor

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado observado pela tela; não guarda Intent, token do Android nem pixels em disco. Uso na thread principal. */
internal object EstadoCaptura {
    private var sessao: SessaoCaptura? = null
    private val atual = MutableStateFlow(EstadoSessao.OCIOSA)
    val estado = atual.asStateFlow()
    private val aviso = MutableStateFlow("")
    val mensagem = aviso.asStateFlow()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sessao_captura", Context.MODE_PRIVATE)

    fun iniciar(ctx: Context) {
        if (sessao != null) return
        sessao = SessaoCaptura(prefs(ctx).getBoolean("em_andamento", false))
        publicar(ctx)
    }
    private fun publicar(ctx: Context) {
        val e = checkNotNull(sessao).estado
        atual.value = e
        // Também marca PEDINDO: morte do processo durante o diálogo exige começar de novo.
        prefs(ctx).edit().putBoolean("em_andamento", e != EstadoSessao.OCIOSA).apply()
    }
    fun pedir(ctx: Context): Long? {
        iniciar(ctx)
        val pedido = sessao!!.pedir()
        if (pedido != null) aviso.value = ""
        publicar(ctx)
        return pedido
    }
    fun aguardando(pedido: Long) = sessao?.aguardando(pedido) == true
    fun ativar(ctx: Context, pedido: Long): Boolean {
        val ok = sessao?.ativar(pedido) == true
        if (ok) publicar(ctx)
        return ok
    }
    fun negar(ctx: Context, pedido: Long, texto: String) {
        if (!aguardando(pedido)) return
        sessao!!.negar(pedido); aviso.value = texto; publicar(ctx)
    }
    fun encerrar(ctx: Context, peloSistema: Boolean, texto: String) {
        iniciar(ctx)
        sessao!!.encerrar(peloSistema); aviso.value = texto; publicar(ctx)
    }
    fun avisar(texto: String) { aviso.value = texto }

    fun acessibilidadeLigada(ctx: Context): Boolean {
        if (ServicoTradutor.ativo != null) return true
        val nome = ComponentName(ctx, ServicoTradutor::class.java)
        val lista = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return lista.split(':').any { ComponentName.unflattenFromString(it) == nome }
    }
    fun modo(ctx: Context) = modoCaptura(acessibilidadeLigada(ctx), atual.value == EstadoSessao.ATIVA)
}
