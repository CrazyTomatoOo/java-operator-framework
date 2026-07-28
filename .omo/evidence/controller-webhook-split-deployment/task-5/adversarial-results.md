# Task 5 adversarial results

- **Selector mismatch:** parsed YAML derives selected Pod templates from each Service selector. Actual controller and webhook Services select only their matching component. A copied controller Service with the webhook selector fails the controller-target expectation, proving webhook traffic cannot target the controller by manifest construction.
- **Ownership matrix:** auto-generated split asserts controller cleanup/self-registration false and webhook cleanup/self-registration false/true with exact predecessor names. External Helm-owned split asserts controller remains false/false and webhook becomes true/false without predecessors.
- **TLS matrix:** auto-generated TLS requires webhook-only `emptyDir`, `fsGroup`, cert env, and mount. External TLS requires webhook-only read-only Secret mount and literal CA path. The controller has neither form in both renders.
- **Service identity:** parsed CRD, validating, and mutating targets all resolve `contract-echo-operator.contract-ns:443`; their external CA bundles decode to the literal PEM bytes.
- **Replica safety:** the poison split render uses three webhook replicas without a validation restriction. The focused JUnit test runs two concurrent resolver instances against one Secret, compares CA bytes, validates both local server certificates against that CA, and checks each local key/certificate pair.
- **Flake resistance:** the full parsed Helm contract passed twice consecutively. The concurrent test completed with six focused tests and no skips.
