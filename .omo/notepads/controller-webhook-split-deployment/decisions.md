# Decisions — controller-webhook-split-deployment

Architectural choices and rationales discovered during work on this plan.

_Auto-scaffolded by /start-work. Append new entries below - never overwrite._

---

- 2026-07-27: Task 4 keeps combined mode as one unchanged Deployment/Service and expresses ownership only through Pod env: `CONTROLLER_ENABLED=true`, `WEBHOOK_ENABLED=<values>`, cleanup=`!webhook.enabled || webhook.createWebhookConfigurations`, and self-registration=`webhook.enabled && !webhook.createWebhookConfigurations`.
