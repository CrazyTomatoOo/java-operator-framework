# Operator Framework Spring Boot Starter

一个基于 Java 21、Spring Boot 3.5.15 与 Fabric8 Kubernetes Client 7.3.0 的 Kubernetes Operator Spring Boot Starter。

英文文档：[README.md](README.md)

## 依赖

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Starter 会自动启用。Spring 发现回调 Bean，在应用上下文就绪后启动所选运行模式，并通过 `SmartLifecycle` 完成关闭。应用不需要自行创建框架运行时，也不需要自行管理其生命周期。

## 最小控制器

定义一个带有具体泛型资源类型的 Spring Bean，Starter 会据此完成自动发现。

```java
package com.example.operator;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import io.fabric8.kubernetes.api.model.ConfigMap;
import org.springframework.stereotype.Component;

@Component
public final class ConfigMapReconciler implements Reconciler<ConfigMap> {
    @Override
    public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext<ConfigMap> context) {
        return ReconcileResult.done();
    }
}
```

`ReconcileResult.requeueNow()` 请求立即再次调和；`ReconcileResult.requeueAfter(Duration)` 请求延迟调和。未处理的回调异常会使用已配置的指数退避重试策略。

仅运行控制器的应用可使用：

```yaml
operator:
  framework:
    mode: controller
```

无需任何注册、启动或关闭调用。

## 高级控制器注册

通过 `ControllerRegistration` Bean 可以覆盖单个控制器的过滤与 resync 设置，并添加从属资源、任意资源监听或 Kubernetes Event 订阅。注册中引用的 Reconciler 也必须是 Spring Bean。

```java
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class ControllerConfiguration {
    @Bean
    ControllerRegistration<MyResource> myResourceController(MyResourceReconciler reconciler) {
        return ControllerBuilder.forResource(MyResource.class, reconciler)
                .generationFilter(true)
                .resyncPeriod(Duration.ofMinutes(2))
                .labelSelector(Map.of("app", "my-operator"))
                .indexField("secretRef", resource -> resource.getSpec().getSecretRef())
                .owns(Deployment.class)
                .watches("configmaps", ConfigMap.class, Mappers.byLabel("operator.example/primary"))
                .watchesKubernetesEvents()
                .build();
    }
}
```

`labelSelector` 与 `fieldSelector` 使用服务端等值选择器收窄主资源监听；重复调用会替换对应选择器并保留另一个。`indexField` 在主资源字段上注册 Informer 索引，使调和期间可以 O(1) 查询缓存（见下文「调和辅助工具」）。

`owns` 通过 Owner Reference 映射资源；已知主资源类型时建议使用带类型过滤的 `Mappers.ownerReferences(Class)`，无参版本会匹配所有 controller owner 类型。`watches` 使用传入的 `ResourceMapper`；内置标签和注解映射器在更新时同时检查当前与先前状态，裸名称使用次级资源的 namespace，cluster-scoped 次级资源可使用 `namespace/name` 指向 namespaced 主资源。内置映射器还支持标签、注解与 Kubernetes Event 的 involved object。`watchesKubernetesEvents()` 订阅指向主资源的 `core/v1` Event：informer 在服务端按 `involvedObject.kind`/`involvedObject.apiVersion` 过滤，聚合事件的 `count` 递增不会触发 reconcile，只有新 Event、删除和 resync 会触发。Kubernetes Event 是尽力而为机制，不能作为正确性关键状态；同时发布和订阅时必须避免反馈循环。

## 调和辅助工具

`ReconciliationContext<T>` 携带资源键、归一化的 `triggers()` 列表（每项是一个包含事件类型、角色与触发资源身份的 `ReconciliationTrigger`）以及 Informer 缓存：`cache()` 对应主资源类型，`cacheFor(Class)` 对应通过 `owns`/`manages`/`watches` 声明的类型。通过 `ControllerBuilder.indexField` 注册的字段支持 O(1) 查询，例如 `context.cache().getByIndex("secretRef", name)`，无需回源 API Server。

在支持控制器的模式中，`KubernetesClient` 是可注入的 Bean。`api.reconcile` 包中的静态工具类覆盖常见写路径；它们都在防御性副本上操作，绝不会修改 Informer 缓存中的实例：

