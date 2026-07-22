# Plan: Primary / Secondary Resource Support in java-operator-framework

## TL;DR

> Add the ability to watch secondary resources (including non-owned resources) and expose which resource triggered a reconciliation, while keeping the existing `Reconciler<T>` and `Operator.register(Class, Reconciler)` API unchanged.
>
> **Deliverables**:
> - `Trigger` / `ResourceEvent` / `ResourceMapper` API for secondary resource mapping
> - Public `ControllerRegistration` / `ControllerBuilder` for explicit controller configuration
> - Updated `Request` carrying optional trigger information
> - Refactored `ResourceEventSource` that binds to a shared controller queue
> - Updated `Operator` supporting secondary watches and backward-compatible registration
> - Comprehensive unit tests covering owned/non-owned secondary resources, delete events, and queue coalescing
>
> **Estimated Effort**: Medium-Large (3-5 days of focused implementation + 1-2 days of tests/docs)
> **Parallel Execution**: YES — 4 waves
> **Critical Path**: Design API → Add Request/Trigger types → Refactor ResourceEventSource → Update Operator → Tests → Final QA

---

## Context

### Original Request
The user asked whether the framework distinguishes primary and secondary resources. After analysis, we concluded it does not. The user then requested multiple design options and decided to implement support with the following requirements:

1. **Watch secondary resources that are NOT owned by the primary resource.**
2. **The Reconciler must know which specific secondary resource triggered reconciliation.**
3. **No time constraint.**

### Design Decision (after Metis review)

Adopt a **minimal viable subset of the controller-runtime-style design** (Option 2):

- Keep `Reconciler<T>.reconcile(Request, T)` unchanged.
- Add explicit configuration via `ControllerRegistration` / `ControllerBuilder`.
- Add `ResourceMapper<R, P>` / `ResourceMapper<R>` for secondary → primary mapping.
- Add `Trigger` to `Request` so reconcilers can inspect what caused the event.
- Use a single shared work queue per controller, coalescing by primary key but accumulating triggers.
- **Defer** `ResourceCache` and `ContextualReconciler` to a future iteration.

### Research Findings

- The current `Operator` creates one `ResourceEventSource` per `register()` call, each with its own queue.
- `ResourceEventSource` wraps a fabric8 `SharedIndexInformer` and translates add/update/delete into `Request(namespace, name)`.
- `Operator.runWorker()` reads from the event source queue and uses `getInformer().getStore().getByKey(key)` to load the primary resource.
- `ControllerRegistration` is currently a private inner record in `Operator.java`.

---

## Work Objectives

### Core Objective
Enable a single reconciler to watch multiple resource types, including secondary resources that are not owned by the primary, and expose trigger information to the reconciler without breaking the existing API.

### Concrete Deliverables
- `Trigger` interface/class with event type, resource reference, and role.
- `Request` with optional trigger(s).
- `ResourceMapper<R, P>` / `ResourceMapper<R>` interface.
- `ResourceEvent<R>` for add/update/delete events.
- Public `ControllerRegistration<P>` record/class.
- `ControllerBuilder<P>` fluent API.
- `ControllerSources<P>` for adding secondary sources.
- Updated `ResourceEventSource` that supports both primary and secondary modes.
- Updated `Operator` that wires multiple event sources into one queue.
- Built-in mappers: `Mappers.ownerReferences()` and `Mappers.byLabel(...)`.
- Tests and updated documentation.

### Definition of Done
- `mvn -f operator/framework/pom.xml test` passes with new tests.
- `mvn -f example/echo-operator/pom.xml test` passes after framework update.
- `Operator.register(Class, Reconciler)` behaves identically to before.
- A secondary Deployment event triggers the primary EchoResource reconciler with a trigger describing the Deployment.
- A non-owned ConfigMap event can be configured to trigger a primary reconciler via a custom mapper.

### Must Have
- Backward-compatible `Operator.register(Class, Reconciler)`.
- Existing `Reconciler<T>` interface unchanged.
- Secondary resource support for at least owner-reference and label-based mapping.
- Trigger information available on `Request`.
- Queue coalescing by primary key while preserving accumulated triggers.
- Comprehensive unit tests.

### Must NOT Have (Guardrails)
- Do NOT introduce `ResourceCache` abstraction in this plan.
- Do NOT introduce `ContextualReconciler` interface yet.
- Do NOT change the default behavior for existing registrations.
- Do NOT require a real Kubernetes cluster for tests.
- Do NOT break existing `Request` equality semantics for primary-key lookups.

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: YES (JUnit 5 + Mockito)
- **Automated tests**: TDD for new classes, tests-after for integration points.
- **Framework**: JUnit 5
- **Agent-Executed QA**: Every task includes Maven test commands.

