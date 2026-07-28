---
slug: controller-webhook-split-deployment
intent: clear
review_required: true
classification: architecture
status: review-approved
created: 2026-07-27
---

# controller-webhook-split-deployment

## Request

新增功能：支持 controller 与 webhook 分离部署。

## Components ledger

| ID | Outcome | Status | Evidence |
|---|---|---|---|
| C1 | Java 进程可按 controller-only、webhook-only、combined 三种组合启动并正确停止 | grounded | `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java:113-323` |
| C2 | Helm 生成互不串流的 controller/webhook 工作负载与 Service selector | grounded | `example/echo-operator/helm/echo-operator/templates/deployment.yaml`, `service.yaml` |
| C3 | controller 与 webhook 权限边界正确，controller-only 不再因 Role 条件缺权 | grounded | `templates/role.yaml:1-39`, `templates/clusterrole.yaml:1-22` |
| C4 | 两个进程具有各自正确的 leader election、readiness、证书与注册生命周期 | grounded | `EchoOperatorMain.java:189-285`, prior cert persistence plan |
| C5 | Chart 同时支持 combined 与 split，默认 combined，并同步配置与文档 | decided | user decision: default combined |
| C6 | 单元、Helm 渲染与集群 smoke QA 覆盖组合/分离及错误配置 | grounded | `EchoOperatorMainWiringTest.java`, `scripts/smoke-test.sh` |

## Verified findings

- 当前单个 `EchoOperatorMain` 无条件创建并注册 controller；`WEBHOOK_ENABLED` 只能关闭 webhook，不能启动 webhook-only。
- 当前单个 Deployment/Service 用同一 selector 承载 metrics 与 webhook；直接复制 Deployment 会让 webhook Service 把请求路由到 controller pod。
- webhook-only 若复用当前 readiness，会因 `operator.eventSources()` 为空而永久不 Ready。
- controller-only 设置 `webhook.enabled=false` 时，当前 `Role` 不渲染，但 `RoleBinding` 仍渲染，controller 会失去 namespaced reconcile 权限。
- controller 在 webhook disabled 时会删除 stale admission registrations；分离部署后 controller 不应删除仍由 webhook 进程拥有的配置。
- 证书 Secret 并发创建、每 pod server cert、CRD/clientConfig patch 已实现；应由 webhook workload 独占该生命周期。
- 最小代码方向应保持 framework 的正交组件 API，不引入 framework-level mode enum；部署组合逻辑留在 example entrypoint/config 与 Helm。

## Adopted reversible defaults

- 使用同一镜像/同一 main，通过独立布尔能力开关组合三种运行形态；不新增第二个 jar 或 framework bootstrap abstraction。
- webhook workload 不参与 leader election，所有副本同时服务；controller workload 沿用 leader election 配置。
- webhook 独占证书生成/挂载、自注册和 CRD conversion patch；controller 独占 reconcile、event recorder 与 stale cleanup 之外的 controller lifecycle。
- controller 与 webhook 各自提供 metrics/health；webhook readiness 检查 server 已启动而不是 informer sync。

## Owner decisions pending

None.

## Owner decisions resolved

- Chart 同时支持 `combined` 与 `split` 两种拓扑，默认 `combined`；分离部署必须显式选择。
- controller 与 webhook 使用完全分离的 ServiceAccount、Role/ClusterRole 与 Binding，遵循最小权限。
- 使用 TDD；先锁定运行模式、readiness、Helm 资源与错误配置契约，再修改实现。
- 默认副本数采用 controller=1、webhook=1，分别可配置；不把未请求的 HA/PDB 纳入本次范围。

## Approved planning approach

1. 在 `EchoOperatorMain` 配置中增加 controller 能力开关；同一镜像通过互斥的 Helm 环境变量分别运行 controller-only 与 webhook-only。controller-only 不创建/注册/清理 webhook，不解析证书；webhook-only 不注册/启动 Operator、EventRecorder 或 leader election。
2. 将 readiness 与 shutdown 按能力拆开：controller readiness 等待 informer sync；webhook readiness 检查 webhook server 已启动；空能力组合在配置加载时 fail fast。
3. 增加显式 Helm 部署模式配置，默认 `combined` 沿用单 Deployment 运行 controller+webhook；选择 `split` 时渲染两个 Deployment、两个只选择各自 Pod 的 Service（controller metrics/health 与 webhook TLS/metrics/health）。split 模式下证书卷只挂载 webhook，leader election 只注入 controller。
4. RBAC 按运行拓扑渲染：split 模式使用完全分离的 ServiceAccount 与权限，controller 获得 reconcile/lease 权限，webhook 获得 CA Secret、自注册 admission configuration 与 CRD conversion patch 权限；combined 模式由单一身份获得两组必要权限。
5. 重构 values/helpers/templates、README 与 smoke test；smoke test等待两个 Deployment，分别验证 health/readiness、admission/conversion 与 reconciliation，并捕获证据。
6. 全程 TDD，覆盖 controller-only、webhook-only、非法双关/全关组合、独立 Service selector、权限边界、证书仅挂载 webhook，以及 Helm lint/template 的正反例。

## Scope guardrails

