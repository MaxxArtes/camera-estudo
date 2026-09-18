package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EstadoMelhorar {
    data object Ocioso : EstadoMelhorar
    data class Consentimento(val rostos: Rostos.Presenca, val capacidades: CapacidadesOnline, val politica: String?) : EstadoMelhorar
    data object Preparando : EstadoMelhorar
    data object Enviando : EstadoMelhorar
    data object Aguardando : EstadoMelhorar
    data object Aplicando : EstadoMelhorar
    data class Resultado(val bitmap: Bitmap, val original: Uri, val restantes: Int, val renovacao: String, val receita: String, val copia: Uri? = null, val salvando: Boolean = false, val erroSalvar: String? = null) : EstadoMelhorar
    data object SemDiferenca : EstadoMelhorar
    data class Falha(val codigo: String) : EstadoMelhorar
    data class Cota(val renovacao: String) : EstadoMelhorar
}

object FluxoMelhorar {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutavel = MutableStateFlow<EstadoMelhorar>(EstadoMelhorar.Ocioso)
    val estado = mutavel.asStateFlow()
    private val atualizacoes = MutableStateFlow(0)
    val versaoGaleria = atualizacoes.asStateFlow()
    private var trabalho: Job? = null
    private var foto: FotoOnline? = null
    private var midia: Midia? = null
    private var rostos = Rostos.Presenca.Falha

    fun iniciar(ctx: Context, alvo: Midia) {
        if (mutavel.value != EstadoMelhorar.Ocioso || alvo.ehVideo || BuildConfig.MELHORAR_TOKEN.isBlank()) return
        val app = ctx.applicationContext
        midia = alvo
        executar {
            mutavel.value = EstadoMelhorar.Preparando
            if (!Rede.validada(app)) throw ErroMelhoramento("SEM_REDE")
            val capacidades = MelhoramentoOnline.capacidades()
            if (!capacidades.habilitado) throw ErroMelhoramento("FEATURE_DISABLED")
            if (capacidades.restantes <= 0) { mutavel.value = EstadoMelhorar.Cota(capacidades.renovacao); return@executar }
            val preparada = try { MelhoramentoOnline.preparar(app, alvo.uri) }
            catch (erro: ErroMelhoramento) { throw erro }
            catch (erro: CancellationException) { throw erro }
            catch (_: Exception) { throw ErroMelhoramento("INVALID_IMAGE") }
            foto = preparada
            rostos = try { kotlinx.coroutines.withTimeout(15_000) { Rostos.verificarOnline(preparada.previa) } }
            catch (_: TimeoutCancellationException) { Rostos.Presenca.Falha }
            consentir(capacidades, capacidades.privacidade != "camera-online-v1")
        }
    }

    private suspend fun consentir(capacidades: CapacidadesOnline, buscarPolitica: Boolean) {
        if (!capacidades.habilitado) throw ErroMelhoramento("FEATURE_DISABLED")
        if (capacidades.restantes <= 0) { mutavel.value = EstadoMelhorar.Cota(capacidades.renovacao); return }
        val politica = if (buscarPolitica) MelhoramentoOnline.politica(capacidades.url) else null
        mutavel.value = EstadoMelhorar.Consentimento(rostos, capacidades, politica)
    }

    fun aceitar(ctx: Context) {
        val consentimento = mutavel.value as? EstadoMelhorar.Consentimento ?: return
        val preparada = foto ?: return
        val alvo = midia ?: return
        val app = ctx.applicationContext
        executar {
            if (!Rede.validada(app)) throw ErroMelhoramento("SEM_REDE")
            mutavel.value = EstadoMelhorar.Enviando
            try {
                val pedido = kotlinx.coroutines.currentCoroutineContext()[Job]
                val resultado = withContext(Dispatchers.IO) {
                    MelhoramentoOnline.melhorar(preparada, consentimento.capacidades.privacidade) {
                        if (pedido?.isActive == true) mutavel.compareAndSet(EstadoMelhorar.Enviando, EstadoMelhorar.Aguardando)
                    }
                }
                mutavel.value = EstadoMelhorar.Aplicando
                val igual = withContext(Dispatchers.Default) { resultado.bitmap.sameAs(preparada.previa) }
                mutavel.value = if (igual) EstadoMelhorar.SemDiferenca else EstadoMelhorar.Resultado(resultado.bitmap, alvo.uri, resultado.restantes, resultado.renovacao, resultado.receita)
            } catch (erro: ErroMelhoramento) {
                if (erro.codigo != "PRIVACY_VERSION_CHANGED") throw erro
                mutavel.value = EstadoMelhorar.Preparando
                consentir(MelhoramentoOnline.capacidades(), true)
            }
        }
    }