- `Applies.apply(client, desired, fieldManager)` 以 Server-Side Apply 提交完整期望状态——一次调用完成创建或更新，其他 manager 拥有的字段不受影响。`applyForcibly` 会额外接管冲突字段的所有权。请始终传入显式且唯一的 field manager。
- `Owners.setController(owner, dependent)` 在副本上写入 `controller=true` 的 Owner Reference，从而启用 Kubernetes 垃圾回收与 Owner Reference 监听映射。
- `Dependents.apply(client, dependent, primary, context, fieldManager)` 从 `DependentResource` 计算期望状态、添加 controller Owner Reference 并完成 Apply。通过 `ControllerBuilder.manages(dependent)` 注册后，从属资源的事件也会触发调和。
- `Finalizers.isDeleting`/`present`/`add`/`remove` 实现用于清理外部资源的 Finalizer 模式。
- `StatusUpdates.update(client, resource, status)` 以 JSON Merge Patch 更新 `/status` 子资源，不修改资源本身；CRD 必须声明 status 子资源。

```java
@Component
final class MyResourceReconciler implements Reconciler<MyResource> {
    private static final String FIELD_MANAGER = "my-operator";
    private final KubernetesClient client;
    private final DependentResource<Deployment, MyResource> deployment = new MyDeploymentDependent();

    MyResourceReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public ReconcileResult reconcile(MyResource resource, ReconciliationContext<MyResource> context) {
        if (Finalizers.isDeleting(resource)) {
            return ReconcileResult.done();
        }
        Dependents.apply(this.client, this.deployment, resource, context, FIELD_MANAGER);
        StatusUpdates.update(this.client, resource, new MyResourceStatus("Ready"));
        return ReconcileResult.done();
    }
}
```

## 配置

所有框架专用配置均使用 `operator.framework` 前缀。

| 配置项 | 默认值 | 含义 |
| --- | ---: | --- |
| `operator.framework.enabled` | `true` | 启用全部 Starter 自动配置；设为 `false` 时不创建任何框架运行时 Bean。 |
| `operator.framework.mode` | `combined` | `controller`、`webhook` 或 `combined`，规则见下文。 |
| `operator.framework.controller.namespace` | 未设置 | 监听该命名空间；空值依次回退到 Fabric8 客户端命名空间和 `default`。 |
| `operator.framework.controller.cluster-scoped` | `false` | 监听所有命名空间；不能同时设置非空 controller namespace。 |
| `operator.framework.controller.worker-threads` | `1` | 每个控制器的调和工作线程数。 |
| `operator.framework.controller.resync-period` | `60s` | Informer resync 周期；`0` 表示禁用周期性 resync。 |
| `operator.framework.controller.generation-change-filter` | `true` | 当 generation、删除时间戳和 finalizer 均未变化时忽略普通主资源更新。 |
| `operator.framework.controller.filter-events-by-involved-object` | `true` | 按 `involvedObject.kind`/`apiVersion` 在服务端收窄 Kubernetes Event 监听；当 API Server 无法匹配这些字段时禁用（例如内存测试服务器）。 |
| `operator.framework.controller.startup-retry-delay` | `5s` | 控制器或选主启动/就绪失败后的重试间隔。 |
| `operator.framework.leader-election.enabled` | `false` | 启用基于 Fabric8 Lease 的选主。 |
| `operator.framework.leader-election.lease-name` | `${spring.application.name}-leader` | Lease 名称；应用名称回退值会按 Kubernetes 规则清理。 |
| `operator.framework.leader-election.namespace` | 继承 | 依次使用 controller namespace、Fabric8 客户端命名空间和 `default`。 |
| `operator.framework.leader-election.lease-duration` | `15s` | Lease 持有时间。 |
| `operator.framework.leader-election.renew-deadline` | `10s` | 续租截止时间。 |
| `operator.framework.leader-election.retry-period` | `2s` | Lease 重试周期。 |
| `operator.framework.retry.initial-delay` | `500ms` | Reconciler 异常后的初始延迟。 |
| `operator.framework.retry.max-delay` | `30s` | 指数退避最大延迟。 |
| `operator.framework.retry.max-attempts` | `5` | 异常成为终止异常前允许的失败次数。 |
| `operator.framework.rate-limit.minimum-interval` | `5s` | 同一控制器/资源键的最小间隔；`0` 禁用限流。 |
| `operator.framework.events.enabled` | `true` | 在支持控制器的模式中创建 `KubernetesEventPublisher`。 |
| `operator.framework.events.component` | `spring.application.name` | Kubernetes Event 的 reporting/source component。 |
| `operator.framework.events.aggregation-window` | `5m` | 相同 Kubernetes Event 的确定性聚合时间窗口。 |
| `operator.framework.events.max-cache-entries` | `1000` | 内存聚合条目的最大数量。 |

