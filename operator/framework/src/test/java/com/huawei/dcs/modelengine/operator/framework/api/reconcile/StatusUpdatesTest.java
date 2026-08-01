package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnableKubernetesMockClient(crud = true)
class StatusUpdatesTest {
    KubernetesClient client;

    @Test
    void updatePatchesStatusWithoutMutatingTheGivenResource() {
        var pod = client.pods().inNamespace("ns").resource(new PodBuilder()
                .withNewMetadata().withNamespace("ns").withName("p").endMetadata()
                .withNewSpec().addNewContainer().withName("c").withImage("img").endContainer().endSpec()
                .build()).create();

        var updated = StatusUpdates.update(client, pod, new PodStatusBuilder().withPhase("Running").build());

        assertThat(pod.getStatus()).as("informer-cached instance must stay untouched").isNull();
        assertThat(updated.getStatus().getPhase()).isEqualTo("Running");
        assertThat(client.pods().inNamespace("ns").withName("p").get().getStatus().getPhase())
                .isEqualTo("Running");
    }

    @Test
    void rejectsNullArguments() {
        var pod = new PodBuilder().withNewMetadata().withNamespace("ns").withName("p").endMetadata().build();
        assertThrows(NullPointerException.class, () -> StatusUpdates.update(client, null, new Object()));
        assertThrows(NullPointerException.class, () -> StatusUpdates.update(client, pod, null));
    }
}
