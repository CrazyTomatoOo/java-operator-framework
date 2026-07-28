#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
DEPLOY_SCRIPT="${SCRIPT_DIR}/deploy.sh"
UNDEPLOY_SCRIPT="${SCRIPT_DIR}/undeploy.sh"
BUILD_IMAGE_SCRIPT="${SCRIPT_DIR}/build-image.sh"
BUILD_SCRIPT="${SCRIPT_DIR}/build.sh"
CHART_DIR="${PROJECT_DIR}/helm/echo-operator"
BASELINE_ARCHIVE="${ROOT_DIR}/.omo/evidence/controller-webhook-split-deployment/baseline/echo-operator-chart.tar"

DEPLOYMENT_MODE="${DEPLOYMENT_MODE:-combined}"
RUN_TOKEN="${RUN_TOKEN:-$(date +%Y%m%d%H%M%S)-$$}"
RELEASE_NAME="${RELEASE_NAME:-echo-smoke-${DEPLOYMENT_MODE}-${RUN_TOKEN}}"
NAMESPACE="${NAMESPACE:-echo-smoke-${DEPLOYMENT_MODE}-${RUN_TOKEN}}"
WATCHED_NAMESPACE="${WATCHED_NAMESPACE:-${NAMESPACE}}"
IMAGE_TAG="${IMAGE_TAG:-smoke-${RUN_TOKEN}}"
EVIDENCE_ROOT="${ROOT_DIR}/.omo/evidence/controller-webhook-split-deployment/${DEPLOYMENT_MODE}"
RUN_DIR="${EVIDENCE_ROOT}/${RUN_TOKEN}"
TMP_DIR="${TMPDIR:-/tmp}/echo-operator-smoke.${RUN_TOKEN}"
METRICS_PORT="${METRICS_PORT:-8080}"
HELM_POST_RENDERER=""
ADMISSION_CA_SECRET="echo-operator-webhook-ca"

case "${DEPLOYMENT_MODE}" in
  combined|split) ;;
  *) echo "ERROR: DEPLOYMENT_MODE must be combined or split" >&2; exit 1 ;;
esac

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

preflight_check() {
  require_command kubectl
  require_command helm
  require_command curl
  require_command openssl
  require_command python3
  kubectl config current-context >/dev/null 2>&1 || die "Kubernetes context is unavailable"
  kubectl cluster-info >/dev/null 2>&1 || die "Kubernetes cluster is unreachable"
}

static_contract() {
  python3 - "${DEPLOY_SCRIPT}" "${UNDEPLOY_SCRIPT}" "$0" <<'PY'
import pathlib
import re
import sys

deploy, undeploy, smoke = (pathlib.Path(path).read_text(encoding="utf-8") for path in sys.argv[1:])
assert "HELM_ARGS=(upgrade --install" in deploy, "deploy.sh must use Helm upgrade --install"
assert "DEPLOYMENT_MODE" in deploy, "deploy.sh must pass the requested topology"
assert "HELM_POST_RENDERER" in deploy, "deploy.sh must support a Helm post-renderer"
assert "helm uninstall" in undeploy, "undeploy.sh must use Helm uninstall"
skip_marker = "No Kubernetes cluster " + "available. Skipping"
assert skip_marker not in smoke, "cluster absence must not be a successful skip"
raw_crd_apply = "kubectl apply -f " + '"${CRD_FILE}"'
assert raw_crd_apply not in smoke, "smoke must not apply a raw Helm CRD template"
assert len(re.findall(r"(?m)^\s*trap\s+(?!-\s)", smoke)) == 1, "smoke must install exactly one cleanup trap"
assert "kubectl auth can-i" in smoke and "--as=" in smoke, "smoke must use SA impersonation"
assert 'answer##*$' in smoke, "auth checks must parse kubectl warnings separately from yes/no"
assert "SMOKE_PRECHECK_ONLY" in smoke, "smoke must test an invalid kube context"
deprecated_resource_flag = "--resource" + "-name"
assert deprecated_resource_flag not in smoke, "auth checks must use kubectl TYPE/NAME syntax"
assert '[[ "${DEPLOYMENT_MODE}" == "split" ]] || return 0' in smoke, "combined split-only checks must return success"
assert 'runtime_admission_name="echo-operator.${WATCHED_NAMESPACE}.echo.example.com"' in smoke, "cleanup must derive its exact runtime admission name"
assert 'kubectl delete echoresources --all -n "${NAMESPACE}"' in smoke, "cleanup must delete EchoResources before removing the controller"
assert 'helm_validating_name="${helm_fullname}-validating"' in smoke, "cleanup must derive its exact Helm validating predecessor name"
assert "patch_preserved_crd_external_ca" in smoke, "external TLS must reconcile a preserved CRD client config"
assert 'kubectl delete validatingwebhookconfiguration "${runtime_admission_name}"' in smoke, "cleanup must remove its runtime validating configuration"
assert 'kubectl delete mutatingwebhookconfiguration "${runtime_admission_name}"' in smoke, "cleanup must remove its runtime mutating configuration"
assert 'spec.message must be 140 characters or fewer' in smoke, "admission denial fixture must survive mutation"
legacy_combined_permission = ("get", "mutatingwebhookconfigurations.admissionregistration.k8s.io")
current_combined_permission = ("delete", "customresourcedefinitions.apiextensions.k8s.io")
combined_rbac = {legacy_combined_permission: True, current_combined_permission: False}
assert combined_rbac[legacy_combined_permission], "legacy combined deliberate assertion must demonstrate its old false failure"
assert not combined_rbac[current_combined_permission], "current combined deliberate assertion must target a real denial"
legacy_auth_call = "get " + "mutatingwebhookconfigurations.admissionregistration.k8s.io"
current_auth_call = "delete customresourcedefinitions.apiextensions.k8s.io"
assert legacy_auth_call not in smoke, "combined deliberate assertion still uses an allowed admission permission"
assert current_auth_call in smoke, "combined deliberate assertion must use the CRD delete denial"
subshell_port_forward = "$(" + "start_port_forward"
assert subshell_port_forward not in smoke, "port-forward state must not be mutated in command substitution"
assert "PORT_FORWARD_PORT" in smoke and "PORT_FORWARD_PID" in smoke, "port-forward must return state through parent-shell variables"
assert "SMOKE_PORT_FORWARD_REGRESSION_ONLY" in smoke, "smoke must execute the port-forward cleanup regression"
print("static smoke contract passed")
PY
}

if [[ "${SMOKE_STATIC_ONLY:-false}" == "true" ]]; then
  static_contract
  exit 0
fi
if [[ "${SMOKE_PRECHECK_ONLY:-false}" == "true" ]]; then
  preflight_check
  exit 0
fi

mkdir -p "${RUN_DIR}" "${TMP_DIR}"
COMMAND_LOG="${RUN_DIR}/commands.log"
CLEANUP_LOG="${RUN_DIR}/cleanup-receipt.log"
: >"${COMMAND_LOG}"
: >"${CLEANUP_LOG}"

log() {
  printf '[%s] %s\n' "$(date -Iseconds)" "$*" | tee -a "${RUN_DIR}/smoke.log"
}

record_command() {
  printf '+ ' >>"${COMMAND_LOG}"
  printf '%q ' "$@" >>"${COMMAND_LOG}"
  printf '\n' >>"${COMMAND_LOG}"
}

capture() {
  local output="$1"
  shift
  record_command "$@"
  "$@" >"${output}" 2>&1
}

