# ADR-0002: Field indexer via fabric8 Store on ReconciliationContext

- **Status:** Accepted
- **Date:** 2026-07-28
- **Related:** ADR-0001 (watch selectors)

## Context

`ReconciliationContext` currently carries only `resourceKey()` and `triggers()` —
it exposes no access to the informer cache. A reconciler that needs to find
related resources by a non-name field (e.g. "every Foo whose `spec.secretRef`
points at this Secret") must either walk the whole cache, or issue a fresh `LIST`
against the API server, on every reconcile. Both defeat the point of the local
informer cache.

fabric8's `SharedIndexInformer` already supports `addIndexers(Map<String, Function<T,
List<String>>>)` before start, and its `Store` exposes `getByIndex(name, key)` and
`getByKey(key)`. The framework opens no API surface for either: it never calls
`addIndexers`, and `Fabric8Controller.reconcile()` constructs the context with no
store reference.

The decision that gates everything else is **how a reconciler reaches the cache**.
This repository's quality gate forbids single-implementation public SPI
(`docs/agents` decision record: "禁止创建单实现公共 SPI"), so introducing a
`Cache<T>` interface with exactly one fabric8-backed implementation is off the
table.

## Decision

Expose fabric8's `Store<T>` directly on `ReconciliationContext<T>`, and add a
builder API for declaring indexed fields. No custom cache abstraction.

### API — index declaration

`ControllerBuilder<T>` gains:

```java
public ControllerBuilder<T> indexField(String key, Function<T,String> extractor);
// callable repeatedly; duplicate keys are rejected (fail-fast); builder collects
// into a LinkedHashMap<String, Function<T,String>>
```

`ControllerRegistration<T>` gains `Map<String, Function<T,String>> indexFields()`
(`Map.copyOf`). The single-value extractor is wrapped internally as
`r -> List.of(fn.apply(r))` to satisfy fabric8's `Function<T, List<String>>` contract;
a multi-value variant (`indexFieldMulti`) is a follow-up.

### API — cache access (breaking)

`ReconciliationContext` becomes generic and gains a `cache()` accessor:

```java
public final class ReconciliationContext<T extends HasMetadata> {
    ReconciliationContext(ResourceKey key, List<ReconciliationTrigger> triggers, Store<T> cache);
    public ResourceKey resourceKey();
    public List<ReconciliationTrigger> triggers();
    public Store<T> cache();   // io.fabric8.kubernetes.client.informers.cache.Store
}
```

`Reconciler<T>`'s signature changes accordingly (breaking):

```java
ReconcileResult reconcile(T resource, ReconciliationContext<T> context);
// usage: context.cache().getByIndex("secretRef", resource.getSpec().getSecretRef())
```

### Integration

`Fabric8Controller`:

```java
// configureInformers() — addIndexers must run before start()
primaryInformer = informer(registration.resourceType(), new PrimaryHandler(),
        resync, registration.watchSelector());
registration.indexFields().forEach((key, fn) ->
        primaryInformer.addIndexers(
                Map.of(key, r -> List.of(fn.apply((T) r)))));

// reconcile() — inject the primary indexer (getIndexer; Store lacks byIndex)
var context = new ReconciliationContext<T>(work.key(), work.triggers(),
        primaryInformer.getIndexer());
```

Only the primary informer's store is exposed; secondary and event informers are not.
Reconciliation operates on the primary resource, so that is the only cache it needs.

### Why a fabric8 type, not a new interface

`Store<T>` is fabric8's existing type — reused, not invented. It carries no new
single-implementation SPI: the decision record's prohibition targets interfaces the
framework would author with one implementation; binding to a dependency's concrete
public type is a different act and has precedent here (`Finalizers` already takes
`io.fabric8.kubernetes.client.dsl.base.PatchContext`).

## Consequences

**Positive**

- Reconcilers can answer "who references this" in O(1) via the index, instead of
  scanning the cache or hitting the API server.
- No custom cache abstraction to maintain; the fabric8 `Indexer` API is the public
  surface.
- Indexer stays opt-in — controllers that don't declare `indexField` see no change
  beyond the context generic.

**Negative (breaking)**

- `Reconciler.reconcile` and `ReconciliationContext` change shape. Every reconciler
  implementation and the `example/echo-operator` reconciler must move to
  `ReconciliationContext<T>`. This is an internal framework (`com.huawei.dcs`) so
  the break is absorbable, but it must ship in one pass.
- `api.reconcile` gains a compile dependency on
  `io.fabric8.kubernetes.client.informers.cache.Store` — a more internal fabric8
  type than the `HasMetadata`/`PatchContext` it already depends on. Acceptable for
  reuse, but `StarterPackagingIT`'s allowed-package-roots rule may need no change
  (Store is under `io.fabric8`, already transitively allowed) — verify at build.
- `addIndexers` must be called before `informer.start()`; `configureInformers()`
  already runs pre-start, so ordering is safe, but it is an invariant to preserve
  in future edits.

**Follow-ups**

- `indexFieldMulti(key, Function<T, Collection<String>>)` for one-to-many indexes.
- Secondary-informer `Store` exposure, if a reconciler ever needs to query a
  secondary cache mid-reconcile.

## Alternatives considered

- **Author a `Cache<T>` interface wrapping fabric8's store.** Rejected — it would be
  a single-implementation public SPI, which this repo's quality gate explicitly
  forbids. It would also add a maintenance surface with no behavioral gain over
  exposing the dependency's own type.
- **Restrict indexers to internal trigger mapping** (e.g. a secondary ConfigMap
  change reverse-looks-up primary owners), leaving `ReconciliationContext` alone.
  Rejected — it preserves the contract but forfeits the in-reconcile reverse lookup,
  which is the indexer's core value; the whole point is letting a reconciler ask
  "who points at this" without a server round-trip.
- **Keep `ReconciliationContext` non-generic and return a raw `Store<?>`.** Rejected
  — forces every caller to cast, loses compile-time type safety on the cached
  resource. Generifying the context is the one breaking change that buys real
  type safety, not churn for its own sake.

## Open verification (at implementation)

Resolved against fabric8 7.3.0 (verified via `javap` on `kubernetes-client-api-7.3.0.jar`):

- `SharedIndexInformer.addIndexers(Map<String, Function<T, List<String>>>)` returns `SharedIndexInformer<T>` (chainable).
- `getStore()` returns `Store<T>` — but `Store` has only `getByKey`/`list`/`getKey`, **no by-index lookup**. `getByIndex` is actually `byIndex(indexName, key) -> List<T>` and lives on `Indexer<T>`, which `extends Store<T>`.
- Therefore `cache()` returns `Indexer<T>` (via `primaryInformer.getIndexer()`), not `Store<T>` as drafted above; `getStore()` is still used internally by `Fabric8Controller.resourceFor` for `getByKey`. The core decision (expose fabric8's own type, author no custom SPI) is unchanged — only the concrete type is `Indexer`, not `Store`.
