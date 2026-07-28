#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
readonly CHART_DIR="${ROOT_DIR}/example/echo-operator/helm/echo-operator"
readonly BASELINE_ARCHIVE="${ROOT_DIR}/.omo/evidence/controller-webhook-split-deployment/baseline/echo-operator-chart.tar"
readonly RELEASE="contract"
readonly NAMESPACE="contract-ns"
readonly WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/echo-operator-helm-contract.XXXXXX")"

cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

run_bounded() {
  python3 - "$@" <<'PY'
import subprocess
import sys

try:
    completed = subprocess.run(
        sys.argv[1:],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=30,
        check=False,
    )
except subprocess.TimeoutExpired as error:
    sys.stdout.write(error.stdout or "")
    sys.stderr.write(f"command timed out after 30 seconds: {' '.join(sys.argv[1:])}\n")
    raise SystemExit(124)

sys.stdout.write(completed.stdout)
raise SystemExit(completed.returncode)
PY
}

render() {
  local output="$1"
  shift
  run_bounded helm template "${RELEASE}" "${CHART_DIR}" --namespace "${NAMESPACE}" "$@" >"${output}"
}

render_in_namespace() {
  local output="$1"
  local namespace="$2"
  shift 2
  run_bounded helm template "${RELEASE}" "${CHART_DIR}" --namespace "${namespace}" "$@" >"${output}"
}

extract_fail_message() {
  python3 - "$1" <<'PY'
import re
import sys

output = sys.argv[1]
matches = re.findall(r'[)\]]:\s*(.+)$', output, re.MULTILINE)
if not matches:
    raise AssertionError(f"could not extract fail message from:\n{output}")
print(matches[-1].strip())
PY
}

assert_combined_contract() {
  local baseline_render="$1"
  local current_render="$2"

  python3 - "${baseline_render}" "${current_render}" <<'PY'
import copy
import sys
import yaml

baseline_path, current_path = sys.argv[1:]

def documents(path):
    with open(path, encoding="utf-8") as source:
        return [document for document in yaml.safe_load_all(source) if document]

def index_resources(resources):
    return {
        (resource["kind"], resource["metadata"]["name"]): resource
        for resource in resources
    }

baseline = index_resources(documents(baseline_path))
current = index_resources(documents(current_path))

expected_selector = {
    "app.kubernetes.io/name": "echo-operator",
    "app.kubernetes.io/instance": "contract",
}

for resource_key in (
    ("Deployment", "contract-echo-operator"),
    ("Service", "contract-echo-operator"),
    ("ServiceAccount", "contract-echo-operator"),
):
    if resource_key not in baseline:
        raise AssertionError(f"baseline render is missing {resource_key}")
    if resource_key not in current:
        raise AssertionError(f"current render is missing {resource_key}")

baseline_deployment = baseline[("Deployment", "contract-echo-operator")]
current_deployment = current[("Deployment", "contract-echo-operator")]
if baseline_deployment["spec"]["selector"]["matchLabels"] != expected_selector:
    raise AssertionError("archived combined Deployment selector changed unexpectedly")
if current_deployment["spec"]["selector"]["matchLabels"] != expected_selector:
    raise AssertionError("current combined Deployment selector changed")
if current_deployment["spec"]["template"]["metadata"]["labels"] != expected_selector:
    raise AssertionError("combined pod template labels must exactly equal the selector labels")

baseline_service = baseline[("Service", "contract-echo-operator")]
current_service = current[("Service", "contract-echo-operator")]
if baseline_service["spec"]["selector"] != expected_selector:
    raise AssertionError("archived combined Service selector changed unexpectedly")
if current_service["spec"]["selector"] != expected_selector:
    raise AssertionError("current combined Service selector changed")
if current[("ServiceAccount", "contract-echo-operator")]["metadata"]["name"] != "contract-echo-operator":
    raise AssertionError("combined ServiceAccount name changed")

expected_env = {
    "CONTROLLER_ENABLED": "true",
    "WEBHOOK_ENABLED": "true",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED": "false",
    "WEBHOOK_SELF_REGISTRATION_ENABLED": "true",
    "WEBHOOK_PREDECESSOR_VALIDATING_NAME": "contract-echo-operator-validating",
    "WEBHOOK_PREDECESSOR_MUTATING_NAME": "contract-echo-operator-mutating",
}
current_env = {
    entry["name"]: entry.get("value")
    for entry in current_deployment["spec"]["template"]["spec"]["containers"][0]["env"]
}
for name, expected in expected_env.items():
    if current_env.get(name) != expected:
        raise AssertionError(f"default combined env {name} must be {expected}, got {current_env.get(name)}")

baseline_without_new_ownership_flags = copy.deepcopy(current_deployment)
baseline_without_new_ownership_flags["spec"]["template"]["spec"]["containers"][0]["env"] = [
    entry
    for entry in baseline_without_new_ownership_flags["spec"]["template"]["spec"]["containers"][0]["env"]
    if entry["name"] not in {
        "CONTROLLER_ENABLED",
        "WEBHOOK_REGISTRATION_CLEANUP_ENABLED",
        "WEBHOOK_SELF_REGISTRATION_ENABLED",
        "WEBHOOK_PREDECESSOR_VALIDATING_NAME",
        "WEBHOOK_PREDECESSOR_MUTATING_NAME",
    }
]
if baseline_without_new_ownership_flags != baseline_deployment:
    raise AssertionError("combined Deployment changed outside the explicit ownership env flags")

rbac_kinds = {"Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding"}
if {key for key in current if key[0] not in rbac_kinds} != {key for key in baseline if key[0] not in rbac_kinds}:
    raise AssertionError("default combined non-RBAC resource set diverged from the archived chart")

print("combined contract passed")
PY
}

assert_combined_identity_rbac_characterization() {
  local render_output="$1"

  python3 - "${render_output}" <<'PY'
import sys
import yaml

RELEASE_NAMESPACE = "contract-ns"
COMBINED_SERVICE_ACCOUNT = "contract-echo-operator"

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

service_accounts = [resource for resource in resources if resource["kind"] == "ServiceAccount"]
if {(resource["metadata"]["name"], resource["metadata"].get("namespace")) for resource in service_accounts} != {
        (COMBINED_SERVICE_ACCOUNT, RELEASE_NAMESPACE)}:
    raise AssertionError("default combined render must keep exactly one release-namespace ServiceAccount")

role_bindings = [resource for resource in resources if resource["kind"] == "RoleBinding"]
cluster_role_bindings = [resource for resource in resources if resource["kind"] == "ClusterRoleBinding"]
if not role_bindings or not cluster_role_bindings:
    raise AssertionError("default combined render must bind both namespaced and cluster-scoped RBAC")
for binding in role_bindings + cluster_role_bindings:
    subjects = binding.get("subjects", [])
    if len(subjects) != 1 or subjects[0] != {
            "kind": "ServiceAccount", "name": COMBINED_SERVICE_ACCOUNT, "namespace": RELEASE_NAMESPACE}:
        raise AssertionError(f"combined RBAC binding must target the one combined identity: {binding['metadata']['name']}")

namespaced_rules = [rule for resource in resources if resource["kind"] == "Role" for rule in resource.get("rules", [])]
cluster_rules = [rule for resource in resources if resource["kind"] == "ClusterRole" for rule in resource.get("rules", [])]
if not any("echoresources" in rule.get("resources", []) for rule in namespaced_rules):
    raise AssertionError("combined RBAC characterization lost controller EchoResource access")
if not any("secrets" in rule.get("resources", []) for rule in namespaced_rules):
    raise AssertionError("combined RBAC characterization lost generated-CA Secret access")
if not any("validatingwebhookconfigurations" in rule.get("resources", []) for rule in cluster_rules):
    raise AssertionError("combined RBAC characterization lost admission registration access")
if not any("customresourcedefinitions" in rule.get("resources", []) for rule in cluster_rules):
    raise AssertionError("combined RBAC characterization lost conversion CRD access")

print("combined identity/RBAC characterization passed")
PY
}

assert_webhook_disabled_combined_contract() {
  local render_output="$1"

  python3 - "${render_output}" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

deployments = [resource for resource in resources if resource["kind"] == "Deployment"]
services = [resource for resource in resources if resource["kind"] == "Service"]
if len(deployments) != 1 or len(services) != 1:
    raise AssertionError("webhook-disabled combined render must contain exactly one Deployment and one Service")

deployment = deployments[0]
service = services[0]
expected_selector = {
    "app.kubernetes.io/name": "echo-operator",
    "app.kubernetes.io/instance": "contract",
}
if deployment["metadata"]["name"] != "contract-echo-operator":
    raise AssertionError("webhook-disabled combined Deployment name changed")
if deployment["spec"]["selector"]["matchLabels"] != expected_selector:
    raise AssertionError("webhook-disabled combined Deployment selector changed")
if service["metadata"]["name"] != "contract-echo-operator" or service["spec"]["selector"] != expected_selector:
    raise AssertionError("webhook-disabled combined Service identity changed")

container = deployment["spec"]["template"]["spec"]["containers"][0]
env = {entry["name"]: entry.get("value") for entry in container["env"]}
expected_env = {
    "CONTROLLER_ENABLED": "true",
    "WEBHOOK_ENABLED": "false",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED": "true",
    "WEBHOOK_SELF_REGISTRATION_ENABLED": "false",
}
for name, expected in expected_env.items():
    if env.get(name) != expected:
        raise AssertionError(f"webhook-disabled combined env {name} must be {expected}, got {env.get(name)}")

forbidden_env = {
    "WEBHOOK_PORT",
    "WEBHOOK_SERVICE_NAME",
    "WEBHOOK_SERVICE_NAMESPACE",
    "WEBHOOK_SERVICE_PORT",
    "WEBHOOK_CERT_AUTO_GENERATE",
    "WEBHOOK_CERT_DIRECTORY",
    "WEBHOOK_CERT_SECRET_NAME",
    "WEBHOOK_CA_BUNDLE_PATH",
}
if forbidden_env & env.keys():
    raise AssertionError(f"webhook-disabled combined render leaked webhook serving env: {forbidden_env & env.keys()}")
if {port["name"] for port in container["ports"]} != {"metrics"}:
    raise AssertionError("webhook-disabled combined Pod must expose only the metrics port")
if [port["name"] for port in service["spec"]["ports"]] != ["metrics"]:
    raise AssertionError("webhook-disabled combined Service must expose only the metrics port")
if container.get("volumeMounts") or deployment["spec"]["template"]["spec"].get("volumes"):
    raise AssertionError("webhook-disabled combined Pod must not mount webhook certificates")
if any(resource["kind"] in {"ValidatingWebhookConfiguration", "MutatingWebhookConfiguration"} for resource in resources):
    raise AssertionError("webhook-disabled combined render must not create admission configurations")

crd = next(resource for resource in resources if resource["kind"] == "CustomResourceDefinition")
if crd["spec"].get("conversion") != {"strategy": "None"}:
    raise AssertionError("webhook-disabled combined CRD conversion strategy must be None")

print("webhook-disabled combined contract passed")
PY
}

