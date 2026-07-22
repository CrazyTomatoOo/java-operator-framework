---
intent: clear
review_required: true
status: awaiting-approval
components:
  - id: event-recorder
    outcome: EventRecorder API for controllers to publish Kubernetes Events (with aggregation and storm-prevention)
    status: designed
    evidence: com/huawei/dcs/modelengine/operator/framework source tree
  - id: event-subscriber
    outcome: Dedicated EventSubscriber class for watching Kubernetes Events and enqueuing primary reconciliations
    status: designed
    evidence: existing SecondaryWatch + ResourceMapper in com/huawei/dcs/modelengine/operator/framework
  - id: framework-wiring
    outcome: Public wiring in ControllerBuilder for EventSubscriber and idiomatic construction for EventRecorder
    status: designed
    evidence: ControllerBuilder + ControllerRegistration + ControllerSources
  - id: tests
    outcome: Unit tests for EventRecorder and EventSubscriber behavior, plus EchoReconcilerTest
    status: pending
    evidence: existing test package com/huawei/dcs/modelengine/operator/framework
  - id: example-update
    outcome: Echo operator demonstrates EventRecorder and optional EventSubscriber usage
    status: pending
    evidence: example/echo-operator
owner_decisions:
  - question: Which k8s Event capability should the framework expose?
    answer: Both create and subscribe
    chosen: Both create and subscribe (Recommended)
  - question: For Event subscription, which public API style do you prefer?
    answer: Dedicated EventSubscriber class
    chosen: Dedicated EventSubscriber class (Recommended)
best_practice_defaults:
  - EventRecorder exposes event/normal/warning convenience methods
  - EventSubscriber wraps a SecondaryWatch<Event, P> and provides an involvedObject mapper, watch name = "events"
  - Event namespace: if involvedObject.metadata.namespace is non-null and non-blank, use it; otherwise use defaultNamespace (constructor parameter, defaulting to client.getNamespace())
  - Event aggregation after suppression window, keyed by source.component + involvedObject UID + type + reason + message; pre-aggregates locally (pendingCount) before sending to the API server
  - Event storm prevention: client-side suppression window (default 5 minutes); only successful create/patch updates the cache timestamp; suppressed calls do not refresh it; identical events are accumulated locally rather than sent repeatedly
  - Bounded cache (max 1000 entries + oldest-entry eviction, TTL eviction on each call) stores lastEmitTime, pendingCount, resourceVersion, and serverCount; testable via Supplier<Instant> clock seam, injectable ScheduledExecutorService flush executor, and configurable maxCacheEntries
  - Event names: sanitized base + 8-char hash suffix, truncated to ≤63 chars with suffix preserved
  - EventSubscriber inherits the controller's namespace scope from the Operator's SharedInformerFactory
  - core/v1 Event is intentionally chosen over events.k8s.io/v1 EventSeries because Fabric8's default Event model and existing informers are built around core/v1
  - No dependency version changes (keep fabric8 7.3.0, micrometer 1.16.1, bouncycastle 1.84 already adjusted); adding a test-scoped io.fabric8:kubernetes-server-mock dependency is allowed
  - EventRecorder is thread-safe and serializes per-key operations; EventSubscriber documents best-effort semantics and self-triggering loop hazard
  - EventRecorder close() synchronously flushes all cache entries with pendingCount > 0 before cancelling the scheduled flush task and shutting down the executor.
  - 409 conflict handling is verified with expectations-mode MockKubernetesServer or a fake event-operation seam, not by mutating the real object in CRUD mode.
metis_review:
  status: completed (multiple rounds)
  findings:
    - "Clarified namespace rule: use involvedObject.metadata.namespace if non-blank; otherwise defaultNamespace."
    - "Fixed test ordering: each T1-T3 creates its own test file; T4 runs the full suite."
    - "Added name sanitization and 63-character truncation acceptance criteria with hash suffix preservation."
    - "Explicitly required aggregation key to include involvedObject UID and source.component."
    - "Added explicit event storm prevention via a configurable client-side suppression window and bounded cache."
    - "Specified that only successful create/patch emissions update the suppression timestamp; suppressed calls do not refresh it."
    - "Added deterministic clock seam (Supplier<Instant>), injectable ScheduledExecutorService flush seam, and configurable maxCacheEntries to avoid flaky tests."
    - "Clarified kind/apiVersion derivation using HasMetadata.getKind(Class) and HasMetadata.getApiVersion(Class)."
    - "Specified EventSubscriber inherits the SharedInformerFactory namespace scope."
    - "Replaced mutation-based negative QA with non-mutating validation and grep checks where possible."
    - "Standardized Maven commands to use -f <pom-path> because there is no parent reactor."
    - "Added EchoReconcilerTest to verify the failure path actually emits a Warning event."
    - "Client-go research findings incorporated: use core/v1 Event, cache resourceVersion after successful writes, use strategic-merge patch for count/lastTimestamp, and handle 409 by refetching and retrying once."
