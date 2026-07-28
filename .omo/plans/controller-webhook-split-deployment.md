# controller-webhook-split-deployment - Work Plan

## TL;DR (For humans)

- **What you get:** the Echo Operator Helm chart supports `deploymentMode: combined|split`, defaults to `combined`, and uses the same image/main class in both modes. `split` produces isolated controller and webhook workloads, Services, identities, RBAC, health checks, and lifecycle ownership.
- **Why this approach:** the framework already exposes orthogonal controller/webhook components; adding capability switches in the example preserves that design and avoids a second executable or framework bootstrap abstraction.
- **Compatibility:** default combined resource names, selectors, webhook Service DNS identity, image, and existing shared workload values remain unchanged. Split mode is explicit and uses component-specific values.
- **What it will not do:** no cert-manager, CA rotation, PDB/HPA, second image/JAR, framework-level deployment mode, or webhook/controller deployment into different namespaces.
- **Effort / risk:** architecture-sized. Highest risks are lifecycle cleanup ownership, immutable selectors, certificate Service identity, and least-privilege RBAC; all are covered by TDD, manifest assertions, and two topology-specific cluster smoke runs.
- **Decisions:** combined is default; split uses distinct ServiceAccounts; both-on/controller-only/webhook-only are valid runtime combinations; all-off fails; split requires webhook enabled; default component replicas are one.

## Scope

### In scope

- Example runtime composition in `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java`:
  - add `CONTROLLER_ENABLED` / `controller.enabled` with default `true`;
  - add `WEBHOOK_REGISTRATION_CLEANUP_ENABLED` / `webhook.registration.cleanup.enabled` with default `true`;
  - add `WEBHOOK_SELF_REGISTRATION_ENABLED` / `webhook.self-registration.enabled` with default `true`;
  - support combined `(true,true)`, controller-only `(true,false)`, and webhook-only `(false,true)`; reject `(false,false)` with `At least one of CONTROLLER_ENABLED or WEBHOOK_ENABLED must be true`;
  - construct/start/stop only owned components and expose capability-appropriate readiness.
- Helm topology contract in `example/echo-operator/helm/echo-operator`:
  - `deploymentMode: combined|split`, default `combined`, any other value fails rendering;
  - combined keeps current names and selectors;
  - split creates `<fullname>-controller` and `<fullname>-webhook` Deployments, a `<fullname>-controller` metrics Service, and a webhook Service whose resolved name remains `webhook.service.name` or `<fullname>`;
  - split requires `webhook.enabled=true` and distinct resolved ServiceAccount names;
  - shared workload values remain the combined-mode contract; nested `controller.*` and `webhook.workload.*` configure split workloads independently.
- RBAC ownership:
  - combined preserves one identity but separates namespaced controller and webhook rules internally where namespaces differ;
  - split creates distinct controller/webhook identities and bindings;
  - controller gets reconcile, Events, and optional Lease permissions but no Secret/admission/CRD-patch permissions;
  - webhook gets named CA Secret, admission registration, and exact CRD patch permissions but no reconcile/Lease permissions.
- Admission registration ownership: Helm-managed admission configurations disable runtime admission self-registration; runtime conversion patching remains enabled only when required by auto-generated CA.
- English/Chinese documentation, Helm render assertions, Java tests, and topology-aware cluster smoke verification.

### Runtime capability and ownership truth table

| Topology | controller | webhook | cleanup | self-registration | Required behavior |
|---|---:|---:|---:|---:|---|
| combined default | true | true | `webhook.createWebhookConfigurations` | `!webhook.createWebhookConfigurations` | construct both paths; when Helm owns configs, cleanup stale runtime names before ready; readiness is controller-ready AND webhook-ready |
| combined controller-only | true | false | true | false | construct controller plus a lazy cleanup-only `WebhookSelfRegistration`; delete only runtime-owned stable registration names; CRD strategy is `None` and Helm-owned admission resources are absent |
| split controller Pod | true | false | false | false | no webhook serving/cert/registration/cleanup objects or permissions |
| split webhook Pod | false | true | `webhook.createWebhookConfigurations` | `!webhook.createWebhookConfigurations` | no Operator/EventRecorder/leader election; when Helm owns configs, cleanup stale runtime names before ready; own serving, certs, conversion config, and optional admission registration |

Only controller=false + webhook=false is invalid. Cleanup deletes only runtime names from `registrationBaseName(config)`; Helm-managed names (`<fullname>-validating`/`-mutating`) are never passed to runtime cleanup and disappear only through Helm reconciliation.

### Webhook/TLS validity matrix

| webhook.enabled | certAutoGenerate | createWebhookConfigurations | Contract |
|---:|---:|---:|---|
| false | either | false | combined controller-only only; no webhook Deployment/Service ports/configurations; CRD conversion `None`; split rejects this combination |
| false | either | true | invalid render |
| true | true | false | runtime CA Secret + per-Pod server cert + runtime admission registration and conversion patch |
| true | true | true | invalid render |
| true | false | false | external Secret must be nonblank and contain `ca.crt`,`tls.crt`,`tls.key`; nonblank `webhook.caBundle` is required for the Helm-rendered CRD conversion client; runtime reads mounted CA and self-registers admission configs |
| true | false | true | same external Secret plus nonblank `webhook.caBundle`; Helm owns admission configs and static conversion client config |

