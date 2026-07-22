#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

mvn -f "${PROJECT_ROOT}/operator/framework/pom.xml" clean install -DskipTests
mvn -f "${PROJECT_ROOT}/example/echo-operator/pom.xml" clean package -DskipTests
