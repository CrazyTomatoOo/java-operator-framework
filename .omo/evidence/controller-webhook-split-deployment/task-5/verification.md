# Task 5 verification

## Characterization before deepening

`python3 -c '... subprocess.run(["bash", "example/echo-operator/scripts/helm-contract-test.sh"], timeout=120) ...'`

Passed before the Task 5 template edit: `split skeleton characterization passed`, `split contract passed`, `poison isolation passed`, and the existing combined/TLS/negative contracts.

## RED proof

The same bounded contract command after adding Task 5 assertions but before editing `deployment.yaml` exited nonzero with:

```
AssertionError: split controller env WEBHOOK_REGISTRATION_CLEANUP_ENABLED must be false, got None
```

The failing-first parsed assertions were already present for controller/webhook ownership env, predecessor injection, leader-election exclusion, auto/external TLS surfaces, Service selectors, Service identity, probes/ports, and nested workload isolation.

## GREEN commands

```text
mvn -f operator/framework/pom.xml -Dtest=WebhookCertificateSecretManagerTest test
  -> BUILD SUCCESS; Tests run: 6, Failures: 0, Errors: 0, Skipped: 0

helm lint example/echo-operator/helm/echo-operator
  -> 1 chart(s) linted, 0 chart(s) failed

bash example/echo-operator/scripts/helm-contract-test.sh
  -> helm contract tests passed

bash example/echo-operator/scripts/helm-contract-test.sh
  -> helm contract tests passed
```

Both repeated contract runs passed the split auto-generated TLS and split external TLS contracts, all combined compatibility contracts, PEM encoding assertions, split workload poison isolation, and every existing invalid-render diagnostic.

## Java diagnostics

`lsp_diagnostics` was requested twice for `WebhookCertificateSecretManagerTest.java`; both requests timed out waiting for the shared LSP daemon (`/Users/crazytomatooo/.omo/lsp-daemon/v0.1.0/daemon.sock`). The targeted Maven compile/test above passed after the test edit. This daemon failure is retained as the only verification-environment risk.
