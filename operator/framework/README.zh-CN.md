# Operator Framework

一个基于纯 fabric8 的轻量级 Java Kubernetes Operator 开发 SDK。不依赖 Quarkus、Spring Boot 或 Java Operator SDK（JOSDK）。

## 功能特性

- `Operator` 启动器，支持命名空间作用域或集群作用域 Informer
- `Reconciler<T>` 接口，以及 `Request`、`Result`
- `ResourceEventSource<T>`，封装 fabric8 `SharedIndexInformer`
- `LeaderElectionManager`，基于 fabric8 选举机制
- 组合的 `MetricsHealthServer`，同时暴露 `/metrics`、`/healthz`、`/readyz`
- `RetryPolicy` / `ExponentialBackoffRetryPolicy` 与 `RateLimiter`
- `OwnerReferenceHelper` 和 `FinalizerHelper` 工具类
- 基于 Java 21 与 Maven

## Maven 坐标

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 核心 API

### Operator

`com.huawei.dcs.modelengine.operator.framework.Operator` 用于注册控制器并运行 Informer/Worker 循环。

```java
Operator operator = new Operator()
    .withNamespace("default")
    .withWorkerThreads(2);
operator.register(MyResource.class, new MyReconciler(client));
operator.start();
```

调用 `operator.stop()` 或使用 try-with-resources 即可关闭 Informer 和 Worker 线程。

### Generation 变更过滤

默认情况下，主资源的每次 update 事件（包括 status 回写）都会触发一次 reconcile。对于会写 status 的控制器，这会产生自我触发的"回声" reconcile，白白占用 worker 线程。

使用 `.withGenerationChangeFilter()` 在事件源头过滤 update 事件：

```java
ControllerRegistration<MyResource> registration = ControllerBuilder.forResource(MyResource.class)
    .withReconciler(new MyReconciler())
    .withGenerationChangeFilter()
    .withResyncPeriod(Duration.ZERO) // 可选：关闭周期性 resync
    .build();
```

启用后，主资源的 update 事件仅在以下情况入队：

- 资源的 `generation` 发生变化（spec 更新）；
- 收到删除请求（`deletionTimestamp` 首次被设置）；
- `finalizers` 发生变化。

add 和 delete 事件始终入队，secondary source 的事件不受过滤影响。启用过滤后，周期性 resync 不会再让未变化的资源重复入队；可通过 `.withResyncPeriod(Duration.ZERO)` 完全关闭默认 60 秒的 resync，也可以保留它作为缓存自愈兜底。另外提供 `.withGenerationChangeFilter(boolean)`，便于按配置开关。

该过滤默认关闭，现有控制器行为不受影响：`operator.register(MyResource.class, reconciler)` 以及未调用 `.withGenerationChangeFilter()` 的 `ControllerBuilder` 用法与之前完全一致。

注意：对于未启用 status 子资源的 CRD，status 写入仍会提升 `generation`，此时过滤无效。

### Reconciler

```java
public interface Reconciler<T extends HasMetadata> {
    Result reconcile(Request request, T resource);
}
```

返回值含义：

- `Result.done()` - 完成，清除重试计数
- `Result.requeueNow()` - 立即重新入队
- `Result.requeueAfter(Duration)` - 延迟后重新入队
- `Result.error(Throwable)` - 失败，按配置策略重试

### ResourceEventSource

`ResourceEventSource<T>` 将 fabric8 Informer 的 add/update/delete 事件转换为内部阻塞队列中的 `Request`。默认 resync 间隔为 60 秒。

### LeaderElectionManager

`LeaderElectionManager` 封装 fabric8 `LeaderElector`。默认值：

- lease duration: 15s
- renew deadline: 10s
- retry period: 2s

```java
LeaderElectionManager leader = new LeaderElectionManager(client, "my-lock", "default")
    .withLeaseDuration(Duration.ofSeconds(15));
leader.run(() -> operator.start());
```

### MetricsHealthServer

`MetricsHealthServer` 在默认 8080 端口启动一个 JDK `HttpServer`，同时暴露：

- `/metrics` - Prometheus 指标
- `/healthz` - 存活探测，返回 200
- `/readyz` - 就绪探测，检查通过返回 200，否则返回 503

```java
MetricsHealthServer server = new MetricsHealthServer(8080);
server.addReadinessCheck(() -> operator.eventSources().stream()
    .allMatch(s -> s.getInformer().hasSynced()));
server.start();
```

### 重试与限流

`ExponentialBackoffRetryPolicy` 默认值：初始间隔 500ms，最大间隔 30s，最大尝试次数 5。

`RateLimiter` 限制同一资源 key 的处理频率，默认最小间隔 5 秒。

### 辅助类

- `OwnerReferenceHelper.createControllerOwnerReference(owner)` 返回 `controller=true`、`blockOwnerDeletion=true` 的 `OwnerReference`。
- `FinalizerHelper.hasFinalizer(resource, finalizer)`、`addFinalizer(...)`、`removeFinalizer(...)`。

## 构建

将 SDK 安装到本地 Maven 仓库：

