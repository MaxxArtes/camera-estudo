package br.maxymus.cameraestudo

/** Ponte entre as teclas físicas (MainActivity.onKeyDown) e a tela da câmera: volume dispara a foto. */
object Atalhos {
    @Volatile var aoDisparar: (() -> Unit)? = null
}
