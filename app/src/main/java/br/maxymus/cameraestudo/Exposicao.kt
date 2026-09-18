package br.maxymus.cameraestudo

/**
 * Últimos parâmetros de exposição lidos do sensor (via CaptureResult do Preview no CameraScreen).
 * É um holder leve, escrito no thread do CameraX e lido no disparo — por isso @Volatile e não estado do Compose
 * (estado do Compose recomporia a tela a cada quadro da prévia). A câmera é uma só, então o último valor é o atual.
 * Serve à telemetria e ao EXIF (Fotos.gravaExif), que antes gravavam iso/tempo nulos fora do modo PRO.
 */
object Exposicao {
    @Volatile var iso: Int? = null            // ISO efetivo (SENSOR_SENSITIVITY)
    @Volatile var tempoNs: Long? = null       // tempo de exposição em nanossegundos (SENSOR_EXPOSURE_TIME)
    @Volatile var focoMm: Float? = null       // distância focal em mm (LENS_FOCAL_LENGTH)
    @Volatile var aberturaF: Float? = null    // número f (LENS_APERTURE)

    fun registra(iso: Int?, tempoNs: Long?, focoMm: Float?, aberturaF: Float?) {
        if (iso != null) this.iso = iso
        if (tempoNs != null) this.tempoNs = tempoNs
        if (focoMm != null) this.focoMm = focoMm
        if (aberturaF != null) this.aberturaF = aberturaF
    }
}
