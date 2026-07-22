package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerEventListener;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperatorBackwardCompatibilityTest {

  @Test
  @SuppressWarnings("unchecked")
  void classBasedRegisterCreatesOnlyPrimaryEventSource() throws Exception {
    KubernetesClient client = mock(KubernetesClient.class);
    SharedIndexInformer<ConfigMap> informer = mock(SharedIndexInformer.class);
    TestInformerFactory namespaceFactory = new TestInformerFactory(informer);
    TestInformerFactory rootFactory = new TestInformerFactory(namespaceFactory);
    Reconciler<ConfigMap> reconciler = mock(Reconciler.class);

    when(client.informers()).thenReturn(rootFactory);
    when(informer.addEventHandler(any())).thenReturn(informer);

    try (Operator operator = new Operator(client)) {
      operator.withNamespace("default").withShutdownHookEnabled(false);
      operator.register(ConfigMap.class, reconciler);
      operator.start();

      assertEquals(1, operator.eventSources().size());
    }
  }

  private static final class TestInformerFactory implements SharedInformerFactory {
    private final SharedIndexInformer<ConfigMap> informer;
    private final TestInformerFactory namespaceFactory;

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
      return (SharedIndexInformer<T>) informer;
    }

    @Override
    public <T> SharedIndexInformer<T> getExistingSharedIndexInformer(Class<T> apiTypeClass) {
      return null;
    }

    @Override
    public Future<Void> startAllRegisteredInformers() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void stopAllRegisteredInformers() {
    }

    @Override
    public void addSharedInformerEventListener(SharedInformerEventListener listener) {
    }
  }
}
