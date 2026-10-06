package br.maxymus.cameraestudo

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.util.SizeF
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun Size.wxh(): String = "${width}x$height"

/** Regra de tipo do esquema fechado da telemetria: diz se um valor já saneado pode sair. */
private typealias Regra = (Any?) -> Boolean

/**
 * Experimento "dois sensores ao mesmo tempo" (0.79). Responde UMA pergunta com o dado do aparelho do dono: o
 * firmware entrega fluxos dos sensores físicos por baixo da câmera lógica "0" (no POCO, "2" principal e "3" ultra)
 * AO MESMO TEMPO numa sessão só (OutputConfiguration.setPhysicalCameraId)? Se sim, guarda um par de quadros
 * simultâneos, só no aparelho, para prototipar profundidade por disparidade depois.
 *
 * Aqui ficam o registro da rodada, o portão leve (lê características e pergunta ao aparelho sem abrir a câmera), a
 * tabela de vereditos, os textos, a gravação e a telemetria. A parte que abre a Camera2 está em DoisSensoresCamera.kt,
 * confinada numa HandlerThread.
 *
 * Por que Camera2 puro: na CameraX 1.5.3 dois físicos só saem como dois Preview, sem consulta real de suporte, e a
 * falha só aparece no CameraState. Aqui a recusa do firmware vira resposta, não silêncio.
 */
object DoisSensores {
    /** Prazo único da rodada (W). Todo prazo interno deriva dele. */
    const val PRAZO_RODADA_MS = 60_000L
    /** Cão de guarda no escopo do object (fora da tela e fora da HandlerThread): W + 8 s, para pegar a thread presa numa chamada bloqueante. */
    const val CAO_DE_GUARDA_MS = PRAZO_RODADA_MS + 8_000L
    const val PRAZO_PORTAO_MS = 5_000L
    /**
     * Prazo do cancelamento cooperativo (Cancelar, Voltar, app fora da tela, tela descartada). Vencido sem a tarefa
     * terminar, o resultado fecha como cancelado e a câmera é fechada por fora, sem esperar a thread do teste, que pode
     * estar presa numa chamada ao HAL.
     */
    const val PRAZO_CANCELAR_MS = 5_000L
    /**
     * Quanto esperar o onClosed depois de pedir o fechamento da câmera. Passado o prazo, o evento fechamento_pendente
     * sai e a câmera CONTINUA bloqueada: o tempo sozinho nunca a libera, só o onClosed.
     */
    const val PRAZO_FECHAR_MS = 5_000L
    /** Versão do protocolo do teste: muda quando o que se mede muda, para não comparar rodadas diferentes. */
    const val VERSAO_TESTE = 1

    /** Pedidos de cancelamento: o dono tocou em Cancelar (ou Voltar), ou o app saiu do primeiro plano. */
    const val PEDIDO_DONO = "dono_cancelou"
    const val PEDIDO_INTERROMPIDO = "interrompido"

    private const val PREFS = "dois_sensores"
    private const val PASTA = "estereo"
    /** Prefixo das pastas temporárias onde o par é gravado antes de ir para a pasta da rodada. */
    private const val TMP = "tmp_"
    private const val GUARDAR_RODADAS = 3
    /** Marca maior que isto não é nossa (ou corrompeu): é descartada sem ler. */
    private const val TAMANHO_MARCA = 1024
    /** Strings dos eventos dois_* e erro{onde=sensores|dois_*}: no máximo isto (cortadas na última fronteira de código), e só código (S5). */
    private const val MAX_TEXTO = 60
    // decisão C na redação do Astra (02/10), que acompanha a documentação do AOSP; a revisão tem prioridade sobre a especificação
    const val DECISAO_C = "Desde o Android 10, o suporte a combinações com fluxos físicos não é obrigatório. A recusa, por si só, não indica defeito."
    /** D5: texto único para carimbos físicos iguais em todos os quadros (relatório, diálogo e resultado.txt). */
    const val TEXTO_CARIMBOS_IGUAIS = "Os carimbos físicos informados são iguais em todos os quadros. Este teste não confirmou independentemente o desvio entre exposições."
    private const val ROTULO_SEQ = "SEQUENCIAL, NÃO SIMULTÂNEO"
    private const val AVISO_SEQ = "Serve só para provar que cada sensor funciona sozinho. Com o celular na mão não serve para profundidade: " +
        "5 cm/s × 100 ms = 5 mm de deslocamento contra ~10 mm de base, e 0,1° de rotação já desloca ~5 px. Para usar este par, apoie o celular e fotografe cena parada."

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** O único Mutex do estado persistido do teste: gravação da marca, finalização da rodada e recuperação na abertura. */
    private val travaMarca = Mutex()
    /** Pastas do par: gravar, mover, podar, apagar e limpar temporárias. Nunca é pedido de dentro da travaMarca. */
    private val travaArquivos = Mutex()
    /** Rodadas cujo teste o dono apagou: o resultado.txt que ainda estiver a caminho não ressuscita a pasta. */
    private val apagadas = ConcurrentHashMap.newKeySet<String>()

    /**
     * Identidade deste processo, sorteada a cada início de processo e preservada no singleton quando a Activity é
     * recriada. O PID sozinho não serve para dizer "a marca é minha": PIDs se repetem, e depois de um reboot a marca de
     * um teste que morreu ficaria calada para sempre.
     */
    private val processo: String = UUID.randomUUID().toString().replace("-", "").take(10)

    /** Trabalho bloqueante (leitura de características, montagem de relatório) fora do Main, esperado com prazo. */
    fun <T> emFundo(bloco: () -> T): Deferred<T> = escopo.async { bloco() }

    // ------------------------------------------------------------------ registro da rodada

    /** Uma rodada do teste. Tudo que mais de uma thread lê é @Volatile; o fim é uma trava única (um dois_fim só). */
    class Rodada internal constructor(val id: String, internal val app: Context) {
        val fim = AtomicBoolean(false)
        /** Completa com o resultado de quem ganhou o fim() (fechar). A tela só espera por ele; quem o produz é o object. */
        internal val finalizada = CompletableDeferred<Resultado>()
        val resultado: Deferred<Resultado> get() = finalizada
        val inicio: Long = SystemClock.elapsedRealtime()
        @Volatile var etapa: String = "inicio"
        @Volatile var parcial: Resultado? = null
        @Volatile var cancelarPedido: String? = null
        /** elapsedRealtime do primeiro pedido de cancelamento; 0 = nenhum. É daqui que se conta o PRAZO_CANCELAR_MS. */
        @Volatile var cancelarEm: Long = 0L
        @Volatile var saiuDoPrimeiroPlano: String? = null
        @Volatile var emPrimeiroPlano: Boolean = true
        @Volatile var termicoIni: Int? = null
        @Volatile var termicoFim: Int? = null
        @Volatile var planoB: Boolean = false
        @Volatile var portao: Portao? = null
        @Volatile internal var pasta: File? = null
        /** Primeiro passo que a Execucao não deu por causa do cancelamento (vai no dois_fim). */
        @Volatile var paradaAntesDe: String? = null
        /** Linha técnica do cartão do teste: configuração e tamanho em curso, menor que a etapa. */
        @Volatile var tecnico: String? = null

        // ---- posse da câmera, separada do resultado: o resultado pode fechar com a câmera ainda por fechar ----
        /** O device aberto, publicado no onOpened para o fechamento de emergência poder chamar close() de fora da thread. */
        @Volatile internal var dispositivo: CameraDevice? = null
        /** Quantas aberturas (openCamera) ainda não tiveram o onClosed. Zero = liberada. */
        internal val vivas = AtomicInteger(0)
        @Volatile internal var fecharPedidoEm: Long = 0L
        @Volatile internal var gatilhoFechar: String? = null
        internal val vigiando = AtomicBoolean(false)
        /** O onClosed não chegou em PRAZO_FECHAR_MS: a câmera segue bloqueada e o dono vê "Reabrir câmera". */
        @Volatile var fechamentoPendente: Boolean = false
        /** A Execucao acabou (a thread está livre). Só então a marca de uma rodada já encerrada pode sair. */
        @Volatile internal var execucaoTerminou: Boolean = false
        /** Estado terminal já gravado no disco. Só lido e escrito dentro da travaMarca. */
        internal var terminalGravado: Boolean = false
        @Volatile internal var fio: HandlerThread? = null
        /** lancar() já foi chamado: daqui em diante quem fecha a rodada é a supervisão, não a tela. */
        @Volatile internal var lancada: Boolean = false
        /** Publicar o par (mover para a pasta) e fechar o resultado (fim) se excluem: nenhum par aparece depois do fim. */
        internal val travaPublicar = Any()

        /**
         * Câmera liberada = o sistema confirmou o fechamento (onClosed) de tudo que a rodada abriu, ou nada chegou a abrir.
         * Resultado encerrado NÃO é câmera liberada, e o tempo passar também não.
         */
        val liberada: Boolean get() = vivas.get() <= 0
    }

    /**
     * Rodada cujo resultado ainda não fechou. A posse física da câmera é `retida` (até o onClosed), e a thread de uma
     * Execucao que ainda não voltou fica em `execucoesVivas`: o cão de guarda e o cancelamento forçado fecham o resultado e
     * soltam esta sem esperar a thread presa (revisão do Astra, 02/10).
     */
    @Volatile var ativa: Rodada? = null
        private set

    /** Rodadas cuja Execucao ainda não voltou (thread possivelmente presa no HAL). Protege as pastas temporárias delas. */
    private val execucoesVivas: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Rodada cuja Camera2 abriu (ou está abrindo) e ainda não teve o onClosed. Daqui saem os dois bloqueios: nenhum teste
     * novo e nenhum bind do CameraX enquanto ela existir. Independe de `ativa`: o resultado pode fechar antes da câmera.
     */
    var retida: Rodada? by mutableStateOf(null)
        private set

    fun novaRodada(ctx: Context): Rodada =
        Rodada(UUID.randomUUID().toString().replace("-", "").take(6), ctx.applicationContext).also { ativa = it }

    /**
     * Pede o cancelamento da rodada (Cancelar, Voltar, ON_STOP, tela descartada). O pedido é irreversível: o primeiro
     * vale e nada o desfaz. Além do pedido cooperativo, fecha a câmera NA HORA por fora (device.close() direto, sem
     * stopRepeating antes e sem esperar a thread do teste), porque a privacidade não pode depender dela.
     * `gatilho` só nomeia quem pediu, para a telemetria do fechamento.
     */
    fun cancelar(r: Rodada, motivo: String, gatilho: String = motivo) {
        val primeiro = synchronized(r) {
            if (r.cancelarPedido != null) false
            else { r.cancelarEm = SystemClock.elapsedRealtime(); r.cancelarPedido = motivo; true }
        }
        if (primeiro) fecharPorFora(r, gatilho)
    }

    /**
     * Fechamento de emergência, que não depende da thread do teste: device.close() direto, SEM stopRepeating antes
     * (que pode bloquear), numa thread do IO (close() também pode esperar o lock interno do device, e a Main não pode).
     * Idempotente. Sem device ainda (openCamera sem resposta), não há o que fechar agora: o onOpened tardio fecha o
     * device na hora, porque a rodada já está encerrando, e o fio de callbacks fica vivo até lá.
     */
    fun fecharPorFora(r: Rodada, gatilho: String) {
        if (r.liberada) return
        fechando(r, gatilho)
        val d = r.dispositivo ?: return
        escopo.launch { try { d.close() } catch (e: Throwable) { } }
    }

    /**
     * Registra, uma vez por ciclo, que o fechamento foi pedido, e arma a vigia do onClosed: sem ele em PRAZO_FECHAR_MS sai
     * o evento fechamento_pendente e `fechamentoPendente` fica true (a tela mostra "Reabrir câmera"). Todo close() da
     * rodada passa por aqui antes, para o evento de fechamento medir o tempo desde o pedido.
     */
    internal fun fechando(r: Rodada, gatilho: String) {
        if (r.liberada) return
        synchronized(r) {
            if (r.fecharPedidoEm == 0L) { r.fecharPedidoEm = SystemClock.elapsedRealtime(); r.gatilhoFechar = gatilho }
        }
        if (!r.vigiando.compareAndSet(false, true)) return
        escopo.launch {
            try {
                val desde = r.fecharPedidoEm
                while (!r.liberada && SystemClock.elapsedRealtime() < desde + PRAZO_FECHAR_MS) delay(50)
                if (!r.liberada) {
                    r.fechamentoPendente = true
                    ev("dois_camerax", linkedMapOf("rodada" to r.id, "acao" to "fechamento_pendente", "gatilho" to r.gatilhoFechar,
                        "ms" to (SystemClock.elapsedRealtime() - desde), "abrindo" to (r.dispositivo == null)))
                }
            } finally { r.vigiando.set(false) }
        }
    }

    /** Chamado imediatamente antes de cada openCamera da rodada: a câmera passa a contar como "por fechar". */
    internal fun abriu(r: Rodada) {
        r.fechamentoPendente = false
        r.vivas.incrementAndGet()
        retida = r
    }

    /**
     * onClosed (ou falha síncrona do openCamera, que não deixa device): uma câmera a menos. Zero = liberada de fato; aí a
     * rodada deixa de ser retida e sai o evento com o tempo até o onClosed (tardio = depois do fechamento_pendente;
     * depois_do_fim = depois do dois_fim, que não espera por ele).
     */
    internal fun liberou(r: Rodada, via: String) {
        if (r.vivas.decrementAndGet() > 0) return
        r.vivas.set(0)
        val pedido = r.fecharPedidoEm
        val ms = if (pedido > 0L) SystemClock.elapsedRealtime() - pedido else null
        val gatilho = r.gatilhoFechar
        val tardio = r.fechamentoPendente
        val depoisDoFim = r.fim.get()   // o dois_fim não espera o onClosed: este evento diz se o sistema confirmou depois dele
        r.fecharPedidoEm = 0L
        r.gatilhoFechar = null
        r.dispositivo = null
        if (retida === r) retida = null
        ev("dois_camerax", linkedMapOf("rodada" to r.id, "acao" to "fechou", "via" to via, "gatilho" to gatilho, "ms_ate_onclosed" to ms, "tardio" to tardio,
            "depois_do_fim" to depoisDoFim))
        encerrarFio(r)
    }

    /**
     * A HandlerThread só sai quando a tarefa acabou E a câmera foi liberada: um onOpened ou onClosed tardio precisa dela
     * viva para o device ser fechado. Chamado por quem acaba por último (a tarefa ou o onClosed).
     */
    internal fun encerrarFio(r: Rodada) {
        if (r.execucaoTerminou && r.liberada) r.fio?.quitSafely()
    }

    /**
     * ON_STOP da tela: guarda em que etapa o app saiu do primeiro plano (perda depois disso é interrupção, não firmware)
     * e pede o cancelamento IRREVERSÍVEL da rodada. Privacidade: com o app fora da tela a câmera não continua capturando.
     * A Execucao confere o pedido antes de abrir a câmera, criar a sessão, chamar setRepeatingRequest, copiar imagem e
     * começar o plano B (revisão de segurança do Astra, 02/10).
     */
    fun aoParar() {
        val r = ativa ?: return
        r.emPrimeiroPlano = false
        synchronized(r) { if (r.saiuDoPrimeiroPlano == null) r.saiuDoPrimeiroPlano = r.etapa }
        cancelar(r, PEDIDO_INTERROMPIDO, "on_stop")
    }

    /** Só registra a volta. O cancelamento pedido no ON_STOP é irreversível: nada aqui o desfaz nem retoma a rodada. */
    fun aoIniciar() { ativa?.emPrimeiroPlano = true }

    /** Veredito de uma rodada cancelada, pelo pedido que a cancelou. */
    internal fun motivoCancelado(r: Rodada): Pair<String, String?> = when (val pedido = r.cancelarPedido) {
        PEDIDO_DONO -> "cancelado_pelo_dono" to null
        PEDIDO_INTERROMPIDO -> "nao_testado" to "interrompido:${r.saiuDoPrimeiroPlano ?: r.etapa}"
        else -> "nao_testado" to "cancelado:${pedido ?: "tarefa"}"
    }

    internal fun soltar(r: Rodada) { if (ativa === r) ativa = null }

    /** Estado do CameraX no momento de soltar, medido pela tela (dona do CameraX). */
    class InfoCameraX(val fechouX: Boolean?, val msFechar: Long?, val estadoAntes: String?, val erroAntes: Int?, val outroAppAntes: Boolean)

    // ------------------------------------------------------------------ portão leve

    /** Uma configuração de sessão: mesmo tamanho em todos os fluxos (é a combinação que o CTS exercita). */
    class Cfg(val nome: String, val tam: Size, val comLogico: Boolean, val tamanhoCts: Boolean, val durOk: Boolean?) {
        @Volatile var pre: String = "sem_consulta:nao_perguntado"
        fun fluxos(a: String, b: String) = if (comLogico) "logico+$a+$b" else "$a+$b"
    }

    /** Características estáticas de um sensor, lidas sem abrir a câmera. */
    class InfoSensor(
        val id: String, val nivel: Int?, val cor: Boolean, val tsFonte: Int?,
        val focais: FloatArray, val focoMin: Float?, val ois: IntArray,
        val ativa: Rect?, val preCorr: Rect?, val matriz: Size?, val fisico: SizeF?, val orientacao: Int?,
        val intr: FloatArray?, val dist: FloatArray?, val poseT: FloatArray?, val poseR: FloatArray?, val poseRef: Int?,
        val lp: List<Size>, val ly: Set<Size>
    ) {
        /** Quais chaves de calibração vieram. Ausência não invalida o teste: só deixa a retificação mais difícil. */
        fun calib(): String = listOfNotNull(
            if (intr != null) "intr" else null, if (dist != null) "dist" else null,
            if (poseT != null) "pose_t" else null, if (poseR != null) "pose_r" else null, if (poseRef != null) "ref" else null
        ).joinToString(",").ifEmpty { "nenhuma" }
    }

