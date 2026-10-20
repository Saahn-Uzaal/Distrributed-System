#!/usr/bin/env bash
# T06 — Generate dev RSA keypair cho JWT RS256 (contract api-contracts.md §2).
# Private key CHỈ nằm ở identity-service runtime; keys/ được gitignore (*.pem).
# Idempotent: bỏ qua nếu đã có, dùng --force để tạo lại.
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

if [[ $FORCE -eq 0 && -f "$PRIV" && -f "$PUB" ]]; then
  echo "Keys already exist in $KEYS_DIR (use --force to regenerate)."
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIV"
openssl pkey -in "$PRIV" -pubout -out "$PUB"
chmod 600 "$PRIV"

echo "Generated dev JWT keys in $KEYS_DIR (gitignored). Do not commit the private key."
