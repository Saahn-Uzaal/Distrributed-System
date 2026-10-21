#!/usr/bin/env bash
# T06 — Generate dev RSA keypair cho JWT RS256 (contract api-contracts.md §2).
# Private key CHỈ nằm ở identity-service runtime; keys/ được gitignore (*.pem).
# Idempotent: bỏ qua nếu đã có, dùng --force để tạo lại.
# F26 fix: private còn mà public THIẾU → tái tạo public TỪ CÙNG private (token cũ vẫn verify);
# chỉ tạo private MỚI khi chưa có private hoặc chạy --force.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
KEYS_DIR="$ROOT/keys"
PRIV="$KEYS_DIR/jwt-private.pem"
PUB="$KEYS_DIR/jwt-public.pem"

FORCE=0
if [[ "${1:-}" == "--force" ]]; then FORCE=1; fi

command -v openssl >/dev/null 2>&1 || { echo "ERROR: openssl not found. Install OpenSSL and retry." >&2; exit 1; }

mkdir -p "$KEYS_DIR"

if [[ $FORCE -eq 0 && -f "$PRIV" ]]; then
  if [[ ! -f "$PUB" ]]; then
    # Public bị mất nhưng private còn: khôi phục public từ private — fingerprint private
    # không đổi, mọi token đã ký trước đó vẫn verify được.
    openssl pkey -in "$PRIV" -pubout -out "$PUB"
    echo "Regenerated $PUB from existing private key (private unchanged; old tokens still valid)."
  else
    echo "Keys already exist in $KEYS_DIR (use --force to regenerate)."
  fi
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIV"
openssl pkey -in "$PRIV" -pubout -out "$PUB"
chmod 600 "$PRIV"

echo "Generated dev JWT keys in $KEYS_DIR (gitignored). Do not commit the private key."