    class Portao internal constructor(val origem: String, val rodada: String?) {
        var veredito: String = "pre_indefinido"
        var logica: String? = null
        var fisicas: List<String> = emptyList()
        var idA: String? = null      // principal: maior matriz
        var idB: String? = null      // mais aberto: menor focal
        var escolha: String? = null  // frase do relatório local; a telemetria leva só os ids (fisicos_cor)
        var fisicosCor: List<String> = emptyList()
        val sensores = LinkedHashMap<String, InfoSensor>()
        var sync: Int? = null
        var versaoConsulta: Int? = null
        var chavesFisicas: Int? = null
        var estabVideo0: IntArray? = null
        var faixasFps: List<Range<Int>> = emptyList()
        var aeLockDisp: Boolean? = null
        var oisOffDisp: Boolean = false
        var tela: Size? = null
        var sCts: Size? = null
        var s43: Size? = null
        var sMenor: Size? = null
        var durOk: Boolean? = null
        var baseMm: Double? = null
        val configs = ArrayList<Cfg>()
        val problemas = ArrayList<String>()
        var ms: Long = 0
        internal val mapas = HashMap<String, StreamConfigurationMap>()

        /** minFrameDuration do YUV naquele tamanho; null se o mapa não tem o tamanho (o Android lança). */
        fun dur(id: String, tam: Size): Long? {
            val m = mapas[id] ?: return null
            return try { m.getOutputMinFrameDuration(ImageFormat.YUV_420_888, tam) } catch (e: Exception) { null }
        }

        /** Faixa de fps constante [n,n] como o CTS (n = 1e9 / minFrameDuration da lógica); senão a de topo n com o menor piso. */
        fun fpsAlvo(tam: Size): Range<Int>? {
            val d0 = logica?.let { dur(it, tam) }?.takeIf { it > 0 }
                ?: listOfNotNull(idA?.let { dur(it, tam) }, idB?.let { dur(it, tam) }).filter { it > 0 }.maxOrNull()
                ?: return null
            val n = Math.floor(1e9 / d0 + 0.05).toInt()
            if (n <= 0) return null
            faixasFps.firstOrNull { it.lower == n && it.upper == n }?.let { return it }
            return faixasFps.filter { it.upper == n }.minByOrNull { it.lower }
        }

        /** Campos do dois_portao. Quem chama informa a própria origem e rodada: o portão pendente pode ser reaproveitado. */
        fun campos(origemEv: String = origem, rodadaEv: String? = rodada): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["rodada"] = rodadaEv; m["origem"] = origemEv; m["prazo"] = false
            if (origemEv != origem) m["portao_reaproveitado_de"] = origem
            m["logica"] = logica; m["fisicas"] = fisicas.joinToString(",")
            m["id_principal"] = idA; m["id_aberto"] = idB; m["fisicos_cor"] = fisicosCor.joinToString(",")
            for ((id, s) in sensores) { m["nivel_$id"] = s.nivel; m["ts_fonte_$id"] = s.tsFonte; m["n_yuv_$id"] = s.ly.size }
            for (id in listOfNotNull(idA, idB)) {
                val s = sensores[id]
                m["cor_$id"] = s?.cor; m["calib_$id"] = s?.calib(); m["pose_ref_$id"] = s?.poseRef; m["foco_min_$id"] = s?.focoMin
            }
            m["sync"] = sync; m["versao_consulta"] = versaoConsulta
            m["tela"] = tela?.wxh()
            m["tam_cts"] = sCts?.wxh(); m["tam_43"] = s43?.wxh(); m["tam_menor"] = sMenor?.wxh()
            val ref = configs.firstOrNull()?.tam
            for (id in listOfNotNull(logica, idA, idB)) m["dur_${id}_ns"] = ref?.let { dur(id, it) }
            m["dur_ok"] = durOk
            m["fps_alvo"] = ref?.let { fpsAlvo(it) }
            m["ae_lock_disp"] = aeLockDisp; m["ois_off_disp"] = oisOffDisp
            m["estab_video_0"] = estabVideo0
            m["base_mm"] = baseMm; m["chaves_fisicas"] = chavesFisicas
            m["consultas_pre"] = configs.map { "${it.nome}:${it.pre}" }
            m["veredito_portao"] = veredito
            m["problemas"] = problemas.toList()
            m["ms"] = ms
            return m
        }