capture_expected_failure() {
  local output="$1"
  shift
  record_command "$@"
  if "$@" >"${output}" 2>&1; then
    die "expected command to fail but it succeeded: $*"
  fi
}

CLEANUP_ARMED=false
PORT_FORWARD_PIDS=""
PORT_FORWARD_PORT=""
PORT_FORWARD_PID=""
cleanup() {
  local original_status=$?
  local cleanup_status=0
  local runtime_admission_name="echo-operator.${WATCHED_NAMESPACE}.echo.example.com"
  local helm_fullname="${RELEASE_NAME}"
  local helm_validating_name
  local helm_mutating_name
  if [[ "${RELEASE_NAME}" != *echo-operator* ]]; then
    helm_fullname="${RELEASE_NAME}-echo-operator"
  fi
  helm_validating_name="${helm_fullname}-validating"
  helm_mutating_name="${helm_fullname}-mutating"
  trap - EXIT
  set +e
  printf 'original_status=%s\n' "${original_status}" >>"${CLEANUP_LOG}"
  for pid in ${PORT_FORWARD_PIDS}; do
    printf 'stopping port-forward pid=%s\n' "${pid}" >>"${CLEANUP_LOG}"
    if kill "${pid}" >/dev/null 2>&1; then
      printf 'killed port-forward pid=%s\n' "${pid}" >>"${CLEANUP_LOG}"
    else
      printf 'kill-not-needed port-forward pid=%s\n' "${pid}" >>"${CLEANUP_LOG}"
    fi
    wait "${pid}" >/dev/null 2>&1
    printf 'waited port-forward pid=%s\n' "${pid}" >>"${CLEANUP_LOG}"
  done
  if [[ "${CLEANUP_ARMED}" == "true" ]]; then
    printf 'delete echoresources namespace=%s\n' "${NAMESPACE}" >>"${CLEANUP_LOG}"
    kubectl delete echoresources --all -n "${NAMESPACE}" --ignore-not-found --wait=true --timeout=180s >>"${CLEANUP_LOG}" 2>&1 || cleanup_status=1
    printf 'release Helm validating=%s\n' "${helm_validating_name}" >>"${CLEANUP_LOG}"
    kubectl patch validatingwebhookconfiguration "${helm_validating_name}" --type=merge -p '{"metadata":{"finalizers":[]}}' >>"${CLEANUP_LOG}" 2>&1 || true
    kubectl delete validatingwebhookconfiguration "${helm_validating_name}" --ignore-not-found --wait=true >>"${CLEANUP_LOG}" 2>&1 || cleanup_status=1
    printf 'release Helm mutating=%s\n' "${helm_mutating_name}" >>"${CLEANUP_LOG}"
    kubectl patch mutatingwebhookconfiguration "${helm_mutating_name}" --type=merge -p '{"metadata":{"finalizers":[]}}' >>"${CLEANUP_LOG}" 2>&1 || true
    kubectl delete mutatingwebhookconfiguration "${helm_mutating_name}" --ignore-not-found --wait=true >>"${CLEANUP_LOG}" 2>&1 || cleanup_status=1
    printf 'delete runtime validating=%s\n' "${runtime_admission_name}" >>"${CLEANUP_LOG}"
    kubectl delete validatingwebhookconfiguration "${runtime_admission_name}" --ignore-not-found --wait=true >>"${CLEANUP_LOG}" 2>&1 || cleanup_status=1
    printf 'delete runtime mutating=%s\n' "${runtime_admission_name}" >>"${CLEANUP_LOG}"
    kubectl delete mutatingwebhookconfiguration "${runtime_admission_name}" --ignore-not-found --wait=true >>"${CLEANUP_LOG}" 2>&1 || cleanup_status=1
    printf 'uninstall release=%s namespace=%s\n' "${RELEASE_NAME}" "${NAMESPACE}" >>"${CLEANUP_LOG}"
    RELEASE_NAME="${RELEASE_NAME}" NAMESPACE="${NAMESPACE}" "${UNDEPLOY_SCRIPT}" >>"${CLEANUP_LOG}" 2>&1 || cleanup_status=1
    printf 'delete namespace=%s\n' "${NAMESPACE}" >>"${CLEANUP_LOG}"
    kubectl delete namespace "${NAMESPACE}" --wait=true --timeout=300s >>"${CLEANUP_LOG}" 2>&1 || cleanup_status=1
  fi
  rm -rf "${TMP_DIR}"
  printf 'cleanup_status=%s\n' "${cleanup_status}" >>"${CLEANUP_LOG}"
  if [[ "${original_status}" -eq 0 && "${cleanup_status}" -ne 0 ]]; then
    exit 1
  fi
  exit "${original_status}"
}
trap cleanup EXIT

assert_invalid_context_fails() {
  local output="${RUN_DIR}/invalid-kube-context.log"
  record_command env "KUBECONFIG=${TMP_DIR}/definitely-invalid-kubeconfig" SMOKE_PRECHECK_ONLY=true "$0"
  if KUBECONFIG="${TMP_DIR}/definitely-invalid-kubeconfig" SMOKE_PRECHECK_ONLY=true "$0" >"${output}" 2>&1; then
    die "invalid kube context unexpectedly passed the prerequisite check"
  fi
  log "invalid kube context prerequisite contract failed as required"
}

create_isolated_namespace() {
  if kubectl get namespace "${NAMESPACE}" >/dev/null 2>&1; then
    die "refusing to reuse namespace ${NAMESPACE}; smoke namespaces must be isolated"
  fi
  capture "${RUN_DIR}/namespace-create.log" kubectl create namespace "${NAMESPACE}"
  CLEANUP_ARMED=true
}

build_image() {
  if [[ "${SKIP_IMAGE_BUILD:-false}" == "true" ]]; then
    log "using prebuilt image example/echo-operator:${IMAGE_TAG}"
    return
  fi
  capture "${RUN_DIR}/framework-build.log" "${BUILD_SCRIPT}"
  capture "${RUN_DIR}/image-build.log" env "IMAGE_TAG=${IMAGE_TAG}" "${BUILD_IMAGE_SCRIPT}"
}

deploy_chart() {
  local label="$1"
  local chart="$2"
  local values_file="$3"
  local wait_for_ready="$4"
  local output="${RUN_DIR}/deploy-${label}.log"
  record_command env "CHART_DIR=${chart}" "RELEASE_NAME=${RELEASE_NAME}" "NAMESPACE=${NAMESPACE}" \
    "DEPLOYMENT_MODE=${DEPLOYMENT_MODE}" "IMAGE_TAG=${IMAGE_TAG}" "HELM_VALUES_FILE=${values_file}" \
    "HELM_POST_RENDERER=${HELM_POST_RENDERER}" "HELM_WAIT=${wait_for_ready}" SKIP_BUILD=true "${DEPLOY_SCRIPT}"
  CHART_DIR="${chart}" RELEASE_NAME="${RELEASE_NAME}" NAMESPACE="${NAMESPACE}" \
    DEPLOYMENT_MODE="${DEPLOYMENT_MODE}" IMAGE_TAG="${IMAGE_TAG}" HELM_VALUES_FILE="${values_file}" \
    HELM_POST_RENDERER="${HELM_POST_RENDERER}" HELM_WAIT="${wait_for_ready}" SKIP_BUILD=true "${DEPLOY_SCRIPT}" >"${output}" 2>&1 || die "Helm deployment failed for ${label}; see ${output}"
}