    fun salvar(ctx: Context) {
        val resultado = mutavel.value as? EstadoMelhorar.Resultado ?: return
        if (resultado.salvando || resultado.copia != null) return
        val alvo = midia ?: return
        val preparada = foto ?: return
        val app = ctx.applicationContext
        mutavel.value = resultado.copy(salvando = true, erroSalvar = null)
        trabalho = escopo.launch {
            try {
                val copia = Fotos.salvarMelhorada(app, alvo, resultado.bitmap, preparada.largura, preparada.altura, resultado.receita)
                atualizacoes.value += 1
                mutavel.value = resultado.copy(copia = copia)
            } catch (_: OutOfMemoryError) {
                mutavel.value = resultado.copy(erroSalvar = "Não há memória suficiente para salvar esta foto. Feche outros apps e tente novamente.")
            } catch (e: Exception) {
                mutavel.value = resultado.copy(erroSalvar = if (e is ErroMelhoramento) textoErroMelhorar(e.codigo) else "Não foi possível salvar a cópia. Verifique o espaço e a permissão de armazenamento.")
            }
        }
    }

    fun desfazer(ctx: Context) {
        val resultado = mutavel.value as? EstadoMelhorar.Resultado ?: return
        val copia = resultado.copia ?: return
        if (resultado.salvando) return
        val app = ctx.applicationContext
        mutavel.value = resultado.copy(salvando = true)
        trabalho = escopo.launch {
            val apagou = withContext(Dispatchers.IO) { Fotos.apagar(app, copia) }
            atualizacoes.value += 1
            if (apagou) { mutavel.value = resultado.copy(salvando = false); fechar() } else mutavel.value = resultado.copy(erroSalvar = "Não foi possível apagar a cópia. Tente novamente.")
        }
    }

    fun fechar() {
        if ((mutavel.value as? EstadoMelhorar.Resultado)?.salvando == true) return
        trabalho?.cancel()
        trabalho = null
        foto = null
        midia = null
        mutavel.value = EstadoMelhorar.Ocioso
    }

    private fun executar(bloco: suspend () -> Unit) {
        trabalho = escopo.launch {
            try { bloco() }
            catch (e: TimeoutCancellationException) { mutavel.value = EstadoMelhorar.Falha("PROVIDER_TIMEOUT") }
            catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { mutavel.value = EstadoMelhorar.Falha("MEMORIA") }
            catch (e: Exception) {
                val codigo = when (e) {
                    is ErroMelhoramento -> e.codigo
                    is java.net.SocketTimeoutException -> "PROVIDER_TIMEOUT"
                    is java.io.IOException -> "CONEXAO"
                    else -> "RESPOSTA_INVALIDA"
                }
                mutavel.value = if (codigo == "QUOTA_EXCEEDED") EstadoMelhorar.Cota((e as? ErroMelhoramento)?.uso?.renovacao.orEmpty()) else EstadoMelhorar.Falha(codigo)
            }
        }
    }
}

fun textoErroMelhorar(codigo: String): String = when (codigo) {
    "INVALID_REQUEST" -> "Não foi possível preparar o pedido de melhoria. Atualize o app e tente novamente."
    "UNAUTHORIZED" -> "O acesso à IA online não foi autorizado. Atualize o app."
    "FEATURE_DISABLED" -> "A melhoria online está desativada no momento."
    "PRIVACY_VERSION_CHANGED" -> "A política de privacidade mudou. Leia os novos termos antes de enviar."
    "REQUEST_IN_PROGRESS" -> "Este pedido ainda está sendo processado. Nenhum novo envio foi feito."
    "REQUEST_ALREADY_FINISHED" -> "Este pedido já terminou. O resultado não está disponível nesta tentativa."
    "IDEMPOTENCY_CONFLICT" -> "Houve um conflito no pedido. Feche e inicie novamente."
    "PAYLOAD_TOO_LARGE" -> "A imagem excede o limite de envio. Escolha outra foto."
    "UNSUPPORTED_IMAGE" -> "Este formato de imagem não é aceito."
    "INVALID_IMAGE" -> "Não foi possível ler esta foto. Escolha outra imagem."
    "CONTENT_REJECTED" -> "O serviço não aceitou esta imagem para melhoria."
    "QUOTA_EXCEEDED" -> "Sua cota de melhorias terminou. Aguarde a renovação."
    "RATE_LIMITED" -> "Muitos pedidos em pouco tempo. Aguarde antes de tentar novamente."
    "PROVIDER_FAILURE" -> "O SnapEdit não conseguiu melhorar a foto. Tente novamente mais tarde."
    "SERVICE_UNAVAILABLE" -> "O serviço está indisponível. Tente novamente mais tarde."
    "PROVIDER_TIMEOUT" -> "O serviço demorou demais para responder. A operação pode ter sido processada; confira a cota antes de tentar novamente."
    "SEM_REDE" -> "Conecte-se à internet para usar IA online."
    "CONEXAO" -> "A conexão foi interrompida. A operação pode ter sido processada. Nenhum reenvio automático será feito."
    "MEMORIA" -> "Não há memória suficiente para trabalhar com esta foto. Feche outros apps e tente novamente."
    "IMAGEM_GRANDE" -> "A foto é grande demais para salvar neste app."
    else -> "O serviço devolveu uma resposta inválida. Nenhuma cópia foi salva."
}
