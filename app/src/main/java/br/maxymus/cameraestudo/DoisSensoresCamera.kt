package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Devolvido por Execucao.repetir() quando o cancelamento barrou a chamada: não é falha da câmera nem do app. */
private const val CANCELADO = "cancelado"

/** Lista de Long que cresce sem caixa (carimbos de imagem e de chegada). */
internal class ListaLong(cap: Int = 64) {
    private var a = LongArray(cap)
    var n = 0
        private set
    fun add(v: Long) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    fun toArray(): LongArray = a.copyOf(n)
    fun ultimos(k: Int): LongArray = a.copyOfRange(max(0, n - k), n)
}

/** Um ImageReader com o que se mede dele. Estado só na HandlerThread. */
internal class Leitor(val papel: String, val leitor: ImageReader, val fisico: Boolean) {
    val superficie: Surface = leitor.surface
    var ativo = true
    var imagens = 0
    var perdidos = 0
    var duranteCopia = 0
    var erros = 0
    var ultimaChegadaMs = 0L
    private val mapaTs = HashMap<String, ListaLong>()
    private val mapaChegada = HashMap<String, ListaLong>()
    // assinaturas 80x60 da fase dupla: média, desvio e quantas vieram iguais à anterior (quadro congelado)
    var nAssin = 0
    var iguaisSeguidos = 0
    private var somaMedia = 0.0
    private var somaDesvio = 0.0
    private var anterior: ByteArray? = null

    fun registra(fase: String, ts: Long, chegadaNs: Long) {
        mapaTs.getOrPut(fase) { ListaLong() }.add(ts)
        mapaChegada.getOrPut(fase) { ListaLong() }.add(chegadaNs)
        ultimaChegadaMs = chegadaNs / 1_000_000L
    }

    fun ts(fase: String): LongArray = mapaTs[fase]?.toArray() ?: LongArray(0)
    fun chegadas(fase: String): LongArray = mapaChegada[fase]?.toArray() ?: LongArray(0)
    fun n(fase: String): Int = mapaTs[fase]?.n ?: 0

    fun anotaAssinatura(a: ByteArray) {
        val (media, desvio) = Pixels.estatistica(a)
        nAssin++; somaMedia += media; somaDesvio += desvio
        val ant = anterior
        if (ant != null && ant.contentEquals(a)) iguaisSeguidos++
        anterior = a
    }

    fun fracIguais(): Double = if (nAssin <= 1) 0.0 else iguaisSeguidos.toDouble() / (nAssin - 1)
    fun mediaY(): Double? = if (nAssin == 0) null else somaMedia / nAssin
    fun desvioY(): Double? = if (nAssin == 0) null else somaDesvio / nAssin

    fun fechar() { ativo = false; try { leitor.close() } catch (e: Exception) { } }
}

/** Um resultado da fase dupla: carimbo lógico e o metadado de cada físico. */
internal class Quadro(val frame: Long, val tsLogico: Long, val ae: Int?, val af: Int?, val ativo: String?,
                      val a: DoisSensores.MetaFisica?, val b: DoisSensores.MetaFisica?)

/**
 * Contas de pixel. Só números saem do aparelho: médias, desvios, MAD, escala e NCC.
 * Leituras do plano Y com get absoluto (não mexe na posição do buffer, que é da câmera).
 */
internal object Pixels {
    const val AW = 80
    const val AH = 60
    const val MW = 160
    const val MH = 120

    /** Grade 80x60 do Y: 4800 leituras, barata o bastante para todo quadro da fase dupla. */
    fun assinatura(img: Image): ByteArray {
        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
        val w = img.width; val h = img.height
        val out = ByteArray(AW * AH)
        for (gy in 0 until AH) {
            val base = (((gy * 2 + 1) * h) / (AH * 2)) * rs
            for (gx in 0 until AW) out[gy * AW + gx] = buf.get(base + (((gx * 2 + 1) * w) / (AW * 2)) * ps)
        }
        return out
    }

    /** Miniatura Y 160x120 (média de 4 amostras por célula), usada no MAD e na escala por correlação. */
    fun miniatura(img: Image): ByteArray {
        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
        val w = img.width; val h = img.height
        val out = ByteArray(MW * MH)
        for (my in 0 until MH) {
            val y0 = ((my * 4 + 1) * h) / (MH * 4) * rs; val y1 = ((my * 4 + 3) * h) / (MH * 4) * rs
            for (mx in 0 until MW) {
                val x0 = ((mx * 4 + 1) * w) / (MW * 4) * ps; val x1 = ((mx * 4 + 3) * w) / (MW * 4) * ps
                val s = (buf.get(y0 + x0).toInt() and 255) + (buf.get(y0 + x1).toInt() and 255) +
                    (buf.get(y1 + x0).toInt() and 255) + (buf.get(y1 + x1).toInt() and 255)
                out[my * MW + mx] = (s / 4).toByte()
            }
        }
        return out
    }

    /**
     * YUV_420_888 -> NV21 copiando por linha (get(dst, off, len)), não byte a byte: 1920x1080 sai em poucos ms.
     * A última linha do croma pode ser mais curta que o rowStride, por isso o min com o limit do buffer.
     */
    fun nv21(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h + 2 * (w / 2) * (h / 2))
        val y = img.planes[0]; val yb = y.buffer.duplicate(); val ys = y.rowStride
        for (row in 0 until h) { yb.position(row * ys); yb.get(out, row * w, w) }
        val u = img.planes[1]; val v = img.planes[2]
        val ub = u.buffer.duplicate(); val vb = v.buffer.duplicate()
        val us = u.rowStride; val vs = v.rowStride; val up = u.pixelStride; val vp = v.pixelStride
        val linhaU = ByteArray(us); val linhaV = ByteArray(vs)
        var pos = w * h
        for (row in 0 until h / 2) {
            val offU = row * us; val offV = row * vs
            if (offU >= ub.limit() || offV >= vb.limit()) break
            ub.position(offU); ub.get(linhaU, 0, min(us, ub.limit() - offU))
            vb.position(offV); vb.get(linhaV, 0, min(vs, vb.limit() - offV))
            for (col in 0 until w / 2) { out[pos++] = linhaV[col * vp]; out[pos++] = linhaU[col * up] }
        }
        return out
    }

    fun estatistica(a: ByteArray): Pair<Double, Double> {
        if (a.isEmpty()) return 0.0 to 0.0
        var s = 0.0; var s2 = 0.0
        for (b in a) { val x = (b.toInt() and 255).toDouble(); s += x; s2 += x * x }
        val m = s / a.size
        return m to sqrt(max(0.0, s2 / a.size - m * m))
    }

    fun mad(a: ByteArray, b: ByteArray): Double {
        val n = min(a.size, b.size)
        if (n == 0) return 0.0
        var s = 0L
        for (i in 0 until n) s += abs((a[i].toInt() and 255) - (b[i].toInt() and 255))
        return s.toDouble() / n
    }

    private fun bilinear(t: ByteArray, x: Double, y: Double): Float {
        val xc = x.coerceIn(0.0, (MW - 1).toDouble()); val yc = y.coerceIn(0.0, (MH - 1).toDouble())
        val x0 = xc.toInt(); val y0 = yc.toInt(); val x1 = min(x0 + 1, MW - 1); val y1 = min(y0 + 1, MH - 1)
        val fx = xc - x0; val fy = yc - y0
        fun p(xx: Int, yy: Int) = (t[yy * MW + xx].toInt() and 255).toDouble()
        val top = p(x0, y0) * (1 - fx) + p(x1, y0) * fx
        val baixo = p(x0, y1) * (1 - fx) + p(x1, y1) * fx
        return (top * (1 - fy) + baixo * fy).toFloat()
    }

    private fun ncc(t2: ByteArray, amostra: FloatArray, dx: Int, dy: Int): Double {
        val m = 8
        var n = 0; var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        for (y in m until MH - m) for (x in m until MW - m) {
            val a = (t2[y * MW + x].toInt() and 255).toDouble()
            val b = amostra[(y + dy) * MW + (x + dx)].toDouble()
            n++; sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b
        }
        if (n == 0) return 0.0
        val va = saa / n - (sa / n) * (sa / n); val vb = sbb / n - (sb / n) * (sb / n)
        if (va < 1e-6 || vb < 1e-6) return 0.0
        return (sab / n - (sa / n) * (sb / n)) / sqrt(va * vb)
    }

    /**
     * Escala entre o principal (t2) e o mais aberto (t3): recorte central de t3 com lado/s, reamostrado para o tamanho
     * de t2, s de 0,90 a 1,80 em passos de 0,05 e deslocamento de ±6 px de 2 em 2. Fica o s de maior NCC.
     * Uma imagem mais aberta que a principal não pode ser recorte dela: escala >= 1,3 com NCC >= 0,5 prova sensor diferente.
     */
    fun escala(t2: ByteArray, t3: ByteArray): Pair<Double, Double> {
        var melhorS = 1.0; var melhorNcc = -2.0
        val amostra = FloatArray(MW * MH)
        for (i in 0..18) {
            val s = 0.90 + i * 0.05
            val cw = MW / s; val ch = MH / s
            val x0 = (MW - cw) / 2.0; val y0 = (MH - ch) / 2.0
            for (y in 0 until MH) for (x in 0 until MW) {
                amostra[y * MW + x] = bilinear(t3, x0 + (x + 0.5) * cw / MW - 0.5, y0 + (y + 0.5) * ch / MH - 0.5)
            }
            for (dy in -6..6 step 2) for (dx in -6..6 step 2) {
                val c = ncc(t2, amostra, dx, dy)
                if (c > melhorNcc) { melhorNcc = c; melhorS = s }
            }
        }
        return melhorS to melhorNcc
    }

    /** Em ordem: mesmo buffer/sensor, quadro vazio, quadro congelado; depois a escala. Contradição encerra a config. */
    fun provar(par: DoisSensores.ParDados, fracIguaisA: Double, fracIguaisB: Double, esperada: Double?): DoisSensores.Prova {
        val mad = mad(par.miniA, par.miniB)
        val desvA = estatistica(par.miniA).second; val desvB = estatistica(par.miniB).second
        val fa = par.metaA?.focal; val fb = par.metaB?.focal
        val focaisIguais = fa != null && fb != null && abs(fa - fb) < 0.3f
        val focaisDiferentes = fa != null && fb != null && abs(fa - fb) >= 0.3f
        val contradicao = when {
            mad < 1.5 -> if (focaisIguais) "mesmo_sensor" else "mesmo_buffer"
            desvA < 3 && desvB >= 8 -> "quadro_vazio:${par.idA}"
            desvB < 3 && desvA >= 8 -> "quadro_vazio:${par.idB}"
            fracIguaisA >= 0.5 && fracIguaisB < 0.5 -> "congelado:${par.idA}"
            fracIguaisB >= 0.5 && fracIguaisA < 0.5 -> "congelado:${par.idB}"
            else -> null
        }
        if (contradicao != null) return DoisSensores.Prova(mad, contradicao, null, null, esperada, focaisDiferentes, false)
        val (s, c) = escala(par.miniA, par.miniB)
        return DoisSensores.Prova(mad, null, s, c, esperada, focaisDiferentes, s >= 1.3 && c >= 0.5)
    }
}

