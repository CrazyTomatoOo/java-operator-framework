# Decisions — k8s-event-recorder-subscriber

## 2026-07-21 Orchestration decisions

- T1 (EventRecorder) assigned to `ultrabrain` category: per-key serialization, scheduled flush, 409 retry, AlreadyExists handling, bounded cache eviction — genuinely logic-heavy.
- T1 + T2 run as a parallel batch: disjoint new files (no file conflicts). Both workers are forbidden from running `mvn` (orchestrator serializes all Maven verification to avoid shared-module build races).
- T3 + T5 run as a parallel batch after T1/T2: T3 depends on T2's `EventSubscriber`; T5 depends on T1's `EventRecorder`. Disjoint files, different modules.
- Plan checklist added to plan file (`## TODOs`, `## Final Verification Wave Checklist`) because the plan used section headers, not checkboxes — required for post-delegation checkbox tracking.

## 2026-07-21 Patch type correction (post-implementation verification)

- Verification of T1 revealed the fabric8 7.3.0 CRUD mock server rejects `application/strategic-merge-patch+json` PATCHes with HTTP 415 (9/29 EventRecorderTest errors; all PATCH paths). Switched `EventRecorder.patchEvent` from `PatchType.STRATEGIC_MERGE` to `PatchType.JSON_MERGE` (`application/merge-patch+json`). Semantics are identical for this design: the patch body is a full Event template with updated `count`/`lastTimestamp`/`metadata.resourceVersion`; JSON merge patch replaces those scalar fields and the API server enforces `resourceVersion` optimistic concurrency from the merged body. Plan wording updated in TL;DR, T1 acceptance criteria, and T1 Action 8.

## 2026-07-21 Final Verification Wave results

- F1 Plan compliance: APPROVE (initial REJECT on a false positive — `EchoResource` string literal in pre-existing `ConversionHandlerTest.java:237`; verified zero `com.example.echooperator` imports in framework tests; re-review APPROVED).
- F2 Code quality: APPROVE (style, fail-loud, lifecycle, thread-safety, aggregation arithmetic all verified).
- F3 Automated build QA: APPROVE (`mvn test` framework 132/0/0 BUILD SUCCESS; example package BUILD SUCCESS).
- F4 Scope fidelity: APPROVE (versions unchanged; webhook/metrics/leader/health untouched; only additive changes; only new dep is test-scoped kubernetes-server-mock).
- PLAN COMPLETE: all 6 implementation tasks + 4 final wave checks approved.