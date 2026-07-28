# F4 scope fidelity and documentation audit

**Result: FAIL** — scope fidelity requirements are not met. This audit did not modify product code, Helm, operational scripts, or documentation.

## Baseline and scope enumeration

- `BASE`: `c7dc8e231074793ec350195716b8606b6bb4ae7d` (loaded from Task 1 evidence)
- Commands: `git diff --name-only "$BASE"`; `git ls-files --others --exclude-standard -z`
- Final tracked changed paths: 32
- Final untracked paths: 1,306
- Final combined unique changed paths: 1,338
- Final allowlist violations: 65

### Out-of-allowlist paths

- `.omo/boulder.json`
- `.omo/notepads/controller-webhook-split-deployment/{decisions,issues,learnings,problems}.md`
- 51 paths under `.omo/run-continuation/*.json`
- `.omo/start-work/ledger.jsonl`
- `example/echo-operator/scripts/build-image.sh`
- `operator/framework/README.md`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/admission/AdmissionHandler.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/conversion/ConversionHandler.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/registration/WebhookSelfRegistration.java`
- `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/webhook/WebhookServerTest.java`
- `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/webhook/admission/AdmissionHandlerTest.java`
- `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/webhook/conversion/ConversionHandlerTest.java`

All `.omo/evidence/controller-webhook-split-deployment/f4/**` paths produced by this audit are within the F4 allowlist.

## Pre-existing dirty-file integrity

`baseline/dirty-file-sha256.txt` was checked for pre-existing paths outside the permitted feature set.

- Unrelated baseline paths checked: 36
- Missing: 0
- Hash changes: 6 (**FAIL**)
  - `.omo/boulder.json`
  - `.omo/notepads/controller-webhook-split-deployment/decisions.md`
  - `.omo/notepads/controller-webhook-split-deployment/issues.md`
  - `.omo/notepads/controller-webhook-split-deployment/learnings.md`
  - `.omo/notepads/controller-webhook-split-deployment/problems.md`
  - `.omo/run-continuation/ses_05e7d477dffe3Cw9sbibw5Dyrx.json`

## Framework main-source exception

`git diff "$BASE" -- operator/framework/src/main` contains four Java files. `WebhookServer.java` contains the documented executor fix, but these additional files violate the required exception-only rule (**FAIL**):

- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/admission/AdmissionHandler.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/conversion/ConversionHandler.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/registration/WebhookSelfRegistration.java`

## Forbidden feature/API scan

Added lines were scanned in 21 product implementation paths (main code, chart manifests/values, and operational scripts). Documentation, test code, and `*-contract-test.sh` assertion text were excluded so their explicit exclusion assertions are not misclassified as implemented features.

- Tokens: `cert-manager`, CA rotation, HA/PDB/HPA, `OperatorMode`, second image/JAR, framework-level mode API
- Matches: 0 (**PASS**)

## Single artifact and entrypoint checks

- `EchoOperatorMain.java` files in the example: 1 (**PASS**)
- Example Dockerfiles: 1 (`example/echo-operator/Dockerfile`) (**PASS**)
- Example `docker build` script paths: 1 (`example/echo-operator/scripts/build-image.sh`) (**PASS**)
- Shade/exec `mainClass`: `com.example.echooperator.EchoOperatorMain` in both BASE and current POM (**PASS**)
- Newly added Java source files: 0; newly added `main` declarations: 0 (**PASS**)
- Changed or untracked JARs: 0 (**PASS**)

## Split render image consistency

Command: `helm template f4-scope example/echo-operator/helm/echo-operator --set deploymentMode=split`

- Deployments: `f4-scope-echo-operator-controller`, `f4-scope-echo-operator-webhook`
- Images: `example/echo-operator:latest`, `example/echo-operator:latest`
- Deployment images equal: **PASS**

## Documentation contract

Command: `example/echo-operator/scripts/docs-contract-test.sh`

- Result: **PASS** — 9 passed, 0 failed
- Raw command log: `.omo/evidence/controller-webhook-split-deployment/task-9/docs-check.log`

## Conclusion

The documentation and single-artifact checks pass, and no forbidden implementation tokens were found. F4 nevertheless fails because 65 changed/untracked paths are outside the strict allowlist, six unrelated pre-existing dirty-file hashes changed, and three framework-main files exceed the sole `WebhookServer.java` executor-fix exception.
