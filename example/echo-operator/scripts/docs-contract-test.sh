#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
readonly CHART_DIR="${PROJECT_DIR}/helm/echo-operator"
readonly VALUES_FILE="${CHART_DIR}/values.yaml"
readonly MAIN_JAVA="${PROJECT_DIR}/src/main/java/com/example/echooperator/EchoOperatorMain.java"
readonly README_EN="${PROJECT_DIR}/README.md"
readonly README_ZH="${PROJECT_DIR}/README.zh-CN.md"
readonly EVIDENCE_DIR="${ROOT_DIR}/.omo/evidence/controller-webhook-split-deployment/task-9"
readonly LOG_FILE="${EVIDENCE_DIR}/docs-check.log"

mkdir -p "${EVIDENCE_DIR}"
: >"${LOG_FILE}"

pass_count=0
fail_count=0

log() {
  printf '[%s] %s\n' "$(date -Iseconds)" "$*" | tee -a "${LOG_FILE}"
}

pass() {
  pass_count=$((pass_count + 1))
  log "PASS: $*"
}

fail() {
  fail_count=$((fail_count + 1))
  log "FAIL: $*"
}

require_file() {
  if [[ ! -f "$1" ]]; then
    fail "required file missing: $1"
    return 1
  fi
}

for f in "${VALUES_FILE}" "${MAIN_JAVA}" "${README_EN}" "${README_ZH}"; do
  require_file "${f}" || true
done

log "=== Phase 1: values.yaml contract ==="

python3 - "${VALUES_FILE}" <<'PY' | tee -a "${LOG_FILE}"
import sys
import yaml

path = sys.argv[1]
with open(path, encoding="utf-8") as source:
    values = yaml.safe_load(source)

errors = []

if values.get("deploymentMode") != "combined":
    errors.append(f"deploymentMode default must be 'combined', got '{values.get('deploymentMode')}'")

if "controller" not in values:
    errors.append("values.yaml must have a top-level 'controller' key for split-mode controller workload")
elif "workload" not in values["controller"]:
    errors.append("controller must have a 'workload' sub-key")

if "webhook" not in values:
    errors.append("values.yaml must have a top-level 'webhook' key")
else:
    webhook = values["webhook"]
    if "workload" not in webhook:
        errors.append("webhook must have a 'workload' sub-key for split-mode webhook workload")
    if webhook.get("enabled") is not True:
        errors.append(f"webhook.enabled default must be true, got {webhook.get('enabled')}")
    if webhook.get("certAutoGenerate") is not True:
        errors.append(f"webhook.certAutoGenerate default must be true, got {webhook.get('certAutoGenerate')}")

controller_workload = values.get("controller", {}).get("workload", {})
webhook_workload = values.get("webhook", {}).get("workload", {})
for key in ("replicas", "resources", "podAnnotations", "podLabels", "nodeSelector", "tolerations", "affinity", "serviceAccount"):
    if key not in controller_workload:
        errors.append(f"controller.workload missing key: {key}")
    if key not in webhook_workload:
        errors.append(f"webhook.workload missing key: {key}")

if errors:
    for error in errors:
        print(f"VALUES_FAIL: {error}")
    raise SystemExit(1)
print("VALUES_PASS: values.yaml contract passed")
PY
if [[ "${PIPESTATUS[0]}" -eq 0 ]]; then
  pass "values.yaml contract"
else
  fail "values.yaml contract"
fi

log ""
log "=== Phase 2: EchoOperatorMain.java constants ==="

python3 - "${MAIN_JAVA}" <<'PY' | tee -a "${LOG_FILE}"
import re
import sys

path = sys.argv[1]
source = open(path, encoding="utf-8").read()

expected = {
    "DEFAULT_CONTROLLER_ENABLED": "true",
    "DEFAULT_WEBHOOK_ENABLED": "true",
    "DEFAULT_WEBHOOK_REGISTRATION_CLEANUP_ENABLED": "true",
    "DEFAULT_WEBHOOK_SELF_REGISTRATION_ENABLED": "true",
    "DEFAULT_WEBHOOK_VALIDATING_ENABLED": "true",
    "DEFAULT_WEBHOOK_MUTATING_ENABLED": "true",
    "DEFAULT_WEBHOOK_CONVERSION_ENABLED": "true",
    "DEFAULT_WEBHOOK_CERT_AUTO_GENERATE": "true",
}