configure_crd_lifecycle() {
  local existing_crd="${RUN_DIR}/preexisting-crd.yaml"
  local renderer="${TMP_DIR}/omit-preexisting-crd.py"
  record_command kubectl get crd echoresources.example.com -o yaml
  if kubectl get crd echoresources.example.com -o yaml >"${existing_crd}" 2>&1; then
    python3 - "${renderer}" <<'PY'
import pathlib
import sys

pathlib.Path(sys.argv[1]).write_text('''#!/usr/bin/env python3
import sys
import yaml

resources = [resource for resource in yaml.safe_load_all(sys.stdin)
             if resource and not (resource.get("kind") == "CustomResourceDefinition"
                                  and resource.get("metadata", {}).get("name") == "echoresources.example.com")]
yaml.safe_dump_all(resources, sys.stdout, explicit_start=True, sort_keys=False)
''', encoding="utf-8")
PY
    chmod +x "${renderer}"
    HELM_POST_RENDERER="${renderer}"
    printf 'mode=preserve-preexisting\ncrd=echoresources.example.com\nrenderer=%s\n' "${renderer}" >"${RUN_DIR}/crd-lifecycle.txt"
    log "preserving the pre-existing CRD outside this isolated Helm release"
  else
    HELM_POST_RENDERER=""
    printf 'mode=release-owned\ncrd=echoresources.example.com\n' >"${RUN_DIR}/crd-lifecycle.txt"
  fi
}

resolve_topology() {
  capture "${RUN_DIR}/topology-resources.json" kubectl get deployment,service -n "${NAMESPACE}" \
    -l "app.kubernetes.io/instance=${RELEASE_NAME}" -o json
  eval "$(python3 - "${DEPLOYMENT_MODE}" "${RUN_DIR}/topology-resources.json" <<'PY'
import json
import shlex
import sys

mode, path = sys.argv[1:]
items = json.load(open(path, encoding="utf-8"))["items"]
deployments = [item for item in items if item["kind"] == "Deployment"]
services = [item for item in items if item["kind"] == "Service"]
if mode == "combined":
    if len(deployments) != 1 or len(services) != 1:
        raise SystemExit("combined topology must have exactly one Deployment and one Service")
    controller = deployments[0]
    webhook = deployments[0]
    controller_service = services[0]
    webhook_service = services[0]
else:
    by_component_deployment = {item["metadata"]["labels"].get("app.kubernetes.io/component"): item for item in deployments}
    by_component_service = {item["metadata"]["labels"].get("app.kubernetes.io/component"): item for item in services}
    if set(by_component_deployment) != {"controller", "webhook"} or set(by_component_service) != {"controller", "webhook"}:
        raise SystemExit("split topology must have controller and webhook Deployments and Services")
    controller = by_component_deployment["controller"]
    webhook = by_component_deployment["webhook"]
    controller_service = by_component_service["controller"]
    webhook_service = by_component_service["webhook"]

values = {
    "CONTROLLER_DEPLOYMENT": controller["metadata"]["name"],
    "WEBHOOK_DEPLOYMENT": webhook["metadata"]["name"],
    "CONTROLLER_SERVICE": controller_service["metadata"]["name"],
    "WEBHOOK_SERVICE": webhook_service["metadata"]["name"],
    "CONTROLLER_SA": controller["spec"]["template"]["spec"]["serviceAccountName"],
    "WEBHOOK_SA": webhook["spec"]["template"]["spec"]["serviceAccountName"],
}
for key, value in values.items():
    print(f"{key}={shlex.quote(value)}")
PY
)"
  FULLNAME="${WEBHOOK_SERVICE}"
  RUNTIME_ADMISSION_NAME="echo-operator.${WATCHED_NAMESPACE}.echo.example.com"
  HELM_VALIDATING_NAME="${FULLNAME}-validating"
  HELM_MUTATING_NAME="${FULLNAME}-mutating"
  printf 'release=%s\nnamespace=%s\nmode=%s\ncontrollerDeployment=%s\nwebhookDeployment=%s\ncontrollerService=%s\nwebhookService=%s\ncontrollerSA=%s\nwebhookSA=%s\n' \
    "${RELEASE_NAME}" "${NAMESPACE}" "${DEPLOYMENT_MODE}" "${CONTROLLER_DEPLOYMENT}" "${WEBHOOK_DEPLOYMENT}" \
    "${CONTROLLER_SERVICE}" "${WEBHOOK_SERVICE}" "${CONTROLLER_SA}" "${WEBHOOK_SA}" >"${RUN_DIR}/resolved-identities.txt"
}

wait_for_rollout() {
  local deployment="$1"
  capture "${RUN_DIR}/rollout-${deployment}.log" kubectl rollout status "deployment/${deployment}" -n "${NAMESPACE}" --timeout=300s
}

running_webhook_pod() {
  local excluded_pod="${1:-}"
  local selector="app.kubernetes.io/instance=${RELEASE_NAME}"
  if [[ "${DEPLOYMENT_MODE}" == "split" ]]; then
    selector+=",app.kubernetes.io/component=webhook"
  fi
  kubectl get pods -n "${NAMESPACE}" -l "${selector}" -o json | python3 -c '
import json
import sys

excluded = sys.argv[1]
pods = [pod for pod in json.load(sys.stdin)["items"]
        if pod.get("status", {}).get("phase") == "Running" and pod["metadata"]["name"] != excluded]
if not pods:
    raise SystemExit("no running webhook pod")
print(pods[0]["metadata"]["name"])
' "${excluded_pod}"
}

wait_for_running_webhook_pod() {
  local excluded_pod="${1:-}"
  local pod=""
  local attempt
  for attempt in $(seq 1 60); do
    if pod="$(running_webhook_pod "${excluded_pod}" 2>/dev/null)"; then
      printf '%s\n' "${pod}"
      return
    fi
    sleep 1
  done
  die "webhook pod did not enter Running state"
}

wait_for_pod_log() {
  local pod="$1"
  local expected="$2"
  local output="${RUN_DIR}/held-webhook-startup.log"
  local log_output
  local attempt
  for attempt in $(seq 1 45); do
    record_command kubectl logs "pod/${pod}" -n "${NAMESPACE}"
    log_output="$(kubectl logs "pod/${pod}" -n "${NAMESPACE}" 2>&1 || true)"
    printf '%s\n' "${log_output}" >"${output}"
    [[ "${log_output}" == *"${expected}"* ]] && return
    sleep 1
  done
  die "pod ${pod} did not log ${expected}"
}

next_local_port() {
  python3 -c 'import socket; sock=socket.socket(); sock.bind(("127.0.0.1", 0)); print(sock.getsockname()[1]); sock.close()'
}

start_port_forward() {
  local label="$1"
  local target="$2"
  local remote_port="${3:-${METRICS_PORT}}"
  local local_port
  local output="${RUN_DIR}/port-forward-${label}.log"
  local_port="$(next_local_port)"
  if [[ -n "${PORT_FORWARD_TEST_COMMAND:-}" ]]; then
    record_command bash -c "${PORT_FORWARD_TEST_COMMAND}"
    bash -c "${PORT_FORWARD_TEST_COMMAND}" >"${output}" 2>&1 &
  else
    record_command kubectl port-forward -n "${NAMESPACE}" "${target}" "${local_port}:${remote_port}"
    kubectl port-forward -n "${NAMESPACE}" "${target}" "${local_port}:${remote_port}" >"${output}" 2>&1 &
  fi
  PORT_FORWARD_PID=$!
  PORT_FORWARD_PIDS="${PORT_FORWARD_PIDS} ${PORT_FORWARD_PID}"
  PORT_FORWARD_PORT="${local_port}"
}

