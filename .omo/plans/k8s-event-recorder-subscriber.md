# k8s-event-recorder-subscriber - Work Plan

## TODOs

- [x] 1. T1: Create `EventRecorder` for publishing Kubernetes Events
- [x] 2. T2: Create `EventSubscriber` and `EventMapper` for watching Kubernetes Events
- [x] 3. T3: Wire `EventSubscriber` into `ControllerBuilder` and `ControllerSources`
- [x] 4. T4: Run full test suite and verify coverage
- [x] 5. T5: Update Echo operator example to demonstrate `EventRecorder`
- [x] 6. T6: Run final build and test verification

## Final Verification Wave Checklist

- [x] F1. Plan compliance audit
- [x] F2. Code quality review
- [x] F3. Automated build QA
- [x] F4. Scope fidelity

## TL;DR (For humans)

This plan adds Kubernetes `core/v1 Event` creation and subscription capabilities to the operator framework.

**What you'll get:**
- A public `EventRecorder` class in `com.huawei.dcs.modelengine.operator.framework.event` that lets reconcilers publish `Normal`/`Warning` Kubernetes Events tied to a primary resource (e.g., `recorder.normal(resource, "Created", "Deployment created")`).
- A public `EventSubscriber<P>` class that watches Kubernetes Events whose `involvedObject` points to the primary resource type `P`, and enqueues the corresponding primary reconciliation requests.
- `ControllerBuilder.withEventSubscriber(EventSubscriber)` wiring so users can attach an event watcher to a controller registration.
- Unit tests and an updated Echo operator example that emits Normal/Warning events.

**Why this approach:**
- It mirrors the controller-runtime pattern (event recorder + event source) while reusing the framework's existing secondary-watch infrastructure (`SecondaryWatch`, `ResourceMapper`, `ResourceEventSource`).
- The `EventSubscriber` is implemented as a dedicated wrapper around a secondary watch, so it is discoverable without duplicating informer logic.
- `EventRecorder` performs client-side pre-aggregation before calling the API server: the first identical event (same `source.component`, `involvedObject` UID, type, reason, and message) creates a new `core/v1` `Event` with `count = 1` and resets a per-key `pendingCount` to `0`; each repeat within the configurable suppression window (default 5 minutes) increments `pendingCount` locally and makes no API call; when the window elapses because a new identical event arrives, the recorder sends a single JSON merge patch (`application/merge-patch+json`) that sets `count = serverCount + pendingCount + 1` and refreshes `lastTimestamp`, then resets `pendingCount` to `0`; when the window elapses because the scheduled flush fires (no new event), the recorder patches `count = serverCount + pendingCount` instead. The cached `resourceVersion` from the last successful write is used to make the patch target-safe; on 409 the implementation fetches the latest event and retries once. A background flush task ensures counts are not lost if no later duplicate arrives.

**What it will NOT do:**
- It does not add a generic event broadcaster or event bus outside of Kubernetes Events.
- It does not change the existing primary/secondary watch internals or event-enqueue semantics.
- It does not change webhook, certificate, metrics, leader-election, or dependency versions.

**Effort:** ~5-6 medium todos, mostly additive with a small API surface change in `ControllerBuilder`/`ControllerSources`.

- **Risk:** Low-Medium. All changes are additive, but `EventRecorder` must be thread-safe (it is called from multiple reconciler worker threads), must correctly handle namespaces for cluster-scoped resources, and must avoid Event name collisions. There is also a self-triggering reconcile-loop hazard if a controller subscribes to its own recorder's Events.

**Decisions made:**
- Both create and subscribe capabilities are included.
- `EventSubscriber` is a dedicated class wrapping the existing secondary watch mechanism.
- Event namespace: primary resource namespace for namespaced resources; default namespace for cluster-scoped resources. The `EventRecorder` default namespace is `client.getNamespace()` unless overridden by the constructor.
- Event aggregation is included, keyed by `source.component + involvedObject.metadata.uid + type + reason + message`.
- Event names are sanitized and truncated to Kubernetes metadata-name limits (max 63 chars, DNS subdomain label characters).
- `EventRecorder` stores the server-returned `resourceVersion` and `count` after each successful create or patch so the next aggregation patch is target-safe, and uses a scheduled flush to ensure locally-pending counts are persisted even if no later duplicate event arrives.
- The framework targets `core/v1` Event first (instead of `events.k8s.io/v1` EventSeries) because Fabric8's default `Event` model and existing informers are built around core/v1; `events.k8s.io/v1` support is left as a future escalation.
- Event storm prevention is included: a client-side suppression window suppresses repeated identical events within a configurable interval (default 5 minutes), and the recorder pre-aggregates locally before sending to the API server.

## Scope

**IN scope:**
- New package `com.huawei.dcs.modelengine.operator.framework.event` containing:
  - `EventRecorder` — creates Kubernetes Events for a given involved object.
  - `EventSubscriber<P>` — watches Kubernetes Events and enqueues primary requests.
  - `EventMapper` — helpers for mapping Events to primary requests (e.g., by `involvedObject`).
