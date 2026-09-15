package br.maxymus.cameraestudo

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Câmera lenta "de estudo": o vídeo é gravado normalmente e depois os tempos de cada quadro são
 * esticados (4x), sem recodificar; o áudio sai porque não faria sentido esticado.
 *
 * Limitação honesta: câmera lenta de verdade grava a 120/240 quadros por segundo (sessão de alta
 * velocidade do Camera2, que o CameraX não expõe). Aqui o vídeo fica 4x mais lento com os mesmos
 * 30 quadros por segundo de origem, ou seja, 7,5 quadros por segundo na reprodução.
 */
object Lenta {
    suspend fun esticar(contexto: Context, origem: Uri, fator: Int = 4): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File.createTempFile("lenta", ".mp4", contexto.cacheDir)
            val ext = MediaExtractor().apply { contexto.contentResolver.openFileDescriptor(origem, "r")!!.use { setDataSource(it.fileDescriptor) } }
            var trilha = -1; var formato: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { trilha = i; formato = f; break }
            }
            if (trilha < 0 || formato == null) return@runCatching null
            ext.selectTrack(trilha)
            val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (formato.containsKey(MediaFormat.KEY_FRAME_RATE)) formato.setInteger(MediaFormat.KEY_FRAME_RATE, maxOf(1, formato.getInteger(MediaFormat.KEY_FRAME_RATE) / fator))
            val saida = muxer.addTrack(formato)
            muxer.start()
            val tamanhoMax = if (formato.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) formato.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 * 1024 * 1024
            val buf = ByteBuffer.allocate(tamanhoMax)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val n = ext.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0; info.size = n; info.presentationTimeUs = ext.sampleTime * fator; info.flags = ext.sampleFlags
                muxer.writeSampleData(saida, buf, info)
                ext.advance()
            }
            muxer.stop(); muxer.release(); ext.release()

            // grava no MediaStore como um vídeo novo, ao lado do original
            val valores = Fotos.novoVideo().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "LENTA_" + System.currentTimeMillis() + ".mp4") }
            val uri = contexto.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, valores) ?: return@runCatching null
            contexto.contentResolver.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
            temp.delete()
            uri
        }.getOrNull()
    }
}