high_accuracy_review:
  status: round 11 completed - both reviewers approved, pending user approval
  round_1:
    momus:
      session_id: ses_07cf4c804ffe1RuYRXDUoTqEAE
      verdict: NOT OKAY
      blocking_issue: "T1's MockKubernetesServer tests require io.fabric8:kubernetes-server-mock, which is not declared in operator/framework/pom.xml."
    oracle:
      session_id: ses_07cf4bb4bffeCD1gQRhgO7e5Ie
      verdict: NOT OKAY
      issues:
        - severity: CRITICAL
          summary: "Suppressed events not clearly counted; patch formula could drop the triggering event."
        - severity: MAJOR
          summary: "No timer-based flush means accumulated counts may never reach the API server."
        - severity: MAJOR
          summary: "EventRecorder thread-safety is missing."
        - severity: MAJOR
          summary: "Namespace handling claims a distinction the plan does not explain how to make."
        - severity: MAJOR
          summary: "EventSubscriber risk is under-documented because Kubernetes Events are best-effort."
        - severity: MAJOR
          summary: "Recorder + subscriber can create self-triggering reconcile loops."
        - severity: MINOR
          summary: "core/v1-only choice is pragmatic but not aligned with current client-go default behavior."
  round_2:
    momus:
      session_id: ses_07ceddb09ffeRMDBZ6yqIjuDlY
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "T2/T3 framework tests reference EchoResource from example module, causing a dependency cycle."
        - severity: MAJOR
          summary: "Null exception message in T5 contradicts T1's null-message NPE requirement."
        - severity: MINOR
          summary: "T4 and T6 lack Failure QA."
    oracle:
      session_id: ses_07cedceb2ffe8cPJHivi4L7r7F
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "New mock-server dependency is underspecified (needs explicit ${fabric8.version})."
        - severity: MAJOR
          summary: "Aggregation key and deterministic Event name omit recorder/source identity."
        - severity: MAJOR
          summary: "Scheduled missing-Event fallback has contradictory count semantics."
        - severity: MAJOR
          summary: "Scheduled flush lifecycle is not executable safely (shutdown/ownership)."
        - severity: MINOR
          summary: "Cache eviction can silently drop pending counts."
  round_3:
    momus:
      session_id: ses_07ce7c285ffeC553xaaGqedj4n
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "T2 uses KubernetesResourceUtil.getKind/getApiVersion which do not accept Class in Fabric8 7.3.0."
        - severity: MAJOR
          summary: "T1 lifecycle contract contradicts QA: external executor should not be shut down by recorder."
        - severity: MINOR
          summary: "T2 Happy QA expected Request name echo-1 but setup uses ConfigMap named config-1."
    oracle:
      session_id: ses_07ce7b473ffeR17Bf3ImCCPkDS
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "Wrong Fabric8 API for kind/apiVersion extraction from a class."
        - severity: MAJOR
          summary: "Example may let best-effort Event failures alter reconciliation correctness."
        - severity: MINOR
          summary: "T2 happy-path test has inconsistent expected Request name."
        - severity: MINOR
          summary: "T6 Failure QA is not deterministic (artifact may already be in local cache)."
  round_4:
    momus:
      session_id: ses_07ce3a603ffeD2wukuZxKGhBrq
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "T2 still contains stale KubernetesResourceUtil.getApiVersion instruction at line 191."
        - severity: MAJOR
          summary: "T6 still contains redundant nondeterministic failure QA."
    oracle:
      session_id: ses_07ce39bedffet5YaCeN3331L4s
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "T2 still contains stale KubernetesResourceUtil.getApiVersion instruction."
        - severity: MAJOR
          summary: "EventRecorder does not specify AlreadyExists handling for deterministic Event names when cache is empty."
        - severity: MINOR
          summary: "T6 still contains old nondeterministic failure QA."
  round_5:
    momus:
      session_id: ses_07cdfc9e0ffekSnIkRVrfrUHS1
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "T6 still contains redundant nondeterministic failure QA (lines 335-338)."
    oracle:
      session_id: ses_07cdfbf5affeoBFWw93WvtElpQ
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "T6 still contains old nondeterministic Failure QA."
        - severity: MAJOR
          summary: "TL;DR aggregation formula contradicts detailed T1 semantics (scheduled flush adds +1)."
        - severity: MINOR
          summary: "T1 eviction QA asks for configurable tiny cache but no seam is specified."
        - severity: MINOR
          summary: "Happy-path fixtures should explicitly set UID."
  fixes_applied_round_5:
    - "Completely rewrote T6 Happy QA and Failure QA to remove all duplicated and nondeterministic checks."
    - "Rewrote TL;DR to distinguish event-driven flush (+1) from scheduled flush (no +1)."
    - "Added maxCacheEntries parameter to the test-visible constructor and updated the eviction QA to use it."
    - "Specified that test fixtures (Deployment, Node) must set metadata.uid."
  round_7:
    momus:
      session_id: ses_07cd6659affeTnsL2EjfYXSFTL
      verdict: OKAY
      summary: "Plan is executable, scoped, and testable; no duplicated sections."
    oracle:
      session_id: ses_07cd6582fffemhnZE7ou7HZ4Cs
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "409 handling verification is not reliable in CRUD mock mode."
        - severity: MAJOR
          summary: "EventRecorder.close() can drop pending counts because it does not flush before shutdown."
  fixes_applied_round_7:
    - "Updated T1 acceptance criteria: close() synchronously flushes all pendingCount > 0 entries before cancelling the scheduled flush task and shutting down the executor."
    - "Updated T1 Happy QA Action 8/Verify 8 to use expectations-mode MockKubernetesServer or a fake event-operation seam for deterministic 409 -> retry verification."
    - "Added close-time flush verification as Action 14b in T1 Happy QA."
    - "Updated T5 acceptance criteria: EchoOperatorMain.stop()/close() must close the EventRecorder before closing the Kubernetes client."
  round_8:
    momus:
      session_id: ses_07cd22a72ffeorljXxCZxQMrSR
      verdict: OKAY
      summary: "Updated plan is executable and its close-time flush and deterministic 409-retry verification are now concrete and testable."
    oracle:
      session_id: ses_07cd21ddbffeKbxVlGHh0y7HwP
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "EchoOperatorMain lifecycle is still underspecified: recorder must be closed before operator.stop()."
        - severity: MAJOR
          summary: "The 409 test can still pass without proving the retry patch body is correct."
        - severity: MAJOR
          summary: "Close-flush must be idempotent and share per-key serialization with normal flushes."
  fixes_applied_round_8:
    - "Updated T1 acceptance criteria: close() is idempotent, uses per-key serialization, and cannot double-count with scheduled flush."
    - "Updated T1 Happy QA Action 8/Verify 8 to require strict verification of the retry PATCH body using the latest resourceVersion and latest.count + pendingCount + 1."
    - "Added T1 Happy QA Actions 14c and 14d to verify concurrent close+flush safety and close idempotency."
    - "Updated T5 acceptance criteria: EchoOperatorMain closes the EventRecorder before operator.stop(), and closes the Kubernetes client last."
  round_9:
    momus:
      session_id: ses_07ccec62fffeqckIR8VkfTkTBA
      verdict: NOT OKAY
      blocking_issue: "T4 references SecondaryEventIntegrationTest.java as a MockKubernetesServer example, but it uses mocked informers/Mockito, not the mock server."
    oracle:
      session_id: ses_07cceb804ffeNq3JUxSJqFzu68
      verdict: OKAY
      optional_improvement: "Add a QA case for normal()/warning() racing with or occurring after close()."
  fixes_applied_round_9:
    - "Replaced T4's false reference to SecondaryEventIntegrationTest with a correct note that there is no existing MockKubernetesServer test in the framework module and EventRecorderTest will introduce the pattern."
    - "Added T1 Happy QA Actions 14e and 14f to verify that event emission after close() is rejected and that concurrent event emission and close() are handled safely."
  round_10:
    momus:
      session_id: ses_07ccaf022ffeVFzjYM5ecqxt97
      verdict: NOT OKAY
      blocking_issue: "T4 setup guidance uses `new KubernetesServer(true, true)` / JUnit @Rule, which is invalid in Fabric8 7.3.0 / JUnit 5."
    oracle:
      session_id: ses_07ccae123ffeRmV0ZHmq4H0mgW
      verdict: NOT OKAY
      issues:
        - severity: MAJOR
          summary: "T4 setup guidance uses invalid new KubernetesServer / JUnit @Rule pattern."
        - severity: MAJOR
          summary: "Plan references client.events() which targets events.k8s.io in Fabric8 7.3.0; core/v1 Event requires client.v1().events()."
        - severity: MAJOR
          summary: "Action 14f 'no exceptions propagate' conflicts with 14e."
  fixes_applied_round_10:
    - "Replaced T4 setup guidance with the JUnit 5 @EnableKubernetesMockClient(crud = true) pattern and injected KubernetesClient / KubernetesMockServer."
    - "Updated all plan references from client.events() to client.v1().events().inNamespace(...) for core/v1 Events."
    - "Updated Action 14f/Verify 14f to allow concurrent normal() to succeed or fail with IllegalStateException, while asserting no duplicate events and that close() succeeds."
  round_11:
    momus:
      session_id: ses_07cc6e3e6ffekzCHUbqNt9Hu0Z
      verdict: OKAY
      optional_improvement: "Remove the duplicated Verify 8 and Verify 14f bullets."
    oracle:
      session_id: ses_07cc6d460ffetgV9wgXaB1iKF4
      verdict: OKAY
      optional_improvement: "Reword Verify 14f to emphasize capturing the concurrent normal() outcome (no unexpected exceptions)."
  fixes_applied_round_11:
    - "Removed duplicate Verify 8 and duplicate Verify 14f bullets left over from earlier edits."
    - "Reworded the kept Verify 14f to state that the test must capture the actual outcome and assert no unexpected exceptions, no duplicate events, and correct final count."
plan_file: .omo/plans/k8s-event-recorder-subscriber.md
pending_action: user approval of the plan (ready to start execution)
---
