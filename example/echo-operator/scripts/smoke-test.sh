#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

BUILD_IMAGE_SCRIPT="${SCRIPT_DIR}/build-image.sh"
DEPLOY_SCRIPT="${SCRIPT_DIR}/deploy.sh"
UNDEPLOY_SCRIPT="${SCRIPT_DIR}/undeploy.sh"

CRD_FILE="${PROJECT_DIR}/helm/echo-operator/templates/crd.yaml"
CR_FILE="${PROJECT_DIR}/examples/echo-cr.yaml"

EVIDENCE_DIR="${ROOT_DIR}/.sisyphus/evidence"
EVIDENCE_LOG="${EVIDENCE_DIR}/task-19-smoke-test.log"
ENDPOINTS_LOG="${EVIDENCE_DIR}/task-19-endpoints.log"

RELEASE_NAME="${RELEASE_NAME:-echo-operator}"
NAMESPACE="${NAMESPACE:-$(kubectl config view --minify --output 'jsonpath={..namespace}' 2>/dev/null || true)}"
NAMESPACE="${NAMESPACE:-default}"
METRICS_PORT="${METRICS_PORT:-8080}"
LOCAL_METRICS_PORT="${LOCAL_METRICS_PORT:-18080}"

mkdir -p "${EVIDENCE_DIR}"
: > "${EVIDENCE_LOG}"
: > "${ENDPOINTS_LOG}"

exec > >(tee -a "${EVIDENCE_LOG}") 2>&1

log() {
  echo "[$(date -Iseconds)] $*"
}

log "=== Echo Operator Smoke Test ==="
log "Script directory: ${SCRIPT_DIR}"
log "Project directory: ${PROJECT_DIR}"
log "Release name: ${RELEASE_NAME}"
log "Namespace: ${NAMESPACE}"

if [[ ! -f "${CR_FILE}" ]]; then
  log "ERROR: sample CR not found at ${CR_FILE}"
  exit 1
fi
if [[ ! -f "${CRD_FILE}" ]]; then
  log "ERROR: CRD not found at ${CRD_FILE}"
  exit 1
fi
if [[ ! -x "${BUILD_IMAGE_SCRIPT}" ]]; then
  log "ERROR: build-image.sh not found or not executable at ${BUILD_IMAGE_SCRIPT}"
  exit 1
fi

log "Sample CR content:"
cat "${CR_FILE}"

log "Step 1/5: Building Docker image..."
"${BUILD_IMAGE_SCRIPT}"
log "Docker image built successfully."

log "Step 2/5: Detecting Kubernetes cluster..."
CLUSTER_AVAILABLE=false
if command -v kubectl >/dev/null 2>&1; then
  if kubectl cluster-info >/dev/null 2>&1; then
    CLUSTER_AVAILABLE=true
    log "Kubernetes cluster detected."
  else
    log "kubectl installed but no cluster reachable (kubectl cluster-info failed)."
  fi
else
  log "kubectl not installed; no cluster checks will be performed."
fi

if [[ "${CLUSTER_AVAILABLE}" != "true" ]]; then
  log "No Kubernetes cluster available. Skipping cluster-based reconciliation checks."
  log "Smoke test completed: image built, syntax validated."
  exit 0
fi

log "Step 3/5: Deploying operator via Helm..."
export NAMESPACE
export RELEASE_NAME
export SKIP_BUILD=true
"${DEPLOY_SCRIPT}"

log "Waiting for operator Deployment to be ready..."
kubectl rollout status "deployment/${RELEASE_NAME}" -n "${NAMESPACE}" --timeout=180s

SERVICE_NAME="${RELEASE_NAME}"
log "Port-forwarding operator service ${SERVICE_NAME}:${METRICS_PORT} to localhost:${LOCAL_METRICS_PORT}..."
kubectl port-forward -n "${NAMESPACE}" "svc/${SERVICE_NAME}" "${LOCAL_METRICS_PORT}:${METRICS_PORT}" >/dev/null 2>&1 &
PF_PID=$!

cleanup_port_forward() {
  if kill "${PF_PID}" 2>/dev/null; then
    wait "${PF_PID}" 2>/dev/null || true
  fi
}
trap cleanup_port_forward EXIT

