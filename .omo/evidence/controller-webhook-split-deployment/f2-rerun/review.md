# F2 Code and Chart Quality Review — Rerun

## Verdict

**PASS** — the three lifecycle leak fixes are correct, all required test and render suites pass without skips, and no blocking code, chart, RBAC, or shell-quality issue was found.

Review scope follows `.omo/plans/controller-webhook-split-deployment.md:357-359`.

## Automated verification

| Check | Result | Evidence |
|---|---|---|
| Framework full Maven tests | PASS — 168 run, 0 failures, 0 errors, 0 skipped | `framework-maven-test.log` |
| Example full Maven tests | PASS — 69 run, 0 failures, 0 errors, 0 skipped | `example-maven-test.log` |
| Helm lint | PASS — 1 chart linted, 0 failed; icon recommendation only | `helm-lint.log` |
| All render-contract cases | PASS — every positive/RBAC/ownership/negative case passed and final acceptance marker emitted | `helm-contract-test.log` |
| Shell syntax | PASS | `shell-syntax.log` |
| Smoke static cleanup/error contract | PASS | `smoke-static-contract.log` |
| Misleading skip-success scan | PASS — no `SKIP`/`NOT RUN`; both Maven suites report skipped=0 | `no-misleading-skip-scan.log` |

## Lifecycle review

### Fix 1 — `operatorStarted` and client ownership

PASS.

- `operatorStarted` is false until the controller start path transfers client ownership to `Operator`.
- A pre-start `stop()` invokes the still-unstarted `Operator.stop()` and then directly closes the shared client because `operatorStarted` is false.
- `EchoOperatorMain.stopped.compareAndSet(false, true)` makes the full cleanup path run once, so repeated pre-start stops cannot close the client twice.
- Once `Operator.start()` is invoked, `operatorStarted` is set first and `Operator.stop()` owns client closure; `EchoOperatorMain` does not close it again.
- Covered by `stopBeforeStartClosesEveryControllerOwnedResourceAtMostOnce`, `controllerStartupFailureBeforeOperatorStartClosesSharedClientExactlyOnce`, and the normal/leader-election stop cases.

### Fix 2 — `create()` failure rollback

PASS.

- The failure handler stops an already-created `WebhookServer`.
- It start-then-closes the pre-start `MetricsHealthServer`, which is required to release a JDK `HttpServer` socket that was bound but never started.
- Cleanup failures are attached to the original failure as suppressed exceptions rather than replacing it.
- `main()` closes the client when `create()` fails before an `EchoOperatorMain` instance is returned.
- `createFailureAfterWebhookBindingReleasesBothServerPorts` proves both bound ports are reusable after rollback.

### Fix 3 — `WebhookServer.stop()` pre-start/failed-start cleanup

PASS.

- Lifecycle transitions are serialized by `lifecycleLock` and `running` is cleared before cleanup.
- `CertWatcher.stop()` is called for pre-start, failed-start, and normal-stop paths.
- For a never-started JDK `HttpsServer`, stop performs the required start-then-stop sequence to release the bound socket; an already stopped server's `IllegalStateException` is intentionally tolerated.
- `executor.shutdownNow()` is in the outer `finally`, so server stop does not strand executor threads.
- Repeated stop operations are safe: CertWatcher stop, server stop, and executor shutdown are repeat-safe.
- Covered by `stopBeforeStartReleasesTheBoundPort` and `stopAfterFailedStartStopsTheCertWatcher`; the full framework suite passes.

### Nullability and idempotence

PASS. Optional controller/webhook fields are gated by their capability flags or null-checked during shutdown. EchoOperatorMain cleanup is idempotent and readiness is reset before shutdown or failed startup.

## Chart and script quality review

### Immutable selector preservation

PASS. Combined Deployment and Service selectors remain the archived `name + instance` pair. Split selectors consistently add the stable `component` label to Deployment selectors, pod labels, and Service selectors. The archived baseline and split isolation contracts passed.

### Values duplication and boundaries

PASS. Top-level workload values are explicitly combined-only; `controller.workload.*` and `webhook.workload.*` are explicit split-only blocks and do not inherit top-level values. Poison-value isolation passed for both directions. The duplication is intentional configuration separation, not conflicting ownership.

### Helper readability

PASS. Helpers are grouped and named by one responsibility, component-generic helpers validate supported component names, and topology/value validation produces specific diagnostics. No unreadable or unnecessary helper indirection was found.

### Least privilege

PASS. Exact RBAC object, rule, binding-subject, and denial matrices passed for combined/split, auto/external TLS, runtime/Helm ownership, watched/release namespace, leader election, and custom-ServiceAccount/no-RBAC renders. Controller and webhook split identities do not receive each other's capabilities.

### Shell cleanup and error handling

PASS. Reviewed scripts use strict mode, bounded render commands, deterministic temporary-directory cleanup, one smoke EXIT trap, parent-shell port-forward PID tracking, and explicit cleanup receipts. Smoke cleanup preserves the original failure status and turns an otherwise successful run into failure if cleanup fails. Best-effort `|| true` uses are limited to probes or finalizer release followed by checked deletion; they do not create false success.

### Misleading skip-success

PASS. Required suites ran with concrete acceptance markers. Maven reports zero skipped tests. Render-contract cases execute unconditionally and negative cases must fail with exact diagnostics. Smoke preflight treats missing context/cluster as failure, not success.

## Findings

No blocking or non-blocking defect found in the requested F2 scope.