Auto-generated TLS supports multiple webhook replicas: all replicas race-safe read/create the same CA Secret (`409` loser re-reads it), then generate distinct local server keypairs signed by that shared CA. Tests must prove shared CA convergence; do not restrict replicas to one.

### Split values and reserved labels

- Global in both modes: `image.*`, `metrics.port`, `operator.*`, `leaderElection.*`, and functional `webhook.*` settings.
- Combined-only: existing top-level `replicas`, `resources`, `podAnnotations`, `podLabels`, `nodeSelector`, `tolerations`, `affinity`, and `serviceAccount`.
- Split-only: fully populated `controller.workload` and `webhook.workload` blocks, each with `replicas: 1`, the same default resource requests/limits as current combined, empty annotations/labels/scheduling values, and nested `serviceAccount.create/name`. Split fields do not inherit top-level workload fields.
- Reject component `podLabels` containing `app.kubernetes.io/name`, `app.kubernetes.io/instance`, or `app.kubernetes.io/component`; helpers own immutable selector labels.
- Component names reserve suffix space before truncation: `<base-truncated-to-52>-controller` and `<base-truncated-to-52>-webhook`. Reject a resolved custom webhook Service name equal to the controller Service name or longer than 63 characters. With `serviceAccount.create=false`, each split component name is required, nonblank, and distinct.

### Exact RBAC ownership matrix

| Owner / condition | Scope | API group | Resources / resourceNames | Verbs |
|---|---|---|---|---|
| controller | watched namespace | `example.com` | `echoresources` | get,list,watch,create,update,patch,delete |
| controller | watched namespace | `example.com` | `echoresources/status` | get,update,patch |
| controller | watched namespace | `example.com` | `echoresources/finalizers` | update |
| controller | watched namespace | core | pods,services | get,list,watch,create,update,patch,delete |
| controller | watched namespace | `apps` | deployments | get,list,watch,create,update,patch,delete |
| controller | watched namespace | core | events | get,create,patch |
| controller when leader election enabled | release namespace | `coordination.k8s.io` | leases | get,list,watch,create,update,patch,delete |
| webhook auto-generate | release namespace | core | secrets / named CA Secret for get; create cannot use `resourceNames` | get on named Secret; create on secrets |
| webhook runtime admission owner | cluster | `admissionregistration.k8s.io` | validating/mutating configs; create unscoped, subsequent verbs restricted to runtime stable names where supported | create plus get,update,patch,delete |
| webhook runtime owner transition barrier | cluster | `admissionregistration.k8s.io` | exact Helm predecessor names `<fullname>-validating`/`-mutating` | get |
| combined cleanup-only | cluster | `admissionregistration.k8s.io` | runtime stable validating/mutating names | get,delete |
| webhook Helm-owner migration cleanup | cluster | `admissionregistration.k8s.io` | runtime stable validating/mutating names only | get,delete |
| webhook auto-generated conversion | cluster | `apiextensions.k8s.io` | CRD / `echoresources.example.com` | get,update,patch |

Helm-owned static admission/conversion mode grants no CRD patch permission, but grants get/delete on stable runtime admission names solely for ownership-transition cleanup. A watched-namespace RoleBinding always names the ServiceAccount subject in the release namespace.

### Admission ownership transitions

- Runtime → Helm: Helm creates `<fullname>-validating`/`-mutating`; the restarted webhook process has self-registration=false and cleanup=true, deletes stable runtime-owned configurations before becoming Ready, and never deletes Helm names.
- Helm → runtime: Helm injects predecessor names through `WEBHOOK_PREDECESSOR_VALIDATING_NAME=<fullname>-validating` and `WEBHOOK_PREDECESSOR_MUTATING_NAME=<fullname>-mutating`. Before runtime registration, the webhook polls exact-name GETs once per second for at most 60 seconds and remains NotReady until both are absent; timeout fails startup. It then creates stable runtime-owned configurations before becoming Ready.
- Both directions are supported and must be covered by in-place cluster upgrade tests. Steady-state success requires exactly one validating and one mutating configuration for the release; any overlap after rollout fails verification.

### External TLS CA encoding contract

- `webhook.caBundle` is a literal PEM CA certificate string (not pre-base64-encoded).
- Helm applies `b64enc` exactly once to place those PEM bytes in CRD/admission `caBundle` fields.
- External Secret `data.ca.crt` is Kubernetes base64 of the same PEM bytes; `data.tls.crt` and `data.tls.key` hold the matching serving pair.
- Contract and cluster tests decode CRD/admission `caBundle` and Secret `data.ca.crt` and require byte equality in both runtime-owned and Helm-owned admission modes. A mismatched CA case must make an API-server webhook request fail under `failurePolicy: Fail` and must never be reported as successful verification.

