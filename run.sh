#!/usr/bin/env bash
# Loads .env, then starts the site. Usage: bash run.sh   (or: bash run.sh compile)
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
exec sbt "${@:-run}"
