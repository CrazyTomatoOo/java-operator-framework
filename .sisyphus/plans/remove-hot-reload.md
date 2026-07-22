# Plan: Remove Runtime Config Hot-Reload Mechanism

## TL;DR

> Cancel the operator-framework runtime configuration hot-update mechanism. Keep resync period, retry policy, and rate-limit interval as startup-time fixed settings. Delete `ConfigWatcher`, `ReloadableConfig`, `OperatorConfigSnapshot`, `ConfigChangeListener`, and all wiring; revert `Operator`, `ResourceEventSource`, retry/rate-limit classes, `EchoOperatorMain`, tests, docs, and Helm chart to fixed-value semantics.

## Context

User requested cancellation of the configuration hot-update mechanism after the advanced-features plan was completed. Some deletions have already been performed by the planner; this plan covers the remaining edits and verification.

Already deleted:
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/config/*.java`
- `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/config/*.java`
- `example/echo-operator/src/main/resources/operator-config.yaml`
- `example/echo-operator/helm/echo-operator/templates/configmap.yaml`
- `.sisyphus/evidence/task-1-reloadable-config.log`
- `.sisyphus/evidence/task-2-config-watcher.log`
- `.sisyphus/evidence/task-3-hot-reload.log`

## Work Objectives

### Must Do
1. Remove `jackson-dataformat-yaml` dependency from `operator/framework/pom.xml`.
2. Revert `Operator.java` to fixed resync/retry/rate-limit values; remove `withConfigSnapshot`, `withReloadableConfig`, listener, snapshot supplier, `rebuildInformers`.
3. Revert `ResourceEventSource.java` to fixed resync constructors only.
4. Revert `ExponentialBackoffRetryPolicy.java` and `RateLimiter.java` to fixed constructors only.
5. Update `OperatorLauncherTest.java` to remove hot-reload test.
6. Update `EchoOperatorMain.java` to remove `ReloadableConfig`, `ConfigWatcher`, `operatorConfigPath`, and related start/stop logic.
7. Update `EchoOperatorMainTest.java` to remove hot-reload assertions and test.
8. Update `example/echo-operator/src/main/resources/application.properties` to remove `operator.config.path`.
9. Update Helm chart `values.yaml` and `deployment.yaml` to remove reloadable config values/volumes/env.
10. Update documentation in `operator/framework/README*.md`, `docs/dev-guide*.md`, `example/echo-operator/README*.md`, and `smoke-test.sh` to remove hot-reload references.
11. Update `.sisyphus/plans/operator-sdk-advanced-features.md` to reflect removal.
12. Run verification: SDK install, example package, Helm lint.

### Must NOT Do
- Do not remove TLS cert reload (`CertWatcher`, `ReloadableSslContext`).
- Do not remove webhook, conversion, multi-version CRD features.
- Do not add new dependencies.

## Verification Strategy

- `mvn -f operator/framework/pom.xml clean install` → BUILD SUCCESS
- `mvn -f example/echo-operator/pom.xml clean package` → BUILD SUCCESS
- `rtk helm lint example/echo-operator/helm/echo-operator` → 0 failed
- Grep confirms no remaining references to removed hot-reload API names in source/docs.

## Execution Strategy

Single sequential pass due to cross-file dependencies.

## TODOs

- [x] Edit `operator/framework/pom.xml` to remove `jackson-dataformat-yaml` dependency.
- [x] Edit `Operator.java` to remove hot-reload wiring.
- [x] Edit `ResourceEventSource.java` to remove supplier constructors.
- [x] Edit `ExponentialBackoffRetryPolicy.java` to remove supplier constructor.
- [x] Edit `RateLimiter.java` to remove supplier constructors.
- [x] Edit `OperatorLauncherTest.java` to remove hot-reload test.
- [x] Edit `EchoOperatorMain.java` to remove reloadable config wiring.
- [x] Edit `EchoOperatorMainTest.java` to remove hot-reload test/assertions.
- [x] Edit `application.properties` to remove `operator.config.path`.
- [x] Edit `values.yaml` and `deployment.yaml` to remove reloadable config.
- [x] Edit documentation files to remove hot-reload references.
- [x] Update `.sisyphus/plans/operator-sdk-advanced-features.md`.
- [x] Run Maven and Helm verification.

## Success Criteria

- SDK builds and tests pass.
- Example builds and tests pass.
- Helm chart lints cleanly.
- No references to `ReloadableConfig`, `ConfigWatcher`, `OperatorConfigSnapshot`, `withConfigSnapshot`, `withReloadableConfig`, `operator-config.yaml`, `OPERATOR_CONFIG_PATH`, or `config.reloadable` remain in source, docs, or Helm values/templates.
