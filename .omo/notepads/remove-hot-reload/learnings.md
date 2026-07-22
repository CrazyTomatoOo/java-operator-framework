# Learnings - remove-hot-reload

## 2026-06-21

- All runtime config hot-reload classes (`ConfigWatcher`, `ReloadableConfig`, `ConfigChangeListener`, `OperatorConfigSnapshot`) and their tests were removed.
- `jackson-dataformat-yaml` dependency removed from `operator/framework/pom.xml` because it was only used for YAML config parsing.
- `Operator`, `ResourceEventSource`, `ExponentialBackoffRetryPolicy`, and `RateLimiter` reverted to startup-time fixed values. `withRetryPolicy` and `withRateLimiter` remain for caller customization.
- `EchoOperatorMain` no longer creates/configures a `ConfigWatcher`; `OPERATOR_CONFIG_PATH`/`operator.config.path` were removed.
- Helm chart no longer renders a reloadable ConfigMap or mounts `operator-config.yaml`.
- SDK and Echo README/dev-guide docs were cleaned of hot-reload references; section numbers in dev-guide were renumbered.
- `.sisyphus/plans/operator-sdk-advanced-features.md` updated to reflect the removal.
- Verification: SDK `mvn clean install` (47 tests), Echo `mvn clean package` (29 tests), `rtk helm lint` (0 failed), and grep checks all passed.

## 2026-06-21 (continuation directive received)

- Blocker: Boulder system repeatedly issues OH-MY-OPENCODE continuation directives stating "Status: 0/0 completed, 0 remaining", but the plan file `.sisyphus/plans/remove-hot-reload.md` has all 13 TODOs checked and no open tasks.
- Action taken: Re-read plan file; confirmed no unchecked tasks remain. No code changes or further verification are required.
- Next step: Await a new user instruction or a new active plan; no work can proceed on a completed plan.

## 2026-06-21 (continuation directive received again)

- Blocker persists: Boulder system continues to issue continuation directives with "Status: 0/0 completed, 0 remaining" while the plan file has no unchecked tasks.
- Plan file re-read; all 13 TODOs remain checked. No new work can be identified.
- Action taken: Re-appended blocker note. Awaiting new user instruction or new active plan.

## 2026-06-21 (continuation directive received yet again)

- Blocker persists: Boulder system continues to issue continuation directives with "Status: 0/0 completed, 0 remaining" while the plan file has no unchecked tasks.
- Plan file re-read; all 13 TODOs remain checked. No new work can be identified.
- Action taken: Re-appended blocker note. Awaiting new user instruction or new active plan.

## 2026-06-21 (continuation directive received yet again — 4th occurrence)

- Blocker persists: Boulder system continues to issue continuation directives with "Status: 0/0 completed, 0 remaining" while `.sisyphus/plans/remove-hot-reload.md` has all 13 TODOs checked and no open tasks.
- Plan file re-read; all TODOs remain checked. No new work can be identified.
- Action taken: Re-appended blocker note. Awaiting new user instruction or new active plan.
