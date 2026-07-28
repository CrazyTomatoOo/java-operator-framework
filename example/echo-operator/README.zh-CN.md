# Echo Operator

一个基于 `operator-framework` 构建的示例 Kubernetes Operator。它监听 `EchoResource` 自定义资源，并为每个资源创建 Deployment 与 Service。该示例演示了 Finalizer、OwnerReference、Status 更新、重试、Metrics、健康探针以及 Leader Election。

## 功能说明

创建 `EchoResource` 时：

- Operator 会添加 Finalizer。
- 创建 Deployment 和 Service，并设置 OwnerReference 指向 `EchoResource`。
- 将 `status.phase` 更新为 `READY`，并将 `spec.message` 写入 `status.message`。

删除 CR 时：

- Finalizer 触发清理逻辑。
- 清理完成后移除 Finalizer，Kubernetes 自动回收 Deployment 和 Service。

## 前置条件

- Java 21
- Maven 3.9+
- Docker
- Helm 3
- 可访问 Kubernetes 集群的 kubectl

## 构建项目

```bash
mvn -f example/echo-operator/pom.xml clean package
```

## 本地运行

本地运行脚本会读取当前 kubectl 命名空间，并使用当前 kubeconfig 启动 Operator：

```bash
example/echo-operator/scripts/local-run.sh
```

可通过环境变量覆盖配置：

```bash
export OPERATOR_NAMESPACE=default
export METRICS_PORT=8080
export LEADER_ELECTION_ENABLED=false
export LEADER_ELECTION_NAMESPACE=default
export LEADER_ELECTION_LOCK_NAME=echo-operator-lock
example/echo-operator/scripts/local-run.sh
```

Operator 暴露以下端点：

- `http://localhost:8080/healthz`
- `http://localhost:8080/readyz`
- `http://localhost:8080/metrics`

## 构建 Docker 镜像

```bash
example/echo-operator/scripts/build-image.sh
```

该脚本会先打包 jar，然后执行：

```bash
docker build -t example/echo-operator:latest example/echo-operator
```

## 使用 Helm 部署

```bash
example/echo-operator/scripts/deploy.sh
```

脚本会构建镜像；如果当前 kubectl context 是 `kind-*`，还会将镜像加载到 kind 集群，最后安装 Helm Chart。

卸载：

```bash
example/echo-operator/scripts/undeploy.sh
```

你也可以在本地渲染 Chart：

```bash
helm template echo-operator example/echo-operator/helm/echo-operator
helm lint example/echo-operator/helm/echo-operator
```

## 示例 CR

参见 `example/echo-operator/examples/echo-cr.yaml`：

```yaml
apiVersion: example.com/v1alpha1
kind: EchoResource
metadata:
  name: my-echo
spec:
  message: "Hello from Echo Operator"
  replicas: 1
```

应用示例：

```bash
kubectl apply -f example/echo-operator/examples/echo-cr.yaml
```

查看结果：

```bash
kubectl get echoresources
kubectl get deployment my-echo
kubectl get service my-echo
```

## Webhook

Echo Operator 在共享的 TLS 服务器上注册了一个校验 Webhook、一个变更 Webhook 和一个转换 Webhook。Webhook 服务器默认监听 8443 端口，并通过 Helm Service 在 443 端口暴露。

### 校验 Webhook

`EchoValidatingWebhook` 在 CR 持久化前拒绝不合法的请求：

- 缺少 `spec`
- `spec.message` 为空
- `spec.message` 超过 140 个字符
- `spec.replicas` 为负数

### 变更 Webhook

`EchoMutatingWebhook` 在 CR 持久化前注入默认值：

- 添加注解 `echo.example.com/defaulted: "true"`
- 当 `spec.replicas` 缺失或非正数时，设置为 1

### 转换 Webhook

Echo Operator 支持 `EchoResource` 的两个 API 版本：

- `example.com/v1alpha1` 已弃用，不再是存储版本。
- `example.com/v1alpha2` 是存储版本，新增了 `spec.logLevel`。

`EchoConverter` 负责版本间转换。从 `v1alpha1` 转换到 `v1alpha2` 时，`logLevel` 默认设置为 `INFO`；从 `v1alpha2` 转换到 `v1alpha1` 时，`logLevel` 会被移除。

