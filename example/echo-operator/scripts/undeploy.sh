#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RELEASE_NAME="${RELEASE_NAME:-echo-operator}"
NAMESPACE="${NAMESPACE:-$(kubectl config view --minify --output 'jsonpath={..namespace}' 2>/dev/null || true)}"
NAMESPACE="${NAMESPACE:-default}"
HELM_TIMEOUT="${HELM_TIMEOUT:-300s}"

command -v helm >/dev/null 2>&1 || {
  echo "ERROR: required command not found: helm" >&2
  exit 1
}

echo "Uninstalling Helm release ${RELEASE_NAME} from namespace ${NAMESPACE}..."
helm uninstall "${RELEASE_NAME}" --namespace "${NAMESPACE}" --wait --timeout "${HELM_TIMEOUT}" --ignore-not-found

echo "Uninstall complete."