port_forward_regression_contract() {
  local legacy_state=""
  local ignored_output
  legacy_state_mutator() {
    legacy_state=mutated
    printf 'ignored\n'
  }
  ignored_output="$(legacy_state_mutator)"
  [[ -z "${legacy_state}" ]] || die "legacy command-substitution state-loss contract did not reproduce"
  PORT_FORWARD_TEST_COMMAND="${PORT_FORWARD_TEST_COMMAND:-sleep 300}"
  start_port_forward regression fake-target
  [[ -n "${PORT_FORWARD_PID}" && -n "${PORT_FORWARD_PORT}" ]] || die "port-forward result variables were not set"
  [[ " ${PORT_FORWARD_PIDS} " == *" ${PORT_FORWARD_PID} "* ]] || die "parent shell did not retain the port-forward PID"
  printf 'port=%s\npid=%s\npids=%s\n' "${PORT_FORWARD_PORT}" "${PORT_FORWARD_PID}" "${PORT_FORWARD_PIDS}" >"${RUN_DIR}/port-forward-regression.txt"
  log "port-forward parent-shell PID propagation contract passed"
}

if [[ "${SMOKE_PORT_FORWARD_REGRESSION_ONLY:-false}" == "true" ]]; then
  port_forward_regression_contract
  exit 0
fi

assert_http_code() {
  local label="$1"
  local port="$2"
  local path="$3"
  local expected="$4"
  local output="${RUN_DIR}/http-${label}-${path#/}.log"
  local code=""
  local attempt
  for attempt in $(seq 1 45); do
    record_command curl --silent --show-error --output "${output}" --write-out '%{http_code}' "http://127.0.0.1:${port}${path}"
    code="$(curl --silent --show-error --output "${output}" --write-out '%{http_code}' "http://127.0.0.1:${port}${path}" || true)"
    if [[ "${code}" == "${expected}" ]]; then
      printf 'HTTP %s\n' "${code}" >>"${output}"
      return
    fi
    sleep 1
  done
  printf 'HTTP %s\n' "${code}" >>"${output}"
  die "${label} ${path} returned HTTP ${code}, expected ${expected}"
}

assert_readiness_endpoints() {
  local controller_port webhook_port
  if [[ "${DEPLOYMENT_MODE}" == "combined" ]]; then
    start_port_forward combined "svc/${WEBHOOK_SERVICE}"
    controller_port="${PORT_FORWARD_PORT}"
    webhook_port="${controller_port}"
  else
    start_port_forward controller "svc/${CONTROLLER_SERVICE}"
    controller_port="${PORT_FORWARD_PORT}"
    start_port_forward webhook "pod/$(wait_for_running_webhook_pod)"
    webhook_port="${PORT_FORWARD_PORT}"
  fi
  assert_http_code controller "${controller_port}" /healthz 200
  assert_http_code controller "${controller_port}" /readyz 200
  assert_http_code webhook "${webhook_port}" /healthz 200
  assert_http_code webhook "${webhook_port}" /readyz 200
  printf 'controller_metrics_port=%s\nwebhook_metrics_port=%s\n' "${controller_port}" "${webhook_port}" >"${RUN_DIR}/readiness-endpoints.txt"
}

assert_webhook_admission_transport() {
  local ca_b64="${RUN_DIR}/admission-probe-ca.b64"
  local ca_pem="${TMP_DIR}/admission-probe-ca.crt"
  local request="${TMP_DIR}/admission-probe.json"
  local output="${RUN_DIR}/admission-direct-probe.log"
  local host="${WEBHOOK_SERVICE}.${NAMESPACE}.svc"
  local webhook_port
  local code
  local attempt
  capture "${ca_b64}" kubectl get secret "${ADMISSION_CA_SECRET}" -n "${NAMESPACE}" -o 'jsonpath={.data.ca\.crt}'
  python3 - "${ca_b64}" "${ca_pem}" <<'PY'
import base64
import pathlib
import sys

pathlib.Path(sys.argv[2]).write_bytes(base64.b64decode(pathlib.Path(sys.argv[1]).read_bytes(), validate=True))
PY
  python3 - "${request}" <<'PY'
import json
import pathlib
import sys

review = {
    "apiVersion": "admission.k8s.io/v1",
    "kind": "AdmissionReview",
    "request": {
        "uid": "echo-smoke-direct-probe",
        "operation": "CREATE",
        "resource": {"group": "example.com", "version": "v1alpha2", "resource": "echoresources"},
        "namespace": "default",
        "object": {
            "apiVersion": "example.com/v1alpha2",
            "kind": "EchoResource",
            "metadata": {"name": "echo-smoke-direct-probe"},
            "spec": {"message": "probe", "replicas": 1},
        },
    },
}
pathlib.Path(sys.argv[1]).write_text(json.dumps(review), encoding="utf-8")
PY
  start_port_forward admission-webhook "svc/${WEBHOOK_SERVICE}" 443
  webhook_port="${PORT_FORWARD_PORT}"
  record_command curl --noproxy '*' --silent --show-error --cacert "${ca_pem}" --resolve "${host}:${webhook_port}:127.0.0.1" \
    --header 'Content-Type: application/json' --data-binary "@${request}" "https://${host}:${webhook_port}/mutate/echo.example.com"
  for attempt in $(seq 1 15); do
    code="$(curl --noproxy '*' --silent --show-error --output "${output}" --write-out '%{http_code}' --connect-timeout 10 --max-time 15 \
      --cacert "${ca_pem}" --resolve "${host}:${webhook_port}:127.0.0.1" --header 'Content-Type: application/json' \
      --data-binary "@${request}" "https://${host}:${webhook_port}/mutate/echo.example.com" || true)"
    [[ "${code}" == 200 ]] && return
    sleep 1
  done
  die "webhook admission endpoint returned HTTP ${code}, expected 200"
}

assert_split_endpoint_isolation() {
  [[ "${DEPLOYMENT_MODE}" == "split" ]] || return 0
  capture "${RUN_DIR}/split-pods.json" kubectl get pods -n "${NAMESPACE}" -l "app.kubernetes.io/instance=${RELEASE_NAME}" -o json
  capture "${RUN_DIR}/split-endpointslices.json" kubectl get endpointslice -n "${NAMESPACE}" \
    -l "app.kubernetes.io/instance=${RELEASE_NAME}" -o json
  python3 - "${RUN_DIR}/split-pods.json" "${RUN_DIR}/split-endpointslices.json" "${CONTROLLER_SERVICE}" "${WEBHOOK_SERVICE}" <<'PY'
import json
import sys

pods_path, slices_path, controller_service, webhook_service = sys.argv[1:]
pods = {pod["metadata"]["name"]: pod["metadata"]["labels"].get("app.kubernetes.io/component")
        for pod in json.load(open(pods_path, encoding="utf-8"))["items"]}
slices = json.load(open(slices_path, encoding="utf-8"))["items"]
for service, expected_component in ((controller_service, "controller"), (webhook_service, "webhook")):
    selected = [item for item in slices if item["metadata"]["labels"].get("kubernetes.io/service-name") == service]
    if not selected:
        raise AssertionError(f"Service {service} has no EndpointSlice")
    targets = [endpoint.get("targetRef", {}).get("name") for item in selected for endpoint in item.get("endpoints", [])]
    if not targets:
        raise AssertionError(f"Service {service} has no endpoints")
    if any(pods.get(target) != expected_component for target in targets):
        raise AssertionError(f"Service {service} targets unexpected Pods: {targets}")
print("split EndpointSlice isolation passed")
PY
  log "split Services select only their matching component Pods"
}

