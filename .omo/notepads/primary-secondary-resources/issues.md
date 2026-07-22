# Issues

## Known Blockers
- None

## Gotchas
- Request.equals must be based on namespace+name only, not triggers.
- `Operator.register(Class, Reconciler)` returns void — do not chain `.start()`.
- DELETE triggers must not be lost during coalescing.

## Open Questions
- None
## 2026-07-11
- Maven test verification for `TriggerTest` is currently blocked by unrelated missing `ResourceMapper` symbols in `SecondaryWatch`, `ControllerBuilder`, and `ControllerSources`.
- Cleaned up the duplicate `ResourceEventType` by deleting the reconciler-package enum; `Trigger` and `TriggerTest` now use `com.huawei.dcs.modelengine.operator.framework.source.ResourceEventType` with `ADD`.
- Focused Maven verification passed: `mvn -f operator/framework/pom.xml test -Dtest=TriggerTest,ResourceEventTypeTest,ResourceEventTest,ResourceMapperTest,ControllerBuilderTest`.
- Public queue exposure issue resolved by removing `ResourceEventSource.getQueue()`; tests now rely on the injected queue instance only.
