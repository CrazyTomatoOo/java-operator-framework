#!/usr/bin/env bash
# End-to-end functional test: builds the image, deploys echo-operator to the current
# Kubernetes context, and exercises controller, admission webhook, event, health and
# garbage-collection behavior against the real API server. Requires docker + kubectl
# with a cluster that can run locally-built images (e.g. Docker Desktop).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
NAMESPACE="${NAMESPACE:-echo-e2e}"
IMAGE="${IMAGE:-echo-operator:e2e}"
PF_PORT="${PF_PORT:-18443}"
WORK_DIR="$(mktemp -d)"
PF_PID=""

log() { printf '\n== %s ==\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

cleanup() {
  [ -n "${PF_PID}" ] && kill "${PF_PID}" 2>/dev/null || true
  kubectl delete mutatingwebhookconfiguration echo-operator-mutating --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete validatingwebhookconfiguration echo-operator-validating --ignore-not-found >/dev/null 2>&1 || true
  kubectl delete namespace "${NAMESPACE}" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

expect_eq() {
  local actual="$1" expected="$2" what="$3"
  [ "${actual}" = "${expected}" ] || die "${what}: expected '${expected}', got '${actual}'"
  printf 'ok: %s = %s\n' "${what}" "${actual}"
}

wait_jsonpath() { # <kind> <name> <jsonpath> <expected> <what>
  local kind="$1" name="$2" path="$3" expected="$4" what="$5" actual=""
  for _ in $(seq 1 60); do
    actual="$(kubectl get "${kind}" "${name}" -n "${NAMESPACE}" -o "jsonpath=${path}" 2>/dev/null || true)"
    [ "${actual}" = "${expected}" ] && { printf 'ok: %s\n' "${what}"; return 0; }
    sleep 2
  done
  die "${what}: timed out waiting for ${kind}/${name} ${path}='${expected}' (last: '${actual}')"
}

preflight() {
  for cmd in kubectl docker openssl mvn curl; do
    command -v "${cmd}" >/dev/null 2>&1 || die "required command not found: ${cmd}"
  done
  kubectl cluster-info --request-timeout=5s >/dev/null 2>&1 || die "Kubernetes cluster is unreachable"
}

build() {
  log "build framework + example"
  mvn -q -f "${ROOT_DIR}/operator/framework/pom.xml" install -DskipTests
  mvn -q -f "${PROJECT_DIR}/pom.xml" package -DskipTests
  docker build -q -t "${IMAGE}" "${PROJECT_DIR}" >/dev/null
}

deploy() {
  log "deploy to namespace ${NAMESPACE}"
  kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

  # TLS for the webhook endpoint: CA + server cert for the in-cluster Service DNS name.
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "${WORK_DIR}/ca.key" \
    -out "${WORK_DIR}/ca.crt" -days 1 -subj "/CN=echo-e2e-ca" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -keyout "${WORK_DIR}/tls.key" \
    -out "${WORK_DIR}/tls.csr" -subj "/CN=echo-operator.${NAMESPACE}.svc" 2>/dev/null
  cat > "${WORK_DIR}/san.ext" <<EOF
subjectAltName=DNS:echo-operator.${NAMESPACE}.svc,DNS:echo-operator.${NAMESPACE}.svc.cluster.local
EOF
  openssl x509 -req -in "${WORK_DIR}/tls.csr" -CA "${WORK_DIR}/ca.crt" -CAkey "${WORK_DIR}/ca.key" \
    -CAcreateserial -out "${WORK_DIR}/tls.crt" -days 1 -extfile "${WORK_DIR}/san.ext" 2>/dev/null
  kubectl create secret tls echo-operator-tls --cert="${WORK_DIR}/tls.crt" --key="${WORK_DIR}/tls.key" \
    -n "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

  kubectl apply -n "${NAMESPACE}" -f "${PROJECT_DIR}/k8s/rbac.yaml"
  kubectl apply -n "${NAMESPACE}" -f "${PROJECT_DIR}/k8s/deployment.yaml"
  kubectl wait deployment/echo-operator -n "${NAMESPACE}" --for condition=available --timeout=180s
  printf 'ok: deployment available\n'
}

register_webhooks() {
  log "register admission webhooks (scoped to ${NAMESPACE})"
  local ca
  ca="$(base64 < "${WORK_DIR}/ca.crt" | tr -d '\n')"
  sed -e "s/__NAMESPACE__/${NAMESPACE}/g" -e "s/__CA__/${ca}/g" <<'EOF' | kubectl apply -f -
apiVersion: admissionregistration.k8s.io/v1
kind: MutatingWebhookConfiguration
metadata:
  name: echo-operator-mutating
webhooks:
  - name: mutate.echo.example.com
    admissionReviewVersions: ["v1"]
    sideEffects: None
    failurePolicy: Fail
    namespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: __NAMESPACE__
    clientConfig:
      service:
        name: echo-operator
        namespace: __NAMESPACE__
        path: /operator-framework/webhooks/mutate/echomutator
      caBundle: __CA__
    rules:
      - apiGroups: [""]
        apiVersions: ["v1"]
        resources: ["configmaps"]
        operations: ["CREATE", "UPDATE"]
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingWebhookConfiguration
metadata:
  name: echo-operator-validating
webhooks:
  - name: validate.echo.example.com
    admissionReviewVersions: ["v1"]
    sideEffects: None
    failurePolicy: Fail
    namespaceSelector:
      matchLabels:
        kubernetes.io/metadata.name: __NAMESPACE__
    clientConfig:
      service:
        name: echo-operator
        namespace: __NAMESPACE__
        path: /operator-framework/webhooks/validate/echovalidator
      caBundle: __CA__
    rules:
      - apiGroups: [""]
        apiVersions: ["v1"]
        resources: ["configmaps"]
        operations: ["CREATE", "UPDATE"]
EOF
  printf 'ok: webhook configurations applied\n'
}

test_mutation_and_controller() {
  log "mutation webhook + reconcile + event"
  kubectl apply -n "${NAMESPACE}" -f - <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: greeting
  labels:
    echo.example.com/enabled: "true"
data:
  other: x
EOF
  expect_eq "$(kubectl get configmap greeting -n "${NAMESPACE}" -o jsonpath='{.data.message}')" \
    "hello world" "mutated data.message"
  wait_jsonpath configmap greeting-echo '{.data.message}' "HELLO WORLD" "owned child reconciled"
  kubectl get events -n "${NAMESPACE}" --field-selector reason=Echoed --no-headers 2>/dev/null | grep -q . \
    || die "no Kubernetes Event with reason=Echoed published"
  printf 'ok: Echoed event published\n'
}

test_validation() {
  log "validating webhook denies blank message"
  local output
  if output="$(kubectl apply -n "${NAMESPACE}" -f - 2>&1)"; then
    die "blank-message ConfigMap was accepted"
  fi <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: blank
  labels:
    echo.example.com/enabled: "true"
data:
  message: " "
EOF
  [[ "${output}" == *"data.message must not be blank"* ]] \
    || die "unexpected denial output: ${output}"
  printf 'ok: blank message denied by API server\n'
}

test_update_and_gc() {
  log "update propagation + owner-reference garbage collection"
  kubectl patch configmap greeting -n "${NAMESPACE}" --type merge \
    -p '{"data":{"message":"bye"}}' >/dev/null
  wait_jsonpath configmap greeting-echo '{.data.message}' "BYE" "child follows owner update"
  kubectl delete configmap greeting -n "${NAMESPACE}" >/dev/null
  for _ in $(seq 1 30); do
    if ! kubectl get configmap greeting-echo -n "${NAMESPACE}" >/dev/null 2>&1; then
      printf 'ok: child garbage-collected after owner deletion\n'
      return 0
    fi
    sleep 2
  done
  die "greeting-echo was not garbage-collected"
}

test_health_and_metrics() {
  log "actuator health + metrics"
  kubectl port-forward service/echo-operator "${PF_PORT}:443" -n "${NAMESPACE}" >/dev/null 2>&1 &
  PF_PID=$!
  sleep 3
  curl -sk --max-time 10 "https://localhost:${PF_PORT}/actuator/health" | grep -q '"status":"UP"' \
    || die "actuator health is not UP"
  printf 'ok: /actuator/health UP\n'
  curl -sk --max-time 10 "https://localhost:${PF_PORT}/actuator/prometheus" \
    | grep -q 'operator_framework_callback_total' || die "framework callback metric missing"
  printf 'ok: operator_framework_callback_total exported\n'
  kill "${PF_PID}" 2>/dev/null || true
  PF_PID=""
}

preflight
build
deploy
register_webhooks
test_mutation_and_controller
test_validation
test_update_and_gc
test_health_and_metrics
log "E2E PASS"
