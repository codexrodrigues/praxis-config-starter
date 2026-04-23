#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKSPACE_ROOT="$(cd "$STARTER_ROOT/.." && pwd)"

QUICKSTART_ROOT="${QUICKSTART_ROOT:-$WORKSPACE_ROOT/praxis-api-quickstart}"
ENV_FILE="${ENV_FILE:-$STARTER_ROOT/.env.openai.local.sh}"
JAVA_HOME="${JAVA_HOME:-${PRAXIS_JAVA_HOME:-}}"
PORT="${PORT:-8088}"
PROVIDER="${PROVIDER:-openai}"
STREAM_TIMEOUT_SECONDS="${STREAM_TIMEOUT_SECONDS:-180}"

if [[ -z "$JAVA_HOME" ]]; then
  if [[ -n "${JAVA_HOME_21_X64:-}" ]]; then
    JAVA_HOME="$JAVA_HOME_21_X64"
  elif [[ -d "$HOME/.jdks/jdk-21.0.10+7/Contents/Home" ]]; then
    JAVA_HOME="$HOME/.jdks/jdk-21.0.10+7/Contents/Home"
  else
    JAVA_BIN="$(command -v java || true)"
    if [[ -z "$JAVA_BIN" ]]; then
      echo "java not found. Set JAVA_HOME or PRAXIS_JAVA_HOME." >&2
      exit 1
    fi
    JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
  fi
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "AI env file not found: $ENV_FILE" >&2
  exit 1
fi
if [[ ! -d "$QUICKSTART_ROOT" ]]; then
  echo "Quickstart root not found: $QUICKSTART_ROOT" >&2
  exit 1
fi
if ! command -v perl >/dev/null 2>&1; then
  echo "perl is required to normalize BOM/CRLF env files." >&2
  exit 1
fi

JAR_PATH="${JAR_PATH:-}"
if [[ -z "$JAR_PATH" ]]; then
  JAR_PATH="$(
    find "$QUICKSTART_ROOT/target" -maxdepth 1 -type f -name '*.jar' \
      ! -name '*sources.jar' ! -name '*javadoc.jar' ! -name '*tests.jar' ! -name '*.original' \
      -print 2>/dev/null \
      | while IFS= read -r candidate; do
          mtime="$(stat -f '%m' "$candidate" 2>/dev/null || stat -c '%Y' "$candidate")"
          printf '%s\t%s\n' "$mtime" "$candidate"
        done \
      | sort -rn \
      | head -1 \
      | cut -f2-
  )"
fi
if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
  echo "Quickstart jar not found. Package praxis-api-quickstart first." >&2
  exit 1
fi

if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $PORT is already in use." >&2
  exit 1
fi

set -a
# Normalize secrets file at runtime only. Do not modify or print its contents.
eval "$(LC_ALL=C perl -pe 's/^\xEF\xBB\xBF// if $. == 1; s/\r$//;' "$ENV_FILE" | sed '/^#!/d')"
set +a

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export PORT
export SERVER_PORT="${SERVER_PORT:-$PORT}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
export PRAXIS_AI_PROVIDER="$PROVIDER"
export PRAXIS_AI_GEMINI_PREFER_GENAI_API="${PRAXIS_AI_GEMINI_PREFER_GENAI_API:-false}"
export APP_SECURITY_READ_OPEN="${APP_SECURITY_READ_OPEN:-true}"
export APP_SECURITY_CSRF_DISABLE="${APP_SECURITY_CSRF_DISABLE:-true}"
export APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS="${APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS:-http://localhost:4003,http://127.0.0.1:4003,https://praxisui-dev.web.app}"
export CORS_ALLOWED_ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:4003,http://127.0.0.1:4003,https://praxisui-dev.web.app}"
export PRAXIS_AI_AUTHORING_HTTP_ENABLED="${PRAXIS_AI_AUTHORING_HTTP_ENABLED:-true}"
export PRAXIS_AI_AUTHORING_ARTIFACTS_DIR="${PRAXIS_AI_AUTHORING_ARTIFACTS_DIR:-$STARTER_ROOT/docs/ai/agentic-authoring/proofs}"
export PRAXIS_AI_AUTHORING_CONTRACTS_DIR="${PRAXIS_AI_AUTHORING_CONTRACTS_DIR:-$STARTER_ROOT/docs/ai/agentic-authoring/contracts}"
export PRAXIS_AI_STREAM_PROCESSING_TIMEOUT_SECONDS="$STREAM_TIMEOUT_SECONDS"
export PRAXIS_AI_SECURITY_CORPORATE_MODE="${PRAXIS_AI_SECURITY_CORPORATE_MODE:-false}"
export PRAXIS_AI_SECURITY_ALLOW_HEADER_IDENTITY_IN_LOCAL="${PRAXIS_AI_SECURITY_ALLOW_HEADER_IDENTITY_IN_LOCAL:-true}"
export PRAXIS_AI_SECURITY_LOCAL_DEFAULT_TENANT="${PRAXIS_AI_SECURITY_LOCAL_DEFAULT_TENANT:-desenv}"
export PRAXIS_AI_SECURITY_LOCAL_DEFAULT_USER="${PRAXIS_AI_SECURITY_LOCAL_DEFAULT_USER:-codex-e2e}"
export PRAXIS_AI_SECURITY_LOCAL_DEFAULT_ENVIRONMENT="${PRAXIS_AI_SECURITY_LOCAL_DEFAULT_ENVIRONMENT:-local}"
export PRAXIS_AI_STREAM_AUTH_MODE="${PRAXIS_AI_STREAM_AUTH_MODE:-signed-url-token}"
export PRAXIS_AI_STREAM_AUTH_TOKEN_SECRET="${PRAXIS_AI_STREAM_AUTH_TOKEN_SECRET:-codex-local-e2e-token-secret-20260421}"
export EMBEDDING_PROVIDER="${EMBEDDING_PROVIDER:-mock}"
export PRAXIS_DOMAIN_FEDERATION_PERSISTENCE_ENABLED="${PRAXIS_DOMAIN_FEDERATION_PERSISTENCE_ENABLED:-true}"

if [[ -n "${PRAXIS_AI_OPENAI_MODEL:-}" ]]; then
  export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL="$PRAXIS_AI_OPENAI_MODEL"
fi

echo "Starting quickstart local E2E backend"
echo "  root: $QUICKSTART_ROOT"
echo "  jar:  $JAR_PATH"
echo "  port: $PORT"
echo "  provider: $PROVIDER"
echo "  stream auth: $PRAXIS_AI_STREAM_AUTH_MODE"

cd "$QUICKSTART_ROOT"
exec "$JAVA_HOME/bin/java" -jar "$JAR_PATH"
