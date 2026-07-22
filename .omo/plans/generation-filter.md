# 实施方案：Primary Source 的 generation/filter 入队控制

## 目标

为 operator-framework 增加可选的 primary source 事件过滤能力：

1. **过滤条件**：仅在 `generation` 变化、或 `deletionTimestamp` 首次出现、或 `finalizers` 变化时才把 UPDATE 事件入队；
2. **只作用于 primary source**，secondary source 完全不过滤；
3. **按 controller 可配置**，默认关闭以保持向后兼容；
4. **resync 行为显式可控**（默认随过滤关闭 60s 自愈 re-enqueue，提供 `withResyncPeriod` 让用户自行决定）。

完成后，crud 压测中实测的 ~46% status 回写回声 reconcile 应当趋近于 0。

## 影响范围

- `SourceConfiguration` — 增加 `generationChangeFilter` 配置字段
- `ControllerRegistration` — 增加 `generationChangeFilter` 和 `resyncPeriod`
- `ControllerBuilder` — 增加 fluent API
- `ResourceEventSource` — 在 `EnqueueingEventHandler.onUpdate` 中实现过滤
- `Operator` — 把注册信息透传给 primary source
- 测试：`ResourceEventSourceTest` 新增单元测试；`OperatorLauncherTest` 或新增集成测试验证端到端行为
- 文档：README 更新说明新 API

## 详细设计

### 1. SourceConfiguration 增加过滤开关

位置：`operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/SourceConfiguration.java`

当前：
```java
public record SourceConfiguration<R extends HasMetadata>(
        String name,
        Class<R> resourceClass,
        SourceRole role,
        ResourceMapper<R, ?> mapper) {
    ...
}
```

变更为：
```java
public record SourceConfiguration<R extends HasMetadata>(
        String name,
        Class<R> resourceClass,
        SourceRole role,
        ResourceMapper<R, ?> mapper,
        boolean generationChangeFilter) {

    public SourceConfiguration {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(resourceClass, "resourceClass must not be null");
        Objects.requireNonNull(role, "role must not be null");
        if (role == SourceRole.SECONDARY) {
            Objects.requireNonNull(mapper, "mapper must not be null for secondary sources");
        }
    }

    public SourceConfiguration(String name, Class<R> resourceClass, SourceRole role, ResourceMapper<R, ?> mapper) {
        this(name, resourceClass, role, mapper, false);
    }
}
```

说明：`generationChangeFilter` 对 primary/secondary 都可携带，但实际过滤逻辑只在 `role == PRIMARY` 时启用。保留 secondary 携带该字段不报错，避免 API 分裂。

### 2. ControllerRegistration 增加配置

位置：`operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/ControllerRegistration.java`

当前：
```java
public record ControllerRegistration<P extends HasMetadata>(
    Class<P> resourceClass,
    Reconciler<P> reconciler,
    List<SecondaryWatch<P, ?>> secondaryWatches) {
    ...
}
```

变更为：
```java
public record ControllerRegistration<P extends HasMetadata>(
    Class<P> resourceClass,
    Reconciler<P> reconciler,
    List<SecondaryWatch<P, ?>> secondaryWatches,
    boolean generationChangeFilter,
    java.time.Duration resyncPeriod) {

    public ControllerRegistration {
        Objects.requireNonNull(resourceClass, "resourceClass must not be null");
        Objects.requireNonNull(reconciler, "reconciler must not be null");
        secondaryWatches = List.copyOf(Objects.requireNonNull(secondaryWatches, "secondaryWatches must not be null"));
        Objects.requireNonNull(resyncPeriod, "resyncPeriod must not be null");
        if (resyncPeriod.isNegative()) {
            throw new IllegalArgumentException("resyncPeriod must not be negative");
        }
    }

    public ControllerRegistration(Class<P> resourceClass, Reconciler<P> reconciler, List<SecondaryWatch<P, ?>> secondaryWatches) {
        this(resourceClass, reconciler, secondaryWatches, false, java.time.Duration.ofMillis(ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS));
    }
}
```

说明：
- 新增 `generationChangeFilter` 开关；
- 新增 `resyncPeriod`，默认 `ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS`（60s），允许用户设置为 `Duration.ZERO` 关闭 resync；
- 保留旧构造器用于向后兼容。

### 3. ControllerBuilder 增加 fluent API

位置：`operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/ControllerBuilder.java`

