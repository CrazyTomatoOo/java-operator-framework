# ADR-0003: Server-side apply helper in api.reconcile

- **Status:** Accepted
- **Date:** 2026-08-01
- **Related:** ADR-0001 (watch selectors), ADR-0002 (field indexer)

## Context

Operators converge owned resources (ConfigMaps, Deployments, Secrets) to a
desired state on every reconcile. Without a framework helper, each operator
hand-rolls create-or-update: read, compare, create-or-patch — racy under
concurrent reconciles and wrong when other managers (kubectl apply, Helm,
other controllers) touch the same object. Kubernetes server-side apply (SSA)
solves exactly this: one PATCH of the full desired state; the apiserver
creates when absent and updates only the fields the caller's field manager
owns.

fabric8 already exposes SSA — `client.resource(x).fieldManager(fm).serverSideApply()`
(`NonDeletingOperation` extends `ServerSideApplicable`). The gap is an omitted
convention, not a missing capability, mirroring ADR-0001's omitted passthrough.

## Decision

Add a static helper `Applies` in `api.reconcile`, the same shape as the
existing `Finalizers`/`StatusUpdates` helpers:

```java
Applies.apply(client, desired, fieldManager);          // SSA create-or-update
Applies.applyForcibly(client, desired, fieldManager);  // + forceConflicts()
```

Two decisions beyond the passthrough:

- **fieldManager is a required, non-blank parameter.** fabric8 silently
  defaults to `fabric8`; managers sharing one name take over each other's
  fields without any error. Forcing the caller to name its manager removes
  that footgun at the type level.
- **`applyForcibly` is a separate method**, not a boolean flag, so the
  conflict-taking variant is visible at the call site.

### Scope

Spec-side desired state only. Status still goes through `StatusUpdates`
(JSON merge patch on `/status`) — applying status via SSA would make the
operator own status fields it does not exclusively manage.

## Consequences

**Positive**

- Reconcilers get idempotent create-or-update in one call; no read-before-write
  race, no diff logic.
- Explicit field manager makes field ownership conflicts detectable instead of
  silent.
- Follows the established `Finalizers`/`StatusUpdates` static-helper pattern; no
  new abstraction, no new dependency, no single-implementation SPI.

**Negative**

- The fabric8 mock server does not implement SSA create-on-apply (404 on PATCH
  of a missing resource) and rejects `apply-patch+yaml` in CRUD mode (415), so
  unit tests stub the PATCH and assert the request shape rather than observing
  merged state. Merge semantics remain apiserver behavior, covered by real-cluster
  e2e (`example/echo-operator/scripts/e2e-test.sh`).

## Alternatives considered

- **Do nothing; document the raw fabric8 call.** Rejected — the default
  `fabric8` field manager is an invisible correctness trap for exactly the
  multi-manager clusters this framework targets; the helper's whole value is
  making the manager name explicit at the type level.
- **A `DependentResource` abstraction owning desired-state computation plus
  submission.** Deferred — this helper is its submission seam. Add the
  abstraction when a second reconciler needs managed dependents, not before.
- **Boolean `force` parameter on `apply`.** Rejected — invisible at call
  sites; a separate method name advertises the dangerous variant.
