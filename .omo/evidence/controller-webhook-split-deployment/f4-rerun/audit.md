# F4 rerun — scope fidelity and documentation audit

**Result: PASS** using the corrected allowlist and Task 1 preservation baseline.

## Basis and corrected allowlist

- `BASE`: `c7dc8e231074793ec350195716b8606b6bb4ae7d`, loaded from `baseline/BASE`.
- The original F4 contract is plan lines 365–367.
- In addition to the original task paths, this rerun permits workflow-generated state:
  `.omo/boulder.json`, `.omo/notepads/controller-webhook-split-deployment/**`,
  `.omo/run-continuation/**`, and `.omo/start-work/ledger.jsonl`.
- It permits Task 8's `example/echo-operator/scripts/build-image.sh` (`IMAGE_TAG`
  support), `WebhookServer.java`, and `WebhookServerTest.java`.
- It excludes the preserved endpoint-toggle unit from feature attribution:
  `operator/framework/README.md`, `AdmissionHandler.java`, `ConversionHandler.java`,
  `WebhookSelfRegistration.java`, `AdmissionHandlerTest.java`, and
  `ConversionHandlerTest.java`.

## Scope and Task 1 dirty baseline

The NUL-safe Python audit combined `git diff --name-only -z "$BASE"` and
`git ls-files --others --exclude-standard -z`, then matched every path against
the corrected allowlist.

- Final enumeration is recorded by the post-evidence check below.
- Before writing this evidence, the audit found 32 tracked changes, 1,336
  untracked paths, 1,368 combined paths, and **0 allowlist violations**.
- 30 workflow-generated baseline hash entries were intentionally excluded.
- All 6 endpoint-toggle preserved-unit hashes match Task 1 exactly.
- There are no remaining unrelated, non-workflow pre-existing dirty baseline
  paths to compare (`0`); therefore none changed.

## Framework-main attribution

Raw command: `git diff --name-only "$BASE" -- operator/framework/src/main`

```text
WebhookServer.java
admission/AdmissionHandler.java
conversion/ConversionHandler.java
registration/WebhookSelfRegistration.java
```

The latter three paths are the endpoint-toggle preserved unit: their Task 1
SHA-256 values are unchanged. Removing only those preserved paths leaves exactly:

```text
operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/WebhookServer.java
```

This is the Task 8 executor and idempotent-stop fix; no other new framework-main
delta is attributed to this task.

## Forbidden product token scan

A Python scan examined 1,905 added lines in 17 allowed product implementation,
manifest, and operational-script files. It excluded documentation, tests,
contract-test assertions, `.omo`, and the preserved unit. The token families
were `cert-manager`, CA rotation, HA/PDB/HPA, `OperatorMode`, second image/JAR,
and framework deployment-mode API.

**Result: 0 matches.**

## Entrypoint and image artifacts

- `EchoOperatorMain.java` files: 1
- Dockerfiles under the example: 1 (`example/echo-operator/Dockerfile`)
- `docker build` paths: 1 (`example/echo-operator/scripts/build-image.sh`)
- Shade `mainClass` is unchanged from `BASE`:
  `com.example.echooperator.EchoOperatorMain`
- Java main declarations: 1 (the existing `EchoOperatorMain`)
- Newly added Java sources: 0; changed or untracked JARs: 0

## Split render and documentation contract

`helm template f4-rerun example/echo-operator/helm/echo-operator --set deploymentMode=split`
rendered exactly two Deployments:

```text
f4-rerun-echo-operator-controller: example/echo-operator:latest
f4-rerun-echo-operator-webhook:    example/echo-operator:latest
```

The image values are equal.

`example/echo-operator/scripts/docs-contract-test.sh` passed: **9 passed,
0 failed** (including Helm lint).

## Final scope recheck

Pending after this evidence file is added; the result is appended in
`final-scope.md`.
