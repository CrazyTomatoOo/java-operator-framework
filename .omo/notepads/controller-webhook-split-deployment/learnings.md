# Learnings — controller-webhook-split-deployment

Conventions, patterns, and successful approaches discovered during work on this plan.

_Auto-scaffolded by /start-work. Append new entries below - never overwrite._

---

- 2026-07-27: Task 1 baseline captured at `c7dc8e231074793ec350195716b8606b6bb4ae7d`; `initial-status.porcelain-v1.z`, NUL-safe SHA-256 inventory, and the BASE chart archive are retained under `baseline/`.

- 2026-07-27: Task 1 integrates with the pre-existing endpoint-toggle record by appending capability/ownership fields and retaining its prior full constructor as a delegating compatibility constructor. This preserves existing endpoint-toggle fixtures while `loadConfig(Map, Properties)` owns explicit controller, cleanup, self-registration, and predecessor parsing only; lifecycle consumption remains for later tasks.

- 2026-07-27: Task 3 `fullnameOverride` contract test verifies that setting `fullnameOverride=custom-name` changes Deployment/Service/ServiceAccount names while selector labels remain anchored to the chart name, not the fullname override. This proves `fullnameOverride` is authoritative for resource names but does not leak into immutable selectors.

- 2026-07-27: Task 3 compares the archived BASE chart with the live default render through parsed YAML: the combined Deployment, Service, selectors, and ServiceAccount remain unchanged. The contract script also requires explicit, non-inheriting split workload blocks and decodes all external-TLS CRD/admission CA bundles back to the supplied literal PEM bytes.

- 2026-07-27: Task 3 fix: split mode now renders two suffix-safe Deployments, two Services (controller metrics + webhook), and two ServiceAccounts. Top-level workload values are explicitly ignored in split mode — nested controller/webhook values are authoritative. PEM body validation rejects envelope-only input. External CA test builds synthetic Secret with data.ca.crt and compares decoded bytes across CRD/admission/Secret. Error diagnostics are normalized and compared exactly, not substring-matched.

- 2026-07-27: PEM body validation strengthened: normalizes whitespace, validates base64 character set and length, decodes via Sprig `b64dec`, and checks the first decoded byte is 0x30 (DER SEQUENCE tag). This is syntactic validation only, not cryptographic trust validation. Rejects invalid-base64 bodies and valid-base64 non-certificate bodies with the same exact diagnostic.

- 2026-07-27: Task 4’s parsed default-combined assertion compares the archived BASE Deployment after removing only the three new ownership env entries. It therefore protects the existing image, ports, probes, security contexts, certificate mounts, leader-election env, selectors, and shared workload behavior without coupling to template text.

- 2026-07-27: Task 5 preserves the Task 3 split skeleton and adds only split ownership wiring: controller cleanup/self-registration are both false; webhook cleanup follows `createWebhookConfigurations`, self-registration is its inverse, and runtime-owned webhook mode receives the exact Helm predecessor configuration names. Parsed auto-generated and external-TLS renders confirm certificates remain webhook-only while both component Services select exactly one component Pod.

- 2026-07-27: `WebhookCertificateSecretManagerTest` now resolves one stored Secret from two concurrent replica-local directories, compares shared CA bytes, verifies each local server certificate against that CA, and proves each local private key matches its certificate. The 409 reread path remains covered by the existing focused test; no framework production code changed.

- 2026-07-27: Task 6 expresses RBAC as separate controller, webhook-Secret, controller-Lease, and webhook cluster roles. Combined keeps one release-namespace ServiceAccount while split binds each capability to its component ServiceAccount. Parsed rule-set equality checks resource names as well as verbs, which catches broadening that grep-based checks would miss; watched controller bindings keep the release-namespace ServiceAccount subject.

- 2026-07-27: Task 6 correction: runtime admission configuration names must use the effective watched namespace, not the release namespace, because Java `registrationBaseName` uses `operatorNamespace`. The parsed contract derives its expectation from the rendered controller Role namespace and exercises `release-ns` plus `watched-ns` with leader election, while retaining release-namespace ServiceAccount binding subjects.

- 2026-07-27: Task 2 composes capabilities without a deployment-mode abstraction: controller ownership gates `Operator`/`EchoReconciler`/`EventRecorder`/leader election, webhook ownership gates certs/handlers/server/registration, and the metrics readiness supplier evaluates controller sync and the example-local `webhookReady` flag according to the active combination.

- 2026-07-27: Task 2 uses one atomic stop guard plus explicit client ownership transfer immediately before `Operator.start()`. Pre-start and webhook-only paths close the shared client directly; a started controller delegates the one client close to `Operator.stop()`. The predecessor barrier uses exact-name GETs for 60 attempts with 1-second intervals and a package-private delay seam for deterministic tests.

