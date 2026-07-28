# F2 Lifecycle Leak Fix Evidence

## Fix 1 — operator pre-start shared client

- RED: `fix1-red.log` — 1 test failed because `KubernetesClient.close()` was never called.
- GREEN: `fix1-green.log` — 1 test passed.
- Change: `EchoOperatorMain` records `operatorStarted` immediately before `Operator.start()` and directly closes the shared client when the operator never started.
- Test seam: the Operator mock now mirrors production ownership and does not close the client on its pre-start `stop()` path.

## Fix 2 — create failure rollback

- RED: `fix2-red.log` — injected failure after webhook binding left both real ports unavailable.
- Partial: `fix2-partial.log` — metrics port was released while the webhook port remained bound, proving the Fix 3 dependency.
- GREEN: `fix2-green.log` — 1 test passed and both real ports were reusable.
- Change: `create()` preserves the original exception, records cleanup failures as suppressed, and rolls back both bound servers.

## Fix 3 — WebhookServer pre-start and failed-start cleanup

- RED: `fix3-prestart-red.log` — replacement server could not bind the leaked port.
- GREEN: `fix3-prestart-green.log` — pre-start port release passed.
- GREEN: `fix3-failed-start-green.log` — CertWatcher thread cleanup after failed start passed.
- Change: `stop()` always stops CertWatcher/server/executor. Because Java 21 `HttpServer.stop(0)` and `HttpsServer.stop(0)` retain a socket that has never started, cleanup briefly starts a never-started server before stopping it.

## Regression

- Framework: `framework-full-test.log` — 168 run, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- Example: `example-full-test.log` — 69 run, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- Focused framework lifecycle class: `framework-webhook-server-test.log` — 5 passed.
- Focused example wiring class: `example-wiring-test.log` — 18 passed.
- Scoped diff: `scoped-git-diff.patch` covers the two production Java files and their corresponding tests.

## Tooling note

Java LSP diagnostics timed out twice at the daemon boundary. No clean LSP result is available. Both Maven clean builds compiled all changed sources and passed all tests.
