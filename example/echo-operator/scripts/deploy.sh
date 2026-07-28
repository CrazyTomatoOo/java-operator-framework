#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_SCRIPT="${SCRIPT_DIR}/build.sh"
BUILD_IMAGE_SCRIPT="${SCRIPT_DIR}/build-image.sh"
CHART_DIR="${CHART_DIR:-${PROJECT_DIR}/helm/echo-operator}"

RELEASE_NAME="${RELEASE_NAME:-echo-operator}"
NAMESPACE="${NAMESPACE:-$(kubectl config view --minify --output 'jsonpath={..namespace}' 2>/dev/null || true)}"
NAMESPACE="${NAMESPACE:-default}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
DEPLOYMENT_MODE="${DEPLOYMENT_MODE:-combined}"
HELM_VALUES_FILE="${HELM_VALUES_FILE:-}"
HELM_POST_RENDERER="${HELM_POST_RENDERER:-}"
HELM_WAIT="${HELM_WAIT:-true}"
HELM_TIMEOUT="${HELM_TIMEOUT:-300s}"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: required command not found: $1" >&2
    exit 1
  }
}

case "${DEPLOYMENT_MODE}" in
  combined|split) ;;
  *)
    echo "ERROR: DEPLOYMENT_MODE must be combined or split, got ${DEPLOYMENT_MODE}" >&2
    exit 1
    ;;
esac

require_command kubectl
require_command helm

if [[ ! -d "${CHART_DIR}" ]]; then
  echo "ERROR: Helm chart directory does not exist: ${CHART_DIR}" >&2
  exit 1
fi

if [[ -n "${HELM_VALUES_FILE}" && ! -f "${HELM_VALUES_FILE}" ]]; then
  echo "ERROR: HELM_VALUES_FILE does not exist: ${HELM_VALUES_FILE}" >&2
  exit 1
fi
if [[ -n "${HELM_POST_RENDERER}" && ! -x "${HELM_POST_RENDERER}" ]]; then
  echo "ERROR: HELM_POST_RENDERER is not executable: ${HELM_POST_RENDERER}" >&2
  exit 1
fi

if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
  "${BUILD_SCRIPT}"
  "${BUILD_IMAGE_SCRIPT}"
fi

CURRENT_CONTEXT="$(kubectl config current-context 2>/dev/null)" || {
  echo "ERROR: unable to resolve the current Kubernetes context" >&2
  exit 1
}
if [[ "${CURRENT_CONTEXT}" == kind-* && "${LOAD_KIND_IMAGE:-true}" == "true" ]]; then
  require_command kind
  KIND_CLUSTER="${CURRENT_CONTEXT#kind-}"
  echo "Loading image into kind cluster ${KIND_CLUSTER}..."
  kind load docker-image "example/echo-operator:${IMAGE_TAG}" -n "${KIND_CLUSTER}"
fi

echo "Deploying ${DEPLOYMENT_MODE} Helm release ${RELEASE_NAME} to namespace ${NAMESPACE}..."
HELM_ARGS=(upgrade --install "${RELEASE_NAME}" "${CHART_DIR}"
  --namespace "${NAMESPACE}"
  --create-namespace
  --timeout "${HELM_TIMEOUT}"
  --set "deploymentMode=${DEPLOYMENT_MODE}"
  --set-string "image.tag=${IMAGE_TAG}")
if [[ -n "${HELM_VALUES_FILE}" ]]; then
  HELM_ARGS+=(--values "${HELM_VALUES_FILE}")
fi
if [[ -n "${HELM_POST_RENDERER}" ]]; then
  HELM_ARGS+=(--post-renderer "${HELM_POST_RENDERER}")
fi
if [[ "${HELM_WAIT}" == "true" ]]; then
  HELM_ARGS+=(--wait)
elif [[ "${HELM_WAIT}" != "false" ]]; then
  echo "ERROR: HELM_WAIT must be true or false, got ${HELM_WAIT}" >&2
  exit 1
fi
helm "${HELM_ARGS[@]}"

echo "Deployment complete."
