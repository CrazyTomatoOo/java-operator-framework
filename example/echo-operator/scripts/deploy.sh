#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BUILD_SCRIPT="${SCRIPT_DIR}/build.sh"
BUILD_IMAGE_SCRIPT="${SCRIPT_DIR}/build-image.sh"
HELM_DIR="${PROJECT_DIR}/helm/echo-operator"

RELEASE_NAME="${RELEASE_NAME:-echo-operator}"
NAMESPACE="${NAMESPACE:-$(kubectl config view --minify --output 'jsonpath={..namespace}' 2>/dev/null || true)}"
NAMESPACE="${NAMESPACE:-default}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
  "${BUILD_SCRIPT}"
  "${BUILD_IMAGE_SCRIPT}"

  CURRENT_CONTEXT="$(kubectl config current-context 2>/dev/null || true)"
  if [[ "${CURRENT_CONTEXT}" == kind-* ]]; then
    KIND_CLUSTER="${CURRENT_CONTEXT#kind-}"
    echo "Loading image into kind cluster ${KIND_CLUSTER}..."
    kind load docker-image "example/echo-operator:${IMAGE_TAG}" -n "${KIND_CLUSTER}"
  fi
fi

echo "Deploying Helm chart ${RELEASE_NAME} to namespace ${NAMESPACE}..."
helm upgrade --install "${RELEASE_NAME}" "${HELM_DIR}" \
  --namespace "${NAMESPACE}" \
  --create-namespace \
  --set "image.tag=${IMAGE_TAG}"

echo "Deployment complete."
