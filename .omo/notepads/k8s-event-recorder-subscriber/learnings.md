# Learnings — k8s-event-recorder-subscriber

## Conventions observed in the framework

- Base package: `com.huawei.dcs.modelengine.operator.framework`.
- Null validation style: `Objects.requireNonNull(x, "x must not be null")`; blank strings rejected with `IllegalArgumentException` (see `SecondaryWatch` compact constructor).
- `SecondaryWatch<P, S>` is a record: `(String name, Class<S> resourceClass, ResourceMapper<S, P> mapper, boolean owned)`.
- `ResourceMapper<R, P>` is a `@FunctionalInterface`: `Collection<Request> map(R secondary, ResourceEvent<R> event)`; returns `List.of()` for no-match.
- `Request` constructors: `new Request(namespace, name)`.
- `ControllerBuilder<P>` implements `ControllerSources<P>`; internal `List<SecondaryWatch<P, ?>> secondaryWatches`; `build()` requires reconciler.
- Javadoc on all public classes; no star imports.
- No parent Maven reactor: always `mvn -f <module>/pom.xml ...`.
- Example module requires framework installed first: `mvn -f operator/framework/pom.xml -DskipTests install` before building `example/echo-operator`.

## Verified environment facts

- `operator/framework/pom.xml`: fabric8 7.3.0, micrometer 1.16.1, bouncycastle 1.84 (confirmed by grep). `kubernetes-server-mock` NOT yet present — T1 must add it test-scoped with `${fabric8.version}`.
- `example/echo-operator/pom.xml`: fabric8 7.3.0, junit + mockito test deps present.
- Fabric8 7.3.0: core/v1 Events via `client.v1().events()`; `client.events()` targets `events.k8s.io` group. `HasMetadata.getKind(Class)` / `HasMetadata.getApiVersion(Class)` are the correct static helpers.
- Mock server: JUnit 5 `@EnableKubernetesMockClient(crud = true)` from `io.fabric8:kubernetes-server-mock` with injected `KubernetesClient` / `KubernetesMockServer`.
- `Operator.stop()` closes the shared Kubernetes client — `EventRecorder.close()` must run BEFORE `operator.stop()`.

## Execution constraints

- Environment is NOT a git repo (verified `git status` fails) — plan commit steps are impossible; workers must skip all git operations.
- Maven runs in the same module must NOT run concurrently (shared `target/` and `.m2` state). Workers write files only; orchestrator serializes Maven verification.

## T2 findings

- Created `event/EventSubscriber.java`, `event/EventMapper.java`, and `event/EventSubscriberTest.java`; no existing files touched.
- `EventSubscriber<P extends HasMetadata>` is a final class with a private constructor; `forInvolvedObject(Class<P>)` null-checks, then wraps `new SecondaryWatch<>("events", Event.class, EventMapper.involvedObject(primaryResourceClass), false)` (`owned=false` — Events are not owned by the primary). `toSecondaryWatch()` exposes the watch.
- `EventMapper.involvedObject(Class<P>)` null-checks, then eagerly derives `primaryKind`/`primaryApiVersion` via `HasMetadata.getKind(Class)` / `HasMetadata.getApiVersion(Class)`; the lambda null-checks the resource, returns `List.of()` for null involvedObject or kind/apiVersion mismatch, else `List.of(new Request(involvedObject.getNamespace(), involvedObject.getName()))`.
- Verified against the fabric8 7.3.0 jar with `javap`: `HasMetadata.getKind(Class)` reads `@Kind` and falls back to `Class.getSimpleName()` (ConfigMap -> `"ConfigMap"`); `getApiVersion(Class)` combines `@Group("")` + `@Version("v1")` -> `"v1"`. `Event.getInvolvedObject()` returns `ObjectReference` with `getKind/getApiVersion/getNamespace/getName`.
- Both classes carry Javadoc stating Events are best-effort (may be dropped/TTL-expired, not for correctness-critical state) and warning about the self-triggering reconcile-loop hazard (filter by source/reason/type); EventSubscriber Javadoc also states namespace scope is inherited from the Operator's `SharedInformerFactory`.
- Verified WITHOUT mvn: compiled the whole framework main source set plus the new test with `javac` against local `.m2` jars (exit 0), then ran `EventSubscriberTest` via a junit-platform-launcher runner: 6/6 tests pass (happy mapping, kind mismatch, apiVersion mismatch, null involvedObject, null class NPE, watch shape).
- Note for orchestrator: `lsp_diagnostics` timed out for Java main sources in this environment; javac + junit-platform-launcher against `.m2` is a working no-mvn verification path.

## T1 findings

- Added `EventRecorder` for `core/v1` Events through `client.v1().events()`, with deterministic names, namespace fallback, null/UID validation, and Normal/Warning helpers.
- Aggregation is serialized under the recorder monitor and stores server count/resourceVersion plus pending count. Event-driven, scheduled, eviction, TTL, AlreadyExists, missing-Event, and one-retry 409 paths all use the same write logic.
- A bounded insertion-ordered cache makes oldest-entry eviction deterministic when clock values tie. Public constructors own a daemon scheduler; the test constructor keeps ownership external, and `close()` flushes synchronously before cancellation/shutdown.
- `EventRecorderTest` covers T1 Happy QA Actions 1-17 (including 14b-14f) and Failure QA using the Fabric8 CRUD mock; a package-private I/O override verifies the exact fresh-resourceVersion 409 retry patch.
- Fabric8's mock dependency may not refresh in the Java language server until the Maven model reloads, but both Java files reached zero LSP diagnostics after the POM update. Maven was intentionally not run per worker constraints.
- Two-axis review tightened suppression semantics so repeats increment only `pendingCount` (they do not debounce the flush window), made naming tests locale-independent, and added an exact QA 3 assertion that the first aggregation patch carries the cached resourceVersion.

