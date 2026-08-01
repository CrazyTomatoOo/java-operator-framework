# 开发指南

本指南描述当前 Spring Boot Starter，目标基线为 Java 21、Spring Boot 3.5.15、Fabric8 Kubernetes Client 7.3.0 与 Lombok 1.18.32。

英文文档：[dev-guide.md](dev-guide.md)

## 1. 添加 Starter

在本地使用快照版本时，先构建本仓库：

```bash
mvn -f operator/framework/pom.xml clean install
```

在 Spring Boot 3.5.16 应用中添加 Starter：

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

使用 Java 21。Starter 已提供 Spring Web、Actuator、Micrometer Prometheus 支持和 Fabric8 Kubernetes Client 7.8.0。只有应用确实需要生成 CRD 或 Java 模型时，才添加 Fabric8 生成器依赖/插件；运行时并不需要它们。

Starter 使用 Spring Boot 自动配置，不存在启用注解，也不存在由应用持有的框架生命周期对象。

## 2. 定义资源和 Reconciler Bean

可以使用任意具体的 Fabric8 `HasMetadata` 类型，包括 `ConfigMap` 等内置类型或自定义 `CustomResource<Spec, Status>`。

最小控制器是一个带具体类型的组件：

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
    public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext context) throws Exception {
        // 从 resource 读取期望状态，并使 Kubernetes 状态收敛。
        return ReconcileResult.done();
    }
}
```

没有 Webhook 回调的应用应选择 controller 模式：

```yaml
operator:
  framework:
    mode: controller
```

Spring 会发现泛型类型、创建控制器、启动 Informer/Worker 并完成关闭。应用代码不得执行框架注册或生命周期调用。

一次调和会收到：

- 当前主资源；
- 包含命名空间/名称的 `context.resourceKey()`；
- 包含事件类型、角色与资源引用的 `context.triggers()`。

返回以下结果之一：

```java
ReconcileResult.done();
ReconcileResult.requeueNow();
ReconcileResult.requeueAfter(Duration.ofSeconds(30));
```

让异常向外抛出。Starter 会按照 `operator.framework.retry.*` 将非终止回调异常转换为延迟重试；达到配置的失败次数后，异常成为终止失败并由指标/日志记录。

### 管理 Finalizer 与 Status

拥有外部资源的 Reconciler 使用 Kubernetes finalizer 模式，并通过 `/status` 子资源持久化进度。Starter 在 `api.reconcile` 包提供静态助手；注入 `KubernetesClient`（缺省时由 Starter 创建并持有）后直接使用：

```java
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Finalizers;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.StatusUpdates;

