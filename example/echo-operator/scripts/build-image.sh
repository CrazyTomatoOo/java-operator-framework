#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
IMAGE_TAG="${IMAGE_TAG:-latest}"

mvn -f "${PROJECT_ROOT}/example/echo-operator/pom.xml" clean package -DskipTests
docker build -t "example/echo-operator:${IMAGE_TAG}" "${PROJECT_ROOT}/example/echo-operator"