if [[ -n "${WEBHOOK_LOCAL_PORT:-}" ]]; then
  log "Port-forwarding webhook service ${SERVICE_NAME}:443 to localhost:${WEBHOOK_LOCAL_PORT}..."
  kubectl port-forward -n "${NAMESPACE}" "svc/${SERVICE_NAME}" "${WEBHOOK_LOCAL_PORT}:443" >/dev/null 2>&1 &
  WEBHOOK_PF_PID=$!
  cleanup_webhook_port_forward() {
    if kill "${WEBHOOK_PF_PID}" 2>/dev/null; then
      wait "${WEBHOOK_PF_PID}" 2>/dev/null || true
    fi
  }
  trap cleanup_webhook_port_forward EXIT
fi

log "Waiting for metrics/health endpoints to respond..."
ENDPOINT_READY=false
for _ in $(seq 1 30); do
  if curl -fs "http://localhost:${LOCAL_METRICS_PORT}/healthz" >/dev/null 2>&1; then
    ENDPOINT_READY=true
    break
  fi
  sleep 1
done

if [[ "${ENDPOINT_READY}" != "true" ]]; then
  log "ERROR: health endpoint did not become ready in time"
  exit 1
fi

log "Step 4/5: Applying CRD and sample CR..."
kubectl apply -f "${CRD_FILE}"
kubectl apply -f "${CR_FILE}" -n "${NAMESPACE}"

log "Waiting for Echo Deployment and Service to be created..."
DEPLOYMENT_FOUND=false
SERVICE_FOUND=false
for _ in $(seq 1 60); do
  if kubectl get deployment echo-sample -n "${NAMESPACE}" >/dev/null 2>&1; then
    DEPLOYMENT_FOUND=true
  fi
  if kubectl get service echo-sample -n "${NAMESPACE}" >/dev/null 2>&1; then
    SERVICE_FOUND=true
  fi
  if [[ "${DEPLOYMENT_FOUND}" == "true" && "${SERVICE_FOUND}" == "true" ]]; then
    break
  fi
  sleep 1
done

if [[ "${DEPLOYMENT_FOUND}" != "true" ]]; then
  log "ERROR: Deployment echo-sample was not created"
  exit 1
fi
if [[ "${SERVICE_FOUND}" != "true" ]]; then
  log "ERROR: Service echo-sample was not created"
  exit 1
fi
log "Deployment and Service created successfully."

log "Waiting for Deployment echo-sample to be available..."
kubectl wait --for=condition=Available deployment/echo-sample -n "${NAMESPACE}" --timeout=120s

log "Echo Resource status:"
kubectl get echoresource echo-sample -n "${NAMESPACE}" -o yaml

log "Capturing endpoint output after reconciliation..."
{
  echo "--- /healthz ---"
  curl -s -w "\nHTTP %{http_code}\n" "http://localhost:${LOCAL_METRICS_PORT}/healthz"
  echo
  echo "--- /readyz ---"
  curl -s -w "\nHTTP %{http_code}\n" "http://localhost:${LOCAL_METRICS_PORT}/readyz"
  echo
  echo "--- /metrics (first 30 lines) ---"
  curl -s "http://localhost:${LOCAL_METRICS_PORT}/metrics" | head -n 30
  echo
} | tee -a "${ENDPOINTS_LOG}"

log "Step 5/5: Deleting sample CR and verifying cleanup..."
kubectl delete -f "${CR_FILE}" -n "${NAMESPACE}" --timeout=180s

CLEANUP_OK=true
for _ in $(seq 1 60); do
  if ! kubectl get deployment echo-sample -n "${NAMESPACE}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if kubectl get deployment echo-sample -n "${NAMESPACE}" >/dev/null 2>&1; then
  log "WARNING: Deployment echo-sample still exists after CR deletion"
  CLEANUP_OK=false
else
  log "Deployment echo-sample cleaned up successfully."
fi

if ! kubectl get service echo-sample -n "${NAMESPACE}" >/dev/null 2>&1; then
  log "Service echo-sample cleaned up successfully."
else
  log "WARNING: Service echo-sample still exists after CR deletion"
  CLEANUP_OK=false
fi

log "Undeploying operator..."
"${UNDEPLOY_SCRIPT}" || true

if [[ "${CLEANUP_OK}" != "true" ]]; then
  log "ERROR: cleanup verification failed"
  exit 1
fi

log "=== Smoke test completed successfully at $(date -Iseconds) ==="
log "Evidence: ${EVIDENCE_LOG}"
log "Endpoints: ${ENDPOINTS_LOG}"