@Override
public ReconcileResult reconcile(MyResource resource, ReconciliationContext context) {
    if (Finalizers.isDeleting(resource)) {
        cleanupExternal(resource);
        Finalizers.remove(client, resource, "example.com/cleanup");
        return ReconcileResult.done();
    }
    Finalizers.add(client, resource, "example.com/cleanup");
    var status = new MyResourceStatus();
    status.setPhase("Ready");
    StatusUpdates.update(client, resource, status);
    return ReconcileResult.done();
}
```

`Finalizers.add`/`remove` 执行服务端 JSON patch（幂等，并发调和下安全）。`StatusUpdates.update` 将给定 status 对象以 JSON merge patch 合并进 `/status` 子资源，绝不修改传入的（informer 缓存的）资源实例；要求 CRD 声明 `status` 子资源。

## 3. 配置高级控制器

普通 `Reconciler<T>` Bean 使用控制器默认设置。需要显式事件源或单控制器覆盖项时，定义一个 `ControllerRegistration<T>` Bean。

```java
package com.example.operator;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class OperatorConfiguration {
    @Bean
    ControllerRegistration<MyResource> myResourceController(MyResourceReconciler reconciler) {
        return ControllerBuilder.forResource(MyResource.class, reconciler)
                .generationFilter(true)
                .resyncPeriod(Duration.ofMinutes(2))
                .owns(Deployment.class)
                .watches("configuration", ConfigMap.class, Mappers.byLabel("operator.example/primary"))
                .watchesKubernetesEvents()
                .build();
    }
}
```

被引用的 Reconciler 必须是 Spring Bean，这样 AOP 重试、限流与观测行为才能保持有效。

- `generationFilter(boolean)` 覆盖全局 Generation Filter。
- `resyncPeriod(Duration)` 覆盖全局 resync；零表示禁用周期性 resync。
- `owns(Deployment.class)` 监听从属资源，并通过 Owner Reference 映射主资源键。
- `watches(name, type, mapper)` 监听任意从资源；同一注册内的监听名称必须唯一。
- `watchesKubernetesEvents()` 监听 `core/v1` Event，并通过 `involvedObject`（服务端按 kind/apiVersion 过滤）映射主资源键。聚合事件的 `count` 递增不会触发 reconcile，只有新 Event、删除和 resync 会触发。

内置 `Mappers` 提供 `ownerReferences()`、`byLabel(key)`、`byAnnotation(key)` 与 `involvedObject()`。自定义 Mapper 接收当前 `ResourceEvent<S>` 并返回 `Collection<ResourceKey>`：

```java
ResourceMapper<ConfigMap, MyResource> mapper = event -> List.of(
        new ResourceKey(event.resource().getMetadata().getNamespace(), "primary-name"));
```

一个主资源类型只能有一个有效注册。显式注册会替代该类型的自动发现；重复的自动/显式资源类型会导致启动失败。Kubernetes Event 可能丢失并受 TTL 限制，因此不能作为正确性边界。如果 Reconciler 为自己的主资源发布 Event，应避免无过滤的订阅循环。

## 4. 配置运行时

所有框架专用配置和默认值如下。

| 配置项 | 默认值 | 语义 |
| --- | ---: | --- |
| `operator.framework.enabled` | `true` | 启用自动配置；设为 `false` 时不创建任何框架运行时/配置 Bean。 |
| `operator.framework.mode` | `combined` | 选择控制器、Webhook 或两侧运行时。 |
| `operator.framework.controller.namespace` | 未设置 | 监听该命名空间；空值依次回退到 Fabric8 客户端命名空间和 `default`。 |
| `operator.framework.controller.cluster-scoped` | `false` | 监听所有命名空间；不能同时设置非空 controller namespace。 |
| `operator.framework.controller.worker-threads` | `1` | 每个控制器注册创建的 Worker 数量。 |
| `operator.framework.controller.resync-period` | `60s` | 默认 Informer resync；零表示禁用周期性 resync。 |
| `operator.framework.controller.generation-change-filter` | `true` | 除非 generation、删除时间戳或 finalizer 变化，否则过滤主资源更新；add/delete/resync 与从资源事件仍会入队。 |
| `operator.framework.controller.filter-events-by-involved-object` | `true` | 用 involvedObject 字段选择器收窄 Kubernetes Event 监听；当 API server（或内存测试 server）无法匹配该选择器时关闭。 |
| `operator.framework.controller.startup-retry-delay` | `5s` | 启动或 Informer 就绪失败后的监督器重试/检查周期。 |
| `operator.framework.leader-election.enabled` | `false` | 为控制器运行时启用 Fabric8 Lease 选主。 |
| `operator.framework.leader-election.lease-name` | `${spring.application.name}-leader` | Lease 名称；应用名称回退值会按 Kubernetes 规则清理。 |
| `operator.framework.leader-election.namespace` | 继承 | 依次使用 controller namespace、Fabric8 客户端命名空间和 `default`。 |
| `operator.framework.leader-election.lease-duration` | `15s` | Lease 持有时间。 |
| `operator.framework.leader-election.renew-deadline` | `10s` | 续租截止时间。 |
| `operator.framework.leader-election.retry-period` | `2s` | 选主重试周期。 |
| `operator.framework.retry.initial-delay` | `500ms` | Reconciler 异常后的首次延迟。 |
| `operator.framework.retry.max-delay` | `30s` | 指数退避最大延迟。 |
| `operator.framework.retry.max-attempts` | `5` | 异常成为终止异常时的失败调用次数。 |
| `operator.framework.rate-limit.minimum-interval` | `5s` | 每个控制器/资源的最小调用间隔；零表示禁用限流。 |
| `operator.framework.events.enabled` | `true` | 在 controller/combined 模式创建公共 Event 发布器。 |
| `operator.framework.events.component` | `spring.application.name` | Kubernetes Event 的 reporting/source component。 |
| `operator.framework.events.aggregation-window` | `5m` | 等价 Event 确定性标识使用的时间窗口。 |
| `operator.framework.events.max-cache-entries` | `1000` | LRU 聚合缓存上限。 |

支持 `500ms`、`5s`、`2m` 等 Spring Boot Duration 值。Worker 数、重试次数和 Event 缓存条目必须为正数；resync 和限流间隔可以为零；启动重试、Event 聚合、重试延迟及选主时长必须为正数。重试须满足 `initial-delay <= max-delay`，选主须满足 `retry-period < renew-deadline < lease-duration`。

模式行为：

| 模式 | 应用必须提供的 Bean | 自动配置的基础设施 |
| --- | --- | --- |
| `controller` | 带具体类型的 `Reconciler` 或 `ControllerRegistration` | Kubernetes 客户端（缺失时）、Informer/Worker、事件发布器（启用时）、生命周期、健康/指标 |
| `webhook` | 带具体类型的 `AdmissionValidator`、`AdmissionMutator` 或 `ResourceConverter` | MVC Webhook 路由、回调注册表、健康/指标；不自动创建 Kubernetes 客户端 |
| `combined` | 控制器与 Webhook 两组 Bean | 两侧能力 |

默认 `combined` 模式有意采用严格校验：只提供一组 Bean 的应用必须选择匹配的模式。原始/无法解析的回调泛型与重复注册都是配置错误，不会被静默忽略。

完整控制器配置示例：

```yaml
spring:
  application:
    name: inventory-operator
  lifecycle:
    timeout-per-shutdown-phase: 30s
