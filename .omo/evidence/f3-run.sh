set -u

ROOT="/Volumes/work/Project/java-operator-framework"
LOG="$ROOT/.sisyphus/evidence/f3-advanced-features-qa.log"
HELM="/opt/homebrew/bin/helm"
RENDER="/tmp/echo-rendered-f3.yaml"

mkdir -p "$(dirname "$LOG")"
: > "$LOG"

record() {
    echo "$@" >> "$LOG"
}

run_cmd() {
    local name="$1"
    shift
    record "=== $name ==="
    record "Command: $*"
    record "Timestamp: $(date -Iseconds)"
    if "$@" >> "$LOG" 2>&1; then
        record "RESULT: PASS"
        echo "PASS"
    else
        record "RESULT: FAIL"
        echo "FAIL"
    fi
    record ""
}

render_helm() {
    local name="$1"
    shift
    record "=== $name ==="
    record "Command: $HELM template $* > $RENDER"
    record "Timestamp: $(date -Iseconds)"
    if $HELM template "$@" > "$RENDER" 2>> "$LOG"; then
        record "RESULT: PASS"
        echo "PASS"
    else
        record "RESULT: FAIL"
        echo "FAIL"
    fi
    record ""
}

grep_present() {
    local name="$1"
    local pattern="$2"
    record "=== $name ==="
    record "Pattern: $pattern"
    if grep -Eq "$pattern" "$RENDER" >> "$LOG" 2>&1; then
        record "RESULT: PASS"
        echo "PASS"
    else
        record "RESULT: FAIL (pattern not found)"
        echo "FAIL"
    fi
    record ""
}

grep_absent() {
    local name="$1"
    local pattern="$2"
    record "=== $name ==="
    record "Pattern (must be absent): $pattern"
    if grep -Eq "$pattern" "$RENDER" >> "$LOG" 2>&1; then
        record "RESULT: FAIL (pattern found)"
        echo "FAIL"
    else
        record "RESULT: PASS"
        echo "PASS"
    fi
    record ""
}

cd "$ROOT"

record "=============================================="
record "F3 Real Manual QA - operator-sdk-advanced-features"
record "Started: $(date -Iseconds)"
record "Helm: $HELM"
record "=============================================="
record ""

SCENARIO_PASSES=0
SCENARIO_TOTAL=16
INTEGRATION_PASSES=0
INTEGRATION_TOTAL=4
EDGE_PASSES=0
EDGE_TOTAL=6

pass_scenario() {
    if [ "$1" = "PASS" ]; then
        SCENARIO_PASSES=$((SCENARIO_PASSES + 1))
    fi
}

pass_integration() {
    if [ "$1" = "PASS" ]; then
        INTEGRATION_PASSES=$((INTEGRATION_PASSES + 1))
    fi
}

pass_edge() {
    if [ "$1" = "PASS" ]; then
        EDGE_PASSES=$((EDGE_PASSES + 1))
    fi
}

all_pass() {
    local status="PASS"
    for s in "$@"; do
        if [ "$s" != "PASS" ]; then
            status="FAIL"
        fi
    done
    echo "$status"
}

pass_scenario "$(run_cmd "T1: WebhookServer HTTPS test" mvn -f operator/framework/pom.xml test -Dtest=WebhookServerTest)"
pass_scenario "$(run_cmd "T2: Admission handler test" mvn -f operator/framework/pom.xml test -Dtest=AdmissionHandlerTest)"
pass_scenario "$(run_cmd "T3: Webhook self-registration test" mvn -f operator/framework/pom.xml test -Dtest=WebhookSelfRegistrationTest)"
pass_scenario "$(run_cmd "T4: TLS cert reload test" mvn -f operator/framework/pom.xml test -Dtest=TlsCertReloadTest)"
pass_scenario "$(run_cmd "T5: Full SDK test suite" mvn -f operator/framework/pom.xml test)"
pass_scenario "$(run_cmd "T6: Echo validating webhook test" mvn -f example/echo-operator/pom.xml test -Dtest=EchoValidatingWebhookTest)"
pass_scenario "$(run_cmd "T7: Echo converter test" mvn -f example/echo-operator/pom.xml test -Dtest=EchoConverterTest)"