errors = []
for constant, expected_value in expected.items():
    pattern = rf'{constant}\s*=\s*{expected_value}'
    if not re.search(pattern, source):
        errors.append(f"constant {constant} must be {expected_value}")

if "At least one of CONTROLLER_ENABLED or WEBHOOK_ENABLED must be true" not in source:
    errors.append("must validate that at least one of CONTROLLER_ENABLED or WEBHOOK_ENABLED is true")

if errors:
    for error in errors:
        print(f"JAVA_FAIL: {error}")
    raise SystemExit(1)
print("JAVA_PASS: EchoOperatorMain constants contract passed")
PY
if [[ "${PIPESTATUS[0]}" -eq 0 ]]; then
  pass "EchoOperatorMain constants"
else
  fail "EchoOperatorMain constants"
fi

log ""
log "=== Phase 3: English README required content ==="

python3 - "${README_EN}" <<'PY' | tee -a "${LOG_FILE}"
import sys

content = open(sys.argv[1], encoding="utf-8").read()

errors = []

required_tokens = [
    "deploymentMode",
    "combined",
    "split",
    "controller.workload",
    "webhook.workload",
    "CONTROLLER_ENABLED",
    "WEBHOOK_ENABLED",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED",
    "WEBHOOK_SELF_REGISTRATION_ENABLED",
    "WEBHOOK_PREDECESSOR_VALIDATING_NAME",
    "WEBHOOK_PREDECESSOR_MUTATING_NAME",
    "WEBHOOK_VALIDATING_ENABLED",
    "WEBHOOK_MUTATING_ENABLED",
    "WEBHOOK_CONVERSION_ENABLED",
    "WEBHOOK_CERT_AUTO_GENERATE",
    "-controller",
    "-webhook",
    "runtime-owned",
    "Helm-owned",
    "auto-generated",
    "external TLS",
    "caBundle",
]

for token in required_tokens:
    # Case-insensitive check for tokens that may appear with different casing
    if token.lower() not in content.lower():
        errors.append(f"English README missing required token: '{token}'")

# Check for forbidden tokens in positive context only (not in exclusions section)
# Split content into sections and exclude the Exclusions section from forbidden token check
sections = content.split('## ')
main_content_parts = []
for section in sections:
    if not section.startswith('Exclusions'):
        main_content_parts.append(section)
main_content = '## '.join(main_content_parts)

forbidden_tokens = [
    "cert-manager",
    "CA rotation",
    "PDB",
    "HPA",
    "OperatorMode",
]

for token in forbidden_tokens:
    if token in main_content:
        errors.append(f"English README contains forbidden token in main content: '{token}'")

if "webhook.enabled=false" in content or "webhook.enabled` is `false" in content or "webhook.enabled=false" in content:
    pass
else:
    webhook_disabled_mentioned = False
    for pattern in ["webhook.enabled=false", "webhook.enabled` is `false", "webhook.enabled is false",
                     "webhook.enabled=false", "webhook.enabled=false"]:
        if pattern in content:
            webhook_disabled_mentioned = True
            break
    if not webhook_disabled_mentioned:
        for pattern in ["webhook.enabled", "webhook disabled", "disable webhook", "without webhook"]:
            if pattern in content and ("split" in content.lower() and ("not" in content.lower() or "invalid" in content.lower() or "require" in content.lower() or "must" in content.lower())):
                webhook_disabled_mentioned = True
                break
    if not webhook_disabled_mentioned:
        errors.append("English README must document webhook.enabled=false restriction for split mode")

if errors:
    for error in errors:
        print(f"EN_FAIL: {error}")
    raise SystemExit(1)
print("EN_PASS: English README required content passed")
PY
if [[ "${PIPESTATUS[0]}" -eq 0 ]]; then
  pass "English README content"
else
  fail "English README content"
fi

log ""
log "=== Phase 4: Chinese README required content ==="

