#!/usr/bin/env bash
# One-command burst test: hot-seat storm, outcome distribution and final reconciliation.
#
#   ADMIN_TOKEN=<admin credential> ./burst.sh https://your-service.example.com
#   ./burst.sh                         # local service on http://localhost:8080 (ADMIN_TOKEN is read from .env)
#   ./burst.sh URL --quick             # 2,000 requests instead of 20,000
#   ./burst.sh URL --one-seat          # every request fights over one single seat
#   ./burst.sh --help                  # all options
#
# Needs a JDK 11+ (the test is a single dependency-free Java file) or, failing that, Docker.
# Exit code: 0 = every check passed, 1 = a correctness check failed, 2 = the test could not run.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_file="$here/scripts/Burst.java"

if [ ! -f "$source_file" ]; then
  echo "burst.sh: $source_file not found" >&2
  exit 2
fi

# Local convenience: reuse the admin token that `make env` generated (it is never printed).
if [ -z "${ADMIN_TOKEN:-}" ] && [ -f "$here/.env" ]; then
  token="$(grep -E '^ADMIN_TOKEN=' "$here/.env" | head -n 1 | cut -d= -f2- || true)"
  if [ -n "$token" ]; then
    export ADMIN_TOKEN="$token"
    echo "(using ADMIN_TOKEN from .env)" >&2
  fi
fi

# Major version of a java launcher: "1.8.0_292" -> 8, "17.0.9" -> 17, "21-ea" -> 21.
java_major() {
  local v
  v="$("$1" -version 2>&1 | awk -F'"' '/ version "/ {print $2; exit}')" || return 1
  case "$v" in 1.*) v="${v#1.}" ;; esac
  echo "${v%%[.-]*}"
}

for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java || true)"; do
  [ -n "$candidate" ] && [ -x "$candidate" ] || continue
  major="$(java_major "$candidate" || true)"
  if [ -n "$major" ] && [ "$major" -ge 11 ] 2>/dev/null; then
    exec "$candidate" "$source_file" "$@"
  fi
done

if command -v docker >/dev/null 2>&1; then
  echo "(no JDK 11+ found - running the test in a Docker container)" >&2
  args=()
  for a in "$@"; do
    # inside the container "localhost" is the container itself; the host is host.docker.internal
    args+=("${a//localhost/host.docker.internal}")
    args[${#args[@]}-1]="${args[${#args[@]}-1]//127.0.0.1/host.docker.internal}"
  done
  base_url="${BASE_URL:-}"
  base_url="${base_url//localhost/host.docker.internal}"
  base_url="${base_url//127.0.0.1/host.docker.internal}"
  exec docker run --rm --add-host=host.docker.internal:host-gateway \
    -e ADMIN_TOKEN -e "BASE_URL=${base_url}" \
    -v "$here/scripts:/burst:ro" eclipse-temurin:21-jdk java /burst/Burst.java "${args[@]+"${args[@]}"}"
fi

echo "burst.sh: need a JDK 11+ (java) or Docker to run the burst test." >&2
exit 2
