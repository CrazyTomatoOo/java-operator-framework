# Problems — controller-webhook-split-deployment

Unresolved blockers and technical debt discovered during work on this plan.

_Auto-scaffolded by /start-work. Append new entries below - never overwrite._

---

- 2026-07-27: **BLOCKED Task 8 live QA.** A reachable Kubernetes API and Docker daemon are required to build/load the image and execute combined, split, upgrade, ownership-transition, EndpointSlice, impersonation, and external-TLS assertions. Current `docker-desktop` context is unreachable (`127.0.0.1:6443` connection refused); see `.omo/evidence/controller-webhook-split-deployment/{combined,split}/cluster-prereq-*/cluster-info.log`.

- 2026-07-27: The Task 8 local port-forward regression passed without Kubernetes, but it does not substitute for cluster smoke. Keep the task blocked until `kubectl cluster-info` and `docker info` succeed, then rerun both topology commands against the live API.

- 2026-07-28: **RESOLVED Task 8 live blocker.** Docker Desktop API/daemon became reachable and final combined/split smoke receipts completed with status 0. The historical unreachable-context entries remain as provenance only; no live-QA blocker remains.
