package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.metrics.MetricsServer;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventType;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;
import com.huawei.dcs.modelengine.operator.framework.reconciler.TriggerRole;
import com.huawei.dcs.modelengine.operator.framework.retry.ExponentialBackoffRetryPolicy;
import com.huawei.dcs.modelengine.operator.framework.retry.RateLimiter;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedInformerEventListener;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Store;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperatorLauncherTest {

  @Test
  @SuppressWarnings("unchecked")
  void startStopUsesNamespaceScopedInformerAndClosesResources() throws Exception {
    KubernetesClient client = mock(KubernetesClient.class);
    SharedIndexInformer<ConfigMap> informer = mock(SharedIndexInformer.class);
    TestInformerFactory namespaceFactory = new TestInformerFactory(informer);
    TestInformerFactory rootFactory = new TestInformerFactory(namespaceFactory);
    Store<ConfigMap> store = mock(Store.class);
    Reconciler<ConfigMap> reconciler = mock(Reconciler.class);

    when(client.getNamespace()).thenReturn("operator-system");
    when(client.informers()).thenReturn(rootFactory);
    when(informer.addEventHandler(any())).thenReturn(informer);
    when(informer.getStore()).thenReturn(store);
    when(reconciler.reconcile(any(), any())).thenReturn(Result.done());

    try (Operator operator = new Operator(client)) {
      operator.withShutdownHookEnabled(false);
      operator.register(ConfigMap.class, reconciler);
      operator.start();
      operator.stop();
    }

    org.junit.jupiter.api.Assertions.assertEquals("operator-system", rootFactory.requestedNamespace);
    org.junit.jupiter.api.Assertions.assertTrue(namespaceFactory.started);
    org.junit.jupiter.api.Assertions.assertTrue(namespaceFactory.stopped);
    verify(client).close();
  }

  @Test
  @SuppressWarnings("unchecked")
  void registeredReconcilerReceivesRequestFromInformerEvent() throws Exception {
    KubernetesClient client = mock(KubernetesClient.class);
    SharedIndexInformer<ConfigMap> informer = mock(SharedIndexInformer.class);
    TestInformerFactory namespaceFactory = new TestInformerFactory(informer);
    TestInformerFactory rootFactory = new TestInformerFactory(namespaceFactory);
    Store<ConfigMap> store = mock(Store.class);
    Reconciler<ConfigMap> reconciler = mock(Reconciler.class);
    ConfigMap resource = new ConfigMapBuilder()
        .withNewMetadata()
        .withNamespace("default")
        .withName("echo-config")
        .endMetadata()
        .build();

    when(client.informers()).thenReturn(rootFactory);
    when(informer.addEventHandler(any())).thenReturn(informer);
    when(informer.getStore()).thenReturn(store);
    when(store.getByKey("default/echo-config")).thenReturn(resource);
    when(reconciler.reconcile(any(), same(resource))).thenReturn(Result.done());

    ArgumentCaptor<ResourceEventHandler<ConfigMap>> handlerCaptor = ArgumentCaptor.forClass(ResourceEventHandler.class);
    try (Operator operator = new Operator(client)) {
      operator.withNamespace("default").withShutdownHookEnabled(false);
      operator.register(ConfigMap.class, reconciler);
      operator.start();
      verify(informer).addEventHandler(handlerCaptor.capture());

      handlerCaptor.getValue().onAdd(resource);

      verify(reconciler, timeout(2_000)).reconcile(new Request("default", "echo-config"), resource);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void failedReconciliationIsRetriedWithBackoffAndThenResetsOnSuccess() throws Exception {
    KubernetesClient client = mock(KubernetesClient.class);
    SharedIndexInformer<ConfigMap> informer = mock(SharedIndexInformer.class);
    TestInformerFactory namespaceFactory = new TestInformerFactory(informer);
    TestInformerFactory rootFactory = new TestInformerFactory(namespaceFactory);
    Store<ConfigMap> store = mock(Store.class);
    Reconciler<ConfigMap> reconciler = mock(Reconciler.class);
    MetricsServer metricsServer = mock(MetricsServer.class);
    ConfigMap resource = new ConfigMapBuilder()
        .withNewMetadata()
        .withNamespace("default")
        .withName("retry-config")
        .endMetadata()
        .build();

    when(client.informers()).thenReturn(rootFactory);
    when(informer.addEventHandler(any())).thenReturn(informer);
    when(informer.getStore()).thenReturn(store);
    when(store.getByKey("default/retry-config")).thenReturn(resource);
    when(reconciler.reconcile(any(), same(resource)))
        .thenReturn(Result.error(new IllegalStateException("transient failure")))
        .thenReturn(Result.done());

    ArgumentCaptor<ResourceEventHandler<ConfigMap>> handlerCaptor = ArgumentCaptor.forClass(ResourceEventHandler.class);
    try (Operator operator = new Operator(client)) {
      operator.withNamespace("default")
          .withShutdownHookEnabled(false)
          .withMetricsServer(metricsServer)
          .withRateLimiter(new RateLimiter(Duration.ZERO))
          .withRetryPolicy(new ExponentialBackoffRetryPolicy(Duration.ofMillis(10), Duration.ofMillis(10), 2));
      operator.register(ConfigMap.class, reconciler);
      operator.start();
      verify(informer).addEventHandler(handlerCaptor.capture());

      handlerCaptor.getValue().onAdd(resource);

      verify(reconciler, timeout(2_000).times(2)).reconcile(new Request("default", "retry-config"), resource);
      verify(metricsServer, timeout(2_000)).recordReconcile("ConfigMap", "error");
      verify(metricsServer, timeout(2_000)).recordReconcile("ConfigMap", "success");
      verify(metricsServer, timeout(2_000)).recordReconcileError("ConfigMap");
      verify(metricsServer, timeout(2_000).times(2)).recordReconcileDuration(any(), any(Duration.class));
      assertEquals(1, operator.reconcileErrorCount());
    }
  }
  @Test
  @SuppressWarnings("unchecked")
  void backwardCompatibleRegistrationEnqueuesPrimaryTrigger() throws Exception {
    KubernetesClient client = mock(KubernetesClient.class);
    SharedIndexInformer<ConfigMap> informer = mock(SharedIndexInformer.class);
    TestInformerFactory namespaceFactory = new TestInformerFactory(informer);
    TestInformerFactory rootFactory = new TestInformerFactory(namespaceFactory);
    Store<ConfigMap> store = mock(Store.class);
    Reconciler<ConfigMap> reconciler = mock(Reconciler.class);
    ConfigMap resource = new ConfigMapBuilder()
        .withNewMetadata()
        .withNamespace("default")
        .withName("trigger-config")
        .endMetadata()
        .build();

    when(client.informers()).thenReturn(rootFactory);
    when(informer.addEventHandler(any())).thenReturn(informer);
    when(informer.getStore()).thenReturn(store);
    when(store.getByKey("default/trigger-config")).thenReturn(resource);
    when(reconciler.reconcile(any(), same(resource))).thenReturn(Result.done());

    ArgumentCaptor<ResourceEventHandler<ConfigMap>> handlerCaptor = ArgumentCaptor.forClass(ResourceEventHandler.class);
    ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
    try (Operator operator = new Operator(client)) {
      operator.withNamespace("default").withShutdownHookEnabled(false);
      operator.register(ConfigMap.class, reconciler);
      operator.start();
      verify(informer).addEventHandler(handlerCaptor.capture());

      handlerCaptor.getValue().onAdd(resource);

      verify(reconciler, timeout(2_000)).reconcile(requestCaptor.capture(), same(resource));
      Request request = requestCaptor.getValue();
      assertEquals("default", request.namespace());
      assertEquals("trigger-config", request.name());
      assertEquals(1, request.triggers().size());
      Trigger trigger = request.triggers().get(0);
      assertEquals(ResourceEventType.ADD, trigger.eventType());
      assertEquals(TriggerRole.PRIMARY, trigger.role());
      assertEquals("ConfigMap", trigger.kind());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldNotReconcileOnStatusWritebackWhenGenerationFilterEnabled() throws Exception {
    KubernetesClient client = mock(KubernetesClient.class);
    SharedIndexInformer<ConfigMap> informer = mock(SharedIndexInformer.class);
    TestInformerFactory namespaceFactory = new TestInformerFactory(informer);
    TestInformerFactory rootFactory = new TestInformerFactory(namespaceFactory);
    Store<ConfigMap> store = mock(Store.class);
    Reconciler<ConfigMap> reconciler = mock(Reconciler.class);
    ConfigMap current = new ConfigMapBuilder()
        .withNewMetadata()
        .withNamespace("default")
        .withName("echo-config")
        .withGeneration(1L)
        .endMetadata()
        .withData(Map.of("key", "value"))
        .build();
    // Status writeback shape: payload changes while metadata.generation stays the same.
    ConfigMap statusWriteback = new ConfigMapBuilder()
        .withNewMetadata()
        .withNamespace("default")
        .withName("echo-config")
        .withGeneration(1L)
        .endMetadata()
        .withData(Map.of("key", "status-updated"))
        .build();
    ConfigMap generationChanged = new ConfigMapBuilder()
        .withNewMetadata()
        .withNamespace("default")
        .withName("echo-config")
        .withGeneration(2L)
        .endMetadata()
        .withData(Map.of("key", "spec-updated"))
        .build();

    when(client.informers()).thenReturn(rootFactory);
    when(informer.addEventHandler(any())).thenReturn(informer);
    when(informer.getStore()).thenReturn(store);
    when(store.getByKey("default/echo-config")).thenReturn(current);
    when(reconciler.reconcile(any(), any())).thenReturn(Result.done());

    ArgumentCaptor<ResourceEventHandler<ConfigMap>> handlerCaptor = ArgumentCaptor.forClass(ResourceEventHandler.class);
    try (Operator operator = new Operator(client)) {
      operator.withNamespace("default")
          .withShutdownHookEnabled(false)
          .withRateLimiter(new RateLimiter(Duration.ZERO));
      operator.register(ControllerBuilder.forResource(ConfigMap.class)
          .withReconciler(reconciler)
          .withGenerationChangeFilter()
          .build());
      operator.start();
      verify(informer).addEventHandler(handlerCaptor.capture());

      handlerCaptor.getValue().onAdd(current);
      verify(reconciler, timeout(2_000)).reconcile(new Request("default", "echo-config"), current);

      // Scenario A: same-generation update (status writeback) must be dropped by the filter,
      // so the reconcile count stays at 1 within a bounded window.
      handlerCaptor.getValue().onUpdate(current, statusWriteback);
      verify(reconciler, after(2_000).times(1)).reconcile(any(), any());

      // Scenario B: a generation change passes the filter and triggers a second reconcile.
      handlerCaptor.getValue().onUpdate(statusWriteback, generationChanged);
      verify(reconciler, timeout(2_000).times(2)).reconcile(any(), any());
    }
  }


  private static final class TestInformerFactory implements SharedInformerFactory {
    private final SharedIndexInformer<ConfigMap> informer;
    private final TestInformerFactory namespaceFactory;
    private String requestedNamespace;
    private boolean started;
    private boolean stopped;
    private long resyncPeriodMs;

    private TestInformerFactory(SharedIndexInformer<ConfigMap> informer) {
      this.informer = informer;
      this.namespaceFactory = null;
    }

    private TestInformerFactory(TestInformerFactory namespaceFactory) {
      this.informer = null;
      this.namespaceFactory = namespaceFactory;
    }

    @Override
    public SharedInformerFactory inNamespace(String namespace) {
      this.requestedNamespace = namespace;
      return namespaceFactory;
    }

    @Override
    public SharedInformerFactory withName(String name) {
      return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends io.fabric8.kubernetes.api.model.HasMetadata> SharedIndexInformer<T> sharedIndexInformerFor(
        Class<T> apiTypeClass, long resyncPeriodInMillis) {
      this.resyncPeriodMs = resyncPeriodInMillis;
      return (SharedIndexInformer<T>) informer;
    }

    @Override
    public <T> SharedIndexInformer<T> getExistingSharedIndexInformer(Class<T> apiTypeClass) {
      return null;
    }

    @Override
    public Future<Void> startAllRegisteredInformers() {
      this.started = true;
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void stopAllRegisteredInformers() {
      this.stopped = true;
    }

    @Override
    public void addSharedInformerEventListener(SharedInformerEventListener listener) {
    }
  }

}