资源类使用 fabric8 `@Version` 注解：

```java
@Version(value = "v1alpha1", storage = false, served = true, deprecated = true)
public class EchoResourceV1 extends CustomResource<EchoSpecV1, EchoStatusV1> implements Namespaced {
}

@Version(value = "v1alpha2", storage = true, served = true)
public class EchoResourceV2 extends CustomResource<EchoSpecV2, EchoStatusV2> implements Namespaced {
}
```

## 环境变量

除本地运行章节列出的变量外，Echo Operator 还支持：

- `WEBHOOK_PORT` - TLS Webhook 服务器端口，默认 `8443`
- `CONTROLLER_ENABLED` - 是否启用调和控制器，默认 `true`。`CONTROLLER_ENABLED` 与 `WEBHOOK_ENABLED` 至少有一个必须为 `true`。
- `WEBHOOK_ENABLED` - 是否启用 Webhook 服务与注册，默认 `true`。设为 `false` 时，Operator 停止提供 Admission 与转换 Webhook，删除其拥有的过期 `ValidatingWebhookConfiguration` 与 `MutatingWebhookConfiguration`，将 CRD 转换策略渲染为 `None`，并保留 Metrics 与 Health 端点。**仅在 combined 模式下合法。** Split 模式要求 `webhook.enabled=true`，设为 `false` 会导致 Helm 渲染失败。
- `WEBHOOK_REGISTRATION_CLEANUP_ENABLED` - 是否清理过期的运行时拥有的 Admission Webhook 配置，默认 `true`。当 Helm 拥有 Admission 配置（`createWebhookConfigurations=true`）时，Operator 会在启动时清理残留的运行时配置。
- `WEBHOOK_SELF_REGISTRATION_ENABLED` - 是否启用运行时自注册 Admission Webhook 配置，默认 `true`。设为 `false` 时，Operator 不会创建或更新 `ValidatingWebhookConfiguration` 与 `MutatingWebhookConfiguration`。
- `WEBHOOK_PREDECESSOR_VALIDATING_NAME` - Operator 在注册自身前必须等待其被删除的前驱 `ValidatingWebhookConfiguration` 名称，默认未设置。用于 Helm 到运行时的所有权过渡。
- `WEBHOOK_PREDECESSOR_MUTATING_NAME` - Operator 在注册自身前必须等待其被删除的前驱 `MutatingWebhookConfiguration` 名称，默认未设置。用于 Helm 到运行时的所有权过渡。
- `WEBHOOK_VALIDATING_ENABLED` - 是否启用校验 Admission Webhook，默认 `true`。设为 `false` 时，Operator 不注册 `ValidatingWebhookConfiguration` 并删除其拥有的过期配置。HTTP 端点仍作为安全网保留并返回拒绝响应。
- `WEBHOOK_MUTATING_ENABLED` - 是否启用变更 Admission Webhook，默认 `true`。设为 `false` 时，Operator 不注册 `MutatingWebhookConfiguration` 并删除其拥有的过期配置。HTTP 端点仍作为安全网保留并返回拒绝响应。
- `WEBHOOK_CONVERSION_ENABLED` - 是否启用 CRD 转换 Webhook，默认 `true`。设为 `false` 时，Operator 不补丁 CRD 转换 Webhook 客户端配置，转换端点返回失败响应。
- `WEBHOOK_CERT_AUTO_GENERATE` - 是否自动生成 Webhook 证书，默认 `true`。设为 `true` 时，Operator 在启动时生成 CA 与服务端证书，并将 CA（私钥与证书）持久化到 `WEBHOOK_CERT_SECRET_NAME` 指定的 Kubernetes Secret。CA 可在 Pod 重启与 Helm 升级后保留，因为它存储在 Secret 中而非本地磁盘。私钥不会写入文件系统。
- `WEBHOOK_CERT_SECRET_NAME` - 当 `WEBHOOK_CERT_AUTO_GENERATE=true` 时保存 Webhook CA 的 Secret 名称，默认 `echo-operator-webhook-ca`。Operator 会在 Secret 不存在时创建它，并在后续启动时从中读取 CA。
- `WEBHOOK_CERT_DIRECTORY` - 生成证书的存放目录，默认 `/tmp/echo-operator/certs`
- `WEBHOOK_SERVICE_NAME` - Webhook 自注册使用的 Kubernetes Service 名称，默认 `echo-operator`
- `WEBHOOK_SERVICE_NAMESPACE` - Webhook Service 的命名空间，默认为 Operator Pod 命名空间（见 `OPERATOR_POD_NAMESPACE`）
- `OPERATOR_POD_NAMESPACE` - Operator Pod 运行的命名空间，默认为监听的 `OPERATOR_NAMESPACE`。用作 `WEBHOOK_SERVICE_NAMESPACE` 的默认值以及命名空间级资源查找。
- `WEBHOOK_CA_BUNDLE_PATH` - 当 `WEBHOOK_CERT_AUTO_GENERATE=false` 时的回退路径；此时会同时使用同目录下的 `tls.crt` 和 `tls.key` 作为服务端证书和私钥，默认 `/etc/echo-operator/certs/ca.crt`