- API additions:
  - `ControllerSources.withEventSubscriber(EventSubscriber)`.
  - `ControllerBuilder.withEventSubscriber(EventSubscriber)`.
- Unit tests:
  - EventRecorderTest also covers event storm prevention / suppression window behavior.
  - `EventSubscriberTest` covering mapping by `involvedObject` and filtering by kind/apiVersion.
- Example update:
  - `EchoReconciler` emits a Normal event on success and a Warning event on failure.
  - `EchoOperatorMain` constructs and passes an `EventRecorder` to the reconciler.
  - Commented-out example of `ControllerBuilder.withEventSubscriber(...)` in `EchoOperatorMain`.
- Test-scoped dependency addition: `io.fabric8:kubernetes-server-mock` (version `${fabric8.version}`, scope `test`) is added to `operator/framework/pom.xml` to support `MockKubernetesServer` tests. This is exempt from the "no dependency version changes" rule because it is a new test-only dependency, not a version bump of an existing production dependency.

**OUT of scope (Must-NOT-Have):**
- Changes to dependency versions (fabric8, micrometer, bouncycastle remain as previously adjusted: 7.3.0, 1.16.1, 1.84). Adding a new test-scoped `kubernetes-server-mock` dependency (version `${fabric8.version}`) is allowed as a test-only addition.
- Changes to webhook, certificate, metrics, leader-election, or health server code.
- A generic event bus or broadcast outside Kubernetes Events.
- Event aggregation beyond the deterministic `source.component + involvedObject UID + type + reason + message` key.
- Full controller-runtime `EventBroadcaster` semantics (e.g., event sink, rate limiting, or batching).

## Verification strategy

- **Unit tests with `MockKubernetesServer`** from fabric8 are the primary verification mechanism. They test real API calls without requiring a real cluster. The framework `pom.xml` must include a test-scoped dependency on `io.fabric8:kubernetes-server-mock` with version `${fabric8.version}` to make these tests compile and run.
- **Integration test pattern:** `EventRecorder` tests will assert the `Event` list in the mock server after publishing events.
- **Map-only tests:** `EventSubscriber` tests use plain Java objects because they only transform `Event` into `Request`.
- **Build verification:** after all changes, `mvn -f operator/framework/pom.xml -DskipTests install` must pass for the framework module and `mvn -f example/echo-operator/pom.xml -DskipTests package` must pass for the example module.
- **Test verification:** `mvn -f operator/framework/pom.xml test` must pass for the framework module.

## Execution strategy

Implement in dependency order:

1. **Core event classes first:** `EventRecorder`, `EventSubscriber`, `EventMapper`. They have no dependencies on the framework builder classes.
2. **Builder wiring next:** add `withEventSubscriber` to `ControllerSources` and `ControllerBuilder`. This depends on `EventSubscriber`.
3. **Tests next:** add `EventRecorderTest` and `EventSubscriberTest` using the mock server and plain object tests.
4. **Example update last:** modify `EchoReconciler` and `EchoOperatorMain` to demonstrate the new capability.
5. **Final verification:** run the full build and test suites.

- No new production dependencies are required. The only new dependency is test-scoped `io.fabric8:kubernetes-server-mock` (version `${fabric8.version}`) for `MockKubernetesServer` tests. The existing fabric8 client already provides `io.fabric8.kubernetes.api.model.Event` and `EventBuilder` for production code.

## Todos

### T1: Create `EventRecorder` for publishing Kubernetes Events

