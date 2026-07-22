#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
HELM_DIR="${PROJECT_DIR}/helm/echo-operator"
CRD_SOURCE="${PROJECT_DIR}/target/classes/META-INF/fabric8/echoresources.example.com-v1.yml"
CRD_TARGET="${HELM_DIR}/templates/crd.yaml"

cd "${PROJECT_DIR}"

echo "Ensuring CRD is generated..."
mvn -f "${PROJECT_DIR}/pom.xml" compile -DskipTests

if [[ ! -f "${CRD_SOURCE}" ]]; then
  echo "ERROR: CRD not found at ${CRD_SOURCE}"
  exit 1
fi

echo "Copying CRD into Helm chart templates..."
cp "${CRD_SOURCE}" "${CRD_TARGET}"

echo "Packaging Helm chart..."
helm package "${HELM_DIR}" -d "${PROJECT_DIR}/helm"

echo "Helm chart packaged successfully."