### Out of scope / Must-NOT-Have

- No changes to public framework deployment/bootstrap APIs and no `OperatorMode` abstraction.
- No second main class, image, JAR, or duplicated controller/webhook business implementation.
- No cert-manager, automatic CA rotation, HPA, PDB, topology spread, or default replica increase.
- No cross-namespace placement of controller and webhook Pods. Watching `operator.namespace` different from the release namespace remains supported by rendering controller Role/RoleBinding in the watched namespace.
- No unrelated cleanup/refactor of `Operator`, certificate generation, webhook handlers, or chart style.

## Verification strategy

- **TDD:** each behavior todo begins with a failing JUnit or Helm contract assertion, then adds the minimum implementation to pass it.
- **Java gates:** `mvn -f operator/framework/pom.xml install -DskipTests` followed by `mvn -f example/echo-operator/pom.xml test`; no relevant skipped tests.
- **Helm gates:** `helm lint`, default combined render, explicit split render, external-TLS renders, watched-namespace renders, and negative renders for invalid mode/configuration. Use a deterministic shell/Python manifest assertion script; grep-only evidence is insufficient.
- **Cluster gates:** run combined and split releases in isolated namespaces. Absence of a reachable cluster is a failure for this verification wave, not a successful skip.
- **Evidence:** save command output and rendered manifests under `.omo/evidence/controller-webhook-split-deployment/<task-or-topology>/`.

## Execution strategy

### Waves and dependencies

| Wave | Tasks | Dependency |
|---|---|---|
| 1 | 1 | first action freezes the immutable implementation baseline before any edit |
| 2 | 2, 3 | both depend on baseline/config contract from 1 and may proceed in parallel |
| 3 | 4 | depends on 3 |
| 4 | 5, 6 | both depend on 3-4 and may proceed in parallel |
| 5 | 7 | depends on 1-6 because registration targets and ownership span runtime, Services, and RBAC |
| 6 | 8, 9 | 8 depends on 2 and 4-7; 9 depends on stable behavior from 1-7 |

- Keep commits task-scoped and do not mix adjacent cleanup.
- For each TDD todo, preserve RED output, GREEN output, and relevant rendered/runtime evidence.
- Task 1's first action records `BASE=$(git rev-parse HEAD)`, `git status --porcelain=v1 -z`, and SHA-256 for every pre-existing dirty file. If any pre-existing dirty path overlaps the Task 1-9 implementation allowlist, stop as BLOCKED rather than overwrite it. Archive the baseline chart directly from `git archive "$BASE" -- example/echo-operator/helm/echo-operator`. Every later diff, allowlist, upgrade, and evidence check uses that same immutable baseline record; unrelated pre-existing dirty paths are protected and excluded only when their hashes remain unchanged.

## Todos

- [x] 1. Define and test the runtime capability/configuration contract

  **Implement:** Before any edit, execute the immutable baseline procedure in Execution strategy and persist it under task evidence. Then extend `EchoOperatorMain.OperatorConfig` and `loadConfig(Map, Properties)` with `controllerEnabled`, `webhookRegistrationCleanupEnabled`, `webhookSelfRegistrationEnabled`, and optional predecessor validating/mutating names. Capability defaults are true; predecessor names default absent. Preserve environment-over-property precedence and strict boolean parsing. Validate after parsing that controller/webhook are not both disabled, using the exact error `At least one of CONTROLLER_ENABLED or WEBHOOK_ENABLED must be true`. Do not infer Helm topology inside Java: Java receives explicit capability/ownership/predecessor values.

  **References:**
  - `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java:325-456,498-513` — current parsing and config record conventions.
  - `example/echo-operator/src/test/java/com/example/echooperator/EchoOperatorMainConfigTest.java:13-128` — exact assertion/error style.
  - `.omo/drafts/controller-webhook-split-deployment.md` — approved capability matrix and cleanup ownership decision.

  **Acceptance criteria:**
  - RED tests are added first for defaults, environment/property parsing, precedence, invalid booleans, three valid capability combinations, and all-off failure.
  - Defaults resolve to controller=true, webhook=true, cleanup=true, self-registration=true.
  - `.omo/evidence/controller-webhook-split-deployment/baseline/` contains BASE, NUL-safe initial status, dirty-file hashes, and chart archive from exactly BASE before any implementation diff; overlapping pre-existing dirty files block work.
  - `(true,true)`, `(true,false)`, and `(false,true)` load; `(false,false)` throws the exact agreed error.
  - Existing webhook configuration tests remain unchanged except constructor fixture updates required by new record fields.

  **QA:**
  - Happy: run `mvn -f operator/framework/pom.xml install -DskipTests && mvn -f example/echo-operator/pom.xml -Dtest=EchoOperatorMainConfigTest test`; save output to `.omo/evidence/controller-webhook-split-deployment/task-1/config-tests.log`.
  - Failure: run the all-off and invalid-boolean parameterized cases and assert the exact messages; test failure or a permissive fallback fails the task.

  **Commit:** `feat(example): define controller and webhook capabilities`

