package com.example.stress;

import com.example.stress.api.v1alpha1.StressTestResource;
import com.huawei.dcs.modelengine.operator.framework.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.Operator;
import com.huawei.dcs.modelengine.operator.framework.retry.RateLimiter;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventSource;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Stress test for the operator framework's reconciliation throughput against a real
 * Kubernetes cluster (current kubeconfig context). Installs a synthetic CRD, starts
 * an {@link Operator} with a measuring reconciler, then drives a create burst followed
 * by a steady mixed-hot-key update load, reporting throughput and end-to-end latency.
 */
public final class StressTestMain {

    private static final String CRD_NAME = "stresstestresources.stress.example.com";

    public static void main(String[] args) throws Exception {
        StressConfig config = StressConfig.parse(args);
        if (config == null) {
            return;
        }
        System.out.println("Starting reconciliation stress test: " + config);
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            ensureNamespace(client, config);
            installCrd(client);
            StressMetrics metrics = new StressMetrics();
            // Operator.stop() closes the client it was given, so it gets its own instance.
            KubernetesClient operatorClient = new KubernetesClientBuilder().build();
            Operator operator = new Operator(operatorClient)
                    .withNamespace(config.namespace)
                    .withWorkerThreads(config.workerThreads)
                    .withRateLimiter(new RateLimiter(Duration.ofMillis(config.rateLimitMs)));
            StressReconciler reconciler = new StressReconciler(client, config, metrics);
            if (config.generationFilter) {
                operator.register(ControllerBuilder.forResource(StressTestResource.class)
                        .withReconciler(reconciler)
                        .withGenerationChangeFilter()
                        .build());
            } else {
                operator.register(StressTestResource.class, reconciler);
            }
            LoadGenerator generator = new LoadGenerator(client, config, metrics);
            Reporter reporter = new Reporter(config, metrics);
            Runtime.getRuntime().addShutdownHook(new Thread(reporter::printSummary, "stress-summary-hook"));
            try {
                operator.start();
                awaitInformerSync(operator);
                reporter.start();
                runCreatePhase(generator, metrics, config);
                generator.runSteady();
            } finally {
                reporter.close();
                operator.stop();
                cleanup(client, generator, config);
            }
        }
    }

    private static void runCreatePhase(LoadGenerator generator, StressMetrics metrics, StressConfig config) {
        generator.createAll();
        long created = metrics.writeOk.sum();
        long drainStartMs = System.currentTimeMillis();
        boolean drained = generator.awaitReconciles(created);
        double drainSec = (System.currentTimeMillis() - drainStartMs) / 1000.0;
        System.out.printf("Phase A: reconcile drain %s in %.1fs (%.0f reconciles/s)%n",
                drained ? "complete" : "TIMED OUT", drainSec, drainSec > 0 ? created / drainSec : 0);
    }

    private static void ensureNamespace(KubernetesClient client, StressConfig config) throws InterruptedException {
        if (client.namespaces().withName(config.namespace).get() != null) {
            return;
        }
        System.out.println("Creating namespace " + config.namespace);
        client.namespaces().resource(new NamespaceBuilder()
                .withNewMetadata().withName(config.namespace).endMetadata()
                .build()).create();
        long deadlineMs = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadlineMs) {
            var namespace = client.namespaces().withName(config.namespace).get();
            if (namespace != null && namespace.getStatus() != null
                    && "Active".equals(namespace.getStatus().getPhase())) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Namespace " + config.namespace + " not active within 30s");
    }

    private static void installCrd(KubernetesClient client) throws InterruptedException {
        try (InputStream in = StressTestMain.class.getResourceAsStream("/crd/stresstest-crd.yaml")) {
            Objects.requireNonNull(in, "crd/stresstest-crd.yaml missing from classpath");
            client.load(in).createOrReplace();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to read CRD resource", exception);
        }
        long deadlineMs = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadlineMs) {
            CustomResourceDefinition crd = client.apiextensions().v1().customResourceDefinitions()
                    .withName(CRD_NAME).get();
            if (crd != null && crd.getStatus() != null && crd.getStatus().getConditions() != null
                    && crd.getStatus().getConditions().stream()
                    .anyMatch(condition -> "Established".equals(condition.getType())
                            && "True".equals(condition.getStatus()))) {
                System.out.println("CRD " + CRD_NAME + " established");
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("CRD " + CRD_NAME + " not established within 60s");
    }

    private static void awaitInformerSync(Operator operator) throws InterruptedException {
        long deadlineMs = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadlineMs) {
            List<ResourceEventSource<?>> sources = operator.eventSources();
            if (!sources.isEmpty() && sources.stream().allMatch(ResourceEventSource::hasSynced)) {
                System.out.println("Informers synced");
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Informers did not sync within 60s");
    }

    private static void cleanup(KubernetesClient client, LoadGenerator generator, StressConfig config) {
        try {
            if (config.cleanupNamespace) {
                System.out.println("Deleting namespace " + config.namespace);
                client.namespaces().withName(config.namespace).delete();
            } else {
                generator.deleteAllQuietly();
            }
            if (config.cleanupCrd) {
                client.apiextensions().v1().customResourceDefinitions().withName(CRD_NAME).delete();
            }
        } catch (KubernetesClientException exception) {
            System.err.println("Cleanup failed: " + exception.getMessage());
        }
    }
}