- 2026-07-27: Task 2 ownership correction supersedes the prior start-state learning: shared-client shutdown follows object ownership, not lifecycle state. If `operator != null`, `Operator.stop()` is the sole close owner for pre-start, failed-start, and normal paths; only webhook-only (`operator == null`) closes from `EchoOperatorMain.stop()`. Wiring construction mocks must make `Operator.stop()` close the fixture client so duplicate-close regressions are observable.

- 2026-07-27: `EchoOperatorMain.main()` distinguishes create failure from start failure by whether `operatorMain` was assigned. Create failure closes the raw client directly; start already performs owned-resource cleanup internally, so the outer catch logs and wraps without closing the client again.

- 2026-07-27: Task 7 limits the exact-name predecessor barrier to runtime self-registration. Helm-owned serving skips predecessor GETs, starts TLS, synchronously removes only `registrationBaseName(config)` runtime names when cleanup is enabled, and becomes Ready only after cleanup succeeds. Combined runtime-owned Helm renders now inject the same exact predecessor names already used by split.

- 2026-07-27: Task 7 ownership contracts compare runtime-owned and Helm-owned renders in both topologies. They derive transition results from rendered self-registration/cleanup flags, exact predecessor env, Helm admission objects, and cleanup RBAC targets, and require one validating plus one mutating owner after either direction. A custom webhook Service render also proves Service metadata, runtime/certificate env, CRD, and admission targets share one identity.

- 2026-07-27: Task 8 smoke uses one release-scoped cleanup trap, isolated namespace/release names, Helm-only release lifecycle, and a self-checking static contract. The runtime path records real prerequisite failures separately beneath `combined/` and `split/`; unavailable clusters never become a successful skip.

- 2026-07-27: Task 8 review correction: combined uses one shared controller/webhook ServiceAccount, so runtime admission `get` is a required positive rather than a denial. The deliberate negative now requests CRD `delete` on exact `echoresources.example.com`, which the rendered auto-generated ClusterRole excludes (`get/update/patch` only). Port forwarding now publishes PID and port through parent-shell variables; the one cleanup trap records stop/kill/wait receipts, with a fake-process regression proving no PID survives.

- 2026-07-28: Task 8 live QA passed on `docker-desktop`: combined `live-combined-20260727290000` and split `live-split-20260728004000` completed all admission, conversion, reconciliation, TLS, ownership-transition, and cleanup assertions. The final cluster audit found no `echo-smoke-*` namespace/release/webhook or port-forward process.
- 2026-07-28: Live failures strengthened smoke safety: preserve pre-owned CRDs with a Helm post-renderer and patch only the external conversion clientConfig when required; cleanup EchoResources before uninstalling controllers and clear exact held Helm predecessor finalizers; use unique per-run image tags; parse `kubectl auth can-i` warnings and denied exit codes independently. `WebhookServer` now has a four-thread executor, and metrics begins before the predecessor barrier so held `/readyz` is observable as 503.

## Task 9: Documentation Contract Testing (2026-07-28)

### What worked well
- Writing the docs-contract-test.sh with RED assertions first caught missing content early
- Phase-based test structure (9 phases) made it easy to iterate on specific areas
- Excluding the "Exclusions" section from forbidden token checks prevented false positives
- Case-insensitive token matching handled heading capitalization differences

### Key decisions
- Did not modify dev-guide.md/zh-CN.md because deployment modes are chart-level, not framework-level
- Used "Exclusions" section (EN) and "不包含的功能" section (ZH) to explicitly document what's NOT provided
- Added deployment example commands with both `helm install --set` and `DEPLOYMENT_MODE= ./scripts/deploy.sh` patterns
- Made forbidden token checks context-aware by splitting content on `## ` and excluding exclusions sections

### Patterns established
- Documentation contract tests can verify EN/ZH consistency, required tokens, forbidden tokens, and verification commands
- Exclusions sections are valuable for preventing scope creep and setting expectations
- Deployment examples should show both direct Helm and script-based approaches

### Issues encountered
- Initial forbidden token check was too broad, flagging tokens mentioned in exclusions context
- Case-sensitive token matching missed "External TLS" when searching for "external TLS"
- Missing `deploymentMode=combined` and `deploymentMode=split` command-line examples caused consistency test failures

### Resolutions
- Updated test to split content and exclude exclusions sections from forbidden token checks
- Changed required token checks to case-insensitive matching
- Added deployment example subsections with both Helm and script command patterns