assert_combined_shared_workload_values() {
  local render_output="$1"

  python3 - "${render_output}" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

deployment = next(resource for resource in resources if resource["kind"] == "Deployment")
pod = deployment["spec"]["template"]
container = pod["spec"]["containers"][0]

if deployment["spec"]["replicas"] != 9:
    raise AssertionError(f"combined replicas must retain top-level value 9, got {deployment['spec']['replicas']}")
expected_resources = {
    "limits": {"cpu": "999m", "memory": "999Mi"},
    "requests": {"cpu": "111m", "memory": "111Mi"},
}
if container["resources"] != expected_resources:
    raise AssertionError(f"combined resources must retain top-level values, got {container['resources']}")
if pod["metadata"].get("annotations") != {"poison-annotation": "poison"}:
    raise AssertionError("combined podAnnotations must retain top-level values")
if pod["metadata"]["labels"].get("poison-label") != "poison":
    raise AssertionError("combined podLabels must retain top-level values")
if pod["spec"].get("nodeSelector") != {"poison-node": "poison"}:
    raise AssertionError("combined nodeSelector must retain top-level values")
if pod["spec"].get("tolerations") != [{"key": "poison", "operator": "Exists"}]:
    raise AssertionError("combined tolerations must retain top-level values")
expected_affinity = {
    "nodeAffinity": {
        "requiredDuringSchedulingIgnoredDuringExecution": {"nodeSelectorTerms": []}
    }
}
if pod["spec"].get("affinity") != expected_affinity:
    raise AssertionError("combined affinity must retain top-level values")
if pod["spec"].get("serviceAccountName") != "poison-combined-sa":
    raise AssertionError("combined serviceAccount must retain top-level value")

print("combined shared workload values passed")
PY
}

assert_split_contract() {
  local split_render="$1"

  python3 - "${split_render}" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

indexed = {
    (resource["kind"], resource["metadata"]["name"]): resource
    for resource in resources
}

controller_dep_key = ("Deployment", "contract-echo-operator-controller")
webhook_dep_key = ("Deployment", "contract-echo-operator-webhook")
controller_svc_key = ("Service", "contract-echo-operator-controller")
webhook_svc_key = ("Service", "contract-echo-operator")
controller_sa_key = ("ServiceAccount", "contract-echo-operator-controller")
webhook_sa_key = ("ServiceAccount", "contract-echo-operator-webhook")

for key, label in [
    (controller_dep_key, "controller Deployment"),
    (webhook_dep_key, "webhook Deployment"),
    (controller_svc_key, "controller Service"),
    (webhook_svc_key, "webhook Service"),
    (controller_sa_key, "controller ServiceAccount"),
    (webhook_sa_key, "webhook ServiceAccount"),
]:
    if key not in indexed:
        raise AssertionError(f"split render is missing {label}: {key}")

controller_dep = indexed[controller_dep_key]
webhook_dep = indexed[webhook_dep_key]
controller_svc = indexed[controller_svc_key]
webhook_svc = indexed[webhook_svc_key]

controller_selector = controller_dep["spec"]["selector"]["matchLabels"]
webhook_selector = webhook_dep["spec"]["selector"]["matchLabels"]

expected_controller_selector = {
    "app.kubernetes.io/name": "echo-operator",
    "app.kubernetes.io/instance": "contract",
    "app.kubernetes.io/component": "controller",
}
expected_webhook_selector = {
    "app.kubernetes.io/name": "echo-operator",
    "app.kubernetes.io/instance": "contract",
    "app.kubernetes.io/component": "webhook",
}

if controller_selector != expected_controller_selector:
    raise AssertionError(f"controller selector mismatch: {controller_selector}")
if webhook_selector != expected_webhook_selector:
    raise AssertionError(f"webhook selector mismatch: {webhook_selector}")

# Mutually exclusive: controller selector must not match webhook pods and vice versa
controller_template_labels = controller_dep["spec"]["template"]["metadata"]["labels"]
webhook_template_labels = webhook_dep["spec"]["template"]["metadata"]["labels"]
if controller_template_labels != expected_controller_selector:
    raise AssertionError(f"controller template labels must exactly equal selector: {controller_template_labels}")
if webhook_template_labels != expected_webhook_selector:
    raise AssertionError(f"webhook template labels must exactly equal selector: {webhook_template_labels}")

# Controller Service selector must match controller pods only
if controller_svc["spec"]["selector"] != expected_controller_selector:
    raise AssertionError(f"controller Service selector mismatch: {controller_svc['spec']['selector']}")
# Webhook Service selector must match webhook pods only
if webhook_svc["spec"]["selector"] != expected_webhook_selector:
    raise AssertionError(f"webhook Service selector mismatch: {webhook_svc['spec']['selector']}")

# Webhook Service name defaults to fullname
if webhook_svc["metadata"]["name"] != "contract-echo-operator":
    raise AssertionError(f"webhook Service must default to fullname, got {webhook_svc['metadata']['name']}")

# Controller has no webhook port
controller_ports = {p["name"] for p in controller_dep["spec"]["template"]["spec"]["containers"][0]["ports"]}
if "webhook" in controller_ports:
    raise AssertionError("controller Deployment must not have webhook port")
# Webhook has webhook port
webhook_ports = {p["name"] for p in webhook_dep["spec"]["template"]["spec"]["containers"][0]["ports"]}
if "webhook" not in webhook_ports:
    raise AssertionError("webhook Deployment must have webhook port")

for name, expected in [("CONTROLLER_ENABLED", "true"), ("WEBHOOK_ENABLED", "false")]:
    envs = {e["name"]: e.get("value") for e in controller_dep["spec"]["template"]["spec"]["containers"][0]["env"]}
    if envs.get(name) != expected:
        raise AssertionError(f"controller env {name} must be {expected}, got {envs.get(name)}")
for name, expected in [("CONTROLLER_ENABLED", "false"), ("WEBHOOK_ENABLED", "true")]:
    envs = {e["name"]: e.get("value") for e in webhook_dep["spec"]["template"]["spec"]["containers"][0]["env"]}
    if envs.get(name) != expected:
        raise AssertionError(f"webhook env {name} must be {expected}, got {envs.get(name)}")

# Both use same image
controller_image = controller_dep["spec"]["template"]["spec"]["containers"][0]["image"]
webhook_image = webhook_dep["spec"]["template"]["spec"]["containers"][0]["image"]
if controller_image != webhook_image:
    raise AssertionError(f"split Deployments must use same image: {controller_image} vs {webhook_image}")

# Distinct SA names
controller_sa_name = controller_dep["spec"]["template"]["spec"]["serviceAccountName"]
webhook_sa_name = webhook_dep["spec"]["template"]["spec"]["serviceAccountName"]
if controller_sa_name == webhook_sa_name:
    raise AssertionError("split ServiceAccount names must differ")
if controller_sa_name != "contract-echo-operator-controller":
    raise AssertionError(f"controller SA name mismatch: {controller_sa_name}")
if webhook_sa_name != "contract-echo-operator-webhook":
    raise AssertionError(f"webhook SA name mismatch: {webhook_sa_name}")

print("split contract passed")
PY
}

assert_split_skeleton_characterization() {
  local split_render="$1"

  python3 - "${split_render}" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

deployments = {
    resource["metadata"]["name"]: resource
    for resource in resources
    if resource["kind"] == "Deployment"
}
services = {
    resource["metadata"]["name"]: resource
    for resource in resources
    if resource["kind"] == "Service"
}

if set(deployments) != {"contract-echo-operator-controller", "contract-echo-operator-webhook"}:
    raise AssertionError(f"Task 3 split skeleton Deployment names changed: {set(deployments)}")
if set(services) != {"contract-echo-operator-controller", "contract-echo-operator"}:
    raise AssertionError(f"Task 3 split skeleton Service names changed: {set(services)}")

controller = deployments["contract-echo-operator-controller"]
webhook = deployments["contract-echo-operator-webhook"]
for component, deployment in (("controller", controller), ("webhook", webhook)):
    labels = deployment["spec"]["template"]["metadata"]["labels"]
    if labels.get("app.kubernetes.io/component") != component:
        raise AssertionError(f"Task 3 split skeleton {component} selector label changed: {labels}")

controller_container = controller["spec"]["template"]["spec"]["containers"][0]
webhook_container = webhook["spec"]["template"]["spec"]["containers"][0]
if controller_container["image"] != webhook_container["image"]:
    raise AssertionError("Task 3 split skeleton Deployments must share one image")
if controller_container["imagePullPolicy"] != webhook_container["imagePullPolicy"]:
    raise AssertionError("Task 3 split skeleton Deployments must share one image pull policy")

print("split skeleton characterization passed")
PY
}

