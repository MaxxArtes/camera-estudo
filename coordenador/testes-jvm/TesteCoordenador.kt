package br.maxymus.coordenador

private var falhas = 0
private var total = 0

private fun confere(nome: String, ok: Boolean) {
    total++
    if (!ok) falhas++
    println((if (ok) "  OK     " else "  FALHOU ") + nome)
}

fun main() {
    val dia = RegraAtualizacao.ESPERA_ADIADA_MS
    val agora = 10 * dia
    println("1) quando oferecer a versão nova")
    confere("mesma versão não oferece", !RegraAtualizacao.oferecer(3, 3, -1, 0, agora))
    confere("versão mais velha publicada não oferece", !RegraAtualizacao.oferecer(3, 2, -1, 0, agora))
    confere("versão nova oferece", RegraAtualizacao.oferecer(3, 4, -1, 0, agora))
    confere("'Depois' cala a mesma versão por um dia", !RegraAtualizacao.oferecer(3, 4, 4, agora - dia + 1, agora))
    confere("passado um dia, oferece de novo", RegraAtualizacao.oferecer(3, 4, 4, agora - dia, agora))
    confere("versão ainda mais nova que a adiada oferece na hora", RegraAtualizacao.oferecer(3, 5, 4, agora, agora))
    confere("relógio voltou para antes do adiamento: oferece", RegraAtualizacao.oferecer(3, 4, 4, agora + 1000, agora))

    println("2) só APK do prefixo da CI")
    val p = RegraAtualizacao.PREFIXO_APK
    confere("APK fixo da CI", RegraAtualizacao.apkConfiavel(p + "coordenador.apk"))
    confere("APK versionado da CI", RegraAtualizacao.apkConfiavel(p + "coordenador-coordenador-v0.2-build3.apk"))
    confere("outro host recusa", !RegraAtualizacao.apkConfiavel("https://exemplo.com/coordenador/coordenador.apk"))
    confere("http recusa", !RegraAtualizacao.apkConfiavel(p.replace("https://", "http://") + "coordenador.apk"))
    confere("outra pasta do bucket recusa", !RegraAtualizacao.apkConfiavel(p.removeSuffix("coordenador/") + "tradutor/tradutor.apk"))
    confere("subir de pasta recusa", !RegraAtualizacao.apkConfiavel(p + "../tradutor/tradutor.apk"))
    confere("subpasta recusa", !RegraAtualizacao.apkConfiavel(p + "x/coordenador.apk"))
    confere("consulta na URL recusa", !RegraAtualizacao.apkConfiavel(p + "coordenador.apk?x=.apk"))
    confere("não-APK recusa", !RegraAtualizacao.apkConfiavel(p + "releases.json"))
    confere("prefixo parecido recusa", !RegraAtualizacao.apkConfiavel(p.removeSuffix("/") + "-falso/coordenador.apk"))

    println(if (falhas == 0) "TUDO CERTO (coordenador): $total verificações" else "FALHARAM $falhas de $total")
    if (falhas > 0) kotlin.system.exitProcess(1)
}