在 `secondaryWatches` 字段后增加：
```java
private boolean generationChangeFilter = false;
private Duration resyncPeriod = Duration.ofMillis(ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS);
```

新增方法：
```java
public ControllerBuilder<P> withGenerationChangeFilter() {
    return withGenerationChangeFilter(true);
}

public ControllerBuilder<P> withGenerationChangeFilter(boolean enabled) {
    this.generationChangeFilter = enabled;
    return this;
}

public ControllerBuilder<P> withResyncPeriod(Duration resyncPeriod) {
    this.resyncPeriod = Objects.requireNonNull(resyncPeriod, "resyncPeriod must not be null");
    if (resyncPeriod.isNegative()) {
        throw new IllegalArgumentException("resyncPeriod must not be negative");
    }
    return this;
}
```

`build()` 改为：
```java
public ControllerRegistration<P> build() {
    if (reconciler == null) {
        throw new IllegalStateException("reconciler must be configured before build");
    }
    return new ControllerRegistration<>(resourceClass, reconciler, secondaryWatches, generationChangeFilter, resyncPeriod);
}
```

### 4. ResourceEventSource 实现过滤逻辑

位置：`operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/source/ResourceEventSource.java`

#### 4.1 字段与构造器

当前构造器都通过 `this(...)` 收敛到一个。在 `ResourceEventSource` 类中新增字段：
```java
private final boolean generationChangeFilter;
```

所有现有构造器默认值设为 `false`。新增接收 `boolean generationChangeFilter` 的内部构造器，并在全参数构造中保存该字段。

最小化改动方式：
- 保留所有现有公共构造器签名不变，内部委托到新增的全参数构造器，`generationChangeFilter = false`；
- 新增一个构造器（或包内可见构造器）允许 `Operator` 传入 `generationChangeFilter`。

#### 4.2 过滤方法

新增私有方法：
```java
private boolean shouldEnqueue(T oldResource, T newResource) {
    if (oldResource == null || newResource == null) {
        return true;
    }
    var oldMeta = oldResource.getMetadata();
    var newMeta = newResource.getMetadata();
    if (oldMeta == null || newMeta == null) {
        return true;
    }
    // Deletion requested / finalizer progression -> always enqueue
    boolean deletionRequested = newMeta.getDeletionTimestamp() != null
            && (oldMeta.getDeletionTimestamp() == null);
    boolean finalizersChanged = !Objects.equals(oldMeta.getFinalizers(), newMeta.getFinalizers());
    if (deletionRequested || finalizersChanged) {
        return true;
    }
    // Generation changed -> enqueue
    return !Objects.equals(oldMeta.getGeneration(), newMeta.getGeneration());
}
```

注意：`deletionTimestamp` 判断只需"从 null 到非 null"触发一次。对象进入删除状态后，后续 finalizer 移除通过 `finalizersChanged` 触发； finalizer 全部移除后对象被 GC，触发 onDelete，不受过滤影响。

#### 4.3 onUpdate 调用点

修改 `EnqueueingEventHandler.onUpdate`：
```java
@Override
public void onUpdate(T oldResource, T newResource) {
    if (configuration.role() == SourceRole.PRIMARY
            && configuration.generationChangeFilter()
            && !shouldEnqueue(oldResource, newResource)) {
        return;
    }
    enqueue(newResource, oldResource, ResourceEventType.UPDATE);
}
```

`onAdd`、`onDelete` 不受影响。

### 5. Operator 透传配置

位置：`operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/Operator.java`

#### 5.1 createPrimaryEventSource

当前：
```java
private <T extends HasMetadata> ResourceEventSource<T> createPrimaryEventSource(
        ControllerRegistration<T> registration,
        BlockingQueue<Request> queue) {
    SourceConfiguration<T> configuration = new SourceConfiguration<>(
            registration.resourceClass().getSimpleName(),
            registration.resourceClass(),
            SourceRole.PRIMARY,
            null);
    return new ResourceEventSource<>(informerFactory, configuration, queue, DEFAULT_RESYNC_PERIOD_MS);
}
```

改为：
```java
private <T extends HasMetadata> ResourceEventSource<T> createPrimaryEventSource(
        ControllerRegistration<T> registration,
        BlockingQueue<Request> queue) {
    SourceConfiguration<T> configuration = new SourceConfiguration<>(
            registration.resourceClass().getSimpleName(),
            registration.resourceClass(),
            SourceRole.PRIMARY,
            null,
            registration.generationChangeFilter());
    return new ResourceEventSource<>(informerFactory, configuration, queue, registration.resyncPeriod().toMillis());
}
```