operator:
  framework:
    mode: controller
    controller:
      namespace: operators
      cluster-scoped: false
      worker-threads: 2
      resync-period: 60s
      generation-change-filter: true
      startup-retry-delay: 5s
    retry:
      initial-delay: 500ms
      max-delay: 30s
      max-attempts: 5
    rate-limit:
      minimum-interval: 5s
    events:
      enabled: true
      component: inventory-operator
      aggregation-window: 5m
      max-cache-entries: 1000
```

## 5. 启用 Leader Election

```yaml
spring:
  application:
    name: inventory-operator
operator:
  framework:
    mode: controller
    leader-election:
      enabled: true
      lease-duration: 15s
      renew-deadline: 10s
      retry-period: 2s
```

Lease 命名空间依次继承 controller namespace、Fabric8 客户端命名空间和 `default`，`leader-election.namespace` 可覆盖。Lease 名称默认为小写、DNS 安全的 `spring.application.name` 加 `-leader`，`leader-election.lease-name` 可覆盖。身份使用 Pod 的 `HOSTNAME`，在 Kubernetes 外则使用 JVM 运行时身份。关闭时 Elector 会释放 Lease。

只有 Leader 运行控制器 Informer/Worker。备用副本保持存活并就绪；获得 Leader 身份后，在全部 Informer 同步前处于未就绪状态。失去 Leader 身份时会拒绝新任务、排空/停止活动运行时并回到备用就绪状态。RBAC 必须允许 Fabric8 所需的 Lease 操作。

## 6. 发布 Kubernetes Event

在启用事件的 controller/combined 模式中注入公共接口：

```java
import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import org.springframework.stereotype.Component;

@Component
final class EventReporter {
    private final KubernetesEventPublisher publisher;

    EventReporter(KubernetesEventPublisher publisher) {
        this.publisher = publisher;
    }