- [x] 2. Compose controller-only, webhook-only, and combined lifecycles with capability-specific readiness

  **Implement:** Refactor only `EchoOperatorMain` composition according to the truth table in Scope. Controller-owned objects (`Operator`, controller registration, `EventRecorder`) are absent when controller is disabled; webhook serving objects are absent when webhook is disabled. Combined controller-only creates only a lazy cleanup helper when cleanup=true; split controller creates none. Keep one Kubernetes client and an atomic once-only stop guard. `LeaderElectionManager` remains controller-only; do not invent a close API. Track example-local `webhookReady`: false initially; for Helm→runtime, poll exact predecessor names once per second up to 60 seconds and keep false until both are absent; then perform TLS start plus every required cleanup/conversion patch/admission registration; set true only after all selected-row operations succeed. Reset false on failure/stop. Combined readiness is `controllerReady && webhookReady`; controller-only is `controllerReady`; webhook-only is `webhookReady`.

  **References:**
  - `EchoOperatorMain.java:113-186` — unconditional current construction/registration.
  - `EchoOperatorMain.java:228-323` — startup, cleanup, readiness, and shutdown ordering.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/Operator.java:143-190` — operator/client ownership.
  - `example/echo-operator/src/test/java/com/example/echooperator/EchoOperatorMainWiringTest.java:53-207` — existing construction mocking and HTTP health assertions.

  **Acceptance criteria:**
  - RED wiring tests precede implementation and cover all three valid modes.
  - Controller-only constructs/registers Operator and EventRecorder but no webhook cert/server/handlers/self-registration; cleanup is invoked only when its explicit flag is true.
  - Webhook-only constructs cert/server/handlers and required registration components but no Operator, reconciler, EventRecorder, or leader election.
  - Combined constructs and starts both paths in current order.
  - Controller readiness is false before informer sync and true after sync; webhook readiness stays false until TLS start and all enabled post-start patch/registration operations complete; combined requires both checks.
  - Failed TLS start, conversion patch, or admission registration leaves webhook readiness false, stops an already-started server, and closes each owned recorder/operator/client at most once.
  - Predecessor-name polling uses exact GETs, stays NotReady while either Helm-owned object exists, and times out after 60 attempts with an actionable startup failure; no broad list/watch permission is added.
  - `stop()` may be called twice in every mode without exception or duplicate client close; tests cover pre-start, partial-start, normal running, and leader-election-enabled controller paths.
  - Split-controller fixture (`controller=true`, `webhook=false`, cleanup=false) never calls `unregisterAdmissionWebhooks`.

  **QA:**
  - Happy: run `mvn -f example/echo-operator/pom.xml -Dtest=EchoOperatorMainWiringTest,EchoOperatorMainTest test`; save `.omo/evidence/controller-webhook-split-deployment/task-2/lifecycle-tests.log`.
  - Failure: inject webhook start failure and assert readiness never becomes true and cleanup/close still releases already-created resources; assert webhook-only never constructs `LeaderElectionManager` even with a stray enabled leader-election config.

  **Commit:** `feat(example): isolate controller and webhook lifecycles`

- [x] 3. Freeze the Helm topology, naming, values inheritance, and validation contract

  **Implement:** Add `deploymentMode: combined` and the exact split-only blocks defined in Scope. Add direct helper functions for mode validation, effective watched namespace, suffix-safe component names, component selectors, webhook Service name, and component ServiceAccount names. Preserve current top-level workload/SA values for combined and explicitly ignore them in split. Reject: unknown mode; split with `webhook.enabled=false`; webhook disabled with Helm-created admission configs; unsupported TLS/registration matrix rows; missing/blank external Secret name; missing CA bundle for any external-TLS conversion render; reserved selector-label overrides; split SA names missing under create=false or resolving equal; webhook Service/controller Service collision; and invalid/overlong resolved custom Service names. Do not add a generic workload factory.

  **References:**
  - `example/echo-operator/helm/echo-operator/values.yaml:4-79` — current public values.
  - `example/echo-operator/helm/echo-operator/templates/_helpers.tpl:4-60` — naming/selector/SA conventions.
  - `example/echo-operator/helm/echo-operator/templates/validatingwebhookconfiguration.yaml:1-2` and matching mutating template — existing fail-fast style.

  **Acceptance criteria:**
  - Add an executable `example/echo-operator/scripts/helm-contract-test.sh` (or equivalently focused test script) that captures YAML and asserts parsed resource fields, not just string presence.
  - Consume the immutable BASE/chart archive produced by Task 1 for the selector contract and live upgrade test; do not regenerate or commit a duplicate legacy chart.
  - Default render validates and contains the same combined Deployment, Service, selector, and ServiceAccount names as before.
  - Split render resolves suffix-safe distinct controller/webhook workload and ServiceAccount names while webhook Service defaults to `<fullname>`; every split workload field equals its nested value and never silently inherits a top-level workload field.
  - Every invalid combination exits nonzero with a specific actionable message.
  - External TLS accepts literal PEM in `webhook.caBundle`, base64-encodes it exactly once, and test fixtures prove decoded CRD/admission bytes equal decoded external Secret `ca.crt` bytes.
  - `fullnameOverride` and explicit `webhook.service.name` remain authoritative and stay within Kubernetes name length limits; custom labels cannot override selector keys and selector/template labels are exactly equal.

  **QA:**
  - Happy: run `helm lint example/echo-operator/helm/echo-operator` and the contract script for default and `--set deploymentMode=split`; save manifests under `.omo/evidence/controller-webhook-split-deployment/task-3/`.
  - Failure: render `deploymentMode=dual`, split+webhook disabled, and colliding split SA names; each must fail and the script must assert its exact diagnostic.

  **Commit:** `feat(helm): define combined and split topology contract`

- [x] 4. Preserve the default combined workload while wiring explicit ownership flags

  **Implement:** Keep the current combined Deployment name and immutable selector labels. Keep the current combined Service name/selector and metrics+webhook port shape. Inject `CONTROLLER_ENABLED=true`; map `WEBHOOK_ENABLED` from values; set cleanup=true for combined controller-only and for webhook-enabled Helm-owned migration cleanup; set self-registration to `webhook.enabled && !webhook.createWebhookConfigurations`. Preserve current shared values, probes, security contexts, ports, cert volumes, leader-election env, and webhook Service DNS behavior. When webhook is disabled, fail if Helm-created admission configs are requested, render CRD conversion `None`, and render no webhook ports/configs. Cleanup targets only runtime stable names; Helm-owned names remain Helm-only. Auto-generated conversion uses runtime patch; every external-TLS mode requires `webhook.caBundle` and renders static CRD CA/client config.

  **References:**
  - `templates/deployment.yaml:1-151` — current combined workload contract.
  - `templates/service.yaml:1-22` — current combined Service identity and selector.
  - `templates/crd.yaml:21-36` — conversion target.
  - `EchoOperatorMain.java:228-252` — current runtime registration and cleanup.

  **Acceptance criteria:**
  - Default render has exactly one Deployment and one Service with the existing names and selectors.
  - Combined Pod env resolves controller=true and requested webhook flag; disabling webhook retains cleanup only for runtime stable names and cannot render Helm-owned admission objects.
  - `createWebhookConfigurations=true` with external TLS sets self-registration=false and renders exactly one validating and one mutating configuration, not duplicate runtime-owned objects.
  - Existing shared workload customizations still appear in the combined Pod spec.

  **QA:**
  - Happy: contract script compares default combined resource names/selectors/env/ports/volumes to checked expectations and saves `.omo/evidence/controller-webhook-split-deployment/task-4/combined.yaml`.
  - Failure: external-TLS Helm-managed registration render must fail if auto-generation is enabled or CA bundle is absent; valid external-TLS render must point admission and conversion clients at the combined webhook Service.

  **Commit:** `feat(helm): preserve combined deployment mode`

- [x] 5. Add isolated split Deployments and Services

  **Implement:** In split mode render suffix-safe controller/webhook Deployment names from Scope using the same image. Add reserved component labels and reject collisions. Controller env: controller=true, webhook=false, cleanup=false; leader election only here. Webhook env: controller=false, webhook=true; cleanup equals `webhook.createWebhookConfigurations`; self-registration is its inverse; when self-registration is true, always inject exact Helm predecessor names `<fullname>-validating`/`-mutating` for the absence barrier. Expose TLS plus metrics/health, mount only selected generated/external certificate storage, and never inject leader election. Render isolated Services, reject name collision, and use one webhook Service identity everywhere. Permit multiple auto-generated replicas via the existing shared-CA Secret 409 convergence protocol and per-Pod local server certs.

  **References:**
  - `templates/deployment.yaml:40-139` — existing env/ports/probes/cert mounts.
  - `templates/service.yaml:10-22` — current port/selector coupling.
  - `templates/crd.yaml:27-32`, validating/mutating webhook templates — every webhook Service reference.
  - `EchoOperatorMain.java:189-205` — Service name/namespace used for certificate SANs.

  **Acceptance criteria:**
  - Split render contains exactly two Deployments and two Services with mutually exclusive selectors/endpoints.
  - Both Deployments use byte-identical image repository/tag/pull policy.
  - Controller has no webhook port, certificate env, volume, or mount; webhook has no leader-election env.
  - Generated and external TLS variants both render valid webhook mounts, and neither changes controller Pod spec.
  - A two-replica auto-generated webhook test proves both resolvers converge on byte-identical CA data while producing valid local server certs; no replica-one validation is introduced.
  - Controller and webhook resources independently honor their nested replicas/resources/annotations/labels/scheduling values.
  - Webhook Service name agrees across Service metadata, Pod env, CRD conversion, Helm-managed admission configs, and runtime certificate identity.

  **QA:**
  - Happy: run contract script against split auto-generated and external-TLS values; save parsed assertions and manifests to `.omo/evidence/controller-webhook-split-deployment/task-5/`.
  - Failure: programmatically prove each Service selector matches only its intended Pod template and that sending webhook traffic to the controller Service is impossible by manifest construction.

  **Commit:** `feat(helm): add split controller and webhook workloads`

- [x] 6. Split ServiceAccounts and least-privilege RBAC by workload

  **Implement:** Render the exact API-group/resource/verb/namespace matrix from Scope. Preserve one combined SA by default; split renders distinct component SAs/bindings. Controller Role is in effective watched namespace; leader-election Lease rights are in the lock namespace. Webhook Secret Role exists only for auto-generation. Runtime admission owner gets unscoped create plus stable-name-constrained mutation verbs and exact-name GET on Helm predecessor names for the absence barrier. Cleanup-only get/delete exists for combined controller-only and webhook Helm-owner migration, restricted to runtime stable names. CRD get/update/patch exists only with auto-generated conversion and is constrained to `echoresources.example.com`; external-TLS static conversion has no CRD patch rights. Every watched-namespace binding names the release-namespace SA subject.

  **References:**
  - `templates/role.yaml:1-39` — currently mixes controller and Secret permissions and is incorrectly webhook-gated.
  - `templates/clusterrole.yaml:1-22` — current registration/cleanup/CRD rules.
  - `templates/serviceaccount.yaml`, `rolebinding.yaml`, `clusterrolebinding.yaml` — current single identity.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/event/EventRecorder.java` — controller Event API dependency.

  **Acceptance criteria:**
  - Split render has different resolved SA names and every binding subject/ref points to the intended object.
  - Controller rules exactly match the matrix; split controller excludes Secrets, admission configurations, and CRD patch. Lease rights render only when enabled and in the lock namespace.
  - Webhook rules exactly match the selected TLS/registration/conversion row and exclude EchoResource mutation, managed workload CRUD, Events, and Leases.
  - `operator.namespace != Release.Namespace` renders controller Role/Binding in the watched namespace with the SA subject namespace set to the release namespace.
  - `rbac.create=false` and `serviceAccount.create=false` render no managed RBAC/SAs while still using explicit supplied names; split rejects name collisions.

  **QA:**
  - Happy: parse combined/split/watched-namespace renders with the Helm contract script; save an exact permission matrix to `.omo/evidence/controller-webhook-split-deployment/task-6/rbac-matrix.txt`.
  - Failure: assertions fail if controller can access the CA Secret/admission/CRD patch or webhook can mutate EchoResources/Leases; include exact namespace/resource-name negative `kubectl auth can-i` checks in Task 8.

  **Commit:** `feat(helm): isolate controller and webhook RBAC`

