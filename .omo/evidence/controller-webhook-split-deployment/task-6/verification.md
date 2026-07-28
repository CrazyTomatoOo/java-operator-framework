# Task 6 verification

## Characterization and RED proof

Before changing the RBAC templates, the parsed contract passed
`combined identity/RBAC characterization passed`: one combined ServiceAccount was the
subject of every namespaced and cluster binding, and the rendered aggregate retained
controller, generated-CA, admission, and CRD paths.

After adding every parsed matrix assertion and before the template implementation, the
contract failed as intended:

```text
AssertionError: combined-auto: RBAC object set mismatch:
{('Role', 'contract-echo-operator', 'contract-ns'), ...}
!=
{('Role', 'contract-echo-operator-controller', 'contract-ns'),
 ('Role', 'contract-echo-operator-webhook-secret', 'contract-ns'), ...}
```

The RED suite already included combined/split, watched namespace plus lease, auto TLS,
external runtime TLS, external Helm-owner TLS, controller-only cleanup, and unmanaged
custom-SA render rows.

## GREEN commands

```text
rtk helm lint example/echo-operator/helm/echo-operator
  -> 1 chart(s) linted, 0 chart(s) failed

bash example/echo-operator/scripts/helm-contract-test.sh
  -> helm contract tests passed

bash example/echo-operator/scripts/helm-contract-test.sh
  -> helm contract tests passed
```

Each contract run parsed exact RBAC object/rule sets and explicit denial probes. The
second run repeated the whole suite for flake resistance.

## Protected dirty-path integrity

`shasum -a 256` for all ten Task 5 protected Java/framework/docs paths exactly matched
the Task 5 receipt. No protected dirty source path changed.

## Diagnostics

`lsp_diagnostics` was requested for every changed YAML template and the changed Bash
contract script. The configured `yaml-ls` and Bash LSP are unavailable because their
installation was previously declined. Helm lint plus two successful parsed render suites
are the available syntax/semantic verification for these file types.

## Watched-namespace admission-name correction

The independent failure was preserved before the fix with a parsed Helm render:

```text
release namespace: release-ns
operator.namespace: watched-ns
RED preserved: rendered=echo-operator.release-ns.echo.example.com
java-expected=echo-operator.watched-ns.echo.example.com
```

The contract now derives the expected runtime name from the rendered controller Role's
effective watched namespace and adds `split-release-watched-lease`. Both validating and
mutating resource-name rules must equal `echo-operator.watched-ns.echo.example.com`,
while Role/Binding placement and release-namespace ServiceAccount subjects remain exact.

```text
rtk helm lint example/echo-operator/helm/echo-operator  -> pass (twice)
bash example/echo-operator/scripts/helm-contract-test.sh -> pass (twice)
```
