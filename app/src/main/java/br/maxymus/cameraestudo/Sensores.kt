package br.maxymus.cameraestudo

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Range
import android.util.Size
import android.util.SizeF
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Diagnóstico das lentes do aparelho. Existe para responder UMA pergunta do dono com o dado dele, em vez de
 * teoria: "tem dois sensores, dá para usar os dois ao mesmo tempo e juntar?"
 *
 * Quem responde é o aparelho, por três coisas que só ele sabe:
 *
 *  1. `cameraIdList` — o que o sistema expõe. Sensor físico escondido atrás de uma câmera lógica NÃO aparece
 *     aqui, e é justamente o caso das traseiras de celular: uma câmera lógica troca de sensor conforme o zoom.
 *  2. `physicalCameraIds` de cada câmera — os sensores por baixo da lógica. Existindo esta lista, dá para pedir
 *     fluxo de um sensor específico na MESMA sessão (`setPhysicalCameraId`), que é o caminho real para os dois
 *     sensores traseiros juntos.
 *  3. `getConcurrentCameraIds` — os CONJUNTOS de câmeras expostas que o hardware aceita abrir ao mesmo tempo.
 *     Quase sempre vem só {frontal, traseira}, porque as traseiras dividem a trilha de processamento. Vazio aqui
 *     não fecha a pergunta: dois sensores físicos da MESMA câmera lógica são o teste de DoisSensores (0.79).
 *
 * Não usa nada além de permissão de câmera, que o app já tem, e não precisa de depuração ligada — o dono
 * mantém a depuração desligada porque gov.br e banco recusam abrir com ela ativa.
 */
object Sensores {

    /**
     * Texto do relatório que cresce por etapa e pode ser lido de outra thread: se a coleta travar numa chamada ao
     * serviço de câmera, quem espera leva o que já veio (relatório parcial) em vez de nada.
     */
    class Coleta {
        private val texto = StringBuffer()
        @Volatile var etapa: String = "inicio"
        /** Completa quando o relatório de texto termina; a telemetria numérica vem depois e não segura o relatório. */
        val pronta = CompletableDeferred<Unit>()
        fun add(s: String) { texto.append(s) }
        fun texto(): String = texto.toString()
    }

    private fun equivalente35(focal: Float, tam: SizeF?): Int? {
        if (tam == null || tam.width <= 0f || tam.height <= 0f) return null
        val diagonal = sqrt(tam.width * tam.width + tam.height * tam.height)
        if (diagonal <= 0f) return null
        return (focal * 43.27f / diagonal).roundToInt()
    }

    /** Descreve uma câmera e, depois, cada sensor físico dela. `saida` recebe um bloco por câmera, já pronto. */
    private fun descreve(cm: CameraManager, id: String, recuo: String, saida: (String) -> Unit) {
        val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull()
        if (c == null) { saida("$recuo$id: não consegui ler as características\n"); return }
        val lado = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "frontal"
            CameraCharacteristics.LENS_FACING_BACK -> "traseira"
            else -> "externa"
        }
        val tam = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val px: Size? = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val focais = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
        val mpix = px?.let { (it.width.toLong() * it.height / 1_000_000.0) }
        val zoom: Range<Float>? = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val logica = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val fisicas = if (Build.VERSION.SDK_INT >= 28) runCatching { c.physicalCameraIds }.getOrNull().orEmpty() else emptySet()
        val foco = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