**References:**
- New file: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/event/EventRecorder.java`
- Fabric8 classes: `io.fabric8.kubernetes.api.model.Event`, `io.fabric8.kubernetes.api.model.EventBuilder`, `io.fabric8.kubernetes.api.model.ObjectReference`, `io.fabric8.kubernetes.api.model.ObjectReferenceBuilder`
- Fabric8 client API: `client.v1().events().inNamespace(...)` for core/v1 Events (note: in Fabric8 7.3.0, `client.events()` targets the `events.k8s.io` group, not core/v1).
- Fabric8 resource metadata: `io.fabric8.kubernetes.api.model.HasMetadata` for instance-level `getKind()` and `getApiVersion()`
- Existing metadata helper style: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/util/OwnerReferenceHelper.java`
- Existing null/validation style: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/Mappers.java`

**Acceptance criteria:**
- `EventRecorder` class is public and lives in `...framework.event`.
- Constructor signatures: `EventRecorder(KubernetesClient, String componentName)`, `EventRecorder(KubernetesClient, String componentName, String defaultNamespace)`, `EventRecorder(KubernetesClient, String componentName, String defaultNamespace, Duration suppressionInterval)`, and a package-private/test-visible constructor that also accepts `Supplier<Instant>` (clock), `ScheduledExecutorService` (flush executor), and `int maxCacheEntries` for deterministic testing. The public constructors default `maxCacheEntries` to 1000.
- `EventRecorder` implements `AutoCloseable`. `close()` synchronously flushes every cache entry with `pendingCount > 0` to the API server using the same per-key serialization as normal and scheduled flushes, then cancels the scheduled flush task. `close()` is idempotent: calling it more than once is safe and does not throw. If the recorder created the internal `ScheduledExecutorService`, `close()` also shuts it down after the flush; if an external executor was supplied via the test-visible constructor, `close()` does NOT shut it down (the caller owns it). The internal executor uses a daemon thread so it does not block JVM exit. The package-private/test-visible constructor accepts an external `ScheduledExecutorService` and a `Supplier<Instant>` for deterministic testing.
- The first constructor defaults `defaultNamespace` to `client.getNamespace()` (or `"default"` if blank) and `suppressionInterval` to 5 minutes.
- The second constructor explicitly sets `defaultNamespace` and keeps the default 5-minute suppression interval.
- The third constructor explicitly sets both `defaultNamespace` and `suppressionInterval`.
- Public methods: `normal(HasMetadata, String, String)`, `warning(HasMetadata, String, String)`, `event(HasMetadata, String, String, String)`.
- The created `Event` has `involvedObject` populated with `apiVersion`, `kind`, `name`, `namespace`, and `uid` from the involved object.
- The created `Event` has `metadata.name` derived deterministically from the aggregation key: `\u003csanitized-involvedObject-name\u003e-\u003cfirst-8-chars-of-sha256(source.component + "|" + involvedObject.metadata.uid + "|" + type + "|" + reason + "|" + message)\u003e`. The name is sanitized to lowercase DNS subdomain label characters, and the base name is truncated so that the final name (including the `-\u003chash\u003e` suffix) does not exceed 63 characters. The full 8-character hash suffix is always preserved.
- Event namespace: if `involvedObject.metadata.namespace` is non-null and non-blank, use it; otherwise use `defaultNamespace`. This rule is the same for both namespaced and cluster-scoped resources; the recorder does not attempt to discover scope from the class or API discovery. The `defaultNamespace` constructor parameter defaults to `client.getNamespace()` (or `"default"` if blank).
- `eventTime` and `firstTimestamp`/`lastTimestamp` are populated with the current UTC time.
- `source.component` is set to the `componentName` passed to the constructor.
- Pre-aggregation (event-driven flush after suppression window): when a new identical event arrives after `suppressionInterval` has passed, the recorder patches the existing `Event` with a JSON merge patch (`application/merge-patch+json`) that sets `count = serverCount + pendingCount + 1` and `lastTimestamp = now`, then resets `pendingCount = 0`. The patch carries the cached `resourceVersion` from the last successful write; if the server returns 409, the recorder fetches the latest event and retries once. If the deterministic Event no longer exists at flush time (deleted or TTL expired), the recorder creates a new Event with `count = pendingCount + 1` instead of failing.
- Pre-aggregation (scheduled flush): a scheduled flush task runs periodically (default interval = `suppressionInterval`) and flushes any entry whose `lastEmitTime + suppressionInterval` has passed. The flush patches the existing `Event` with `count = serverCount + pendingCount` and `lastTimestamp = now`, then resets `pendingCount = 0`. If the Event no longer exists, the recorder creates a new Event with `count = pendingCount` (not `serverCount + pendingCount`, because the old server count is lost). This ensures locally-pending counts are persisted even if no later duplicate event arrives.
- Null-checking: `null` involved object, `null` type, or `null` reason throw `NullPointerException` with clear messages. `null` message is treated as an empty string `""` (or a non-null fallback) so that downstream callers such as `warning(..., exception.getMessage())` do not fail when the exception message is null.
- `involvedObject.metadata.uid` must be non-null and non-blank when an event is emitted; otherwise `IllegalArgumentException` is thrown with a clear message. This ensures the aggregation key, suppression key, and deterministic Event name are well-defined.
- Event storm prevention and pre-aggregation: an internal bounded cache records, per aggregation key, `lastEmitTime`, `pendingCount`, `resourceVersion`, and `serverCount`. The cache key includes `source.component` so Events from different recorders/components do not collide. The cache is thread-safe and all per-key read/update/create/patch operations are serialized for a given key. If the same key is emitted again within `suppressionInterval`, only `pendingCount` is incremented (no API call). The cache is bounded to a maximum of `maxCacheEntries` entries (default 1000, configurable via the test-visible constructor); when the size exceeds the limit, the oldest entry is evicted after first flushing any `pendingCount > 0` for that entry. On each event call, entries older than `suppressionInterval * 2` are also evicted before inserting a new entry, to avoid memory leaks; if an evicted entry has `pendingCount > 0`, it is flushed before removal. After a successful create or patch, the cache entry is updated with the server-returned `resourceVersion` and `count`. A scheduled flush task runs periodically (default interval = `suppressionInterval`) and flushes any entry whose `lastEmitTime + suppressionInterval` has passed, ensuring pending counts are persisted even if no later duplicate event arrives.
- Testability seam: `EventRecorder` accepts an optional `Supplier<Instant>` (or `Clock`) in a package-private or test-visible constructor. Tests use this seam to advance time deterministically instead of relying on real `Thread.sleep`, eliminating flakiness in suppression-window tests.
- Missing Event at flush time: if the deterministic Event no longer exists when the recorder tries to patch it, the recorder creates a new Event (count = `pendingCount + 1` for event-driven flush, count = `pendingCount` for scheduled flush) rather than failing permanently.
- Thread-safety: `EventRecorder.event(...)` must be safe to call concurrently from multiple reconciler worker threads. All mutable state (cache, flush executor) is either thread-safe or serialized per aggregation key.
- RBAC / documentation: the operator service account must have `create`/`get`/`patch` permissions on `events` in the relevant namespace scope. `EventSubscriber` requires `list`/`watch` permissions on `events`. These requirements are documented in the Javadoc of `EventRecorder` and `EventSubscriber` and in the example README.
- Create idempotency (`AlreadyExists`): if the initial `Create` for a deterministic Event name returns `AlreadyExists` (because the Event already exists after cache eviction, recorder restart, or concurrent creation), the recorder fetches the existing `Event` and patches it with `count = existing.count + pendingCount + 1` and `lastTimestamp = now`. It then updates the cache with the server-returned `resourceVersion` and `count`. If the patch returns 409, the recorder fetches the latest event and retries once.

**Happy QA:**
- Setup: `MockKubernetesServer` in `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/event/EventRecorderTest.java`.
- Action 1: create a `Deployment` in namespace `test` with `metadata.uid` set to a non-blank value, then call `recorder.normal(deployment, "Created", "Deployment created")`.
- Verify 1: `client.v1().events().inNamespace("test").list().getItems()` has one event with:
  - `type = "Normal"`
  - `reason = "Created"`
  - `message = "Deployment created"`
  - `count = 1`
  - `involvedObject` matching the deployment's apiVersion/kind/name/namespace/uid
- Action 2: call `recorder.normal(deployment, "Created", "Deployment created")` a second time immediately after the first call.
- Verify 2: only one event exists on the server and `count` is still `1`, because the second call was suppressed by the 5-minute suppression window; the local `pendingCount` for this key has been incremented to `1`.
- Action 3: construct the same `EventRecorder` with a test-visible `Supplier<Instant>` clock and a `suppressionInterval` of 10 milliseconds, emit the first event, advance the clock by 20 milliseconds, then emit the same event again.
- Verify 3: the existing event is fetched from the server, then patched to `count = 2` and `lastTimestamp` is updated, using the cached `resourceVersion` from the first create. This verifies both cache expiry and aggregation after the suppression window.
- Action 4: emit `recorder.normal(deployment, "Created", "Deployment created")` and then emit `recorder.normal(deployment, "Created", "Deployment updated")`.
- Verify 4: two distinct events exist on the server (different aggregation keys), confirming that suppression is per-key and not global.
- Action 5: emit an event with a message containing uppercase letters and special characters, then verify the resulting `Event.metadata.name` contains only lowercase DNS-subdomain characters, is at most 63 characters long, and ends with the full 8-character hash suffix.
- Verify 5: the sanitization and truncation rules are satisfied.
- Action 6: emit more than 1000 distinct events (distinct messages) and then emit a 1001st event.
- Verify 6: no `OutOfMemoryError` occurs and the cache size stays bounded by the configured maximum (assert via the package-private or test-visible cache-size seam).
- Action 7: create a cluster-scoped `Node` (no namespace) with `metadata.uid` set to a non-blank value and call `recorder.normal(node, "Synced", "Node synced")`.
- Verify 7: the event is created in the recorder's default namespace.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest`