本地开发时可以在运行 `local-run.sh` 前设置：

```bash
export WEBHOOK_PORT=8443
export WEBHOOK_ENABLED=true
export WEBHOOK_VALIDATING_ENABLED=true
export WEBHOOK_MUTATING_ENABLED=true
export WEBHOOK_CONVERSION_ENABLED=true
export WEBHOOK_CERT_AUTO_GENERATE=true
export WEBHOOK_CERT_SECRET_NAME=echo-operator-webhook-ca
export WEBHOOK_CERT_DIRECTORY=/tmp/echo-operator/certs
export WEBHOOK_SERVICE_NAME=echo-operator
# 仅在 WEBHOOK_CERT_AUTO_GENERATE=false 时使用
export WEBHOOK_CA_BUNDLE_PATH=/tmp/echo-operator/certs/ca.crt
```

## Helm Webhook 配置值

Chart 在 `values.yaml` 中支持以下 Webhook 相关配置：

```yaml
webhook:
  port: 8443
  caBundle: ""                    # CRD 转换与 Admission 客户端配置使用的 base64 CA 证书
  path: /convert
  certAutoGenerate: true          # 启动时生成 CA 与服务端证书；CA 持久化到 certSecretName
  certSecretName: echo-operator-webhook-ca  # certAutoGenerate 为 true 时保存 CA 的 Secret
  createWebhookConfigurations: false   # 设为 true 可通过 Helm 预创建 Webhook 配置（要求 certAutoGenerate=false）
  failurePolicy: Fail
  timeoutSeconds: 10
  admissionName: echo.example.com
  tls:
    secretName: echo-operator-webhook-tls
  service:
    namespace: ""
    name: ""
    port: 443
```

TLS Secret 需由集群管理员或外部工具在 Chart 外部创建。`createWebhookConfigurations` 为 `false`（默认）时，Operator 会在启动时自行注册 Admission Webhook 配置。

## 部署模式

Helm Chart 支持两种部署模式，通过 `values.yaml` 中的 `deploymentMode` 控制。

### Combined 模式（默认）

当 `deploymentMode: combined`（默认）时，Chart 渲染一个 Deployment、一个 Service 和一个 ServiceAccount。Combined 工作负载在一个进程中同时运行调和控制器与 Webhook 服务器。这是最简单的部署路径，也是新安装的默认模式。

```yaml
deploymentMode: combined  # 默认
webhook:
  enabled: true           # 默认
```

设置 `webhook.enabled=false` 仅在 combined 模式下合法。它会生成一个仅控制器的 Deployment，只暴露 Metrics 端口，将 CRD 转换策略渲染为 `None`，且不创建 Admission 配置。

### Split 模式（可选）

当 `deploymentMode: split` 时，Chart 渲染两个 Deployment、两个 Service 和两个 ServiceAccount：

| 资源 | Controller | Webhook |
|------|-----------|---------|
| Deployment | `<fullname>-controller` | `<fullname>-webhook` |
| Service | `<fullname>-controller` | `<fullname>`（Webhook Service） |
| ServiceAccount | `<fullname>-controller` | `<fullname>-webhook` |

Controller Deployment 以 `CONTROLLER_ENABLED=true` 和 `WEBHOOK_ENABLED=false` 运行。Webhook Deployment 以 `CONTROLLER_ENABLED=false` 和 `WEBHOOK_ENABLED=true` 运行。两者使用相同的容器镜像。