时长使用 Spring Boot Duration 语法。Worker 数、重试次数和 Event 缓存条目必须为正数；resync 和限流间隔可以为零；启动重试、Event 聚合、重试延迟及选主时长必须为正数。重试须满足 `initial-delay <= max-delay`，选主须满足 `retry-period < renew-deadline < lease-duration`。

模式校验是严格的：

- `controller`：至少需要一个带具体类型的 `Reconciler` 或 `ControllerRegistration` Bean，且不创建 Webhook MVC 路由。
- `webhook`：至少需要一个带具体类型的 Admission 或 Conversion 回调 Bean，且不创建控制器、客户端和事件发布器基础设施。
- `combined`：两组 Bean 都必须存在，并同时创建两侧能力。

缺失或有歧义的回调类型、重复的控制器资源类型、不安全的 Webhook Bean 名称以及不完整的模式配置都会导致应用启动失败。

## Leader Election

仅在支持控制器的模式中启用选主：

```yaml
spring:
  application:
    name: my-operator
operator:
  framework:
    mode: controller
    leader-election:
      enabled: true
      lease-duration: 15s
      renew-deadline: 10s
      retry-period: 2s
```

Lease 名称默认是经过清理的 `spring.application.name` 加 `-leader`。命名空间依次继承 controller namespace、Fabric8 客户端命名空间和 `default`；可通过 `lease-name` 与 `namespace` 分别覆盖。身份在存在时使用 `HOSTNAME`。备用副本保持存活/就绪但不运行 Informer；新 Leader 在 Informer 同步后进入就绪状态。部署 RBAC 必须授予 Lease 的读取、创建和更新权限。

## Kubernetes Event

可将公共发布器注入任意 Spring Bean：

```java
import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;

@Component
final class StatusReporter {
    private final KubernetesEventPublisher events;

    StatusReporter(KubernetesEventPublisher events) {
        this.events = events;
    }

    void report(MyResource resource) {
        events.normal(resource, "Reconciled", "Dependent resources are current");
        // events.warning(resource, "InvalidSpec", "The requested value is invalid");
    }
}
```

上报组件名取自 `spring.application.name`，默认是 `operator-framework`。发布 Event 需要对 involved object 所在命名空间中的 `events` 拥有 `create`、`get` 与 `update` 权限。不需要事件发布时可设置 `operator.framework.events.enabled=false`。

## Webhook 回调与固定路由

Webhook 由 Spring Bean 名称路由。Bean 名称必须匹配 `[a-z0-9][a-z0-9._-]*`——Kubernetes 会拒绝 `clientConfig.service.path` 中含大写字母的段，因此框架在启动时强制小写 RFC 1123 名称。

```java
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.MutationResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ResourceConverter;
import org.springframework.context.annotation.Bean;

@Bean("myresourcevalidator")
AdmissionValidator<MyResource> validator() {
    return (current, context) -> current.getSpec().isValid()
            ? AdmissionDecision.allow()
            : AdmissionDecision.deny("spec is invalid");
}

@Bean("myresourcemutator")
AdmissionMutator<MyResource> mutator() {
    return (current, context) -> {
        current.getMetadata().getLabels().putIfAbsent("managed-by", "my-operator");
        return MutationResult.mutated(current);
    };
}

@Bean("myresourceconverter")
ResourceConverter<MyResource> converter() {
    return (resource, context) -> ConversionResult.converted(convertTo(resource, context.desiredVersion()));
}
```

固定 HTTP 路由为：

- `POST /operator-framework/webhooks/validate/{beanName}`
- `POST /operator-framework/webhooks/mutate/{beanName}`
- `POST /operator-framework/webhooks/convert/{beanName}`