- [x] 7. Align admission/conversion registration ownership across both topologies

  **Implement:** Apply the runtime truth table, TLS/encoding matrix, and ownership-transition protocol exactly. Self-registration=false serves handlers but synchronously deletes stable runtime names when cleanup=true before Ready. Self-registration=true first executes the exact-name 60-second predecessor absence barrier, then registers runtime names. Runtime conversion patch occurs only for auto-generated CA. Every external-TLS mode requires literal-PEM `webhook.caBundle` for static CRD conversion while runtime admission registration reads the mounted same-CA file. Combined controller-only lazily constructs cleanup only when enabled; split controller constructs none. Helm-owned names are never delete targets. Make every Service/cert/CRD/admission target resolve from one webhook Service identity.

  **References:**
  - `EchoOperatorMain.java:144-160,207-209,228-278` — registration config, stable base name, patch and cleanup.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/registration/WebhookSelfRegistration.java` — runtime ownership operations.
  - `templates/crd.yaml`, `validatingwebhookconfiguration.yaml`, `mutatingwebhookconfiguration.yaml` — Helm ownership and targets.
  - `EchoOperatorMainWiringTest.java:119-193` — service identity assertions.

  **Acceptance criteria:**
  - RED wiring tests prove self-registration on/off behavior and no cleanup under split controller.
  - Runtime-owned mode creates one stable validating and mutating registration targeting the selected webhook Service.
  - Helm-owned mode renders one of each and runtime does not create or delete them.
  - Auto-generated CA patches conversion clientConfig after webhook server startup; external TLS does not perform an unnecessary runtime patch.
  - Disabled webhook rejects Helm-owned configs, renders CRD conversion None, and performs only the explicitly enabled combined cleanup path.
  - Changing `webhook.service.name` updates Service metadata, cert SAN inputs, CRD, admission configs, and runtime registration consistently.
  - In-place runtime→Helm and Helm→runtime upgrade tests converge to exactly one validating and one mutating configuration after rollout, with no steady-state overlap; failed migration cleanup keeps webhook readiness false.
  - Helm→runtime test holds predecessor objects present and proves `/readyz` remains 503 and runtime objects absent, then removes predecessors and proves registration plus readiness; timeout path fails startup.

  **QA:**
  - Happy: run focused config/wiring/registration tests plus Helm contract cases; store `.omo/evidence/controller-webhook-split-deployment/task-7/ownership-tests.log`.
  - Failure: a test fixture with Helm ownership must fail if `WebhookSelfRegistration.register` or cleanup is called; manifest assertions must fail on any Service-name mismatch.

  **Commit:** `fix(webhook): make registration ownership explicit`

- [x] 8. Make deployment and cluster smoke verification topology-aware and non-skippable

  **Implement:** Update deploy/undeploy/smoke scripts for topology-aware, non-skippable verification and one cleanup trap. Stop applying raw Helm templates. Add admission denial/mutation, both-version conversion, reconciliation/status/cleanup, both split readiness endpoints, selector isolation, and identity-correct authorization. Add external-TLS fixtures for runtime-owned and Helm-owned admission: create Secret from PEM CA/serving pair, pass the same literal PEM through `webhook.caBundle`, and compare decoded Secret/CRD/admission CA bytes. Add mismatched-CA case and require API-server webhook failure under `failurePolicy: Fail`. Run exact `kubectl auth can-i --as=...` checks and fail if impersonation is unavailable. Use isolated namespaces/releases. Add live baseline combined upgrade and both ownership-transition upgrades, including a held-predecessor barrier check that remains NotReady until predecessor deletion.

  **References:**
  - `scripts/smoke-test.sh:12,61-110,128-209` — current false-success skip, raw template apply, single-resource assumptions, and replaced traps.
  - `scripts/deploy.sh`, `scripts/undeploy.sh` — release lifecycle entrypoints.
  - `examples/echo-cr.yaml` and CRD versions — reconciliation/conversion fixtures.

  **Acceptance criteria:**
  - One command runs combined smoke and one runs split smoke, with explicit release/namespace isolation.
  - Each run proves rollout, health/readiness HTTP 200, invalid admission denial, mutation, both-version conversion, reconciliation/status, and deletion cleanup.
  - Split additionally proves each Service endpoint targets only the intended Pods and authorization denials for cross-capability permissions.
  - Baseline-combined to new-default-combined in-place Helm upgrade succeeds without immutable-selector replacement/failure and preserves webhook Service identity.
  - Separate in-place runtime→Helm and Helm→runtime ownership transitions each finish with exactly one release validating configuration and one mutating configuration and a Ready webhook Pod.
  - Runtime-owned and Helm-owned external-TLS cases prove byte-equal decoded CA across Secret, CRD, and admission configs; mismatched CA causes the expected webhook call failure and cannot pass the suite.
  - Cluster unavailability, missing endpoint, failed admission/conversion, unexpected permission, or skipped assertion returns nonzero.
  - Evidence is written separately under `.omo/evidence/controller-webhook-split-deployment/combined/` and `/split/`.

  **QA:**
  - Happy: execute `DEPLOYMENT_MODE=combined .../smoke-test.sh` and `DEPLOYMENT_MODE=split .../smoke-test.sh`; retain full logs and queried resources.
  - Failure: run the script against an invalid kube context and assert nonzero prerequisite failure; temporarily request a forbidden `kubectl auth can-i` capability and assert the test catches any unexpected allow.

  **Commit:** `test(example): verify combined and split deployments`

- [x] 9. Document the deployment modes and operational ownership in both languages

  **Implement:** Update `example/echo-operator/README.md`, `README.zh-CN.md`, and only directly affected developer guide sections. Document default combined mode, split opt-in command/values, exact resource names, nested workload settings, identity/RBAC ownership, Service/DNS contract, certificate modes, runtime env flags, registration ownership, watched namespace behavior, verification commands, and exclusions. Clearly state that `webhook.enabled=false` is valid only in combined/controller-only mode; split requires webhook enabled. Add `example/echo-operator/scripts/docs-contract-test.sh` to compare documented mode/value/env names against `values.yaml` and `EchoOperatorMain` constants and require both languages to state the same default.

  **References:**
  - `example/echo-operator/README.md` — current deploy, env, and webhook value contract.
  - `example/echo-operator/README.zh-CN.md` — Chinese counterpart that must remain aligned.
  - `docs/dev-guide.md`, `docs/dev-guide.zh-CN.md` — framework usage narrative.
  - Final `values.yaml` and `scripts/smoke-test.sh` — source of truth for commands/options.

  **Acceptance criteria:**
  - English and Chinese docs describe the same defaults, valid modes, flags, and resource ownership.
  - Every documented command is executable and matches final option names.
  - Docs do not promise excluded HA/cert-manager/rotation features or framework-level mode APIs.

  **QA:**
  - Happy: run documented Helm lint/template and smoke command syntax checks; save `.omo/evidence/controller-webhook-split-deployment/task-9/docs-check.log`.
  - Failure: `example/echo-operator/scripts/docs-contract-test.sh` fails on stale option names or contradictory combined/split defaults.

  **Commit:** `docs(example): describe combined and split deployment modes`

## Final verification wave

- [x] F1. Plan compliance audit

  Run `mvn -f operator/framework/pom.xml clean install` so the exact tested framework artifact is installed, then `mvn -f example/echo-operator/pom.xml clean test`, `helm lint example/echo-operator/helm/echo-operator`, and `example/echo-operator/scripts/helm-contract-test.sh`. Then run a Python evidence-ledger check that enumerates Tasks 1-9, requires every declared `.omo/evidence/controller-webhook-split-deployment/...` artifact to exist and be nonempty, and fails on `SKIP`, `NOT RUN`, or missing acceptance markers. Expected: all commands exit 0, every task has happy+failure evidence, default render is combined, split is opt-in, and the capability/ownership truth tables match emitted env/resources.

- [x] F2. Code and chart quality review

  Run full framework/example Maven tests, Helm lint, and all render-contract cases. Inspect lifecycle nullability/idempotence, immutable selector preservation, values duplication, helper readability, least-privilege rules, and shell cleanup/error handling. Require zero test failures and no misleading skip-success output.

- [x] F3. Real combined and split cluster QA

  Execute both smoke modes against a reachable cluster in isolated namespaces plus the baseline-to-new combined upgrade case. Independently inspect Deployment rollouts, EndpointSlices, both split readiness endpoints, webhook configurations, CRD conversion clientConfig, reconciliation/status/cleanup, and identity-correct `kubectl auth can-i --as=system:serviceaccount:<release-namespace>:<resolved-sa>` denials with exact target namespaces/resource names. Save raw evidence; absent cluster or unavailable impersonation is a failed verification, not a pass.

- [x] F4. Scope fidelity and documentation audit

  Load `BASE` from Task 1. Combine tracked changes from `git diff --name-only "$BASE"` with NUL-safe untracked paths from `git ls-files --others --exclude-standard -z`. Permit only: `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java`; `example/echo-operator/src/test/java/com/example/echooperator/{EchoOperatorMainConfigTest.java,EchoOperatorMainWiringTest.java,EchoOperatorMainTest.java}`; `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateSecretManagerTest.java`; `example/echo-operator/helm/echo-operator/{values.yaml,templates/_helpers.tpl,templates/*.yaml}`; `example/echo-operator/scripts/{deploy.sh,undeploy.sh,smoke-test.sh,helm-contract-test.sh,docs-contract-test.sh}`; `example/echo-operator/{README.md,README.zh-CN.md}`; `docs/{dev-guide.md,dev-guide.zh-CN.md}`; and `.omo/{plans/controller-webhook-split-deployment.md,drafts/controller-webhook-split-deployment.md,evidence/controller-webhook-split-deployment/**}`. Fail on every other new/changed path; separately verify unrelated pre-existing dirty-file hashes are unchanged. Require `git diff "$BASE" -- operator/framework/src/main` empty. Scan added lines only in allowed product code/manifests/scripts (exclude docs and `.omo`) for forbidden feature/API tokens. Structurally assert exactly one `EchoOperatorMain.java`, one example Dockerfile/image build path, unchanged shade `mainClass`, no new executable JAR/main class, and split rendered Deployment images equal. Run `docs-contract-test.sh`. Expected: no hidden/untracked scope, no new framework API/excluded feature, one main/image/JAR, and exact bilingual behavior docs.

## Commit strategy

- Prefer one commit per numbered todo using the listed message; Tasks 1-2 and 3-7 may be squashed only if test-first history remains understandable.
- Never combine generated evidence or unrelated formatting with product changes.
- Before each commit, run that task's focused QA; before handoff, run the complete Maven/Helm/cluster gates.

## Success criteria

- Default `helm template` produces one combined Deployment/Service with existing names/selectors and both runtime capabilities enabled.
- `deploymentMode=split` produces isolated controller/webhook Deployments, Services, ServiceAccounts, and least-privilege RBAC while reusing one image/main.
- Java supports all three valid capability combinations and rejects all-off; shutdown/readiness work without absent-component errors.
- Split controller cannot delete/register/patch webhook resources or access webhook Secrets; split webhook cannot reconcile EchoResources or use leader-election Leases.
- Admission validation/mutation, conversion, reconciliation, metrics/health, and cleanup pass in both topologies on a real cluster.
- Existing combined release upgrades in place to the new default combined chart without immutable-selector failure or webhook Service identity drift.
- Identity-correct authorization checks use resolved release-namespace ServiceAccounts and exact target namespaces/resource names; inability to impersonate fails verification.
- Helm-managed and runtime-managed admission registrations do not overlap after rollout reaches Ready.
- Runtime↔Helm admission ownership upgrades converge to exactly one validating and one mutating configuration before webhook readiness succeeds.
- Maven tests, Helm lint/render contracts, both cluster smoke suites, and F1-F4 all pass with non-skipped evidence.