    void reconciled(MyResource resource) {
        publisher.normal(resource, "Reconciled", "Desired state applied");
    }

    void invalid(MyResource resource) {
        publisher.warning(resource, "InvalidSpec", "spec is invalid");
    }
}
```

一个聚合窗口内的等价 Event 会更新已有 `core/v1` Event 的 count。有界缓存在关闭时刷新。上报组件名为 `spring.application.name`，缺失时回退到 `operator-framework`。需要授予 `events` 的 `create`、`get`、`update` 权限。发布失败会被日志/指标记录，但不会让 Kubernetes Event 成为可靠状态存储。

## 7. 实现 Webhook 回调

选择 `webhook` 或 `combined` 模式，并声明带具体类型的 Spring 回调 Bean。Bean 名称是固定路由键，必须匹配 `[a-z0-9][a-z0-9._-]*`——Kubernetes 会拒绝 `clientConfig.service.path` 中含大写字母的段，因此框架在启动时强制小写 RFC 1123 名称。

### 校验

```java
@Bean("myresourcevalidator")
AdmissionValidator<MyResource> myresourcevalidator() {
    return (current, context) -> current.getSpec().isValid()
            ? AdmissionDecision.allow()
            : AdmissionDecision.deny("spec is invalid");
}
```

精确 API 为 `AdmissionDecision validate(T current, AdmissionContext context) throws Exception`。`AdmissionContext` 包含请求 UID、操作、稳定的 `ResourceReference`、dry-run 标记和用户身份。

路由：`POST /operator-framework/webhooks/validate/myresourcevalidator`

### 变更

```java
@Bean("myresourcemutator")
AdmissionMutator<MyResource> myresourcemutator() {
    return (current, context) -> MutationResult.unchanged();
}
```

精确 API 为 `MutationResult<T> mutate(T current, AdmissionContext context) throws Exception`。返回 `unchanged()`、`mutated(resource)` 或 `denied(message)`。对于变更后的资源，框架会根据输入计算 JSON Patch，并在 `AdmissionResponse` 中进行 Base64 编码。

路由：`POST /operator-framework/webhooks/mutate/myresourcemutator`

### 转换

```java
@Bean("myresourceconverter")
ResourceConverter<MyResource> myresourceconverter() {
    return (resource, context) -> {
        MyResource converted = convert(resource, context.desiredVersion());
        return ConversionResult.converted(converted);
    };
}
```

精确 API 为 `ConversionResult<T> convert(T resource, ConversionContext context) throws Exception`。`ConversionContext` 包含源 API 版本与目标 API 版本。返回 `converted(resource)` 或 `failed(message)`。同版本资源会直接透传，不调用回调。

路由：`POST /operator-framework/webhooks/convert/myresourceconverter`

传输层接收 Kubernetes `admission.k8s.io/v1` 与 `apiextensions.k8s.io/v1` Review 对象，保留响应 UID，并在回调失败时返回安全失败响应。无效或未知路由返回 HTTP 400。

## 8. 提供外部 Webhook TLS

Webhook 路由运行在应用的 Spring Web 服务器上。配置标准 Spring Boot HTTPS 属性，并挂载平台管理的 PEM 材料：

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

Starter 有意不提供证书生成器、CA 持久化、证书重载子系统或 Kubernetes Webhook 自注册。部署工具必须提供并轮换证书/Secret、Service、`ValidatingWebhookConfiguration`、`MutatingWebhookConfiguration` 与 CRD Conversion Webhook 配置。把每个 Kubernetes Webhook 的 service path 配置为对应 Bean 名路由，并将签发 CA 写入其 `caBundle`。

如果更适合平台，也可以使用标准 Spring Boot SSL Bundle 配置代替直接 PEM 配置。

## 9. 暴露存活、就绪与 Prometheus

Starter 使用 Spring Boot Actuator 与应用的 Micrometer Registry。启用并暴露标准端点：

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  endpoints:
    web:
      exposure:
        include: health,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
```

