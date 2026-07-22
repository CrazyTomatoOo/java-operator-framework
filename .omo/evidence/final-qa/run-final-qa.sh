#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
EVIDENCE_DIR="${ROOT_DIR}/.sisyphus/evidence/final-qa"
mkdir -p "${EVIDENCE_DIR}"

export NAMESPACE="${NAMESPACE:-echo-operator-test}"
export RELEASE_NAME="${RELEASE_NAME:-echo-operator}"

declare -a RESULTS=()
SCENARIOS_PASSED=0
SCENARIOS_FAILED=0

log() {
    echo "[$(date -Iseconds)] $*"
}

run_scenario() {
    local name="$1"
    shift
    local log="${EVIDENCE_DIR}/${name}"
    {
        echo "=== ${name} ==="
        echo "Command: $*"
        echo "Started: $(date -Iseconds)"
    } | tee -a "${log}"
    "$@" >> "${log}" 2>&1
    local rc=$?
    {
        echo "Finished: $(date -Iseconds)"
        echo "EXIT_CODE: ${rc}"
    } >> "${log}"
    RESULTS+=("${name}|${rc}")
    if [ "${rc}" -eq 0 ]; then
        SCENARIOS_PASSED=$((SCENARIOS_PASSED + 1))
        log "PASS: ${name}"
    else
        SCENARIOS_FAILED=$((SCENARIOS_FAILED + 1))
        log "FAIL: ${name}"
    fi
    return "${rc}"
}

run_shell() {
    local name="$1"
    shift
    run_scenario "${name}" bash -c "$*"
}

log "Starting Final Verification Wave F3 from ${ROOT_DIR}"

run_scenario "final-task-1-mvn-install.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" clean install -DskipTests

run_scenario "final-task-2-example-compile.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" clean compile -DskipTests

run_scenario "final-task-3-resource-event-source-test.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=ResourceEventSourceTest

run_scenario "final-task-3-reconciler-interface-test.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=ReconcilerInterfaceTest

run_scenario "final-task-4-launcher-test.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=OperatorLauncherTest

run_scenario "final-task-5-helper-tests.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=OwnerReferenceHelperTest,FinalizerHelperTest

run_scenario "final-task-6-leader-test.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=LeaderElectionManagerTest

run_scenario "final-task-7-retry-test.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=ExponentialBackoffRetryPolicyTest

run_scenario "final-task-7-rate-limiter-test.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=RateLimiterTest

run_scenario "final-task-8-metrics-test.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=MetricsServerTest

run_scenario "final-task-9-health-test.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test -Dtest=HealthServerTest

run_scenario "final-task-10-sdk-tests.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" test

run_shell "final-task-11-crd-generated.log" \
    "cd '${ROOT_DIR}' && mvn -f example/echo-operator/pom.xml clean compile -q && ls example/echo-operator/target/classes/META-INF/fabric8/"

if [ -f "${ROOT_DIR}/example/echo-operator/target/classes/META-INF/fabric8/echoresources.example.com-v1.yml" ]; then
    cp "${ROOT_DIR}/example/echo-operator/target/classes/META-INF/fabric8/echoresources.example.com-v1.yml" \
        "${EVIDENCE_DIR}/final-task-11-crd-generated.yml"
else
    echo "CRD file not found" > "${EVIDENCE_DIR}/final-task-11-crd-generated.yml"
fi

run_shell "final-task-11-java-generated.log" \
    "cd '${ROOT_DIR}' && ls -R example/echo-operator/target/generated-sources/java/"

run_scenario "final-task-12-reconciler-create.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" test -Dtest=EchoReconcilerTest#testCreateDeploymentAndService

run_scenario "final-task-12-invalid-replicas.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" test -Dtest=EchoReconcilerTest#testInvalidReplicas

run_scenario "final-task-13-finalizer.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" test -Dtest=EchoReconcilerTest#testFinalizer

run_scenario "final-task-13-status-update.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" test -Dtest=EchoReconcilerTest#testStatusUpdate

run_scenario "final-task-14-main-test.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" test -Dtest=EchoOperatorMainTest

run_shell "final-task-15-docker-build.log" \
    "cd '${ROOT_DIR}' && mvn -f example/echo-operator/pom.xml clean package -DskipTests && docker build -t example/echo-operator:latest example/echo-operator && docker images | grep example/echo-operator"

run_shell "final-task-16-helm-lint.log" \
    "cd '${ROOT_DIR}' && helm lint example/echo-operator/helm/echo-operator"

run_shell "final-task-16-helm-render.yaml" \
    "cd '${ROOT_DIR}' && helm template echo-operator example/echo-operator/helm/echo-operator"

run_scenario "final-task-17-example-tests.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" test

run_scenario "final-task-18-build-script.log" \
    "${ROOT_DIR}/example/echo-operator/scripts/build.sh"

run_shell "final-task-18-scripts-executable.log" \
    "cd '${ROOT_DIR}' && ls -l example/echo-operator/scripts/*.sh"

run_shell "final-task-19-docs-exist.log" \
    "cd '${ROOT_DIR}' && ls -l operator/framework/README.md operator/framework/README.zh-CN.md example/echo-operator/README.md example/echo-operator/README.zh-CN.md docs/dev-guide.md docs/dev-guide.zh-CN.md"

run_scenario "final-task-20-smoke-test.log" \
    "${ROOT_DIR}/example/echo-operator/scripts/smoke-test.sh"

if [ -f "${ROOT_DIR}/.sisyphus/evidence/task-20-endpoints.log" ]; then
    cp "${ROOT_DIR}/.sisyphus/evidence/task-20-endpoints.log" "${EVIDENCE_DIR}/final-task-20-endpoints.log"
fi

run_shell "final-integration-sdk-example.log" \
    "cd '${ROOT_DIR}' && mvn -f operator/framework/pom.xml clean install -DskipTests && mvn -f example/echo-operator/pom.xml clean package -DskipTests && docker build -t example/echo-operator:latest example/echo-operator && helm template echo-operator example/echo-operator/helm/echo-operator > /dev/null"

run_scenario "final-edge-cases.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" test -Dtest=EdgeCaseTest

run_scenario "final-full-sdk-tests.log" \
    mvn -f "${ROOT_DIR}/operator/framework/pom.xml" clean test

run_scenario "final-full-example-tests.log" \
    mvn -f "${ROOT_DIR}/example/echo-operator/pom.xml" clean test

TOTAL=$((SCENARIOS_PASSED + SCENARIOS_FAILED))
REPORT="${EVIDENCE_DIR}/final-qa-summary.txt"
{
    echo "=== Final Verification Wave F3 Summary ==="
    echo "Total scenarios: ${TOTAL}"
    echo "Passed: ${SCENARIOS_PASSED}"
    echo "Failed: ${SCENARIOS_FAILED}"
    echo
    echo "Detailed results:"
    for entry in "${RESULTS[@]}"; do
        echo "  ${entry}"
    done
    echo
    if [ "${SCENARIOS_FAILED}" -eq 0 ]; then
        echo "VERDICT: APPROVE"
    else
        echo "VERDICT: REJECT"
        echo
        echo "Failed scenarios:"
        for entry in "${RESULTS[@]}"; do
            if [[ "${entry}" == *"|1"* || "${entry}" == *"|"[1-9]* ]]; then
                echo "  ${entry}"
            fi
        done
    fi
} | tee "${REPORT}"

log "Final QA complete. Report: ${REPORT}"