python3 - "${README_ZH}" <<'PY' | tee -a "${LOG_FILE}"
import sys

content = open(sys.argv[1], encoding="utf-8").read()

errors = []

required_tokens = [
    "deploymentMode",
    "combined",
    "split",
    "controller.workload",
    "webhook.workload",
    "CONTROLLER_ENABLED",
    "WEBHOOK_ENABLED",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED",
    "WEBHOOK_SELF_REGISTRATION_ENABLED",
    "WEBHOOK_PREDECESSOR_VALIDATING_NAME",
    "WEBHOOK_PREDECESSOR_MUTATING_NAME",
    "WEBHOOK_VALIDATING_ENABLED",
    "WEBHOOK_MUTATING_ENABLED",
    "WEBHOOK_CONVERSION_ENABLED",
    "WEBHOOK_CERT_AUTO_GENERATE",
    "-controller",
    "-webhook",
]

for token in required_tokens:
    if token not in content:
        errors.append(f"Chinese README missing required token: '{token}'")

# Check for forbidden tokens in positive context only (not in exclusions section)
sections = content.split('## ')
main_content_parts = []
for section in sections:
    if not section.startswith('不包含'):
        main_content_parts.append(section)
main_content = '## '.join(main_content_parts)

forbidden_tokens = [
    "cert-manager",
    "CA rotation",
    "PDB",
    "HPA",
    "OperatorMode",
]

for token in forbidden_tokens:
    if token in main_content:
        errors.append(f"Chinese README contains forbidden token in main content: '{token}'")

if errors:
    for error in errors:
        print(f"ZH_FAIL: {error}")
    raise SystemExit(1)
print("ZH_PASS: Chinese README required content passed")
PY
if [[ "${PIPESTATUS[0]}" -eq 0 ]]; then
  pass "Chinese README content"
else
  fail "Chinese README content"
fi

log ""
log "=== Phase 5: EN/ZH default consistency ==="

python3 - "${README_EN}" "${README_ZH}" <<'PY' | tee -a "${LOG_FILE}"
import re
import sys

en = open(sys.argv[1], encoding="utf-8").read()
zh = open(sys.argv[2], encoding="utf-8").read()

errors = []

shared_code_tokens = [
    "deploymentMode: combined",
    "deploymentMode: split",
    "deploymentMode=combined",
    "deploymentMode=split",
    "CONTROLLER_ENABLED",
    "WEBHOOK_ENABLED",
    "WEBHOOK_VALIDATING_ENABLED",
    "WEBHOOK_MUTATING_ENABLED",
    "WEBHOOK_CONVERSION_ENABLED",
    "WEBHOOK_CERT_AUTO_GENERATE",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED",
    "WEBHOOK_SELF_REGISTRATION_ENABLED",
    "WEBHOOK_PREDECESSOR_VALIDATING_NAME",
    "WEBHOOK_PREDECESSOR_MUTATING_NAME",
    "controller.workload",
    "webhook.workload",
]

for token in shared_code_tokens:
    if token not in en:
        errors.append(f"English README missing shared token: '{token}'")
    if token not in zh:
        errors.append(f"Chinese README missing shared token: '{token}'")

en_has_split_default = "deploymentMode: combined" in en or "deploymentMode=combined" in en
zh_has_split_default = "deploymentMode: combined" in zh or "deploymentMode=combined" in zh
if en_has_split_default != zh_has_split_default:
    errors.append("EN and ZH must agree on deploymentMode default")

en_has_split_resources = "-controller" in en and "-webhook" in en
zh_has_split_resources = "-controller" in zh and "-webhook" in zh
if en_has_split_resources != zh_has_split_resources:
    errors.append("EN and ZH must agree on split resource naming (-controller/-webhook)")

if errors:
    for error in errors:
        print(f"CONSISTENCY_FAIL: {error}")
    raise SystemExit(1)
print("CONSISTENCY_PASS: EN/ZH default consistency passed")
PY
if [[ "${PIPESTATUS[0]}" -eq 0 ]]; then
  pass "EN/ZH consistency"
else
  fail "EN/ZH consistency"
fi