T8_COMPILE=$(run_cmd "T8: Echo operator clean compile" mvn -f example/echo-operator/pom.xml clean compile)
T8_RENDER=$(render_helm "T8: Helm template for CRD conversion" echo-operator example/echo-operator/helm/echo-operator --set webhook.createWebhookConfigurations=true)
T8_CONV=$(grep_present "T8: CRD conversion block present" '^  conversion:')
pass_scenario "$(all_pass "$T8_COMPILE" "$T8_RENDER" "$T8_CONV")"

pass_scenario "$(run_cmd "T9: Conversion webhook handler test" mvn -f operator/framework/pom.xml test -Dtest=ConversionHandlerTest)"
pass_scenario "$(run_cmd "T10: Echo conversion endpoint test" mvn -f example/echo-operator/pom.xml test -Dtest=EchoConversionEndpointTest)"
pass_scenario "$(run_cmd "T11: Conversion error handling test" mvn -f operator/framework/pom.xml test -Dtest=ConversionHandlerTest)"

T12_LINT=$(run_cmd "T12: Helm lint" $HELM lint example/echo-operator/helm/echo-operator)
T12_RENDER=$(render_helm "T12: Helm template with webhooks" echo-operator example/echo-operator/helm/echo-operator --set webhook.createWebhookConfigurations=true)
T12_VWC=$(grep_present "T12: ValidatingWebhookConfiguration present" '^kind: ValidatingWebhookConfiguration$')
T12_MWC=$(grep_present "T12: MutatingWebhookConfiguration present" '^kind: MutatingWebhookConfiguration$')
T12_SVC=$(grep_present "T12: Webhook Service targetPort present" 'targetPort: webhook')
T12_TLS=$(grep_present "T12: TLS Secret mount present" 'secretName: echo-operator-webhook-tls')
pass_scenario "$(all_pass "$T12_LINT" "$T12_RENDER" "$T12_VWC" "$T12_MWC" "$T12_SVC" "$T12_TLS")"

record "=== T13: SDK README + dev-guide webhook sections ==="
record "Command: grep -n Webhook operator/framework/README.md docs/dev-guide.md"
if grep -n "Webhook" operator/framework/README.md docs/dev-guide.md >> "$LOG" 2>&1; then
    record "RESULT: PASS"
    pass_scenario "PASS"
else
    record "RESULT: FAIL"
    pass_scenario "FAIL"
fi
record ""

record "=== T14: Echo README webhook and v1alpha2 sections ==="
record "Command: grep -n webhook / v1alpha2 example/echo-operator/README.md"
if grep -n "webhook" example/echo-operator/README.md >> "$LOG" 2>&1 && grep -n "v1alpha2" example/echo-operator/README.md >> "$LOG" 2>&1; then
    record "RESULT: PASS"
    pass_scenario "PASS"
else
    record "RESULT: FAIL"
    pass_scenario "FAIL"
fi
record ""

T15_LINT=$(run_cmd "T15: Helm lint" $HELM lint example/echo-operator/helm/echo-operator)
T15_RENDER=$(render_helm "T15: Helm template default values" echo-operator example/echo-operator/helm/echo-operator)
pass_scenario "$(all_pass "$T15_LINT" "$T15_RENDER")"

pass_scenario "$(run_cmd "T16: Smoke-test script syntax" bash -n example/echo-operator/scripts/smoke-test.sh)"

record "=============================================="
record "Integration Checks"
record "=============================================="

pass_integration "$(run_cmd "Integration 1: SDK clean install" mvn -f operator/framework/pom.xml clean install)"
pass_integration "$(run_cmd "Integration 2: Example clean package" mvn -f example/echo-operator/pom.xml clean package)"

I3_RENDER=$(render_helm "Integration 3: Helm template with webhook configs" echo-operator example/echo-operator/helm/echo-operator --set webhook.createWebhookConfigurations=true)
I3_VWC=$(grep_present "Integration 3: ValidatingWebhookConfiguration" '^kind: ValidatingWebhookConfiguration$')
I3_MWC=$(grep_present "Integration 3: MutatingWebhookConfiguration" '^kind: MutatingWebhookConfiguration$')
I3_SVC=$(grep_present "Integration 3: Webhook Service" 'targetPort: webhook')
I3_TLS=$(grep_present "Integration 3: TLS Secret mount" 'secretName: echo-operator-webhook-tls')
I3_CONV=$(grep_present "Integration 3: Conversion webhook block" '^  conversion:')
pass_integration "$(all_pass "$I3_RENDER" "$I3_VWC" "$I3_MWC" "$I3_SVC" "$I3_TLS" "$I3_CONV")"