注意：若 `registration.resyncPeriod()` 为 `Duration.ZERO`，需要确认 fabric8 `sharedIndexInformerFor(Class, 0)` 是否关闭 resync。fabric8 通常把 0 视为"不 resync"，但需在实现后测试确认；若 fabric8 不接受 0，可改为用 `Long.MAX_VALUE` 或最小正值，并在代码注释中说明。

#### 5.2 createSecondaryEventSource

当前已用 `DEFAULT_RESYNC_PERIOD_MS`；为了统一，可改为使用 `registration.resyncPeriod().toMillis()`，因为 resyncPeriod 注册在 controller 级别，对 secondary 也合理。若希望 secondary 保持默认 60s 不变，则不动。推荐：secondary 也使用该 controller 的 `resyncPeriod`，保持行为一致。

### 6. 测试计划

#### 6.1 ResourceEventSourceTest 新增单元测试

在 `shouldEnqueuePrimaryRequestsForAddUpdateAndDeleteEvents` 同级新增：

```java
@Test
void shouldFilterPrimaryStatusUpdatesWhenGenerationChangeFilterEnabled() {
    // setup informer mocks ...
    BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    SourceConfiguration<ConfigMap> configuration = new SourceConfiguration<>(
            "primary-config-maps", ConfigMap.class, SourceRole.PRIMARY, null, true);
    ResourceEventSource<ConfigMap> eventSource = new ResourceEventSource<>(client, configuration, queue, 5_000L);
    ResourceEventHandler<ConfigMap> handler = registeredHandler();

    ConfigMap oldResource = configMapWithGeneration("demo", "old", 1L);
    ConfigMap newResource = configMapWithGeneration("demo", "new", 1L); // generation unchanged

    handler.onUpdate(oldResource, newResource);

    assertEquals(0, queue.size());
}

@Test
void shouldEnqueuePrimaryDeletionAndFinalizerChangesDespiteFilter() {
    // ... generation filter enabled
    // case A: deletionTimestamp newly set
    // case B: finalizers list changed
    // assert queue size == 2
}

@Test
void shouldNotFilterSecondaryUpdatesWhenFilterEnabledOnSecondarySource() {
    // secondary source with generationChangeFilter=true
    // onUpdate with unchanged generation
    // assert queue size == 1
}
```

需要给 `configMap(...)` 增加带 `generation` 和 `deletionTimestamp` 的 helper，或新增 `configMapWithGeneration(...)`。

#### 6.2 OperatorLauncherTest 新增集成测试

在 `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/OperatorLauncherTest.java` 中新增：

```java
@Test
void shouldNotReconcileOnStatusWritebackWhenGenerationFilterEnabled() {
    // 注册 generationChangeFilter=true 的控制器
    // 启动 operator
    // 创建 CR
    // 等第一次 reconcile 把 status.observedSeq 写回
    // 等待一段时间，断言 reconcile 调用次数 == 1（或稳定，不持续增长）
}
```

具体断言方式取决于 `OperatorLauncherTest` 中已有的 fake informer factory。可能需要暴露一个可计数的 reconciler。

#### 6.3 压测验证

重建 stress-test：
```bash
mvn -f operator/framework/pom.xml clean install
mvn -f stress-test/pom.xml clean package
java -jar stress-test/target/operator-stress-test-0.1.0-SNAPSHOT.jar \
  --keys 500 --rate 4000 --duration-sec 60 --write-threads 24 --worker-threads 32 \
  --reconcile-mode crud --report-interval-sec 10
```

预期结果（与当前对比）：
- `Echoes` 从 ~13,000 降到接近 0；
- `Reconciles` 从 37,334 提升到接近 API 预算允许的上限（因为不再浪费 worker 在回声上）；
- 总 API 写入仍收敛到 ~2050/s；
- p50/p99 延迟下降。

#### 6.4 Finalizer 回归测试（可选但重要）

给 `StressTestResource` 加一个 finalizer（或在框架单测中），启用 filter 后：
1. 创建资源；
2. 删除资源；
3. 验证 reconciler 收到至少一次 deletionTimestamp 触发的事件，并能完成 finalizer 移除 + 对象被 GC。

