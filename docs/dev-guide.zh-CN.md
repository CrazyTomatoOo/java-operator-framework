# 开发指南

本指南介绍如何使用 `operator-framework` 构建一个新的 Kubernetes Operator。

## 1. 安装 SDK

在仓库根目录执行：

```bash
mvn -f operator/framework/pom.xml clean install
```

该命令将 `com.huawei.dcs.modelengine:operator-framework:0.1.0-SNAPSHOT` 安装到本地 Maven 仓库。

## 2. 创建 Maven 项目

新建一个 Java 21 的 Maven 项目，添加 SDK 依赖：

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

同时添加 fabric8 `kubernetes-client` 与 `generator-annotations` 依赖，版本为 `7.7.0`。

添加 `crd-generator-maven-plugin` 与 `java-generator-maven-plugin`，用于生成 CRD 与 Java 类：

```xml
<plugin>
  <groupId>io.fabric8</groupId>
  <artifactId>crd-generator-maven-plugin</artifactId>
  <version>${fabric8.version}</version>
  <executions>
    <execution>
      <phase>compile</phase>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
</plugin>

<plugin>
  <groupId>io.fabric8</groupId>
  <artifactId>java-generator-maven-plugin</artifactId>
  <version>${fabric8.version}</version>
  <executions>
    <execution><goals><goal>generate</goal></goals></execution>
  </executions>
  <configuration>
    <source>${project.basedir}/src/main/resources/crd</source>
    <target>${project.build.directory}/generated-sources/java</target>
  </configuration>
</plugin>
```

## 3. 定义 CRD Java 类

创建资源类：

```java
package com.example.myoperator.api.v1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("example.com")
@Version("v1alpha1")
@Kind("MyResource")
@Plural("myresources")
@ShortNames({"my"})
public class MyResource extends CustomResource<MySpec, MyStatus> implements Namespaced {
}
```

创建普通的 Spec 与 Status 类。使用 `io.fabric8.generator.annotation` 中的 `@Required` 与 `@Default` 控制生成的 OpenAPI Schema。

## 4. 编写 Reconciler

```java
package com.example.myoperator;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;

public class MyReconciler implements Reconciler<MyResource> {
    @Override
    public Result reconcile(Request request, MyResource resource) {
        try {
            // 创建或更新子资源
            return Result.done();
        } catch (Exception e) {
            return Result.error(e);
        }
    }
}
```

需要再次调和时使用 `Result.requeueNow()` 或 `Result.requeueAfter(Duration)`。当返回 `Result.error(...)` 时，Operator 会自动按配置的重试策略进行重试。

## 5. 配置 Leader Election

```java
import com.huawei.dcs.modelengine.operator.framework.leader.LeaderElectionManager;

LeaderElectionManager leader = new LeaderElectionManager(client, "my-lock", namespace)
    .withLeaseDuration(Duration.ofSeconds(15))
    .withRenewDeadline(Duration.ofSeconds(10))
    .withRetryPeriod(Duration.ofSeconds(2));
leader.run(() -> operator.start());
```

只有 Leader 才会启动 Operator。当失去 Leader 身份时，Operator 运行线程会被中断。

## 6. 添加 Metrics 与 Health

```java
import com.huawei.dcs.modelengine.operator.framework.metrics.MetricsHealthServer;

MetricsHealthServer server = new MetricsHealthServer(8080);
server.start();
```

暴露端点：

- `GET /healthz` - 存活探测，返回 200
- `GET /readyz` - 就绪探测，检查通过返回 200，否则返回 503
- `GET /metrics` - Prometheus 指标

添加自定义就绪检查：

```java
server.addReadinessCheck(() -> operator.eventSources().stream()
    .allMatch(s -> s.getInformer().hasSynced()));
```

`MetricsHealthServer.metricsRegistry()` 返回 Micrometer 的 `MeterRegistry`，可传给 Reconciler 用于自定义 Counter 与 Timer。

## 7. 从 Java 类生成 CRD YAML

编译项目：

```bash
mvn -f example/echo-operator/pom.xml clean compile
```

CRD 生成器会将 YAML 写入：

```text
example/echo-operator/target/classes/META-INF/fabric8/echoresources.example.com-v1.yml
```

验证：

```bash
ls example/echo-operator/target/classes/META-INF/fabric8/
```

## 8. 从 CRD YAML 生成 Java 类

将 CRD YAML 文件放在：

```text
src/main/resources/crd/
```

然后编译：

```bash
mvn -f example/echo-operator/pom.xml clean compile
```

java-generator 插件会将生成的类写入：

```text
example/echo-operator/target/generated-sources/java/
```

验证：

```bash
ls example/echo-operator/target/generated-sources/java/
```

生成的类适用于引入其他团队维护的 CRD，或基于已有 Schema 快速搭建新 Operator。

## 9. 运行 Operator

使用 `exec-maven-plugin` 或打包为可执行 jar。Echo Operator 示例使用：

```bash
mvn -f example/echo-operator/pom.xml exec:java -Dexec.mainClass=com.example.echooperator.EchoOperatorMain
```

也可以使用辅助脚本：

```bash
example/echo-operator/scripts/local-run.sh
```

## 10. 验证端点

Operator 运行后：

```bash
curl -s http://localhost:8080/healthz
curl -s http://localhost:8080/readyz
curl -s http://localhost:8080/metrics
```

## 11. 添加 Admission Webhook

Admission Webhook 运行在独立的 TLS 服务器上，供 Kubernetes 通过 HTTPS 调用。

