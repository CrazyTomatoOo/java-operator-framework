package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventSource;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerEventListener;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Store;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperatorSecondaryWatchTest {

  @Test
  @SuppressWarnings("unchecked")
  void registerControllerRegistrationCreatesSecondarySourceSharingPrimaryQueue() throws Exception {
    KubernetesClient client = mock(KubernetesClient.class);
    SharedIndexInformer<ConfigMap> primaryInformer = mock(SharedIndexInformer.class);
    SharedIndexInformer<Secret> secondaryInformer = mock(SharedIndexInformer.class);
    TestInformerFactory namespaceFactory = new TestInformerFactory(Map.of(
        ConfigMap.class, primaryInformer,
        Secret.class, secondaryInformer));
    TestInformerFactory rootFactory = new TestInformerFactory(namespaceFactory);
    Store<ConfigMap> primaryStore = mock(Store.class);
    Reconciler<ConfigMap> reconciler = mock(Reconciler.class);
    ConfigMap primary = new ConfigMapBuilder()
        .withNewMetadata()
        .withNamespace("default")
        .withName("primary-config")
        .endMetadata()
        .build();
    Secret secondary = new SecretBuilder()
        .withNewMetadata()
        .withNamespace("default")
        .withName("owned-secret")
        .endMetadata()
        .build();
    ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
        .withReconciler(reconciler)
        .watches("secret-source", Secret.class, (secret, event) -> List.of(new Request("default", "primary-config")))
        .build();

    when(client.informers()).thenReturn(rootFactory);
    when(primaryInformer.addEventHandler(any())).thenReturn(primaryInformer);
    when(secondaryInformer.addEventHandler(any())).thenReturn(secondaryInformer);
    when(primaryInformer.getStore()).thenReturn(primaryStore);
    when(primaryStore.getByKey("default/primary-config")).thenReturn(primary);
    when(reconciler.reconcile(any(), same(primary))).thenReturn(Result.done());

    ArgumentCaptor<ResourceEventHandler<Secret>> secondaryHandlerCaptor = ArgumentCaptor.forClass(ResourceEventHandler.class);
    try (Operator operator = new Operator(client)) {
      operator.withNamespace("default").withShutdownHookEnabled(false);
      operator.register(registration);
      operator.start();

      List<ResourceEventSource<?>> eventSources = operator.eventSources();
      assertEquals(2, eventSources.size());
      // Shared queue behavior is validated by the end-to-end event flow below.
      verify(secondaryInformer).addEventHandler(secondaryHandlerCaptor.capture());

      secondaryHandlerCaptor.getValue().onAdd(secondary);

      verify(reconciler, timeout(2_000)).reconcile(new Request("default", "primary-config"), primary);
    }
  }

  private static final class TestInformerFactory implements SharedInformerFactory {
    private final Map<Class<?>, SharedIndexInformer<?>> informers;
    private final TestInformerFactory namespaceFactory;
    private String requestedNamespace;
    private boolean started;
    private boolean stopped;

    private TestInformerFactory(Map<Class<?>, SharedIndexInformer<?>> informers) {
      this.informers = new HashMap<>(informers);
      this.namespaceFactory = null;
    }

    private TestInformerFactory(TestInformerFactory namespaceFactory) {
      this.informers = Map.of();
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
    public <T extends HasMetadata> SharedIndexInformer<T> sharedIndexInformerFor(
        Class<T> apiTypeClass, long resyncPeriodInMillis) {
      return (SharedIndexInformer<T>) informers.get(apiTypeClass);
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
