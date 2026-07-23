# Decisions

## Architecture decisions (from plan)
- CA Secret stores only `ca.crt`/`ca.key`; server cert regenerated locally per pod
- CA private key never written to local disk
- CRD `caBundle` and service fields patched at runtime via `patchConversionWebhookClientConfig`
- `caBundle` preserved on Helm upgrade via `lookup`
- `WEBHOOK_ENABLED=false` renders CRD `conversion.strategy: None`, keeps metrics/health, cleans up stale admission webhooks
- Self-registered admission webhook names use stable base name (not configurable service name)
- CA Secret lives in `OPERATOR_POD_NAMESPACE`, separate from watched `OPERATOR_NAMESPACE`
- `webhook.createWebhookConfigurations=true && webhook.certAutoGenerate=true` must fail in Helm