- Action 8: set up the `MockKubernetesServer` in expectations mode and program it so that the first JSON merge patch for the deterministic Event returns HTTP 409 with a Status object. Program a GET for the deterministic Event to return the latest server state with `count = 3` and `resourceVersion = "2"`. Program the second PATCH to return HTTP 200 only if the request body uses `resourceVersion = "2"` and sets `count = 5` (latest `count = 3` + `pendingCount = 1` + 1 for the new event occurrence). Alternatively, use a test-visible seam that records the exact retry patch body and resourceVersion. Do not rely on external modification of the event between calls because CRUD mock mode does not reliably emulate Kubernetes `resourceVersion` concurrency.
- Verify 8: the recorder sends the first PATCH with the stale cached resourceVersion, receives 409, performs a GET to fetch the latest event, then sends a second PATCH whose body uses the latest resourceVersion and computes `count = latest.count + pendingCount + 1`. This proves the retry path uses the fresh resourceVersion and correct arithmetic. This verifies the 409 retry path.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest`
- Action 9: emit the first event, then emit three more identical events within the suppression window, then advance the clock past the window and emit the same event again.
- Verify 9: the existing event is patched to `count = 5` (1 original + 3 suppressed + 1 new flush event) and `lastTimestamp` is updated. This verifies that multiple suppressed events are accumulated and flushed in one patch.
- Action 10: emit the first event, then emit two identical events within the suppression window, then advance the clock past the window without emitting another event and trigger the scheduled flush (using the test-visible flush executor).
- Verify 10: the existing event is patched to `count = 3` (1 original + 2 suppressed) by the scheduled flush, even though no new event arrived. This verifies the scheduled flush path.
- Action 11: delete the deterministic Event from the server after the first create, then emit one suppressed event and advance the clock past the window for the scheduled flush.
- Verify 11: the recorder creates a new Event with `count = 1` (the one suppressed event, because the scheduled flush does not add a new event occurrence) instead of failing, because the old Event was gone. This verifies the missing-Event fallback for scheduled flush.
- Action 12: emit the same event concurrently from 10 threads within the suppression window.
- Verify 12: only one Event is created on the server with `count = 1`, and no exceptions or duplicate creates occur. This verifies per-key thread-safety.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest`
- Action 13: call `recorder.warning(deployment, "Failed", null)` where the third argument (message) is `null`.
- Verify 13: the created Event has `message = ""` (empty string) and does not throw; this verifies the null-message fallback used by callers that pass `exception.getMessage()`.
- Action 14: create an `EventRecorder` with a test-visible `ScheduledExecutorService`, emit one event, then call `recorder.close()`.
- Verify 14: the recorder's own flush task is cancelled, and the external `ScheduledExecutorService` is still running (not shut down by the recorder). This verifies the `AutoCloseable` lifecycle for an externally-owned executor.
- Action 14b: emit two identical events within the suppression window so that `pendingCount = 1`, then call `recorder.close()`.
- Verify 14b: the recorder synchronously flushes the pending count before returning from `close()`, so the server event has `count = 2` (1 original + 1 suppressed). This verifies close-time flush behavior.
- Action 14c: emit two identical events within the suppression window so that `pendingCount = 1`, then trigger the scheduled flush concurrently with `recorder.close()` (e.g., run the flush task and close() from separate threads).
- Verify 14c: the server event has `count = 2` exactly once (no duplicate creates or double-counting), and `recorder.close()` completes without throwing. This verifies that close-flush shares per-key serialization with scheduled flush and is safe under concurrency.
- Action 14d: call `recorder.close()` a second time after it has already closed.
- Verify 14d: the second close is a no-op and does not throw. This verifies close idempotency.
- Action 14e: call `recorder.close()` and then call `recorder.normal(...)` for any key after `close()` has fully returned.
- Verify 14e: the recorder throws `IllegalStateException` with a clear message indicating the recorder is closed, and no new Event is created. This verifies that event emission after close is rejected.
- Action 14f: start closing `recorder.close()` from one thread while another thread calls `recorder.normal(...)` for the same key.
- Verify 14f: `close()` completes successfully. The concurrent `normal()` call may either return normally and be counted, or throw `IllegalStateException` (recorder closed), depending on ordering; the test must capture the actual outcome and assert that no duplicate Event is created and the final server count matches the accepted outcome (1 if rejected, 2 if accepted and flushed). This verifies that concurrent event emission and close share per-key serialization and are handled safely.
- Action 15: construct an `EventRecorder` with the test-visible `maxCacheEntries = 2`, emit 3 distinct events to fill and evict the cache, where the first evicted entry has `pendingCount > 0`.
- Verify 15: the evicted entry is flushed before removal, and the total server count for that key reflects the flushed pending events. This verifies eviction-flush behavior.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest`
- Action 16: pre-create an Event on the server with the same deterministic name that the recorder would compute (e.g., `count = 5`), then create a fresh `EventRecorder` with an empty cache and emit the same event.
- Verify 16: the recorder detects the `AlreadyExists` conflict, fetches the existing Event, and patches it to `count = 6` (existing count + current event). This verifies create idempotency when the cache has no record of the existing Event.
- Action 17: emit an event, wait for the cache entry to be evicted (by advancing the clock beyond `suppressionInterval * 2` and triggering eviction), ensure the Event still exists on the server, then emit the same event again.
- Verify 17: the recorder handles the `AlreadyExists` on create and patches the existing Event to the correct total count. This verifies cache-eviction + existing-server-Event behavior.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest`
**Failure QA:**
- Action: call `recorder.normal(null, "Created", "...")`.
- Verify: throws `NullPointerException` with a message containing `involvedObject`, and no Event is created on the server.
- Action: call `recorder.event(deployment, null, "Created", "...")`.
- Verify: throws `NullPointerException` with a message containing `type`, and no Event is created on the server.
- Action: create a `Deployment` without a UID, then call `recorder.normal(deployment, "Created", "...")`.
- Verify: throws `IllegalArgumentException` with a message containing `uid`, and no Event is created on the server.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest`
**Commit:** `feat: add EventRecorder for publishing Kubernetes Events`

### T2: Create `EventSubscriber` and `EventMapper` for watching Kubernetes Events

**References:**
- New files:
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/event/EventSubscriber.java`
  - `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/event/EventMapper.java`