校验返回 `AdmissionDecision.allow()` 或 `AdmissionDecision.deny(message)`。变更返回 `MutationResult.unchanged()`、`MutationResult.mutated(resource)` 或 `MutationResult.denied(message)`；传输层会计算并 Base64 编码 JSON Patch。转换返回 `ConversionResult.converted(resource)` 或 `ConversionResult.failed(message)`。Context 对象暴露稳定的请求身份/版本信息，而不暴露传输对象；Admission 回调还可以通过不可变的 JSON 兼容 Map 获取操作相关的 `AdmissionReview` options。

## 外部 TLS 与 Kubernetes 注册

Webhook HTTPS 使用 Spring Boot Web 服务器及标准 SSL 配置。挂载由平台提供的证书和私钥，例如来自 Kubernetes Secret：

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    certificate: file:/etc/operator/tls/tls.crt
    certificate-private-key: file:/etc/operator/tls/tls.key
operator:
  framework:
    mode: webhook
```

Starter **不会**生成证书，也**不会**创建或更新 `ValidatingWebhookConfiguration`、`MutatingWebhookConfiguration`、Service、Secret 或 CRD Conversion Webhook 配置。请使用 Helm、Kustomize、准入平台或其他部署工具提供这些资源，并把 service path 指向上述固定路由。Kubernetes Webhook 的 `caBundle` 必须信任外部提供的服务端证书。

## Actuator 健康检查与 Prometheus

Starter 向标准 Spring Boot Actuator 端点贡献 `operatorFramework` 健康状态和 Micrometer 指标。需要显式启用探针并暴露 Prometheus：

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
```

端点：

- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`

就绪状态会反映当前控制器 Leader 的 Informer 同步状态，以及 Webhook 回调的可用性/失败信息。Starter 使用应用的 `MeterRegistry`，不存在独立的健康检查或指标服务器。

## 生命周期与 KubernetesClient 所有权

- Spring 通过 `SmartLifecycle` 启停运行时。
- 关闭时先停止新任务入队，再停止 Informer、排空工作线程，并在 `spring.lifecycle.timeout-per-shutdown-phase` 后中断剩余工作线程；Starter 使用的默认值为 `30s`。
- 支持控制器的模式中如果不存在 `KubernetesClient`，自动配置会创建客户端，并由 Starter 关闭。
- 用户提供的 `KubernetesClient` 会被复用，Starter 的所有权逻辑绝不会关闭它；其生命周期仍由声明它的应用负责。
- 仅 Webhook 模式不需要也不会自动创建 Kubernetes 客户端。

## 支持的包边界

只有 `com.huawei.dcs.modelengine.operator.framework.api.*` 是受支持的应用 API。`...autoconfigure.*` 仅用于 Spring Boot 加载/配置，`...internal.*` 是实现细节。生产类被限制在这三个根包中，应用不得依赖 `internal` 类。

## 测试

`operator-framework-testing` 模块提供 `OperatorTestKit`：内存 Kubernetes API Server 与客户端，可以从 `ControllerRegistration` 启动真实控制器运行时，或构建带缓存的 `ReconciliationContext` 以直接调用 Reconciler。

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework-testing</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

```java
try (var kit = OperatorTestKit.create()) {
    var runtime = kit.controller(registration);
    runtime.start();
    kit.client().configMaps().inNamespace("default").resource(configMap).create();
    // await effects through kit.client()
}
```

监听 Kubernetes Event 的控制器必须设置 `operator.framework.controller.filter-events-by-involved-object=false`，因为内存 API Server 无法匹配 `involvedObject` 字段选择器。使用 `mvn -f operator/testing/pom.xml clean verify` 构建该模块。

## 构建与质量门禁

在仓库根目录执行：

```bash
mvn -f operator/framework/pom.xml clean verify
```

将快照安装到本地仓库：

```bash
mvn -f operator/framework/pom.xml clean install
```

`verify` 会执行单元/集成测试、制品打包检查、源码 JAR 生成与 Checkstyle。生产源码限制为每行最多 120 字符、参数最多 5 个、每个方法最多 50 个非空行、圈复杂度最多 5。

旧 `example/` 与 `stress-test/` 模块已被有意删除，`stress-test/` 保持不存在。当前示例位于 `example/echo-operator`，内含真实集群端到端脚本（`scripts/e2e-test.sh`）。
