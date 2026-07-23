# Issues

## 2026-07-23T15:27:50+08:00 — T17/F4 live verification blocked by stale local admission routing

- Cluster safety: context `docker-desktop`, server `https://127.0.0.1:6443`, node `docker-desktop` Ready; selected project release `echo-operator` in namespace `default` (Helm chart `echo-operator-0.1.0`).
- Deployment mode: `helm upgrade --install echo-operator example/echo-operator/helm/echo-operator --namespace default --create-namespace --reset-values --set image.tag=latest --set webhook.enabled=true --set webhook.certAutoGenerate=true`.
- Runtime evidence: image build `sha256:cae3a5579f150811a6d32e56ab932db958bb558e9fd757e17d1453bdda3c2310`; Pod reports `Ready=True`, but `Endpoints/echo-operator` retains `notReady=10.1.1.244` and no ready address. The v1alpha1 apply was rejected with `failed calling webhook ... dial tcp ...:443: connect: connection refused`.
- Root causes found and fixed in the working tree: shaded JAR carried invalid `.SF`/`.RSA` signatures (JVM crash); example admission registration used wildcard rules and intercepted unrelated core resources. Regressions were red then green; Maven verification passed: framework 156/156 and example 45/45; shaded-JAR signature assertion exit 0.
- Cluster recovery blocker: the pre-fix live mutating webhook rules are `operations=["*"] apiGroups=["*"] apiVersions=["*"] resources=["*"]`. With no ready Service endpoint, the API server rejects attempts to delete the project Endpoints and the operator Pod, so the corrected image/rules cannot be deployed. No Secret private-key data was read or printed.
- Required administrator recovery before rerun: clear or repair the stale `echo-operator.default.echo.example.com` admission webhook configurations and the EndpointSlice/Endpoints reconciliation in this local cluster (or reset/redeploy the local project release), then rerun T17 from the explicit Helm deployment command above.
- Verdict: **T17 REJECT** (mandatory v1alpha1/v1alpha2 conversion, equal-CA Helm upgrade, post-restart conversion, Secret-key, and in-Pod file assertions could not be completed). **F4 REJECT** (its sole required live evidence is absent).

## 2026-07-23T15:38:20+08:00 — T17/F4 recovered and approved

- Project-scoped recovery: deleted only `MutatingWebhookConfiguration/echo-operator.default.echo.example.com` and `ValidatingWebhookConfiguration/echo-operator.default.echo.example.com` (both delete exit 0), then deleted only `Endpoints/echo-operator` and its controller-managed EndpointSlice. Kubernetes recreated `EndpointSlice/echo-operator-rvtf8`; bounded wait passed and Endpoints became `ready=10.1.1.244`.
- Deployment: Helm release `echo-operator/default`, `webhook.certAutoGenerate=true`, revision 5, status `deployed`. Restarted Pod `echo-operator-7d64dc9b7f-vhxbs` is Ready and its imageID exactly matches the rebuilt `sha256:cae3a5579f150811a6d32e56ab932db958bb558e9fd757e17d1453bdda3c2310`.
- Live admission rules after recovery: both validating and mutating configurations have operations `CREATE,UPDATE`, apiGroups `example.com`, apiVersions `v1alpha1,v1alpha2`, resources `echoresources`, scope `Namespaced`, failurePolicy `Fail`.
- Conversion: v1alpha1 and v1alpha2 apply both succeeded before the same-value Helm upgrade and again after Pod restart; all reads report stored `apiVersion=example.com/v1alpha2`.
- Upgrade preservation: CRD caBundle `BEFORE` and `AFTER` both had length `1516`, SHA-256 `e89a850a15c3a2fa653d251cf60bf86be1d0821c14e7a247165d6c3e3784e6d2`, and equality exit `0`.
- Restart persistence: `echo-operator-webhook-ca` is `Opaque` and prints only `ca.crt,ca.key`; `kubectl exec` confirmed `/tmp/echo-operator/certs/ca.key` is absent (exit 0). Secret CA and CRD caBundle both had the same length/hash above, equality exit `0`. No private key content was printed.
- This supersedes the prior blocker: **T17 APPROVE** and **F4 APPROVE**.