- Existing `SecondaryWatch`: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/SecondaryWatch.java`
- Existing `ResourceMapper`: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceMapper.java`
- Existing `Request`: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/reconciler/Request.java`
- Existing `ResourceEvent`: `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceEvent.java`
- Fabric8 API: `io.fabric8.kubernetes.api.model.HasMetadata.getKind(Class<? extends HasMetadata>)` and `HasMetadata.getApiVersion(Class<? extends HasMetadata>)` for kind/apiVersion extraction from a class.

**Acceptance criteria:**
- `EventSubscriber<P extends HasMetadata>` is public and lives in `...framework.event`.
- It wraps a `SecondaryWatch<P, Event>` produced by a static factory method `EventSubscriber.forInvolvedObject(Class<P> primaryResourceClass)`.
- It exposes `SecondaryWatch<P, Event> toSecondaryWatch()` so the existing builder can consume it.
- `EventSubscriber.forInvolvedObject(...)` produces a `SecondaryWatch` whose name is `"events"`, whose resource class is `Event.class`, and whose mapper is the `involvedObject` mapper.
- `EventMapper.involvedObject(Class<P> primaryResourceClass)` returns a `ResourceMapper<Event, P>` that maps an `Event` to a `Request` when:
  - `event.involvedObject` is non-null.
  - `event.involvedObject.kind` matches the primary resource's kind (derived from the class via `HasMetadata.getKind(Class)`).
  - `event.involvedObject.apiVersion` matches the primary resource's apiVersion (derived from the class via `HasMetadata.getApiVersion(Class)`).
  - Returns a `Request(involvedObject.namespace, involvedObject.name)`.
- If the involved object does not match the primary resource type, returns an empty list.
- If `event.involvedObject` is null, returns an empty list.
- `EventSubscriber` inherits the controller's namespace scope from the `SharedInformerFactory` already created by `Operator` (i.e., it watches the same namespace as the primary controller; for cluster-scoped operators it watches all namespaces via the factory).
- `EventSubscriber` Javadoc and class-level documentation clearly state that Kubernetes Events are best-effort, may be dropped or TTL-expired, and should not be used for correctness-critical or durable workflow state.
- `EventSubscriber` Javadoc and the example code warn against the self-triggering reconcile-loop hazard: a controller that both records Events for its primary resource and subscribes to Events involving that same resource can cause infinite reconciliation loops unless the Events are filtered by source, reason, or type. The example leaves the subscriber commented out for this reason.
- Null input is rejected with `NullPointerException`.

**Happy QA:**
- Setup: in `EventSubscriberTest`, create an `Event` with `involvedObject` pointing to a `ConfigMap` (kind `ConfigMap`, apiVersion `v1`) in namespace `test`, name `config-1`.
- Action: `EventSubscriber<ConfigMap> subscriber = EventSubscriber.forInvolvedObject(ConfigMap.class);` then map the event via `subscriber.toSecondaryWatch().mapper().map(event, resourceEvent)`.
- Verify: returns a list containing one `Request(namespace="test", name="config-1")`.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=EventSubscriberTest`

