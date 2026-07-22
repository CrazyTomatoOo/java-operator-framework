#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RELEASE_NAME="${RELEASE_NAME:-echo-operator}"
NAMESPACE="${NAMESPACE:-$(kubectl config view --minify --output 'jsonpath={..namespace}' 2>/dev/null || true)}"
NAMESPACE="${NAMESPACE:-default}"

echo "Uninstalling Helm release ${RELEASE_NAME} from namespace ${NAMESPACE}..."
helm uninstall "${RELEASE_NAME}" --namespace "${NAMESPACE}"

echo "Uninstall complete."
