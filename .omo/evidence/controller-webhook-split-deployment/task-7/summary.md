# Task 7 ownership evidence

## RED

- `java-red.log`: `helmOwnedWebhookDoesNotWaitForOrDeleteHelmPredecessorNames` failed because the Helm-owned path entered `awaitPredecessorRemoval` and issued an exact-name GET.
- `helm-red.log`: combined runtime-owned external-TLS render failed because `WEBHOOK_PREDECESSOR_VALIDATING_NAME` was absent.

## GREEN

- `java-green.log`: `EchoOperatorMainWiringTest` passed 16/16.
- `helm-green.log`: combined and split ownership-transition contracts passed, including custom resolved Service identity.

## Final verification

See `ownership-tests.log`:

- Framework `WebhookSelfRegistrationTest`: 9/9 passed.
- Focused `EchoOperatorMainWiringTest,EchoOperatorMainConfigTest,EchoOperatorMainTest`: 35/35 passed.
- Full example suite: 67/67 passed, zero skipped.
- Helm lint: 1 chart linted, 0 failed.
- Helm contract: passed all positive, transition, TLS, identity, RBAC, and negative cases.

JDT LS diagnostics were requested twice for both changed Java files and timed out; Maven compiler/tests are the recorded hard fallback gate.