- Must NOT add a framework-level deployment-mode enum or bootstrap framework；仅修改 example 的组合入口与 Chart。
- Must NOT 增加第二个镜像或复制业务实现；两个 Deployment 复用同一镜像。
- Must NOT 引入 cert-manager、CA 自动轮换、PDB、HPA、跨 namespace 部署或新的外部依赖。
- Must NOT 静默推断部署模式；仅接受明确的 `combined`/`split` 值，非法值必须 Helm 渲染失败。
- Must NOT 让 controller 拥有 webhook Secret/admission/CRD patch 权限，或让 webhook 拥有 EchoResource reconcile/lease 权限。

## Pending action

Run required dual high-accuracy review of `.omo/plans/controller-webhook-split-deployment.md`; implementation remains separate.

## Review state

```json
{
  "transition": "replace",
  "phase": "review_round_initialized",
  "atomic": true,
  "review_required": true,
  "plan_path": ".omo/plans/controller-webhook-split-deployment.md",
  "plan_sha256": "8d51d6a5158d9c5a250241e1f7613e788c1caf0eff5581313dff5fc0203fa56a",
  "review_round_id": "controller-webhook-split-20260727-r4",
  "round_status": "approved",
  "pending-action": "start work in a separate worker session",
  "review": {
    "momus": {"status": "approved", "workspace_root": "/Volumes/work/Project/java-operator-framework", "runtime_home": null, "target": ".omo/plans/controller-webhook-split-deployment.md", "round_id": "controller-webhook-split-20260727-r4", "plan_sha256": "8d51d6a5158d9c5a250241e1f7613e788c1caf0eff5581313dff5fc0203fa56a", "launch_id": "momus-controller-webhook-split-r4", "session": "ses_05da49fb0ffeiNxeXMTAsuGNM3", "result": "OKAY"},
    "independent": {"status": "approved", "workspace_root": "/Volumes/work/Project/java-operator-framework", "runtime_home": null, "target": ".omo/plans/controller-webhook-split-deployment.md", "round_id": "controller-webhook-split-20260727-r4", "plan_sha256": "8d51d6a5158d9c5a250241e1f7613e788c1caf0eff5581313dff5fc0203fa56a", "launch_id": "oracle-controller-webhook-split-r4", "session": "ses_05da49e3bffeNqfJacmBNjZzMP", "result": "OKAY"}
  }
}
```

## Review history

- Round `controller-webhook-split-20260727-r1`, SHA `7cfc041e75663518cccbc04c4aaf1ec819f567edee284dcb39b22006622b8c76`:
  - Momus session `ses_05dc120efffexG1xemmBR2BEEA`: changes requested — fixed Task 3 paths and made F1/F4 executable.
  - Oracle session `ses_05dc11d3fffes0xuSixlGxkkuq` / process `pid:14213`: changes requested — added ownership/readiness truth tables, exact values/TLS/RBAC matrices, identity-correct authorization, and immutable-upgrade QA.
- Round `controller-webhook-split-20260727-r2`, SHA `809c99042ff41369fe38b0cc100b1270ed60136bda88a78ae8946077ca70117f`:
  - Momus session `ses_05db3742fffesC0xhuyHjfJr42`: OKAY.
  - Oracle session `ses_05db3725effeR54sW5VTmo7Z3g`: changes requested — required external-TLS CRD CA, ownership-transition cleanup/tests, and an unrestricted added-lines scope audit; all integrated for round 3.
- Round `controller-webhook-split-20260727-r3`, SHA `7ad472cc3a22759f1a00b1e0283c42201d91d4b47b49a97b6236f889db383c1a`:
  - Momus session `ses_05dac74b4ffeA6b7UofN59liZD`: OKAY.
  - Oracle session `ses_05dac74abffeEkMipz6xwyRKDF`: changes requested — defined external CA byte equality/mismatch QA, a Helm-predecessor absence barrier, immutable BASE preflight, complete tracked/untracked scope audit, and exact Maven install ordering; integrated for round 4.
- Round `controller-webhook-split-20260727-r4`, SHA `8d51d6a5158d9c5a250241e1f7613e788c1caf0eff5581313dff5fc0203fa56a`:
  - Momus session `ses_05da49fb0ffeiNxeXMTAsuGNM3`: OKAY.
  - Oracle session `ses_05da49e3bffeNqfJacmBNjZzMP`: OKAY.
  - Final live validation session `ses_05da2a542ffevebUF8IenHqxUw`: digest MATCH, 44,078 bytes, regular file, no symlink.

## Metis gap-analysis findings integrated

- Valid capability matrix is `(controller=true, webhook=true)` combined, `(true,false)` controller-only, `(false,true)` webhook-only; only `(false,false)` is invalid.
- Add explicit cleanup ownership so split controller cannot delete webhook registrations while combined controller-only preserves existing stale-registration cleanup.
- Keep combined resource names/selectors unchanged; split uses component-specific Deployment identities while preserving the existing webhook Service DNS identity.
- Define exact values inheritance and split ServiceAccount collision validation.
- Keep webhook readiness example-local rather than expanding the framework API.
- Split RBAC must include controller EventRecorder permissions and handle watched namespace separately from release namespace.
- Avoid duplicate runtime/Helm admission registration when `createWebhookConfigurations=true`.
- Cluster smoke must fail rather than report success when no cluster is reachable, and must not apply an unrendered Helm template.