### QA Policy
Every task must be verifiable by running `mvn test`. Evidence saved to `.sisyphus/evidence/`.

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation - independent type design):
├── T1: Define Trigger, ResourceEvent, ResourceMapper model
├── T2: Design and update Request with trigger support
├── T3: Define ControllerRegistration / ControllerBuilder / ControllerSources
└── T4: Create built-in Mappers (ownerReferences, byLabel)

Wave 2 (Core integration - depends on Wave 1):
├── T5: Refactor ResourceEventSource to support primary/secondary modes
├── T6: Update Operator to wire multiple event sources into one queue
├── T7: Add queue coalescing with trigger accumulation
└── T8: Ensure backward compatibility for existing register(Class, Reconciler)

Wave 3 (Tests + Example update - depends on Wave 2):
├── T9: Write unit tests for secondary events, mappers, and triggers
├── T10: Update OperatorLauncherTest and ResourceEventSourceTest
├── T11: Add example EchoReconciler secondary watch demo (optional but recommended)
└── T12: Update docs/dev-guide and framework README

Wave 4 (Final verification):
├── F1: Full Maven test run for operator/framework
├── F2: Full Maven test run for example/echo-operator
├── F3: API review (verify backward compatibility)
└── F4: User okay
```

### Dependency Matrix

- T1, T2, T3, T4: no dependencies
- T5 depends on T1, T2, T3
- T6 depends on T3, T5
- T7 depends on T2, T6
- T8 depends on T6
- T9 depends on T1-T5
- T10 depends on T6, T8
- T11 depends on T6, T7
- T12 depends on T10
- F1-F3 depend on T9-T12
- F4 depends on F1-F3

### Agent Dispatch Summary

- Wave 1: 4 tasks, all `quick` or `deep` (API design + model classes)
- Wave 2: 4 tasks, `deep` and `unspecified-high` (informer + queue integration)
- Wave 3: 4 tasks, `unspecified-high` and `writing` (tests + docs)
- Wave 4: 4 tasks, `oracle` and `unspecified-high` (verification + review)

---

## TODOs


- [x] 1. **Define Trigger, ResourceEvent, ResourceMapper model**

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger` interface/record with: event type (ADD/UPDATE/DELETE/RESYNC), resource reference (GVK + namespace + name + uid), and role (PRIMARY or SECONDARY).
  - Create `com.huawei.dcs.modelengine.operator.framework.source.ResourceEvent<R extends HasMetadata>` record with `ResourceEventType type()`, `R resource()`, and `R oldResource()`.
  - Create `com.huawei.dcs.modelengine.operator.framework.source.ResourceMapper<R extends HasMetadata, P extends HasMetadata>` functional interface: `Collection<Request> map(R secondary, ResourceEvent<R> event)`.
  - Ensure all classes are public, immutable, and have Javadoc.

  **Must NOT do**:
  - Do not add Kubernetes-specific logic (e.g., owner reference parsing) into these model classes. That belongs in `Mappers`.
  - Do not modify `Operator` or `ResourceEventSource` yet.

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Reason**: Small model classes; no complex logic.
  - **Skills**: [`tdd`] — write tests for model class behavior first.

  **Parallelization**:
  - **Can Run In Parallel**: YES — with T2, T3, T4.
  - **Parallel Group**: Wave 1.
  - **Blocks**: T5, T6, T9.
  - **Blocked By**: None.

  **References**:
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/reconciler/Request.java` — existing structure to align with.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceEventSource.java` — consumer of these types.

  **Acceptance Criteria**:
  - [ ] `Trigger` compiles and has a static factory from `HasMetadata`.
  - [ ] `ResourceEvent` test verifies it holds type/resource/oldResource.
  - [ ] `ResourceMapper` functional interface compiles and can be used in a lambda.
  - [ ] `mvn -f operator/framework/pom.xml test -Dtest=TriggerTest,ResourceEventTest,ResourceMapperTest` passes.

  **QA Scenarios**:
  ```
  Scenario: Create a secondary trigger from a Deployment resource
    Tool: Bash (mvn test)
    Preconditions: New Trigger class exists.
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=TriggerTest`.
    Expected Result: All tests pass; Trigger correctly captures GVK, namespace, name, and event type.
    Evidence: .sisyphus/evidence/task-1-trigger.log

  Scenario: ResourceEvent holds old and new resource for updates
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ResourceEventTest`.
    Expected Result: Tests assert oldResource is present on update and null on add/delete.
    Evidence: .sisyphus/evidence/task-1-resource-event.log
  ```

  **Commit**: YES
  - Message: `feat(reconciler): add Trigger, ResourceEvent, and ResourceMapper model`
  - Files: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/reconciler/Trigger.java`, `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceEvent.java`, `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceMapper.java`, `operator/framework/src/test/java/.../TriggerTest.java`, `.../ResourceEventTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 2. **Update Request with optional trigger(s)**

  **What to do**:
  - Modify `com.huawei.dcs.modelengine.operator.framework.reconciler.Request` to carry a `Trigger` (or `List<Trigger> triggers`).
  - Preserve `namespace()` and `name()` as the primary resource identity for backward compatibility.
  - Ensure `equals()` and `hashCode()` are based on the primary key (namespace + name), **NOT** on the trigger list. This is critical for queue coalescing.
  - Add a constructor `Request(String namespace, String name, Trigger trigger)` and `Request(String namespace, String name, List<Trigger> triggers)`.
  - Keep the existing `Request(String namespace, String name)` constructor.
  - Add `triggeredByPrimary()` convenience method and `trigger()` / `triggers()` accessors.

  **Must NOT do**:
  - Do not change the existing primary-key-based equality semantics.
  - Do not make `Request` mutable. If coalescing needs to accumulate triggers, create a new `Request` instance on merge rather than mutating.

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`tdd`] — test equality and constructors first.

  **Parallelization**:
  - **Can Run In Parallel**: YES — with T1, T3, T4.
  - **Parallel Group**: Wave 1.
  - **Blocks**: T5, T6, T7, T9.
  - **Blocked By**: None.

  **References**:
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/reconciler/Request.java` — file to modify.
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/reconciler/RequestTest.java` — existing or new tests.

  **Acceptance Criteria**:
  - [ ] `new Request("default", "foo")` still works and has no trigger.
  - [ ] `new Request("default", "foo", trigger)` compiles and returns the trigger.
  - [ ] Two requests with the same namespace/name but different triggers are equal.
  - [ ] `mvn -f operator/framework/pom.xml test -Dtest=RequestTest` passes.

  **QA Scenarios**:
  ```
  Scenario: Request with and without trigger
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=RequestTest`.
    Expected Result: Tests assert equality by primary key, trigger list optional.
    Evidence: .sisyphus/evidence/task-2-request.log
  ```

  **Commit**: YES
  - Message: `feat(reconciler): add trigger support to Request`
  - Files: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/reconciler/Request.java`, `operator/framework/src/test/java/.../RequestTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 3. **Define ControllerRegistration, ControllerBuilder, and ControllerSources**

  **What to do**:
  - Promote the inner `ControllerRegistration` record in `Operator.java` to a public class/record in `com.huawei.dcs.modelengine.operator.framework` package.
  - Add `ControllerRegistration<P>` fields: primary resource class, `Reconciler<P>`, list of `SecondaryWatch<P, ?>` (or `ResourceEventSource<P, ?>`).
  - Create `ControllerBuilder<P>` fluent API with methods: `forResource(Class<P>)`, `withReconciler(Reconciler<P>)`, `owns(Class<S>)`, `watches(String name, Class<S>, ResourceMapper<S, P>)`, `build()`.
  - Create `ControllerSources<P>` interface for programmatic addition of secondary sources (used by the builder and possibly by advanced users).
  - Keep the builder focused: no label selectors or predicates in the first iteration; those can be added later.

  **Must NOT do**:
  - Do not expose internal queues or informer factories in the public API.
  - Do not introduce `ContextualReconciler` yet. The builder uses existing `Reconciler<P>`.

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`design-an-interface`, `tdd`] — design the builder API carefully, then test.

  **Parallelization**:
  - **Can Run In Parallel**: YES — with T1, T2, T4.
  - **Parallel Group**: Wave 1.
  - **Blocks**: T5, T6, T8.
  - **Blocked By**: None.

  **References**:
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/Operator.java` — existing `ControllerRegistration` inner record.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/reconciler/Reconciler.java` — interface used by the builder.

  **Acceptance Criteria**:
  - [ ] `ControllerBuilder.forResource(EchoResource.class).withReconciler(...).owns(Deployment.class).build()` compiles.
  - [ ] `ControllerBuilder` produces a `ControllerRegistration` with primary class, reconciler, and secondary watches.
  - [ ] `mvn -f operator/framework/pom.xml test -Dtest=ControllerBuilderTest` passes.

  **QA Scenarios**:
  ```
  Scenario: Build controller registration with secondary watches
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ControllerBuilderTest`.
    Expected Result: Builder produces a registration with one primary and two secondary watches.
    Evidence: .sisyphus/evidence/task-3-builder.log
  ```

  **Commit**: YES
  - Message: `feat(controller): add ControllerRegistration and ControllerBuilder`
  - Files: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/ControllerRegistration.java`, `.../ControllerBuilder.java`, `.../ControllerSources.java`, `.../SecondaryWatch.java` (optional), tests.
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 4. **Create built-in Mappers**

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.source.Mappers` utility class with static factory methods:

  - `Mappers.ownerReferences()` — maps a secondary resource to its controller owner (matching primary GVK). Returns `Collection<Request>`.
  - `Mappers.byLabel(String nameLabel)` — maps by a label containing the primary name. Assumes same namespace.
  - `Mappers.byLabel(String nameLabel, String namespaceLabel)` — maps by label containing both name and namespace.
  - `Mappers.byAnnotation(String nameAnnotation, String namespaceAnnotation)` — maps by annotation.

  **Must NOT do**:
  - Do not add complex generic predicates in this iteration.
  - Do not handle cross-cluster or cross-namespace owner references beyond what Kubernetes supports.

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`tdd`] — test mapper outputs against real Kubernetes object metadata.

  **Parallelization**:
  - **Can Run In Parallel**: YES — with T1, T2, T3.
  - **Parallel Group**: Wave 1.
  - **Blocks**: T9, T11.
  - **Blocked By**: None.

  **References**:
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/util/OwnerReferenceHelper.java` — existing owner reference utilities.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceMapper.java` — interface to implement against.

  **Acceptance Criteria**:
  - [ ] `Mappers.ownerReferences()` returns the correct primary request for a Deployment owned by an EchoResource.
  - [ ] `Mappers.byLabel("primary-name")` maps a ConfigMap to the primary name.
  - [ ] `mvn -f operator/framework/pom.xml test -Dtest=MappersTest` passes.

  **QA Scenarios**:
  ```
  Scenario: Owner reference mapper finds primary
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=MappersTest`.
    Expected Result: Owner-reference mapper extracts the controller owner and returns primary request.
    Evidence: .sisyphus/evidence/task-4-mappers.log
  ```

  **Commit**: YES
  - Message: `feat(source): add built-in owner-reference and label mappers`
  - Files: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/Mappers.java`, `operator/framework/src/test/java/.../MappersTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 5. **Refactor ResourceEventSource for primary/secondary modes**

  **What to do**:
  - Change `ResourceEventSource<T extends HasMetadata>` to a generic internal controller component that can represent either a primary source or a secondary source.
  - Add a `SourceConfiguration` record/class holding: resource class, role (PRIMARY/SECONDARY), optional `ResourceMapper`, and source name.
  - Keep `ResourceEventSource` responsible for creating the fabric8 informer, but translate informer events into `Request` objects using the mapper when it is a secondary source.
  - For primary events, map directly to a `Request` with a `Trigger` of role PRIMARY.
  - For secondary events, use `ResourceMapper.map()` to obtain a collection of primary requests, each with a `Trigger` of role SECONDARY.
  - Continue to expose the underlying `SharedIndexInformer` for primary resource lookups.

  **Must NOT do**:
  - Do not change the queue ownership model yet; the queue is still shared per controller.
  - Do not add retry/periodic resync logic beyond what already exists.

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`diagnose`] — may need to debug informer handler ordering.

  **Parallelization**:
  - **Can Run In Parallel**: NO — must wait for Wave 1.
  - **Parallel Group**: Wave 2.
  - **Blocks**: T6, T7, T9, T10.
  - **Blocked By**: T1, T2, T3.

  **References**:
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceEventSource.java` — file to modify.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/Operator.java` — how the source is constructed today.
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceEventSourceTest.java` — existing tests.

  **Acceptance Criteria**:
  - [ ] Primary events still produce `Request(namespace, name)` with a PRIMARY trigger.
  - [ ] Secondary events with an owner-reference mapper produce the expected primary request(s) with a SECONDARY trigger.
  - [ ] Existing `ResourceEventSourceTest` tests continue to pass.
  - [ ] `mvn -f operator/framework/pom.xml test -Dtest=ResourceEventSourceTest` passes.

  **QA Scenarios**:
  ```
  Scenario: Primary event generates request with primary trigger
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ResourceEventSourceTest`.
    Expected Result: Existing tests pass; primary events carry PRIMARY trigger.
    Evidence: .sisyphus/evidence/task-5-primary-trigger.log

  Scenario: Secondary event maps to primary request via owner reference
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ResourceEventSourceTest#secondaryEventMapsToOwner`.
    Expected Result: A Deployment event whose owner is an EchoResource yields a Request for that EchoResource with a SECONDARY trigger.
    Evidence: .sisyphus/evidence/task-5-secondary-owner.log
  ```

  **Commit**: YES
  - Message: `feat(source): support primary and secondary modes in ResourceEventSource`
  - Files: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceEventSource.java`, `.../SourceConfiguration.java`, `.../SourceRole.java`, `operator/framework/src/test/java/.../ResourceEventSourceTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 6. **Update Operator to wire multiple event sources into one queue**

  **What to do**:
  - Refactor `Operator` to manage a `Controller` object per registration (keep old behavior but structured internally).
  - `Operator.register(Class<P>, Reconciler<P>)` should build a default `ControllerRegistration` with only a primary source and call a new internal `register(ControllerRegistration<P>)` method.
  - `Operator.register(ControllerRegistration<P>)` becomes the new public entry point for advanced users.
  - For each registration, create a single `BlockingQueue<Request>` shared by all event sources of that controller.
  - Create primary and secondary `ResourceEventSource` instances from the registration and attach them to the same queue.
  - Update `runWorker()` to handle `Request` objects with triggers; when reconciling, pass the request to the reconciler.
  - Ensure the framework still calls `reconciler.reconcile(request, resource)` with the primary resource loaded from the primary informer store.

  **Must NOT do**:
  - Do not change the public `Operator.register(Class, Reconciler)` signature.
  - Do not remove the existing `runWorker()` loop semantics entirely; adapt them.
  - Do not expose internal event source queues in public API.

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`diagnose`, `tdd`] — integration work; verify existing tests still pass.

  **Parallelization**:
  - **Can Run In Parallel**: NO — must wait for Wave 1; can run in parallel with T5 if T5 and T6 teams coordinate on SourceConfiguration.
  - **Parallel Group**: Wave 2.
  - **Blocks**: T7, T8, T10, T11.
  - **Blocked By**: T3, T5.

  **References**:
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/Operator.java` — file to modify.
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/OperatorLauncherTest.java` — existing tests.
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/OperatorTest.java` — if exists.

  **Acceptance Criteria**:
  - [ ] `Operator.register(Class, Reconciler)` compiles and behaves as before.
  - [ ] `Operator.register(ControllerRegistration)` works and wires secondary sources.
  - [ ] `mvn -f operator/framework/pom.xml test -Dtest=OperatorLauncherTest` passes.
  - [ ] `mvn -f example/echo-operator/pom.xml test` passes after the framework change.

  **QA Scenarios**:
  ```
  Scenario: Backward-compatible registration still works
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=OperatorLauncherTest`.
    Expected Result: All existing tests pass with primary-only registration.
    Evidence: .sisyphus/evidence/task-6-backward-compat.log

  Scenario: Controller registration with secondary source wires informers
    Tool: Bash (mvn test)
    Steps:
      1. Add a test that builds a `ControllerRegistration` with a secondary Deployment watch.
      2. Run `mvn -f operator/framework/pom.xml test -Dtest=OperatorSecondaryWatchTest`.
    Expected Result: Operator accepts the registration and creates both informers.
    Evidence: .sisyphus/evidence/task-6-secondary-watch.log
  ```

  **Commit**: YES
  - Message: `feat(operator): wire multiple event sources into shared controller queue`
  - Files: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/Operator.java`, `operator/framework/src/test/java/.../OperatorLauncherTest.java`, `.../OperatorSecondaryWatchTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 7. **Implement queue coalescing with trigger accumulation**

  **What to do**:
  - Introduce a `ReconciliationQueue` or utility inside `Operator` that wraps `BlockingQueue<Request>` and deduplicates by primary key.
  - When a new `Request` arrives for the same primary key as an already-queued request, merge the triggers rather than enqueuing a duplicate.
  - Preserve the original event type semantics if possible (e.g., a DELETE trigger should not be coalesced away if it is still pending).
  - Keep the existing `Request` immutable; the merge must produce a new `Request` with the union of triggers.
  - Use a concurrent `Map<Request, Request>` to track pending requests, or use a specialized queue implementation.

  **Must NOT do**:
  - Do not drop DELETE triggers during coalescing.
  - Do not make the queue unbounded in production without documenting memory risk.

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: [`tdd`] — test coalescing edge cases.

  **Parallelization**:
  - **Can Run In Parallel**: NO — depends on T2 and T6.
  - **Parallel Group**: Wave 2.
  - **Blocks**: T9, T11.
  - **Blocked By**: T2, T6.

  **References**:
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/Operator.java` — queue logic.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/reconciler/Request.java` — equality and trigger handling.

  **Acceptance Criteria**:
  - [ ] Two requests for the same primary key are coalesced into one.
  - [ ] Coalesced request contains triggers from both original requests.
  - [ ] A DELETE trigger is not lost during coalescing.
  - [ ] `mvn -f operator/framework/pom.xml test -Dtest=ReconciliationQueueTest` passes.

  **QA Scenarios**:
  ```
  Scenario: Multiple secondary events coalesce into one request with multiple triggers
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ReconciliationQueueTest`.
    Expected Result: Tests demonstrate coalescing and trigger accumulation.
    Evidence: .sisyphus/evidence/task-7-coalescing.log

  Scenario: DELETE trigger is preserved after coalescing with UPDATE triggers
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ReconciliationQueueTest#deleteNotLost`.
    Expected Result: DELETE trigger remains in the merged request.
    Evidence: .sisyphus/evidence/task-7-delete-preserved.log
  ```

  **Commit**: YES
  - Message: `feat(operator): coalesce requests by primary key and accumulate triggers`
  - Files: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/Operator.java` (or new `ReconciliationQueue.java`), `.../ReconciliationQueueTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 8. **Ensure backward compatibility for existing register(Class, Reconciler)**

  **What to do**:
  - Add a compatibility test that uses the exact old API (`new Operator(...).register(EchoResource.class, reconciler); operator.start();`) and asserts that the reconciler is still invoked on primary events.
  - Verify the method signature is unchanged.
  - Ensure the default event source behavior (one informer per primary resource) is preserved.
  - If any existing tests were broken by T6, fix them here.

  **Must NOT do**:
  - Do not change the return type of `Operator.register(Class, Reconciler)`.
  - Do not require new imports for existing users.

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`tdd`] — compatibility regression test.

  **Parallelization**:
  - **Can Run In Parallel**: NO — depends on T6.
  - **Parallel Group**: Wave 2.
  - **Blocks**: T10.
  - **Blocked By**: T6.

  **References**:
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/OperatorLauncherTest.java` — existing compatibility tests.
  - `example/echo-operator/src/test/java/...` — real-world usage.

  **Acceptance Criteria**:
  - [ ] Existing `OperatorLauncherTest` still passes without modification.
  - [ ] `mvn -f example/echo-operator/pom.xml test` passes.
  - [ ] New `OperatorBackwardCompatibilityTest` passes.

  **QA Scenarios**:
  ```
  Scenario: Old register API behaves identically
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=OperatorBackwardCompatibilityTest`.
    Expected Result: Reconciler is invoked exactly as before.
    Evidence: .sisyphus/evidence/task-8-backward-compat.log
  ```

  **Commit**: YES
  - Message: `test(operator): verify backward compatibility of register API`
  - Files: `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/OperatorBackwardCompatibilityTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`



- [x] 9. **Write unit tests for secondary events, mappers, and triggers**

  **What to do**:
  - Create focused unit tests for each new API surface:
    - `TriggerTest`
    - `ResourceEventTest`
    - `ResourceMapperTest`
    - `MappersTest`
    - `RequestTriggerTest`
  - Add integration-style tests using mocked informers that fire secondary events and assert the resulting primary `Request` and triggers.
  - Cover edge cases: missing owner reference, label mismatch, empty mapper result, multiple secondaries mapping to same primary, DELETE event on secondary.

  **Must NOT do**:
  - Do not add tests that require a real Kubernetes cluster.
  - Do not test internal implementation details not exposed by the API.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: [`tdd`] — test-first for new behavior.

  **Parallelization**:
  - **Can Run In Parallel**: NO — needs Wave 1 and Wave 2 types.
  - **Parallel Group**: Wave 3.
  - **Blocks**: F1, F2, F3.
  - **Blocked By**: T1, T2, T4, T5.

  **References**:
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/...` — existing JUnit 5 + Mockito test patterns.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/Mappers.java` — mappers to test.
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/reconciler/Request.java` — equality semantics to test.

  **Acceptance Criteria**:
  - [ ] New tests cover at least 80% of the new lines (Jacoco if available).
  - [ ] `mvn -f operator/framework/pom.xml test` passes.
  - [ ] Edge cases (missing owner, label mismatch, multiple secondaries, delete) are explicitly tested.

  **QA Scenarios**:
  ```
  Scenario: Missing owner reference returns empty mapper result
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=MappersTest#missingOwnerReturnsEmpty`.
    Expected Result: Mapper returns empty collection, no primary request enqueued.
    Evidence: .sisyphus/evidence/task-9-missing-owner.log

  Scenario: Multiple secondary resources map to same primary
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=SecondaryEventIntegrationTest`.
    Expected Result: Coalescing accumulates triggers from both secondary resources.
    Evidence: .sisyphus/evidence/task-9-multiple-secondaries.log
  ```

  **Commit**: YES
  - Message: `test(operator): add unit tests for secondary events, mappers, and triggers`
  - Files: `operator/framework/src/test/java/.../TriggerTest.java`, `.../ResourceEventTest.java`, `.../ResourceMapperTest.java`, `.../MappersTest.java`, `.../RequestTriggerTest.java`, `.../SecondaryEventIntegrationTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 10. **Update OperatorLauncherTest and ResourceEventSourceTest**

  **What to do**:
  - Review existing `OperatorLauncherTest` and `ResourceEventSourceTest` for assumptions that may have changed (e.g., queue type, Request equality, trigger presence).
  - Update any assertions that need to account for the new `Request` trigger field without changing the test semantics.
  - If a test was asserting `equals` on a `Request` with only namespace/name, ensure it still passes with the new constructor defaults.
  - Add new test cases to the existing test classes for secondary resource behavior.

  **Must NOT do**:
  - Do not delete existing tests; update them if needed.
  - Do not change the public API just to make tests pass.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: [`tdd`, `diagnose`] — fix regressions from Wave 2.

  **Parallelization**:
  - **Can Run In Parallel**: NO — depends on T6 and T8.
  - **Parallel Group**: Wave 3.
  - **Blocks**: F1, F2, F3.
  - **Blocked By**: T6, T8.

  **References**:
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/OperatorLauncherTest.java`
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceEventSourceTest.java`

  **Acceptance Criteria**:
  - [ ] `OperatorLauncherTest` passes without regressions.
  - [ ] `ResourceEventSourceTest` passes without regressions.
  - [ ] New secondary-resource test cases added.

  **QA Scenarios**:
  ```
  Scenario: OperatorLauncherTest still passes after refactor
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=OperatorLauncherTest`.
    Expected Result: All tests pass.
    Evidence: .sisyphus/evidence/task-10-operator-launcher.log

  Scenario: ResourceEventSourceTest covers secondary event
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ResourceEventSourceTest`.
    Expected Result: Existing tests pass; new secondary-event test passes.
    Evidence: .sisyphus/evidence/task-10-resource-event-source.log
  ```

  **Commit**: YES
  - Message: `test(operator): update existing tests and add secondary coverage`
  - Files: `operator/framework/src/test/java/.../OperatorLauncherTest.java`, `.../ResourceEventSourceTest.java`
  - Pre-commit: `mvn -f operator/framework/pom.xml test`


- [x] 11. **Add example EchoReconciler secondary watch demo**

  **What to do**:
  - Update the `echo-operator` example to demonstrate a secondary ConfigMap watch using `ControllerBuilder`.
  - The reconciler should log or assert that the `Request` trigger indicates a ConfigMap event when appropriate.
  - Keep the example simple and self-contained; it should not require a real cluster.
  - Add a test that verifies the secondary watch is registered and that the trigger is present.

  **Must NOT do**:
  - Do not make the example production-grade (no complex business logic).
  - Do not break the existing `EchoOperatorMain` flow.

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO — depends on T6 and T7.
  - **Parallel Group**: Wave 3.
  - **Blocks**: F3 (final QA).
  - **Blocked By**: T6, T7.

  **References**:
  - `example/echo-operator/src/main/java/com/example/echooperator/controller/EchoReconciler.java`
  - `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java`
  - `example/echo-operator/src/test/java/...`

  **Acceptance Criteria**:
  - [ ] `EchoReconciler` has a commented, minimal secondary watch example.
  - [ ] New or updated test in `echo-operator` demonstrates the trigger inspection.
  - [ ] `mvn -f example/echo-operator/pom.xml test` passes.

  **QA Scenarios**:
  ```
  Scenario: EchoOperator example demonstrates secondary watch
    Tool: Bash (mvn test)
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoOperatorSecondaryWatchTest`.
    Expected Result: Test registers a secondary ConfigMap watch and asserts the trigger is present.
    Evidence: .sisyphus/evidence/task-11-echo-example.log
  ```

  **Commit**: YES
  - Message: `docs(example): demonstrate secondary watch in echo-operator`
  - Files: `example/echo-operator/src/main/java/com/example/echooperator/controller/EchoReconciler.java`, `example/echo-operator/src/test/java/.../EchoOperatorSecondaryWatchTest.java`
  - Pre-commit: `mvn -f example/echo-operator/pom.xml test`


