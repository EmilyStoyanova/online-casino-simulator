#!/usr/bin/env bash
# Runs SQL from standard input against the Neon database configured in `.env.neon`, inside one
# READ ONLY transaction that is rolled back, with psql from the PostgreSQL image of
# docker-compose.yml (no local psql needed). Procedure: docs/database.md, "Neon".
#
# - Credentials go from the file straight into the container's environment (`--env-file`). They
#   never appear in a command line, the shell history or the output.
# - TLS: sslmode=verify-full against the image's system CA bundle (Neon uses Let's Encrypt), and
#   SCRAM channel binding is required, like the backend's `neon` profile.
# - Read-only: PostgreSQL rejects any write ("cannot execute ... in a read-only transaction").
#
# Usage (repository root, Git Bash):
#   infrastructure/neon/readonly-query.sh < infrastructure/neon/preflight.sql
set -euo pipefail

cd "$(dirname "$0")/../.."
env_file=.env.neon

fail() {
  echo "$1" >&2
  exit 1
}

[ -f "$env_file" ] || fail "Missing $env_file in the repository root (template: .env.neon.example)."
# Checks look at names and shapes only; values are never printed.
for name in NEON_DB_HOST NEON_DB_NAME NEON_DB_USERNAME NEON_DB_PASSWORD; do
  grep -q "^$name=." "$env_file" || fail "$name is missing or empty in $env_file."
done
! grep -Eq "^NEON_DB_[A-Z]+=[\"']" "$env_file" || fail "Remove the quotes around the values in $env_file."
! grep -q "^NEON_DB_HOST=.*-pooler" "$env_file" || fail "NEON_DB_HOST is a pooled (-pooler) host: use the direct endpoint."

# MSYS_NO_PATHCONV: Git Bash must pass the arguments to docker unchanged.
{ echo 'BEGIN TRANSACTION READ ONLY;'; cat; echo 'ROLLBACK;'; } |
  MSYS_NO_PATHCONV=1 docker run --rm -i --env-file "$env_file" postgres:17.11-alpine sh -c '
    export PGHOST="$NEON_DB_HOST" PGDATABASE="$NEON_DB_NAME" PGUSER="$NEON_DB_USERNAME" PGPASSWORD="$NEON_DB_PASSWORD"
    unset NEON_DB_PASSWORD
    export PGSSLMODE=verify-full PGSSLROOTCERT=system PGCHANNELBINDING=require PGCONNECT_TIMEOUT=20
    exec psql -X -q -v ON_ERROR_STOP=1 -P pager=off'
