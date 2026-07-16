#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STARTER_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ARTIFACTS_DIR="${ARTIFACTS_DIR:-$STARTER_ROOT/artifacts/local-e2e/authoring-turn-state-machine-$(date +%Y%m%d-%H%M%S)}"

if ! command -v mvn >/dev/null 2>&1; then
  echo "mvn is required." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required." >&2
  exit 1
fi

mkdir -p "$ARTIFACTS_DIR"

test_selector="AiTurnEventServiceTest,AiTurnRepositoryContractTest,AiStreamTransactionContractTest,AiStreamRuntimeTransactionManagerIntegrationTest,AgenticAuthoringTurnStreamServiceTest,AgenticAuthoringTurnStreamHttpSseIntegrationTest,AgenticAuthoringTurnStreamSignedTokenIntegrationTest,AgenticAuthoringTurnStreamSecurityChainIntegrationTest,AiStreamAccessTokenServiceTest,AgenticAuthoringPreviewServiceTest#previewFailsClosedWhenCanonicalCreateRequestSchemaIsUnavailable"

echo "[1/2] running the canonical authoring-turn state-machine matrix"
set +e
(
  cd "$STARTER_ROOT"
  mvn -q -Dtest="$test_selector" test
) 2>&1 | tee "$ARTIFACTS_DIR/maven.log"
maven_status="${PIPESTATUS[0]}"
set -e
if [[ "$maven_status" -ne 0 ]]; then
  echo "State-machine matrix failed. See $ARTIFACTS_DIR/maven.log" >&2
  exit "$maven_status"
fi

echo "[2/2] certifying mandatory evidence and writing the local report"
STARTER_ROOT="$STARTER_ROOT" ARTIFACTS_DIR="$ARTIFACTS_DIR" python3 <<'PY'
import datetime
import json
import os
import pathlib
import xml.etree.ElementTree as ET

root = pathlib.Path(os.environ["STARTER_ROOT"])
artifacts = pathlib.Path(os.environ["ARTIFACTS_DIR"])
report_dir = root / "target" / "surefire-reports"

report_names = [
    "org.praxisplatform.config.service.AiTurnEventServiceTest",
    "org.praxisplatform.config.repository.AiTurnRepositoryContractTest",
    "org.praxisplatform.config.service.AiStreamTransactionContractTest",
    "org.praxisplatform.config.service.AiStreamRuntimeTransactionManagerIntegrationTest",
    "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamServiceTest",
    "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamHttpSseIntegrationTest",
    "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamSignedTokenIntegrationTest",
    "org.praxisplatform.config.controller.AgenticAuthoringTurnStreamSecurityChainIntegrationTest",
    "org.praxisplatform.config.service.AiStreamAccessTokenServiceTest",
    "org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewServiceTest",
]

totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "timeSeconds": 0.0}
executed = set()
for report_name in report_names:
    path = report_dir / f"TEST-{report_name}.xml"
    if not path.exists():
        raise SystemExit(f"Missing Surefire report: {path}")
    suite = ET.parse(path).getroot()
    for key in ("tests", "failures", "errors", "skipped"):
        totals[key] += int(suite.attrib.get(key, "0"))
    totals["timeSeconds"] += float(suite.attrib.get("time", "0"))
    for case in suite.findall("testcase"):
        executed.add(f"{case.attrib.get('classname')}#{case.attrib.get('name')}")

evidence = {
    "idempotentResume": "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamServiceTest#startWithExistingClientTurnIdReplaysExistingStreamWithoutProcessingAgain",
    "sseReplay": "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamHttpSseIntegrationTest#shouldReplayOnlyEventsAfterLastEventId",
    "cancelResultRace": "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamHttpSseIntegrationTest#shouldKeepSingleTerminalWhenCancelRacesWithAuthoringResult",
    "timeoutBlocksLateCompletion": "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamServiceTest#timeoutTerminalPreventsLateCompletionAndPreview",
    "schemaUnavailableFailsClosed": "org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewServiceTest#previewFailsClosedWhenCanonicalCreateRequestSchemaIsUnavailable",
    "appendAfterTerminalRejected": "org.praxisplatform.config.service.AiTurnEventServiceTest#shouldRejectAppendingAfterTerminalEvent",
    "crossInstanceTerminalReconciliation": "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamServiceTest#eventSinkTerminalReachedReconcilesPersistedTerminalEventFromAnotherInstance",
    "configTransactionBoundary": "org.praxisplatform.config.service.AiStreamRuntimeTransactionManagerIntegrationTest#shouldUseConfigTransactionManagerAtRuntimeForAppendAndReplay",
    "signedTokenOwnership": "org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnStreamSignedTokenIntegrationTest#shouldUseSignedTokenForConnectProbeCancelAndReplayWithoutCookieIdentity",
}
missing = {name: test for name, test in evidence.items() if test not in executed}
if missing:
    raise SystemExit("Mandatory state-machine evidence missing: " + json.dumps(missing, indent=2))
if totals["failures"] or totals["errors"] or totals["skipped"]:
    raise SystemExit("State-machine matrix is not fully green: " + json.dumps(totals))

summary = {
    "schemaVersion": "praxis-authoring-turn-state-machine-gate.v1",
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "status": "passed",
    "totals": {**totals, "timeSeconds": round(totals["timeSeconds"], 3)},
    "evidence": {
        name: {"status": "passed", "test": test}
        for name, test in evidence.items()
    },
}
(artifacts / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

lines = [
    "# Authoring turn state-machine gate",
    "",
    f"- Status: `{summary['status']}`",
    f"- Tests: `{totals['tests']}`",
    f"- Failures/errors/skipped: `{totals['failures']}/{totals['errors']}/{totals['skipped']}`",
    f"- Test time: `{summary['totals']['timeSeconds']}s`",
    "",
    "## Mandatory evidence",
    "",
]
for name, item in summary["evidence"].items():
    lines.append(f"- `{name}`: `{item['test']}`")
(artifacts / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

print(json.dumps(summary, indent=2))
print(f"Artifacts: {artifacts}")
PY
