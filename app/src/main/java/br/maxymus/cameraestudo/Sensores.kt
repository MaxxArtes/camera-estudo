package br.maxymus.cameraestudo

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Range
import android.util.Size
import android.util.SizeF
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
 *  3. `getConcurrentCameraIds` — os CONJUNTOS que o hardware aceita abrir ao mesmo tempo. Quase sempre vem só
 *     {frontal, traseira}, porque as traseiras dividem a trilha de processamento. Se vier vazio, a resposta
 *     para a pergunta é não, e não há código que contorne.
 *
 * Não usa nada além de permissão de câmera, que o app já tem, e não precisa de depuração ligada — o dono
 * mantém a depuração desligada porque gov.br e banco recusam abrir com ela ativa.
 */
object Sensores {

    private fun equivalente35(focal: Float, tam: SizeF?): Int? {
        if (tam == null || tam.width <= 0f || tam.height <= 0f) return null
        val diagonal = sqrt(tam.width * tam.width + tam.height * tam.height)
        if (diagonal <= 0f) return null
        return (focal * 43.27f / diagonal).roundToInt()
    }

    private fun descreve(cm: CameraManager, id: String, recuo: String): String {
        val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull()
            ?: return "$recuo$id: não consegui ler as características"
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
        if (fisicas.isNotEmpty()) {
            sb.append("$recuo   sensores físicos por baixo: ${fisicas.joinToString(", ")}\n")
            for (f in fisicas.sorted()) sb.append(descreve(cm, f, "$recuo      "))
        }
        return sb.toString()
    }

    /** Relatório legível. Bloqueante, mas é leitura de características: custa milissegundos. */
    fun relatorio(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.append("Sensores de ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.SDK_INT}\n\n")
        val ids = runCatching { cm.cameraIdList }.getOrNull() ?: emptyArray()
        sb.append("Câmeras expostas ao app: ${ids.size}\n\n")
        for (id in ids) sb.append(descreve(cm, id, "")).append("\n")

        sb.append("Conjuntos que o hardware aceita abrir AO MESMO TEMPO:\n")
        if (Build.VERSION.SDK_INT < 30) sb.append("   Android antigo demais para perguntar (precisa de 30).\n")
        else {
            val conj = runCatching { cm.concurrentCameraIds }.getOrNull()
            if (conj.isNullOrEmpty()) sb.append("   Nenhum. O aparelho não aceita duas câmeras simultâneas.\n")
            else for (s in conj) sb.append("   { ${s.sorted().joinToString(", ")} }\n")
        }
        return sb.toString()
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

    /** Mesmo caminho do Registro do scanner: texto direto, sem arquivo e sem tela nova. */
    fun compartilhar(ctx: Context) {
        enviar(ctx)
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, relatorio(ctx))
        }
        ctx.startActivity(Intent.createChooser(envio, "Sensores do aparelho").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
