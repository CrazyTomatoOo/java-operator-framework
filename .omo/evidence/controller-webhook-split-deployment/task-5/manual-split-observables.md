# Task 5 manual parsed-render observables

Command used a temporary CA and parsed `helm template contract ... --namespace contract-ns` YAML with Python. The temporary directory was removed by `TemporaryDirectory`.

## Split auto-generated TLS

- Deployments: `contract-echo-operator-controller` and `contract-echo-operator-webhook`; both use `example/echo-operator:latest` with `IfNotPresent`.
- Controller selector and controller Service selector are `app.kubernetes.io/name=echo-operator`, `app.kubernetes.io/instance=contract`, `app.kubernetes.io/component=controller`; Service exposes `8080 -> metrics` only.
- Controller env includes `CONTROLLER_ENABLED=true`, `WEBHOOK_ENABLED=false`, `WEBHOOK_REGISTRATION_CLEANUP_ENABLED=false`, `WEBHOOK_SELF_REGISTRATION_ENABLED=false`, `LEADER_ELECTION_ENABLED=false`, and downward-API leader namespace. It exposes metrics only, probes `/healthz` and `/readyz` through `metrics`, and has no mounts, volumes, or `fsGroup`.
- Webhook selector and webhook Service selector use the same name/instance labels with `app.kubernetes.io/component=webhook`; Service exposes `443 -> webhook` only.
- Webhook env includes `CONTROLLER_ENABLED=false`, `WEBHOOK_ENABLED=true`, cleanup `false`, self-registration `true`, predecessor names `contract-echo-operator-validating` and `contract-echo-operator-mutating`, Service identity `contract-echo-operator` / `contract-ns` / `443`, and generated-cert settings at `/tmp/echo-operator/certs`.
- Webhook exposes metrics `8080` and TLS webhook `8443`; both probes use `metrics`; it has `fsGroup=1001`, `webhook-certs` mounted at `/tmp/echo-operator/certs`, and the matching `emptyDir` volume only.
- CRD conversion target is `contract-echo-operator.contract-ns:443` at `/convert`.

## Split external TLS with Helm-owned admission configurations

- Controller image, pull policy, labels, metrics port/probes, leader-election env, and empty TLS surface are byte-for-byte the same parsed shape as the auto-generated controller.
- Webhook has `CONTROLLER_ENABLED=false`, `WEBHOOK_ENABLED=true`, `WEBHOOK_REGISTRATION_CLEANUP_ENABLED=true`, `WEBHOOK_SELF_REGISTRATION_ENABLED=false`, no predecessor or leader-election env, and literal `WEBHOOK_CA_BUNDLE_PATH=/etc/echo-operator/certs/ca.crt`.
- Webhook mounts only Secret `echo-operator-webhook-tls` read-only at `/etc/echo-operator/certs`; it has no generated-certificate `fsGroup` or `emptyDir`.
- CRD conversion, validating admission, and mutating admission all target Service `contract-echo-operator` in `contract-ns` on `443`; their paths are `/convert`, `/validate/echo.example.com`, and `/mutate/echo.example.com` respectively.
- The parsed contract decodes every external `caBundle` and compares it to the literal PEM input bytes.

## Independent workload controls and selector adversary

`assert_poison_isolation` parsed distinct controller/webhook replicas (2/3), resources, annotations, labels, node selectors, tolerations, affinities, and ServiceAccounts while rejecting top-level combined workload inheritance. Its selector matcher confirms each actual Service targets only its intended Pod template, then intentionally substitutes the webhook selector into a controller-Service copy and confirms that this no longer targets the controller Pod.