**Failure QA:**
- Action: create an `Event` whose `involvedObject.kind` is `Deployment` instead of `ConfigMap` and map it with `EventSubscriber.forInvolvedObject(ConfigMap.class)`.
- Verify: returns an empty list.
- Action: create an `Event` with `involvedObject` set to `null` and map it.
- Verify: returns an empty list.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=EventSubscriberTest`

**Commit:** `feat: add EventSubscriber and EventMapper for watching Kubernetes Events`

### T3: Wire `EventSubscriber` into `ControllerBuilder` and `ControllerSources`

**References:**
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/ControllerSources.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/ControllerBuilder.java`
- `EventSubscriber` from T2.
- Existing `ControllerBuilderTest` style: `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/ControllerBuilderTest.java`

**Acceptance criteria:**
- `ControllerSources<P>` interface has a new method: `ControllerSources<P> withEventSubscriber(EventSubscriber<P> eventSubscriber)`.
- `ControllerBuilder<P>` implements `withEventSubscriber`, adding the underlying `SecondaryWatch` to its internal `secondaryWatches` list.
- The method returns `this` for fluent chaining.
- Existing `owns(Class)`, `watches(String, Class, ResourceMapper)` behavior is unchanged.
- The `ControllerRegistration` produced by `build()` includes the event subscriber as a secondary watch.
- `null` `EventSubscriber` is rejected with `NullPointerException`.

