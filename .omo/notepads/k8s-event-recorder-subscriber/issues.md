# Issues — k8s-event-recorder-subscriber

## 2026-07-21

- NOT a git repo (`git status` fatal). Plan commit steps (`feat: ...` per task) cannot be performed. All workers instructed to skip git operations. Recorded as accepted deviation from plan.

## 2026-07-21

- Fabric8 7.3.0's CRUD mock returned HTTP 415 for core/v1 Event strategic-merge PATCH requests. `EventRecorder` now uses `PatchType.JSON_MERGE`; its full-object patch retains equivalent scalar `count`/`lastTimestamp` replacement and `metadata.resourceVersion` optimistic concurrency semantics.
