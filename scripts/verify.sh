#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd backend && ../.venv/bin/python -m pytest -q)
node --check web/app.js
./gradlew :core:test :app:assembleDemoDebug :app:assemblePleosDebug --console=plain