        val sb = StringBuilder()
        sb.append("$recuo$id  $lado")
        if (logica) sb.append("  [câmera lógica]")
        sb.append("\n")
        val listaFocais = focais.joinToString(", ") { f ->
            val e = equivalente35(f, tam)
            if (e != null) "%.1f mm (~%d mm em 35)".format(f, e) else "%.1f mm".format(f)
        }
        if (listaFocais.isNotBlank()) sb.append("$recuo   focal: $listaFocais\n")
        if (px != null) sb.append("$recuo   matriz: ${px.width} x ${px.height}" + (mpix?.let { "  (%.1f MP)".format(it) } ?: "") + "\n")
        if (tam != null) sb.append("$recuo   sensor: %.2f x %.2f mm\n".format(tam.width, tam.height))
        if (zoom != null) sb.append("$recuo   zoom: %.1fx a %.1fx\n".format(zoom.lower, zoom.upper))
        if (foco != null && foco > 0f) sb.append("$recuo   foco mínimo: %.0f cm\n".format(100f / foco))
        if (fisicas.isNotEmpty()) sb.append("$recuo   sensores físicos por baixo: ${fisicas.joinToString(", ")}\n")
        saida(sb.toString())
        for (f in fisicas.sorted()) descreve(cm, f, "$recuo      ", saida)
    }

    /**
     * Linha da consulta de concorrência: diz só o que getConcurrentCameraIds informa. Ela fala de câmeras EXPOSTAS (ids)
     * abertas juntas; não responde sobre os sensores físicos de uma mesma câmera lógica, que é o teste de dois sensores.
     * Falha da consulta e lista vazia são coisas diferentes e saem com palavras diferentes.
     */
    private fun linhaConcorrentes(cm: CameraManager): String {
        val cabecalho = "Conjuntos que o sistema aceita abrir ao mesmo tempo (getConcurrentCameraIds): "
        if (Build.VERSION.SDK_INT < 30) return cabecalho + "consulta indisponível (precisa do Android 11)\n"
        val consulta = runCatching { cm.concurrentCameraIds }
        val conjuntos = consulta.getOrNull() ?: return cabecalho + "a consulta falhou (${consulta.exceptionOrNull()?.javaClass?.simpleName ?: "?"})\n"
        if (conjuntos.isEmpty()) return cabecalho + "nenhum conjunto informado\n"
        return cabecalho + conjuntos.joinToString("; ") { "{ ${it.sorted().joinToString(", ")} }" } + "\n"
    }

    /**
     * Relatório legível, só texto. Bloqueante (consulta o serviço de câmera), por isso quem chama roda fora da Main; cada
     * etapa entra em `coleta` assim que fica pronta, e uma coleta interrompida vale como relatório parcial. O portão do
     * teste de dois sensores chega já calculado (com prazo próprio de 5 s); se ele não respondeu, sai sem a seção.
     */
    fun relatorio(ctx: Context, portao: DoisSensores.Portao? = null, prazoPortao: Boolean = false, coleta: Coleta = Coleta()): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        coleta.add("Somente texto, sem imagens.\n")
        coleta.add("Sensores de ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}\n\n")
        coleta.etapa = "lista"
        val ids = runCatching { cm.cameraIdList }.getOrNull() ?: emptyArray()
        coleta.add("Câmeras expostas ao app: ${ids.size}\n\n")
        for (id in ids) {
            coleta.etapa = "camera:$id"
            descreve(cm, id, "") { coleta.add(it) }
            coleta.add("\n")
        }
        coleta.etapa = "concorrentes"
        coleta.add(linhaConcorrentes(cm))
        coleta.add("\n")
        when {
            portao != null -> coleta.add(portao.texto())
            prazoPortao -> coleta.add("Dois sensores ao mesmo tempo: a pré-checagem não respondeu em 5 s.\n")
        }
        DoisSensores.ultimoResumo(ctx)?.let { coleta.add("\nÚltimo teste: $it\n") }
        coleta.etapa = "fim"
        return coleta.texto()
    }

    /** O mesmo dado em números, para a telemetria responder sem depender de eu ver a tela dele. */
    fun enviar(ctx: Context) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { cm.cameraIdList }.getOrNull() ?: emptyArray()
        var traseiras = 0; var frontais = 0; var logicas = 0
        val fisicas = HashSet<String>()
        val equivalentes = ArrayList<String>()
        for (id in ids) {
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> traseiras++
                CameraCharacteristics.LENS_FACING_FRONT -> frontais++
            }
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) logicas++
            if (Build.VERSION.SDK_INT >= 28) runCatching { c.physicalCameraIds }.getOrNull()?.let { fisicas.addAll(it) }
            val tam = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.forEach { f ->
                equivalente35(f, tam)?.let { equivalentes += "$id:$it" }
            }
        }
        val conj = if (Build.VERSION.SDK_INT >= 30) runCatching { cm.concurrentCameraIds }.getOrNull() else null
        Telemetria.evento("sensores", mapOf(
            "expostas" to ids.size, "traseiras" to traseiras, "frontais" to frontais,
            "logicas" to logicas, "fisicas" to fisicas.size,
            "fisicas_ids" to fisicas.sorted().joinToString(","),
            "equiv35" to equivalentes.joinToString(","),
            "concorrentes" to (conj?.size ?: -1),
            "conjuntos" to (conj?.joinToString(" | ") { it.sorted().joinToString(",") } ?: "-")))
    }

    private var emCurso: Pair<Coleta, Deferred<Unit>>? = null

    /**
     * Uma coleta pendente por vez, reaproveitável: com uma ainda presa no serviço de câmera, quem chega leva a mesma em
     * vez de prender outra thread (como o portão do teste). A telemetria numérica roda DEPOIS do texto e na mesma
     * tarefa, para um travamento dela não atrasar o relatório.
     */
    private fun coletar(ctx: Context, portao: DoisSensores.Portao?, prazoPortao: Boolean): Coleta {
        val app = ctx.applicationContext
        synchronized(this) {
            emCurso?.let { (c, tarefa) -> if (tarefa.isActive) return c }
            val c = Coleta()
            val tarefa = DoisSensores.emFundo {
                try { relatorio(app, portao, prazoPortao, c) } catch (e: Exception) { c.add("\n[a coleta falhou: ${e.javaClass.simpleName}]\n") } finally { c.pronta.complete(Unit) }
                try { enviar(app) } catch (e: Exception) { }
            }
            emCurso = c to tarefa
            return c
        }
    }

    /**
     * Compartilha o relatório de texto (sem imagens) pelo chooser. A coleta roda fora da Main e a espera tem prazo de
     * 5 s; vencido o prazo, o relatório sai parcial, dizendo em que etapa a coleta parou, em vez de falhar. `extra` é o
     * texto do teste (resultado.txt), que se junta ao relatório. Devolve false se o relatório saiu parcial.
     * Chamar da Main: o chooser é aberto aqui.
     */
    suspend fun compartilhar(ctx: Context, portao: DoisSensores.Portao?, prazoPortao: Boolean, extra: String?, titulo: String): Boolean {
        val coleta = coletar(ctx, portao, prazoPortao)
        val completo = withTimeoutOrNull(DoisSensores.PRAZO_PORTAO_MS) { coleta.pronta.await(); true } ?: false
        var texto = coleta.texto()
        if (!completo) {
            texto += "\n[Relatório parcial: a coleta parou na etapa ${coleta.etapa} depois de ${DoisSensores.PRAZO_PORTAO_MS / 1000} s. " +
                "O que viria depois dessa etapa não foi lido.]\n"
            DoisSensores.ev("erro", linkedMapOf("onde" to "sensores", "acao" to "relatorio", "motivo" to "relatorio_parcial", "etapa" to coleta.etapa))
        }
        if (!extra.isNullOrEmpty()) texto += "\n$extra"
        DoisSensores.compartilharTexto(ctx, texto, titulo)
        return completo
    }
}