- [x] 12. **Update developer guide and framework README**

  **What to do**:
  - Add a new section to the developer guide explaining primary vs. secondary resources.
  - Document the `ControllerBuilder` API and how to use `owns` and `watches`.
  - Document the `Trigger` object and how a reconciler can inspect it to determine what changed.
  - Add a short migration note: existing `Operator.register(Class, Reconciler)` users do not need to change anything.
  - Update the framework README with a minimal example of secondary resource watching.

  **Must NOT do**:
  - Do not duplicate the existing Javadoc; focus on high-level usage patterns.
  - Do not write docs that are not backed by the code in this plan.

  **Recommended Agent Profile**:
  - **Category**: `writing`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO — depends on T10 and T11 for code stability.
  - **Parallel Group**: Wave 3.
  - **Blocks**: F4 (user review).
  - **Blocked By**: T10, T11.

  **References**:
  - `docs/dev-guide.md` or equivalent documentation directory.
  - `operator/framework/README.md`
  - Design rationale is captured in the "Context / Design Decision" section of this plan; no additional draft file is needed.

  **Acceptance Criteria**:
  - [ ] Developer guide has a new "Primary and Secondary Resources" section.
  - [ ] README has a minimal `ControllerBuilder` example.
  - [ ] Migration note is clear and accurate.

  **QA Scenarios**:
  ```
  Scenario: Docs reflect the new API
    Tool: Bash (read + grep)
    Steps:
      1. `grep -R "ControllerBuilder" docs/ operator/framework/README.md`
      2. `grep -R "Trigger" docs/ operator/framework/README.md`
    Expected Result: Both terms appear in relevant documentation sections with usage examples.
    Evidence: .sisyphus/evidence/task-12-docs-check.log
  ```

  **Commit**: YES
  - Message: `docs: add primary/secondary resources guide and migration note`
  - Files: `docs/dev-guide.md`, `operator/framework/README.md`
  - Pre-commit: N/A (docs only, but run `mvn test` if code files are touched)


