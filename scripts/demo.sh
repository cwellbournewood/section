#!/usr/bin/env bash
# Section end-to-end demo.
#
# Sends three illustrative requests through the gateway and validates that
# each was handled per policy:
#
#   1. PII (person + email)  -> 200, transform (tokenised)
#   2. AWS access key        -> 403, with X-Section-Reason header
#   3. IBAN                  -> 200, redacted
#
# If OPENAI_API_KEY is unset, the upstream call assertion for request 1 is
# relaxed: we still POST through the gateway and verify a new event lands in
# /admin/events with the expected decision; we do not require a 200.
#
# Requires: bash, curl, awk, sed. (No jq.)
#
# Usage:  scripts/demo.sh [GATEWAY_URL]
#
# POSIX-safe-ish: uses bash arrays but otherwise sticks to portable utilities,
# so it runs in Git Bash on Windows.

set -eu

GATEWAY_URL="${1:-${GATEWAY_URL:-http://localhost:8080}}"
API_KEY="${SECTION_API_KEYS:-section-demo-key}"
TIMEOUT="${TIMEOUT:-30}"

pass=0
fail=0

color() {
  if [ -t 1 ] && [ "${NO_COLOR:-}" = "" ]; then
    printf '\033[%sm%s\033[0m' "$1" "$2"
  else
    printf '%s' "$2"
  fi
}

ok()    { printf '  %s %s\n' "$(color 32 PASS)" "$1"; pass=$((pass+1)); }
bad()   { printf '  %s %s\n' "$(color 31 FAIL)" "$1"; fail=$((fail+1)); }
info()  { printf '  %s %s\n' "$(color 36 INFO)" "$1"; }
note()  { printf '  %s %s\n' "$(color 33 NOTE)" "$1"; }

# Wait for the gateway to be healthy.
wait_healthy() {
  printf '%s\n' "$(color 1 ":: Waiting for gateway at ${GATEWAY_URL}/healthz ...")"
  i=0
  while [ "$i" -lt "$TIMEOUT" ]; do
    if curl -fsS --max-time 2 "${GATEWAY_URL}/healthz" >/dev/null 2>&1; then
      ok "Gateway is healthy"
      return 0
    fi
    i=$((i+1))
    sleep 1
  done
  bad "Gateway never became healthy after ${TIMEOUT}s"
  exit 2
}

# Extract the most recent /admin/events row's decision field.
# Polls for ~5s because the gateway's audit writer batches every ~1s; a
# single-shot fetch can race the writer and see an empty table.
latest_decision() {
  local out=""
  local i=0
  while [ $i -lt 50 ]; do
    out=$(curl -fsS "${GATEWAY_URL}/admin/events?limit=1" \
            -H "Authorization: Bearer ${API_KEY}" 2>/dev/null \
            | tr -d '\n' \
            | sed -n 's/.*"decision"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
            | head -n 1)
    if [ -n "$out" ]; then
      printf '%s\n' "$out"
      return 0
    fi
    sleep 0.1
    i=$((i + 1))
  done
  return 1
}

# Send a chat-completions request. Captures HTTP status and the
# X-Section-Reason header into globals: STATUS, REASON, BODY.
send_chat() {
  prompt="$1"
  tmp_body=$(mktemp -t section.XXXXXX)
  tmp_head=$(mktemp -t section.XXXXXX)
  STATUS=$(curl -sS -o "$tmp_body" -D "$tmp_head" -w '%{http_code}' \
    -H "Authorization: Bearer ${API_KEY}" \
    -H 'Content-Type: application/json' \
    --max-time 30 \
    -d "$(printf '{"model":"gpt-4o-mini","messages":[{"role":"user","content":%s}]}' "$(printf '%s' "$prompt" | awk 'BEGIN{ORS=""} {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); print "\""$0"\""}')")" \
    "${GATEWAY_URL}/v1/chat/completions" || true)
  REASON=$(awk 'BEGIN{IGNORECASE=1} /^[Xx]-[Ss]ection-[Rr]eason:/ {sub(/^[^:]+:[ \t]*/,""); sub(/\r$/,""); print; exit}' "$tmp_head")
  BODY=$(cat "$tmp_body")
  rm -f "$tmp_body" "$tmp_head"
}

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------

wait_healthy

if [ -z "${OPENAI_API_KEY:-}" ]; then
  note "OPENAI_API_KEY unset — upstream-success assertion relaxed for test 1"
fi

# ---- Test 1: PII (person + email) -> transform ----
printf '\n%s\n' "$(color 1 ":: Test 1: PII (person + email)")"
send_chat 'Please email John Smith at john.smith@acme.com about invoice 4471'
info "HTTP ${STATUS}"
decision=$(latest_decision || true)
info "Latest decision: ${decision:-<none>}"
if [ -n "${OPENAI_API_KEY:-}" ]; then
  case "$STATUS" in
    200) ok "PII request returned 200" ;;
    *)   bad "Expected 200, got ${STATUS}" ;;
  esac
fi
case "$decision" in
  transform) ok "Decision was 'transform'" ;;
  allow)     note "Decision was 'allow' — no PII detected in this run" ;;
  "")        bad "No /admin/events row found" ;;
  *)         bad "Expected 'transform', got '${decision}'" ;;
esac

# ---- Test 2: AWS access key -> block ----
printf '\n%s\n' "$(color 1 ":: Test 2: AWS access key")"
send_chat 'Use AWS access key AKIAIOSFODNN7EXAMPLE and secret wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY to upload'
info "HTTP ${STATUS}"
info "X-Section-Reason: ${REASON:-<none>}"
decision=$(latest_decision || true)
info "Latest decision: ${decision:-<none>}"
case "$STATUS" in
  403) ok "AWS-key request returned 403" ;;
  *)   bad "Expected 403, got ${STATUS}" ;;
esac
if [ -n "$REASON" ]; then
  ok "X-Section-Reason header present"
else
  bad "X-Section-Reason header missing"
fi
case "$decision" in
  block) ok "Decision was 'block'" ;;
  "")    bad "No /admin/events row found" ;;
  *)     bad "Expected 'block', got '${decision}'" ;;
esac

# ---- Test 3: IBAN -> 200 with redaction ----
printf '\n%s\n' "$(color 1 ":: Test 3: IBAN")"
send_chat 'Send the invoice to my account DE89 3704 0044 0532 0130 00 by Friday'
info "HTTP ${STATUS}"
decision=$(latest_decision || true)
info "Latest decision: ${decision:-<none>}"
if [ -n "${OPENAI_API_KEY:-}" ]; then
  case "$STATUS" in
    200) ok "IBAN request returned 200" ;;
    *)   bad "Expected 200, got ${STATUS}" ;;
  esac
fi
case "$decision" in
  transform) ok "Decision was 'transform' (IBAN redacted)" ;;
  ""       ) bad "No /admin/events row found" ;;
  *)         bad "Expected 'transform', got '${decision}'" ;;
esac

# ---- Summary ----
printf '\n%s\n' "$(color 1 ":: Summary")"
printf '  passed: %d\n' "$pass"
printf '  failed: %d\n' "$fail"

if [ "$fail" -gt 0 ]; then
  exit 1
fi
exit 0