assert_split_task_five_auto() {
  local split_render="$1"

  python3 - "${split_render}" <<'PY'
import sys
import yaml

RELEASE_NAMESPACE = "contract-ns"
WEBHOOK_SERVICE = "contract-echo-operator"

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

indexed = {(resource["kind"], resource["metadata"]["name"]): resource for resource in resources}
deployments = [resource for resource in resources if resource["kind"] == "Deployment"]
services = [resource for resource in resources if resource["kind"] == "Service"]
if len(deployments) != 2 or len(services) != 2:
    raise AssertionError("split auto-generated TLS render must contain exactly two Deployments and two Services")

controller = indexed[("Deployment", "contract-echo-operator-controller")]
webhook = indexed[("Deployment", "contract-echo-operator-webhook")]
controller_service = indexed[("Service", "contract-echo-operator-controller")]
webhook_service = indexed[("Service", WEBHOOK_SERVICE)]
controller_pod = controller["spec"]["template"]["spec"]
webhook_pod = webhook["spec"]["template"]["spec"]
controller_container = controller_pod["containers"][0]
webhook_container = webhook_pod["containers"][0]
controller_env = {entry["name"]: entry.get("value") for entry in controller_container["env"]}
webhook_env = {entry["name"]: entry.get("value") for entry in webhook_container["env"]}

if (controller_container["image"], controller_container["imagePullPolicy"]) != (
        webhook_container["image"], webhook_container["imagePullPolicy"]):
    raise AssertionError("split Deployments must have identical image and imagePullPolicy")

expected_controller_env = {
    "CONTROLLER_ENABLED": "true",
    "WEBHOOK_ENABLED": "false",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED": "false",
    "WEBHOOK_SELF_REGISTRATION_ENABLED": "false",
}
for name, expected in expected_controller_env.items():
    if controller_env.get(name) != expected:
        raise AssertionError(f"split controller env {name} must be {expected}, got {controller_env.get(name)}")
forbidden_controller_env = {
    "WEBHOOK_PORT", "WEBHOOK_SERVICE_NAME", "WEBHOOK_SERVICE_NAMESPACE", "WEBHOOK_SERVICE_PORT",
    "WEBHOOK_CERT_AUTO_GENERATE", "WEBHOOK_CERT_DIRECTORY", "WEBHOOK_CERT_SECRET_NAME",
    "WEBHOOK_CA_BUNDLE_PATH", "WEBHOOK_PREDECESSOR_VALIDATING_NAME", "WEBHOOK_PREDECESSOR_MUTATING_NAME",
}
if forbidden_controller_env & controller_env.keys():
    raise AssertionError(f"split controller leaked webhook or certificate env: {forbidden_controller_env & controller_env.keys()}")
if "LEADER_ELECTION_ENABLED" not in controller_env or "LEADER_ELECTION_NAMESPACE" not in controller_env:
    raise AssertionError("split controller must retain leader-election env")
if {port["name"] for port in controller_container["ports"]} != {"metrics"}:
    raise AssertionError("split controller must expose only metrics")
for probe_name in ("livenessProbe", "readinessProbe"):
    if controller_container[probe_name]["httpGet"]["port"] != "metrics":
        raise AssertionError(f"split controller {probe_name} must use the metrics port")
if controller_container.get("volumeMounts") or controller_pod.get("volumes"):
    raise AssertionError("split controller must not have TLS mounts or volumes")
if "fsGroup" in controller_pod["securityContext"]:
    raise AssertionError("split controller must not have generated-TLS fsGroup")

expected_webhook_env = {
    "CONTROLLER_ENABLED": "false",
    "WEBHOOK_ENABLED": "true",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED": "false",
    "WEBHOOK_SELF_REGISTRATION_ENABLED": "true",
    "WEBHOOK_PREDECESSOR_VALIDATING_NAME": "contract-echo-operator-validating",
    "WEBHOOK_PREDECESSOR_MUTATING_NAME": "contract-echo-operator-mutating",
    "WEBHOOK_PORT": "8443",
    "WEBHOOK_SERVICE_NAME": WEBHOOK_SERVICE,
    "WEBHOOK_SERVICE_NAMESPACE": RELEASE_NAMESPACE,
    "WEBHOOK_SERVICE_PORT": "443",
    "WEBHOOK_CERT_AUTO_GENERATE": "true",
    "WEBHOOK_CERT_DIRECTORY": "/tmp/echo-operator/certs",
    "WEBHOOK_CERT_SECRET_NAME": "echo-operator-webhook-ca",
    "WEBHOOK_CA_BUNDLE_PATH": "/tmp/echo-operator/certs/ca.crt",
}
for name, expected in expected_webhook_env.items():
    if webhook_env.get(name) != expected:
        raise AssertionError(f"split auto-generated webhook env {name} must be {expected}, got {webhook_env.get(name)}")
if {name for name in webhook_env if name.startswith("LEADER_ELECTION_")}:
    raise AssertionError("split webhook must not have leader-election env")
if {port["name"] for port in webhook_container["ports"]} != {"metrics", "webhook"}:
    raise AssertionError("split webhook must expose metrics and webhook ports")
for probe_name in ("livenessProbe", "readinessProbe"):
    if webhook_container[probe_name]["httpGet"]["port"] != "metrics":
        raise AssertionError(f"split webhook {probe_name} must use the metrics port")
if webhook_pod["securityContext"].get("fsGroup") != 1001:
    raise AssertionError("split auto-generated webhook must set fsGroup")
if webhook_container.get("volumeMounts") != [{"name": "webhook-certs", "mountPath": "/tmp/echo-operator/certs"}]:
    raise AssertionError("split auto-generated webhook must mount only its certificate emptyDir")
if webhook_pod.get("volumes") != [{"name": "webhook-certs", "emptyDir": {}}]:
    raise AssertionError("split auto-generated webhook must have only its certificate emptyDir")

controller_labels = controller["spec"]["template"]["metadata"]["labels"]
webhook_labels = webhook["spec"]["template"]["metadata"]["labels"]
def selected_templates(service):
    selector = service["spec"]["selector"]
    return [name for name, labels in (("controller", controller_labels), ("webhook", webhook_labels))
            if all(labels.get(key) == value for key, value in selector.items())]

if selected_templates(controller_service) != ["controller"]:
    raise AssertionError("controller Service must target only the controller Pod")
if selected_templates(webhook_service) != ["webhook"]:
    raise AssertionError("webhook Service must target only the webhook Pod")
miswired_controller_service = {**controller_service, "spec": {**controller_service["spec"],
    "selector": webhook_service["spec"]["selector"]}}
if selected_templates(miswired_controller_service) == ["controller"]:
    raise AssertionError("selector isolation verifier must reject a controller Service wired to the webhook Pod")

conversion_target = indexed[("CustomResourceDefinition", "echoresources.example.com")]["spec"]["conversion"]["webhook"]["clientConfig"]["service"]
if conversion_target != {"namespace": RELEASE_NAMESPACE, "name": WEBHOOK_SERVICE, "path": "/convert", "port": 443}:
    raise AssertionError(f"split auto-generated CRD target must use the webhook Service identity, got {conversion_target}")

print("split Task 5 auto-generated TLS contract passed")
PY
}

assert_split_task_five_external() {
  local split_render="$1"
  local pem_file="$2"

  python3 - "${split_render}" "${pem_file}" <<'PY'
import base64
import sys
import yaml

RELEASE_NAMESPACE = "contract-ns"
WEBHOOK_SERVICE = "contract-echo-operator"

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]
with open(sys.argv[2], "rb") as source:
    expected_ca = source.read()

indexed = {(resource["kind"], resource["metadata"]["name"]): resource for resource in resources}
controller = indexed[("Deployment", "contract-echo-operator-controller")]
webhook = indexed[("Deployment", "contract-echo-operator-webhook")]
controller_pod = controller["spec"]["template"]["spec"]
webhook_pod = webhook["spec"]["template"]["spec"]
controller_container = controller_pod["containers"][0]
webhook_container = webhook_pod["containers"][0]
controller_env = {entry["name"]: entry.get("value") for entry in controller_container["env"]}
webhook_env = {entry["name"]: entry.get("value") for entry in webhook_container["env"]}

if controller_container.get("volumeMounts") or controller_pod.get("volumes"):
    raise AssertionError("external TLS must not change the controller Pod TLS surface")
if "fsGroup" in controller_pod["securityContext"]:
    raise AssertionError("external TLS must not add controller fsGroup")
expected_controller_env = {
    "CONTROLLER_ENABLED": "true",
    "WEBHOOK_ENABLED": "false",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED": "false",
    "WEBHOOK_SELF_REGISTRATION_ENABLED": "false",
}
for name, expected in expected_controller_env.items():
    if controller_env.get(name) != expected:
        raise AssertionError(f"split external controller env {name} must be {expected}, got {controller_env.get(name)}")

expected_webhook_env = {
    "CONTROLLER_ENABLED": "false",
    "WEBHOOK_ENABLED": "true",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED": "true",
    "WEBHOOK_SELF_REGISTRATION_ENABLED": "false",
    "WEBHOOK_CERT_AUTO_GENERATE": "false",
    "WEBHOOK_CA_BUNDLE_PATH": "/etc/echo-operator/certs/ca.crt",
    "WEBHOOK_SERVICE_NAME": WEBHOOK_SERVICE,
    "WEBHOOK_SERVICE_NAMESPACE": RELEASE_NAMESPACE,
    "WEBHOOK_SERVICE_PORT": "443",
}
for name, expected in expected_webhook_env.items():
    if webhook_env.get(name) != expected:
        raise AssertionError(f"split external webhook env {name} must be {expected}, got {webhook_env.get(name)}")
