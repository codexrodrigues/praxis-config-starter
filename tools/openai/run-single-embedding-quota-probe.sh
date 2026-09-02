#!/usr/bin/env bash
set -euo pipefail

readonly expected_confirmation="ONE_PAID_EMBEDDING_REQUEST"
readonly embedding_model="text-embedding-3-large"
readonly embedding_dimensions=768
readonly embeddings_url="https://api.openai.com/v1/embeddings"

if [[ "${OPENAI_EMBEDDING_PROBE_CONFIRMATION:-}" != "${expected_confirmation}" ]]; then
  echo "Embedding quota probe not authorized. Set OPENAI_EMBEDDING_PROBE_CONFIRMATION=${expected_confirmation}." >&2
  exit 2
fi

if [[ -z "${PRAXIS_AI_OPENAI_API_KEY:-}" ]]; then
  echo "PRAXIS_AI_OPENAI_API_KEY is required." >&2
  exit 2
fi

client_request_id="${OPENAI_EMBEDDING_PROBE_CLIENT_REQUEST_ID:-praxis-embedding-quota-probe-local}"
if [[ ! "${client_request_id}" =~ ^[A-Za-z0-9._:-]{1,128}$ ]]; then
  echo "OPENAI_EMBEDDING_PROBE_CLIENT_REQUEST_ID must be a safe identifier of at most 128 characters." >&2
  exit 2
fi

probe_tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "${probe_tmp_dir}"' EXIT

request_body="$(jq -cn \
  --arg model "${embedding_model}" \
  --arg input "Praxis OpenAI quota diagnostic probe." \
  --argjson dimensions "${embedding_dimensions}" \
  '{model: $model, input: $input, dimensions: $dimensions, encoding_format: "float"}')"

set +e
http_status="$(curl \
  --silent \
  --show-error \
  --request POST \
  --url "${embeddings_url}" \
  --proto '=https' \
  --tlsv1.2 \
  --connect-timeout 15 \
  --max-time 60 \
  --max-redirs 0 \
  --retry 0 \
  --header "Authorization: Bearer ${PRAXIS_AI_OPENAI_API_KEY}" \
  --header 'Content-Type: application/json' \
  --header "X-Client-Request-Id: ${client_request_id}" \
  --user-agent 'praxis-openai-embedding-quota-probe/1.0' \
  --data-binary "${request_body}" \
  --dump-header "${probe_tmp_dir}/response.headers" \
  --output "${probe_tmp_dir}/response.json" \
  --write-out '%{http_code}')"
curl_exit=$?
set -e

if (( curl_exit != 0 )); then
  echo "OpenAI embedding quota probe had a transport failure (curl exit ${curl_exit}); no retry was attempted." >&2
  exit 1
fi

if [[ ! "${http_status}" =~ ^[0-9]{3}$ ]]; then
  echo "OpenAI embedding quota probe returned an invalid HTTP status; no retry was attempted." >&2
  exit 1
fi

provider_request_id="$(awk '
  BEGIN { IGNORECASE = 1 }
  /^x-request-id:/ {
    sub(/^[^:]*:[[:space:]]*/, "")
    sub(/\r$/, "")
    print
    exit
  }
' "${probe_tmp_dir}/response.headers")"
if [[ ! "${provider_request_id}" =~ ^[A-Za-z0-9._:-]{1,128}$ ]]; then
  provider_request_id="unavailable"
fi

echo "OpenAI single embedding quota probe completed."
echo "http_status=${http_status}"
echo "model=${embedding_model}"
echo "dimensions=${embedding_dimensions}"
echo "client_request_id=${client_request_id}"
echo "x_request_id=${provider_request_id}"

if [[ "${http_status}" =~ ^2[0-9]{2}$ ]]; then
  usage_summary="$(jq -c '
    {
      prompt_tokens: (.usage.prompt_tokens // null),
      total_tokens: (.usage.total_tokens // null)
    }
  ' "${probe_tmp_dir}/response.json" 2>/dev/null || printf '{"prompt_tokens":null,"total_tokens":null}')"
  echo "result=success"
  echo "usage=${usage_summary}"
  exit 0
fi

safe_error="$(jq -c '
  if (.error | type) == "object" then
    {
      error: {
        type: (.error.type // null),
        code: (.error.code // null),
        param: (.error.param // null)
      }
    }
  else
    {error: {type: "unparseable_provider_error", code: null, param: null}}
  end
' "${probe_tmp_dir}/response.json" 2>/dev/null || printf '{"error":{"type":"unparseable_provider_error","code":null,"param":null}}')"

echo "result=provider_error"
echo "provider_error=${safe_error}"
echo "The provider message and raw response body were intentionally omitted from logs."
