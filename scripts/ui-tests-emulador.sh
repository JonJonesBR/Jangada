#!/usr/bin/env bash
# Roda os testes do emulador (chamado pelo ui-tests.yml, já dentro do emulador).
# Uso: ui-tests-emulador.sh <debug|minificado|ambos> <capturas:true|false> [classe]
set -u
VARIANTE="${1:-debug}"; CAPTURAS="${2:-false}"; CLASSE="${3:-}"
OUT=app/build/reports/androidTests
mkdir -p "$OUT"

if [ "$CAPTURAS" = "true" ]; then
  ./gradlew --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.jonjonesbr.jangada.ui.ReadmeShotsTest \
    -Pandroid.testInstrumentationRunnerArguments.capturas=true || exit 1
  adb pull /data/local/tmp/jangada-capturas "$OUT/capturas"
  exit 0
fi

ARGS=()
[ -n "$CLASSE" ] && ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=$CLASSE")

rodar() { # $1 = nome (debug|minificado), resto = argumentos do gradle
  local nome="$1"; shift
  echo "=== Testes no APK $nome ==="
  # Cada variante começa com o emulador limpo do app: desinstala o que a anterior deixou.
  adb uninstall io.github.jonjonesbr.jangada >/dev/null 2>&1
  adb uninstall io.github.jonjonesbr.jangada.test >/dev/null 2>&1
  if ! timeout "$LIMITE" ./gradlew --no-daemon "$@" "${ARGS[@]}"; then
    mkdir -p "$OUT/$nome"
    adb logcat -d > "$OUT/$nome/logcat.txt"
    adb pull /data/local/tmp/jangada-falhas "$OUT/$nome/telas" || true
    return 1
  fi
}

# Limite por variante: um teste preso não pode gastar a hora inteira do job sem deixar o logcat.
LIMITE=40m; [ -n "$CLASSE" ] && LIMITE=15m

falhou=0
case "$VARIANTE" in
  debug|ambos) rodar debug :app:connectedDebugAndroidTest || falhou=1 ;;
esac
case "$VARIANTE" in
  minificado|ambos) rodar minificado -PtestarMinificado :app:connectedMinifiedAndroidTest || falhou=1 ;;
esac
exit $falhou
