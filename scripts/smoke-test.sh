#!/usr/bin/env bash
# Tên theo spec §12; thân script nằm ở smoke-trip.sh (T26).
exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/smoke-trip.sh" "$@"