**Split 模式要求 `webhook.enabled=true`。** 设置 `deploymentMode: split` 且 `webhook.enabled=false` 会导致 Helm 渲染失败。

#### 部署示例

以 combined 模式部署（默认）：

```bash
helm install echo-operator ./helm/echo-operator \
  --set deploymentMode=combined
```

以 split 模式部署：

```bash
helm install echo-operator ./helm/echo-operator \
  --set deploymentMode=split
```

或使用部署脚本：

```bash
# Combined 模式
DEPLOYMENT_MODE=combined ./scripts/deploy.sh

# Split 模式
DEPLOYMENT_MODE=split ./scripts/deploy.sh
```

### 嵌套工作负载值

Split 模式的工作负载不会继承顶层 combined 工作负载值。每个 split 工作负载有自己的嵌套块：

```yaml
controller:
  workload:
    replicas: 1
    resources:
      limits: { cpu: 500m, memory: 512Mi }
      requests: { cpu: 100m, memory: 128Mi }
    podAnnotations: {}
    podLabels: {}
    nodeSelector: {}
    tolerations: []
    affinity: {}
    serviceAccount:
      create: true
      name: ""

webhook:
  workload:
    replicas: 1
    resources:
      limits: { cpu: 500m, memory: 512Mi }
      requests: { cpu: 100m, memory: 128Mi }
    podAnnotations: {}
    podLabels: {}
    nodeSelector: {}
    tolerations: []
    affinity: {}
    serviceAccount:
      create: true
      name: ""
```

顶层值（`replicas`、`resources`、`podAnnotations`、`podLabels`、`nodeSelector`、`tolerations`、`affinity`、`serviceAccount.name`）仅适用于 combined Deployment。Split 工作负载仅从各自的嵌套块 `controller.workload.*` 和 `webhook.workload.*` 读取。

### 独立 RBAC 与监听命名空间

在 split 模式下，controller 与 webhook ServiceAccount 有独立的 RBAC 绑定：

- **Controller ServiceAccount**：获得 controller Role（EchoResource CRUD、pods/services/deployments/events），以及当 `leaderElection.enabled=true` 时在 release 命名空间中的 lease Role。它不会获得 Admission 注册或 CRD 补丁权限。
- **Webhook ServiceAccount**：获得 webhook ClusterRole（Admission 注册、CRD 补丁、barrier 读取），以及当 `webhook.certAutoGenerate=true` 时的 CA Secret 读取 Role。它不会获得 EchoResource 或工作负载管理权限。

Controller Role 的作用域是 `operator.namespace`（监听命名空间）。当 `operator.namespace` 与 release 命名空间不同且 `leaderElection.enabled=true` 时，lease Role 在 release 命名空间中创建，而 controller Role 作用于监听命名空间。

## 证书模式

### 自动生成（默认）

当 `webhook.certAutoGenerate=true`（默认）时，Operator 在启动时生成 CA 与服务端证书。CA 私钥与证书持久化到 `webhook.certSecretName`（默认 `echo-operator-webhook-ca`）指定的 Kubernetes Secret。CA 可在 Pod 重启与 Helm 升级后保留，因为它存储在 Secret 中而非本地磁盘。私钥不会写入文件系统。

### 外部 TLS

当 `webhook.certAutoGenerate=false` 时，必须提供：

1. 一个 TLS Secret（由 `webhook.tls.secretName` 指定），包含 `ca.crt`、`tls.crt` 和 `tls.key`，由集群管理员或外部工具在外部创建。
2. `webhook.caBundle` 中的字面量 PEM CA 证书。Helm 对其进行一次 base64 编码，并嵌入到 CRD 转换客户端配置和任何 Helm 拥有的 Admission 配置中。

Operator 从挂载的 Secret 中读取 CA 证书和同目录下的 `tls.crt`/`tls.key`，路径为 `WEBHOOK_CA_BUNDLE_PATH`（默认 `/etc/echo-operator/certs/ca.crt`）。

### caBundle 字面量 PEM

`webhook.caBundle` 值必须是字面量 PEM 证书（包含 `-----BEGIN CERTIFICATE-----` / `-----END CERTIFICATE-----` 信封和有效的 base64 DER 体）。Helm 在渲染时验证 PEM 体，拒绝仅信封或非证书数据。

