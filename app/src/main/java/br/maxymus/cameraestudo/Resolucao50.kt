package br.maxymus.cameraestudo

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * EXPERIMENTAL: captura na resolução cheia do sensor (50 MP), pela config vendor da Xiaomi
 * `com.xiaomi.scaler.availableSuperResolutionStreamConfigurations` (8192x6144 em YUV; o mapa padrão só oferece
 * 12 MP binado). É o mesmo caminho que os ports de GCam usam ("exposeFullSizeForQCFA"). Camera2 cru, fora do
 * CameraX: o chamador tem que soltar a câmera antes (unbind) e religar depois.
 *
 * Incerto por natureza: o HAL MediaTek pode recusar o fluxo, ou entregar 12 MP esticado sem detalhe real. Toda
 * falha vira telemetria e devolve null (o app cai na captura normal). Só vale se a medição no aparelho provar
 * que o detalhe é real. Não há JPEG na config vendor, só YUV/RAW: capturamos YUV_420_888 e comprimimos aqui.
 */
object Resolucao50 {
    // a tag vendor é lida por nome (não há constante pública); devolve os tamanhos [formato, larg, alt, io] em int[]
    private fun vendorConfigs(c: CameraCharacteristics): IntArray? = runCatching {
        val chave = CameraCharacteristics.Key("com.xiaomi.scaler.availableSuperResolutionStreamConfigurations", IntArray::class.java)
        c.get(chave)
    }.getOrNull()

    /** Maior tamanho YUV (formato 35 = YUV_420_888) anunciado na config vendor da câmera traseira principal. */
    fun disponivel(contexto: Context): Pair<String, Size>? = runCatching {
        val mgr = contexto.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var melhor: Pair<String, Size>? = null
        for (id in mgr.cameraIdList) {
            val cc = mgr.getCameraCharacteristics(id)
            if (cc.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val cfg = vendorConfigs(cc) ?: continue
            var i = 0
            while (i + 3 < cfg.size) {
                val fmt = cfg[i]; val w = cfg[i + 1]; val h = cfg[i + 2]; i += 4
                if (fmt == ImageFormat.YUV_420_888 && (melhor == null || w.toLong() * h > melhor!!.second.width.toLong() * melhor!!.second.height)) {
                    melhor = id to Size(w, h)
                }
            }
        }
        melhor
    }.getOrNull()

    /** Captura 1 quadro na resolução cheia e devolve JPEG (ou null se falhar). Bloqueante: rodar em IO. */
    suspend fun captura(contexto: Context): ByteArray? = withContext(Dispatchers.IO) {
        val alvo = disponivel(contexto) ?: run { Telemetria.evento("erro", mapOf("onde" to "res50", "msg" to "sem config vendor")); return@withContext null }
        val (idCam, tam) = alvo
        val mgr = contexto.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val fio = HandlerThread("res50").also { it.start() }
        val h = Handler(fio.looper)
        val leitor = ImageReader.newInstance(tam.width, tam.height, ImageFormat.YUV_420_888, 2)
        try {
            withTimeoutOrNull(20_000) {
                val cam = abre(mgr, idCam, h) ?: return@withTimeoutOrNull null
                try {
                    val sessao = sessao(cam, leitor.surface, h) ?: return@withTimeoutOrNull null
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(leitor.surface)
                        // dica de remosaico: tenta ligar a chave vendor (se o HAL ignorar, o fluxo grande já costuma bastar)
                        runCatching { set(CaptureRequest.Key("com.xiaomi.capture.remosaic.enable", Int::class.java), 1) }
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    val img = suspendCancellableCoroutine<android.media.Image?> { cont ->
                        leitor.setOnImageAvailableListener({ r -> cont.resume(runCatching { r.acquireNextImage() }.getOrNull()) }, h)
                        sessao.capture(req, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(s: CameraCaptureSession, rq: CaptureRequest, f: android.hardware.camera2.CaptureFailure) { if (cont.isActive) cont.resume(null) }
                        }, h)
                    } ?: return@withTimeoutOrNull null
                    val jpeg = runCatching { yuvParaJpeg(img) }.getOrNull()
                    img.close()
                    Telemetria.evento("res50", mapOf("ok" to (jpeg != null), "larg" to tam.width, "alt" to tam.height, "mp" to (tam.width.toLong() * tam.height / 1_000_000)))
                    jpeg
                } finally { runCatching { cam.close() } }
            }
        } catch (e: Exception) {
            Telemetria.evento("erro", mapOf("onde" to "res50", "msg" to (e.message ?: e::class.java.simpleName))); null
        } finally { runCatching { leitor.close() }; fio.quitSafely() }
    }

    @Suppress("MissingPermission")
    private suspend fun abre(mgr: CameraManager, id: String, h: Handler): CameraDevice? = suspendCancellableCoroutine { cont ->
        runCatching {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { if (cont.isActive) cont.resume(d) }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (cont.isActive) cont.resume(null) }
                override fun onError(d: CameraDevice, e: Int) { d.close(); if (cont.isActive) cont.resume(null) }
            }, h)
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    private suspend fun sessao(cam: CameraDevice, s: Surface, h: Handler): CameraCaptureSession? = suspendCancellableCoroutine { cont ->
        runCatching {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(sess: CameraCaptureSession) { if (cont.isActive) cont.resume(sess) }
                override fun onConfigureFailed(sess: CameraCaptureSession) { if (cont.isActive) cont.resume(null) }
            }, h)
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    // YUV_420_888 -> NV21 -> JPEG (YuvImage). 50 MP é pesado mas roda em IO.
    private fun yuvParaJpeg(img: android.media.Image): ByteArray {
        val w = img.width; val h = img.height
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val nv21 = ByteArray(w * h * 3 / 2)
        // luminância
        var pos = 0
        val yb = y.buffer; val yStride = y.rowStride; val yPix = y.pixelStride
        for (row in 0 until h) { var col = 0; var o = row * yStride; while (col < w) { nv21[pos++] = yb.get(o); o += yPix; col++ } }
        // croma intercalado VU (NV21)
        val vb = v.buffer; val ub = u.buffer; val cStride = v.rowStride; val cPix = v.pixelStride
        for (row in 0 until h / 2) { var col = 0; var o = row * cStride; while (col < w / 2) { nv21[pos++] = vb.get(o); nv21[pos++] = ub.get(o); o += cPix; col++ } }
        val saida = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 95, saida)
        return saida.toByteArray()
    }
}