log ""
log "=== Phase 6: Forbidden feature tokens ==="

python3 - "${README_EN}" "${README_ZH}" <<'PY' | tee -a "${LOG_FILE}"
import sys

en = open(sys.argv[1], encoding="utf-8").read()
zh = open(sys.argv[2], encoding="utf-8").read()

errors = []

forbidden = [
    "cert-manager",
    "certmanager",
    "CA rotation",
    "caRotation",
    "PodDisruptionBudget",
    "HorizontalPodAutoscaler",
    "OperatorMode",
    "operatorMode",
    "deployment mode API",
    "framework-level mode",
]

for label, content in [("English", en), ("Chinese", zh)]:
    # Exclude exclusions section from forbidden token check
    sections = content.split('## ')
    main_content_parts = []
    for section in sections:
        if not section.startswith('Exclusions') and not section.startswith('不包含'):
            main_content_parts.append(section)
    main_content = '## '.join(main_content_parts)
    for token in forbidden:
        if token.lower() in main_content.lower():
            errors.append(f"{label} README contains forbidden feature token in main content: '{token}'")

if errors:
    for error in errors:
        print(f"FORBIDDEN_FAIL: {error}")
    raise SystemExit(1)
print("FORBIDDEN_PASS: no forbidden feature tokens found")
PY
if [[ "${PIPESTATUS[0]}" -eq 0 ]]; then
  pass "forbidden feature tokens"
else
  fail "forbidden feature tokens"
fi

log ""
log "=== Phase 7: Helm lint ==="

if helm lint "${CHART_DIR}" >>"${LOG_FILE}" 2>&1; then
  pass "helm lint"
else
  fail "helm lint"
fi

log ""
log "=== Phase 8: Verification commands documented ==="

python3 - "${README_EN}" "${README_ZH}" <<'PY' | tee -a "${LOG_FILE}"
import sys

en = open(sys.argv[1], encoding="utf-8").read()
zh = open(sys.argv[2], encoding="utf-8").read()

errors = []

verification_commands = [
    "helm lint",
    "helm-contract-test",
    "smoke-test",
    "docs-contract-test",
]

for label, content in [("English", en), ("Chinese", zh)]:
    for cmd in verification_commands:
        if cmd not in content:
            errors.append(f"{label} README missing verification command: '{cmd}'")

if errors:
    for error in errors:
        print(f"VERIFY_FAIL: {error}")
    raise SystemExit(1)
print("VERIFY_PASS: verification commands documented")
PY
if [[ "${PIPESTATUS[0]}" -eq 0 ]]; then
  pass "verification commands documented"
else
  fail "verification commands documented"
fi

log ""
log "=== Phase 9: Exclusions documented ==="

python3 - "${README_EN}" <<'PY' | tee -a "${LOG_FILE}"
import sys

content = open(sys.argv[1], encoding="utf-8").read()

errors = []

exclusion_markers = [
    "cert-manager",
    "CA rotation",
    "PDB",
    "HPA",
    "second image",
    "second JAR",
]

exclusion_section_found = False
for marker in ["not provide", "does not provide", "does not include", "out of scope",
                "not included", "not supported", "not managed", "excludes", "excluding"]:
    if marker in content.lower():
        exclusion_section_found = True
        break

if not exclusion_section_found:
    for marker in ["不提供", "不包含", "不支持", "不在", "范围之外"]:
        if marker in content:
            exclusion_section_found = True
            break

if not exclusion_section_found:
    errors.append("English README must have an explicit exclusions/limitations section")

if errors:
    for error in errors:
        print(f"EXCLUSION_FAIL: {error}")
    raise SystemExit(1)
print("EXCLUSION_PASS: exclusions documented")
PY
if [[ "${PIPESTATUS[0]}" -eq 0 ]]; then
  pass "exclusions documented"
else
  fail "exclusions documented"
fi

log ""
log "========================================="
log "docs-contract-test summary: ${pass_count} passed, ${fail_count} failed"
log "========================================="

if [[ "${fail_count}" -gt 0 ]]; then
  log "docs-contract-test FAILED"
  exit 1
fi

log "docs-contract-test PASSED"