## Admission 所有权

Admission 配置（`ValidatingWebhookConfiguration` 和 `MutatingWebhookConfiguration`）在任意时刻由恰好一个权威方拥有。

### 运行时拥有（默认）

当 `webhook.createWebhookConfigurations=false`（默认）时，Operator 在启动时自注册 Admission 配置。运行时拥有的配置名为 `echo-operator.<watched-namespace>.echo.example.com`。Operator 创建、更新和删除它们。

Helm Chart 注入前驱 barrier 环境变量，使运行时能够等待任何残留的 Helm 拥有配置被删除后再注册自己的配置：

```
WEBHOOK_PREDECESSOR_VALIDATING_NAME=<fullname>-validating
WEBHOOK_PREDECESSOR_MUTATING_NAME=<fullname>-mutating
```

### Helm 拥有

当 `webhook.createWebhookConfigurations=true`（要求 `webhook.certAutoGenerate=false`）时，Helm Chart 直接创建 Admission 配置。Helm 拥有的配置名为 `<fullname>-validating` 和 `<fullname>-mutating`。Operator 不自注册，但在启动时清理任何残留的运行时拥有配置（`WEBHOOK_REGISTRATION_CLEANUP_ENABLED=true`，`WEBHOOK_SELF_REGISTRATION_ENABLED=false`）。

### 所有权过渡

运行时拥有与 Helm 拥有之间的过渡是双向的：

- **Helm 到运行时**：设置 `createWebhookConfigurations=false`。新的运行时 Deployment 等待被持有的 Helm 配置被删除（前驱 barrier），然后注册自己的配置。过渡期间，新的 webhook Pod 在 `/readyz` 上报告 `503`，直到前驱消失。
- **运行时到 Helm**：设置 `createWebhookConfigurations=true`，`certAutoGenerate=false`，并提供有效的 `caBundle`。Helm Chart 创建其配置，运行时在下次启动时清理自己的配置。

## 验证命令

```bash
# Lint Chart
helm lint example/echo-operator/helm/echo-operator

# 运行 Helm 契约测试（静态，无需集群）
example/echo-operator/scripts/helm-contract-test.sh

# 运行文档契约测试（静态，无需集群）
example/echo-operator/scripts/docs-contract-test.sh

# Combined 模式冒烟测试（需要 Kubernetes 集群）
DEPLOYMENT_MODE=combined example/echo-operator/scripts/smoke-test.sh

# Split 模式冒烟测试（需要 Kubernetes 集群）
DEPLOYMENT_MODE=split example/echo-operator/scripts/smoke-test.sh

# Maven 回归
mvn -f example/echo-operator/pom.xml clean verify
```

## 不包含的功能

Chart 与 Operator 不提供以下功能：

- **cert-manager 集成**：证书管理由 Operator 内置的自动生成功能或外部提供的 TLS Secret 处理。
- **CA 轮换**：自动生成的 CA 是持久的（存储在 Secret 中），但不会自动轮换。如需轮换，请删除 Secret 并重启 Operator。
- **HA/PDB/HPA**：高可用、PodDisruptionBudget 和 HorizontalPodAutoscaler 配置不在本 Chart 范围内。
- **框架级部署模式 API**：`deploymentMode` 是 Helm Chart 值，不是框架级 API。Operator 框架本身没有 combined 与 split 模式的概念。
- **第二镜像或 JAR**：combined 与 split 模式使用相同的容器镜像。Split 模式通过运行时环境变量（`CONTROLLER_ENABLED`、`WEBHOOK_ENABLED`）区分行为，而非通过独立的构件。

## v1alpha2 示例 CR

也可以创建 `v1alpha2` 的 CR：

```yaml
apiVersion: example.com/v1alpha2
kind: EchoResource
metadata:
  name: my-echo-v2
spec:
  message: "Hello from v1alpha2"
  replicas: 2
  logLevel: DEBUG
```

保存为 `echo-v2.yaml` 后应用：

```bash
kubectl apply -f echo-v2.yaml
```

由于 `v1alpha2` 是存储版本，读回资源时会显示 `apiVersion: example.com/v1alpha2` 和 `spec.logLevel`。 
