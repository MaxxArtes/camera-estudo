package br.maxymus.galeriaestudo

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder

/**
 * Carregador de imagens da grade: decode normal do Coil (respeita a rotação do EXIF; a miniatura do MediaStore na
 * MIUI às vezes vem deitada, aviso do agy) mais o decodificador de quadro de vídeo, para os vídeos terem miniatura.
 */
object Miniaturas {
    @Volatile private var carregador: ImageLoader? = null
    fun carregador(ctx: Context): ImageLoader = carregador ?: synchronized(this) {
        carregador ?: ImageLoader.Builder(ctx.applicationContext).components { add(VideoFrameDecoder.Factory()) }.crossfade(false).build().also { carregador = it }
    }
}