探针/指标 URL：

```text
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

`operatorFramework` 健康贡献会报告模式、存活/就绪、控制器运行/Informer/Leader 状态以及 Webhook 回调数量/最后失败。`show-details` 是可选项，只控制响应可见性。不存在框架专用的 HTTP 健康/指标服务器或端口。

## 10. 理解生命周期与客户端所有权

运行时是 Spring `SmartLifecycle`，会自动启动。控制器启动不会阻塞应用线程，并按照 `operator.framework.controller.startup-retry-delay` 重试暂时性启动/就绪失败。

关闭顺序：

1. 将就绪状态设为 false 并停止选主；
2. 拒绝新队列任务，停止 Informer/Scheduler；
3. 排空进行中的 Worker；
4. 在 `spring.lifecycle.timeout-per-shutdown-phase` 后中断剩余 Worker。

支持控制器的模式中，如果应用不存在 `KubernetesClient` Bean，Starter 会构建一个客户端并记录所有权，从而在框架关闭后关闭它。如果应用提供客户端，Starter 会复用它，其所有权组件不会关闭该客户端；应用自行控制该 Bean 的销毁策略。仅 Webhook 模式既不需要也不会创建客户端。

## 11. 遵守公共包边界

应用代码只可以依赖：

```text
com.huawei.dcs.modelengine.operator.framework.api.*
```

`autoconfigure` 根包用于 Spring Boot 加载与配置元数据；`internal` 根包不是兼容性契约。生产 JAR 在 framework 包下有意只包含 `api`、`autoconfigure` 与 `internal`。

## 12. 使用测试套件

`operator-framework-testing` 模块提供内存 CRUD API server 和一个小测试套件，Operator 测试不再需要真实集群，也不用自己拼装 mock server：

```xml
<dependency>
    <groupId>com.huawei.dcs.modelengine</groupId>
    <artifactId>operator-framework-testing</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

针对内存 server 驱动真实的控制器运行时——informer、worker、队列以及二级缓存：

```java
try (var kit = OperatorTestKit.create()) {
    var runtime = kit.controller(registration);
    runtime.start();
    kit.client().configMaps().inNamespace(kit.client().getNamespace()).resource(configMap).create();
    // 通过 kit.client() 等待副作用
}
```

直接调用 reconciler 时，`OperatorTestKit.context(primary)` 返回已预置主缓存的 `ReconciliationContext`，无需运行时即可走 by-index/get-by-key 路径。内存 server 的 client 命名空间是 `test`——请使用 `kit.client().getNamespace()` 而不是硬编码。监听 Kubernetes Event 的 Operator 必须关闭 involvedObject 字段选择器过滤（`operator.framework.controller.filter-events-by-involved-object: false`），内存 server 无法匹配该选择器。`example/echo-operator` 中有完整的套件测试（`EchoOperatorKitTest`）。

## 13. 构建与验证

在仓库根目录执行完整门禁：

```bash
mvn -f operator/framework/pom.xml clean verify
```

门禁会执行测试、打包后集成检查、配置元数据/资源检查、JAR/包边界检查、源码 JAR 生成与 Checkstyle。Checkstyle 仅检查生产源码，并强制每行最多 120 字符、方法/构造参数最多 5 个、每个方法最多 50 个非空行、圈复杂度不大于 5。

更快的仅测试循环：

```bash
mvn -f operator/framework/pom.xml test
```

旧 `example/` 与 `stress-test/` 模块已被有意删除，`stress-test/` 保持不存在。当前示例为 `example/echo-operator`——基于该 Starter 的 Spring Boot 应用，包含单元测试、MockMvc admission 端点测试，以及真实集群端到端脚本：

```bash
example/echo-operator/scripts/e2e-test.sh
```

该脚本部署到临时命名空间（含 RBAC/TLS），注册真实 admission webhook 配置，并对活跃 API server 验证 mutation、validation、reconcile、事件发布、垃圾回收、健康检查与指标。
