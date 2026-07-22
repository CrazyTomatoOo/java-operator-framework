# Decisions — generation-filter

## 2026-07-21 — D1: `Duration.ZERO` maps straight to `0L` (no `Long.MAX_VALUE` workaround)
- **Decision**: `Operator` passes `registration.resyncPeriod().toMillis()` directly to fabric8. `Duration.ZERO` disables
  periodic resync. No mapping to `Long.MAX_VALUE`, no deviation from plan §5.1 needed.
- **Evidence**: `javap -p -c` on `io.fabric8.kubernetes.client.informers.impl.DefaultSharedIndexInformer` from
  `~/.m2/repository/io/fabric8/kubernetes-client/7.3.0/kubernetes-client-7.3.0.jar`:
  - `scheduleResync(...)`: `resyncCheckPeriodMillis <= 0` -> logs `"Resync skipped due to 0 full resync period for {}"`,
    skips `Utils.scheduleAtFixedRate`, `resyncFuture` remains null.
  - Constructor rejects only negative periods (`"Invalid resync period provided, It should be a non-negative value"`).
  - `determineResyncPeriod(0, x) == 0` — a zero handler resync is not raised to the minimum.
- Plan §5.1's open question ("需确认 fabric8 sharedIndexInformerFor(Class, 0) 是否关闭 resync") is resolved: **yes, 0 closes resync**.


## 2026-07-21 — D2: effective filter = constructor field OR `configuration.generationChangeFilter()`
- Plan §4.1 requires a `generationChangeFilter` field + a flag-accepting constructor on `ResourceEventSource`, while
  plan §5.1 keeps `Operator` calling the existing 4-arg constructor (flag flows via `SourceConfiguration`).
- Checking only the field would silently disable the Operator path; checking only the configuration would make the
  new field/constructor dead code. `isGenerationChangeFilterEnabled()` therefore returns
  `generationChangeFilter || configuration.generationChangeFilter()`. All legacy paths (old ctors + 4-arg
  SourceConfiguration) resolve to false => fully backward compatible; both new paths can enable the filter.
- `shouldEnqueue` semantics copied exactly from plan §4.2 (null-safe, deletionTimestamp null->non-null,
  finalizers changed, else generation changed). `var` from the plan replaced with explicit `ObjectMeta` to match
  file style.
- Filter check in `onUpdate` applies only when `role == PRIMARY` — secondary sources never filtered (test c proves
  a secondary source carrying `generationChangeFilter=true` still enqueues unchanged-generation updates).

## 2026-07-21 — D3: secondary sources share the controller-level resyncPeriod; Operator constant removed
- Per plan §5.2 recommendation, `createSecondaryEventSource` gained a `long resyncPeriodMs` parameter (private
  method, not public API) and `createController` passes `registration.resyncPeriod().toMillis()` for both primary
  and secondary sources.
- `Operator`'s private `DEFAULT_RESYNC_PERIOD_MS` constant became unused after the change and was removed (cleanup
  of own diff). `ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS` remains the single default, used by
  `ControllerRegistration`'s legacy 3-arg constructor and `ControllerBuilder`'s field initializer.
