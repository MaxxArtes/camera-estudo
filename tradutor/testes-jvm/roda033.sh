#!/usr/bin/env bash
# Testes JVM do Tradutor 0.33 (e o harness_idioma de sempre) contra os fontes reais, sem Gradle, aparelho nem ML Kit.
# Uso: SRC=<pasta dos .kt do tradutor> MEMCAP=1800M /opt/pocket-lm/heavy.sh tradutor/testes-jvm/roda033.sh
# Precisa das ferramentas em /root/.cache/kc-camera (kotlinc, android.jar, classpath_tradutor.txt, stub BuildConfig) e do
# TesteRegra.kt do harness_idioma (HARNESS=<pasta>, padrao a da bancada).
C=/root/.cache/kc-camera
F=$C/ferramentas
AQUI=$(cd "$(dirname "$0")" && pwd)
HARNESS=${HARNESS:-$F/harness_idioma}
SRC=${SRC:-$AQUI/../src/main/java/br/maxymus/tradutor}
ls "$SRC"/*.kt > /dev/null 2>&1 || { echo "SRC sem arquivos .kt: $SRC"; exit 2; }
CP=$F/android.jar:$(cat $C/classpath_tradutor.txt)
OUT=${OUT:-$C/out_harness_033}
EXTRAS=""
for f in Voz LeituraCapitulo RegrasTexto Falas Avaliacao; do [ -f "$SRC/$f.kt" ] && EXTRAS="$EXTRAS $SRC/$f.kt"; done
rm -rf $OUT; mkdir -p $OUT
JAVA_OPTS="-Xmx1400m -Xms256m" $F/kotlinc/bin/kotlinc -no-jdk -no-reflect -jvm-target 17 -opt-in=kotlin.RequiresOptIn \
  -cp "$CP" -d $OUT $F/stub_tradutor/BuildConfig.kt $SRC/Traducao.kt $SRC/Telemetria.kt $SRC/Idiomas.kt $SRC/Captura.kt $EXTRAS \
  $HARNESS/TesteRegra.kt $AQUI/Teste033.kt 2>&1 \
  | grep -v -E "^WARNING: (A restricted|java.lang.System|Use --enable|Restricted methods|A terminally|sun.misc|Please consider)|^$"
[ "${PIPESTATUS[0]}" = 0 ] || { echo "compilacao do harness falhou"; exit 1; }
rc=0
java -cp "$OUT:$F/kotlinc/lib/kotlin-stdlib.jar:$CP" br.maxymus.tradutor.TesteRegraKt || rc=1
java -cp "$OUT:$F/kotlinc/lib/kotlin-stdlib.jar:$CP" br.maxymus.tradutor.Teste033Kt || rc=1
exit $rc
