#!/usr/bin/env bash
# Usa apenas o compilador já instalado na bancada; não baixa ferramentas nem roda Gradle.
# Executa também a regressão 0.33 (junção, cache, contrato antigo e OCR em faixas).
set -euo pipefail
AQUI=$(cd "$(dirname "$0")" && pwd)
SRC=${SRC:-$AQUI/../src/main/java/br/maxymus/tradutor}
KOTLINC=${KOTLINC:-/root/.cache/kc-camera/ferramentas/kotlinc/bin/kotlinc}
export SRC
bash "$AQUI/roda033.sh"
SAIDA=$(mktemp -d "${TMPDIR:-/tmp}/tradutor-034.XXXXXX")
trap 'rm -rf "$SAIDA"' EXIT
"$KOTLINC" -jvm-target 17 -include-runtime \
    "$SRC/RegrasSessao.kt" "$AQUI/Teste034.kt" -d "$SAIDA/teste034.jar"
java -jar "$SAIDA/teste034.jar"
