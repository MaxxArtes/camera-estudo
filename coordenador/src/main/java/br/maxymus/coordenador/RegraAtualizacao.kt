package br.maxymus.coordenador

/** Quando oferecer a versão nova do app. Sem Android, para rodar na JVM (testes-jvm). */
internal object RegraAtualizacao {
    const val PREFIXO_APK = "https://pub-520120b0b03b4d3f8c94c5c9ba10d569.r2.dev/coordenador/"
    const val ESPERA_ADIADA_MS = 24L * 60 * 60 * 1000

    /** "Depois" cala a MESMA versão por um dia; uma versão ainda mais nova volta a ser oferecida na hora. */
    fun oferecer(instalado: Long, publicado: Long, adiadaCodigo: Long, adiadaEm: Long, agora: Long): Boolean {
        if (publicado <= instalado) return false
        return !(publicado == adiadaCodigo && agora - adiadaEm in 0 until ESPERA_ADIADA_MS)
    }

    /** O manifesto vem de um bucket público: o app só abre APK publicado pela CI no próprio prefixo. */
    fun apkConfiavel(url: String): Boolean =
        url.startsWith(PREFIXO_APK) && url.endsWith(".apk") && !url.contains("..") &&
            url.substring(PREFIXO_APK.length).none { it == '/' || it == '?' || it == '#' || it == '\\' }
}