if {"WEBHOOK_PREDECESSOR_VALIDATING_NAME", "WEBHOOK_PREDECESSOR_MUTATING_NAME"} & webhook_env.keys():
    raise AssertionError("split Helm-owned webhook must not inject predecessor names")
if {name for name in webhook_env if name.startswith("LEADER_ELECTION_")}:
    raise AssertionError("split external webhook must not have leader-election env")
if "fsGroup" in webhook_pod["securityContext"]:
    raise AssertionError("split external webhook must not set generated-TLS fsGroup")
if webhook_container.get("volumeMounts") != [{"name": "webhook-tls", "mountPath": "/etc/echo-operator/certs", "readOnly": True}]:
    raise AssertionError("split external webhook must read-only mount only its TLS Secret")
if webhook_pod.get("volumes") != [{"name": "webhook-tls", "secret": {"secretName": "echo-operator-webhook-tls"}}]:
    raise AssertionError("split external webhook must have only its TLS Secret volume")

targets = []
for resource in resources:
    if resource["kind"] == "CustomResourceDefinition":
        targets.append(("CRD", resource["spec"]["conversion"]["webhook"]["clientConfig"]))
    elif resource["kind"] in {"ValidatingWebhookConfiguration", "MutatingWebhookConfiguration"}:
        targets.append((resource["kind"], resource["webhooks"][0]["clientConfig"]))
if {label for label, _ in targets} != {"CRD", "ValidatingWebhookConfiguration", "MutatingWebhookConfiguration"}:
    raise AssertionError(f"split external TLS must render CRD and Helm-owned admission targets, got {targets}")
for label, client_config in targets:
    if client_config["service"] != {"namespace": RELEASE_NAMESPACE, "name": WEBHOOK_SERVICE,
                                    "path": "/convert" if label == "CRD" else (
                                        "/validate/echo.example.com" if label == "ValidatingWebhookConfiguration"
                                        else "/mutate/echo.example.com"), "port": 443}:
        raise AssertionError(f"{label} must use the split webhook Service identity")
    if base64.b64decode(client_config["caBundle"], validate=True) != expected_ca:
        raise AssertionError(f"{label} CA bundle must decode to the literal external CA bytes")

print("split Task 5 external TLS contract passed")
PY
}

assert_poison_isolation() {
  local split_render="$1"

  python3 - "${split_render}" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

indexed = {
    (resource["kind"], resource["metadata"]["name"]): resource
    for resource in resources
}

controller_dep = indexed[("Deployment", "contract-echo-operator-controller")]
webhook_dep = indexed[("Deployment", "contract-echo-operator-webhook")]

# Top-level replicas=9 must NOT appear; nested controller=2, webhook=3 must appear
if controller_dep["spec"]["replicas"] != 2:
    raise AssertionError(f"controller replicas must be nested 2, got {controller_dep['spec']['replicas']}")
if webhook_dep["spec"]["replicas"] != 3:
    raise AssertionError(f"webhook replicas must be nested 3, got {webhook_dep['spec']['replicas']}")

# Top-level resources poison must NOT appear; nested resources must appear
controller_resources = controller_dep["spec"]["template"]["spec"]["containers"][0]["resources"]
webhook_resources = webhook_dep["spec"]["template"]["spec"]["containers"][0]["resources"]

expected_controller_resources = {
    "limits": {"cpu": "999m", "memory": "999Mi"},
    "requests": {"cpu": "111m", "memory": "111Mi"},
}
expected_webhook_resources = {
    "limits": {"cpu": "888m", "memory": "888Mi"},
    "requests": {"cpu": "222m", "memory": "222Mi"},
}

if controller_resources != expected_controller_resources:
    raise AssertionError(f"controller resources must be nested, got {controller_resources}")
if webhook_resources != expected_webhook_resources:
    raise AssertionError(f"webhook resources must be nested, got {webhook_resources}")

# Top-level podLabels poison must NOT appear in template labels
controller_labels = controller_dep["spec"]["template"]["metadata"]["labels"]
webhook_labels = webhook_dep["spec"]["template"]["metadata"]["labels"]
if "poison-label" in controller_labels:
    raise AssertionError("controller must not inherit top-level podLabels")
if "poison-label" in webhook_labels:
    raise AssertionError("webhook must not inherit top-level podLabels")

# Top-level SA name must NOT appear
controller_sa = controller_dep["spec"]["template"]["spec"]["serviceAccountName"]
webhook_sa = webhook_dep["spec"]["template"]["spec"]["serviceAccountName"]
if controller_sa == "poison-sa":
    raise AssertionError("controller must not inherit top-level serviceAccount name")
if webhook_sa == "poison-sa":
    raise AssertionError("webhook must not inherit top-level serviceAccount name")

expected_controller_annotations = {"component-annotation": "controller"}
expected_webhook_annotations = {"component-annotation": "webhook"}
if controller_dep["spec"]["template"]["metadata"].get("annotations") != expected_controller_annotations:
    raise AssertionError("controller must use only its nested podAnnotations")
if webhook_dep["spec"]["template"]["metadata"].get("annotations") != expected_webhook_annotations:
    raise AssertionError("webhook must use only its nested podAnnotations")
if controller_labels.get("component-label") != "controller" or webhook_labels.get("component-label") != "webhook":
    raise AssertionError("split Pods must use their independent nested podLabels")

controller_pod = controller_dep["spec"]["template"]["spec"]
webhook_pod = webhook_dep["spec"]["template"]["spec"]
if controller_pod.get("nodeSelector") != {"component-node": "controller"}:
    raise AssertionError("controller must use only its nested nodeSelector")
if webhook_pod.get("nodeSelector") != {"component-node": "webhook"}:
    raise AssertionError("webhook must use only its nested nodeSelector")
if controller_pod.get("tolerations") != [{"key": "controller", "operator": "Exists"}]:
    raise AssertionError("controller must use only its nested tolerations")
if webhook_pod.get("tolerations") != [{"key": "webhook", "operator": "Exists"}]:
    raise AssertionError("webhook must use only its nested tolerations")
if controller_pod.get("affinity") != {"nodeAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": {"nodeSelectorTerms": [{"matchExpressions": [{"key": "component", "operator": "In", "values": ["controller"]}]}]}}}:
    raise AssertionError("controller must use only its nested affinity")
if webhook_pod.get("affinity") != {"nodeAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": {"nodeSelectorTerms": [{"matchExpressions": [{"key": "component", "operator": "In", "values": ["webhook"]}]}]}}}:
    raise AssertionError("webhook must use only its nested affinity")
if controller_sa != "controller-only-sa" or webhook_sa != "webhook-only-sa":
    raise AssertionError("split Pods must use their independent nested ServiceAccounts")

print("poison isolation passed")
PY
}