**Happy QA:**
- Setup: in `ControllerBuilderTest`, add a test for `ControllerBuilder.forResource(ConfigMap.class).withReconciler(reconciler).withEventSubscriber(EventSubscriber.forInvolvedObject(ConfigMap.class)).build()`.
- Verify: the resulting `ControllerRegistration.secondaryWatches()` contains a secondary watch with name `events`, resource class `Event.class`, and a non-null mapper.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=ControllerBuilderTest`

**Failure QA:**
- Action: call `builder.withEventSubscriber(null)`.
- Verify: throws `NullPointerException` with message containing `eventSubscriber`.
- Tool: `mvn -f operator/framework/pom.xml test -Dtest=ControllerBuilderTest`

**Commit:** `feat: wire EventSubscriber into ControllerBuilder`

### T4: Run full test suite and verify coverage

**References:**
- Test files created in T1-T3:
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/event/EventRecorderTest.java`
  - `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/event/EventSubscriberTest.java`
  - updated `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/ControllerBuilderTest.java`
- There is no existing `MockKubernetesServer` test in the framework module; `EventRecorderTest` will introduce the pattern. With Fabric8 7.3.0 and this repo's JUnit 5 setup, annotate the test class with `@EnableKubernetesMockClient(crud = true)` and inject `KubernetesClient client` (and optionally `KubernetesMockServer server`) into the test. The injected client is the mock client; do not use `new KubernetesServer(...)` because that is not the JUnit 5 extension used here.
- Existing mapper test examples: `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/source/MappersTest.java`
- Fabric8 `@EnableKubernetesMockClient` extension usage.

**Acceptance criteria:**
- All new test files created in T1-T3 compile and pass.
- `EventRecorderTest` covers event creation, normal/warning types, aggregation after suppression, suppression-window behavior, namespace selection, name sanitization, null/blank validation, and missing UID.
- `EventSubscriberTest` covers `EventMapper.involvedObject` mapping for matching and non-matching resources, null `involvedObject`, and null constructor input.
- `ControllerBuilderTest` verifies the new `withEventSubscriber` wiring.
- Existing tests continue to pass (no regressions).
- `mvn -f operator/framework/pom.xml test` passes.

**Happy QA:**
- Action: run `mvn -f operator/framework/pom.xml test`.
- Verify: all tests pass, including the new `EventRecorderTest` and `EventSubscriberTest`.
- Tool: `mvn -f operator/framework/pom.xml test`

**Failure QA:**
- Action: temporarily change one assertion in `EventRecorderTest` to an intentionally false value (e.g., assert `count == 99` instead of `count == 1`), then run `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest`.
- Verify: Maven exits with `BUILD FAILURE` and the test report shows the failed assertion, proving the test suite catches regressions in the new code.
- Tool: edit assertion + `mvn -f operator/framework/pom.xml test -Dtest=EventRecorderTest` (revert the temporary change afterward).

**Commit:** `test: add EventRecorder and EventSubscriber unit tests`

### T5: Update Echo operator example to demonstrate `EventRecorder`

