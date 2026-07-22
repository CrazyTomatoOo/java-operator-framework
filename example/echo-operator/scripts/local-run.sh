#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

NAMESPACE="$(kubectl config view --minify --output 'jsonpath={..namespace}' 2>/dev/null || echo "")"
NAMESPACE="${NAMESPACE:-default}"

export OPERATOR_NAMESPACE="${OPERATOR_NAMESPACE:-$NAMESPACE}"
export METRICS_PORT="${METRICS_PORT:-8080}"
export LEADER_ELECTION_ENABLED="${LEADER_ELECTION_ENABLED:-false}"
export LEADER_ELECTION_NAMESPACE="${LEADER_ELECTION_NAMESPACE:-$OPERATOR_NAMESPACE}"
export LEADER_ELECTION_LOCK_NAME="${LEADER_ELECTION_LOCK_NAME:-echo-operator-lock}"

cd "${PROJECT_DIR}"
mvn -f pom.xml exec:java -Dexec.mainClass=com.example.echooperator.EchoOperatorMain