assert_fullname_override() {
  local render_output="$1"

  python3 - "${render_output}" <<'PY'
import sys
import yaml

with open(sys.argv[1], encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

indexed = {
    (resource["kind"], resource["metadata"]["name"]): resource
    for resource in resources
}

deployment = indexed[("Deployment", "custom-name")]
service = indexed[("Service", "custom-name")]
service_account = indexed[("ServiceAccount", "custom-name")]

expected_selector = {
    "app.kubernetes.io/name": "echo-operator",
    "app.kubernetes.io/instance": "contract",
}
if deployment["spec"]["selector"]["matchLabels"] != expected_selector:
    raise AssertionError("fullnameOverride must not change selector labels")
if service["spec"]["selector"] != expected_selector:
    raise AssertionError("fullnameOverride must not change Service selector")

print("fullnameOverride contract passed")
PY
}

assert_external_ca_secret() {
  local render_output="$1"
  local pem_file="$2"

  python3 - "${render_output}" "${pem_file}" <<'PY'
import base64
import sys
import yaml

render_path, pem_path = sys.argv[1:]
with open(render_path, encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]
with open(pem_path, "rb") as source:
    pem_bytes = source.read()

deployment = next(resource for resource in resources if resource["kind"] == "Deployment")
service = next(resource for resource in resources if resource["kind"] == "Service")
env = {
    entry["name"]: entry.get("value")
    for entry in deployment["spec"]["template"]["spec"]["containers"][0]["env"]
}
expected_env = {
    "CONTROLLER_ENABLED": "true",
    "WEBHOOK_ENABLED": "true",
    "WEBHOOK_REGISTRATION_CLEANUP_ENABLED": "true",
    "WEBHOOK_SELF_REGISTRATION_ENABLED": "false",
}
for name, expected in expected_env.items():
    if env.get(name) != expected:
        raise AssertionError(f"Helm-owned combined env {name} must be {expected}, got {env.get(name)}")

validating = [resource for resource in resources if resource["kind"] == "ValidatingWebhookConfiguration"]
mutating = [resource for resource in resources if resource["kind"] == "MutatingWebhookConfiguration"]
if len(validating) != 1 or len(mutating) != 1:
    raise AssertionError("Helm-owned external-TLS combined render must have exactly one validating and one mutating configuration")

service_name = service["metadata"]["name"]
for label, client_config in [
    ("CRD", next(resource for resource in resources if resource["kind"] == "CustomResourceDefinition")["spec"]["conversion"]["webhook"]["clientConfig"]),
    ("Validating", validating[0]["webhooks"][0]["clientConfig"]),
    ("Mutating", mutating[0]["webhooks"][0]["clientConfig"]),
]:
    target = client_config["service"]
    if target["name"] != service_name or target["namespace"] != "contract-ns" or target["port"] != 443:
        raise AssertionError(f"{label} client config must target the combined webhook Service")

# Build synthetic external Secret with Kubernetes-base64 ca.crt
secret_ca_b64 = base64.b64encode(pem_bytes).decode()

ca_bundles = []
for resource in resources:
    if resource["kind"] == "CustomResourceDefinition":
        ca_bundles.append(("CRD", resource["spec"]["conversion"]["webhook"]["clientConfig"]["caBundle"]))
    if resource["kind"] == "ValidatingWebhookConfiguration":
        ca_bundles.append(("Validating", resource["webhooks"][0]["clientConfig"]["caBundle"]))
    if resource["kind"] == "MutatingWebhookConfiguration":
        ca_bundles.append(("Mutating", resource["webhooks"][0]["clientConfig"]["caBundle"]))

if len(ca_bundles) != 3:
    raise AssertionError(f"expected CRD plus two admission CA bundles, got {len(ca_bundles)}")

for label, value in ca_bundles:
    decoded = base64.b64decode(value, validate=True)
    if decoded != pem_bytes:
        raise AssertionError(f"{label} caBundle does not match PEM bytes")

# Synthetic Secret data.ca.crt must decode to the same PEM bytes
decoded_secret_ca = base64.b64decode(secret_ca_b64, validate=True)
if decoded_secret_ca != pem_bytes:
    raise AssertionError("synthetic Secret ca.crt does not match PEM bytes")

# Cross-compare: decoded CRD/admission bundles must equal decoded Secret ca.crt
for label, value in ca_bundles:
    decoded = base64.b64decode(value, validate=True)
    if decoded != decoded_secret_ca:
        raise AssertionError(f"{label} caBundle does not equal decoded Secret ca.crt")

print("external CA Secret comparison passed")
PY
}

assert_ownership_transition_contract() {
  local runtime_render="$1"
  local helm_render="$2"
  local webhook_service_name="$3"

  python3 - "${runtime_render}" "${helm_render}" "${webhook_service_name}" <<'PY'
import sys
import yaml

runtime_path, helm_path, webhook_service_name = sys.argv[1:]
FULLNAME = "contract-echo-operator"
RELEASE_NAMESPACE = "contract-ns"
RUNTIME_NAME = f"echo-operator.{RELEASE_NAMESPACE}.echo.example.com"
HELM_VALIDATING_NAME = f"{FULLNAME}-validating"
HELM_MUTATING_NAME = f"{FULLNAME}-mutating"

def documents(path):
    with open(path, encoding="utf-8") as source:
        return [document for document in yaml.safe_load_all(source) if document]

def serving_env(resources):
    for resource in resources:
        if resource["kind"] != "Deployment":
            continue
        env = {
            entry["name"]: entry.get("value")
            for entry in resource["spec"]["template"]["spec"]["containers"][0]["env"]
        }
        if env.get("WEBHOOK_ENABLED") == "true":
            return env
    raise AssertionError("render is missing a webhook-serving Deployment")

def resources_of_kind(resources, kind):
    return [resource for resource in resources if resource["kind"] == kind]

def assert_service_identity(resources, include_admission):
    services = resources_of_kind(resources, "Service")
    if len([service for service in services if service["metadata"]["name"] == webhook_service_name]) != 1:
        raise AssertionError(f"render must contain exactly one resolved webhook Service {webhook_service_name}")
    env = serving_env(resources)
    if env.get("WEBHOOK_SERVICE_NAME") != webhook_service_name:
        raise AssertionError("certificate/runtime config must use the resolved webhook Service name")
    if env.get("WEBHOOK_SERVICE_NAMESPACE") != RELEASE_NAMESPACE:
        raise AssertionError("runtime config must use the webhook Service namespace")

    crd = resources_of_kind(resources, "CustomResourceDefinition")[0]
    targets = [("CRD", crd["spec"]["conversion"]["webhook"]["clientConfig"]["service"])]
    if include_admission:
        targets.extend(
            (resource["kind"], resource["webhooks"][0]["clientConfig"]["service"])
            for resource in resources
            if resource["kind"] in {"ValidatingWebhookConfiguration", "MutatingWebhookConfiguration"}
        )
    for label, target in targets:
        if target["name"] != webhook_service_name or target["namespace"] != RELEASE_NAMESPACE:
            raise AssertionError(f"{label} must use the resolved webhook Service identity")

runtime = documents(runtime_path)
helm = documents(helm_path)
runtime_env = serving_env(runtime)
helm_env = serving_env(helm)

if (runtime_env.get("WEBHOOK_REGISTRATION_CLEANUP_ENABLED"),
        runtime_env.get("WEBHOOK_SELF_REGISTRATION_ENABLED")) != ("false", "true"):
    raise AssertionError("runtime-owned render must disable cleanup and enable self-registration")
if runtime_env.get("WEBHOOK_PREDECESSOR_VALIDATING_NAME") != HELM_VALIDATING_NAME:
    raise AssertionError("runtime-owned render must wait for the exact validating predecessor name")
if runtime_env.get("WEBHOOK_PREDECESSOR_MUTATING_NAME") != HELM_MUTATING_NAME:
    raise AssertionError("runtime-owned render must wait for the exact mutating predecessor name")
if resources_of_kind(runtime, "ValidatingWebhookConfiguration") or resources_of_kind(
        runtime, "MutatingWebhookConfiguration"):
    raise AssertionError("runtime-owned render must not create Helm admission configurations")

if (helm_env.get("WEBHOOK_REGISTRATION_CLEANUP_ENABLED"),
        helm_env.get("WEBHOOK_SELF_REGISTRATION_ENABLED")) != ("true", "false"):
    raise AssertionError("Helm-owned render must enable runtime-name cleanup and disable self-registration")
if {"WEBHOOK_PREDECESSOR_VALIDATING_NAME", "WEBHOOK_PREDECESSOR_MUTATING_NAME"} & helm_env.keys():
    raise AssertionError("Helm-owned render must not wait for or delete Helm predecessor names")
validating = resources_of_kind(helm, "ValidatingWebhookConfiguration")
mutating = resources_of_kind(helm, "MutatingWebhookConfiguration")
if [resource["metadata"]["name"] for resource in validating] != [HELM_VALIDATING_NAME]:
    raise AssertionError("Helm-owned render must contain exactly its stable validating owner")
if [resource["metadata"]["name"] for resource in mutating] != [HELM_MUTATING_NAME]:
    raise AssertionError("Helm-owned render must contain exactly its stable mutating owner")

assert_service_identity(runtime, include_admission=False)
assert_service_identity(helm, include_admission=True)

def delete_targets(resources, admission_resource):
    targets = set()
    for role in resources_of_kind(resources, "ClusterRole"):
        for rule in role.get("rules", []):
            if admission_resource in rule.get("resources", []) and "delete" in rule.get("verbs", []):
                targets.update(rule.get("resourceNames", []))
    return targets

cleanup_targets = {
    "validating": delete_targets(helm, "validatingwebhookconfigurations"),
    "mutating": delete_targets(helm, "mutatingwebhookconfigurations"),
}
if cleanup_targets != {"validating": {RUNTIME_NAME}, "mutating": {RUNTIME_NAME}}:
    raise AssertionError(f"Helm ownership cleanup must target only stable runtime names: {cleanup_targets}")

runtime_created = {"validating": {RUNTIME_NAME}, "mutating": {RUNTIME_NAME}}
helm_created = {
    "validating": {resource["metadata"]["name"] for resource in validating},
    "mutating": {resource["metadata"]["name"] for resource in mutating},
}
barrier_targets = {
    "validating": {runtime_env["WEBHOOK_PREDECESSOR_VALIDATING_NAME"]},
    "mutating": {runtime_env["WEBHOOK_PREDECESSOR_MUTATING_NAME"]},
}
runtime_to_helm = {
    owner: (runtime_created[owner] - cleanup_targets[owner]) | helm_created[owner]
    for owner in runtime_created
}
helm_to_runtime = {
    owner: (helm_created[owner] - barrier_targets[owner]) | runtime_created[owner]
    for owner in runtime_created
}
for direction, final_owners in (("runtime-to-Helm", runtime_to_helm), ("Helm-to-runtime", helm_to_runtime)):
    if any(len(owners) != 1 for owners in final_owners.values()):
        raise AssertionError(f"{direction} must converge to one validating and one mutating owner")

print(f"ownership transition contract passed for Service {webhook_service_name}")
PY
}

assert_rbac_matrix() {
  local render_output="$1"
  local scenario="$2"
  local release_namespace="${3:-${NAMESPACE}}"

  python3 - "${render_output}" "${scenario}" "${release_namespace}" <<'PY'
import sys
import yaml

render_path, scenario, RELEASE_NAMESPACE = sys.argv[1:]
FULLNAME = "contract-echo-operator"

with open(render_path, encoding="utf-8") as source:
    resources = [document for document in yaml.safe_load_all(source) if document]

controller_roles = [
    resource for resource in resources
    if resource["kind"] == "Role" and resource["metadata"]["name"] == f"{FULLNAME}-controller"
]
WATCHED_NAMESPACE = controller_roles[0]["metadata"]["namespace"] if controller_roles else RELEASE_NAMESPACE
RUNTIME_ADMISSION_NAME = f"echo-operator.{WATCHED_NAMESPACE}.echo.example.com"

def key(resource):
    kind = resource["kind"]
    namespace = resource["metadata"].get("namespace") if kind in {"Role", "RoleBinding"} else None
    return kind, resource["metadata"]["name"], namespace

def rule(api_group, resource, verbs, resource_names=None):
    return (tuple(sorted([api_group])), tuple(sorted(resource)),
            None if resource_names is None else tuple(sorted(resource_names)), tuple(sorted(verbs)))

def normalized_rules(resource):
    return sort_rules(
        (tuple(sorted(item.get("apiGroups", []))), tuple(sorted(item.get("resources", []))),
         None if "resourceNames" not in item else tuple(sorted(item["resourceNames"])),
         tuple(sorted(item.get("verbs", []))))
        for item in resource.get("rules", [])
    )

def sort_rules(rules):
    return sorted(rules, key=lambda item: (item[0], item[1], "" if item[2] is None else ",".join(item[2]), item[3]))

controller_rules = [
    rule("example.com", ["echoresources"], ["get", "list", "watch", "create", "update", "patch", "delete"]),
    rule("example.com", ["echoresources/status"], ["get", "update", "patch"]),
    rule("example.com", ["echoresources/finalizers"], ["update"]),
    rule("", ["pods", "services"], ["get", "list", "watch", "create", "update", "patch", "delete"]),
    rule("apps", ["deployments"], ["get", "list", "watch", "create", "update", "patch", "delete"]),
    rule("", ["events"], ["get", "create", "patch"]),
]
secret_rules = [
    rule("", ["secrets"], ["get"], ["echo-operator-webhook-ca"]),
    rule("", ["secrets"], ["create"]),
]
lease_rules = [
    rule("coordination.k8s.io", ["leases"], ["get", "list", "watch", "create", "update", "patch", "delete"]),
]
runtime_owner_rules = [
    rule("admissionregistration.k8s.io", ["validatingwebhookconfigurations"], ["create"]),
    rule("admissionregistration.k8s.io", ["validatingwebhookconfigurations"], ["get", "update", "patch", "delete"], [RUNTIME_ADMISSION_NAME]),
    rule("admissionregistration.k8s.io", ["mutatingwebhookconfigurations"], ["create"]),
    rule("admissionregistration.k8s.io", ["mutatingwebhookconfigurations"], ["get", "update", "patch", "delete"], [RUNTIME_ADMISSION_NAME]),
]
barrier_rules = [
    rule("admissionregistration.k8s.io", ["validatingwebhookconfigurations"], ["get"], [f"{FULLNAME}-validating"]),
    rule("admissionregistration.k8s.io", ["mutatingwebhookconfigurations"], ["get"], [f"{FULLNAME}-mutating"]),
]
cleanup_rules = [
    rule("admissionregistration.k8s.io", ["validatingwebhookconfigurations"], ["get", "delete"], [RUNTIME_ADMISSION_NAME]),
    rule("admissionregistration.k8s.io", ["mutatingwebhookconfigurations"], ["get", "delete"], [RUNTIME_ADMISSION_NAME]),
]
conversion_rules = [
    rule("apiextensions.k8s.io", ["customresourcedefinitions"], ["get", "update", "patch"], ["echoresources.example.com"]),
]

def role_key(name, namespace):
    return "Role", name, namespace

def role_binding_key(name, namespace):
    return "RoleBinding", name, namespace

def cluster_role_key(name):
    return "ClusterRole", name, None

def cluster_role_binding_key(name):
    return "ClusterRoleBinding", name, None

def expected_matrix(controller_service_account, webhook_service_account, controller_namespace,
                    include_secret, cluster_rules, include_lease=False, expected_service_accounts=None,
                    expected_deployments=None):
    rules = {
        role_key(f"{FULLNAME}-controller", controller_namespace): controller_rules,
        cluster_role_key(f"{FULLNAME}-webhook"): cluster_rules,
    }
    bindings = {
        role_binding_key(f"{FULLNAME}-controller", controller_namespace):
            ("Role", f"{FULLNAME}-controller", controller_service_account),
        cluster_role_binding_key(f"{FULLNAME}-webhook"):
            ("ClusterRole", f"{FULLNAME}-webhook", webhook_service_account),
    }
    if include_secret:
        rules[role_key(f"{FULLNAME}-webhook-secret", RELEASE_NAMESPACE)] = secret_rules
        bindings[role_binding_key(f"{FULLNAME}-webhook-secret", RELEASE_NAMESPACE)] = (
            "Role", f"{FULLNAME}-webhook-secret", webhook_service_account)
    if include_lease:
        rules[role_key(f"{FULLNAME}-controller-lease", RELEASE_NAMESPACE)] = lease_rules
        bindings[role_binding_key(f"{FULLNAME}-controller-lease", RELEASE_NAMESPACE)] = (
            "Role", f"{FULLNAME}-controller-lease", controller_service_account)
    return rules, bindings, expected_service_accounts, expected_deployments

if scenario == "combined-auto":
    expected_rules, expected_bindings, expected_sas, expected_deployments = expected_matrix(
        FULLNAME, FULLNAME, RELEASE_NAMESPACE, True, runtime_owner_rules + barrier_rules + conversion_rules,
        expected_service_accounts={FULLNAME}, expected_deployments={FULLNAME: FULLNAME})
elif scenario == "split-auto":
    expected_rules, expected_bindings, expected_sas, expected_deployments = expected_matrix(
        f"{FULLNAME}-controller", f"{FULLNAME}-webhook", RELEASE_NAMESPACE, True,
        runtime_owner_rules + barrier_rules + conversion_rules,
        expected_service_accounts={f"{FULLNAME}-controller", f"{FULLNAME}-webhook"},
        expected_deployments={f"{FULLNAME}-controller": f"{FULLNAME}-controller",
                              f"{FULLNAME}-webhook": f"{FULLNAME}-webhook"})
elif scenario in {"split-watched-lease", "split-release-watched-lease"}:
    expected_rules, expected_bindings, expected_sas, expected_deployments = expected_matrix(
        f"{FULLNAME}-controller", f"{FULLNAME}-webhook", "watched-ns", True,
        runtime_owner_rules + barrier_rules + conversion_rules, include_lease=True,
        expected_service_accounts={f"{FULLNAME}-controller", f"{FULLNAME}-webhook"},
        expected_deployments={f"{FULLNAME}-controller": f"{FULLNAME}-controller",
                              f"{FULLNAME}-webhook": f"{FULLNAME}-webhook"})
elif scenario == "combined-external-helm":
    expected_rules, expected_bindings, expected_sas, expected_deployments = expected_matrix(
        FULLNAME, FULLNAME, RELEASE_NAMESPACE, False, cleanup_rules,
        expected_service_accounts={FULLNAME}, expected_deployments={FULLNAME: FULLNAME})
elif scenario == "split-external-runtime":
    expected_rules, expected_bindings, expected_sas, expected_deployments = expected_matrix(
        f"{FULLNAME}-controller", f"{FULLNAME}-webhook", RELEASE_NAMESPACE, False,
        runtime_owner_rules + barrier_rules,
        expected_service_accounts={f"{FULLNAME}-controller", f"{FULLNAME}-webhook"},
        expected_deployments={f"{FULLNAME}-controller": f"{FULLNAME}-controller",
                              f"{FULLNAME}-webhook": f"{FULLNAME}-webhook"})
elif scenario == "split-external-helm":
    expected_rules, expected_bindings, expected_sas, expected_deployments = expected_matrix(
        f"{FULLNAME}-controller", f"{FULLNAME}-webhook", RELEASE_NAMESPACE, False, cleanup_rules,
        expected_service_accounts={f"{FULLNAME}-controller", f"{FULLNAME}-webhook"},
        expected_deployments={f"{FULLNAME}-controller": f"{FULLNAME}-controller",
                              f"{FULLNAME}-webhook": f"{FULLNAME}-webhook"})
elif scenario == "combined-controller-only":
    expected_rules, expected_bindings, expected_sas, expected_deployments = expected_matrix(
        FULLNAME, FULLNAME, RELEASE_NAMESPACE, False, cleanup_rules,
        expected_service_accounts={FULLNAME}, expected_deployments={FULLNAME: FULLNAME})
elif scenario == "combined-custom-no-rbac":
    expected_rules, expected_bindings = {}, {}
    expected_sas = set()
    expected_deployments = {FULLNAME: "external-combined"}
elif scenario == "split-custom-no-rbac":
    expected_rules, expected_bindings = {}, {}
    expected_sas = set()
    expected_deployments = {f"{FULLNAME}-controller": "external-controller",
                            f"{FULLNAME}-webhook": "external-webhook"}
else:
    raise AssertionError(f"unknown RBAC matrix scenario: {scenario}")

rbac_kinds = {"Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding"}
actual_rbac = {key(resource): resource for resource in resources if resource["kind"] in rbac_kinds}
expected_rbac_keys = set(expected_rules) | set(expected_bindings)
if set(actual_rbac) != expected_rbac_keys:
    raise AssertionError(f"{scenario}: RBAC object set mismatch: {set(actual_rbac)} != {expected_rbac_keys}")

for resource_key, rules in expected_rules.items():
    actual = normalized_rules(actual_rbac[resource_key])
    expected = sort_rules(rules)
    if actual != expected:
        raise AssertionError(f"{scenario}: exact rules mismatch for {resource_key}: {actual} != {expected}")

for resource_key, (role_kind, role_name, service_account) in expected_bindings.items():
    binding = actual_rbac[resource_key]
    if binding.get("roleRef") != {
            "apiGroup": "rbac.authorization.k8s.io", "kind": role_kind, "name": role_name}:
        raise AssertionError(f"{scenario}: roleRef mismatch for {resource_key}: {binding.get('roleRef')}")
    expected_subject = {"kind": "ServiceAccount", "name": service_account, "namespace": RELEASE_NAMESPACE}
    if binding.get("subjects") != [expected_subject]:
        raise AssertionError(f"{scenario}: binding subject mismatch for {resource_key}: {binding.get('subjects')}")

actual_sas = {
    resource["metadata"]["name"] for resource in resources if resource["kind"] == "ServiceAccount"
}
if actual_sas != expected_sas:
    raise AssertionError(f"{scenario}: ServiceAccount set mismatch: {actual_sas} != {expected_sas}")

actual_deployments = {
    resource["metadata"]["name"]: resource["spec"]["template"]["spec"]["serviceAccountName"]
    for resource in resources if resource["kind"] == "Deployment"
}
if actual_deployments != expected_deployments:
    raise AssertionError(f"{scenario}: Deployment ServiceAccounts mismatch: {actual_deployments} != {expected_deployments}")

def rules_for(service_account, namespace):
    result = []
    for resource in actual_rbac.values():
        if resource["kind"] == "RoleBinding" and resource["metadata"].get("namespace") == namespace:
            if resource.get("subjects") == [{"kind": "ServiceAccount", "name": service_account, "namespace": RELEASE_NAMESPACE}]:
                role = actual_rbac[role_key(resource["roleRef"]["name"], namespace)]
                result.extend(role.get("rules", []))
        if resource["kind"] == "ClusterRoleBinding":
            if resource.get("subjects") == [{"kind": "ServiceAccount", "name": service_account, "namespace": RELEASE_NAMESPACE}]:
                cluster_role = actual_rbac[cluster_role_key(resource["roleRef"]["name"])]
                result.extend(cluster_role.get("rules", []))
    return result

def grants(service_account, namespace, api_group, resource, verb, resource_name=None):
    for candidate in rules_for(service_account, namespace):
        if api_group not in candidate.get("apiGroups", []) or resource not in candidate.get("resources", []):
            continue
        if verb not in candidate.get("verbs", []):
            continue
        names = candidate.get("resourceNames")
        if names is None or resource_name in names:
            return True
    return False

def require_grant(service_account, namespace, api_group, resource, verb, resource_name=None):
    if not grants(service_account, namespace, api_group, resource, verb, resource_name):
        raise AssertionError(f"{scenario}: expected {service_account} to grant {verb} {api_group}/{resource} {resource_name}")

def require_denial(service_account, namespace, api_group, resource, verb, resource_name=None):
    if grants(service_account, namespace, api_group, resource, verb, resource_name):
        raise AssertionError(f"{scenario}: forbidden grant: {service_account} may {verb} {api_group}/{resource} {resource_name}")

if scenario in {"split-auto", "split-watched-lease", "split-release-watched-lease", "split-external-runtime", "split-external-helm"}:
    controller = f"{FULLNAME}-controller"
    webhook = f"{FULLNAME}-webhook"
    controller_namespace = "watched-ns" if scenario in {"split-watched-lease", "split-release-watched-lease"} else RELEASE_NAMESPACE
    require_grant(controller, controller_namespace, "example.com", "echoresources", "update")
    require_denial(controller, RELEASE_NAMESPACE, "", "secrets", "get", "echo-operator-webhook-ca")
    require_denial(controller, RELEASE_NAMESPACE, "admissionregistration.k8s.io", "validatingwebhookconfigurations", "create")
    require_denial(controller, RELEASE_NAMESPACE, "apiextensions.k8s.io", "customresourcedefinitions", "patch", "echoresources.example.com")
    require_denial(webhook, controller_namespace, "example.com", "echoresources", "update")
    require_denial(webhook, controller_namespace, "apps", "deployments", "delete")
    require_denial(webhook, controller_namespace, "", "events", "create")
    if scenario in {"split-watched-lease", "split-release-watched-lease"}:
        require_grant(controller, RELEASE_NAMESPACE, "coordination.k8s.io", "leases", "update")
    else:
        require_denial(controller, RELEASE_NAMESPACE, "coordination.k8s.io", "leases", "update")
    require_denial(webhook, RELEASE_NAMESPACE, "coordination.k8s.io", "leases", "update")

if scenario in {"split-auto", "combined-auto"}:
    webhook = f"{FULLNAME}-webhook" if scenario == "split-auto" else FULLNAME
    require_grant(webhook, RELEASE_NAMESPACE, "", "secrets", "get", "echo-operator-webhook-ca")
    require_grant(webhook, RELEASE_NAMESPACE, "admissionregistration.k8s.io", "validatingwebhookconfigurations", "create")
    require_grant(webhook, RELEASE_NAMESPACE, "admissionregistration.k8s.io", "validatingwebhookconfigurations", "get", f"{FULLNAME}-validating")
    require_grant(webhook, RELEASE_NAMESPACE, "apiextensions.k8s.io", "customresourcedefinitions", "patch", "echoresources.example.com")

if scenario in {"combined-external-helm", "split-external-helm", "combined-controller-only"}:
    cleanup_owner = f"{FULLNAME}-webhook" if scenario == "split-external-helm" else FULLNAME
    require_grant(cleanup_owner, RELEASE_NAMESPACE, "admissionregistration.k8s.io", "validatingwebhookconfigurations", "delete", RUNTIME_ADMISSION_NAME)
    require_denial(cleanup_owner, RELEASE_NAMESPACE, "admissionregistration.k8s.io", "validatingwebhookconfigurations", "delete", f"{FULLNAME}-validating")
    require_denial(cleanup_owner, RELEASE_NAMESPACE, "admissionregistration.k8s.io", "validatingwebhookconfigurations", "create")
    require_denial(cleanup_owner, RELEASE_NAMESPACE, "apiextensions.k8s.io", "customresourcedefinitions", "patch", "echoresources.example.com")

if scenario == "split-external-runtime":
    webhook = f"{FULLNAME}-webhook"
    require_denial(webhook, RELEASE_NAMESPACE, "", "secrets", "get", "echo-operator-webhook-ca")
    require_grant(webhook, RELEASE_NAMESPACE, "admissionregistration.k8s.io", "mutatingwebhookconfigurations", "get", f"{FULLNAME}-mutating")
    require_denial(webhook, RELEASE_NAMESPACE, "apiextensions.k8s.io", "customresourcedefinitions", "patch", "echoresources.example.com")

print(f"RBAC matrix passed: {scenario}")
PY
}

expect_failure() {
  local name="$1"
  local expected_message="$2"
  shift 2
  local output="${WORK_DIR}/${name}.log"

  if run_bounded helm template "${RELEASE}" "${CHART_DIR}" --namespace "${NAMESPACE}" "$@" >"${output}" 2>&1; then
    printf 'expected %s to fail, but it succeeded\n' "${name}" >&2
    exit 1
  fi

  local actual_message
  actual_message="$(extract_fail_message "$(cat "${output}")")"
  if [[ "${actual_message}" != "${expected_message}" ]]; then
    printf 'expected %s to fail with: %s\n got: %s\n' "${name}" "${expected_message}" "${actual_message}" >&2
    cat "${output}" >&2
    exit 1
  fi
  printf 'negative contract passed: %s\n' "${name}"
}

if [[ ! -f "${BASELINE_ARCHIVE}" ]]; then
  printf 'missing immutable baseline archive: %s\n' "${BASELINE_ARCHIVE}" >&2
  exit 1
fi

# 1. Lint
run_bounded helm lint "${CHART_DIR}" >"${WORK_DIR}/lint.log"

# 2. Baseline combined assertions
tar -xf "${BASELINE_ARCHIVE}" -C "${WORK_DIR}"
run_bounded helm template "${RELEASE}" "${WORK_DIR}/example/echo-operator/helm/echo-operator" --namespace "${NAMESPACE}" >"${WORK_DIR}/baseline.yaml"
render "${WORK_DIR}/combined.yaml"
assert_combined_contract "${WORK_DIR}/baseline.yaml" "${WORK_DIR}/combined.yaml"
assert_combined_identity_rbac_characterization "${WORK_DIR}/combined.yaml"
assert_rbac_matrix "${WORK_DIR}/combined.yaml" combined-auto

render "${WORK_DIR}/combined-webhook-disabled.yaml" --set webhook.enabled=false
assert_webhook_disabled_combined_contract "${WORK_DIR}/combined-webhook-disabled.yaml"
assert_rbac_matrix "${WORK_DIR}/combined-webhook-disabled.yaml" combined-controller-only

cat >"${WORK_DIR}/poison-combined-values.yaml" <<'YAML'
replicas: 9
resources:
  limits:
    cpu: 999m
    memory: 999Mi
  requests:
    cpu: 111m
    memory: 111Mi
podAnnotations:
  poison-annotation: poison
podLabels:
  poison-label: poison
nodeSelector:
  poison-node: poison
tolerations:
  - key: poison
    operator: Exists
affinity:
  nodeAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      nodeSelectorTerms: []
serviceAccount:
  create: true
  name: poison-combined-sa
YAML
render "${WORK_DIR}/poison-combined.yaml" -f "${WORK_DIR}/poison-combined-values.yaml"
assert_combined_shared_workload_values "${WORK_DIR}/poison-combined.yaml"

# 5. Split-specific assertions
render "${WORK_DIR}/split.yaml" --set deploymentMode=split
assert_split_skeleton_characterization "${WORK_DIR}/split.yaml"
assert_split_contract "${WORK_DIR}/split.yaml"
assert_split_task_five_auto "${WORK_DIR}/split.yaml"
assert_rbac_matrix "${WORK_DIR}/split.yaml" split-auto
render "${WORK_DIR}/split-watched-lease.yaml" \
  --set deploymentMode=split \
  --set operator.namespace=watched-ns \
  --set leaderElection.enabled=true
assert_rbac_matrix "${WORK_DIR}/split-watched-lease.yaml" split-watched-lease
render_in_namespace "${WORK_DIR}/split-release-watched-lease.yaml" release-ns \
  --set deploymentMode=split \
  --set operator.namespace=watched-ns \
  --set leaderElection.enabled=true
assert_rbac_matrix "${WORK_DIR}/split-release-watched-lease.yaml" split-release-watched-lease release-ns

# 6. Poison-value isolation test
cat >"${WORK_DIR}/poison-values.yaml" <<'YAML'
deploymentMode: split
replicas: 9
resources:
  limits:
    cpu: 999m
    memory: 999Mi
  requests:
    cpu: 111m
    memory: 111Mi
podLabels:
  poison-label: poison
podAnnotations:
  poison-annotation: poison
nodeSelector:
  poison-node: poison
tolerations:
  - key: poison
    operator: Exists
affinity:
  nodeAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      nodeSelectorTerms: []
serviceAccount:
  create: false
  name: poison-sa
controller:
  workload:
    replicas: 2
    resources:
      limits:
        cpu: 999m
        memory: 999Mi
      requests:
        cpu: 111m
        memory: 111Mi
    podAnnotations:
      component-annotation: controller
    podLabels:
      component-label: controller
    nodeSelector:
      component-node: controller
    tolerations:
      - key: controller
        operator: Exists
    affinity:
      nodeAffinity:
        requiredDuringSchedulingIgnoredDuringExecution:
          nodeSelectorTerms:
            - matchExpressions:
                - key: component
                  operator: In
                  values: [controller]
    serviceAccount:
      create: false
      name: controller-only-sa
webhook:
  workload:
    replicas: 3
    resources:
      limits:
        cpu: 888m
        memory: 888Mi
      requests:
        cpu: 222m
        memory: 222Mi
    podAnnotations:
      component-annotation: webhook
    podLabels:
      component-label: webhook
    nodeSelector:
      component-node: webhook
    tolerations:
      - key: webhook
        operator: Exists
    affinity:
      nodeAffinity:
        requiredDuringSchedulingIgnoredDuringExecution:
          nodeSelectorTerms:
            - matchExpressions:
                - key: component
                  operator: In
                  values: [webhook]
    serviceAccount:
      create: false
      name: webhook-only-sa
YAML
render "${WORK_DIR}/poison-split.yaml" -f "${WORK_DIR}/poison-values.yaml"
assert_poison_isolation "${WORK_DIR}/poison-split.yaml"

render "${WORK_DIR}/combined-custom-no-rbac.yaml" \
  --set rbac.create=false \
  --set serviceAccount.create=false \
  --set serviceAccount.name=external-combined
assert_rbac_matrix "${WORK_DIR}/combined-custom-no-rbac.yaml" combined-custom-no-rbac
render "${WORK_DIR}/split-custom-no-rbac.yaml" \
  --set deploymentMode=split \
  --set rbac.create=false \
  --set controller.workload.serviceAccount.create=false \
  --set controller.workload.serviceAccount.name=external-controller \
  --set webhook.workload.serviceAccount.create=false \
  --set webhook.workload.serviceAccount.name=external-webhook
assert_rbac_matrix "${WORK_DIR}/split-custom-no-rbac.yaml" split-custom-no-rbac

# 7. FullnameOverride test
render "${WORK_DIR}/fullname-override.yaml" --set fullnameOverride=custom-name
assert_fullname_override "${WORK_DIR}/fullname-override.yaml"

# 8. External CA test with synthetic Secret comparison
openssl req -x509 -newkey rsa:2048 -keyout /dev/null -out "${WORK_DIR}/ca.pem" -days 1 -nodes -subj "/CN=contract-test-ca" 2>/dev/null
render "${WORK_DIR}/combined-external-runtime-custom-service.yaml" \
  --set webhook.certAutoGenerate=false \
  --set webhook.service.name=selected-webhook \
  --set-file webhook.caBundle="${WORK_DIR}/ca.pem"
render "${WORK_DIR}/combined-external-helm-custom-service.yaml" \
  --set webhook.certAutoGenerate=false \
  --set webhook.createWebhookConfigurations=true \
  --set webhook.service.name=selected-webhook \
  --set-file webhook.caBundle="${WORK_DIR}/ca.pem"
assert_ownership_transition_contract \
  "${WORK_DIR}/combined-external-runtime-custom-service.yaml" \
  "${WORK_DIR}/combined-external-helm-custom-service.yaml" \
  selected-webhook
render "${WORK_DIR}/external-tls.yaml" \
  --set webhook.certAutoGenerate=false \
  --set webhook.createWebhookConfigurations=true \
  --set-file webhook.caBundle="${WORK_DIR}/ca.pem"
assert_external_ca_secret "${WORK_DIR}/external-tls.yaml" "${WORK_DIR}/ca.pem"
assert_rbac_matrix "${WORK_DIR}/external-tls.yaml" combined-external-helm
render "${WORK_DIR}/split-external-runtime.yaml" \
  --set deploymentMode=split \
  --set webhook.certAutoGenerate=false \
  --set-file webhook.caBundle="${WORK_DIR}/ca.pem"
assert_rbac_matrix "${WORK_DIR}/split-external-runtime.yaml" split-external-runtime
render "${WORK_DIR}/split-external-tls.yaml" \
  --set deploymentMode=split \
  --set webhook.certAutoGenerate=false \
  --set webhook.createWebhookConfigurations=true \
  --set-file webhook.caBundle="${WORK_DIR}/ca.pem"
assert_split_task_five_external "${WORK_DIR}/split-external-tls.yaml" "${WORK_DIR}/ca.pem"
assert_rbac_matrix "${WORK_DIR}/split-external-tls.yaml" split-external-helm
assert_ownership_transition_contract \
  "${WORK_DIR}/split-external-runtime.yaml" \
  "${WORK_DIR}/split-external-tls.yaml" \
  contract-echo-operator

# 9. PEM body validation tests
# Envelope-only PEM (no body) must fail
printf -- '-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n' > "${WORK_DIR}/envelope-only.pem"
expect_failure envelope-only-pem 'webhook.caBundle must be a literal PEM certificate' \
  --set webhook.certAutoGenerate=false --set-file webhook.caBundle="${WORK_DIR}/envelope-only.pem"
# Invalid base64 body must fail
printf -- '-----BEGIN CERTIFICATE-----\nnot-base64-or-der!\n-----END CERTIFICATE-----\n' > "${WORK_DIR}/invalid-base64.pem"
expect_failure invalid-base64-pem 'webhook.caBundle must be a literal PEM certificate' \
  --set webhook.certAutoGenerate=false --set-file webhook.caBundle="${WORK_DIR}/invalid-base64.pem"

# Valid base64 that decodes to non-certificate data must fail
printf -- '-----BEGIN CERTIFICATE-----\naGVsbG8=\n-----END CERTIFICATE-----\n' > "${WORK_DIR}/non-certificate.pem"
expect_failure non-certificate-pem 'webhook.caBundle must be a literal PEM certificate' \
  --set webhook.certAutoGenerate=false --set-file webhook.caBundle="${WORK_DIR}/non-certificate.pem"

# 10. Negative test cases with normalized diagnostics
cat >"${WORK_DIR}/reserved-label.yaml" <<'YAML'
deploymentMode: split
controller:
  workload:
    podLabels:
      app.kubernetes.io/name: override
YAML
cat >"${WORK_DIR}/missing-sa.yaml" <<'YAML'
deploymentMode: split
controller:
  workload:
    serviceAccount:
      create: false
      name: ""
YAML
cat >"${WORK_DIR}/colliding-sa.yaml" <<'YAML'
deploymentMode: split
controller:
  workload:
    serviceAccount:
      create: false
      name: shared
webhook:
  workload:
    serviceAccount:
      create: false
      name: shared
YAML

expect_failure unknown-mode 'deploymentMode must be "combined" or "split"' --set deploymentMode=dual
expect_failure split-without-webhook 'deploymentMode=split requires webhook.enabled=true' --set deploymentMode=split --set webhook.enabled=false
expect_failure disabled-webhook-configurations 'webhook.createWebhookConfigurations requires webhook.enabled=true' --set webhook.enabled=false --set webhook.createWebhookConfigurations=true
expect_failure auto-generated-helm-configurations 'webhook.createWebhookConfigurations requires webhook.certAutoGenerate=false' --set webhook.createWebhookConfigurations=true
expect_failure missing-external-secret 'webhook.tls.secretName is required when webhook.certAutoGenerate=false' --set webhook.certAutoGenerate=false --set webhook.tls.secretName=
expect_failure missing-external-ca 'webhook.caBundle is required when webhook.certAutoGenerate=false' --set webhook.certAutoGenerate=false --set webhook.caBundle=
expect_failure invalid-external-ca 'webhook.caBundle must be a literal PEM certificate' --set webhook.certAutoGenerate=false --set webhook.caBundle=not-a-pem
expect_failure reserved-selector-label 'controller.workload.podLabels may not override reserved selector label "app.kubernetes.io/name"' -f "${WORK_DIR}/reserved-label.yaml"
expect_failure missing-split-sa 'controller.workload.serviceAccount.name is required when create=false' -f "${WORK_DIR}/missing-sa.yaml"
expect_failure colliding-split-sa 'controller and webhook ServiceAccount names must differ in split mode' -f "${WORK_DIR}/colliding-sa.yaml"
expect_failure colliding-services 'webhook.service.name must not equal the split controller Service name' --set deploymentMode=split --set webhook.service.name=contract-echo-operator-controller
expect_failure overlong-service-name 'webhook.service.name must be at most 63 characters' --set webhook.service.name="$(python3 -c 'print("a" * 64)')"
expect_failure invalid-service-name 'webhook.service.name must be a valid DNS label' --set webhook.service.name=Invalid_Name

printf 'helm contract tests passed\n'