**References:**
- `example/echo-operator/src/main/java/com/example/echooperator/controller/EchoReconciler.java`
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java`
- New `EventRecorder` from T1.

**Acceptance criteria:**
- `EchoReconciler` constructor accepts an `EventRecorder`.
- On successful reconciliation, it emits a `Normal` event with reason `Reconciled` and a short message.
- On failure, it emits a `Warning` event with reason `ReconcileFailed` and the exception message. If `exception.getMessage()` is null, the message falls back to the exception class name (e.g., `RuntimeException`).
- `EchoOperatorMain` constructs the `EventRecorder` with component name `echo-operator` and passes it to the reconciler.
- `EchoOperatorMain` includes a commented-out example of `ControllerBuilder.withEventSubscriber(EventSubscriber.forInvolvedObject(EchoResource.class))`, and the comment explains that enabling it can cause an infinite reconciliation loop because the reconciler itself emits Events for `EchoResource`.
- `EchoOperatorMain` owns the `EventRecorder` lifecycle: it keeps the `EventRecorder` as a field, constructs it, passes it to the reconciler, and closes it in its own shutdown path (e.g., `stop()` or `close()`) **before** calling `operator.stop()`. The Kubernetes client is closed last. Because `Operator.stop()` closes the shared Kubernetes client, closing the recorder after `operator.stop()` would be too late to flush pending counts.
- The echo-operator module continues to compile and package.
- Add `EchoReconcilerTest` that uses Mockito to verify `EventRecorder.normal(...)` is called on successful reconciliation and `EventRecorder.warning(...)` is called when reconciliation throws an exception.
- `EchoReconciler` records Events through a small best-effort helper that wraps the `EventRecorder` calls in a `try/catch` and logs any recorder/API exception. The helper does not propagate recorder exceptions to the caller, so a failure to create an Event cannot turn a successful reconcile into a failure or mask the original exception. The `EventRecorder` itself remains fail-loud (it throws on invalid input), but the example demonstrates safe usage in a reconciler.

- Action: run `mvn -f operator/framework/pom.xml -DskipTests install` first, then run `mvn -f example/echo-operator/pom.xml test`.
- Verify: the example module builds and all tests pass, including `EchoReconcilerTest`.
- Tool: `mvn -f operator/framework/pom.xml -DskipTests install && mvn -f example/echo-operator/pom.xml test`

**Failure QA:**
- Action: grep `EchoReconciler.java` for `EventRecorder`, `.normal(`, `.warning(`, and `ReconcileFailed`.
- Verify: all four patterns are present. `ReconcileFailed` confirms the failure path emits a Warning event with the required reason.
- Tool: `grep -E 'EventRecorder|\.normal\(|\.warning\(|ReconcileFailed' example/echo-operator/src/main/java/com/example/echooperator/controller/EchoReconciler.java`

**Commit:** `docs(example): emit Kubernetes Events from EchoReconciler`

### T6: Run final build and test verification

**References:**
- `operator/framework/pom.xml`
- `example/echo-operator/pom.xml`
- All files changed in T1-T5.

**Acceptance criteria:**
- `mvn -f operator/framework/pom.xml -DskipTests install` succeeds.
- `mvn -f operator/framework/pom.xml test` succeeds.
- `mvn -f example/echo-operator/pom.xml -DskipTests package` succeeds (requires framework installed first).
- No regressions in existing tests.

**Happy QA:**
- Action: run all three commands above in order.
- Verify: all exit with `BUILD SUCCESS`.
- Tool: `mvn -f operator/framework/pom.xml -DskipTests install && mvn -f operator/framework/pom.xml test && mvn -f example/echo-operator/pom.xml -DskipTests package`

**Failure QA:**
- Action: capture an empty temporary local Maven repository path and run the example package build against it before installing the framework into that same repository: `REPO=$(mktemp -d) && mvn -f example/echo-operator/pom.xml -DskipTests package -Dmaven.repo.local=$REPO`.
- Verify: Maven exits with `BUILD FAILURE` because the `operator-framework` dependency cannot be resolved in the empty repository, demonstrating the final build order dependency.
- Tool: `REPO=$(mktemp -d) && mvn -f example/echo-operator/pom.xml -DskipTests package -Dmaven.repo.local=$REPO` (after this verification, run the correct order with the default repository).

**Commit:** no separate commit; this verifies T1-T5.

## Final verification wave

Run these in parallel after all todos are done:

1. **Plan compliance audit (F1):** Check that every file in the plan exists and matches the acceptance criteria. Use `grep`/`read` to verify `ControllerBuilder` has `withEventSubscriber`, `EventRecorder` has the required methods (including `close()`), and tests are present. Verify that framework tests do not import `EchoResource` from the example module to avoid a dependency cycle.
2. **Code quality review (F2):** Review the new `event` package for consistency with the existing framework style: null checks, package naming, Javadoc, and no unnecessary imports. Ensure `EventRecorder` does not swallow exceptions silently and that it implements `AutoCloseable` with proper flush-task lifecycle.
3. **Automated build QA (F3):** Execute `mvn -f operator/framework/pom.xml test` and `mvn -f example/echo-operator/pom.xml -DskipTests package` (after framework install) and confirm `BUILD SUCCESS`.
4. **Scope fidelity (F4):** Confirm no changes were made to dependency versions (fabric8, micrometer, bouncycastle), webhook, certificate, metrics, leader-election, or health server code. Confirm only additive changes exist. Confirm that the only new dependency is test-scoped `io.fabric8:kubernetes-server-mock` in `operator/framework/pom.xml`.

All four verifications must pass. If any fail, loop back to the corresponding todo, fix, and rerun the final wave.

## Commit strategy

- **T1:** `feat: add EventRecorder for publishing Kubernetes Events`
- **T2:** `feat: add EventSubscriber and EventMapper for watching Kubernetes Events`
- **T3:** `feat: wire EventSubscriber into ControllerBuilder`
- **T4:** `test: add EventRecorder and EventSubscriber unit tests`
- **T5:** `docs(example): emit Kubernetes Events from EchoReconciler`

Each commit is atomic and leaves the repo buildable. If the worker prefers, T1 and T2 may be combined into one `feat:` commit because they are tightly related, but T3 should remain separate (it touches public API), and T4/T5 should remain separate.

## Success criteria

- `operator/framework` builds and passes `mvn -f operator/framework/pom.xml test`.
- `example/echo-operator` builds and packages with `mvn -f example/echo-operator/pom.xml -DskipTests package` after the framework is installed.
- Public API is available and tested:
  - `new EventRecorder(client, "echo-operator").normal(resource, "Created", "...")`
  - `ControllerBuilder.forResource(EchoResource.class).withReconciler(reconciler).withEventSubscriber(EventSubscriber.forInvolvedObject(EchoResource.class)).build()`
- Existing framework behavior (primary watch, secondary watch, reconciler, retry) is unchanged.
- No changes to dependency versions or unrelated subsystems. A test-scoped `io.fabric8:kubernetes-server-mock` dependency may be added to `operator/framework/pom.xml` to support mock-server tests.