write_manifest() {
  local file="$1"
  local version="$2"
  local name="$3"
  local message="$4"
  local replicas="$5"
  local log_level="${6:-}"
  python3 - "${file}" "${version}" "${name}" "${message}" "${replicas}" "${log_level}" <<'PY'
import json
import sys

path, version, name, message, replicas, log_level = sys.argv[1:]
spec = {"message": message}
if replicas != "omit":
    spec["replicas"] = int(replicas)
if log_level:
    spec["logLevel"] = log_level
document = {"apiVersion": f"example.com/{version}", "kind": "EchoResource", "metadata": {"name": name}, "spec": spec}
with open(path, "w", encoding="utf-8") as target:
    target.write(json.dumps(document))
PY
}

assert_file_contains() {
  local file="$1"
  local expected="$2"
  python3 - "${file}" "${expected}" <<'PY'
import pathlib
import sys
if sys.argv[2] not in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace"):
    raise SystemExit(f"expected text not present: {sys.argv[2]}")
PY
}

wait_for_resource() {
  local kind="$1"
  local name="$2"
  local attempt
  for attempt in $(seq 1 90); do
    if kubectl get "${kind}" "${name}" -n "${NAMESPACE}" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  die "${kind}/${name} was not created"
}

wait_for_absence() {
  local kind="$1"
  local name="$2"
  local attempt
  for attempt in $(seq 1 90); do
    if ! kubectl get "${kind}" "${name}" -n "${NAMESPACE}" >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  die "${kind}/${name} still exists after cleanup"
}

capture_raw_json() {
  local output="$1"
  local warnings="$2"
  local path="$3"
  record_command kubectl get --raw "${path}"
  kubectl get --raw "${path}" >"${output}" 2>"${warnings}"
}

capture_admission_diagnostics() {
  local webhook_pod
  capture "${RUN_DIR}/admission-validating-configuration.yaml" kubectl get validatingwebhookconfiguration "${RUNTIME_ADMISSION_NAME}" -o yaml
  capture "${RUN_DIR}/admission-mutating-configuration.yaml" kubectl get mutatingwebhookconfiguration "${RUNTIME_ADMISSION_NAME}" -o yaml
  capture "${RUN_DIR}/admission-endpointslices.yaml" kubectl get endpointslice -n "${NAMESPACE}" \
    -l "kubernetes.io/service-name=${WEBHOOK_SERVICE}" -o yaml
  webhook_pod="$(wait_for_running_webhook_pod)"
  capture "${RUN_DIR}/admission-webhook-pod.log" kubectl logs "pod/${webhook_pod}" -n "${NAMESPACE}"
}

exercise_admission_conversion_reconciliation() {
  local invalid="${TMP_DIR}/invalid.json"
  local mutated="${TMP_DIR}/mutated.json"
  local alpha_two="${TMP_DIR}/alpha-two.json"
  local reconciliation="${TMP_DIR}/reconciliation.json"
  local invalid_name="invalid-${RUN_TOKEN}"
  local invalid_message
  local mutation_name="mutation-${RUN_TOKEN}"
  local alpha_two_name="alpha-two-${RUN_TOKEN}"
  local reconcile_name="reconcile-${RUN_TOKEN}"

  invalid_message="$(printf 'x%.0s' {1..141})"
  write_manifest "${invalid}" v1alpha2 "${invalid_name}" "${invalid_message}" 1
  capture_expected_failure "${RUN_DIR}/admission-deny.log" kubectl apply -n "${NAMESPACE}" -f "${invalid}"
  capture_admission_diagnostics
  assert_file_contains "${RUN_DIR}/admission-deny.log" "spec.message must be 140 characters or fewer"

  write_manifest "${mutated}" v1alpha1 "${mutation_name}" mutation 0
  capture "${RUN_DIR}/mutation-create.log" kubectl apply -n "${NAMESPACE}" -f "${mutated}"
  capture "${RUN_DIR}/mutation-read.json" kubectl get echoresource "${mutation_name}" -n "${NAMESPACE}" -o json
  python3 - "${RUN_DIR}/mutation-read.json" <<'PY'
import json
import sys
resource = json.load(open(sys.argv[1], encoding="utf-8"))
assert resource["metadata"]["annotations"].get("echo.example.com/mutated") == "true", "mutating webhook annotation missing"
assert resource["spec"].get("replicas") == 1, "mutating webhook did not default replicas to 1"
PY

  capture_raw_json "${RUN_DIR}/conversion-alpha1-to-alpha2.json" "${RUN_DIR}/conversion-alpha1-to-alpha2.stderr.log" \
    "/apis/example.com/v1alpha2/namespaces/${NAMESPACE}/echoresources/${mutation_name}"
  write_manifest "${alpha_two}" v1alpha2 "${alpha_two_name}" alpha-two 2 DEBUG
  capture "${RUN_DIR}/conversion-alpha2-create.log" kubectl apply -n "${NAMESPACE}" -f "${alpha_two}"
  capture_raw_json "${RUN_DIR}/conversion-alpha2-to-alpha1.json" "${RUN_DIR}/conversion-alpha2-to-alpha1.stderr.log" \
    "/apis/example.com/v1alpha1/namespaces/${NAMESPACE}/echoresources/${alpha_two_name}"
  python3 - "${RUN_DIR}/conversion-alpha1-to-alpha2.json" "${RUN_DIR}/conversion-alpha2-to-alpha1.json" <<'PY'
import json
import sys
alpha_two, alpha_one = (json.load(open(path, encoding="utf-8")) for path in sys.argv[1:])
assert alpha_two["apiVersion"] == "example.com/v1alpha2", "v1alpha1 to v1alpha2 conversion did not occur"
assert alpha_two["spec"].get("logLevel") == "INFO", "v1alpha2 conversion did not default logLevel"
assert alpha_one["apiVersion"] == "example.com/v1alpha1", "v1alpha2 to v1alpha1 conversion did not occur"
assert "logLevel" not in alpha_one["spec"], "v1alpha1 conversion must drop logLevel"
PY

  write_manifest "${reconciliation}" v1alpha2 "${reconcile_name}" reconciliation 1 INFO
  capture "${RUN_DIR}/reconciliation-create.log" kubectl apply -n "${NAMESPACE}" -f "${reconciliation}"
  wait_for_resource deployment "${reconcile_name}"
  wait_for_resource service "${reconcile_name}"
  capture "${RUN_DIR}/reconciliation-status.json" kubectl get echoresource "${reconcile_name}" -n "${NAMESPACE}" -o json
  python3 - "${RUN_DIR}/reconciliation-status.json" <<'PY'
import json
import sys
status = json.load(open(sys.argv[1], encoding="utf-8")).get("status", {})
assert status.get("phase") == "READY", f"unexpected reconciliation phase: {status}"
assert status.get("message") == "reconciliation", f"unexpected reconciliation message: {status}"
PY

  for name in "${mutation_name}" "${alpha_two_name}" "${reconcile_name}"; do
    capture "${RUN_DIR}/delete-${name}.log" kubectl delete echoresource "${name}" -n "${NAMESPACE}" --wait=false
    wait_for_absence echoresource "${name}"
    wait_for_absence deployment "${name}"
    wait_for_absence service "${name}"
  done
  log "admission, mutation, bidirectional conversion, reconciliation status, and deletion cleanup passed"
}