pass_integration "$(run_cmd "Integration 4: Smoke-test script syntax" bash -n example/echo-operator/scripts/smoke-test.sh)"

record "=============================================="
record "Edge Case Checks"
record "=============================================="

pass_edge "$(run_cmd "Edge 1: Missing CA bundle fails fast" mvn -f operator/framework/pom.xml test -Dtest=WebhookSelfRegistrationTest#registerFailsFastWhenCaBundleIsMissing)"
pass_edge "$(run_cmd "Edge 2: Unregistered conversion version returns failure" mvn -f operator/framework/pom.xml test -Dtest=ConversionHandlerTest#unregisteredVersionPairReturnsFailureStatus)"
pass_edge "$(run_cmd "Edge 3: Mutating webhook defaults blank message" mvn -f example/echo-operator/pom.xml test -Dtest=EchoMutatingWebhookTest#defaultsBlankMessageBeforeValidationSeesTheResource)"

E4_RENDER=$(render_helm "Edge 4: Helm template with createWebhookConfigurations=false" echo-operator example/echo-operator/helm/echo-operator --set webhook.createWebhookConfigurations=false)
E4_VWC=$(grep_absent "Edge 4: No ValidatingWebhookConfiguration" '^kind: ValidatingWebhookConfiguration$')
E4_MWC=$(grep_absent "Edge 4: No MutatingWebhookConfiguration" '^kind: MutatingWebhookConfiguration$')
pass_edge "$(all_pass "$E4_RENDER" "$E4_VWC" "$E4_MWC")"

E5_RENDER=$(render_helm "Edge 5: Helm template default values" echo-operator example/echo-operator/helm/echo-operator)
E5_SVC=$(grep_present "Edge 5: Webhook Service targetPort" 'targetPort: webhook')
E5_PORT=$(grep_present "Edge 5: Webhook containerPort 8443" 'containerPort: 8443')
E5_TLS=$(grep_present "Edge 5: TLS Secret volume" 'secretName: echo-operator-webhook-tls')
pass_edge "$(all_pass "$E5_RENDER" "$E5_SVC" "$E5_PORT" "$E5_TLS")"

E6_RENDER=$(render_helm "Edge 6: Helm template with createWebhookConfigurations=true" echo-operator example/echo-operator/helm/echo-operator --set webhook.createWebhookConfigurations=true)
E6_VWC=$(grep_present "Edge 6: ValidatingWebhookConfiguration rendered" '^kind: ValidatingWebhookConfiguration$')
E6_MWC=$(grep_present "Edge 6: MutatingWebhookConfiguration rendered" '^kind: MutatingWebhookConfiguration$')
E6_CONV=$(grep_present "Edge 6: Conversion webhook block" '^  conversion:')
pass_edge "$(all_pass "$E6_RENDER" "$E6_VWC" "$E6_MWC" "$E6_CONV")"

record "=============================================="
record "Summary"
record "=============================================="
record "Scenarios [$SCENARIO_PASSES/$SCENARIO_TOTAL pass]"
record "Integration [$INTEGRATION_PASSES/$INTEGRATION_TOTAL]"
record "Edge Cases [$EDGE_PASSES/$EDGE_TOTAL tested]"
if [ "$SCENARIO_PASSES" -eq "$SCENARIO_TOTAL" ] && [ "$INTEGRATION_PASSES" -eq "$INTEGRATION_TOTAL" ] && [ "$EDGE_PASSES" -eq "$EDGE_TOTAL" ]; then
    record "VERDICT: APPROVE"
    VERDICT="APPROVE"
else
    record "VERDICT: REJECT"
    VERDICT="REJECT"
fi
record "Finished: $(date -Iseconds)"

echo ""
echo "Scenarios [$SCENARIO_PASSES/$SCENARIO_TOTAL pass] | Integration [$INTEGRATION_PASSES/$INTEGRATION_TOTAL] | Edge Cases [$EDGE_PASSES/$EDGE_TOTAL tested] | VERDICT: $VERDICT"
