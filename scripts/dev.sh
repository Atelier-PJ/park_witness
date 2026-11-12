#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -d .venv ]]; then python3.11 -m venv .venv; fi
.venv/bin/pip install -r backend/requirements.txt
cd backend
exec ../.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port "${PORT:-8000}"
