#!/usr/bin/env bash
# Section red-team runner.
#
# Reads scripts/redteam/cases.json, POSTs each case through the gateway,
# pulls the latest /admin/events row, and asserts on decision +
# findings categories.
#
# Usage:
#   scripts/redteam/run.sh [GATEWAY_URL]
#
# Env:
#   SECTION_API_KEY   admin API key (default: section-demo-key)
#   CASES_FILE          path to manifest (default: scripts/redteam/cases.json)
#   JUNIT_OUT           if set, write JUnit XML for CI ingestion
#
# Requires: bash, curl, python3 (used for robust JSON handling).

set -eu

GATEWAY_URL="${1:-${GATEWAY_URL:-http://localhost:8080}}"
API_KEY="${SECTION_API_KEY:-${SECTION_API_KEYS%%,*}}"
API_KEY="${API_KEY:-section-demo-key}"
CASES_FILE="${CASES_FILE:-scripts/redteam/cases.json}"
JUNIT_OUT="${JUNIT_OUT:-}"

if [ ! -f "$CASES_FILE" ]; then
  echo "ERROR: cases file not found: $CASES_FILE" >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required for the red-team runner" >&2
  exit 2
fi

export GATEWAY_URL API_KEY CASES_FILE JUNIT_OUT

python3 - <<'PY'
import json
import os
import sys
import time
import urllib.error
import urllib.request
from xml.sax.saxutils import escape as xml_escape

GATEWAY = os.environ["GATEWAY_URL"].rstrip("/")
KEY = os.environ["API_KEY"]
CASES = os.environ["CASES_FILE"]
JUNIT = os.environ.get("JUNIT_OUT") or ""

def http_json(method, path, body=None, timeout=30):
    url = f"{GATEWAY}{path}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        method=method,
        headers={
            "Authorization": f"Bearer {KEY}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        data=data,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
            return resp.status, resp.read().decode("utf-8", errors="replace"), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace"), dict(e.headers or {})

def latest_event():
    status, body, _ = http_json("GET", "/admin/events?limit=1")
    if status >= 400:
        return None
    try:
        rows = json.loads(body)
    except json.JSONDecodeError:
        return None
    if isinstance(rows, dict):
        rows = rows.get("items") or rows.get("events") or []
    if not rows:
        return None
    return rows[0]

with open(CASES, "r", encoding="utf-8") as f:
    manifest = json.load(f)

cases = manifest["cases"]
print(f":: red-team — {len(cases)} case(s) against {GATEWAY}")

results = []
t0 = time.time()
for c in cases:
    name = c["name"]
    expected_decision = c["expected_decision"]
    expected_findings = set(c.get("expected_findings_contain") or [])
    prompt = c["prompt"]
    case_t0 = time.time()
    failures = []

    req_body = {
        "model": "gpt-4o-mini",
        "messages": [{"role": "user", "content": prompt}],
    }
    status, body, headers = http_json("POST", "/v1/chat/completions", req_body)

    # Give the audit row a moment to land.
    ev = None
    for _ in range(5):
        ev = latest_event()
        if ev is not None:
            break
        time.sleep(0.2)

    actual_decision = (ev or {}).get("decision")
    actual_findings = set()
    for fnd in (ev or {}).get("findings", []) or []:
        # Findings serialise a canonical `<category>.<thing>` id in `label`
        # (e.g. "credential.aws_access_key"). They have never carried a
        # `category` or `type` key, so reading those returned None for every
        # finding and every case reported "got []" — including cases whose
        # decision was already correct. Keep the old keys as a fallback for
        # audit rows written before the label taxonomy landed.
        label = fnd.get("label") or fnd.get("category") or fnd.get("type")
        if label:
            actual_findings.add(label)

    if actual_decision != expected_decision:
        failures.append(
            f"decision: expected {expected_decision!r}, got {actual_decision!r}"
        )
    missing = expected_findings - actual_findings
    if missing:
        failures.append(
            f"findings: missing {sorted(missing)} (got {sorted(actual_findings)})"
        )

    elapsed = time.time() - case_t0
    ok = not failures
    results.append({
        "name": name,
        "class": c.get("class", ""),
        "ok": ok,
        "elapsed": elapsed,
        "status": status,
        "actual_decision": actual_decision,
        "actual_findings": sorted(actual_findings),
        "failures": failures,
    })
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {name:35s}  decision={actual_decision!s:9s}  ({elapsed:.2f}s)")
    for f in failures:
        print(f"         {f}")

total_elapsed = time.time() - t0
passed = sum(1 for r in results if r["ok"])
failed = len(results) - passed
print(f":: summary — {passed} pass, {failed} fail, {total_elapsed:.1f}s total")

if JUNIT:
    parts = [
        f'<testsuite name="section.redteam" tests="{len(results)}" '
        f'failures="{failed}" time="{total_elapsed:.3f}">'
    ]
    for r in results:
        parts.append(
            f'  <testcase classname="redteam.{xml_escape(r["class"]) or "case"}" '
            f'name="{xml_escape(r["name"])}" time="{r["elapsed"]:.3f}">'
        )
        if not r["ok"]:
            msg = xml_escape("; ".join(r["failures"]))
            parts.append(f'    <failure message="{msg}">{msg}</failure>')
        parts.append("  </testcase>")
    parts.append("</testsuite>")
    with open(JUNIT, "w", encoding="utf-8") as fh:
        fh.write("\n".join(parts))
    print(f":: junit -> {JUNIT}")

sys.exit(0 if failed == 0 else 1)
PY