AUTH_INDEX=0
can_i() {
  local expected="$1"
  local identity="$2"
  shift 2
  local output="${RUN_DIR}/auth-$((AUTH_INDEX += 1)).log"
  local answer
  local decision
  local line
  record_command kubectl auth can-i "$@" "--as=${identity}"
  answer="$(kubectl auth can-i "$@" "--as=${identity}" 2>&1)" || true
  printf '%s\n' "${answer}" >"${output}"
  decision=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && decision="${line}"
  done <<< "${answer}"
  [[ "${decision}" == yes || "${decision}" == no ]] || return 1
  [[ "${decision}" == "${expected}" ]]
}

expect_can_i() {
  local expected="$1"
  local identity="$2"
  shift 2
  can_i "${expected}" "${identity}" "$@" || die "authorization assertion failed: expected ${expected} for ${identity}: $*"
}

assert_authorization_matrix() {
  local controller_identity="system:serviceaccount:${NAMESPACE}:${CONTROLLER_SA}"
  local webhook_identity="system:serviceaccount:${NAMESPACE}:${WEBHOOK_SA}"
  expect_can_i yes "${controller_identity}" get "echoresources.example.com/reconcile-${RUN_TOKEN}" --namespace "${WATCHED_NAMESPACE}"
  if [[ "${DEPLOYMENT_MODE}" == "combined" ]]; then
    expect_can_i yes "${controller_identity}" get "validatingwebhookconfigurations.admissionregistration.k8s.io/${RUNTIME_ADMISSION_NAME}"
  else
    expect_can_i no "${controller_identity}" get "validatingwebhookconfigurations.admissionregistration.k8s.io/${RUNTIME_ADMISSION_NAME}"
    expect_can_i yes "${webhook_identity}" get "validatingwebhookconfigurations.admissionregistration.k8s.io/${RUNTIME_ADMISSION_NAME}"
    expect_can_i no "${webhook_identity}" get "echoresources.example.com/reconcile-${RUN_TOKEN}" --namespace "${WATCHED_NAMESPACE}"
    expect_can_i no "${webhook_identity}" get leases.coordination.k8s.io/echo-operator-lock --namespace "${NAMESPACE}"
  fi
  if can_i yes "${controller_identity}" delete customresourcedefinitions.apiextensions.k8s.io/echoresources.example.com; then
    die "deliberately incorrect permission assertion unexpectedly passed"
  fi
  log "exact ServiceAccount authorization matrix and negative assertion passed"
}

write_external_values() {
  local output="$1"
  local helm_owned="$2"
  local ca_file="$3"
  python3 - "${output}" "${DEPLOYMENT_MODE}" "${WATCHED_NAMESPACE}" "${TLS_SECRET}" "${helm_owned}" "${ca_file}" <<'PY'
import pathlib
import sys

output, mode, watched, secret, helm_owned, ca_path = sys.argv[1:]
ca = pathlib.Path(ca_path).read_text(encoding="utf-8")
with open(output, "w", encoding="utf-8") as target:
    target.write(f"deploymentMode: {mode}\noperator:\n  namespace: {watched}\nwebhook:\n")
    target.write("  certAutoGenerate: false\n")
    target.write(f"  createWebhookConfigurations: {helm_owned}\n")
    target.write("  failurePolicy: Fail\n")
    target.write(f"  tls:\n    secretName: {secret}\n")
    target.write("  caBundle: |\n")
    for line in ca.splitlines():
        target.write(f"    {line}\n")
PY
}

create_external_tls_secret() {
  TLS_SECRET="${RELEASE_NAME}-webhook-tls"
  ADMISSION_CA_SECRET="${TLS_SECRET}"
  CA_FILE="${TMP_DIR}/ca.crt"
  local ca_key="${TMP_DIR}/ca.key"
  local serving_key="${TMP_DIR}/tls.key"
  local serving_csr="${TMP_DIR}/tls.csr"
  local serving_cert="${TMP_DIR}/tls.crt"
  local san_file="${TMP_DIR}/san.ext"
  record_command openssl req -x509 -newkey rsa:2048 -nodes -keyout "${ca_key}" -out "${CA_FILE}" -days 1 -subj /CN=echo-smoke-ca
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "${ca_key}" -out "${CA_FILE}" -days 1 -subj /CN=echo-smoke-ca >"${RUN_DIR}/openssl-ca.log" 2>&1
  [[ -n "${WEBHOOK_SERVICE:-}" ]] || die "webhook Service identity must be resolved before generating external TLS"
  record_command openssl req -newkey rsa:2048 -nodes -keyout "${serving_key}" -out "${serving_csr}" -subj /CN="${WEBHOOK_SERVICE}"
  openssl req -newkey rsa:2048 -nodes -keyout "${serving_key}" -out "${serving_csr}" -subj /CN="${WEBHOOK_SERVICE}" >"${RUN_DIR}/openssl-serving.log" 2>&1
  printf 'subjectAltName=DNS:%s,DNS:%s.%s,DNS:%s.%s.svc,DNS:%s.%s.svc.cluster.local\n' \
    "${WEBHOOK_SERVICE}" "${WEBHOOK_SERVICE}" "${NAMESPACE}" "${WEBHOOK_SERVICE}" "${NAMESPACE}" "${WEBHOOK_SERVICE}" "${NAMESPACE}" >"${san_file}"
  record_command openssl x509 -req -in "${serving_csr}" -CA "${CA_FILE}" -CAkey "${ca_key}" -CAcreateserial -out "${serving_cert}" -days 1 -extfile "${san_file}"
  openssl x509 -req -in "${serving_csr}" -CA "${CA_FILE}" -CAkey "${ca_key}" -CAcreateserial -out "${serving_cert}" -days 1 -extfile "${san_file}" >>"${RUN_DIR}/openssl-serving.log" 2>&1
  record_command kubectl create secret generic "${TLS_SECRET}" -n "${NAMESPACE}" --from-file="ca.crt=${CA_FILE}" --from-file="tls.crt=${serving_cert}" --from-file="tls.key=${serving_key}"
  kubectl create secret generic "${TLS_SECRET}" -n "${NAMESPACE}" --from-file="ca.crt=${CA_FILE}" --from-file="tls.crt=${serving_cert}" --from-file="tls.key=${serving_key}" >"${RUN_DIR}/external-tls-secret.log" 2>&1
}

patch_preserved_crd_external_ca() {
  local ca_file="$1"
  local patch_file="${TMP_DIR}/external-crd-client-config.json"
  [[ -n "${HELM_POST_RENDERER}" ]] || return 0
  python3 - "${patch_file}" "${ca_file}" "${NAMESPACE}" "${WEBHOOK_SERVICE}" <<'PY'
import base64
import json
import pathlib
import sys

patch_path, ca_path, namespace, service_name = sys.argv[1:]
patch = {
    "spec": {
        "conversion": {
            "strategy": "Webhook",
            "webhook": {
                "conversionReviewVersions": ["v1"],
                "clientConfig": {
                    "service": {"namespace": namespace, "name": service_name, "path": "/convert", "port": 443},
                    "caBundle": base64.b64encode(pathlib.Path(ca_path).read_bytes()).decode(),
                },
            },
        },
    },
}
pathlib.Path(patch_path).write_text(json.dumps(patch), encoding="utf-8")
PY
  capture "${RUN_DIR}/preserved-crd-external-ca-patch.log" kubectl patch crd echoresources.example.com --type=merge --patch-file "${patch_file}"
}

