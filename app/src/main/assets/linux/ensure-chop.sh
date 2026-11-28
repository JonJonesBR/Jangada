#!/usr/bin/env bash
# Instala ou atualiza o chop v1.39.6 em ~/.local/bin/chop com conferencia SHA-256.
set -eu

case "$(uname -m)" in
  aarch64|arm64)
    ca=arm64
    cs=a36beb2c1965fdec189fd0d740c789922d2ef0dff5295102390bbf612d0f7454
    ;;
  x86_64|amd64)
    ca=amd64
    cs=db9fbd51faa4b5ba614e0fb43b2243ba682421319a55b46f4089f1201b29fab7
    ;;
  *)
    exit 0
    ;;
esac

mkdir -p "$HOME/.local/bin"
if [ -x "$HOME/.local/bin/chop" ] && [ "$(sha256sum "$HOME/.local/bin/chop" 2>/dev/null | cut -d' ' -f1)" = "$cs" ]; then
  exit 0
fi

tmp="$HOME/.local/bin/.chop.tmp.$$"
trap 'rm -f "$tmp"' EXIT
curl -fsSL --connect-timeout 20 --retry 3 --speed-limit 1000 --speed-time 60 \
  "https://github.com/AgusRdz/chop/releases/download/v1.39.6/chop-linux-$ca" -o "$tmp"
printf '%s  %s\n' "$cs" "$tmp" | sha256sum -c - >/dev/null
chmod 755 "$tmp"
mv -f "$tmp" "$HOME/.local/bin/chop"