        /** Seção do relatório "Sensores". */
        fun texto(): String {
            val sb = StringBuilder()
            sb.append("Dois sensores ao mesmo tempo (pré-checagem, sem abrir a câmera)\n")
            when (veredito) {
                "android_antigo" -> { sb.append("   Android ${Build.VERSION.SDK_INT}: pedir sensor físico exige o Android 9 (API 28).\n"); return sb.toString() }
                "aparelho_sem_dois_sensores" -> {
                    sb.append("   Este aparelho não expõe câmera lógica traseira com dois sensores físicos de cor.\n")
                    if (problemas.isNotEmpty()) sb.append("   problemas: ${problemas.joinToString("; ")}\n")
                    return sb.toString()
                }
            }
            val a = idA ?: "?"; val b = idB ?: "?"
            sb.append("   câmera lógica: $logica; sensores físicos: ${fisicas.joinToString(", ")} (principal $a, mais aberto $b)\n")
            escolha?.let { sb.append("   escolha: $it\n") }
            sb.append("   sincronia declarada pelo aparelho: ${nomeSync(sync)}\n")
            sb.append("   consulta sem abrir a câmera: ${versaoConsulta?.let { "versão $it" + if (it < 35) " (antiga demais para perguntar)" else "" } ?: "não oferecida"}\n")
            sb.append("   tela ${tela?.wxh() ?: "?"}; tamanho do CTS: ${sCts?.wxh() ?: "nenhum"}; maior 4:3: ${s43?.wxh() ?: "nenhum"}; menor: ${sMenor?.wxh() ?: "-"}\n")
            if (configs.isEmpty()) sb.append("   os sensores $a e $b não têm tamanho de saída em comum\n")
            for (c in configs) sb.append("   ${c.nome} (${c.tam.wxh()}, ${c.fluxos(a, b)}${if (c.tamanhoCts) ", tamanho do CTS" else ""}): ${consultaPorExtenso(c.pre)}\n")
            for (id in listOfNotNull(idA, idB)) sensores[id]?.let { s ->
                sb.append("   sensor $id: focal ${s.focais.joinToString(", ") { "%.2f mm".format(it) }}; calibração: ${s.calib()}\n")
            }
            sb.append("   base entre os sensores: ${baseMm?.let { "%.1f mm".format(it) } ?: "desconhecida"}\n")
            sb.append("   pré-checagem: ${portaoPorExtenso(veredito)} ($veredito)\n")
            if (problemas.isNotEmpty()) sb.append("   problemas: ${problemas.joinToString("; ")}\n")
            return sb.toString()
        }
    }

    private val etapaPortaoAtual = AtomicReference("nenhuma")
    private var portaoPendente: Deferred<Portao>? = null

    /**
     * Portão em Deferred no escopo próprio: quem espera usa withTimeoutOrNull no await(). Um withTimeout em volta de
     * withContext(IO) não resolveria, porque o withContext espera o bloco travado terminar.
     *
     * Um portão por vez: se o anterior ainda está preso numa chamada ao serviço de câmera, quem chega reaproveita o
     * mesmo Deferred em vez de empilhar outra thread bloqueada (revisão de segurança do Astra, 02/10).
     */
    fun portaoAsync(ctx: Context, rodadaId: String?, origem: String): Deferred<Portao> {
        val app = ctx.applicationContext
        synchronized(this) {
            portaoPendente?.let { if (it.isActive) return it }
            etapaPortaoAtual.set("inicio")
            val d = escopo.async { montarPortao(app, rodadaId, origem, etapaPortaoAtual) }
            portaoPendente = d
            return d
        }
    }

    /** Em que passo o portão em curso (ou o último) estava: vai no evento quando o prazo de 5 s estoura. */
    fun etapaPortao(): String = etapaPortaoAtual.get()

    private fun montarPortao(ctx: Context, rodadaId: String?, origem: String, etapa: AtomicReference<String>): Portao {
        val t0 = SystemClock.elapsedRealtime()
        val p = Portao(origem, rodadaId)
        try {
            if (Build.VERSION.SDK_INT < 28) p.veredito = "android_antigo"
            else montarPortao28(ctx, p, etapa)
        } catch (e: Exception) {
            // só a classe: a mensagem de exceção é texto livre e não sai do aparelho (S5)
            p.problemas += "portao:${e.javaClass.simpleName}"
        }
        p.ms = SystemClock.elapsedRealtime() - t0
        return p
    }

    @RequiresApi(28)
    private fun montarPortao28(ctx: Context, p: Portao, etapa: AtomicReference<String>) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        etapa.set("lista")
        val ids = cm.cameraIdList
        var achada: Pair<String, CameraCharacteristics>? = null
        for (id in ids) {
            etapa.set("caracteristicas:$id")
            val c = try { cm.getCameraCharacteristics(id) } catch (e: Exception) { p.problemas += "$id:${e.javaClass.simpleName}"; continue }
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) { achada = id to c; break }
        }
        // vals (não vars) daqui para baixo: entram em lambdas, e smart cast de var capturada não é garantido
        val (logica, c0) = achada ?: run { p.veredito = "aparelho_sem_dois_sensores"; return }
        p.logica = logica
        val fisicas = c0.physicalCameraIds.toList().sorted()
        p.fisicas = fisicas
        if (fisicas.size < 2) { p.veredito = "aparelho_sem_dois_sensores"; return }
        p.sensores[logica] = lerSensor(logica, c0, p)
        for (f in fisicas) {
            etapa.set("caracteristicas:$f")
            val c = try { cm.getCameraCharacteristics(f) } catch (e: Exception) { p.problemas += "$f:${e.javaClass.simpleName}"; continue }
            p.sensores[f] = lerSensor(f, c, p)
        }
        // físico sem BACKWARD_COMPATIBLE (sem saída de cor) fica fora, como no CTS
        val comCor = fisicas.filter { p.sensores[it]?.cor == true }
        if (comCor.size < 2) { p.problemas += "fisicos_com_cor:${comCor.size}"; p.veredito = "aparelho_sem_dois_sensores"; return }
        val a = comCor.maxByOrNull { id -> p.sensores[id]?.matriz?.let { it.width.toLong() * it.height } ?: 0L } ?: return
        val b = comCor.filter { it != a }.minByOrNull { id -> p.sensores[id]?.focais?.minOrNull() ?: Float.MAX_VALUE } ?: return
        p.idA = a; p.idB = b
        p.fisicosCor = comCor
        if (comCor.size > 2) p.escolha = "físicos de cor ${comCor.joinToString(",")}: principal $a (maior matriz), mais aberto $b (menor focal)"

        etapa.set("chaves_logica")
        p.sync = lerSeguro(p, "sync") { c0.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE) }
        p.chavesFisicas = lerSeguro(p, "chaves_fisicas") { c0.availablePhysicalCameraRequestKeys?.size }
        p.estabVideo0 = lerSeguro(p, "estab") { c0.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) }
        p.faixasFps = lerSeguro(p, "fps") { c0.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() } ?: emptyList()
        p.aeLockDisp = lerSeguro(p, "ae_lock") { c0.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) }
        p.oisOffDisp = p.sensores[logica]?.ois?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true

        // base: |t_a − t_b| em mm, só com as duas poses e referência definida e igual (LENS_POSE_REFERENCE_UNDEFINED = 2)
        val sa = p.sensores[a]; val sb = p.sensores[b]
        val ta = sa?.poseT; val tb = sb?.poseT
        if (sa != null && sb != null && ta != null && tb != null && ta.size >= 3 && tb.size >= 3 && sa.poseRef != 2 && sb.poseRef != 2 && sa.poseRef == sb.poseRef) {
            val dx = (ta[0] - tb[0]).toDouble(); val dy = (ta[1] - tb[1]).toDouble(); val dz = (ta[2] - tb[2]).toDouble()
            val base = 1000.0 * sqrt(dx * dx + dy * dy + dz * dz)
            if (base > 0 && !base.isNaN() && !base.isInfinite()) p.baseMm = base
        }

        etapa.set("tamanhos")
        val dm = ctx.resources.displayMetrics
        val tela = Size(max(dm.widthPixels, dm.heightPixels), min(dm.widthPixels, dm.heightPixels))
        p.tela = tela
        val s0 = p.sensores[logica]
        if (s0 == null || sa == null || sb == null) { p.problemas += "sem_caracteristicas"; p.veredito = "aparelho_sem_dois_sensores"; return }
        val inter023 = s0.ly.intersect(sa.ly).intersect(sb.ly)
        val inter23 = sa.ly.intersect(sb.ly)
        fun durOkEm(s: Size): Boolean? {
            val d0 = p.dur(logica, s) ?: return null
            val da = p.dur(a, s) ?: return null
            val db = p.dur(b, s) ?: return null
            return da <= d0 && db <= d0
        }
        val porArea = compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width }
        fun eh43(s: Size) = s.width * 3 == s.height * 4

        if (inter23.isEmpty()) { p.veredito = "sem_tamanho_comum"; return }
        if (inter023.isNotEmpty()) {
            // S_CTS: maior tamanho de prévia da lógica até 1920x1088 que cabe na tela em paisagem, presente nas listas de
            // prévia e YUV dos dois físicos, com minFrameDuration físico <= o da lógica (findCommonPreviewSize do CTS)
            val sCts = s0.lp.firstOrNull { s ->
                s.width <= tela.width && s.height <= tela.height && s in sa.lp && s in sb.lp && s in inter023 && durOkEm(s) == true
            }
            val cand43 = inter023.filter { eh43(it) && it.width.toLong() * it.height <= 1440L * 1080L }.sortedWith(porArea)
            val s43 = cand43.firstOrNull { durOkEm(it) == true } ?: cand43.firstOrNull()
            val vga = Size(640, 480)
            val menor = if (vga in inter023) vga else inter023.filter { eh43(it) && it.width >= 640 && it.height >= 480 }.minByOrNull { it.width.toLong() * it.height }
            p.sCts = sCts; p.s43 = s43; p.durOk = s43?.let { durOkEm(it) }
            p.sMenor = menor?.takeIf { it != sCts && it != s43 }
            if (sCts != null) p.configs += Cfg("A_cts", sCts, true, true, true)
            if (s43 != null && s43 != sCts) p.configs += Cfg("A_43", s43, true, false, durOkEm(s43))
            when {
                sCts != null -> p.configs += Cfg("B_cts", sCts, false, true, true)
                s43 != null -> p.configs += Cfg("B_43", s43, false, false, durOkEm(s43))
            }
            p.sMenor?.let { s ->
                p.configs += Cfg("A_menor", s, true, false, durOkEm(s))
                p.configs += Cfg("B_menor", s, false, false, durOkEm(s))
            }
            if (p.configs.isEmpty()) {
                // nenhum 4:3 e nenhum tamanho do CTS em comum: o maior tamanho comum até 1080p
                val s = inter023.filter { it.width <= 1920 && it.height <= 1088 }.sortedWith(porArea).firstOrNull() ?: inter023.sortedWith(porArea).last()
                p.configs += Cfg("A_comum", s, true, false, durOkEm(s))
                p.configs += Cfg("B_comum", s, false, false, durOkEm(s))
            }
        } else {
            // a lógica não tem tamanho em comum com os dois: só B, na interseção dos físicos
            val ord = inter23.sortedWith(porArea)
            val sB = ord.firstOrNull { eh43(it) && it.width.toLong() * it.height <= 1440L * 1080L }
                ?: ord.firstOrNull { it.width <= 1920 && it.height <= 1088 } ?: ord.last()
            val vga = Size(640, 480)
            val menor = if (vga in inter23) vga else inter23.filter { eh43(it) && it.width >= 640 && it.height >= 480 }.minByOrNull { it.width.toLong() * it.height }
            p.s43 = sB
            p.configs += Cfg(if (eh43(sB)) "B_43" else "B_comum", sB, false, false, null)
            if (menor != null && menor != sB) { p.sMenor = menor; p.configs += Cfg("B_menor", menor, false, false, null) }
        }

        // consulta pré: só no id lógico, sem abrir a câmera (CameraDeviceSetup, Android 15+)
        if (Build.VERSION.SDK_INT >= 35) {
            etapa.set("versao_consulta")
            p.versaoConsulta = lerSeguro(p, "versao_consulta") { Api35.versao(c0) }
            for (cfg in p.configs) {
                etapa.set("consulta_pre:${cfg.nome}")
                cfg.pre = Api35.consulta(cm, logica, p.versaoConsulta, cfg.tam, cfg.comLogico, listOf(a, b))
            }
        } else for (cfg in p.configs) cfg.pre = "sem_consulta:android_${Build.VERSION.SDK_INT}"
        etapa.set("fim")
        p.veredito = when {
            p.configs.isEmpty() -> "sem_tamanho_comum"
            p.configs.any { it.pre == "sim" } -> "pre_sim"
            p.configs.all { it.pre == "nao" } -> "pre_nao"
            else -> "pre_indefinido"
        }
    }

    private fun <T> lerSeguro(p: Portao, nome: String, f: () -> T?): T? =
        try { f() } catch (e: Exception) { p.problemas += "$nome:${e.javaClass.simpleName}"; null }

    @RequiresApi(28)
    private fun lerSensor(id: String, c: CameraCharacteristics, p: Portao): InfoSensor {
        val mapa = lerSeguro(p, "$id:mapa") { c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
        if (mapa != null) p.mapas[id] = mapa
        val caps = lerSeguro(p, "$id:caps") { c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) } ?: IntArray(0)
        // Lp: critério do CTS (SurfaceHolder + alta resolução PRIVATE, até 1920x1088, maior área primeiro)
        val lp = lerSeguro(p, "$id:lp") {
            val base = mapa?.getOutputSizes(SurfaceHolder::class.java)?.toList().orEmpty()
            val alta = try { mapa?.getHighResolutionOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty() } catch (e: Exception) { emptyList() }
            (base + alta).filter { it.width <= 1920 && it.height <= 1088 }.distinct()
                .sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width })
        } ?: emptyList()
        val ly = lerSeguro(p, "$id:ly") { mapa?.getOutputSizes(ImageFormat.YUV_420_888)?.toSet() } ?: emptySet()
        return InfoSensor(
            id = id,
            nivel = lerSeguro(p, "$id:nivel") { c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) },
            cor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE),
            tsFonte = lerSeguro(p, "$id:ts_fonte") { c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) },
            focais = lerSeguro(p, "$id:focais") { c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) } ?: FloatArray(0),
            focoMin = lerSeguro(p, "$id:foco_min") { c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) },
            ois = lerSeguro(p, "$id:ois") { c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) } ?: IntArray(0),
            ativa = lerSeguro(p, "$id:ativa") { c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) },
            preCorr = lerSeguro(p, "$id:pre_corr") { c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) },
            matriz = lerSeguro(p, "$id:matriz") { c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) },
            fisico = lerSeguro(p, "$id:fisico") { c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) },
            orientacao = lerSeguro(p, "$id:orientacao") { c.get(CameraCharacteristics.SENSOR_ORIENTATION) },
            intr = lerSeguro(p, "$id:intr") { c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) },
            dist = lerSeguro(p, "$id:dist") { c.get(CameraCharacteristics.LENS_DISTORTION) },
            poseT = lerSeguro(p, "$id:pose_t") { c.get(CameraCharacteristics.LENS_POSE_TRANSLATION) },
            poseR = lerSeguro(p, "$id:pose_r") { c.get(CameraCharacteristics.LENS_POSE_ROTATION) },
            poseRef = lerSeguro(p, "$id:pose_ref") { c.get(CameraCharacteristics.LENS_POSE_REFERENCE) },
            lp = lp, ly = ly
        )
    }

    // ------------------------------------------------------------------ dados da execução

    /** Uma configuração avaliada: consultas, decisão, sessão e desfecho ("tipo:motivo"). */
    class RegistroCfg(val config: String, val larg: Int, val alt: Int, val tamanhoCts: Boolean, val fluxos: String, val pre: String) {
        var pos: String = "-"
        var decisao: String = "-"
        var fonteRecusa: String? = null
        var tentou = false
        var resultado: String = "nao_tentou"
        var motivo: String? = null
        var msBloqueio: Long? = null
        var ms: Long = 0
        var desfecho: String = "nao_testado:nao_avaliada"
        fun tipo(): String = desfecho.substringBefore(':')
        fun motivoDesfecho(): String? = desfecho.substringAfter(':', "").ifEmpty { null }
        fun rotulo(): String = config + ":" + when (tipo()) {
            "recusa" -> "recusa_${fonteRecusa ?: "?"}"
            "simultaneo", "simultaneo_nao_provado" -> tipo()
            else -> if (resultado == "configurada") desfecho else resultado
        }
    }

    /** Metadado de um sensor físico num resultado (CaptureResult físico). */
    class MetaFisica(
        val ts: Long?, val exp: Long?, val iso: Int?, val dur: Long?, val skew: Long?,
        val focal: Float?, val foco: Float?, val crop: Rect?, val zoom: Float?, val ois: Int?, val ae: Int?,
        val intr: FloatArray?, val dist: FloatArray?, val poseT: FloatArray?, val poseR: FloatArray?
    )

    class Prova(
        val mad: Double, val contradicao: String?, val escala: Double?, val ncc: Double?, val esperada: Double?,
        val porMetadado: Boolean, val porPixel: Boolean
    ) {
        val tipo: String get() = when {
            porMetadado && porPixel -> "ambos"; porMetadado -> "metadado"; porPixel -> "pixel"; else -> "nenhuma"
        }
    }

    /** Par simultâneo: NV21 completo dos dois (só até gravar), miniaturas Y 160x120 e o metadado casado. */
    class ParDados(
        val config: String, val w: Int, val h: Int, val idA: String, val idB: String,
        @Volatile var nvA: ByteArray?, @Volatile var nvB: ByteArray?,
        val tsImgA: Long, val tsImgB: Long, val casou: String,
        val miniA: ByteArray, val miniB: ByteArray, val msCopia: Long
    ) {
        var tsLogico: Long? = null
        var casouMeta: String = "nao"
        var metaA: MetaFisica? = null
        var metaB: MetaFisica? = null
        var af: Int? = null
        var ae: Int? = null
        var prova: Prova? = null
    }

    /** Um quadro do plano B (um sensor por sessão). */
    class SeqDados(val id: String, val w: Int, val h: Int) {
        var ok = false
        var motivo: String? = null
        @Volatile var nv: ByteArray? = null
        var tsImg: Long? = null
        var meta: MetaFisica? = null
        var af: Int? = null
        var ae: Int? = null
        var ms: Long = 0
        var intervaloMs: Double? = null
    }

    /** O que a Execucao devolve. O primeiro fim() vale. */
    internal class Bruto {
        var resultado: String? = null
        var motivo: String? = null
        var etapa: String = "inicio"
        var classe: String? = null
        var msg: String? = null
        val registros = ArrayList<RegistroCfg>()
        val medidas = ArrayList<Map<String, Any?>>()
        val contradicoes = ArrayList<String>()
        var consultaSim = false
        var configOk: String? = null
        var par: ParDados? = null
        val seq = ArrayList<SeqDados>()
        var sequencial: String = "nao_rodou"
        var prazo: String? = null
        var carimbo: String? = null
        var carimboTexto: String? = null
        var deltaUsMed: Double? = null
        var quadrosOk: Int? = null

        fun fim(resultado: String, motivo: String?, etapa: String) {
            if (this.resultado != null) return
            this.resultado = resultado; this.motivo = motivo; this.etapa = etapa
        }
    }

    /**
     * Veredito sem par válido, pela prioridade aceitou_nao_entregou > erro_app > recusou_sem_consulta > recusou_limpo >
     * nao_testado. recusou_limpo exige TODAS as configs existentes recusadas (inclusive A_cts, quando existe).
     */
    internal fun vereditoSemPar(regs: List<RegistroCfg>, p: Portao?, prazo: String?): Pair<String, String?> {
        if (regs.isEmpty()) return when {
            p?.veredito == "sem_tamanho_comum" -> "sem_tamanho_comum" to null
            prazo != null -> "nao_testado" to prazo
            else -> "nao_testado" to "nenhuma_config"
        }
        regs.firstOrNull { it.tipo() == "aceitou_nao_entregou" }?.let { return "aceitou_nao_entregou" to it.motivoDesfecho() }
        regs.firstOrNull { it.tipo() == "erro_app" }?.let { return "erro_app" to it.motivoDesfecho() }
        regs.firstOrNull { it.tipo() == "recusou_sem_consulta" }?.let { return "recusou_sem_consulta" to it.config }
        val total = p?.configs?.size ?: regs.size
        if (regs.size >= total && regs.all { it.tipo() == "recusa" }) return "recusou_limpo" to regs.joinToString(",") { "${it.config}:${it.fonteRecusa}" }
        regs.firstOrNull { it.tipo() == "nao_testado" }?.let { return "nao_testado" to (it.motivoDesfecho() ?: "incompleto") }
        return "nao_testado" to (prazo ?: "incompleto")
    }

    // ------------------------------------------------------------------ resultado

    class Resultado internal constructor(val rodada: String, val resultado: String, val motivo: String?, val etapa: String, val portao: Portao?) {
        var registros: List<RegistroCfg> = emptyList()
        var medidas: List<Map<String, Any?>> = emptyList()
        var contradicoes: List<String> = emptyList()
        var consultaSim = false
        var configOk: String? = null
        var par: ParDados? = null
        var seq: List<SeqDados> = emptyList()
        var sequencial: String = "nao_rodou"
        var prazo: String? = null
        var carimbo: String? = null
        var carimboTexto: String? = null
        var deltaUsMed: Double? = null
        var quadrosOk: Int? = null
        @Volatile var parSalvo = false
        @Volatile var seqSalvo = false
        @Volatile var pasta: File? = null
        var classe: String? = null
        var msg: String? = null
        var saidaMotivo: String? = null
        var saidaStatus: Int? = null
        var telaLigada: Boolean? = null
        var msTotal: Long = 0
        val quando: Long = System.currentTimeMillis()

        val prova: String? get() = par?.prova?.tipo

        /** Mesmos dados com outro veredito: o que o cancelamento forçado faz com o parcial da rodada. */
        internal fun comVeredito(res: String, mot: String?, et: String): Resultado = Resultado(rodada, res, mot, et, portao).also { c ->
            c.registros = registros; c.medidas = medidas; c.contradicoes = contradicoes; c.consultaSim = consultaSim
            c.configOk = configOk; c.par = par; c.seq = seq; c.sequencial = sequencial; c.prazo = prazo
            c.carimbo = carimbo; c.carimboTexto = carimboTexto; c.deltaUsMed = deltaUsMed; c.quadrosOk = quadrosOk
            c.parSalvo = parSalvo; c.seqSalvo = seqSalvo; c.pasta = pasta; c.classe = classe; c.msg = msg
        }

        internal fun copia(): Resultado = comVeredito(resultado, motivo, etapa)

        private fun a() = portao?.idA ?: par?.idA ?: "?"
        private fun b() = portao?.idB ?: par?.idB ?: "?"

        private fun tamanhoPar(): String = par?.let { "${it.w}x${it.h}" } ?: "?"

        /**
         * Tabela fechada de vereditos, em duas partes (revisão de desenho do Astra, 02/10): a primeira frase diz a
         * conclusão; a segunda traz a evidência e as ressalvas, junto da conclusão. O mesmo texto vale no diálogo, no
         * relatório e no resultado.txt. As distinções da tabela continuam: recusa do aparelho, erro do app e "não
         * testado" nunca se confundem.
         */
        fun titulo(): String = when (resultado) {
            "simultaneo" -> "Dois sensores distintos entregaram imagens na mesma requisição."
            "simultaneo_nao_provado" -> "Dois fluxos recebidos; sensores distintos não comprovados."
            "recusou_limpo" -> "O aparelho recusou a configuração com dois sensores."
            "recusou_sem_consulta" -> "O aparelho recusou criar a sessão com dois sensores."
            // frase da tabela do Astra, literal em todos os casos; "a sessão falhou ao configurar" vem na evidência (detalhe)
            "aceitou_nao_entregou" -> "O aparelho aceitou a configuração, mas não entregou as imagens esperadas."
            "sem_tamanho_comum" -> "Não foi encontrado tamanho de saída comum aos sensores ${a()} e ${b()}."
            "aparelho_sem_dois_sensores" -> "O aparelho não disponibiliza ao app uma câmera lógica com dois sensores físicos."
            "android_antigo" -> "Este teste precisa do Android 9 ou mais recente."
            "erro_app" -> "Teste inconclusivo por erro do app."
            "nao_testado" -> "Não foi possível concluir o teste."
            "cancelado_pelo_dono" -> "Teste cancelado."
            "processo_morreu" -> "O app foi encerrado durante o teste anterior, na etapa $etapa."
            else -> resultado
        }

        /** Evidência e ressalvas da conclusão. O motivo cru não entra aqui: vai entre parênteses, depois. */
        fun detalhe(): String = when (resultado) {
            "simultaneo" -> "Confirmado em ${quadrosOk ?: "?"} quadros: sensores ${a()} e ${b()}, ${configOk ?: "?"}, ${tamanhoPar()}. Evidência: ${provaPorExtenso()}."
            "simultaneo_nao_provado" -> "Chegaram na mesma requisição, mas ${semProvaPorExtenso()}."
            "recusou_limpo" -> detalheRecusa()
            "recusou_sem_consulta" -> "Não havia consulta de suporte disponível. Configuração: ${motivo ?: "?"}."
            "aceitou_nao_entregou" -> {
                val consulta = if (consultaSim) {
                    val cts = registros.firstOrNull { it.tipo() == "aceitou_nao_entregou" }?.tamanhoCts == true
                    " A consulta de suporte tinha respondido que aceitava; o que aconteceu contradiz essa resposta" +
                        if (cts) ", no tamanho que o CTS testa." else "."
                } else " Não havia consulta positiva para comparar; só a sessão foi configurada."
                maiuscula(extenso(motivo)) + "." + consulta
            }
            "sem_tamanho_comum" -> "O teste não pode pedir os dois juntos nas condições verificadas."
            "erro_app" -> maiuscula(extenso(motivo)) + ". Esse resultado não avalia o suporte do aparelho."
            "nao_testado" -> maiuscula(extenso(motivo)) + ". O suporte do aparelho não foi determinado; falha do teste, não resposta do aparelho."
            "processo_morreu" -> when (motivo) {
                "crash", "crash_nativo", "anr" -> "Registro do sistema: ${extenso(motivo)}."
                else -> "A causa não foi identificada."
            }
            else -> ""
        }

        private fun detalheRecusa(): String {
            val recusas = registros.filter { it.tipo() == "recusa" }
            val fontes = recusas.mapNotNull { it.fonteRecusa }.toSet()
            val quando = when (fontes) {
                setOf("pre") -> "antes de abrir a câmera"
                setOf("pos") -> "com a câmera aberta"
                else -> "antes e depois de abrir a câmera"
            }
            val sb = StringBuilder("A consulta indicou falta de suporte $quando, para ")
            sb.append(recusas.joinToString(", ") { "${it.config} ${it.larg}x${it.alt}" }).append(".")
            val cts = recusas.firstOrNull { it.tamanhoCts }
            if (cts != null) sb.append(" Inclui o tamanho que o CTS testa, ${cts.larg}x${cts.alt}.")
            else sb.append(" Ressalva: o tamanho de referência do CTS não foi testado.")
            if (fontes == setOf("pre")) sb.append(" Ressalva: recusa apenas na consulta antes de abrir.")
            return sb.toString()
        }

        private fun maiuscula(s: String): String = s.replaceFirstChar { it.uppercaseChar() }

        private fun provaPorExtenso(): String {
            val pr = par ?: return "?"
            val pv = pr.prova
            val partes = ArrayList<String>()
            val fa = pr.metaA?.focal; val fb = pr.metaB?.focal
            if (pv?.porMetadado == true && fa != null && fb != null) partes += "focais %.2f e %.2f mm".format(fa, fb)
            if (pv?.porPixel == true) partes += "escala medida %.2f (esperada %s)".format(pv.escala ?: 0.0, pv.esperada?.let { "%.2f".format(it) } ?: "?")
            return partes.joinToString(" e ").ifEmpty { "?" }
        }

        private fun semProvaPorExtenso(): String {
            val pr = par ?: return "sem par"
            val pv = pr.prova
            val meta = if (pr.metaA == null || pr.metaB == null) "o metadado físico não veio" else "as focais do metadado são iguais"
            val esc = pv?.escala?.let { "a escala %.2f não tem correlação suficiente (%.2f)".format(it, pv.ncc ?: 0.0) } ?: "a escala não foi medida"
            return "$meta e $esc"
        }

        /** Os três campos que separam captura, sincronização e arquivo (Astra): sucesso num não vira certeza no outro. */
        fun origem(): String = when {
            resultado == "simultaneo" -> "sensores ${a()} e ${b()} distintos, na mesma requisição (prova: ${prova ?: "nenhuma"})"
            resultado == "simultaneo_nao_provado" -> "mesma requisição; sensores distintos não comprovados"
            seq.any { it.ok } -> "cada sensor sozinho, um depois do outro"
            else -> "nenhuma imagem"
        }

        fun sincronizacao(): String = when (carimbo) {
            "medido" -> "medida pelo carimbo físico de cada sensor: mediana ${deltaUsMed?.let { "%.0f".format(it) } ?: "?"} µs"
            "aproximado" -> "aproximada: o aparelho declara sincronia aproximada; o carimbo não mede a exposição"
            // D5: só o que se viu; nada de atribuir a igualdade a uma cópia do HAL, que este teste não demonstra
            "iguais" -> TEXTO_CARIMBOS_IGUAIS
            "sem_metadado" -> "não medida: faltou o metadado físico dos dois sensores"
            else -> "não se aplica"
        }

        fun parSalvoTexto(): String = when {
            parSalvo -> "sim, só no aparelho"
            seqSalvo -> "só o par sequencial, no aparelho"
            else -> "não"
        }

        /** Rótulo obrigatório do plano B, sempre que um quadro sequencial existir (diálogo, relatório, seq.json). */
        fun rotuloSequencial(): String? {
            if (seq.none { it.ok }) return null
            val intervalo = seq.lastOrNull()?.intervaloMs
            return ROTULO_SEQ + (intervalo?.let { ": %.0f ms entre os quadros".format(it) } ?: "") + ". " + AVISO_SEQ
        }

        /** Estado do plano B quando ele foi tentado mas não deixou quadro (sem rótulo de par, porque não há par). */
        fun planoBSemQuadro(): String? =
            if (seq.none { it.ok } && sequencial != "nao_rodou") "Plano B (cada sensor sozinho): $sequencial" else null

        /** Há linha do plano B para o dono ver (com quadro ou com o estado dele). */
        private fun planoBTentado(): Boolean = seq.any { it.ok } || sequencial != "nao_rodou"

        fun proximoPasso(): String = when (resultado) {
            "simultaneo" -> if (carimbo == "medido") "Compartilhe o par para o protótipo de profundidade."
                else "Compartilhe o par para o protótipo de profundidade; a sincronização entre as exposições não foi medida."
            "simultaneo_nao_provado" -> "Repita com objetos com textura a 30 cm–1 m e boa luz."
            // D8: não promete que o plano B funcionou; "abaixo" só aparece quando há uma linha do plano B para ver
            "recusou_limpo", "recusou_sem_consulta" ->
                if (planoBTentado()) "A recusa não indica erro do app. Veja abaixo o resultado da tentativa com cada sensor separado."
                else "A recusa não indica erro do app."
            "aceitou_nao_entregou" -> "Repita uma vez com o celular frio e parado. Se persistir, compartilhe o relatório para investigar."
            "sem_tamanho_comum" -> if (planoBTentado()) "Veja abaixo o resultado da tentativa com cada sensor separado." else "Compartilhe o relatório."
            "erro_app" -> "Mande o relatório; tento de novo depois da correção."
            "nao_testado" -> {
                val m = motivo ?: ""
                when {
                    m.startsWith("camera_ocupada_por_outro_app") || m.contains("IN_USE") -> "Feche o outro app de câmera e tente de novo."
                    m.startsWith("interrompido") || m.startsWith("fechado_pelo_dono") -> "Mantenha o app aberto e a tela ligada até o fim."
                    m.startsWith("atualizado") -> "Rode o teste de novo na versão nova."
                    m.startsWith("cancelado") -> "Você pode tentar novamente em Sensores."
                    (portao != null && termicoAlto) -> "Espere o celular esfriar e tente de novo."
                    else -> "Tente de novo; se repetir, mande o relatório."
                }
            }
            "cancelado_pelo_dono" -> "Você pode tentar novamente em Sensores."
            "processo_morreu" -> "Compartilhe o relatório."
            else -> ""
        }

        internal var termicoAlto = false

        /** Motivo cru e prazo, só entre parênteses e depois da frase. */
        fun motivoCru(): String? = listOfNotNull(motivo, prazo).joinToString("; ").ifEmpty { null }

        /** Conclusão + evidência + motivo cru: a linha única do relatório. */
        fun resumo(): String = (titulo() + " " + detalhe()).trim() + (motivoCru()?.let { " ($it)" } ?: "")

        /** Linha "Último teste", com data. Interrupção usa o texto curto que o Astra pediu para o diálogo. */
        internal fun resumoComData(): String {
            val quandoTxt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(quando))
            val corpo = if (resultado == "processo_morreu") "interrompido com o app fechado, na etapa $etapa (${extenso(motivo)}). Compartilhe o relatório."
                else titulo() + (motivoCru()?.let { " ($it)" } ?: "")
            return "$quandoTxt, $corpo"
        }

        /** Tabela de quem também manda erro{onde=dois_sensores}. Recusa, cancelamento do dono e ambiente não mandam. */
        fun mandaErro(): Boolean {
            if (prazo != null) return true
            return when (resultado) {
                "erro_app", "aceitou_nao_entregou", "processo_morreu" -> true
                "nao_testado" -> {
                    val m = motivo ?: ""
                    m.substringBefore(':') in setOf("camerax_nao_fechou", "camera_0_ocupada", "prazo_portao", "prazo_geral", "camerax_religando", "cancelado") ||
                        m.startsWith("abrir:")
                }
                else -> false
            }
        }

        fun tentativas(): List<String> = registros.map { it.rotulo() }

        internal fun camposFim(r: Rodada?): Map<String, Any?> = linkedMapOf(
            "rodada" to rodada, "resultado" to resultado, "motivo" to motivo, "etapa" to etapa,
            "config_ok" to configOk, "prova" to prova, "carimbo" to carimbo, "delta_us_med" to deltaUsMed,
            "par_salvo" to parSalvo, "sequencial" to sequencial, "tentativas" to tentativas(), "prazo" to prazo,
            "em_primeiro_plano" to r?.emPrimeiroPlano, "tela_ligada" to telaLigada, "saiu_do_primeiro_plano" to r?.saiuDoPrimeiroPlano,
            "termico_ini" to r?.termicoIni, "termico_fim" to r?.termicoFim, "versao_teste" to VERSAO_TESTE,
            "parou_antes_de" to r?.paradaAntesDe,
            "saida_motivo" to saidaMotivo, "saida_status" to saidaStatus, "ms_total" to msTotal
        )

        /** Texto completo para compartilhar: sai mesmo com a telemetria desligada. */
        fun texto(): String {
            val sb = StringBuilder()
            sb.append("Teste de dois sensores ao mesmo tempo, rodada $rodada (app ${BuildConfig.VERSION_NAME}, teste v$VERSAO_TESTE, Android ${Build.VERSION.SDK_INT})\n")
            sb.append(titulo()).append("\n")
            detalhe().takeIf { it.isNotEmpty() }?.let { sb.append(it).append("\n") }
            motivoCru()?.let { sb.append("($it)\n") }
            if (resultado == "recusou_limpo" || resultado == "recusou_sem_consulta") sb.append(DECISAO_C).append("\n")
            sb.append("Origem das imagens: ${origem()}\n")
            sb.append("Sincronização: ${sincronizacao()}\n")
            sb.append("Par salvo: ${parSalvoTexto()}\n")
            proximoPasso().takeIf { it.isNotEmpty() }?.let { sb.append("Próximo passo: $it\n") }
            prazo?.let { sb.append("Prazo: ${extenso(it)}.\n") }
            if (registros.isNotEmpty()) {
                sb.append("\nConfigurações (pré = consulta sem abrir; pós = consulta com a câmera aberta):\n")
                for (g in registros) {
                    sb.append("   ${g.config} ${g.larg}x${g.alt} (${g.fluxos}${if (g.tamanhoCts) ", tamanho do CTS" else ""}): pré=${g.pre}, pós=${g.pos}, decisão=${g.decisao}; ")
                    sb.append("sessão: ${g.resultado}${g.motivo?.let { " ($it)" } ?: ""}; desfecho: ${g.desfecho}\n")
                }
            }
            for (m in medidas) sb.append(textoMedida(m))
            if (medidas.isNotEmpty()) {
                carimboTexto?.let { sb.append("Carimbo: $it\n") }
                sb.append("Nota: o carimbo igual das imagens é por desenho da API; quem mede é o SENSOR_TIMESTAMP físico.\n")
            }
            par?.let { sb.append(textoPar(it)) }
            faixaUtil()?.let { sb.append(it) }
            if (sequencial != "nao_rodou" || seq.isNotEmpty()) sb.append(textoSeq())
            if (contradicoes.isNotEmpty()) sb.append("\nContradições: ${contradicoes.joinToString("; ")}\n")
            if (saidaMotivo != null) sb.append("\nSaída do processo: $saidaMotivo (status $saidaStatus)\n")
            // texto livre de exceção: fica só aqui, no arquivo local; nunca vai para a telemetria (S5)
            if (classe != null || !msg.isNullOrEmpty()) sb.append("\nDetalhe técnico (só local): ${classe ?: "?"}${msg?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}\n")
            pasta?.let { sb.append("\nArquivos (só no aparelho): files/$PASTA/${it.name}\n") }
            return sb.toString()
        }

        private fun textoMedida(m: Map<String, Any?>): String {
            val a = a(); val b = b()
            return "\nMedida ${m["config"]}: ${m["quadros"]} resultados duplos, ${m["quadros_meta_2e3"]} com metadado dos dois sensores; " +
                "imagens: lógica ${m["imagens_logico"] ?: "-"}, $a ${m["imagens_$a"]}, $b ${m["imagens_$b"]}; " +
                "carimbos em comum ${m["ts_comuns"]} (mais ${m["ts_comuns_tol"]} por tolerância); " +
                "|Δ| mediana ${m["delta_us_med"] ?: "-"} µs (mín ${m["delta_us_min"] ?: "-"}, máx ${m["delta_us_max"] ?: "-"}); " +
                "meio da exposição ${m["meio_exp_us_med"] ?: "-"} µs; diferença de rolling shutter ${m["dif_skew_us"] ?: "-"} µs; " +
                "fps lógico ${m["fps_logico"] ?: "-"}, fps duplo ${m["fps_duplo"] ?: "-"}; ritmo $a ${m["ritmo_${a}_ms"] ?: "-"} ms, $b ${m["ritmo_${b}_ms"] ?: "-"} ms; " +
                "perdidos $a ${m["perdidos_$a"]}, $b ${m["perdidos_$b"]}; falhas $a ${m["falhas_$a"]}, $b ${m["falhas_$b"]}, sem id ${m["falhas_sem_id"]}; " +
                "AE ${m["ae_estado"]} (travado ${m["ae_travado"]}), AF ${m["af_estado"]}; sensor ativo ${m["ativo_fisico"] ?: "-"}; par: ${m["ts_casou"]}\n"
        }

        private fun textoPar(pr: ParDados): String {
            val pv = pr.prova
            val sb = StringBuilder("\nPar (${pr.config}, ${pr.w}x${pr.h}): carimbo das imagens ${pr.casou}, metadado ${pr.casouMeta}")
            val ta = pr.metaA?.ts; val tb = pr.metaB?.ts
            if (ta != null && tb != null) sb.append("; Δ do par ${(ta - tb) / 1000.0} µs")
            if (pv != null) {
                sb.append("; MAD %.1f".format(pv.mad))
                pv.escala?.let { sb.append("; escala medida %.2f (NCC %.2f)".format(it, pv.ncc ?: 0.0)) }
                pv.esperada?.let { sb.append("; escala esperada pelo recorte %.2f".format(it)) }
                sb.append("; prova: ${pv.tipo}")
            }
            sb.append(if (parSalvo) "; gravado" else "; NÃO gravado").append("\n")
            return sb.toString()
        }

        /** Z ≈ f·B/d para d = 4, 8 e 16 px, com f da ultra em px de saída. Só com base válida e recorte medido. */
        private fun faixaUtil(): String? {
            val p = portao ?: return null
            val pr = par ?: return null
            val base = p.baseMm ?: return "Faixa útil: base desconhecida (o aparelho não informou a pose dos dois sensores).\n"
            val sb = p.sensores[pr.idB] ?: return null
            val crop = pr.metaB?.crop ?: return null
            val f = (pr.metaB?.focal ?: sb.focais.minOrNull())?.toDouble() ?: return null
            val pa = sb.matriz?.width?.toDouble() ?: return null
            val wp = sb.fisico?.width?.toDouble() ?: return null
            if (wp <= 0 || crop.width() <= 0) return null
            val fpx = f * (pa / wp) * (pr.w.toDouble() / crop.width())
            val z = { d: Int -> fpx * base / 1000.0 / d }
            return "Faixa útil: Z ≈ f·B/d com f = ${fpx.roundToInt()} px (sensor ${pr.idB} na saída) e B = %.1f mm: d = 4 px → %.2f m; 8 px → %.2f m; 16 px → %.2f m.\n"
                .format(base, z(4), z(8), z(16))
        }

        private fun textoSeq(): String {
            val sb = StringBuilder("\nPlano B ($sequencial): $ROTULO_SEQ")
            val intervalo = seq.lastOrNull()?.intervaloMs
            if (intervalo != null) sb.append(": %.0f ms entre os quadros".format(intervalo))
            sb.append(". ").append(AVISO_SEQ).append("\n")
            for (s in seq) {
                sb.append("   sensor ${s.id} (${s.w}x${s.h}): ${if (s.ok) "ok" else "falhou"}${s.motivo?.let { " ($it)" } ?: ""}")
                s.meta?.let { m -> sb.append("; exposição ${m.exp} ns, ISO ${m.iso}, focal ${m.focal} mm") }
                sb.append("\n")
            }
            return sb.toString()
        }
    }

    private fun nomeSync(s: Int?) = when (s) {
        null -> "não informada"
        CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "CALIBRATED (calibrada)"
        CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "APPROXIMATE (aproximada)"
        else -> "desconhecida ($s)"
    }

    private fun consultaPorExtenso(v: String) = when {
        v == "sim" -> "o aparelho diz que aceita"
        v == "nao" -> "o aparelho diz que recusa"
        v.startsWith("sem_consulta") -> "sem consulta ($v)"
        else -> "a consulta falhou ($v)"
    }

    private fun portaoPorExtenso(v: String) = when (v) {
        "pre_sim" -> "alguma configuração foi aceita na consulta"
        "pre_nao" -> "todas as configurações foram recusadas na consulta"
        "pre_indefinido" -> "a consulta não decidiu; só o teste responde"
        "sem_tamanho_comum" -> "sem tamanho em comum entre os sensores"
        else -> v
    }

    /** Motivo cru por extenso, para a frase. O cru aparece entre parênteses. */
    internal fun extenso(motivo: String?): String {
        val m = motivo ?: return "sem motivo registrado"
        val base = m.substringBefore(':')
        val resto = m.substringAfter(':', "")
        return when (base) {
            "camerax_nao_fechou" -> "a câmera do app não fechou a tempo"
            "camera_0_ocupada" -> "a câmera 0 continuou ocupada"
            "camera_ocupada_por_outro_app" -> "outro app está usando a câmera"
            "prazo_portao" -> "a pré-checagem não respondeu em 5 s"
            "prazo_geral" -> "o teste passou do prazo de 1 min (etapa $resto)"
            "prazo_cancelar" -> "o cancelamento não terminou em 5 s (etapa $resto); a câmera foi fechada por fora"
            "camerax_religando" -> "a câmera do app estava religando"
            "perdeu_camera" -> "o sistema tirou a câmera do app ($resto)"
            "interrompido" -> "o app saiu da tela durante o teste (etapa $resto)"
            "sensor_indisponivel_pelo_sistema" -> "o sistema marcou o sensor $resto como indisponível"
            "abrir" -> "não consegui abrir a câmera ($resto)"
            "sem_quadro" -> "o sensor $resto não mandou nenhum quadro em 2 s"
            "configure_failed" -> "a sessão falhou ao configurar"
            "erro_dispositivo" -> "a câmera deu erro de dispositivo depois de configurar"
            "servico_caiu" -> "o serviço de câmera caiu"
            "ritmos_independentes" -> "os dois sensores mandaram quadros, mas em ritmos independentes, sem carimbo em comum"
            "entrega_insuficiente" -> "poucos quadros dos sensores ($resto)"
            "mesmo_sensor" -> "as duas imagens vieram do mesmo sensor"
            "mesmo_buffer" -> "as duas imagens eram o mesmo quadro copiado"
            "quadro_vazio" -> "o sensor $resto mandou quadro vazio"
            "congelado" -> "o sensor $resto mandou quadro congelado"
            "pareamento" -> "havia carimbos em comum, mas o app não montou o par"
            "sessao_sem_resposta" -> "a sessão não respondeu em 3 s"
            "excecao_callback" -> "exceção dentro de um retorno da câmera"
            "excecao" -> "exceção ($resto)"
            "consulta_pos" -> "a consulta com a câmera aberta falhou"
            "sessao", "requisicao", "leitor", "saidas", "orquestracao" -> "falha ao montar $base ($resto)"
            "cancelado" -> "o teste foi interrompido pelo app ($resto)"
            "fechado_pelo_dono" -> "o app foi fechado durante o teste"
            "atualizado" -> "o app foi atualizado durante o teste"
            "processo_encerrado" -> "o sistema encerrou o app ($resto)"
            "crash" -> "crash"
            "crash_nativo" -> "crash nativo"
            "anr" -> "ANR"
            "sem_registro" -> "sem registro do sistema"
            "sem_tempo" -> "faltou tempo dentro do prazo de 1 min"
            "sem_camera" -> "a câmera não estava aberta"
            "nenhuma_config" -> "nenhuma configuração avaliada"
            "incompleto" -> "nem todas as configurações foram avaliadas"
            else -> m
        }
    }

    // ------------------------------------------------------------------ execução

    /**
     * Lança a rodada no escopo do object: HandlerThread própria, Execucao confinada nela, depois análise (Default),
     * gravação (IO) e fechar(). Nunca toca no CameraX: quem solta e religa é a tela. O fio de callbacks NÃO sai quando a
     * tarefa acaba: fica vivo enquanto a câmera não for liberada pelo sistema (encerrarFio), porque um onOpened ou
     * onClosed tardio precisa dele para o device ser fechado.
     *
     * A supervisão nasce junto, no mesmo escopo (supervisionar): cão de guarda, prazo de cancelamento, fechar(), dois_fim
     * e a liberação de `ativa` não dependem da tela. Se a Activity for recriada no meio do teste, a rodada continua
     * supervisionada e termina com dois_fim; a tela só espera Rodada.resultado.
     */
    fun lancar(ctx: Context, r: Rodada, p: Portao, cx: InfoCameraX, aoFase: (String) -> Unit) {
        r.lancada = true
        execucoesVivas.add(r.id)
        val app = ctx.applicationContext
        val tarefa = escopo.async {
            r.portao = p
            r.termicoIni = termico(app)
            val fio = HandlerThread("dois-sensores").also { it.start() }
            r.fio = fio
            val h = Handler(fio.looper)
            try {
                val b: Bruto = if (Build.VERSION.SDK_INT >= 28) {
                    val e = Execucao(app, r, p, cx, h, aoFase)
                    withContext(h.asCoroutineDispatcher("dois-sensores")) { e.rodar() }
                } else Bruto().also { it.fim("android_antigo", null, "lancar") }
                r.termicoFim = termico(app)
                val res = montar(r, p, b)
                // volta tardia: o cão de guarda ou o cancelamento forçado já fecharam o resultado; nada é gravado depois
                // disso, e o fechar() abaixo só registra o dois_fim_tardio
                if (!r.fim.get()) {
                    if (res.par != null || res.seq.any { it.ok }) aoFase("Gravando o par.")
                    gravar(r, res)
                }
                fechar(r, res, limparAtiva = false, execucaoViva = false)
            } catch (e: CancellationException) {
                fechar(r, cancelado(r, p), limparAtiva = false, execucaoViva = false)
                throw e
            } catch (e: Throwable) {
                val res = Resultado(r.id, "erro_app", "excecao:${e.javaClass.simpleName}", r.etapa, p)
                res.classe = e.javaClass.simpleName; res.msg = (e.message ?: "").take(160)
                fechar(r, res, limparAtiva = false, execucaoViva = false)
            } finally {
                execucoesVivas.remove(r.id)
                r.execucaoTerminou = true
                escopo.launch { liberarMarca(r) }
                encerrarFio(r)
                soltar(r)
            }
        }
        supervisionar(r, tarefa)
    }

    private fun cancelado(r: Rodada, p: Portao?): Resultado {
        val (res, mot) = motivoCancelado(r)
        return Resultado(r.id, res, mot, r.etapa, p)
    }

    internal fun montar(r: Rodada, p: Portao?, b: Bruto): Resultado {
        val (res, mot) = b.resultado?.let { it to b.motivo } ?: vereditoSemPar(b.registros, p, b.prazo)
        val x = Resultado(r.id, res, mot, b.etapa, p)
        x.registros = b.registros.toList(); x.medidas = b.medidas.toList(); x.contradicoes = b.contradicoes.toList()
        x.consultaSim = b.consultaSim; x.configOk = b.configOk; x.par = b.par; x.seq = b.seq.toList()
        x.sequencial = b.sequencial; x.prazo = b.prazo; x.carimbo = b.carimbo; x.carimboTexto = b.carimboTexto
        x.deltaUsMed = b.deltaUsMed; x.quadrosOk = b.quadrosOk; x.classe = b.classe; x.msg = b.msg
        // 2 = PowerManager.THERMAL_STATUS_MODERATE (constante da API 29, escrita como número para não virar InlinedApi)
        x.termicoAlto = (r.termicoIni ?: 0) >= 2 || (r.termicoFim ?: 0) >= 2
        return x
    }

    /** Parcial ao fim de cada config: é o que o cão de guarda devolve se a thread travar depois. */
    internal fun atualizarParcial(r: Rodada, p: Portao?, b: Bruto) {
        r.parcial = montar(r, p, b)
    }

    private fun termico(ctx: Context): Int? =
        if (Build.VERSION.SDK_INT >= 29) try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus } catch (e: Exception) { null } else null

    private fun telaLigada(ctx: Context): Boolean? =
        try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (e: Exception) { null }

    // ------------------------------------------------------------------ espera da tarefa, cancelamento forçado e cão de guarda

    /**
     * Supervisão da rodada, no escopo do object e não no da tela: se a Activity for recriada (ou a tela sair da composição)
     * no meio do teste, ela segue de pé. Espera a tarefa (esperarFim) e GARANTE que a rodada termine com um dois_fim e que
     * `ativa` seja solta quando a tarefa acabar, ou já quando o cão de guarda ou o cancelamento forçado fecharem o
     * resultado. A tela só acompanha o resultado (Rodada.resultado).
     */
    private fun supervisionar(r: Rodada, t: Deferred<Resultado>) {
        escopo.launch {
            try {
                esperarFim(r, t)
                if (t.isCompleted) {
                    // a tarefa acabou sem fechar a rodada (exceção antes do try do corpo dela): fecha aqui, com a classe
                    if (!r.fim.get()) {
                        val causa = runCatching { t.await() }.exceptionOrNull() ?: IllegalStateException("sem_resultado")
                        falhaOrquestracao(r, r.etapa, causa, temExecucao = false)
                    }
                    soltar(r)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // última rede: nenhuma exceção sai desta tarefa (no escopo do object ela derrubaria o processo)
                runCatching { falhaOrquestracao(r, "supervisao", e, temExecucao = true) }
            }
        }
    }

    /**
     * Espera a tarefa da rodada. Saídas: ela termina (ou outro caminho já fechou a rodada); passa o cão de guarda
     * (W + 8 s); ou um cancelamento pedido (Cancelar, Voltar, app fora da tela) passa de PRAZO_CANCELAR_MS sem ela
     * terminar. Nas duas últimas, a câmera é fechada por fora, sem esperar a thread do teste, e o resultado fecha a partir
     * do parcial. A espera é uma sondagem de 50 ms: barata, e não depende de a thread do teste estar viva.
     */
    private suspend fun esperarFim(r: Rodada, t: Deferred<Resultado>) {
        val ini = SystemClock.elapsedRealtime()
        while (!t.isCompleted && !r.fim.get()) {
            val agora = SystemClock.elapsedRealtime()
            val cancelouEm = r.cancelarEm
            if (cancelouEm > 0L && agora - cancelouEm >= PRAZO_CANCELAR_MS) { cancelamentoForcado(r); return }
            if (agora - ini >= CAO_DE_GUARDA_MS) { prazoGeral(r); return }
            delay(50)
        }
    }

    /**
     * Cancelamento que a tarefa não cumpriu em PRAZO_CANCELAR_MS: fecha a câmera por fora e entrega o cancelado com o
     * parcial. O prazo vai no resultado (e no erro{}), porque thread presa depois de cancelar é anomalia a investigar.
     */
    private fun cancelamentoForcado(r: Rodada): Resultado {
        fecharPorFora(r, "prazo_cancelar")
        val etapa = r.etapa
        val (veredito, motivo) = motivoCancelado(r)
        val res = r.parcial?.comVeredito(veredito, motivo, etapa) ?: Resultado(r.id, veredito, motivo, etapa, r.portao)
        res.prazo = "prazo_cancelar:$etapa"
        return fechar(r, res, limparAtiva = true, execucaoViva = true)
    }

    // ------------------------------------------------------------------ fins (um dois_fim só por rodada)

    /**
     * Registro único: quem ganha a trava emite dois_fim (e erro{} quando a tabela manda) e grava o estado terminal e o
     * resultado.txt. Quem perde só registra. `limparAtiva` = solta `ativa`: o resultado da rodada acabou, e quem segura a
     * câmera física é `retida`, até o onClosed. `execucaoViva` = a thread do teste ainda pode estar presa (cão de guarda,
     * cancelamento forçado): a marca fica, como evidência, até a thread voltar (liberarMarca).
     */
    internal fun fechar(r: Rodada, res: Resultado, limparAtiva: Boolean, execucaoViva: Boolean = !limparAtiva): Resultado {
        // dentro da trava de publicação: gravar() confere o fim nela antes de mover o par, então nenhum par aparece depois
        if (!synchronized(r.travaPublicar) { r.fim.compareAndSet(false, true) }) {
            ev("dois_fim_tardio", linkedMapOf("rodada" to r.id, "resultado" to res.resultado, "motivo" to res.motivo, "etapa" to res.etapa))
            if (limparAtiva) soltar(r)
            return res
        }
        // o resultado final é publicado e `ativa` solta mesmo que a emissão abaixo falhe: a tela nunca fica esperando
        try {
            res.msTotal = SystemClock.elapsedRealtime() - r.inicio
            res.telaLigada = telaLigada(r.app)
            ev("dois_fim", res.camposFim(r))
            if (res.mandaErro()) ev("erro", linkedMapOf("onde" to "dois_sensores", "rodada" to r.id, "etapa" to res.etapa,
                "motivo" to (res.prazo ?: res.motivo ?: res.resultado), "classe" to res.classe))
            val resumo = res.resumoComData()
            escopo.launch {
                encerrarEstado(r, resumo, execucaoViva)
                gravarResultadoTxt(r, res)
            }
        } finally {
            if (limparAtiva) soltar(r)
            r.finalizada.complete(res)
        }
        return res
    }

    fun prazoPortao(r: Rodada, origem: String, etapa: String): Resultado {
        ev("dois_portao", linkedMapOf("rodada" to r.id, "origem" to origem, "prazo" to true, "etapa" to etapa))
        val res = Resultado(r.id, "nao_testado", "prazo_portao:$etapa", "portao", null)
        return fechar(r, res, limparAtiva = true)
    }

    fun semCamera(r: Rodada, p: Portao): Resultado =
        fechar(r, Resultado(r.id, p.veredito, null, "portao", p), limparAtiva = true)

    fun naoTestado(r: Rodada, p: Portao?, motivo: String, etapa: String): Resultado =
        fechar(r, Resultado(r.id, "nao_testado", motivo, etapa, p), limparAtiva = true)

    /**
     * Cão de guarda: fecha a câmera por fora (device.close() direto, a thread do teste pode estar presa no HAL) e devolve o
     * parcial com o prazo marcado. Solta `ativa` (teste novo e instalador passam a depender só da câmera liberada, pela
     * `retida`), mas não apaga a marca: a thread pode estar presa no HAL, e a volta tardia dela não grava nem abre nada.
     */
    fun prazoGeral(r: Rodada): Resultado {
        fecharPorFora(r, "cao_de_guarda")
        val etapa = r.etapa
        val res = r.parcial?.copia() ?: Resultado(r.id, "nao_testado", "prazo_geral:$etapa", etapa, r.portao)
        res.prazo = "prazo_geral:$etapa"
        if (r.planoB) res.sequencial = "interrompido:prazo"
        return fechar(r, res, limparAtiva = true, execucaoViva = true)
    }

    fun falhaOrquestracao(r: Rodada, etapa: String, e: Throwable, temExecucao: Boolean): Resultado {
        val res = Resultado(r.id, "erro_app", "orquestracao:${e.javaClass.simpleName}", etapa, r.portao)
        res.classe = e.javaClass.simpleName; res.msg = (e.message ?: "").take(160)
        return fechar(r, res, limparAtiva = !temExecucao)
    }

    /** Fim antes de haver Execucao (portão, soltar o CameraX). `quem`: dono, interrompido, activity_recriada... */
    fun canceladaAntes(r: Rodada, etapa: String, quem: String): Resultado {
        val res = when (quem) {
            "dono" -> Resultado(r.id, "cancelado_pelo_dono", null, etapa, r.portao)
            "interrompido" -> Resultado(r.id, "nao_testado", "interrompido:${r.saiuDoPrimeiroPlano ?: etapa}", etapa, r.portao)
            else -> Resultado(r.id, "nao_testado", "cancelado:$quem", etapa, r.portao)
        }
        return fechar(r, res, limparAtiva = true)
    }

    // ------------------------------------------------------------------ telemetria

    /**
     * ESQUEMA FECHADO (S5; revisão final do Astra, 02/10). Os eventos dois_* e os erro{onde=sensores|dois_*} só levam os
     * campos que estão numa lista explícita, por evento (ESQUEMA e PADROES), cada um com o seu tipo: número, booleano,
     * texto de um conjunto fechado (enum), código "base(:segmento)*" cuja BASE está num conjunto fechado, ou a classe
     * simples de uma exceção. Campo fora da lista, ou com valor fora do tipo, NÃO sai: conta em campos_barrados (e, se o
     * nome está na lista, entra em campos_invalidos). Throwable.message, toString() de objeto, ApplicationExitInfo.description
     * e texto livre não passam por construção; o texto livre mora no resultado.txt local. Os pontos de chamada já passam
     * códigos; esta tabela é a barreira que vale se um deles deixar de passar. Evento que não está na tabela não sai.
     */
    private fun esquemaFechado(tipo: String, campos: Map<String, Any?>): Boolean {
        if (tipo.startsWith("dois_") || tipo.startsWith("rajada_")) return true
        val onde = campos["onde"] as? String ?: return false
        return tipo == "erro" && (onde == "sensores" || onde == "rajada" || onde.startsWith("dois_"))
    }

    private val FORA_DO_CODIGO = Regex("[^A-Za-z0-9_:.,|=+\\-]")

    /** String do evento de falha do próprio esquema: até MAX_TEXTO caracteres, sem espaço, barra, acento nem parêntese. */
    internal fun texto60(s: String): String = FORA_DO_CODIGO.replace(s.take(MAX_TEXTO), "_")

    // ---- regras de tipo: cada uma diz se um valor (já saneado por limpa) pode sair

    private val NUM: Regra = { it is Number }
    private val BOOL: Regra = { it is Boolean }
    private fun txt(re: Regex): Regra = { it is String && re.matches(it) }
    private fun conj(c: Set<String>): Regra = { it is String && it in c }
    private fun conj(vararg v: String): Regra = conj(v.toSet())
    private fun lista(item: Regra): Regra = { v -> v is List<*> && v.all { it == null || item(it) } }
    private fun ou(a: Regra, b: Regra): Regra = { a(it) || b(it) }

    /** Nome simples de exceção: termina em Exception, Error ou Throwable. A mensagem da exceção nunca entra aqui. */
    private const val CLASSE_RE = "[A-Z][A-Za-z0-9]{0,50}(Exception|Error|Throwable)"
    private val BASE_COM_ID = Regex("sem_nv21_[A-Za-z0-9]{1,8}")

    /**
     * Código composto "base(:segmento)*". A base TEM de estar no conjunto fechado (bases); cada segmento (ou cada item de
     * uma lista separada por vírgula) tem de ser uma palavra do vocabulário fechado, uma etapa, ou uma forma estruturada
     * (classe de exceção, nome de enum em maiúsculas, número, razão "12/9", versao_N, android_N, imagem_<id>).
     */
    private fun cod(bases: Set<String>): Regra = { v ->
        v is String && v.isNotEmpty() && run {
            val partes = v.split(':')
            (partes[0] in bases || BASE_COM_ID.matches(partes[0])) && partes.drop(1).all { segmentoValido(it) }
        }
    }

    private val CONFIGS = setOf("A_cts", "A_43", "A_menor", "A_comum", "B_cts", "B_43", "B_menor", "B_comum")

    private val RESULTADOS = setOf(
        "simultaneo", "simultaneo_nao_provado", "recusou_limpo", "recusou_sem_consulta", "aceitou_nao_entregou", "sem_tamanho_comum",
        "aparelho_sem_dois_sensores", "android_antigo", "erro_app", "nao_testado", "cancelado_pelo_dono", "processo_morreu"
    )

    /**
     * Bases dos códigos compostos: os motivos que extenso() conhece, os resultados, as configs e os códigos de desfecho,
     * de consulta, de gravação e de compartilhamento. Base nova entra aqui junto com o código que a produz.
     */
    private val BASES = RESULTADOS + CONFIGS + setOf(
        "camerax_nao_fechou", "camera_0_ocupada", "camera_ocupada_por_outro_app", "prazo_portao", "prazo_geral", "prazo_cancelar", "camerax_religando",
        "perdeu_camera", "interrompido", "sensor_indisponivel_pelo_sistema", "abrir", "sem_quadro", "configure_failed", "erro_dispositivo", "servico_caiu",
        "ritmos_independentes", "entrega_insuficiente", "mesmo_sensor", "mesmo_buffer", "quadro_vazio", "congelado", "pareamento", "sessao_sem_resposta",
        "excecao_callback", "excecao", "consulta_pos", "sessao", "requisicao", "leitor", "saidas", "orquestracao", "cancelado", "fechado_pelo_dono",
        "atualizado", "processo_encerrado", "crash", "crash_nativo", "anr", "sem_registro", "sem_tempo", "sem_camera", "nenhuma_config", "incompleto",
        "recusa", "perda", "repeticao", "criar", "falhas", "falhou", "parcial", "ok", "nao_rodou", "prazo", "sim", "nao", "sem_consulta", "-",
        "CameraAccessException", "pre_diferente_de_pos", "configurada",
        "mkdirs_tmp", "mkdirs", "json_invalido", "vazio", "y_incompleto", "listar", "substituir", "mover", "pasta", "rodada_encerrada", "marca_grande", "marca_ilegivel",
        "pasta_invalida", "sem_imagens", "arquivo_sumiu", "lista_mudou", "decodificar", "relatorio_parcial"
    )

    /** Palavras que só aparecem depois da base (segmentos), além das próprias bases. */
    private val PALAVRAS = BASES + setOf(
        "pre", "pos", "ambas", "tarefa", "desconhecido", "desconhecida", "activity_recriada", "activity_fechando", "dono", "nao_perguntado",
        "setup_nao_suportado", "nao_avaliada", "reabrir", "caracteristicas", "consulta_pre", "nao_tentou", "requisicao_falhou", "recusa_pre", "recusa_pos",
        "recusa_ambas", "desconectada", "disponivel", "indisponivel", "fisica_disponivel", "fisica_indisponivel", "aberta", "erro_camera", "fechada",
        "sessao_configurada", "sessao_falhou", "resultado", "falha", "buffer_perdido"
    )
    private val ESTRUTURADO = Regex(
        "$CLASSE_RE|[A-Z][A-Z0-9_]{2,40}|R[0-9]{1,4}|[0-9]{1,9}|[0-9]{1,6}/[0-9]{1,6}|versao_([0-9]{1,3}|null)|android_[0-9]{1,3}|imagem_[A-Za-z0-9]{1,8}")
    private val ETAPA_RE = Regex(
        "inicio|nenhuma|lista|portao|soltar|execucao|supervisao|esperar_livre|abrir|fechar|lancar|gravar|gravar_seq|gravar_resultado|" +
            "chaves_logica|tamanhos|versao_consulta|concorrentes|fim|plano_b_reabrir|plano_b_abrir|" +
            "(plano_b|caracteristicas|camera)[_:][A-Za-z0-9]{1,8}|(sessao|duplo)[_:](A|B)_(cts|43|menor|comum)|consulta_pre:(A|B)_(cts|43|menor|comum)")

    private fun segmentoValido(s: String): Boolean =
        s.split(',').all { it in PALAVRAS || ETAPA_RE.matches(it) || ESTRUTURADO.matches(it) }

    private val RODADA = txt(Regex("[0-9a-f]{6}|manual"))
    private val ID = txt(Regex("[A-Za-z0-9]{1,8}"))
    private val IDS = txt(Regex("([A-Za-z0-9]{1,8}(,[A-Za-z0-9]{1,8}){0,7})?"))
    private val CFG = ou(conj(CONFIGS + "sequencial"), txt(Regex("seq_[A-Za-z0-9]{1,8}")))
    private val CLASSE = txt(Regex(CLASSE_RE))
    private val COD = cod(BASES)
    private val COD_OU_CLASSE = ou(COD, CLASSE)
    private val TAMANHO = txt(Regex("[0-9]{1,5}x[0-9]{1,5}"))
    private val FAIXA = txt(Regex("[0-9]{1,3}-[0-9]{1,3}"))
    private val ETAPA = txt(ETAPA_RE)
    private val PASSO = ou(ETAPA, conj("criar_sessao", "repetir", "copiar_imagem", "plano_b"))
    private val GATILHO = conj(
        "on_stop", "botao", "voltar", PEDIDO_DONO, PEDIDO_INTERROMPIDO, "activity_recriada", "activity_fechando", "dono", "desconhecido",
        "prazo_cancelar", "cao_de_guarda", "reabrir_manual", "onopened_tardio", "desconectada", "erro_camera", "fim_da_rodada"
    )
    private val ORIGEM = conj("teste", "relatorio")
    private val PROVA = conj("ambos", "metadado", "pixel", "nenhuma")
    private val CARIMBO = conj("medido", "aproximado", "iguais", "sem_metadado")
    private val FLUXOS = txt(Regex("(logico\\+)?[A-Za-z0-9]{1,8}(\\+[A-Za-z0-9]{1,8})?"))
    private val PERDA = txt(Regex("desconectada|ERROR_[A-Z_]{3,32}|ERRO_-?[0-9]{1,6}"))
    private val SAIDA = txt(Regex("[A-Z][A-Z_]{2,32}|R[0-9]{1,4}|sem_registro"))
    private val CALIB = txt(Regex("nenhuma|(intr|dist|pose_t|pose_r|ref)(,(intr|dist|pose_t|pose_r|ref)){0,4}"))
    private val PROBLEMA = txt(Regex(
        "(portao|sync|chaves_fisicas|estab|fps|ae_lock|versao_consulta|[A-Za-z0-9]{1,8}):$CLASSE_RE|" +
            "[A-Za-z0-9]{1,8}:(mapa|caps|lp|ly|nivel|ts_fonte|focais|foco_min|ois|ativa|pre_corr|matriz|fisico|orientacao|intr|dist|pose_t|pose_r|pose_ref):$CLASSE_RE|" +
            "fisicos_com_cor:[0-9]{1,2}|sem_caracteristicas"))
    private val RAZAO = txt(Regex("(erro|r[0-9]{1,3})(_com_imagem)?(_[A-Za-z0-9]{1,8})?=[0-9]{1,6}"))
    private val EXCECAO_CB = txt(Regex(
        "(disponivel|indisponivel|fisica_disponivel|fisica_indisponivel|aberta|desconectada|erro_camera|fechada|sessao_configurada|sessao_falhou|" +
            "resultado|falha|buffer_perdido|imagem_[A-Za-z0-9]{1,8}):$CLASSE_RE"))

    /** Campos permitidos por evento (chave "erro:<onde>" para os erro{}). Nome que não está aqui nem em PADROES não sai. */
    private val ESQUEMA: Map<String, Map<String, Regra>> = mapOf(
        "dois_inicio" to mapOf(
            "rodada" to RODADA, "android" to NUM, "modo" to conj("pro", "video", "foto", "retrato", "documento", "lenta", "macro", "tela"),
            "lente" to conj("frontal", "traseira"),
            "recusado" to conj("gravando", "ja_rodando", "fechando_camera", "ocupado", "processando", "temporizador", "camera_abrindo"),
            "camerax_estado_antes" to conj("PENDING_OPEN", "OPENING", "OPEN", "CLOSING", "CLOSED"), "camerax_erro_antes" to NUM,
            "camerax_bind_falhou" to BOOL
        ),
        "dois_portao" to mapOf(
            "rodada" to RODADA, "origem" to ORIGEM, "prazo" to BOOL, "etapa" to ETAPA, "portao_reaproveitado_de" to ORIGEM,
            "logica" to ID, "fisicas" to IDS, "id_principal" to ID, "id_aberto" to ID, "fisicos_cor" to IDS,
            "sync" to NUM, "versao_consulta" to NUM, "tela" to TAMANHO, "tam_cts" to TAMANHO, "tam_43" to TAMANHO, "tam_menor" to TAMANHO,
            "dur_ok" to BOOL, "fps_alvo" to FAIXA, "ae_lock_disp" to BOOL, "ois_off_disp" to BOOL, "estab_video_0" to lista(NUM),
            "base_mm" to NUM, "chaves_fisicas" to NUM, "consultas_pre" to lista(COD),
            "veredito_portao" to conj("pre_sim", "pre_nao", "pre_indefinido", "sem_tamanho_comum", "android_antigo", "aparelho_sem_dois_sensores"),
            "problemas" to lista(PROBLEMA), "ms" to NUM
        ),
        "dois_camerax" to mapOf(
            "rodada" to RODADA,
            "acao" to conj("fechamento_pendente", "fechou", "soltou", "abrir", "religou", "religar_manual", "repetir_fechamento",
                "fechamento_cooperativo_expirou"),
            "gatilho" to GATILHO, "ms" to NUM, "abrindo" to BOOL, "via" to conj("onclosed", "falha_abertura"), "ms_ate_onclosed" to NUM,
            "tardio" to BOOL, "depois_do_fim" to BOOL, "camerax_closed" to BOOL, "camerax_closed_ms" to NUM, "livre" to BOOL, "livre_ms" to NUM,
            "motivo" to COD_OU_CLASSE, "fisicas_indisponiveis" to lista(ID), "ok" to BOOL, "perda" to PERDA, "ms_bloqueio" to NUM,
            "estado" to txt(Regex("aberta|cancelado|aguardando_primeiro_plano|bind_falhou|prazo|erro:[0-9]{1,4}")), "erro_codigo" to NUM,
            "bind_classe" to CLASSE, "ms_sem_camerax" to NUM, "ms_ate_abrir" to NUM, "ms_contados" to NUM,
            "codigo_anterior" to txt(Regex("bind|sem_resposta|[0-9]{1,4}"))
        ),
        "dois_sessao" to mapOf(
            "rodada" to RODADA, "config" to CFG, "tamanho_cts" to BOOL, "larg" to NUM, "alt" to NUM, "fluxos" to FLUXOS,
            "consulta_pre" to COD, "consulta_pos" to COD,
            "decisao" to conj("tentar", "nao_tentar", "nao_tentar_erro_app", "nao_tentar_excecao", "sem_consulta", "plano_b", "-"),
            "fonte_recusa" to conj("pre", "pos", "ambas"), "tentou" to BOOL,
            "resultado" to conj("nao_tentou", "configurada", "configure_failed", "perdeu_camera", "prazo", "excecao", "sem_tempo", "cancelado",
                "tardia", "requisicao_falhou"),
            "motivo" to COD_OU_CLASSE, "desfecho" to COD, "ms_bloqueio" to NUM, "ms" to NUM,
            "fase" to conj("nenhuma", "aquece", "trava", "A1", "duplo", "parado", "seq")
        ),
        "dois_contradicao" to mapOf(
            "rodada" to RODADA, "config" to CFG, "consulta_pre" to COD, "consulta_pos" to COD, "o_que" to COD, "larg" to NUM, "alt" to NUM
        ),
        "dois_medida" to mapOf(
            "rodada" to RODADA, "config" to CFG, "quadros" to NUM, "quadros_meta_2e3" to NUM, "delta_us_min" to NUM, "delta_us_med" to NUM,
            "delta_us_max" to NUM, "delta_us_sinal_med" to NUM, "meio_exp_us_med" to NUM, "dif_skew_us" to NUM, "carimbo" to CARIMBO,
            "sync" to NUM, "fps_alvo" to FAIXA, "fps_logico" to NUM, "fps_duplo" to NUM, "resultados_A1" to NUM, "ts_comuns" to NUM,
            "ts_comuns_tol" to NUM, "atraso_entrega_med_ms" to NUM, "atraso_entrega_max_ms" to NUM, "menor_dif_ms" to NUM,
            "falhas_sem_id" to NUM, "falhas_outras" to NUM, "falha_razoes" to lista(RAZAO), "chegadas_durante_copia" to NUM,
            "erros_aquisicao" to NUM, "ae_estado" to NUM, "ae_travado" to BOOL, "af_estado" to NUM, "ativo_fisico" to ID, "par" to BOOL,
            "ts_casou" to conj("exato", "tolerancia", "nao"), "sem_quadro" to ID, "excecao_callback" to EXCECAO_CB, "ms" to NUM
        ),
        "dois_par" to mapOf(
            "rodada" to RODADA, "simultaneo" to BOOL, "config" to CFG, "larg" to NUM, "alt" to NUM, "ts_casou" to conj("exato", "tolerancia"),
            "meta" to conj("nao", "exato", "tolerancia"), "delta_par_us" to NUM, "meio_exp_par_us" to NUM, "af_estado" to NUM, "ae_estado" to NUM,
            "mad_y" to NUM, "escala_medida" to NUM, "escala_esperada" to NUM, "ncc" to NUM, "prova" to PROVA, "ms_copia" to NUM,
            "gravou" to BOOL, "motivo" to COD_OU_CLASSE, "ms" to NUM, "sequencial" to COD, "intervalo_ms" to NUM
        ),
        "dois_sequencial" to mapOf(
            "rodada" to RODADA, "id" to ID, "larg" to NUM, "alt" to NUM, "ok" to BOOL, "motivo" to COD_OU_CLASSE, "exp_ns" to NUM, "iso" to NUM,
            "focal_mm" to NUM, "crop" to lista(NUM), "af_estado" to NUM, "simultaneo" to BOOL, "rotulo" to conj("SEQUENCIAL_NAO_SIMULTANEO"),
            "intervalo_ms" to NUM, "ms" to NUM
        ),
        "dois_fim" to mapOf(
            "rodada" to RODADA, "resultado" to conj(RESULTADOS), "motivo" to COD_OU_CLASSE, "etapa" to ETAPA, "config_ok" to CFG, "prova" to PROVA,
            "carimbo" to CARIMBO, "delta_us_med" to NUM, "par_salvo" to BOOL, "sequencial" to COD, "tentativas" to lista(COD), "prazo" to COD,
            "em_primeiro_plano" to BOOL, "tela_ligada" to BOOL, "saiu_do_primeiro_plano" to ETAPA, "termico_ini" to NUM, "termico_fim" to NUM,
            "versao_teste" to NUM, "parou_antes_de" to PASSO, "saida_motivo" to SAIDA, "saida_status" to NUM, "ms_total" to NUM,
            "versao_codigo_rodada" to NUM, "relatado_na_abertura" to BOOL, "ja_encerrada" to BOOL
        ),
        "dois_fim_tardio" to mapOf(
            "rodada" to RODADA, "resultado" to conj(RESULTADOS), "motivo" to COD_OU_CLASSE, "etapa" to ETAPA
        ),
        "dois_apagar" to mapOf("rodada" to RODADA, "ok" to BOOL),
        "dois_compartilhar" to mapOf("rodada" to RODADA, "arquivos" to NUM, "bytes" to NUM, "sequencial" to BOOL),
        "erro:sensores" to mapOf(
            "onde" to conj("sensores"), "acao" to conj("abrir", "relatorio"), "motivo" to conj("prazo_portao", "relatorio_parcial"),
            "etapa" to ETAPA, "classe" to CLASSE
        ),
        "erro:dois_sensores" to mapOf(
            "onde" to conj("dois_sensores"), "rodada" to RODADA, "etapa" to ETAPA, "motivo" to COD_OU_CLASSE, "classe" to CLASSE
        ),
        "erro:dois_marca" to mapOf(
            "onde" to conj("dois_marca"), "rodada" to RODADA, "acao" to conj("gravar", "finalizar", "liberar", "relatar", "ler"),
            "motivo" to conj("marca_grande", "marca_ilegivel"), "classe" to CLASSE
        ),
        "erro:dois_arquivos" to mapOf("onde" to conj("dois_arquivos"), "acao" to conj("limpar"), "classe" to CLASSE),
        // rajada de teste (0.80, Rajada.kt): só números e códigos, também aqui. 0.81: "modo" (0 processada, 1 sem_processamento,
        // 2 raw), o resultado "modo_nao_aplicado" e a etapa "dng" (gravação dos DNG com a câmera aberta)
        "rajada_teste" to mapOf(
            "rodada" to RODADA, "modo" to NUM,
            "resultado" to conj("ok", "recusou_resolucao", "perdeu_camera", "cancelado", "erro", "camerax_nao_fechou", "sem_convergencia", "variou", "modo_nao_aplicado"),
            "classe" to CLASSE,
            "etapa" to conj("inicio", "soltar", "caracteristicas", "abrir", "sessao", "3a", "rajada", "dng", "devolver", "normal", "gravar"),
            "quadros" to NUM, "ms_total" to NUM, "fps" to NUM, "largura" to NUM, "altura" to NUM, "largura_max" to NUM, "altura_max" to NUM,
            "tentativas" to NUM, "exp_ns" to NUM, "iso" to NUM, "ois" to NUM, "nr" to NUM, "edge" to NUM, "ae_travado" to BOOL, "af_fixo" to BOOL,
            "max_res_disp" to BOOL, "normal" to BOOL, "gravou" to BOOL, "ms_gravar" to NUM
        ),
        "rajada_compartilhar" to mapOf("rodada" to RODADA, "arquivos" to NUM, "bytes" to NUM,
            "destino" to conj("downloads", "interno"), "ms_zip" to NUM, "reaproveitado" to BOOL),
        "erro:rajada" to mapOf(
            "onde" to conj("rajada"), "acao" to conj("apagar", "revisar", "zip", "compartilhar", "zip_downloads", "limpa_downloads"),
            "motivo" to conj("pasta_invalida", "arquivo_sumiu", "lista_mudou"), "classe" to CLASSE
        ),
        "erro:dois_compartilhar" to mapOf(
            "onde" to conj("dois_compartilhar"), "rodada" to RODADA, "acao" to conj("pacote", "ampliar"),
            "motivo" to conj("pasta_invalida", "sem_imagens", "arquivo_sumiu", "lista_mudou", "excecao", "decodificar"), "arquivos" to NUM,
            "classe" to CLASSE
        )
    )

    /** Nomes que levam o id de um sensor (2, 3, logico): o id é só letras e números, e o tipo vale para qualquer id. */
    private fun porSensor(prefixo: String, sufixo: String = ""): Regex = Regex(Regex.escape(prefixo) + "[A-Za-z0-9]{1,8}" + Regex.escape(sufixo))

    private val PADROES: Map<String, List<Pair<Regex, Regra>>> = mapOf(
        "dois_portao" to listOf(
            porSensor("nivel_") to NUM, porSensor("ts_fonte_") to NUM, porSensor("n_yuv_") to NUM, porSensor("cor_") to BOOL,
            porSensor("calib_") to CALIB, porSensor("pose_ref_") to NUM, porSensor("foco_min_") to NUM, porSensor("dur_", "_ns") to NUM
        ),
        "dois_medida" to listOf(
            porSensor("imagens_") to NUM, porSensor("imagens_", "_A1") to NUM, porSensor("meta_", "_A1") to NUM,
            porSensor("ts_comuns_logico_", "_A1") to NUM, porSensor("ritmo_", "_ms") to NUM, porSensor("perdidos_") to NUM,
            porSensor("falhas_") to NUM, porSensor("focal_", "_mm") to NUM, porSensor("zoom_") to NUM, porSensor("ois_") to NUM,
            porSensor("media_y_") to NUM, porSensor("desvio_y_") to NUM, porSensor("iguais_seguidos_") to NUM, porSensor("crop_") to lista(NUM)
        ),
        "dois_par" to listOf(
            porSensor("exp_", "_ns") to NUM, porSensor("iso_") to NUM, porSensor("foco_") to NUM, porSensor("bytes_jpg_") to NUM
        )
    )

    private fun regraDe(chave: String, campo: String): Regra? =
        ESQUEMA[chave]?.get(campo) ?: PADROES[chave]?.firstOrNull { it.first.matches(campo) }?.second

    /** Corta em MAX_TEXTO caracteres na última fronteira de código (':' ou ','), para não sobrar um pedaço de palavra. */
    private fun cortar(s: String): String {
        if (s.length <= MAX_TEXTO) return s
        val corte = s.take(MAX_TEXTO)
        if (s[MAX_TEXTO] == ',' || s[MAX_TEXTO] == ':') return corte
        val i = corte.lastIndexOfAny(charArrayOf(',', ':'))
        return if (i > 0) corte.substring(0, i) else corte
    }

    private fun truncar(v: Any?): Any? = when (v) {
        is String -> cortar(v)
        is List<*> -> v.map { truncar(it) }
        else -> v
    }

    /**
     * Passa os campos pelo esquema do evento. O valor é saneado (limpa) e cortado em MAX_TEXTO, e só então conferido contra
     * a regra do campo: o texto é conferido como veio, antes de qualquer troca de caracteres.
     */
    private fun filtrar(chave: String, campos: Map<String, Any?>, saida: MutableMap<String, Any?>) {
        var barrados = 0
        val invalidos = ArrayList<String>()
        for ((k, v) in campos) {
            val regra = regraDe(chave, k)
            if (regra == null) { barrados++; continue }
            val x = truncar(limpa(v))
            if (x == null || regra(x)) saida[k] = x else { barrados++; invalidos += k }
        }
        if (barrados > 0) saida["campos_barrados"] = barrados
        if (invalidos.isNotEmpty()) saida["campos_invalidos"] = invalidos
    }

    /**
     * Ponto central de envio dos eventos dois_* e dos erro{onde=sensores|dois_*}: esquema fechado (acima), saneador e ensaio
     * local antes do Telemetria.evento. Float/Double não finito vira null (o JSONObject do Android lança no chamador),
     * arrays e Rect viram lista. Se mesmo assim não serializar, o evento original é trocado por erro{onde=telemetria} com o
     * campo culpado e a classe da exceção, em vez de sumir calado.
     */
    fun ev(tipo: String, campos: Map<String, Any?>) {
        val limpo = LinkedHashMap<String, Any?>()
        if (esquemaFechado(tipo, campos)) {
            val chave = if (tipo == "erro") "erro:" + campos["onde"] else tipo
            if (ESQUEMA[chave] == null) {
                Telemetria.evento("erro", mapOf("onde" to "telemetria", "tipo" to texto60(tipo), "motivo" to "fora_do_esquema"))
                return
            }
            filtrar(chave, campos, limpo)
        } else for ((k, v) in campos) limpo[k] = limpa(v)
        var culpado: String? = null
        try {
            val o = JSONObject()
            for ((k, v) in limpo) {
                culpado = k
                o.put(k, when (v) { null -> JSONObject.NULL; is List<*> -> JSONArray(v); else -> v })
            }
            culpado = "toString"
            val txt: String? = o.toString()
            if (txt == null) throw IllegalStateException("toString nulo")
        } catch (e: Exception) {
            Telemetria.evento("erro", mapOf("onde" to "telemetria", "tipo" to texto60(tipo), "campo" to texto60(culpado ?: "?"),
                "classe" to e.javaClass.simpleName))
            return
        }
        Telemetria.evento(tipo, limpo)
    }

    internal fun limpa(v: Any?): Any? = when (v) {
        null -> null
        is Double -> if (v.isNaN() || v.isInfinite()) null else Math.round(v * 1000.0) / 1000.0
        is Float -> if (v.isNaN() || v.isInfinite()) null else Math.round(v.toDouble() * 10000.0) / 10000.0
        is String -> v
        is Int, is Long, is Short, is Byte, is Boolean -> v
        is Number -> v.toDouble().let { if (it.isNaN() || it.isInfinite()) null else it }
        is Enum<*> -> v.name
        is FloatArray -> v.map { limpa(it) }
        is DoubleArray -> v.map { limpa(it) }
        is IntArray -> v.toList()
        is LongArray -> v.toList()
        is Array<*> -> v.map { limpa(it) }
        is Collection<*> -> v.map { limpa(it) }
        is Map<*, *> -> v.entries.joinToString(",") { "${it.key}=${limpa(it.value)}" }
        is Rect -> listOf(v.left, v.top, v.right, v.bottom)
        is Size -> v.wxh()
        is SizeF -> "${v.width}x${v.height}"
        is Range<*> -> "${v.lower}-${v.upper}"
        else -> v.toString()
    }

    /** Valor pronto para JSONObject.put (par.json, arquivo local): saneado, lista vira JSONArray, null vira NULL. */
    private fun jsonValor(v: Any?): Any = when (val x = limpa(v)) {
        null -> JSONObject.NULL
        is List<*> -> JSONArray(x)
        else -> x
    }

    private fun JSONObject.poe(k: String, v: Any?): JSONObject { put(k, jsonValor(v)); return this }

    // ------------------------------------------------------------------ marca contra crash nativo

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun versaoCodigo(): Long = BuildConfig.VERSION_CODE.toLong()

    /**
     * Marca gravada com commit() só antes de etapa arriscada (abrir, sessão, fase dupla, plano B). Crash nativo no HAL
     * derruba o processo sem passar por catch nenhum; a marca é o que sobra para a abertura seguinte contar. Passa pela
     * travaMarca, como a finalização e a recuperação, e confere a rodada e o estado DENTRO dela: uma finalização que passou
     * enquanto esta gravação esperava a trava não pode ser seguida por uma marca nova.
     */
    internal suspend fun gravarMarca(r: Rodada, etapa: String) {
        if (r.fim.get()) return
        withContext(Dispatchers.IO) {
            travaMarca.withLock {
                if (r.fim.get() || r.terminalGravado) return@withLock
                try {
                    val j = JSONObject().put("v", 2).put("rodada", r.id).put("etapa", etapa).put("pid", Process.myPid()).put("proc", processo)
                        .put("codigo", versaoCodigo()).put("epoch", System.currentTimeMillis())
                    prefs(r.app).edit().putString(chaveMarca(r.id), j.toString()).commit()
                } catch (e: Exception) {
                    ev("erro", linkedMapOf("onde" to "dois_marca", "rodada" to r.id, "acao" to "gravar", "classe" to e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * Finalização da rodada, dentro da travaMarca: primeiro o estado terminal (o resumo, recuperável se o processo morrer
     * agora), depois a marca. A marca só sai se a Execucao já acabou: o cão de guarda ou um cancelamento forçado encerram o
     * RESULTADO, mas a thread pode continuar presa no HAL, e a marca é a evidência dessa execução (liberarMarca a apaga
     * quando a thread voltar).
     */
    private suspend fun encerrarEstado(r: Rodada, resumo: String, execucaoViva: Boolean) {
        travaMarca.withLock {
            try {
                val p = prefs(r.app)
                p.edit().putString("ultimo_resumo", resumo).putString("ultimo_resumo_rodada", r.id).commit()
                r.terminalGravado = true
                if (!execucaoViva || r.execucaoTerminou) removerMarcaDe(p, r.id)
            } catch (e: Exception) {
                ev("erro", linkedMapOf("onde" to "dois_marca", "rodada" to r.id, "acao" to "finalizar", "classe" to e.javaClass.simpleName))
            }
        }
    }

    /** A Execucao acabou (a thread está livre): se o estado terminal já foi gravado, a marca pode sair. */
    private suspend fun liberarMarca(r: Rodada) {
        travaMarca.withLock {
            try {
                if (r.terminalGravado) removerMarcaDe(prefs(r.app), r.id)
            } catch (e: Exception) {
                ev("erro", linkedMapOf("onde" to "dois_marca", "rodada" to r.id, "acao" to "liberar", "classe" to e.javaClass.simpleName))
            }
        }
    }

    /**
     * Uma marca por rodada ("marca_<rodada>"). Com o cão de guarda soltando `ativa`, uma rodada nova pode começar com a
     * thread da anterior ainda presa: com a marca única, a nova sobrescrevia a evidência da antiga e a apagava ao
     * terminar (revisão do Astra, 02/10). "marca" sem sufixo é o formato antigo, só lido na recuperação.
     */
    private fun chaveMarca(rodada: String) = "marca_$rodada"

    /** Remove a marca desta rodada; as de outras rodadas ficam. Dentro da travaMarca. */
    private fun removerMarcaDe(p: SharedPreferences, rodada: String) {
        if (p.contains(chaveMarca(rodada))) p.edit().remove(chaveMarca(rodada)).commit()
    }

    /**
     * Abertura do app: se sobrou marca de outro processo, o teste anterior morreu no meio. Conta o que o sistema
     * registrou (ApplicationExitInfo, Android 11+) e avisa uma vez. Marca deste mesmo processo = Activity recriada com
     * o teste vivo: não mexe. Tudo em IO (a primeira tela não espera disco nem Binder) e sob a mesma trava da marca:
     * uma marca nova gravada no meio não é apagada por engano (revisão de segurança do Astra, 02/10). Também apaga as
     * pastas temporárias órfãs do par.
     */
    fun relatarInterrompida(ctx: Context) {
        val app = ctx.applicationContext
        escopo.launch(Dispatchers.IO) {
            try { limparTemporarias(app) } catch (e: Exception) {
                ev("erro", linkedMapOf("onde" to "dois_arquivos", "acao" to "limpar", "classe" to e.javaClass.simpleName))
            }
            try { travaMarca.withLock { recuperar(app) } } catch (e: Exception) {
                ev("erro", linkedMapOf("onde" to "dois_marca", "acao" to "relatar", "classe" to e.javaClass.simpleName))
            }
        }
    }

    /** Recuperação das marcas (uma por rodada, mais a antiga "marca"), dentro da travaMarca. */
    private fun recuperar(app: Context) {
        val p = prefs(app)
        val chaves = p.all.keys.filter { it == "marca" || it.startsWith("marca_") }
        for (chave in chaves) recuperarMarca(app, p, chave)
    }

    private fun recuperarMarca(app: Context, p: SharedPreferences, chave: String) {
        val txt = p.all[chave] as? String ?: run { p.edit().remove(chave).commit(); return }
        // marca maior que o tamanho que a gente grava não é nossa: descartada sem ler o conteúdo
        if (txt.length > TAMANHO_MARCA || txt.toByteArray().size > TAMANHO_MARCA) {
            p.edit().remove(chave).commit()
            ev("erro", linkedMapOf("onde" to "dois_marca", "acao" to "ler", "motivo" to "marca_grande"))
            return
        }
        val j = try { JSONObject(txt) } catch (e: Exception) {
            p.edit().remove(chave).commit()
            ev("erro", linkedMapOf("onde" to "dois_marca", "acao" to "ler", "motivo" to "marca_ilegivel", "classe" to e.javaClass.simpleName))
            return
        }
        if (j.optString("proc", "") == processo) return   // Activity recriada com o teste vivo neste mesmo processo
        relatarMarca(app, p, j, chave)
    }

    /**
     * O teste morreu com o processo. Só a razão e o status do ApplicationExitInfo contam (nada de traces, tombstones nem
     * description). O estado terminal (resumo) é gravado ANTES de a marca sair. Se a rodada já tinha o resultado gravado
     * (o cão de guarda a encerrou e a thread presa levou o processo junto), o dono já viu o resultado: só a telemetria
     * leva a novidade, sem sobrescrever o resumo nem avisar de novo.
     */
    private fun relatarMarca(app: Context, p: SharedPreferences, j: JSONObject, chave: String) {
        val pid = j.optInt("pid", -1)
        val rodada = j.optString("rodada", "?")
        val etapa = j.optString("etapa", "?")
        val versaoDaRodada = j.optLong("codigo", -1L)
        val epoch = j.optLong("epoch", 0L)
        var razao: Int? = null; var status: Int? = null
        if (Build.VERSION.SDK_INT >= 30) saidaDoProcesso(app, pid, epoch)?.let { razao = it.first; status = it.second }
        val rz = razao
        val (res, mot) = when {
            rz == null -> "processo_morreu" to "sem_registro"
            rz == 4 -> "processo_morreu" to "crash"               // REASON_CRASH
            rz == 5 -> "processo_morreu" to "crash_nativo"        // REASON_CRASH_NATIVE
            rz == 6 -> "processo_morreu" to "anr"                 // REASON_ANR
            rz == 10 || rz == 11 -> "nao_testado" to "fechado_pelo_dono"   // USER_REQUESTED, USER_STOPPED
            rz == 16 -> "nao_testado" to "atualizado"             // PACKAGE_UPDATED
            else -> "nao_testado" to "processo_encerrado:${nomeRazao(rz)}"
        }
        val x = Resultado(rodada, res, mot, etapa, null)
        x.saidaMotivo = rz?.let { nomeRazao(it) } ?: "sem_registro"; x.saidaStatus = status
        val jaEncerrada = p.getString("ultimo_resumo_rodada", null) == rodada
        ev("dois_fim", linkedMapOf("rodada" to rodada, "resultado" to res, "motivo" to mot, "etapa" to etapa, "versao_teste" to VERSAO_TESTE,
            "versao_codigo_rodada" to versaoDaRodada, "saida_motivo" to x.saidaMotivo, "saida_status" to status,
            "relatado_na_abertura" to true, "ja_encerrada" to jaEncerrada))
        if (x.mandaErro()) ev("erro", linkedMapOf("onde" to "dois_sensores", "rodada" to rodada, "etapa" to etapa, "motivo" to mot))
        if (!jaEncerrada) {
            p.edit().putString("ultimo_resumo", x.resumoComData()).putString("ultimo_resumo_rodada", rodada).commit()
            Handler(Looper.getMainLooper()).post {
                try { Toast.makeText(app, "Teste anterior interrompido. Veja Sensores.", Toast.LENGTH_LONG).show() } catch (e: Exception) { }
            }
        }
        p.edit().remove(chave).commit()
    }

    /** Razão e status da saída do processo, só do próprio pacote, casando o PID e o horário da marca. */
    @RequiresApi(30)
    private fun saidaDoProcesso(ctx: Context, pid: Int, epoch: Long): Pair<Int, Int>? = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lista: List<ApplicationExitInfo> = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 16)
        // a saída tem que ser deste PID e posterior à marca: PID sozinho pode ser de outro processo antigo
        lista.firstOrNull { it.pid == pid && it.timestamp >= epoch }?.let { it.reason to it.status }
    } catch (e: Exception) { null }

    private fun nomeRazao(r: Int) = when (r) {
        0 -> "UNKNOWN"; 1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"; 6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"
        else -> "R$r"
    }

    fun ultimoResumo(ctx: Context): String? = try { prefs(ctx.applicationContext).getString("ultimo_resumo", null) } catch (e: Exception) { null }

    // ------------------------------------------------------------------ arquivos (só no aparelho)

    private fun raiz(ctx: Context) = File(ctx.filesDir, PASTA)

    /** Pasta final da rodada, criada na primeira vez. Só chamar com a travaArquivos. */
    private fun pastaDaRodada(r: Rodada): File {
        r.pasta?.let { return it }
        val p = File(raiz(r.app), SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + r.id)
        if (!p.isDirectory && !p.mkdirs()) throw java.io.IOException("mkdirs")
        r.pasta = p
        return p
    }

    /** Onde o par é escrito antes de ir para a pasta da rodada. Só ganha o nome final quando está consistente. */
    private fun pastaTemporaria(ctx: Context, id: String) = File(raiz(ctx), TMP + id)

    /**
     * Guarda no máximo GUARDAR_RODADAS pastas COM par (par.json ou seq.json) e, à parte, no máximo GUARDAR_RODADAS só com
     * resultado.txt. Contar tudo junto deixava as rodadas sem par (recusas) empurrarem um par bom para fora. As
     * temporárias não entram: pertencem a uma gravação em curso. Só chamar com a travaArquivos.
     */
    private fun podar(ctx: Context) {
        val pastas = raiz(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(TMP) }?.sortedByDescending { it.name } ?: return
        val (comPar, semPar) = pastas.partition { File(it, "par.json").exists() || File(it, "seq.json").exists() }
        for (velha in comPar.drop(GUARDAR_RODADAS) + semPar.drop(GUARDAR_RODADAS)) try { velha.deleteRecursively() } catch (e: Exception) { }
    }

    /** Pasta mais nova que tem par (ou plano B) gravado, conferida com exists(). Temporárias não contam. */
    fun ultimaPasta(ctx: Context): File? = try {
        raiz(ctx).listFiles()?.filter { it.isDirectory && !it.name.startsWith(TMP) }?.sortedByDescending { it.name }
            ?.firstOrNull { File(it, "par.json").exists() || File(it, "seq.json").exists() }
    } catch (e: Exception) { null }

    /** Na abertura do app: apaga as temporárias órfãs (gravação que o processo não terminou), menos as de rodada viva. */
    private suspend fun limparTemporarias(ctx: Context) {
        travaArquivos.withLock {
            val vivas = setOfNotNull(ativa?.id, retida?.id) + execucoesVivas
            raiz(ctx).listFiles()?.filter { it.isDirectory && it.name.startsWith(TMP) && it.name.removePrefix(TMP) !in vivas }?.forEach {
                try { it.deleteRecursively() } catch (e: Exception) { }
            }
        }
    }

    /**
     * "Apagar este teste": remove a pasta inteira da rodada (par, metadados e resultado). Só aceita filha direta de
     * files/estereo pelo caminho canônico. Devolve se apagou.
     */
    suspend fun apagar(ctx: Context, pasta: File): Boolean = travaArquivos.withLock {
        val id = pasta.name.substringAfterLast('_')
        val ok = try {
            val p = pasta.canonicalFile
            p.parentFile == raiz(ctx).canonicalFile && !p.name.startsWith(TMP) && p.isDirectory && p.deleteRecursively()
        } catch (e: Exception) { false }
        if (ok) apagadas.add(id)
        ev("dois_apagar", linkedMapOf("rodada" to id, "ok" to ok))
        ok
    }

    /** resultado.txt da rodada na pasta final, depois do estado terminal. Uma rodada apagada pelo dono não volta. */
    private suspend fun gravarResultadoTxt(r: Rodada, res: Resultado) {
        travaArquivos.withLock {
            if (r.id in apagadas) return@withLock
            try {
                val pasta = pastaDaRodada(r)
                res.pasta = pasta
                File(pasta, "resultado.txt").writeText(res.texto())
                podar(r.app)
            } catch (e: Exception) {
                ev("erro", linkedMapOf("onde" to "dois_sensores", "rodada" to r.id, "etapa" to "gravar_resultado", "classe" to e.javaClass.simpleName))
            }
        }
    }

    /** Falha de gravação com código fechado: só o código vai para a telemetria, nunca a mensagem. */
    private class FalhaGravacao(val codigo: String) : Exception(codigo)

    private class Gravado(val ok: Boolean, val motivo: String?, val bytesA: Long? = null, val bytesB: Long? = null)

    /**
     * Grava o par numa pasta TEMPORÁRIA, confere que tudo chegou e só então move para a pasta da rodada (S8): uma falha ou
     * um cancelamento no meio nunca deixa JPEG ou Y parcial onde "Ver último par" e o compartilhamento enxergam.
     */
    private suspend fun gravar(r: Rodada, res: Resultado) {
        travaArquivos.withLock {
            val par = res.par
            val temPar = par != null
            val temSeq = res.seq.any { it.ok }
            if (!temPar && !temSeq) return@withLock   // nada de imagem a guardar: o resultado.txt vai pelo fechar()
            val t0 = SystemClock.elapsedRealtime()
            val tmp = pastaTemporaria(r.app, r.id)
            var gp: Gravado? = null
            var gs: Gravado? = null
            var motivoPasta: String? = null
            try {
                tmp.deleteRecursively()
                if (!tmp.mkdirs()) throw FalhaGravacao("mkdirs_tmp")
                gp = par?.let { escreverPar(r, res, it, tmp) }
                if (temSeq) gs = escreverSeq(r, res, tmp)
                res.parSalvo = gp?.ok == true; res.seqSalvo = gs?.ok == true
                // o resultado.txt entra no pacote; o fechar() o regrava depois, com os tempos finais
                try { File(tmp, "resultado.txt").writeText(res.texto()) } catch (e: Exception) { }
                if ((gp != null && !gp.ok) || (gs != null && !gs.ok)) throw FalhaGravacao("incompleto")
                // volta tardia: com o resultado já anunciado (cão de guarda, cancelamento forçado), o par é descartado em
                // vez de aparecer depois do fim; a temporária sai no finally (revisão do Astra, 02/10)
                synchronized(r.travaPublicar) {
                    if (r.fim.get()) throw FalhaGravacao("rodada_encerrada")
                    mover(tmp, pastaDaRodada(r))
                }
            } catch (e: Exception) {
                motivoPasta = if (e is FalhaGravacao) e.codigo else "pasta:${e.javaClass.simpleName}"
                res.parSalvo = false; res.seqSalvo = false
            } finally {
                try { tmp.deleteRecursively() } catch (e: Exception) { }
            }
            res.pasta = r.pasta
            // os eventos saem depois do movimento: "gravou" quer dizer que o par está na pasta da rodada
            par?.let { emitirPar(r, res, it, gp, motivoPasta, t0) }
            if (temSeq) emitirSeq(r, res, gs, motivoPasta, t0)
            podar(r.app)
        }
    }

    /**
     * Move os arquivos da temporária para a pasta da rodada, o JSON por último: ultimaPasta() só enxerga uma pasta quando
     * par.json ou seq.json existe, então o par aparece de uma vez. Se algum movimento falhar, desfaz os que já foram.
     */
    private fun mover(tmp: File, destino: File) {
        if (!destino.isDirectory && !destino.mkdirs()) throw FalhaGravacao("mkdirs")
        val arquivos = tmp.listFiles()?.filter { it.isFile }?.sortedBy { if (it.name == "par.json" || it.name == "seq.json") 1 else 0 }
            ?: throw FalhaGravacao("listar")
        val movidos = ArrayList<File>()
        try {
            for (a in arquivos) {
                val alvo = File(destino, a.name)
                if (alvo.exists() && !alvo.delete()) throw FalhaGravacao("substituir")
                if (!a.renameTo(alvo)) throw FalhaGravacao("mover")
                movidos += alvo
            }
        } catch (e: Exception) {
            for (m in movidos) try { m.delete() } catch (x: Exception) { }
            throw e
        }
    }

    private fun gravaJpeg(nv: ByteArray, w: Int, h: Int, arq: File, orientacao: Int?): Long {
        arq.outputStream().use { out ->
            if (!YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 95, out)) throw java.io.IOException("compressToJpeg")
        }
        // pixels na orientação do sensor; quem vê a foto gira pelo EXIF
        val tag = when (((orientacao ?: 0) % 360 + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90; 180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270; else -> ExifInterface.ORIENTATION_NORMAL
        }
        try { ExifInterface(arq.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, tag.toString()); saveAttributes() } } catch (e: Exception) { }
        return arq.length()
    }

    private fun gravaY(nv: ByteArray, w: Int, h: Int, arq: File) { arq.outputStream().use { it.write(nv, 0, w * h) } }

    private fun sensorJson(p: Portao?, id: String, meta: MetaFisica?, tsImg: Long?, prefixo: String): JSONObject {
        val s = p?.sensores?.get(id)
        return JSONObject()
            .poe("arquivo_jpg", "${prefixo}_$id.jpg").poe("arquivo_y", "${prefixo}_$id.y")
            .poe("carimbo_imagem_ns", tsImg).poe("sensor_timestamp_ns", meta?.ts)
            .poe("exposicao_ns", meta?.exp).poe("iso", meta?.iso).poe("frame_duration_ns", meta?.dur).poe("rolling_shutter_skew_ns", meta?.skew)
            .poe("focal_mm", meta?.focal).poe("foco_dioptrias", meta?.foco).poe("crop", meta?.crop).poe("zoom", meta?.zoom).poe("ois", meta?.ois)
            .poe("intrinseca_estatica", s?.intr).poe("intrinseca_resultado", meta?.intr)
            .poe("distorcao_estatica", s?.dist).poe("distorcao_resultado", meta?.dist)
            .poe("pose_t", meta?.poseT ?: s?.poseT).poe("pose_r", meta?.poseR ?: s?.poseR).poe("pose_ref", s?.poseRef)
            .poe("matriz_ativa", s?.ativa).poe("pre_correcao", s?.preCorr).poe("matriz_pixels", s?.matriz).poe("tamanho_fisico_mm", s?.fisico)
            .poe("sensor_orientation", s?.orientacao).poe("focais_mm", s?.focais)
    }

    private fun escreverPar(r: Rodada, res: Resultado, pr: ParDados, pasta: File): Gravado {
        val p = res.portao
        var bytesA: Long? = null; var bytesB: Long? = null
        return try {
            val nvA = pr.nvA ?: throw FalhaGravacao("sem_nv21_${pr.idA}")
            val nvB = pr.nvB ?: throw FalhaGravacao("sem_nv21_${pr.idB}")
            bytesA = gravaJpeg(nvA, pr.w, pr.h, File(pasta, "par_${pr.idA}.jpg"), p?.sensores?.get(pr.idA)?.orientacao)
            bytesB = gravaJpeg(nvB, pr.w, pr.h, File(pasta, "par_${pr.idB}.jpg"), p?.sensores?.get(pr.idB)?.orientacao)
            gravaY(nvA, pr.w, pr.h, File(pasta, "par_${pr.idA}.y"))
            gravaY(nvB, pr.w, pr.h, File(pasta, "par_${pr.idB}.y"))
            val ta = pr.metaA?.ts; val tb = pr.metaB?.ts
            val ea = pr.metaA?.exp; val eb = pr.metaB?.exp
            val pv = pr.prova
            val j = JSONObject()
                .poe("rodada", r.id).poe("versao", BuildConfig.VERSION_NAME).poe("versao_teste", VERSAO_TESTE).poe("android", Build.VERSION.SDK_INT)
                .poe("aparelho", "${Build.MANUFACTURER} ${Build.MODEL}").poe("logica", p?.logica).poe("ids", listOf(pr.idA, pr.idB))
                .poe("config", pr.config).poe("larg", pr.w).poe("alt", pr.h).poe("resultado", res.resultado)
                .poe("simultaneo", res.resultado == "simultaneo" || res.resultado == "simultaneo_nao_provado")
                .poe("sync", p?.sync).poe("ts_fonte", p?.sensores?.get(pr.idA)?.tsFonte).poe("ts_casou", pr.casou).poe("meta_casou", pr.casouMeta)
                .poe("delta_ns", if (ta != null && tb != null) ta - tb else null)
                .poe("meio_exp_ns", if (ta != null && tb != null && ea != null && eb != null) (ta + ea / 2) - (tb + eb / 2) else null)
                .poe("carimbo", res.carimbo).poe("base_mm", p?.baseMm)
                .poe("prova", pv?.tipo).poe("escala_medida", pv?.escala).poe("escala_esperada", pv?.esperada).poe("ncc", pv?.ncc).poe("mad", pv?.mad)
                .poe("af_estado", pr.af).poe("ae_estado", pr.ae)
                .poe("como_ler_y", "np.fromfile('par_<id>.y', np.uint8).reshape(alt, larg); pixels na orientação do sensor")
            j.put("sensores", JSONObject().put(pr.idA, sensorJson(p, pr.idA, pr.metaA, pr.tsImgA, "par")).put(pr.idB, sensorJson(p, pr.idB, pr.metaB, pr.tsImgB, "par")))
            val txt = try { j.toString(2) } catch (e: Exception) { throw FalhaGravacao("json_invalido") }
            File(pasta, "par.json").writeText(txt)
            // consistência antes de mover: nada vazio, e cada Y com exatamente larg x alt bytes
            for (n in listOf("par_${pr.idA}.jpg", "par_${pr.idB}.jpg", "par.json")) if (File(pasta, n).length() <= 0L) throw FalhaGravacao("vazio")
            for (id in listOf(pr.idA, pr.idB)) if (File(pasta, "par_$id.y").length() != pr.w.toLong() * pr.h) throw FalhaGravacao("y_incompleto")
            Gravado(true, null, bytesA, bytesB)
        } catch (e: Exception) {
            Gravado(false, if (e is FalhaGravacao) e.codigo else e.javaClass.simpleName, bytesA, bytesB)
        } finally {
            pr.nvA = null; pr.nvB = null   // 2 x 3 MB não ficam presos no estado da tela
        }
    }

    private fun emitirPar(r: Rodada, res: Resultado, pr: ParDados, g: Gravado?, motivoPasta: String?, t0: Long) {
        val gravou = g?.ok == true && motivoPasta == null
        val motivo = g?.motivo ?: motivoPasta
        val ta = pr.metaA?.ts; val tb = pr.metaB?.ts
        val ea = pr.metaA?.exp; val eb = pr.metaB?.exp
        val pv = pr.prova
        ev("dois_par", linkedMapOf(
            "rodada" to r.id, "simultaneo" to (res.resultado == "simultaneo"), "config" to pr.config, "larg" to pr.w, "alt" to pr.h,
            "ts_casou" to pr.casou, "meta" to pr.casouMeta,
            "delta_par_us" to if (ta != null && tb != null) (ta - tb) / 1000.0 else null,
            "meio_exp_par_us" to if (ta != null && tb != null && ea != null && eb != null) ((ta + ea / 2) - (tb + eb / 2)) / 1000.0 else null,
            "exp_${pr.idA}_ns" to ea, "exp_${pr.idB}_ns" to eb, "iso_${pr.idA}" to pr.metaA?.iso, "iso_${pr.idB}" to pr.metaB?.iso,
            "foco_${pr.idA}" to pr.metaA?.foco, "foco_${pr.idB}" to pr.metaB?.foco, "af_estado" to pr.af, "ae_estado" to pr.ae,
            "mad_y" to pv?.mad, "escala_medida" to pv?.escala, "escala_esperada" to pv?.esperada, "ncc" to pv?.ncc, "prova" to pv?.tipo,
            "bytes_jpg_${pr.idA}" to g?.bytesA, "bytes_jpg_${pr.idB}" to g?.bytesB, "ms_copia" to pr.msCopia,
            "gravou" to gravou, "motivo" to motivo, "ms" to (SystemClock.elapsedRealtime() - t0)))
        if (!gravou) ev("erro", linkedMapOf("onde" to "dois_sensores", "rodada" to r.id, "etapa" to "gravar", "motivo" to motivo))
    }

    private fun escreverSeq(r: Rodada, res: Resultado, pasta: File): Gravado {
        val p = res.portao
        return try {
            val lista = JSONArray()
            for (s in res.seq) {
                val nv = s.nv
                if (s.ok && nv != null) {
                    gravaJpeg(nv, s.w, s.h, File(pasta, "seq_${s.id}.jpg"), p?.sensores?.get(s.id)?.orientacao)
                    gravaY(nv, s.w, s.h, File(pasta, "seq_${s.id}.y"))
                    if (File(pasta, "seq_${s.id}.jpg").length() <= 0L || File(pasta, "seq_${s.id}.y").length() != s.w.toLong() * s.h) throw FalhaGravacao("incompleto")
                }
                lista.put(sensorJson(p, s.id, s.meta, s.tsImg, "seq").poe("ok", s.ok).poe("motivo", s.motivo).poe("larg", s.w).poe("alt", s.h)
                    .poe("af_estado", s.af).poe("ae_estado", s.ae).poe("intervalo_ms", s.intervaloMs))
            }
            val j = JSONObject().poe("rodada", r.id).poe("versao", BuildConfig.VERSION_NAME).poe("versao_teste", VERSAO_TESTE)
                .poe("simultaneo", false).poe("rotulo", "$ROTULO_SEQ. $AVISO_SEQ").poe("sequencial", res.sequencial)
                .poe("intervalo_ms", res.seq.lastOrNull()?.intervaloMs).poe("base_mm", p?.baseMm)
            j.put("quadros", lista)
            val txt = try { j.toString(2) } catch (e: Exception) { throw FalhaGravacao("json_invalido") }
            File(pasta, "seq.json").writeText(txt)
            Gravado(true, null)
        } catch (e: Exception) {
            Gravado(false, if (e is FalhaGravacao) e.codigo else e.javaClass.simpleName)
        } finally {
            for (s in res.seq) s.nv = null
        }
    }

    private fun emitirSeq(r: Rodada, res: Resultado, g: Gravado?, motivoPasta: String?, t0: Long) {
        val gravou = g?.ok == true && motivoPasta == null
        val motivo = g?.motivo ?: motivoPasta
        ev("dois_par", linkedMapOf("rodada" to r.id, "simultaneo" to false, "config" to "sequencial", "sequencial" to res.sequencial,
            "intervalo_ms" to res.seq.lastOrNull()?.intervaloMs, "gravou" to gravou, "motivo" to motivo, "ms" to (SystemClock.elapsedRealtime() - t0)))
        if (!gravou) ev("erro", linkedMapOf("onde" to "dois_sensores", "rodada" to r.id, "etapa" to "gravar_seq", "motivo" to motivo))
    }

    /** Miniatura para o diálogo: decodificada no aparelho, ~320 px, girada pelo EXIF. */
    class Miniatura(val rotulo: String, val bitmap: Bitmap, val arquivo: File)

    /**
     * O que sai do aparelho num "Compartilhar este par": a mesma lista fechada que a revisão declara é a que vai no
     * envio (revisão do Astra, 02/10: os .y também são imagem e precisam aparecer na declaração).
     */
    class Pacote internal constructor(
        val pasta: File, val rodada: String, val quando: String, val sequencial: Boolean, val rotuloCaptura: String,
        val miniaturas: List<Miniatura>, val anexos: List<File>, private val esperadas: Int
    ) {
        val bytes: Long = anexos.sumOf { it.length() }

        /** Só com as prévias carregadas: o dono vê exatamente o que vai sair antes de poder tocar em compartilhar. */
        fun pronto(): Boolean = esperadas > 0 && miniaturas.size >= esperadas

        /** Alguma prévia não abriu: o compartilhamento fica desligado e a tela diz por quê. */
        fun previaFalhou(): Boolean = esperadas > 0 && miniaturas.size < esperadas

        /** A frase "Serão compartilhados: ..." vem da lista real de anexos; mudou a lista, mudou a frase. */
        fun descricao(): String {
            val jpg = anexos.count { it.name.endsWith(".jpg") }
            val y = anexos.count { it.name.endsWith(".y") }
            val partes = ArrayList<String>()
            if (jpg > 0) partes += if (jpg == 1) "1 foto JPG" else "$jpg fotos JPG"
            if (y > 0) partes += if (y == 1) "1 arquivo de imagem Y" else "$y arquivos de imagem Y"
            if (anexos.any { it.name.endsWith(".json") }) partes += "metadados JSON"
            if (anexos.any { it.name == "resultado.txt" }) partes += "resultado TXT"
            val lista = if (partes.size <= 1) partes.joinToString("") else partes.dropLast(1).joinToString(", ") + " e " + partes.last()
            return "Serão compartilhados: $lista. Total: %.1f MB.".format(bytes / 1048576.0)
        }

        /**
         * Os nomes exatos dos anexos, com o tamanho de cada um, para o dono conferir contra a frase acima. Calculada na
         * criação do pacote (em IO): a tela só lê o texto, sem tocar em disco a cada recomposição.
         */
        val listaExata: String = anexos.joinToString(", ") { a ->
            val b = a.length()
            a.name + " (" + (if (b >= 1048576L) "%.1f MB".format(b / 1048576.0) else "%d KB".format(maxOf(1L, b / 1024))) + ")"
        }
    }

    private val NOME_ANEXO = Regex("^(par|seq)_[A-Za-z0-9]+\\.(jpg|y)$")

    /**
     * Lista fechada de anexos de uma rodada (S6): a pasta tem de ser filha direta de files/estereo pelo caminho CANÔNICO
     * (atalho simbólico não passa) e nunca uma temporária; cada arquivo tem de ser regular, estar nessa pasta também pelo
     * caminho canônico e ter um dos nomes que o teste grava. Nada de listar a pasta e mandar tudo.
     */
    private fun anexos(ctx: Context, pasta: File): List<File>? {
        val raizC = raiz(ctx).canonicalFile
        val p = pasta.canonicalFile
        if (p.parentFile != raizC || p.name.startsWith(TMP) || !p.isDirectory) return null
        val arquivos = p.listFiles()?.filter { it.isFile } ?: return null
        val prefixo = if (arquivos.any { it.name == "par.json" }) "par" else "seq"
        return arquivos.filter { a ->
            val nome = a.name
            val nomeOk = (NOME_ANEXO.matches(nome) && nome.startsWith(prefixo + "_")) || nome == "$prefixo.json" || nome == "resultado.txt"
            nomeOk && a.canonicalFile == File(p, nome)
        }.sortedBy { it.name }
    }

    /** Pacote de uma pasta de rodada, com as miniaturas decodificadas. Rodar em IO. */
    fun pacote(ctx: Context, pasta: File): Pacote? = try {
        val lista = anexos(ctx, pasta)
        val temPar = lista?.any { it.name == "par.json" } == true
        val jpgs = lista?.filter { it.name.endsWith(".jpg") }.orEmpty()
        if (lista == null || jpgs.isEmpty()) {
            ev("erro", linkedMapOf("onde" to "dois_compartilhar", "acao" to "pacote", "motivo" to (if (lista == null) "pasta_invalida" else "sem_imagens")))
            null
        } else {
            val nome = pasta.name
            val quando = try {
                SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(nome.substringBeforeLast('_')) ?: Date(pasta.lastModified()))
            } catch (e: Exception) { "?" }
            val resultado = if (temPar) try { JSONObject(File(pasta, "par.json").readText()).optString("resultado", "") } catch (e: Exception) { "" } else ""
            val rotulo = when {
                !temPar -> "Captura sequencial — uma imagem depois da outra"
                resultado == "simultaneo_nao_provado" -> "Captura na mesma requisição. Origem em sensores distintos não comprovada."
                else -> "Captura na mesma requisição"
            }
            val minis = jpgs.mapNotNull { arq ->
                decodifica(arq, 320)?.let { Miniatura("Sensor " + arq.name.substringAfter('_').substringBefore('.'), it, arq) }
            }
            Pacote(pasta, nome.substringAfterLast('_'), quando, !temPar, rotulo, minis, lista, jpgs.size)
        }
    } catch (e: Exception) {
        ev("erro", linkedMapOf("onde" to "dois_compartilhar", "acao" to "pacote", "classe" to e.javaClass.simpleName))
        null
    }

    /** Pacote DESTA rodada (nunca de um par antigo). Rodar em IO. */
    fun pacote(ctx: Context, res: Resultado): Pacote? {
        val pasta = res.pasta ?: return null
        return if (res.parSalvo || res.seqSalvo) pacote(ctx, pasta) else null
    }

    /** Imagem maior para o toque de ampliar (~1600 px). Rodar em IO. */
    fun ampliar(arq: File): Bitmap? = decodifica(arq, 1600)

    private fun decodifica(arq: File, alvo: Int): Bitmap? {
        if (!arq.exists()) return null
        // o bitmap intermediário (decodificado, ainda sem girar) é reciclado em qualquer saída, erro de leitura do EXIF ou da
        // rotação inclusive; o que sai da função nunca é reciclado aqui
        var intermediario: Bitmap? = null
        var devolvido: Bitmap? = null
        try {
            // inJustDecodeBounds devolve null por desenho: só os campos de medida importam aqui
            val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(arq.absolutePath, medidas)
            var amostra = 1
            while (max(medidas.outWidth, medidas.outHeight) / (amostra * 2) >= alvo) amostra *= 2
            val bmp = BitmapFactory.decodeFile(arq.absolutePath, BitmapFactory.Options().apply { inSampleSize = amostra }) ?: return null
            intermediario = bmp
            val graus = when (ExifInterface(arq.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
            devolvido = if (graus == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(graus) }, true)
            return devolvido
        } catch (e: Exception) {
            return null
        } finally {
            val i = intermediario
            if (i != null && i !== devolvido) i.recycle()
        }
    }

    // ------------------------------------------------------------------ compartilhar (só por toque do dono)

    fun compartilharTexto(ctx: Context, texto: String, titulo: String) {
        val envio = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, texto) }
        ctx.startActivity(Intent.createChooser(envio, titulo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Pacote pelo FileProvider do app, só por toque do dono e depois das prévias. No momento do envio a lista fechada é
     * refeita (anexos(): pasta e arquivos pelo caminho canônico dentro de estereo/<rodada>/) e tem de ser a MESMA que a
     * revisão mostrou; arquivo sumido ou lista diferente vira erro com motivo. Concessão só de leitura, nas URIs da lista.
     */
    fun compartilharPar(ctx: Context, pc: Pacote): Boolean {
        try {
            val agora = anexos(ctx, pc.pasta)
            val nomes = pc.anexos.map { it.name }
            val completo = if (pc.sequencial) "seq.json" in nomes && nomes.any { it.startsWith("seq_") && it.endsWith(".jpg") }
                else "par.json" in nomes && nomes.count { it.startsWith("par_") && it.endsWith(".jpg") } >= 2 && nomes.count { it.startsWith("par_") && it.endsWith(".y") } >= 2
            if (agora == null || !completo || agora.any { !it.exists() } || agora.map { it.name } != nomes) {
                ev("erro", linkedMapOf("onde" to "dois_compartilhar", "rodada" to pc.rodada,
                    "motivo" to (if (agora == null || agora.any { !it.exists() } || !completo) "arquivo_sumiu" else "lista_mudou"), "arquivos" to nomes.size))
                Toast.makeText(ctx, "Os arquivos do par mudaram ou sumiram; abra a revisão de novo ou rode o teste.", Toast.LENGTH_LONG).show()
                return false
            }
            val uris = ArrayList<Uri>(agora.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".arquivos", it) })
            val clip = ClipData.newUri(ctx.contentResolver, "par", uris[0])
            for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
            val envio = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"   // .jpg, .y e .json vão juntos
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(envio, "Par dos dois sensores").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            ev("dois_compartilhar", linkedMapOf("rodada" to pc.rodada, "arquivos" to nomes.size, "bytes" to pc.bytes, "sequencial" to pc.sequencial))
            return true
        } catch (e: Exception) {
            ev("erro", linkedMapOf("onde" to "dois_compartilhar", "rodada" to pc.rodada, "motivo" to "excecao", "classe" to e.javaClass.simpleName))
            Toast.makeText(ctx, "Não consegui compartilhar o par: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return false
        }
    }
}