/**
 * Consulta sem abrir a câmera (Android 15+). Isolada num object próprio: os construtores OutputConfiguration(int, Size)
 * e SessionConfiguration(int, List) só existem na API 35, e nada aqui roda abaixo dela.
 */
@RequiresApi(35)
internal object Api35 {
    fun versao(c: CameraCharacteristics): Int? = c.get(CameraCharacteristics.INFO_SESSION_CONFIGURATION_QUERY_VERSION)

    /** CameraDeviceSetup recebe SÓ o id lógico; os físicos entram por setPhysicalCameraId nas saídas. */
    fun consulta(cm: CameraManager, logica: String, versao: Int?, tam: Size, comLogico: Boolean, fisicos: List<String>): String {
        if (versao == null || versao <= 34) return "sem_consulta:versao_$versao"
        return try {
            if (!cm.isCameraDeviceSetupSupported(logica)) "sem_consulta:setup_nao_suportado"
            else {
                val saidas = ArrayList<OutputConfiguration>()
                if (comLogico) saidas += OutputConfiguration(ImageFormat.YUV_420_888, tam)
                for (f in fisicos) saidas += OutputConfiguration(ImageFormat.YUV_420_888, tam).also { it.setPhysicalCameraId(f) }
                val ok = cm.getCameraDeviceSetup(logica).isSessionConfigurationSupported(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, saidas))
                if (ok) "sim" else "nao"
            }
        } catch (e: Exception) {
            // IllegalArgumentException aqui pode ser limite da consulta sem Surface: não decide nada. Só a classe (S5).
            "excecao:${e.javaClass.simpleName}"
        }
    }
}

/**
 * A rodada na Camera2. Todo o estado mora na HandlerThread "dois-sensores": rodar() corre num dispatcher dessa thread e
 * todos os callbacks (câmera, sessão, captura, ImageReader) são entregues no mesmo Handler, então não há trava. As
 * esperas são sondagens curtas (delay) com prazo, que liberam a thread para os callbacks e param em deveParar().
 */