这直接验证副作用 1（finalizer 静默失效）被规避。

## 向后兼容

- 所有默认行为不变：`generationChangeFilter = false`。
- `Operator.register(Class, Reconciler)` 不走 `ControllerBuilder`，保持不过滤。
- `ControllerRegistration` 旧三参数构造器保留，行为与之前一致。
- 只有显式调用 `.withGenerationChangeFilter()` 的用户才会启用新行为。

## 文档更新

在 `operator/framework/README.md` 的 `ControllerBuilder` 章节增加：

```markdown
### Generation-change filtering

By default every update event (including status writebacks) enqueues a reconcile. For controllers that write status, this causes self-triggered "echo" reconciles.

Use `.withGenerationChangeFilter()` to enqueue only when the primary resource's spec (generation) changes, deletion is requested, or finalizers change:

```java
ControllerRegistration<MyResource> registration = ControllerBuilder.forResource(MyResource.class)
    .withReconciler(new MyReconciler())
    .withGenerationChangeFilter()
    .withResyncPeriod(Duration.ZERO) // optional: disable periodic resync
    .build();
```

When enabled, status updates are filtered at the source and no longer waste worker threads.
```

## 风险与决策点

1. **resync 是否保留**：推荐方案是"过滤 naturally 丢弃 resync"，并通过 `withResyncPeriod(Duration.ZERO)` 显式关闭。如果用户希望保留 resync 作为自愈兜底，可以:
   - 保持 `resyncPeriod` 默认 60s，接受 resync 事件被过滤（等于保留 informer cache 自愈，但不再周期性 re-reconcile）；或
   - 在 `shouldEnqueue` 中增加 `oldResource == newResource` 特殊分支，但此判断依赖 fabric8 内部行为，版本升级后可能失效，**不推荐**。
2. **secondary 的 owned watches**：`ControllerBuilder.owns()` 使用 `Mappers.ownerReferences()`，secondary 更新事件不过滤，符合设计。
3. **CRD 未启用 status 子资源**：此时 status 写入 bump generation，过滤无效；`StressReconciler.isEcho` 作为 reconciler 级兜底仍然有效。

## 验收标准

- [ ] `mvn -f operator/framework/pom.xml test` 全部通过；
- [ ] 新增 3 个以上 `ResourceEventSourceTest` 单元测试覆盖过滤/不过滤场景；
- [ ] 新增/扩展集成测试验证 filter 启用后 status 回写不再触发二次 reconcile；
- [ ] stress-test crud 模式回声 reconcile 数量下降 90% 以上；
- [ ] README 已更新新 API。

## TODOs

- [x] 1. 核心实现+单元测试（TDD）— 按 §1-§5 修改 SourceConfiguration/ControllerRegistration/ControllerBuilder/ResourceEventSource/Operator；先跑基线 `mvn test` 确认绿色，再先写 ResourceEventSourceTest 三个新用例（编译失败=red），实现后全套测试转绿；实证 fabric8 resync=0 语义并记录证据
- [x] 2. 集成测试 — OperatorLauncherTest 新增 `shouldNotReconcileOnStatusWritebackWhenGenerationFilterEnabled`，验证 filter 启用后 status 回写不触发二次 reconcile
- [x] 3. 文档 — operator/framework/README.md 按「文档更新」节新增 Generation-change filtering 章节
- [x] 4. 压测验证 — `mvn clean install` 框架、重打包 stress-test、跑 crud 压测（32 worker），Echoes 较基线（12839/13805）下降 ≥90%，总 API 写入仍 ~2050/s

## Final Verification Wave

- [x] F1. `mvn -f operator/framework/pom.xml test` 全套通过，且 ResourceEventSourceTest 测试数较基线 +3 以上（附基线/当前计数证据）
- [x] F2. 压测数字复核 — Echoes 降幅 ≥90%、Reconciles 提升、总 API 写入 ~2050/s、p50/p99 下降，与 ledger 一致
- [x] F3. 独立代码审查（oracle）— 过滤条件完备性（deletionTimestamp/finalizer/generation 三分支）、null 安全、向后兼容（默认 false、旧构造器保留）、secondary 零影响
- [x] F4. 对抗性检查 — resync=0 语义有实证而非假设；null metadata/generation 路径有单测；无 stale jar（clean install 证据）；无误导性成功输出（测试计数证据）