wait_for_cluster_resource() {
  local kind="$1"
  local name="$2"
  local expected="$3"
  local attempt
  for attempt in $(seq 1 90); do
    if kubectl get "${kind}" "${name}" >/dev/null 2>&1; then
      [[ "${expected}" == present ]] && return
    else
      [[ "${expected}" == absent ]] && return
    fi
    sleep 1
  done
  die "cluster resource ${kind}/${name} did not become ${expected}"
}

extract_ca_bundle() {
  local label="$1"
  local command_jsonpath="$2"
  local b64="${RUN_DIR}/ca-${label}.b64"
  local decoded="${RUN_DIR}/ca-${label}.pem"
  capture "${b64}" kubectl get ${command_jsonpath}
  python3 - "${b64}" "${decoded}" <<'PY'
import base64
import pathlib
import sys
pathlib.Path(sys.argv[2]).write_bytes(base64.b64decode(pathlib.Path(sys.argv[1]).read_bytes(), validate=True))
PY
  cmp -s "${CA_FILE}" "${decoded}" || die "external TLS CA differs for ${label}"
}

verify_external_ca_bytes() {
  local ownership="$1"
  extract_ca_bundle "${ownership}-secret" "secret ${TLS_SECRET} -n ${NAMESPACE} -o jsonpath={.data.ca\\.crt}"
  extract_ca_bundle "${ownership}-crd" "crd echoresources.example.com -o jsonpath={.spec.conversion.webhook.clientConfig.caBundle}"
  if [[ "${ownership}" == helm ]]; then
    extract_ca_bundle "helm-validating" "validatingwebhookconfiguration ${HELM_VALIDATING_NAME} -o jsonpath={.webhooks[0].clientConfig.caBundle}"
    extract_ca_bundle "helm-mutating" "mutatingwebhookconfiguration ${HELM_MUTATING_NAME} -o jsonpath={.webhooks[0].clientConfig.caBundle}"
  else
    extract_ca_bundle "runtime-validating" "validatingwebhookconfiguration ${RUNTIME_ADMISSION_NAME} -o jsonpath={.webhooks[0].clientConfig.caBundle}"
    extract_ca_bundle "runtime-mutating" "mutatingwebhookconfiguration ${RUNTIME_ADMISSION_NAME} -o jsonpath={.webhooks[0].clientConfig.caBundle}"
  fi
  shasum -a 256 "${RUN_DIR}"/ca-${ownership}-*.pem >"${RUN_DIR}/ca-${ownership}-hashes.txt"
  wc -c "${RUN_DIR}"/ca-${ownership}-*.pem >"${RUN_DIR}/ca-${ownership}-bytes.txt"
  log "${ownership}-owned external TLS CA bytes are equal across Secret, CRD, and admission configurations"
}

assert_runtime_owner() {
  wait_for_cluster_resource validatingwebhookconfiguration "${RUNTIME_ADMISSION_NAME}" present
  wait_for_cluster_resource mutatingwebhookconfiguration "${RUNTIME_ADMISSION_NAME}" present
  wait_for_cluster_resource validatingwebhookconfiguration "${HELM_VALIDATING_NAME}" absent
  wait_for_cluster_resource mutatingwebhookconfiguration "${HELM_MUTATING_NAME}" absent
}

assert_helm_owner() {
  wait_for_cluster_resource validatingwebhookconfiguration "${HELM_VALIDATING_NAME}" present
  wait_for_cluster_resource mutatingwebhookconfiguration "${HELM_MUTATING_NAME}" present
  wait_for_cluster_resource validatingwebhookconfiguration "${RUNTIME_ADMISSION_NAME}" absent
  wait_for_cluster_resource mutatingwebhookconfiguration "${RUNTIME_ADMISSION_NAME}" absent
}

capture_cluster_state() {
  local label="$1"
  capture "${RUN_DIR}/resources-${label}.yaml" kubectl get deployment,service,pod,endpointslice,serviceaccount,role,rolebinding -n "${NAMESPACE}" -o yaml
  capture "${RUN_DIR}/crd-${label}.yaml" kubectl get crd echoresources.example.com -o yaml
  capture "${RUN_DIR}/validating-${label}.yaml" kubectl get validatingwebhookconfiguration -o yaml
  capture "${RUN_DIR}/mutating-${label}.yaml" kubectl get mutatingwebhookconfiguration -o yaml
}

run_baseline_upgrade() {
  [[ -f "${BASELINE_ARCHIVE}" ]] || die "immutable baseline chart archive is missing: ${BASELINE_ARCHIVE}"
  local base_dir="${TMP_DIR}/baseline-chart"
  mkdir -p "${base_dir}"
  capture "${RUN_DIR}/baseline-archive-list.log" tar -tf "${BASELINE_ARCHIVE}"
  tar -xf "${BASELINE_ARCHIVE}" -C "${base_dir}"
  local baseline_chart
  baseline_chart="$(python3 - "${base_dir}" <<'PY'
import os
import sys
for root, _, files in os.walk(sys.argv[1]):
    if "Chart.yaml" in files:
        print(root)
        break
else:
    raise SystemExit("Chart.yaml not found in immutable baseline archive")
PY
)"
  deploy_chart baseline "${baseline_chart}" "" true
  resolve_topology
  wait_for_rollout "${CONTROLLER_DEPLOYMENT}"
  capture "${RUN_DIR}/baseline-deployment.json" kubectl get deployment "${CONTROLLER_DEPLOYMENT}" -n "${NAMESPACE}" -o json
  capture "${RUN_DIR}/baseline-service.json" kubectl get service "${WEBHOOK_SERVICE}" -n "${NAMESPACE}" -o json
  deploy_chart default-combined "${CHART_DIR}" "" true
  resolve_topology
  wait_for_rollout "${CONTROLLER_DEPLOYMENT}"
  capture "${RUN_DIR}/upgraded-deployment.json" kubectl get deployment "${CONTROLLER_DEPLOYMENT}" -n "${NAMESPACE}" -o json
  capture "${RUN_DIR}/upgraded-service.json" kubectl get service "${WEBHOOK_SERVICE}" -n "${NAMESPACE}" -o json
  python3 - "${RUN_DIR}/baseline-deployment.json" "${RUN_DIR}/upgraded-deployment.json" "${RUN_DIR}/baseline-service.json" "${RUN_DIR}/upgraded-service.json" <<'PY'
import json
import sys
before_deployment, after_deployment, before_service, after_service = (json.load(open(path, encoding="utf-8")) for path in sys.argv[1:])
assert before_deployment["metadata"]["name"] == after_deployment["metadata"]["name"], "combined Deployment identity drifted"
assert before_deployment["spec"]["selector"] == after_deployment["spec"]["selector"], "combined Deployment selector drifted"
assert before_service["metadata"]["name"] == after_service["metadata"]["name"], "webhook Service identity drifted"
assert before_service["spec"]["selector"] == after_service["spec"]["selector"], "webhook Service selector drifted"
PY
  log "immutable baseline combined chart upgraded in place without selector or Service identity drift"
}

upgrade_to_external_runtime() {
  local values="${TMP_DIR}/external-runtime-values.yaml"
  write_external_values "${values}" false "${CA_FILE}"
  deploy_chart external-runtime "${CHART_DIR}" "${values}" true
  resolve_topology
  patch_preserved_crd_external_ca "${CA_FILE}"
  wait_for_rollout "${WEBHOOK_DEPLOYMENT}"
  assert_runtime_owner
  verify_external_ca_bytes runtime
}

