# ADR-0001: Watch label/field selector on primary informers

- **Status:** Accepted
- **Date:** 2026-07-28
- **Related:** ADR-0002 (field indexer)

## Context

Primary and secondary informers are created without any label or field selector
configuration — only the event informer uses `withField("involvedObject.kind", …)`
and `withField("involvedObject.apiVersion", …)` in `Fabric8Controller.addEventInformer`.
Operators that should watch a subset of resources (multi-tenant deployments, a
single app label, one controller per shard) therefore receive and reconcile every
object of the watched type, wasting watch connections and enqueueing irrelevant
events.

fabric8's informer builder already supports `.withLabels(Map)` and
`.withField(key, value)` before `runnableInformer(…)` — the framework simply opens
no API surface for it. The gap is an omitted passthrough, not a missing capability.

## Decision

Expose label and field selectors on the primary watch only, using `Map<String,String>`
equality matching (no expression selectors).

### API

`ControllerBuilder<T>` gains two methods and one aggregation record (keeps the
`informer()` factory under the repo's five-argument parameter gate, and leaves room
to extend to expression selectors without changing signatures):

```java
public record WatchSelector(Map<String,String> labels, Map<String,String> fields) {
    public static WatchSelector of(Map<String,String> labels, Map<String,String> fields);
}
public ControllerBuilder<T> labelSelector(Map<String,String> labels);
public ControllerBuilder<T> fieldSelector(Map<String,String> fields);
```

`ControllerRegistration<T>` gains `Optional<WatchSelector> watchSelector()`,
mirroring the existing `Optional<Duration> resyncPeriod()` convention. `Optional`
(empty = no filtering) rather than nullable.

### Integration

`Fabric8Controller.informer(Class, ResourceEventHandler, Duration, WatchSelector)`
chains the selector before `runnableInformer`:

```java
var scoped = properties.isClusterScoped()
        ? resources.inAnyNamespace() : resources.inNamespace(namespace());
watchSelector.ifPresent(s -> {
    scoped.withLabels(s.labels());
    s.fields().forEach((k, v) -> scoped.withField(k, v));
});
var informer = scoped.runnableInformer(resync.toMillis());
```

The primary informer passes `registration.watchSelector()`; `addOwnedInformer` and
`addSecondaryInformer` pass `Optional.empty()`; `addEventInformer` keeps its existing
`involvedObject` field selectors and is unaffected.

### Scope

Primary informer only. `SecondaryWatch` is a record; adding fields would force its
constructor and every `owns()`/`watches()` call site to change for a marginal gain.
Secondary-watch selectors are a follow-up if real demand appears.

## Consequences

**Positive**

- Operators can scope a watch to a label/field subset, cutting irrelevant events and
  connection load in multi-tenant or sharded deployments.
- Reuses fabric8's existing selector API; no new abstraction, no new dependency.
- No breaking changes — additive methods and an `Optional` accessor.

**Negative**

- Equality matching only (`app=foo`). Expression selectors (`in`, `!=`, `exists`)
  are deferred; `Map<String,String>` covers the ~90% case, the rest can extend
  `WatchSelector` later without touching `informer()`'s signature.
- Secondary watches are not filterable yet.
- The fabric8 mock server's support for watch label/field filtering is limited (see
  the existing `withoutEventFieldSelector()` test hook on the event informer).
  Integration tests may degrade to asserting `withLabels`/`withField` are invoked
  rather than observing filtered delivery, with the server-side path noted as a
  known limitation.

## Alternatives considered

- **Expose fabric8's `LabelSelector` object directly** for full expression support.
  Rejected — deeper coupling for a capability 90% of operators won't use; `Map<String,String>`
  equality suffices, and `WatchSelector` can grow an expression field later without a
  signature break.
- **Add selectors to `SecondaryWatch` in the same change.** Rejected — the record's
  constructor change ripples through `owns()`/`watches()` call sites; the marginal
  value of filtering secondary watches doesn't justify the churn now.
