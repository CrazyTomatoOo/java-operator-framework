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
- `WEBHOOK_CERT_AUTO_GENERATE` - 是否自动生成 Webhook 证书，默认 `true`
- `WEBHOOK_CERT_DIRECTORY` - 生成证书的存放目录，默认 `/tmp/echo-operator/certs`
- `WEBHOOK_CA_BUNDLE_PATH` - 当 `WEBHOOK_CERT_AUTO_GENERATE=false` 时的降级回退路径；此时会同时使用同目录下的 `tls.crt` 和 `tls.key` 作为服务端证书和私钥，默认 `/etc/echo-operator/certs/ca.crt`

本地开发时可以在运行 `local-run.sh` 前设置：

```bash
export WEBHOOK_PORT=8443
export WEBHOOK_CERT_AUTO_GENERATE=true
export WEBHOOK_CERT_DIRECTORY=/tmp/echo-operator/certs
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
  createWebhookConfigurations: false   # 设为 true 可通过 Helm 预创建 Webhook 配置
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