```bash
mvn -f operator/framework/pom.xml clean install
```

运行测试：

```bash
mvn -f operator/framework/pom.xml test
```

两条命令的预期结果均为 `BUILD SUCCESS`。

## Admission Webhook

`WebhookServer` 基于 JDK `HttpsServer` 实现 TLS 服务。默认监听 `0.0.0.0:8443`，从 PEM 文件加载证书链和私钥，并可选地监视 `tls.crt`、`tls.key`、`ca.crt` 的变化。

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import java.nio.file.Path;

WebhookServer webhookServer = WebhookServer.withCertWatcher(
    WebhookServer.DEFAULT_HOST, 8443,
    Path.of("/etc/operator/certs/tls.crt"),
    Path.of("/etc/operator/certs/tls.key"),
    Path.of("/etc/operator/certs/ca.crt"),
    CertWatcher.DEFAULT_POLLING_INTERVAL);
```

Admission Webhook 通过 `AdmissionValidator<T>` 和 `AdmissionMutator<T>` 实现。

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionValidator;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;

public class MyValidator implements AdmissionValidator<MyResource> {
    @Override
    public AdmissionResponse validate(AdmissionRequest request, MyResource resource) {
        if (resource.getSpec() == null) {
            return AdmissionResult.denied("spec is required");
        }
        return AdmissionResult.allowed();
    }
}

AdmissionHandler handler = new AdmissionHandler(client);
handler.registerValidator("my.example.com", MyResource.class, new MyValidator());
handler.registerMutator("my.example.com", MyResource.class, new MyMutator());
handler.register(webhookServer);
webhookServer.start();
```

`AdmissionHandler.register(WebhookServer)` 会为每个已注册的校验器/变更器暴露 `/validate/{name}` 和 `/mutate/{name}`。变更器通过 `AdmissionResult.jsonPatch(...)` 返回原始 JSON Patch 字符串，由 Handler 自动 base64 编码并设置 `patchType` 为 `JSONPatch`。

若要在 Operator 启动时向 Kubernetes 注册 Webhook 配置，可组合使用 `WebhookSelfRegistration` 与 `WebhookRegistrationConfig`：

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookRegistrationConfig;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookSelfRegistration;

WebhookRegistrationConfig config = WebhookRegistrationConfig.builder(
    // 基于文件的 CA 证书包降级路径
    "my-operator", "my-namespace", Path.of("/etc/operator/certs/ca.crt")
    .withServicePort(443)
    .withFailurePolicy("Fail")
    .withTimeoutSeconds(10)
    .withSideEffects("None")
    .build();

WebhookSelfRegistration registration = new WebhookSelfRegistration(client, config);
registration.register(handler);
```

`register(handler)` 从磁盘读取 CA 证书并 base64 编码，然后为每个已注册的 Webhook 名称创建或更新一个 `ValidatingWebhookConfiguration` 和一个 `MutatingWebhookConfiguration`。

你也可以使用 `WebhookCertificateGenerator`（`com.huawei.dcs.modelengine.operator.framework.webhook.cert`）自动生成 CA 证书包和服务端证书。它会生成包含 `ca.crt`、`tls.crt` 和 `tls.key` 的文件，SAN 覆盖服务名及其命名空间 FQDN 变体，并为服务端证书设置 `serverAuth` 扩展密钥用途。`EchoOperatorMain` 默认使用此生成器，将证书写入 `WEBHOOK_CERT_DIRECTORY`。当 `WEBHOOK_CERT_AUTO_GENERATE` 设为 `false` 时，仍可使用上文基于文件的降级示例。

## Conversion Webhook

针对多版本 CRD，SDK 提供了 Conversion Webhook Handler，可挂载在同一个 `WebhookServer` 上。

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionWebhookHandler;
import io.fabric8.kubernetes.api.model.HasMetadata;

ConversionHandler conversionHandler = new ConversionHandler(client);
conversionHandler.register("example.com/v1alpha1", "example.com/v1alpha2",
    (desiredVersion, resource) -> ConversionResult.converted(convertToV2(resource)));
conversionHandler.register("example.com/v1alpha2", "example.com/v1alpha1",
    (desiredVersion, resource) -> ConversionResult.converted(convertToV1(resource)));
conversionHandler.register(webhookServer);
```

`ConversionWebhookHandler.convert(desiredVersion, HasMetadata)` 返回 `ConversionResult.converted(...)` 或 `ConversionResult.failed(...)`。Handler 负责解析 `apiextensions.k8s.io/v1 ConversionReview`，按 `(源 apiVersion, 目标 apiVersion)` 路由。同版本请求直接透传，未注册版本对返回转换失败状态。

在资源类中用 fabric8 `@Version` 标记多版本：

```java
@Version(value = "v1alpha1", storage = false, served = true, deprecated = true)
public class MyResourceV1 extends CustomResource<MySpecV1, MyStatusV1> implements Namespaced {
}

@Version(value = "v1alpha2", storage = true, served = true)
public class MyResourceV2 extends CustomResource<MySpecV2, MyStatusV2> implements Namespaced {
}
```