## T3 findings

- Added `ControllerSources.withEventSubscriber(EventSubscriber<P>)` with Javadoc and wired `ControllerBuilder` to null-check the subscriber, append `eventSubscriber.toSecondaryWatch()`, and return itself for fluent chaining.
- Added `ControllerBuilderTest` coverage for the `events` secondary watch shape and the required null-subscriber exception message.
- Existing `owns(...)` and `watches(...)` implementations remain unchanged. Maven and git were intentionally not run under the worker constraints.

## T5 findings

- Updated `EchoReconciler` to accept an optional `EventRecorder` through a new three-argument constructor while keeping the existing one- and two-argument constructors delegating with a null recorder.
- The READY path now records a best-effort Normal `Reconciled` event after status update; the catch path records a best-effort Warning `ReconcileFailed` event using the exception message or the exception simple class name when the message is null.
- `EchoOperatorMain` constructs `new EventRecorder(client, "echo-operator")`, passes it to the reconciler, documents the commented `EventSubscriber.forInvolvedObject(EchoResource.class)` self-trigger loop hazard, and closes the recorder before `operator.stop()` because `Operator.stop()` closes the shared client.
- `EchoReconcilerTest` already existed in the example module; it was updated in place with Mockito `EventRecorder` coverage for success, failure, null-message fallback, and recorder best-effort behavior using `mock(KubernetesClient.class, RETURNS_DEEP_STUBS)` for the new event tests.
- Maven and git were intentionally not run under the worker constraints; allowed verification used the T5 grep-style acceptance check, a no-Maven `javac` compile of framework main sources + echo main sources + `EchoReconcilerTest`, and a direct JUnit Platform run of `EchoReconcilerTest` (11/11 passing). LSP diagnostics remained limited by the uninstalled framework artifact in the local test classpath.
## T5 test completion
- testRecordsNormalEventOnSuccessfulReconcile
- testRecordsWarningEventOnReconcileFailure
- testRecordsWarningEventWithExceptionClassWhenMessageIsNull
- testRecorderFailureDoesNotChangeSuccessfulReconcileResult
## T4 findings
#TF|
#FY|- Failure injection output: `org.opentest4j.AssertionFailedError: expected: <99> but was: <1>` at `EventRecorderTest.lambda$3(EventRecorderTest.java:78)` inside `action1CreatesCoreV1NormalEventWithExpectedFields`; Maven ended with `BUILD FAILURE` and `Tests run: 23, Failures: 1, Errors: 0, Skipped: 0`.
#PG|- After restoring the literal back to `assertEquals(1, event.getCount())`, rerunning `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest` returned `BUILD SUCCESS` with `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0`.
#BX|- Only the one assertion literal was toggled and restored; the test file is back to its original state.

## T6 findings

1. `mvn -f operator/framework/pom.xml -DskipTests install`
   - Exit status: `0`
   - Key output: `BUILD SUCCESS`
   - Artifact: `/Volumes/work/Project/java-operator-framework/operator/framework/target/operator-framework-0.1.0-SNAPSHOT.jar`
2. `mvn -f operator/framework/pom.xml test`
   - Exit status: `0`
   - Key output: `Tests run: 132, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`
3. `mvn -f example/echo-operator/pom.xml -DskipTests package`
   - Exit status: `0`
   - Key output: `BUILD SUCCESS`
   - Artifact: `/Volumes/work/Project/java-operator-framework/example/echo-operator/target/echo-operator-0.1.0-SNAPSHOT.jar`
4. `REPO=$(mktemp -d) && mvn -f example/echo-operator/pom.xml -DskipTests package -Dmaven.repo.local=$REPO; rm -rf $REPO`
   - Exit status: `1`
   - Key output: `[ERROR] Could not find artifact com.huawei.dcs.modelengine:operator-framework:jar:0.1.0-SNAPSHOT`
   - Result: `BUILD FAILURE`
## F3 build QA

- `mvn -f operator/framework/pom.xml test` → `BUILD SUCCESS`; `Tests run: 132, Failures: 0, Errors: 0, Skipped: 0`
- `mvn -f example/echo-operator/pom.xml -DskipTests package` → `BUILD SUCCESS`
## F4 scope fidelity

- Check 1: `operator/framework/pom.xml` keeps `fabric8.version=7.3.0`, `micrometer.version=1.16.1`, and `bouncycastle.version=1.84`; the only new dependency is `io.fabric8:kubernetes-server-mock` with `${fabric8.version}` and `test` scope.
- Check 2: `webhook/`, `metrics/`, `leader/`, and `health/` source files are dated 2026-06-18/20, so those subsystems were not edited on 2026-07-21.
- Check 3: `ControllerSources.java` and `ControllerBuilder.java` show only the new `withEventSubscriber(...)` API/import; `owns(...)`, `watches(...)`, `withReconciler(...)`, and `build()` are unchanged in the current source.
- Check 4: No extra production dependencies appear in either pom; the framework pom only adds the test-scoped mock server, and the example pom still has `fabric8.version=7.3.0` with no new deps.
- Check 5: `example/echo-operator/pom.xml` is unchanged, and the example edits stay within the allowed files (`EchoReconciler.java`, `EchoOperatorMain.java`, `EchoReconcilerTest.java`).
