# Issues — controller-webhook-split-deployment

Problems and gotchas encountered during work on this plan.

_Auto-scaffolded by /start-work. Append new entries below - never overwrite._

---

- 2026-07-27: **BLOCKED Task 1.** Pre-existing dirty edits overlap required product paths `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java` and `example/echo-operator/src/test/java/com/example/echooperator/EchoOperatorMainConfigTest.java`. Stopped without product edits, Maven, or LSP checks.

- 2026-07-27: Read-only baseline diagnostic classifies the overlap as **partially compatible but externally coupled**. It adds the separate `WEBHOOK_VALIDATING_ENABLED`, `WEBHOOK_MUTATING_ENABLED`, and `WEBHOOK_CONVERSION_ENABLED` feature plus framework enable/disable APIs, registration filtering, tests, and docs. It does not add `CONTROLLER_ENABLED`, cleanup/self-registration flags, predecessor names, matching properties, or the required all-off error. Preserve as one unit: `EchoOperatorMain.java`, `EchoOperatorMainConfigTest.java`, `EchoOperatorMainWiringTest.java`, both webhook handler sources and tests, `WebhookSelfRegistration.java`, and both related READMEs. Do not layer Task 1 into this worktree without the feature owner's explicit integration decision.

- 2026-07-27: User approved preserve-and-integrate; the original Task 1 overlap blocker is resolved. The endpoint-toggle feature remains intact. Focused configuration tests pass with 16 tests and zero failures/errors/skips. LSP diagnostics were requested for both changed Java files but the shared daemon timed out after 30 seconds before responding.

- 2026-07-27: Task 3 intentionally leaves split Deployment/Service rendering unchanged; the split render evidence therefore still contains the combined resource set. Tasks 4 and 5 own those topology resources and must update the parsed contract assertions when they land.

- 2026-07-27: Task 4 emits the Task 1-planned ownership environment names but does not integrate the unavailable Java lifecycle implementation. The protected dirty Java/framework/docs paths were SHA-256 verified byte-identical before and after this Helm-only work.

- 2026-07-27: **Dependent tasks paused.** Tasks 2 and 7 require editing the same pre-existing dirty Java endpoint-toggle feature blocked in Task 1. Task 8 requires those runtime behaviors for non-skippable live QA. Task 9 requires the final Java configuration contract and overlaps dirty README work. F1–F4 cannot approve a partially implemented plan. All are marked `[~]` until the feature owner chooses preserve-and-integrate versus clean-worktree isolation.

- 2026-07-27: Task 2 JDT LS diagnostics were requested twice for both modified Java files; all four requests timed out after 30 seconds because the shared daemon did not respond. Maven compiler/tests remained the hard fallback gate: focused lifecycle tests passed 15/15 and the complete example suite passed 63/63 with zero failures, errors, or skips.

- 2026-07-27: Task 2 validation correction: the initial `clientOwnedByOperator` start-state flag encoded the wrong ownership rule. Once an `Operator` object exists, `EchoOperatorMain.stop()` must always delegate shared-client closure to `Operator.stop()`; directly closing based on whether `Operator.start()` was attempted caused duplicate close calls in pre-start and startup-failure tests once the mock reflected the framework stop contract. The previous 15/15 and 63/63 results did not expose this because the construction mock's `stop()` was a no-op.

- 2026-07-27: Task 2 ownership-fix JDT LS diagnostics were requested again for both modified Java files and both requests timed out after 30 seconds. Maven fallback gates passed 16/16 focused and 64/64 full with no failures, errors, or skips.

- 2026-07-27: Task 7 JDT LS diagnostics were requested twice for `EchoOperatorMain.java` and `EchoOperatorMainWiringTest.java`; all four requests timed out because the shared daemon did not respond. Compiler/test fallback gates passed: framework registration 9/9, focused example ownership 35/35, and full example 67/67 with zero failures, errors, or skips.

- 2026-07-27: Task 8 live verification is blocked before any Helm release or namespace was created: the active `docker-desktop` Kubernetes context points to `127.0.0.1:6443`, which refused the API connection, and the Docker daemon socket was absent. The nonzero prerequisite receipts are retained in the Task 8 combined/split evidence directories.

- 2026-07-27: Atlas static review found and Task 8 corrected two script-only defects before live retry: the shared combined ServiceAccount made the former deliberate mutating-webhook `get` assertion an expected allow, and command substitution around `start_port_forward` lost its PID-list mutation. No Java, framework, chart, documentation, or plan file changed; the Kubernetes/Docker blocker remains unresolved.

- 2026-07-28: Task 8 external blocker resolved. Live runs initially surfaced pre-owned CRD Helm metadata, stale held-predecessor registrations, auto-mutated invalid fixtures, raw conversion warning pollution, kubectl auth output/exit semantics, cached `latest` images, and a single-threaded webhook server starvation path. Each was reproduced by the corresponding live evidence or focused RED test and repaired minimally; final combined/split runs passed.