---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.
> Do NOT auto-proceed after verification. Wait for user's explicit approval before marking work complete.

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, run test, inspect API). For each "Must NOT Have": search codebase for forbidden patterns (e.g., `ResourceCache` abstraction, `ContextualReconciler`, public queue exposure). Check evidence files exist in `.sisyphus/evidence/`. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | Evidence [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `mvn -f operator/framework/pom.xml test` and `mvn -f example/echo-operator/pom.xml test`. Review all changed files for: `// TODO` without ticket, empty catch blocks, `System.out` in production code, dead imports, generic names (`data`, `result`, `item`). Check AI slop: excessive comments, over-abstraction, non-idiomatic Java.
  Output: `Tests [PASS/FAIL] | Lint [PASS/FAIL] | Files [N clean/N issues] | VERDICT`

- [x] F3. **Real Manual QA** — `unspecified-high`
  Start from clean state. Execute EVERY QA scenario from EVERY task — follow exact steps, capture evidence. Test cross-task integration: primary event + secondary event coalescing in the same queue. Test edge cases: missing owner reference, label mismatch, secondary delete, rapid duplicate events. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (`git diff`). Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Detect cross-task contamination: Task N touching Task M's files. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- Wave 1: 4 atomic commits (model, Request, builder, mappers).
- Wave 2: 4 atomic commits (source refactor, Operator wiring, queue coalescing, backward-compat test).
- Wave 3: 4 atomic commits (unit tests, existing test updates, echo example, docs).
- Wave 4: no commits; verification only.
- Example commit: `feat(controller): add ControllerRegistration and ControllerBuilder`
- Pre-commit: `mvn -f operator/framework/pom.xml test` for code changes; `mvn -f example/echo-operator/pom.xml test` for example changes.

---

## Success Criteria

### Verification Commands

```bash
# Framework unit tests
mvn -f operator/framework/pom.xml test

# Example operator tests
mvn -f example/echo-operator/pom.xml test

# Spot check for forbidden patterns
grep -R "class ResourceCache" operator/framework/src/main/java || echo "ResourceCache not found"
grep -R "interface ContextualReconciler" operator/framework/src/main/java || echo "ContextualReconciler not found"

# Backward compatibility compile check
javac -cp "operator/framework/target/classes" -d /tmp/compat example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java
```

### Final Checklist

- [x] All "Must Have" items present
- [x] All "Must NOT Have" items absent
- [x] `mvn -f operator/framework/pom.xml test` passes
- [x] `mvn -f example/echo-operator/pom.xml test` passes
- [x] `Operator.register(Class, Reconciler)` unchanged and backward-compatible
- [x] New `ControllerBuilder` and `ControllerRegistration` APIs documented
- [x] Evidence files exist in `.sisyphus/evidence/` for all QA scenarios
- [x] User has explicitly approved the final verification results