@RequiresApi(28)
internal class Execucao(
    ctx: Context,
    private val r: DoisSensores.Rodada,
    private val p: DoisSensores.Portao,
    private val cx: DoisSensores.InfoCameraX,
    private val h: Handler,
    private val aoFase: (String) -> Unit
) {
    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val inicioMs = SystemClock.elapsedRealtime()
    private val logica = p.logica ?: "0"
    private val idA = p.idA ?: "?"
    private val idB = p.idB ?: "?"
    private val executor = Executor { h.post(it) }

    /**
     * openCamera chamado e ainda sem resposta. O fio de callbacks fica vivo enquanto a câmera da rodada não for liberada
     * (DoisSensores.encerrarFio), não por tempo: um onOpened tardio sempre acha a thread para fechar o device na hora.
     */
    @Volatile var abrindoPendente = false
        private set

    private var device: CameraDevice? = null
    private var estado: EstadoCamera? = null
    private var perda: String? = null
    private var perdaCodigo: Int? = null
    private var perdaAposConfigurar = false
    private var perdaForaDoPrimeiroPlano = false
    private var encerrando = false
    private var parando = false
    /** "onde:Classe" da primeira exceção num retorno da câmera. A mensagem fica em excecaoCallbackMsg, só para o arquivo local. */
    private var excecaoCallback: String? = null
    private var excecaoCallbackClasse: String? = null
    private var excecaoCallbackMsg: String? = null
    private var registrouDisp = false
    private val disponivel = HashMap<String, Boolean>()
    private val fisicasIndisponiveis = LinkedHashSet<String>()
    private var tentativaAtual: Tentativa? = null
    private var sessao: CameraCaptureSession? = null
    private val leitores = ArrayList<Leitor>()
    private var fase = "nenhuma"
    private var med: Medicao? = null
    private var seqMed: SeqMedicao? = null

    private fun restanteMs(): Long = DoisSensores.PRAZO_RODADA_MS - (SystemClock.elapsedRealtime() - inicioMs)

    /** Todo callback passa por aqui: exceção vira registro e encerra a rodada como erro do app, nunca crash na thread. */
    private fun guarda(onde: String, bloco: () -> Unit) {
        try { bloco() } catch (t: Throwable) {
            if (excecaoCallback == null) {
                // telemetria: só onde e a classe (S5); a mensagem é texto livre e vai só para o resultado.txt local
                excecaoCallback = "$onde:${t.javaClass.simpleName}"
                excecaoCallbackClasse = t.javaClass.simpleName
                excecaoCallbackMsg = (t.message ?: "").take(160)
            }
        }
    }

    /** Fecha a rodada como erro do app por exceção num retorno da câmera, com a mensagem só no campo local. */
    private fun fimExcecaoCallback(b: DoisSensores.Bruto) {
        b.classe = excecaoCallbackClasse; b.msg = excecaoCallbackMsg
        b.fim("erro_app", "excecao_callback:$excecaoCallback", r.etapa)
    }

    /**
     * Cancelamento (Cancelar, Voltar, app fora da tela) é IRREVERSÍVEL, e esta conferência vem ANTES de cada passo que
     * abre a câmera, cria a sessão, chama setRepeatingRequest, copia imagem ou começa o plano B (S1). true = não dê o
     * passo. O primeiro passo barrado vai no dois_fim (parou_antes_de). ON_START não desfaz nada: o pedido não tem volta.
     */
    private fun cancelou(passo: String): Boolean {
        // resultado já fechado (cão de guarda, cancelamento forçado) vale como cancelamento: a volta tardia da thread não dá passo
        if (r.cancelarPedido == null && !r.fim.get()) return false
        if (r.paradaAntesDe == null) r.paradaAntesDe = passo
        return true
    }

    private fun deveParar(): String? = when {
        excecaoCallback != null -> "excecao_callback"
        r.cancelarPedido != null || r.fim.get() -> "cancelado"
        perda != null -> "perda"
        restanteMs() <= 0 -> "prazo"
        else -> null
    }

    private suspend fun esperar(maxMs: Long, cond: () -> Boolean): Boolean {
        val ate = SystemClock.elapsedRealtime() + min(maxMs, max(0L, restanteMs()))
        while (true) {
            if (cond()) return true
            if (deveParar() != null || SystemClock.elapsedRealtime() >= ate) return cond()
            delay(15)
        }
    }

    /** Espera do caminho de saída: ignora parada e prazo, que é justamente por onde se sai. */
    private suspend fun esperarFechando(maxMs: Long, cond: () -> Boolean): Boolean {
        val ate = SystemClock.elapsedRealtime() + maxMs
        while (!cond() && SystemClock.elapsedRealtime() < ate) delay(15)
        return cond()
    }

    // ------------------------------------------------------------------ callbacks

    private val dispCb = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) = guarda("disponivel") { disponivel[cameraId] = true }
        override fun onCameraUnavailable(cameraId: String) = guarda("indisponivel") { disponivel[cameraId] = false }
        override fun onPhysicalCameraAvailable(cameraId: String, physicalCameraId: String) =
            guarda("fisica_disponivel") { if (cameraId == logica) fisicasIndisponiveis.remove(physicalCameraId) }
        override fun onPhysicalCameraUnavailable(cameraId: String, physicalCameraId: String) =
            guarda("fisica_indisponivel") { if (cameraId == logica) fisicasIndisponiveis.add(physicalCameraId) }
    }

    private inner class EstadoCamera : CameraDevice.StateCallback() {
        var aberta = false
        var fechada = false
        private val contada = AtomicBoolean(false)

        /** Esta abertura deixa de contar como "por fechar", uma vez só: onClosed, ou falha síncrona que não deixa device. */
        fun descontar(via: String) {
            if (contada.compareAndSet(false, true)) DoisSensores.liberou(r, via)
        }

        override fun onOpened(camera: CameraDevice) = guarda("aberta") {
            abrindoPendente = false
            // S3: rodada encerrada (resultado, cancelamento ou fechamento em curso) fecha o device NA HORA e nunca o adota
            if (encerrando || r.fim.get() || r.cancelarPedido != null || estado !== this) {
                DoisSensores.fechando(r, "onopened_tardio")
                try { camera.close() } catch (e: Exception) { }
                return@guarda
            }
            aberta = true
            device = camera
            r.dispositivo = camera   // publicado para o fechamento de emergência (close() direto, fora desta thread)
        }
        override fun onDisconnected(camera: CameraDevice) = guarda("desconectada") {
            abrindoPendente = false
            if (estado === this && !encerrando) registraPerda("desconectada", null)
            if (device === camera) device = null
            DoisSensores.fechando(r, "desconectada")
            try { camera.close() } catch (e: Exception) { }
        }
        override fun onError(camera: CameraDevice, error: Int) = guarda("erro_camera") {
            abrindoPendente = false
            if (estado === this && !encerrando) registraPerda(nomeErroCamera(error), error)
            if (device === camera) device = null
            DoisSensores.fechando(r, "erro_camera")
            try { camera.close() } catch (e: Exception) { }
        }
        override fun onClosed(camera: CameraDevice) = guarda("fechada") {
            fechada = true
            if (r.dispositivo === camera) r.dispositivo = null
            descontar("onclosed")   // única porta para "câmera liberada": o sistema confirmou
        }
    }

    /** Um StateCallback por tentativa: callback de outra tentativa (ou tardio) só fecha a própria sessão e registra. */
    private inner class Tentativa(val nome: String) {
        var configurada = false
        var falhou = false
        var sessao: CameraCaptureSession? = null
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = guarda("sessao_configurada") {
                if (tentativaAtual !== this@Tentativa || encerrando) {
                    try { session.close() } catch (e: Exception) { }
                    tardia(nome, "configurada"); return@guarda
                }
                sessao = session; configurada = true
            }
            override fun onConfigureFailed(session: CameraCaptureSession) = guarda("sessao_falhou") {
                if (tentativaAtual !== this@Tentativa) {
                    try { session.close() } catch (e: Exception) { }
                    tardia(nome, "falhou"); return@guarda
                }
                falhou = true
            }
        }
    }

    private fun tardia(config: String, oQue: String) {
        DoisSensores.ev("dois_sessao", linkedMapOf("rodada" to r.id, "config" to config, "resultado" to "tardia", "motivo" to oQue))
    }

    private val capturaCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) =
            guarda("resultado") { aoResultado(session, request, result) }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) =
            guarda("falha") { aoFalha(session, failure) }
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) =
            guarda("buffer_perdido") { if (!encerrando && !parando && session === sessao) leitores.firstOrNull { it.superficie === target }?.let { it.perdidos++ } }
    }

    private fun registraPerda(nome: String, codigo: Int?) {
        if (perda != null) return
        perda = nome; perdaCodigo = codigo
        perdaAposConfigurar = tentativaAtual?.configurada == true
        perdaForaDoPrimeiroPlano = r.saiuDoPrimeiroPlano != null || !r.emPrimeiroPlano
    }

    /**
     * Só ERROR_CAMERA_DEVICE ou SERVICE depois de onConfigured, com o app em primeiro plano o tempo todo, contam contra o
     * firmware. Perda fora do primeiro plano é interrupção; DISABLED/IN_USE/MAX/desconexão é o sistema tirando a câmera.
     */
    private fun classeDaPerda(): Pair<String, String> {
        val nome = perda ?: "desconhecida"
        if (r.saiuDoPrimeiroPlano != null || perdaForaDoPrimeiroPlano) return "nao_testado" to "interrompido:${r.saiuDoPrimeiroPlano ?: r.etapa}"
        if (!perdaAposConfigurar) return "nao_testado" to "perdeu_camera:$nome"
        return when (perdaCodigo) {
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "aceitou_nao_entregou" to "erro_dispositivo"
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "aceitou_nao_entregou" to "servico_caiu"
            else -> "nao_testado" to "perdeu_camera:$nome"
        }
    }

    private fun desfechoDaPerda(): String = classeDaPerda().let { "${it.first}:${it.second}" }

    // ------------------------------------------------------------------ rodada

    suspend fun rodar(): DoisSensores.Bruto {
        val b = DoisSensores.Bruto()
        try {
            rodarDentro(b)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            b.classe = t.javaClass.simpleName; b.msg = (t.message ?: "").take(160)
            b.fim("erro_app", "excecao:${t.javaClass.simpleName}", r.etapa)
        } finally {
            withContext(NonCancellable) { fecharTudo() }
        }
        if (b.resultado == null) {
            val (res, mot) = DoisSensores.vereditoSemPar(b.registros, p, b.prazo)
            b.fim(res, mot, r.etapa)
        }
        return b
    }

    /** Parada antes de haver config: fecha a rodada pelo motivo (cancelamento, exceção, perda, prazo). */
    private fun parou(b: DoisSensores.Bruto): Boolean {
        when (deveParar() ?: return false) {
            "cancelado" -> { cancelou(r.etapa); fimCancelado(b) }
            "excecao_callback" -> fimExcecaoCallback(b)
            "perda" -> classeDaPerda().let { b.fim(it.first, it.second, r.etapa) }
            "prazo" -> { b.prazo = "prazo_geral:${r.etapa}"; b.fim("nao_testado", "prazo_geral:${r.etapa}", r.etapa) }
        }
        return true
    }

    private fun fimCancelado(b: DoisSensores.Bruto) {
        val (res, mot) = DoisSensores.motivoCancelado(r)
        b.fim(res, mot, r.etapa)
    }

    private suspend fun rodarDentro(b: DoisSensores.Bruto) {
        // 8. a "0" livre: o fechamento do CameraX é assíncrono, e o registro entrega o estado atual de cada câmera
        r.etapa = "esperar_livre"
        cm.registerAvailabilityCallback(dispCb, h)
        registrouDisp = true
        val t0 = SystemClock.elapsedRealtime()
        val livre = esperar(3_000) { disponivel[logica] == true }
        val motivoLivre = when {
            livre -> null
            cx.fechouX == false -> "camerax_nao_fechou"
            cx.outroAppAntes -> "camera_ocupada_por_outro_app"
            else -> "camera_0_ocupada"
        }
        DoisSensores.ev("dois_camerax", linkedMapOf(
            "rodada" to r.id, "acao" to "soltou", "camerax_closed" to cx.fechouX, "camerax_closed_ms" to cx.msFechar,
            "livre" to livre, "livre_ms" to (SystemClock.elapsedRealtime() - t0), "motivo" to motivoLivre,
            "fisicas_indisponiveis" to fisicasIndisponiveis.toList()))
        if (parou(b)) return
        if (!livre) { b.fim("nao_testado", motivoLivre, r.etapa); return }

        // 9. abrir
        r.etapa = "abrir"
        r.tecnico = "câmera lógica $logica"
        aoFase("Abrindo a câmera $logica.")
        DoisSensores.gravarMarca(r, "abrir")
        if (parou(b) || !abrir(b)) return

        // 10. laço das configs, na ordem do portão
        var tentadas = 0
        for (cfg in p.configs) {
            val g = DoisSensores.RegistroCfg(cfg.nome, cfg.tam.width, cfg.tam.height, cfg.tamanhoCts, cfg.fluxos(idA, idB), cfg.pre)
            b.registros += g
            val parada = deveParar()
            when {
                parada == "cancelado" -> { cancelou("criar_sessao"); g.desfecho = "nao_testado:cancelado"; fimCancelado(b) }
                parada == "excecao_callback" -> { g.desfecho = "erro_app:excecao_callback:$excecaoCallback"; b.classe = excecaoCallbackClasse; b.msg = excecaoCallbackMsg }
                parada == "prazo" -> { g.resultado = "sem_tempo"; g.desfecho = "nao_testado:prazo_geral"; b.prazo = "prazo_geral:${r.etapa}" }
                parada == "perda" || device == null -> { g.desfecho = if (perda != null) desfechoDaPerda() else "nao_testado:sem_camera" }
                restanteMs() < 5_000 -> { g.resultado = "sem_tempo"; g.desfecho = "nao_testado:sem_tempo" }
                else -> {
                    val ok = tentarConfig(cfg, g, b, tentadas)
                    if (g.tentou) tentadas++
                    DoisSensores.atualizarParcial(r, p, b)
                    if (ok) break
                }
            }
        }
        when (deveParar()) {
            "cancelado" -> fimCancelado(b)
            "excecao_callback" -> fimExcecaoCallback(b)
            "prazo" -> if (b.prazo == null) b.prazo = "prazo_geral:${r.etapa}"
        }

        // 17. plano B, só sem par válido e com tempo (planoB() confere o cancelamento antes de começar)
        planoB(b)
        if (r.cancelarPedido != null) fimCancelado(b)
        DoisSensores.atualizarParcial(r, p, b)
    }

    @Suppress("MissingPermission")
    private fun pedirAbertura(cb: EstadoCamera): String? = try { cm.openCamera(logica, cb, h); null }
        catch (e: CameraAccessException) { "CameraAccessException:${nomeAcesso(e.reason)}" }
        catch (e: SecurityException) { "SecurityException" }
        catch (e: IllegalArgumentException) { "IllegalArgumentException" }
        catch (e: Exception) { e.javaClass.simpleName }

    /** Registra a abertura como "por fechar" ANTES do openCamera: a câmera só deixa de contar quando o onClosed chegar. */
    private fun iniciarAbertura(): EstadoCamera {
        val cb = EstadoCamera()
        estado = cb
        abrindoPendente = true
        DoisSensores.abriu(r)
        return cb
    }

    /** Abre a lógica com prazo de 3 s. A chamada bloqueia (binder), por isso o tempo vai como ms_bloqueio. */
    private suspend fun abrir(b: DoisSensores.Bruto): Boolean {
        // S1: última conferência do cancelamento imediatamente antes de pedir a câmera ao sistema
        if (cancelou("abrir")) { fimCancelado(b); return false }
        val cb = iniciarAbertura()
        val t0 = SystemClock.elapsedRealtime()
        val falha = pedirAbertura(cb)
        val msBloqueio = SystemClock.elapsedRealtime() - t0
        if (falha != null) {
            abrindoPendente = false
            cb.descontar("falha_abertura")   // o openCamera recusou na hora: não ficou device nenhum por fechar
            b.fim("nao_testado", "abrir:$falha", r.etapa)
            DoisSensores.ev("dois_camerax", linkedMapOf("rodada" to r.id, "acao" to "abrir", "ok" to false, "motivo" to falha, "ms_bloqueio" to msBloqueio))
            return false
        }
        esperar(3_000) { device != null || perda != null }
        val ok = device != null
        DoisSensores.ev("dois_camerax", linkedMapOf("rodada" to r.id, "acao" to "abrir", "ok" to ok, "perda" to perda,
            "ms_bloqueio" to msBloqueio, "ms" to (SystemClock.elapsedRealtime() - t0)))
        if (ok) return true
        if (perda != null) { classeDaPerda().let { b.fim(it.first, it.second, r.etapa) }; return false }
        if (parou(b)) return false
        b.fim("nao_testado", "abrir:prazo", r.etapa)
        return false
    }

    private fun novoLeitor(papel: String, w: Int, hh: Int, maxImagens: Int, fisico: Boolean): Leitor {
        val ir = ImageReader.newInstance(w, hh, ImageFormat.YUV_420_888, maxImagens)
        val l = Leitor(papel, ir, fisico)
        leitores += l
        ir.setOnImageAvailableListener({ guarda("imagem_$papel") { aoImagem(l) } }, h)
        return l
    }

    private fun consultaPos(dev: CameraDevice, sc: SessionConfiguration): String =
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                @Suppress("DEPRECATION")
                val ok = dev.isSessionConfigurationSupported(sc)
                if (ok) "sim" else "nao"
            } catch (e: UnsupportedOperationException) { "sem_consulta:UnsupportedOperationException" }
            catch (e: IllegalArgumentException) { "erro_app:IllegalArgumentException" }
            catch (e: CameraAccessException) { "excecao:CameraAccessException:${nomeAcesso(e.reason)}" }
            catch (e: Exception) { "excecao:${e.javaClass.simpleName}" }
        } else "sem_consulta:android_${Build.VERSION.SDK_INT}"

    private fun decisivo(v: String) = v == "sim" || v == "nao"

    /** Tenta se QUALQUER consulta disse sim; recusa só quando nenhuma disse sim e ao menos uma disse não. */
    private fun decidir(pre: String, pos: String): String = when {
        pos.startsWith("erro_app") -> "nao_tentar_erro_app"
        pos.startsWith("excecao") -> "nao_tentar_excecao"
        pre == "sim" || pos == "sim" -> "tentar"
        pre != "nao" && pos != "nao" -> "sem_consulta"
        else -> "nao_tentar"
    }

    private fun fonte(pre: String, pos: String) = when {
        pre == "nao" && pos == "nao" -> "ambas"
        pre == "nao" -> "pre"
        else -> "pos"
    }

    private fun evSessao(cfg: String, g: DoisSensores.RegistroCfg, t0: Long) {
        g.ms = SystemClock.elapsedRealtime() - t0
        DoisSensores.ev("dois_sessao", linkedMapOf(
            "rodada" to r.id, "config" to cfg, "tamanho_cts" to g.tamanhoCts, "larg" to g.larg, "alt" to g.alt, "fluxos" to g.fluxos,
            "consulta_pre" to g.pre, "consulta_pos" to g.pos, "decisao" to g.decisao, "fonte_recusa" to g.fonteRecusa, "tentou" to g.tentou,
            "resultado" to g.resultado, "motivo" to g.motivo, "desfecho" to g.desfecho, "ms_bloqueio" to g.msBloqueio, "ms" to g.ms))
    }

    private fun evContradicao(cfg: DoisSensores.Cfg, g: DoisSensores.RegistroCfg, oQue: String) {
        DoisSensores.ev("dois_contradicao", linkedMapOf("rodada" to r.id, "config" to cfg.nome, "consulta_pre" to g.pre, "consulta_pos" to g.pos,
            "o_que" to oQue, "larg" to g.larg, "alt" to g.alt))
    }

    /** Uma configuração: leitores novos, consulta com a câmera aberta, decisão, sessão e fases. true = par válido. */
    private suspend fun tentarConfig(cfg: DoisSensores.Cfg, g: DoisSensores.RegistroCfg, b: DoisSensores.Bruto, tentadas: Int): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        r.etapa = "sessao_${cfg.nome}"
        val dev = device ?: run { g.desfecho = "nao_testado:sem_camera"; return false }
        val w = cfg.tam.width; val hh = cfg.tam.height
        r.tecnico = "${cfg.nome} · ${w}x$hh · ${cfg.fluxos(idA, idB)}"
        val leit = try {
            Triple(novoLeitor(idA, w, hh, 4, true), novoLeitor(idB, w, hh, 4, true), if (cfg.comLogico) novoLeitor("logico", w, hh, 3, false) else null)
        } catch (e: Exception) {
            g.resultado = "excecao"; g.motivo = "leitor:${e.javaClass.simpleName}"; g.desfecho = "erro_app:leitor:${e.javaClass.simpleName}"
            evSessao(cfg.nome, g, t0); return false
        }
        val (lA, lB, lL) = leit
        val tentativa = Tentativa(cfg.nome)
        val sc = try {
            val saidas = ArrayList<OutputConfiguration>()
            if (lL != null) saidas += OutputConfiguration(lL.superficie)
            saidas += OutputConfiguration(lA.superficie).also { it.setPhysicalCameraId(idA) }
            saidas += OutputConfiguration(lB.superficie).also { it.setPhysicalCameraId(idB) }
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR, saidas, executor, tentativa.cb)
        } catch (e: Exception) {
            lA.fechar(); lB.fechar(); lL?.fechar()
            g.resultado = "excecao"; g.motivo = "saidas:${e.javaClass.simpleName}"; g.desfecho = "erro_app:saidas:${e.javaClass.simpleName}"
            evSessao(cfg.nome, g, t0); return false
        }
        g.pos = consultaPos(dev, sc)
        val disseSim = cfg.pre == "sim" || g.pos == "sim"
        if (disseSim) b.consultaSim = true
        if (decisivo(cfg.pre) && decisivo(g.pos) && cfg.pre != g.pos) evContradicao(cfg, g, "pre_diferente_de_pos")
        val decisao = decidir(cfg.pre, g.pos)
        g.decisao = decisao
        if (decisao.startsWith("nao_tentar")) {
            // leitores nunca ligados a um fluxo: fecham na hora
            lA.fechar(); lB.fechar(); lL?.fechar()
            g.motivo = g.pos
            g.desfecho = when (decisao) {
                "nao_tentar_erro_app" -> "erro_app:consulta_pos"
                "nao_tentar_excecao" -> if (perda != null) desfechoDaPerda() else "nao_testado:consulta_pos"
                else -> { g.fonteRecusa = fonte(cfg.pre, g.pos); "recusa:${g.fonteRecusa}" }
            }
            evSessao(cfg.nome, g, t0); return false
        }
        // a primeira sessão tentada precisa de 17 s; as seguintes, 38 s (reserva do plano B)
        if (restanteMs() < (if (tentadas == 0) 17_000L else 38_000L)) {
            lA.fechar(); lB.fechar(); lL?.fechar()
            g.resultado = "sem_tempo"; g.desfecho = "nao_testado:sem_tempo"
            evSessao(cfg.nome, g, t0); return false
        }

        aoFase("Preparando a captura.")
        DoisSensores.gravarMarca(r, "sessao:${cfg.nome}")
        // S1: gravar a marca suspende; o cancelamento pode ter chegado nesse intervalo, antes de a sessão existir
        if (cancelou("criar_sessao")) {
            lA.fechar(); lB.fechar(); lL?.fechar()
            g.resultado = "cancelado"; g.desfecho = "nao_testado:cancelado"
            evSessao(cfg.nome, g, t0); return false
        }
        g.tentou = true
        tentativaAtual = tentativa
        val tb = SystemClock.elapsedRealtime()
        val erroCriar: String? = try { dev.createCaptureSession(sc); null }
            catch (e: IllegalArgumentException) { "erro_app:IllegalArgumentException" }
            catch (e: CameraAccessException) { "perda:CameraAccessException:${nomeAcesso(e.reason)}" }
            catch (e: IllegalStateException) { "perda:IllegalStateException" }
            catch (e: Exception) { "erro_app:${e.javaClass.simpleName}" }
        g.msBloqueio = SystemClock.elapsedRealtime() - tb
        if (erroCriar != null) {
            tentativaAtual = null
            g.resultado = "excecao"; g.motivo = erroCriar
            g.desfecho = when {
                erroCriar.startsWith("erro_app") -> "erro_app:sessao:${erroCriar.substringAfter(':')}"
                perda != null -> desfechoDaPerda()
                else -> "nao_testado:sessao:${erroCriar.substringAfter(':')}"
            }
            evSessao(cfg.nome, g, t0); return false
        }
        esperar(3_000) { tentativa.configurada || tentativa.falhou || perda != null }
        when {
            tentativa.configurada -> g.resultado = "configurada"
            tentativa.falhou -> {
                g.resultado = "configure_failed"
                g.desfecho = if (disseSim) "aceitou_nao_entregou:configure_failed" else "recusou_sem_consulta:${cfg.nome}"
                if (disseSim) { evContradicao(cfg, g, "configure_failed"); b.contradicoes += "${cfg.nome}:configure_failed" }
            }
            perda != null -> { g.resultado = "perdeu_camera"; g.desfecho = desfechoDaPerda() }
            else -> {
                tentativaAtual = null   // callback que chegar depois do prazo só fecha a própria sessão
                val parada = deveParar()
                if (parada != null) { g.resultado = "prazo"; g.desfecho = "nao_testado:$parada" }
                else { g.resultado = "prazo"; g.desfecho = if (disseSim) "aceitou_nao_entregou:sessao_sem_resposta" else "nao_testado:sessao_sem_resposta" }
            }
        }
        evSessao(cfg.nome, g, t0)
        val sess = tentativa.sessao
        if (g.resultado != "configurada" || sess == null) return false
        sessao = sess
        return fases(cfg, g, b, dev, sess, lA, lB, lL, disseSim)
    }

    private fun aplicarBase(req: CaptureRequest.Builder, tam: Size) {
        p.fpsAlvo(tam)?.let { req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        // estabilização desligada explicitamente: no X8 Pro, ligada, derrubava o fluxo físico
        req.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (p.oisOffDisp) req.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        // CONTROL_ZOOM_RATIO fica intocado: 0,6x troca o sensor ativo
    }

    private fun repetir(s: CameraCaptureSession, req: CaptureRequest.Builder): String? {
        // S1: a flag de cancelamento é conferida ANTES de chamar setRepeatingRequest
        if (cancelou("repetir")) return CANCELADO
        return try { s.setRepeatingRequest(req.build(), capturaCb, h); null }
        catch (e: CameraAccessException) { "CameraAccessException:${nomeAcesso(e.reason)}" }
        catch (e: IllegalStateException) { "IllegalStateException" }
        catch (e: IllegalArgumentException) { "IllegalArgumentException" }
    }

    private fun falhaRepeticao(g: DoisSensores.RegistroCfg, erro: String) {
        val barrada = erro == CANCELADO
        g.motivo = "repeticao:$erro"
        g.desfecho = when {
            barrada -> "nao_testado:cancelado"
            perda != null -> desfechoDaPerda()
            erro.startsWith("IllegalArgumentException") -> "erro_app:requisicao:$erro"
            else -> "nao_testado:requisicao:$erro"
        }
        // a sessão já tinha saído como "configurada"; sem este evento a queda na requisição só apareceria no dois_fim
        DoisSensores.ev("dois_sessao", linkedMapOf("rodada" to r.id, "config" to g.config, "tamanho_cts" to g.tamanhoCts, "larg" to g.larg, "alt" to g.alt,
            "fluxos" to g.fluxos, "tentou" to true, "resultado" to (if (barrada) "cancelado" else "requisicao_falhou"), "fase" to fase,
            "motivo" to g.motivo, "desfecho" to g.desfecho))
    }

    private fun aeOk(ae: Int?) = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED ||
        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED

    private fun afOk(af: Int?) = af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

    /** Fases da config já configurada: aquece, trava e A1 (só em A, como o CTS), depois a fase dupla. */
    private suspend fun fases(cfg: DoisSensores.Cfg, g: DoisSensores.RegistroCfg, b: DoisSensores.Bruto, dev: CameraDevice, sess: CameraCaptureSession,
                              lA: Leitor, lB: Leitor, lL: Leitor?, disseSim: Boolean): Boolean {
        val m = Medicao(cfg, lL, lA, lB)
        med = m
        parando = false
        val req = try { dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) } catch (e: Exception) {
            falhaRepeticao(g, "criar:${e.javaClass.simpleName}"); return false
        }
        aplicarBase(req, cfg.tam)
        if (lL != null) {
            fase = "aquece"
            req.addTarget(lL.superficie); req.setTag("${cfg.nome}|aquece")
            repetir(sess, req)?.let { falhaRepeticao(g, it); return false }
            esperar(2_000) { aeOk(m.ae) || m.nAquece >= 45 }
            if (p.aeLockDisp == true) {
                fase = "trava"
                req.set(CaptureRequest.CONTROL_AE_LOCK, true); req.setTag("${cfg.nome}|trava")
                repetir(sess, req)?.let { falhaRepeticao(g, it); return false }
                val n0 = m.nAquece
                esperar(1_000) { m.ae == CaptureResult.CONTROL_AE_STATE_LOCKED || m.nAquece - n0 >= 30 }
                m.aeTravado = m.ae == CaptureResult.CONTROL_AE_STATE_LOCKED
            }
            m.fpsLogico = fps(m.tsAquece.ultimos(15))
            // A1: lógico + o mais aberto, o passo intermediário do CTS; só medida
            fase = "A1"
            req.addTarget(lB.superficie); req.setTag("${cfg.nome}|A1")
            repetir(sess, req)?.let { falhaRepeticao(g, it); return false }
            esperar(800) { m.nA1 >= 15 }
            req.addTarget(lA.superficie)
        } else {
            req.addTarget(lA.superficie); req.addTarget(lB.superficie)
        }
        DoisSensores.gravarMarca(r, "duplo:${cfg.nome}")
        r.etapa = "duplo_${cfg.nome}"
        fase = "duplo"
        req.setTag("${cfg.nome}|duplo")
        m.inicioDuplo = SystemClock.elapsedRealtime()
        // o par sai da repetição, nunca de capture() avulso: stopRepeating + capture engasga HAL MediaTek (agy)
        repetir(sess, req)?.let { falhaRepeticao(g, it); return false }
        aoFase("Medindo as imagens.")
        janelaDupla(m, cfg, sess, req)
        parando = true
        // cancelado: nada de stopRepeating (pode bloquear); o close() do device, que já vem, encerra a sessão junto
        if (r.cancelarPedido == null) try { sess.stopRepeating() } catch (e: Exception) { }
        m.soltarAncora()
        fase = "parado"

        val medida = medida(m, cfg)
        DoisSensores.ev("dois_medida", medida)
        b.medidas += medida
        val parada = deveParar()
        if (parada != null) {
            g.desfecho = when (parada) {
                "perda" -> desfechoDaPerda()
                "excecao_callback" -> { b.classe = excecaoCallbackClasse; b.msg = excecaoCallbackMsg; "erro_app:excecao_callback:$excecaoCallback" }
                "prazo" -> "nao_testado:prazo_geral"
                else -> "nao_testado:cancelado"
            }
            return false
        }
        val par = m.par
        if (par == null) {
            val sq = m.semQuadro
            val nA = lA.n("duplo"); val nB = lB.n("duplo")
            g.desfecho = when {
                sq != null -> if (sq in fisicasIndisponiveis) "nao_testado:sensor_indisponivel_pelo_sistema:$sq" else "aceitou_nao_entregou:sem_quadro:$sq"
                ((medida["ts_comuns"] as? Int) ?: 0) > 0 -> "erro_app:pareamento"
                nA >= 30 && nB >= 30 -> "aceitou_nao_entregou:ritmos_independentes"
                else -> "aceitou_nao_entregou:entrega_insuficiente:$nA/$nB"
            }
            if (g.tipo() == "aceitou_nao_entregou") {
                val oQue = g.motivoDesfecho() ?: "?"
                b.contradicoes += "${cfg.nome}:$oQue"
                if (disseSim) evContradicao(cfg, g, oQue)
            }
            return false
        }

        // 16. prova do par, fora da HandlerThread (a análise de escala leva ~0,1 s)
        val fracA = lA.fracIguais(); val fracB = lB.fracIguais()
        val esperada = escalaEsperada(par)
        val prova = withContext(Dispatchers.Default) { Pixels.provar(par, fracA, fracB, esperada) }
        par.prova = prova
        val c = prova.contradicao
        if (c != null) {
            g.desfecho = "aceitou_nao_entregou:$c"
            b.contradicoes += "${cfg.nome}:$c"
            if (disseSim) evContradicao(cfg, g, c)
            return false
        }
        g.desfecho = if (prova.porMetadado || prova.porPixel) "simultaneo" else "simultaneo_nao_provado"
        b.par = par; b.configOk = cfg.nome; b.quadrosOk = m.nDuplo
        b.carimbo = medida["carimbo"] as? String
        b.carimboTexto = textoCarimbo(medida)
        b.deltaUsMed = medida["delta_us_med"] as? Double
        b.fim(g.desfecho, null, r.etapa)
        return true
    }

    /**
     * Janela dupla. Acaba com par + 30 resultados + metadado do par (ou 1 s depois do par); sem par, aos 3 s, prorrogáveis
     * até 5 s enquanto os dois leitores entregam; teto de 6 s. Leitor físico sem imagem aos 2 s encerra na hora.
     */
    private suspend fun janelaDupla(m: Medicao, cfg: DoisSensores.Cfg, sess: CameraCaptureSession, req: CaptureRequest.Builder) {
        while (true) {
            if (deveParar() != null) return
            val agora = SystemClock.elapsedRealtime()
            val dec = agora - m.inicioDuplo
            // em B o AE converge já na repetição dupla; a trava vem por cima do mesmo builder
            if (!cfg.comLogico && !m.aeTravado && p.aeLockDisp == true && aeOk(m.ae)) {
                req.set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (repetir(sess, req) == null) m.aeTravado = true
            }
            if (!m.querPar) {
                val aeBom = (m.aeTravado || aeOk(m.ae)) && (cfg.comLogico || m.aeFisicoB == null || aeOk(m.aeFisicoB))
                if (aeBom && m.aeOkEm == 0L) m.aeOkEm = agora
                if ((aeBom && (afOk(m.af) || agora - m.aeOkEm >= 1_000)) || dec >= 1_500) m.querPar = true
            }
            val par = m.par
            if (par != null) {
                casarMeta(m)
                if (m.nDuplo >= 30 && par.tsLogico != null) return
                if (agora - m.parEm >= 1_000) return
            } else {
                if (dec >= 2_000) {
                    val semA = m.lA.n("duplo") == 0; val semB = m.lB.n("duplo") == 0
                    if (semA || semB) { m.semQuadro = if (semA) idA else idB; return }
                }
                val ambosRecentes = agora - m.lA.ultimaChegadaMs <= 500 && agora - m.lB.ultimaChegadaMs <= 500
                if (dec >= (if (ambosRecentes) 5_000L else 3_000L)) return
            }
            if (dec >= 6_000) return
            delay(15)
        }
    }

    // ------------------------------------------------------------------ resultados, falhas e imagens

    private fun fisicos(res: TotalCaptureResult): Map<String, CaptureResult> {
        @Suppress("DEPRECATION")
        val m: Map<String, CaptureResult>? = if (Build.VERSION.SDK_INT >= 31) res.physicalCameraTotalResults else res.physicalCameraResults
        return m ?: emptyMap()
    }

    private fun meta(c: CaptureResult): DoisSensores.MetaFisica = DoisSensores.MetaFisica(
        ts = c.get(CaptureResult.SENSOR_TIMESTAMP), exp = c.get(CaptureResult.SENSOR_EXPOSURE_TIME), iso = c.get(CaptureResult.SENSOR_SENSITIVITY),
        dur = c.get(CaptureResult.SENSOR_FRAME_DURATION), skew = c.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
        focal = c.get(CaptureResult.LENS_FOCAL_LENGTH), foco = c.get(CaptureResult.LENS_FOCUS_DISTANCE), crop = c.get(CaptureResult.SCALER_CROP_REGION),
        zoom = if (Build.VERSION.SDK_INT >= 30) c.get(CaptureResult.CONTROL_ZOOM_RATIO) else null,
        ois = c.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE), ae = c.get(CaptureResult.CONTROL_AE_STATE),
        intr = c.get(CaptureResult.LENS_INTRINSIC_CALIBRATION), dist = c.get(CaptureResult.LENS_DISTORTION),
        poseT = c.get(CaptureResult.LENS_POSE_TRANSLATION), poseR = c.get(CaptureResult.LENS_POSE_ROTATION)
    )

    private fun aoResultado(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
        if (encerrando || s !== sessao) return
        val tag = req.tag as? String ?: return
        val faseReq = tag.substringAfter('|')
        val ts = res.get(CaptureResult.SENSOR_TIMESTAMP)
        val ae = res.get(CaptureResult.CONTROL_AE_STATE)
        val af = res.get(CaptureResult.CONTROL_AF_STATE)
        val ativo = if (Build.VERSION.SDK_INT >= 29) res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
        if (faseReq == "seq") { seqMed?.let { sm -> if (tag.startsWith("seq_${sm.id}|")) sm.aoResultado(ts, ae, af, fisicos(res)[sm.id]?.let { meta(it) }) }; return }
        val m = med ?: return
        if (tag.substringBefore('|') != m.cfg.nome) return
        m.ae = ae; m.af = af
        if (ativo != null) m.ativo = ativo
        when (faseReq) {
            "aquece", "trava" -> { m.nAquece++; if (ts != null) m.tsAquece.add(ts) }
            "A1" -> { m.nA1++; if (fisicos(res).containsKey(idB)) m.metaBnoA1++ }
            "duplo" -> {
                m.nDuplo++
                if (ts != null) m.tsDuplo.add(ts)
                val fis = fisicos(res)
                val ma = fis[idA]?.let { meta(it) }
                val mb = fis[idB]?.let { meta(it) }
                if (mb?.ae != null) m.aeFisicoB = mb.ae
                if (ma != null && mb != null) {
                    m.nMetaAmbos++
                    m.ultimaMetaA = ma; m.ultimaMetaB = mb
                    val ta = ma.ts; val tb = mb.ts
                    if (ta != null && tb != null) {
                        m.deltas += ta - tb
                        val ea = ma.exp; val eb = mb.exp
                        if (ea != null && eb != null) m.meioExp += (ta + ea / 2) - (tb + eb / 2)
                        val sa = ma.skew; val sb = mb.skew
                        if (sa != null && sb != null) m.difSkew += sa - sb
                    }
                }
                if (ts != null) {
                    m.mapa[ts] = Quadro(res.frameNumber, ts, ae, af, ativo, ma, mb)
                    if (m.mapa.size > 150) m.mapa.remove(m.mapa.keys.first())
                }
                casarMeta(m)
            }
        }
    }

    /** REASON_FLUSHED e falhas depois do stopRepeating não contam; o resto conta por sensor físico. */
    private fun aoFalha(s: CameraCaptureSession, f: CaptureFailure) {
        if (encerrando || s !== sessao || parando || f.reason == CaptureFailure.REASON_FLUSHED) return
        val id = if (Build.VERSION.SDK_INT >= 29) f.physicalCameraId else null
        val sm = seqMed
        if (sm != null) { sm.falhas++; return }
        val m = med ?: return
        when (id) {
            null -> m.falhasSemId++
            idA -> m.falhasA++
            idB -> m.falhasB++
            else -> m.falhasOutras++
        }
        val chave = (if (f.reason == CaptureFailure.REASON_ERROR) "erro" else "r${f.reason}") + (if (f.wasImageCaptured()) "_com_imagem" else "") + (id?.let { "_$it" } ?: "")
        m.razoes[chave] = (m.razoes[chave] ?: 0) + 1
    }

    private fun aoImagem(l: Leitor) {
        // uma acquireNextImage por notificação, nunca acquireLatestImage (que descartaria o par)
        val img: Image = try { l.leitor.acquireNextImage() } catch (e: IllegalStateException) { l.erros++; null } ?: return
        // quadro tardio (depois do stopRepeating da medida ou do plano B, ou com a rodada fechando) só é fechado, nunca processado
        if (!l.ativo || encerrando || parando) { img.close(); return }
        // S1: com o cancelamento pedido nenhuma imagem é copiada (NV21 do par ou do plano B) nem pareada
        if (cancelou("copiar_imagem")) { img.close(); return }
        var guardou = false
        try {
            val chegada = SystemClock.elapsedRealtimeNanos()
            val ts = img.timestamp
            l.imagens++
            l.registra(fase, ts, chegada)
            val m = med
            if (m != null && m.fimCopiaNs > 0 && chegada - m.fimCopiaNs < 5_000_000L) l.duranteCopia++
            if (!l.fisico) return
            if (fase == "duplo" && m != null && (l === m.lA || l === m.lB)) {
                l.anotaAssinatura(Pixels.assinatura(img))
                guardou = parear(m, l, img, ts, chegada)
            } else if (fase == "seq") {
                val sm = seqMed
                if (sm != null && sm.leitor === l && sm.quer && sm.dados.nv == null) {
                    sm.dados.nv = Pixels.nv21(img); sm.dados.tsImg = ts
                }
            }
        } finally {
            if (!guardou) img.close()
        }
    }

    /**
     * Pareamento por âncora: segura UMA imagem, a mais antiga, e só avança o lado atrasado. Aguenta qualquer atraso fixo
     * de entrega e se recupera de quadro perdido. true = a imagem ficou guardada como âncora (o chamador não fecha).
     */
    private fun parear(m: Medicao, l: Leitor, img: Image, ts: Long, chegada: Long): Boolean {
        if (m.par != null || !m.querPar) return false
        val anc = m.ancora
        val de = m.ancoraDe
        if (anc == null || de == null) { m.ancora = img; m.ancoraDe = l; m.ancoraTs = ts; m.ancoraEm = chegada; return true }
        if (de === l) {
            if (chegada - m.ancoraEm > 500_000_000L) {
                try { anc.close() } catch (e: Exception) { }
                m.ancora = img; m.ancoraTs = ts; m.ancoraEm = chegada
                return true
            }
            return false
        }
        val dif = ts - m.ancoraTs
        val tol = min(5_000_000L, max(1L, m.intervaloNs() / 4))
        return when {
            dif == 0L -> { montarPar(m, anc, de, img, "exato"); false }
            abs(dif) <= tol -> { montarPar(m, anc, de, img, "tolerancia"); false }
            dif < 0 -> false                 // a nova é mais antiga: este leitor está atrasado, fecha e espera
            else -> {                        // a nova é mais nova: o par da âncora não vem mais
                try { anc.close() } catch (e: Exception) { }
                m.ancora = img; m.ancoraDe = l; m.ancoraTs = ts; m.ancoraEm = chegada
                true
            }
        }
    }

    /** i1 é a âncora (de l1), i2 a imagem que acabou de chegar do outro leitor. */
    private fun montarPar(m: Medicao, i1: Image, l1: Leitor, i2: Image, casou: String) {
        // S1: conferência imediatamente antes de copiar os dois quadros (cada NV21 tem ~3 MB); a âncora fecha aqui
        if (cancelou("copiar_imagem")) { m.soltarAncora(); return }
        val imgA = if (l1 === m.lA) i1 else i2
        val imgB = if (l1 === m.lA) i2 else i1
        val t0 = SystemClock.elapsedRealtimeNanos()
        val nvA = Pixels.nv21(imgA); val nvB = Pixels.nv21(imgB)
        val miniA = Pixels.miniatura(imgA); val miniB = Pixels.miniatura(imgB)
        m.fimCopiaNs = SystemClock.elapsedRealtimeNanos()
        val par = DoisSensores.ParDados(m.cfg.nome, imgA.width, imgA.height, idA, idB, nvA, nvB, imgA.timestamp, imgB.timestamp, casou,
            miniA, miniB, (m.fimCopiaNs - t0) / 1_000_000L)
        m.par = par
        m.parEm = SystemClock.elapsedRealtime()
        // a âncora era a imagem guardada; a outra o chamador fecha
        try { m.ancora?.close() } catch (e: Exception) { }
        m.ancora = null; m.ancoraDe = null
        casarMeta(m)
    }

    /** Metadado do par: resultado com o mesmo SENSOR_TIMESTAMP lógico (o carimbo das Images é o lógico, por desenho). */
    private fun casarMeta(m: Medicao) {
        val par = m.par ?: return
        if (par.tsLogico != null) return
        val alvo = par.tsImgA
        val tol = min(5_000_000L, max(1L, m.intervaloNs() / 4))
        val q = m.mapa[alvo] ?: m.mapa.values.minByOrNull { abs(it.tsLogico - alvo) }?.takeIf { abs(it.tsLogico - alvo) <= tol } ?: return
        par.tsLogico = q.tsLogico
        par.casouMeta = if (q.tsLogico == alvo) "exato" else "tolerancia"
        par.metaA = q.a; par.metaB = q.b; par.af = q.af; par.ae = q.ae
    }

    /** Escala esperada pelo recorte de cada físico: (f·PA/WP/crop) do principal sobre o do mais aberto. */
    private fun escalaEsperada(par: DoisSensores.ParDados): Double? {
        val ma = par.metaA ?: return null; val mb = par.metaB ?: return null
        val sa = p.sensores[idA] ?: return null; val sb = p.sensores[idB] ?: return null
        val fa = (ma.focal ?: sa.focais.firstOrNull())?.toDouble() ?: return null
        val fb = (mb.focal ?: sb.focais.firstOrNull())?.toDouble() ?: return null
        val paA = sa.matriz?.width?.toDouble() ?: return null; val paB = sb.matriz?.width?.toDouble() ?: return null
        val wpA = sa.fisico?.width?.toDouble() ?: return null; val wpB = sb.fisico?.width?.toDouble() ?: return null
        val cA = ma.crop?.width()?.toDouble() ?: return null; val cB = mb.crop?.width()?.toDouble() ?: return null
        if (wpA <= 0 || wpB <= 0 || cA <= 0 || cB <= 0 || fb <= 0 || paB <= 0) return null
        return (fa * paA / wpA / cA) / (fb * paB / wpB / cB)
    }

    // ------------------------------------------------------------------ medida

    private fun mediana(v: List<Long>): Long? = if (v.isEmpty()) null else v.sorted()[v.size / 2]
    private fun medianaD(v: List<Double>): Double? = if (v.isEmpty()) null else v.sorted()[v.size / 2]

    private fun fps(ts: LongArray): Double? {
        if (ts.size < 3) return null
        val dif = ArrayList<Long>()
        for (i in 1 until ts.size) { val d = ts[i] - ts[i - 1]; if (d > 0) dif += d }
        val md = mediana(dif) ?: return null
        return 1e9 / md
    }

    private fun ritmoMs(ts: LongArray): Double? {
        if (ts.size < 2) return null
        val ord = ts.sortedArray()
        val dif = ArrayList<Long>()
        for (i in 1 until ord.size) { val d = ord[i] - ord[i - 1]; if (d > 0) dif += d }
        return mediana(dif)?.let { it / 1e6 }
    }

    private fun maisProximo(ord: LongArray, t: Long): Long? {
        if (ord.isEmpty()) return null
        val i = ord.binarySearch(t)
        if (i >= 0) return ord[i]
        val ins = -i - 1
        val antes = if (ins > 0) ord[ins - 1] else null
        val depois = if (ins < ord.size) ord[ins] else null
        return when {
            antes == null -> depois
            depois == null -> antes
            abs(antes - t) <= abs(depois - t) -> antes
            else -> depois
        }
    }

    private fun medida(m: Medicao, cfg: DoisSensores.Cfg): Map<String, Any?> {
        val tA = m.lA.ts("duplo"); val tB = m.lB.ts("duplo")
        val cA = m.lA.chegadas("duplo"); val cB = m.lB.chegadas("duplo")
        val chegadaB = HashMap<Long, Long>(tB.size * 2)
        for (i in tB.indices) chegadaB[tB[i]] = cB[i]
        var comuns = 0
        val atrasos = ArrayList<Long>()
        for (i in tA.indices) { val chB = chegadaB[tA[i]] ?: continue; comuns++; atrasos += chB - cA[i] }
        val tol = min(5_000_000L, max(1L, m.intervaloNs() / 4))
        val ordB = tB.sortedArray()
        var comTol = 0
        var menorDif = Long.MAX_VALUE
        for (t in tA) {
            val d = maisProximo(ordB, t) ?: continue
            val ad = abs(d - t)
            if (ad < menorDif) menorDif = ad
            if (ad in 1..tol) comTol++
        }
        val tsLogA1 = m.lL?.ts("A1")
        val comunsA1 = tsLogA1?.let { lg -> val s = lg.toHashSet(); m.lB.ts("A1").count { it in s } }
        val absUs = m.deltas.map { abs(it) / 1000.0 }.sorted()
        val carimbo = when {
            m.deltas.isEmpty() -> "sem_metadado"
            // "iguais", não "copiado": igualdade dos carimbos físicos não demonstra que o HAL os copiou (D5)
            m.deltas.all { it == 0L } -> "iguais"
            p.sync == CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "medido"
            else -> "aproximado"
        }
        val ma = m.ultimaMetaA; val mb = m.ultimaMetaB
        return linkedMapOf(
            "rodada" to r.id, "config" to cfg.nome, "quadros" to m.nDuplo, "quadros_meta_2e3" to m.nMetaAmbos,
            "delta_us_min" to absUs.firstOrNull(), "delta_us_med" to medianaD(absUs), "delta_us_max" to absUs.lastOrNull(),
            "delta_us_sinal_med" to mediana(m.deltas)?.let { it / 1000.0 },
            "meio_exp_us_med" to mediana(m.meioExp)?.let { it / 1000.0 },
            "dif_skew_us" to mediana(m.difSkew)?.let { it / 1000.0 },
            "carimbo" to carimbo, "sync" to p.sync, "fps_alvo" to p.fpsAlvo(cfg.tam), "fps_logico" to m.fpsLogico, "fps_duplo" to fps(m.tsDuplo.toArray()),
            "imagens_logico" to m.lL?.n("duplo"), "imagens_$idA" to tA.size, "imagens_$idB" to tB.size,
            "imagens_${idB}_A1" to m.lB.n("A1"), "resultados_A1" to m.nA1, "meta_${idB}_A1" to m.metaBnoA1, "ts_comuns_logico_${idB}_A1" to comunsA1,
            "ts_comuns" to comuns, "ts_comuns_tol" to comTol,
            "atraso_entrega_med_ms" to mediana(atrasos)?.let { it / 1e6 }, "atraso_entrega_max_ms" to atrasos.maxOrNull()?.let { it / 1e6 },
            "ritmo_${idA}_ms" to ritmoMs(tA), "ritmo_${idB}_ms" to ritmoMs(tB),
            "menor_dif_ms" to (if (menorDif == Long.MAX_VALUE) null else menorDif / 1e6),
            "perdidos_logico" to m.lL?.perdidos, "perdidos_$idA" to m.lA.perdidos, "perdidos_$idB" to m.lB.perdidos,
            "falhas_$idA" to m.falhasA, "falhas_$idB" to m.falhasB, "falhas_sem_id" to m.falhasSemId, "falhas_outras" to m.falhasOutras,
            "falha_razoes" to m.razoes.entries.map { "${it.key}=${it.value}" },
            "chegadas_durante_copia" to (m.lA.duranteCopia + m.lB.duranteCopia), "erros_aquisicao" to (m.lA.erros + m.lB.erros),
            "ae_estado" to m.ae, "ae_travado" to m.aeTravado, "af_estado" to m.af, "ativo_fisico" to m.ativo,
            "focal_${idA}_mm" to ma?.focal, "focal_${idB}_mm" to mb?.focal, "crop_$idA" to ma?.crop, "crop_$idB" to mb?.crop,
            "zoom_$idA" to ma?.zoom, "zoom_$idB" to mb?.zoom, "ois_$idA" to ma?.ois, "ois_$idB" to mb?.ois,
            "media_y_$idA" to m.lA.mediaY(), "media_y_$idB" to m.lB.mediaY(), "desvio_y_$idA" to m.lA.desvioY(), "desvio_y_$idB" to m.lB.desvioY(),
            "iguais_seguidos_$idA" to m.lA.iguaisSeguidos, "iguais_seguidos_$idB" to m.lB.iguaisSeguidos,
            "par" to (m.par != null), "ts_casou" to (m.par?.casou ?: "nao"), "sem_quadro" to m.semQuadro,
            "excecao_callback" to excecaoCallback, "ms" to (SystemClock.elapsedRealtime() - m.inicioDuplo)
        )
    }

    /** Linha do carimbo: só CALIBRATED mede a exposição; carimbo igual em todos os quadros não vira "0 µs medido". */
    private fun textoCarimbo(medida: Map<String, Any?>): String = when (medida["carimbo"]) {
        "medido" -> "desvio entre as exposições: mediana ${medida["delta_us_med"]} µs, máximo ${medida["delta_us_max"]} µs (o CTS exige até 10 ms); " +
            "meio da exposição: ${medida["meio_exp_us_med"] ?: "-"} µs"
        // todos iguais a zero: só diz o que se viu e o que a medida não confirmou, sem atribuir causa (D5, Astra 02/10)
        "iguais" -> DoisSensores.TEXTO_CARIMBOS_IGUAIS
        "aproximado" -> "carimbo aproximado informado pelo HAL; não mede a exposição (mediana ${medida["delta_us_med"]} µs)"
        else -> "sem metadado físico dos dois sensores: desvio não medido"
    }

    // ------------------------------------------------------------------ plano B

    private class SeqMedicao(val id: String, val leitor: Leitor, val dados: DoisSensores.SeqDados) {
        var n = 0
        var ae: Int? = null
        var af: Int? = null
        var quer = false
        var falhas = 0
        val mapa = LinkedHashMap<Long, Triple<DoisSensores.MetaFisica?, Int?, Int?>>()
        fun aoResultado(ts: Long?, ae: Int?, af: Int?, meta: DoisSensores.MetaFisica?) {
            n++; this.ae = ae; this.af = af
            if (ts != null) { mapa[ts] = Triple(meta, ae, af); if (mapa.size > 60) mapa.remove(mapa.keys.first()) }
        }
        /** Metadado do quadro guardado: mesmo carimbo, ou o mais próximo dentro de ±5 ms. */
        fun casar(ts: Long): Boolean {
            val e = mapa[ts] ?: mapa.entries.minByOrNull { abs(it.key - ts) }?.takeIf { abs(it.key - ts) <= 5_000_000L }?.value ?: return false
            dados.meta = e.first; dados.ae = e.second; dados.af = e.third
            return true
        }
    }

    private fun tamanhoSeq(id: String): Size {
        val ly = p.sensores[id]?.ly.orEmpty()
        listOfNotNull(p.sCts, p.s43, p.configs.firstOrNull()?.tam).firstOrNull { it in ly }?.let { return it }
        return ly.filter { it.width * 3 == it.height * 4 && it.width.toLong() * it.height <= 1440L * 1080L }.maxByOrNull { it.width.toLong() * it.height }
            ?: ly.filter { it.width <= 1920 && it.height <= 1088 }.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)
    }

    /**
     * Prova de que cada sensor funciona SOZINHO. Nunca transforma "não" em "sim": o veredito da pergunta continua sendo
     * o da tentativa dupla, e tudo daqui sai rotulado SEQUENCIAL, NÃO SIMULTÂNEO.
     */
    private suspend fun planoB(b: DoisSensores.Bruto) {
        if (b.resultado != null) return
        val (vered, _) = DoisSensores.vereditoSemPar(b.registros, p, b.prazo)
        if (vered !in setOf("recusou_limpo", "recusou_sem_consulta", "aceitou_nao_entregou", "erro_app", "sem_tamanho_comum")) return
        // S1: o cancelamento (ou o app ter saído da tela) barra o plano B ANTES de ele começar
        if (cancelou("plano_b") || r.saiuDoPrimeiroPlano != null || excecaoCallback != null || b.prazo != null) return
        val reabrir = device == null || perda != null
        if (reabrir && perdaCodigo != CameraDevice.StateCallback.ERROR_CAMERA_DEVICE && perdaCodigo != CameraDevice.StateCallback.ERROR_CAMERA_SERVICE) {
            b.sequencial = "nao_rodou:sem_camera"; return
        }
        if (restanteMs() < (if (reabrir) 24_000L else 21_000L)) { b.sequencial = "nao_rodou:sem_tempo"; return }
        if (reabrir) {
            // a perda já está no desfecho da config; reabre uma vez, com prazo de 3 s
            try { sessao?.close() } catch (e: Exception) { }
            sessao = null
            perda = null; perdaCodigo = null; perdaAposConfigurar = false; perdaForaDoPrimeiroPlano = false
            r.etapa = "plano_b_reabrir"
            // S1: reabrir também é "abrir a câmera"; a flag é conferida de novo imediatamente antes
            if (cancelou("plano_b_abrir")) return
            val cb = iniciarAbertura()
            val falha = pedirAbertura(cb)
            if (falha != null) { abrindoPendente = false; cb.descontar("falha_abertura"); b.sequencial = "falhou:reabrir:$falha"; return }
            esperar(3_000) { device != null || perda != null }
            if (device == null) { b.sequencial = "falhou:reabrir:${perda ?: "prazo"}"; return }
        }
        r.planoB = true
        val dA = seqUm(idA, b)
        val dB = if (deveParar() == null) seqUm(idB, b) else null
        if (dA.ok && dB?.ok == true) {
            val ta = dA.meta?.ts ?: dA.tsImg; val tb = dB.meta?.ts ?: dB.tsImg
            if (ta != null && tb != null) dB.intervaloMs = (tb - ta) / 1e6
        }
        b.sequencial = when {
            deveParar() == "prazo" -> "interrompido:prazo"
            dA.ok && dB?.ok == true -> "ok"
            dA.ok -> "parcial:$idA"
            dB?.ok == true -> "parcial:$idB"
            else -> "falhou:${dA.motivo ?: dB?.motivo ?: "?"}"
        }
    }

    private suspend fun seqUm(id: String, b: DoisSensores.Bruto): DoisSensores.SeqDados {
        val t0 = SystemClock.elapsedRealtime()
        r.etapa = "plano_b_$id"
        aoFase("Testando um sensor de cada vez.")
        DoisSensores.gravarMarca(r, "plano_b:$id")
        val tam = tamanhoSeq(id)
        r.tecnico = "sensor $id sozinho · ${tam.wxh()}"
        val d = DoisSensores.SeqDados(id, tam.width, tam.height)
        b.seq += d
        try {
            seqDentro(id, tam, d, t0)
        } finally {
            seqMed = null
            if (fase == "seq") fase = "parado"
            d.ok = d.nv != null
            if (!d.ok && d.motivo == null) d.motivo = perda?.let { "perdeu_camera:$it" } ?: deveParar() ?: "sem_quadro"
            d.ms = SystemClock.elapsedRealtime() - t0
            val mt = d.meta
            DoisSensores.ev("dois_sequencial", linkedMapOf(
                "rodada" to r.id, "id" to id, "larg" to tam.width, "alt" to tam.height, "ok" to d.ok, "motivo" to d.motivo,
                "exp_ns" to mt?.exp, "iso" to mt?.iso, "focal_mm" to mt?.focal, "crop" to mt?.crop, "af_estado" to d.af,
                "simultaneo" to false, "rotulo" to "SEQUENCIAL_NAO_SIMULTANEO", "intervalo_ms" to d.intervaloMs, "ms" to d.ms))
        }
        return d
    }

    private suspend fun seqDentro(id: String, tam: Size, d: DoisSensores.SeqDados, t0: Long) {
        val dev = device ?: run { d.motivo = "sem_camera"; return }
        val l = try { novoLeitor(id, tam.width, tam.height, 4, true) } catch (e: Exception) { d.motivo = "leitor:${e.javaClass.simpleName}"; return }
        val t = Tentativa("seq_$id")
        val sc = try {
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(l.superficie).also { it.setPhysicalCameraId(id) }), executor, t.cb)
        } catch (e: Exception) { d.motivo = "saidas:${e.javaClass.simpleName}"; return }
        val pos = consultaPos(dev, sc)   // registrada; não decide no plano B
        // S1: conferência imediatamente antes de criar a sessão do plano B (a marca gravada acima suspendeu)
        if (cancelou("criar_sessao")) { l.fechar(); d.motivo = "cancelado"; return }
        tentativaAtual = t
        val tb = SystemClock.elapsedRealtime()
        val erro: String? = try { dev.createCaptureSession(sc); null } catch (e: Exception) { e.javaClass.simpleName }
        val msBloqueio = SystemClock.elapsedRealtime() - tb
        if (erro == null) esperar(3_000) { t.configurada || t.falhou || perda != null }
        val resSessao = when { erro != null -> "excecao"; t.configurada -> "configurada"; t.falhou -> "configure_failed"; perda != null -> "perdeu_camera"; else -> "prazo" }
        DoisSensores.ev("dois_sessao", linkedMapOf(
            "rodada" to r.id, "config" to "seq_$id", "tamanho_cts" to (tam == p.sCts), "larg" to tam.width, "alt" to tam.height, "fluxos" to id,
            "consulta_pos" to pos, "decisao" to "plano_b", "tentou" to true, "resultado" to resSessao, "motivo" to erro,
            "ms_bloqueio" to msBloqueio, "ms" to (SystemClock.elapsedRealtime() - t0)))
        val sess = t.sessao
        if (resSessao != "configurada" || sess == null) { tentativaAtual = null; d.motivo = "sessao:$resSessao"; return }
        sessao = sess
        val sm = SeqMedicao(id, l, d)
        seqMed = sm
        parando = false
        fase = "seq"
        val req = try { dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) } catch (e: Exception) { d.motivo = "requisicao:${e.javaClass.simpleName}"; return }
        aplicarBase(req, tam)
        req.addTarget(l.superficie); req.setTag("seq_$id|seq")
        repetir(sess, req)?.let { d.motivo = "repeticao:$it"; return }
        esperar(2_000) { aeOk(sm.ae) || sm.n >= 20 }
        if (id == idA) esperar(1_000) { afOk(sm.af) }
        sm.quer = true
        esperar(1_000) { d.nv != null }
        val ts = d.tsImg
        if (ts != null) esperar(1_000) { sm.casar(ts) }
        parando = true
        if (r.cancelarPedido == null) try { sess.stopRepeating() } catch (e: Exception) { }
        if (d.nv == null && sm.falhas > 0) d.motivo = "falhas:${sm.falhas}"
    }

    // ------------------------------------------------------------------ fechamento

    /**
     * Fecha sempre, inclusive em cancelamento (chamado em NonCancellable): repetição, sessão, device (espera onClosed até
     * 1,5 s), leitores (os das tentativas só agora, depois do device) e o callback de disponibilidade.
     *
     * Este é o fechamento COOPERATIVO, que roda na thread do teste. O de emergência, que não depende dela, é
     * DoisSensores.fecharPorFora (Cancelar, Voltar, ON_STOP, cão de guarda e prazo de cancelamento vencido).
     */
    private suspend fun fecharTudo() {
        encerrando = true
        parando = true
        r.etapa = "fechar"
        med?.soltarAncora()
        // O device fecha primeiro e sem stopRepeating antes: no caminho normal a repetição já parou na fase, e no de
        // emergência (cancelamento, prazo, perda) uma chamada presa ao HAL não pode impedir o close() de acontecer
        // (revisão de segurança do Astra, 02/10). close() do device encerra a sessão junto; o close() da sessão depois
        // é permitido pela API e só garante.
        val d = device
        device = null
        DoisSensores.fechando(r, "fim_da_rodada")
        if (d != null) try { d.close() } catch (e: Exception) { }
        try { sessao?.close() } catch (e: Exception) { }
        sessao = null
        val e = estado
        if (e != null && e.aberta && !esperarFechando(1_500) { e.fechada }) {
            // o onClosed não veio em 1,5 s: o fechamento cooperativo expirou. A câmera segue retida (só o onClosed a libera);
            // o evento fechamento_pendente só sairia 5 s depois do pedido, e este marca o fim desta espera
            DoisSensores.ev("dois_camerax", linkedMapOf("rodada" to r.id, "acao" to "fechamento_cooperativo_expirou", "gatilho" to r.gatilhoFechar, "ms" to 1_500L))
        }
        // A câmera NÃO é marcada como liberada aqui, nem por ter esperado: só o onClosed (EstadoCamera.descontar) libera.
        // Sem ele, o fechamento fica pendente, novo teste e novo bind do CameraX continuam bloqueados e a tela oferece
        // "Reabrir câmera" (S3). O fio de callbacks também continua vivo até lá.
        for (l in leitores) l.fechar()
        if (registrouDisp) try { cm.unregisterAvailabilityCallback(dispCb) } catch (x: Exception) { }
    }

    private fun nomeErroCamera(e: Int) = when (e) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
        else -> "ERRO_$e"
    }

    private fun nomeAcesso(razao: Int) = when (razao) {
        CameraAccessException.CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraAccessException.CAMERA_DISCONNECTED -> "CAMERA_DISCONNECTED"
        CameraAccessException.CAMERA_ERROR -> "CAMERA_ERROR"
        CameraAccessException.CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        else -> "R$razao"
    }

    /** Medidas de uma config. Estado só na HandlerThread. */
    private class Medicao(val cfg: DoisSensores.Cfg, val lL: Leitor?, val lA: Leitor, val lB: Leitor) {
        var ae: Int? = null
        var af: Int? = null
        var ativo: String? = null
        var aeTravado = false
        var aeOkEm = 0L
        var aeFisicoB: Int? = null
        var nAquece = 0
        val tsAquece = ListaLong()
        var nA1 = 0
        var metaBnoA1 = 0
        var nDuplo = 0
        var nMetaAmbos = 0
        val tsDuplo = ListaLong()
        val deltas = ArrayList<Long>()
        val meioExp = ArrayList<Long>()
        val difSkew = ArrayList<Long>()
        var ultimaMetaA: DoisSensores.MetaFisica? = null
        var ultimaMetaB: DoisSensores.MetaFisica? = null
        val mapa = LinkedHashMap<Long, Quadro>()
        var falhasA = 0
        var falhasB = 0
        var falhasSemId = 0
        var falhasOutras = 0
        val razoes = LinkedHashMap<String, Int>()
        var fpsLogico: Double? = null
        var inicioDuplo = 0L
        var querPar = false
        var ancora: Image? = null
        var ancoraDe: Leitor? = null
        var ancoraTs = 0L
        var ancoraEm = 0L
        var par: DoisSensores.ParDados? = null
        var parEm = 0L
        var fimCopiaNs = 0L
        var semQuadro: String? = null

        fun soltarAncora() { try { ancora?.close() } catch (e: Exception) { }; ancora = null; ancoraDe = null }

        /** Intervalo entre quadros pela mediana dos últimos carimbos lógicos; 33 ms se ainda não há dado. */
        fun intervaloNs(): Long {
            val u = tsDuplo.ultimos(11)
            if (u.size < 3) return 33_333_333L
            val d = ArrayList<Long>()
            for (i in 1 until u.size) { val x = u[i] - u[i - 1]; if (x > 0) d += x }
            if (d.isEmpty()) return 33_333_333L
            d.sort()
            return d[d.size / 2]
        }
    }
}
