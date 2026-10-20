#!/usr/bin/env bash
# Testes JVM do app do Coordenador (regra pura de atualização), sem Gradle nem aparelho.
# Uso: MEMCAP=1800M /opt/pocket-lm/heavy.sh coordenador/testes-jvm/roda.sh
set -euo pipefail
AQUI=$(cd "$(dirname "$0")" && pwd)
SRC=${SRC:-$AQUI/../src/main/java/br/maxymus/coordenador}
C=/root/.cache/kc-camera
KOTLINC=${KOTLINC:-$C/ferramentas/kotlinc/bin/kotlinc}
SAIDA=$(mktemp -d "${TMPDIR:-/tmp}/coordenador-teste.XXXXXX")
trap 'rm -rf "$SAIDA"' EXIT
JAVA_OPTS="-Xmx1400m -Xms256m" "$KOTLINC" -no-jdk -no-reflect -jvm-target 17 \
    -cp "$C/ferramentas/android.jar" "$SRC/RegraAtualizacao.kt" "$AQUI/TesteCoordenador.kt" -d "$SAIDA"
java -cp "$SAIDA:$C/ferramentas/kotlinc/lib/kotlin-stdlib.jar" br.maxymus.coordenador.TesteCoordenadorKt
