# ADR-0004: Managed dependent resources

- **Status:** Accepted
- **Date:** 2026-08-01
- **Related:** ADR-0003 (server-side apply helper)

## Context

`owns()`/`watches()` only *trigger* reconciliation — nothing *manages* owned
resources. Every operator hand-rolls the same loop per dependent: compute
desired state from the primary, set the owner reference, create-or-update it,
rely on GC for cleanup. JOSDK's core value is exactly this loop
(`KubernetesDependentResource`); without it, each reconciler re-implements it
with slightly different create-or-update races and missing owner references.

The submission seam already exists: `Applies` (ADR-0003) does idempotent
server-side apply, `Owners` writes the controller reference. What is missing
is the user-facing callback that ties them together.

## Decision

Add three pieces:

- **`DependentResource<D, P>`** (`api.reconcile`) — user callback with two
  methods: `resourceType()` and `desired(P primary, ReconciliationContext<P>)`.
  Computing desired state is the operator's real domain logic; everything
  after it is mechanical.
- **`Dependents.apply(client, dependent, primary, context, fieldManager)`**
  (`api.reconcile`) — the mechanical part: `desired()` → `Owners.setController`
  → `Applies.apply`. Static helper, same shape as its two building blocks.
- **`ControllerBuilder.manages(dependent)`** (`api.controller`) — registers
  `dependent.resourceType()` as an owned resource, so the dependent's events
  trigger reconciliation via the existing owner-reference mapping.

Deliberately **not** a workflow engine: no reconcile/pre-delete/ready
conditions, no bulk dependents, no ordering between dependents. Reconcile
loops are idempotent by design, so applying dependents in the reconciler's
own order is sufficient today.

### Scope

- Deleting an individual dependent while the primary lives is out of scope —
  GC only fires on owner deletion. Operators that need per-dependent deletion
  use the client directly.
- Status of dependents is not managed; `StatusUpdates` covers the primary.

## Consequences

**Positive**

- The common single-dependent case collapses to one callback + one call:
  no create-or-update race, no forgotten owner reference, no hand-rolled diff.
- `manages` keeps watch registration and dependent declaration at one call
  site — they cannot drift apart.
- Composition over a new engine: the helper is three lines over two existing
  helpers; no new abstraction layer, no single-implementation SPI (the
  callback is the user's own type).

**Negative**

- Multiple dependents with ordering/condition needs still hand-roll sequencing
  in their reconciler. Acceptable until a real multi-dependent operator
  appears; the callback shape does not preclude a workflow layer later.

## Alternatives considered

- **Full workflow engine (JOSDK-style).** Rejected — conditions, bulk
  dependents, and ordering are unneeded complexity for the current single-
  dependent use cases; the seams (`DependentResource`, `Dependents.apply`)
  leave room to add them without breaking the API.
- **Annotation-driven dependents (`@Dependent` on fields).** Rejected —
  runtime annotation scanning adds a discovery mechanism for zero gain over
  an explicit interface; this repo favors explicit beans over annotation
  magic (see `ControllerRegistration` design).
- **Fold `manages` into `owns(Class)`.** Rejected — passing the dependent
  instance keeps the type in one place; `owns(dep.resourceType())` invites
  declaring the type twice.
