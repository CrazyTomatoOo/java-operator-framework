# Task 6 adversarial outcomes

- **Malformed inputs:** the full contract retained exact failing diagnostics for missing
  and colliding split custom ServiceAccounts, invalid topology/TLS combinations, reserved
  labels, and invalid webhook Service names.
- **Least privilege:** parsed rule equality rejects any extra API group, resource,
  resource name, or verb. Explicit checks deny controller Secret/admission/CRD access and
  webhook reconcile/Event/Lease access in split mode.
- **Ownership variants:** runtime admission owner has separate unscoped create and
  stable-name mutation rules; predecessor polling is get-only; Helm-owner cleanup is
  stable-name get/delete only; static external conversion has no CRD rule.
- **Namespace correctness:** a watched namespace render places the controller
  Role/RoleBinding in `watched-ns` while preserving the `contract-ns` ServiceAccount
  subject; leader Lease Role/Binding remain in `contract-ns`.
- **Stale/dirty protection:** combined/split workload contracts still pass and all ten
  protected pre-existing dirty-file SHA-256 hashes remain unchanged.
- **Flake/hang resistance:** every Helm invocation runs through the script's 30-second
  bounded runner; two complete sequential runs passed.
- **Stale namespace expectation:** the contract computes the expected runtime admission
  name from the parsed controller Role namespace rather than a hardcoded release
  namespace. The explicit `release-ns`/`watched-ns`/Lease render rejects the former
  `echo-operator.release-ns.echo.example.com` value for both admission kinds.