upgrade_runtime_to_helm() {
  local values="${TMP_DIR}/external-helm-values.yaml"
  write_external_values "${values}" true "${CA_FILE}"
  deploy_chart runtime-to-helm "${CHART_DIR}" "${values}" true
  resolve_topology
  patch_preserved_crd_external_ca "${CA_FILE}"
  wait_for_rollout "${WEBHOOK_DEPLOYMENT}"
  assert_helm_owner
  verify_external_ca_bytes helm
  log "in-place runtime to Helm ownership transition converged to one owner of each admission configuration"
}

upgrade_helm_to_runtime_with_held_predecessors() {
  local values="${TMP_DIR}/external-runtime-values.yaml"
  local helm_webhook_pod
  helm_webhook_pod="$(wait_for_running_webhook_pod)"
  write_external_values "${values}" false "${CA_FILE}"
  capture "${RUN_DIR}/hold-validating.log" kubectl patch validatingwebhookconfiguration "${HELM_VALIDATING_NAME}" --type=merge -p '{"metadata":{"finalizers":["echo-smoke.hold"]}}'
  capture "${RUN_DIR}/hold-mutating.log" kubectl patch mutatingwebhookconfiguration "${HELM_MUTATING_NAME}" --type=merge -p '{"metadata":{"finalizers":["echo-smoke.hold"]}}'
  deploy_chart helm-to-runtime-held "${CHART_DIR}" "${values}" false
  resolve_topology
  patch_preserved_crd_external_ca "${CA_FILE}"
  local held_port
  local held_pod
  held_pod="$(wait_for_running_webhook_pod "${helm_webhook_pod}")"
  capture "${RUN_DIR}/held-webhook-pod.json" kubectl get pod "${held_pod}" -n "${NAMESPACE}" -o json
  capture "${RUN_DIR}/held-webhook-pod.log" kubectl logs "pod/${held_pod}" -n "${NAMESPACE}"
  wait_for_pod_log "${held_pod}" "Metrics/health server started"
  start_port_forward held-webhook "pod/${held_pod}"
  held_port="${PORT_FORWARD_PORT}"
  assert_http_code held-webhook "${held_port}" /readyz 503
  wait_for_cluster_resource validatingwebhookconfiguration "${RUNTIME_ADMISSION_NAME}" absent
  wait_for_cluster_resource mutatingwebhookconfiguration "${RUNTIME_ADMISSION_NAME}" absent
  capture "${RUN_DIR}/release-held-validating.log" kubectl patch validatingwebhookconfiguration "${HELM_VALIDATING_NAME}" --type=merge -p '{"metadata":{"finalizers":[]}}'
  capture "${RUN_DIR}/release-held-mutating.log" kubectl patch mutatingwebhookconfiguration "${HELM_MUTATING_NAME}" --type=merge -p '{"metadata":{"finalizers":[]}}'
  wait_for_cluster_resource validatingwebhookconfiguration "${HELM_VALIDATING_NAME}" absent
  wait_for_cluster_resource mutatingwebhookconfiguration "${HELM_MUTATING_NAME}" absent
  wait_for_rollout "${WEBHOOK_DEPLOYMENT}"
  assert_runtime_owner
  assert_http_code held-webhook "${held_port}" /readyz 200
  verify_external_ca_bytes runtime
  log "in-place Helm to runtime transition held predecessors at NotReady, then registered exactly one owner after deletion"
}

assert_mismatched_ca_fails() {
  local wrong_key="${TMP_DIR}/wrong-ca.key"
  local wrong_ca="${TMP_DIR}/wrong-ca.crt"
  local values="${TMP_DIR}/mismatched-ca-values.yaml"
  record_command openssl req -x509 -newkey rsa:2048 -nodes -keyout "${wrong_key}" -out "${wrong_ca}" -days 1 -subj /CN=wrong-echo-smoke-ca
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "${wrong_key}" -out "${wrong_ca}" -days 1 -subj /CN=wrong-echo-smoke-ca >"${RUN_DIR}/openssl-wrong-ca.log" 2>&1
  write_external_values "${values}" true "${wrong_ca}"
  deploy_chart mismatched-ca "${CHART_DIR}" "${values}" true
  resolve_topology
  patch_preserved_crd_external_ca "${wrong_ca}"
  wait_for_rollout "${WEBHOOK_DEPLOYMENT}"
  capture "${RUN_DIR}/mismatched-ca-failure-policy.log" kubectl get validatingwebhookconfiguration "${HELM_VALIDATING_NAME}" -o jsonpath='{.webhooks[0].failurePolicy}'
  assert_file_contains "${RUN_DIR}/mismatched-ca-failure-policy.log" Fail
  local fixture="${TMP_DIR}/mismatched-ca-request.json"
  write_manifest "${fixture}" v1alpha2 "mismatched-${RUN_TOKEN}" mismatch 1 INFO
  capture_expected_failure "${RUN_DIR}/mismatched-ca-webhook-request.log" kubectl apply -n "${NAMESPACE}" -f "${fixture}"
  assert_file_contains "${RUN_DIR}/mismatched-ca-webhook-request.log" "failed calling webhook"
  log "mismatched external CA caused a failurePolicy=Fail API-server webhook request failure"
}

require_command kubectl
require_command helm
require_command curl
require_command openssl
require_command python3
static_contract | tee "${RUN_DIR}/static-contract.log"
assert_invalid_context_fails
if ! capture "${RUN_DIR}/kube-context.log" kubectl config current-context; then
  die "Kubernetes context is unavailable; see ${RUN_DIR}/kube-context.log"
fi
if ! capture "${RUN_DIR}/cluster-info.log" kubectl cluster-info; then
  die "Kubernetes cluster is unreachable; see ${RUN_DIR}/cluster-info.log"
fi
create_isolated_namespace
configure_crd_lifecycle
build_image

if [[ "${DEPLOYMENT_MODE}" == "combined" ]]; then
  run_baseline_upgrade
else
  WEBHOOK_SERVICE="${RELEASE_NAME}-echo-operator"
  create_external_tls_secret
  initial_values="${TMP_DIR}/initial-external-runtime-values.yaml"
  write_external_values "${initial_values}" false "${CA_FILE}"
  deploy_chart initial-split "${CHART_DIR}" "${initial_values}" true
  resolve_topology
  patch_preserved_crd_external_ca "${CA_FILE}"
  wait_for_rollout "${CONTROLLER_DEPLOYMENT}"
  wait_for_rollout "${WEBHOOK_DEPLOYMENT}"
  assert_runtime_owner
  verify_external_ca_bytes runtime
fi

resolve_topology
wait_for_rollout "${CONTROLLER_DEPLOYMENT}"
wait_for_rollout "${WEBHOOK_DEPLOYMENT}"
assert_runtime_owner
assert_readiness_endpoints
assert_webhook_admission_transport
assert_split_endpoint_isolation
exercise_admission_conversion_reconciliation
assert_authorization_matrix
capture_cluster_state core

if [[ "${DEPLOYMENT_MODE}" == "combined" ]]; then
  create_external_tls_secret
  upgrade_to_external_runtime
fi
upgrade_runtime_to_helm
upgrade_helm_to_runtime_with_held_predecessors
assert_mismatched_ca_fails
capture_cluster_state final
log "all ${DEPLOYMENT_MODE} topology smoke assertions passed; cleanup will now remove the release and namespace"