创建 TLS 服务：

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import com.huawei.dcs.modelengine.operator.framework.webhook.cert.CertWatcher;
import java.nio.file.Path;

WebhookServer webhookServer = WebhookServer.withCertWatcher(
    WebhookServer.DEFAULT_HOST, 8443,
    Path.of("/etc/operator/certs/tls.crt"),
    Path.of("/etc/operator/certs/tls.key"),
    Path.of("/etc/operator/certs/ca.crt"),
    CertWatcher.DEFAULT_POLLING_INTERVAL);
```

实现校验器与变更器：

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionValidator;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;

public class MyValidator implements AdmissionValidator<MyResource> {
    @Override
    public AdmissionResponse validate(AdmissionRequest request, MyResource resource) {
        if (resource.getSpec().replicas < 0) {
            return AdmissionResult.denied("replicas must not be negative");
        }
        return AdmissionResult.allowed();
    }
}
```

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionMutator;

public class MyMutator implements AdmissionMutator<MyResource> {
    @Override
    public AdmissionResponse mutate(AdmissionRequest request, MyResource resource) {
        return AdmissionResult.jsonPatch(
            "[{\"op\":\"add\",\"path\":\"/metadata/annotations/my.example.com~1defaulted\",\"value\":\"true\"}]");
    }
}
```

注册到服务：

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionHandler;

AdmissionHandler admissionHandler = new AdmissionHandler(client);
admissionHandler.registerValidator("my.example.com", MyResource.class, new MyValidator());
admissionHandler.registerMutator("my.example.com", MyResource.class, new MyMutator());
admissionHandler.register(webhookServer);
webhookServer.start();
```

这会自动暴露 `/validate/my.example.com` 和 `/mutate/my.example.com`。Handler 解析 `AdmissionReview`，将 `request.object` 转换为已注册资源类，并序列化响应。变更器的 JSON Patch 会自动 base64 编码。

若要在启动时自动注册 Webhook 配置：

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookRegistrationConfig;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookSelfRegistration;

WebhookRegistrationConfig registrationConfig = WebhookRegistrationConfig.builder(
    "my-operator", "my-namespace", Path.of("/etc/operator/certs/ca.crt"))
    .withServicePort(443)
    .withFailurePolicy("Fail")
    .withTimeoutSeconds(10)
    .withSideEffects("None")
    .build();

WebhookSelfRegistration registration = new WebhookSelfRegistration(client, registrationConfig);
registration.register(admissionHandler);
```

`WebhookSelfRegistration` 读取 CA 证书并 base64 编码，然后为每个已注册的 Webhook 名称创建或更新一个 `ValidatingWebhookConfiguration` 和一个 `MutatingWebhookConfiguration`。Operator 需要 `admissionregistration.k8s.io` 的 RBAC 权限。


### 证书生成

默认情况下，`WebhookCertificateGenerator` 会自动为 Webhook 服务生成 CA 证书包和服务端证书，并将 `ca.crt`、`tls.crt`、`tls.key` 写入 `WEBHOOK_CERT_DIRECTORY`（默认 `/tmp/echo-operator/certs`）。证书 SAN 覆盖服务名及其 FQDN 变体，服务端证书设置 `serverAuth` 扩展密钥用途。将 `WEBHOOK_CERT_AUTO_GENERATE` 设为 `false` 可回退到基于文件的方式，从 `WEBHOOK_CA_BUNDLE_PATH` 及其同目录下的 `tls.crt`/`tls.key` 加载证书和私钥。

## 12. 添加 Conversion Webhook

对于多版本 CRD，实现 `ConversionWebhookHandler` 并挂载到同一个 TLS 服务。

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionResult;
import io.fabric8.kubernetes.api.model.HasMetadata;

ConversionHandler conversionHandler = new ConversionHandler(client);
conversionHandler.register("example.com/v1alpha1", "example.com/v1alpha2",
    (desiredVersion, resource) -> ConversionResult.converted(toV2(resource)));
conversionHandler.register("example.com/v1alpha2", "example.com/v1alpha1",
    (desiredVersion, resource) -> ConversionResult.converted(toV1(resource)));
conversionHandler.register(webhookServer);
```

在资源类中标记版本：

```java
@Version(value = "v1alpha1", storage = false, served = true, deprecated = true)
public class MyResourceV1 extends CustomResource<MySpecV1, MyStatusV1> implements Namespaced {
}

@Version(value = "v1alpha2", storage = true, served = true)
public class MyResourceV2 extends CustomResource<MySpecV2, MyStatusV2> implements Namespaced {
}
```

Conversion Handler 接收 `apiextensions.k8s.io/v1 ConversionReview` 请求，按 `(源 apiVersion, 目标 apiVersion)` 路由并返回 review 响应。成功时返回 `ConversionResult.converted(...)`，失败时返回 `ConversionResult.failed(...)`。同版本请求直接透传。

## 13. 同时启动 Webhook、Metrics 与 Operator

典型的启动顺序：

```java
webhookServer.start();
webhookSelfRegistration.register(admissionHandler);
metricsHealthServer.start();
operator.start();
```

关闭顺序相反：

```java
webhookServer.stop();
operator.stop();
metricsHealthServer.close();
client.close();
```

Webhook 服务与 Metrics/Health 服务相互独立。Metrics 默认在 8080 端口，Admission 与 Conversion Webhook 共享 8443 端口的 TLS 服务。
