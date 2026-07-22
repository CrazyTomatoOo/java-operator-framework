# Decisions

## Architectural
- Primary/secondary resource support via `ControllerRegistration` / `ControllerBuilder`.
- `Request` carries triggers but equality remains by primary key.
- Single shared queue per controller with coalescing by primary key.

## API
- `ResourceMapper<R, P>` functional interface: `Collection<Request> map(R, ResourceEvent<R>)`.
- Built-in mappers: `Mappers.ownerReferences()`, `Mappers.byLabel(...)`, `Mappers.byAnnotation(...)`.
- `Trigger` role: PRIMARY or SECONDARY.

## Deferrals
- `ResourceCache` abstraction
- `ContextualReconciler` interface
- Label selectors / predicates in builder
