package com.example.echooperator;

import com.example.echooperator.api.v1alpha2.EchoResource;
import com.example.echooperator.api.v1alpha2.EchoSpec;
import com.example.echooperator.api.v1alpha2.EchoStatus;
import com.example.echooperator.converter.EchoConverter;
import com.example.echooperator.controller.EchoReconciler;
import com.example.echooperator.webhook.EchoMutatingWebhook;
import com.example.echooperator.webhook.EchoValidatingWebhook;
import com.huawei.dcs.modelengine.operator.framework.Operator;
import com.huawei.dcs.modelengine.operator.framework.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.leader.LeaderElectionManager;
import com.huawei.dcs.modelengine.operator.framework.event.EventRecorder;
import com.huawei.dcs.modelengine.operator.framework.event.EventSubscriber;
import com.huawei.dcs.modelengine.operator.framework.metrics.MetricsHealthServer;
import com.huawei.dcs.modelengine.operator.framework.source.Mappers;
import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.cert.CertWatcher;
import com.huawei.dcs.modelengine.operator.framework.webhook.cert.GeneratedCertificate;
import com.huawei.dcs.modelengine.operator.framework.webhook.cert.WebhookCertificateGenerator;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookRegistrationConfig;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookSelfRegistration;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the Echo Operator.
 */
public final class EchoOperatorMain {

    private static final Logger LOGGER = Logger.getLogger(EchoOperatorMain.class.getName());

    private static final String CONFIG_FILE = "/application.properties";

    private static final String DEFAULT_NAMESPACE = "default";
    private static final int DEFAULT_METRICS_PORT = MetricsHealthServer.DEFAULT_PORT;
    private static final boolean DEFAULT_LEADER_ELECTION_ENABLED = false;
    private static final String DEFAULT_LEADER_ELECTION_LOCK_NAME = "echo-operator-lock";
    private static final boolean DEFAULT_WEBHOOK_CERT_AUTO_GENERATE = true;
    private static final String DEFAULT_WEBHOOK_CERT_DIRECTORY = "/tmp/echo-operator/certs";
    private static final String DEFAULT_WEBHOOK_CA_BUNDLE_PATH = "/etc/echo-operator/certs/ca.crt";
    private static final int DEFAULT_WEBHOOK_PORT = WebhookServer.DEFAULT_PORT;
    private static final int DEFAULT_WEBHOOK_SERVICE_PORT = 443;
    private static final String WEBHOOK_SERVICE_NAME = "echo-operator";
    private static final String WEBHOOK_NAME = "echo.example.com";
    private static final String V1ALPHA1 = "example.com/v1alpha1";
    private static final String V1ALPHA2 = "example.com/v1alpha2";

    private final KubernetesClient client;
    private final Operator operator;
    private final MetricsHealthServer metricsHealthServer;
    private final OperatorConfig config;
    private final WebhookServer webhookServer;
    private final AdmissionHandler admissionHandler;
    private final ConversionHandler conversionHandler;
    private final WebhookSelfRegistration webhookSelfRegistration;
    private final EventRecorder eventRecorder;

    EchoOperatorMain(KubernetesClient client, Operator operator, MetricsHealthServer metricsHealthServer,
            OperatorConfig config, WebhookServer webhookServer, AdmissionHandler admissionHandler,
            ConversionHandler conversionHandler,
            WebhookSelfRegistration webhookSelfRegistration, EventRecorder eventRecorder) {
        this.client = client;
        this.operator = operator;
        this.metricsHealthServer = metricsHealthServer;
        this.config = config;
        this.webhookServer = webhookServer;
        this.admissionHandler = admissionHandler;
        this.conversionHandler = conversionHandler;
        this.webhookSelfRegistration = webhookSelfRegistration;
        this.eventRecorder = eventRecorder;
    }

    public static void main(String[] args) {
        OperatorConfig config = loadConfig();
        KubernetesClient client = new KubernetesClientBuilder().build();
        try {
            EchoOperatorMain operatorMain = create(client, config);
            operatorMain.start();
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Failed to start Echo Operator", exception);
            client.close();
            throw new RuntimeException("Failed to start Echo Operator", exception);
        }
    }

    static EchoOperatorMain create(KubernetesClient client, OperatorConfig config) throws IOException {
        Operator operator = new Operator(client).withNamespace(config.operatorNamespace());
        MetricsHealthServer metricsHealthServer = new MetricsHealthServer(config.metricsPort());
        AdmissionHandler admissionHandler = new AdmissionHandler(client);
        admissionHandler.registerValidator(WEBHOOK_NAME, com.example.echooperator.api.v1alpha2.EchoResource.class, new EchoValidatingWebhook());
        admissionHandler.registerMutator(WEBHOOK_NAME, com.example.echooperator.api.v1alpha2.EchoResource.class, new EchoMutatingWebhook());
        ConversionHandler conversionHandler = new ConversionHandler(client);
        EchoConverter echoConverter = new EchoConverter();
        KubernetesSerialization serialization = client.getKubernetesSerialization();
        conversionHandler.register(V1ALPHA1, V1ALPHA2, (desiredVersion, resource) -> ConversionResult.converted(
                echoConverter.toV2(toV1Resource(serialization, resource))));
        conversionHandler.register(V1ALPHA2, V1ALPHA1, (desiredVersion, resource) -> ConversionResult.converted(
                echoConverter.toV1(toV2Resource(serialization, resource))));

        WebhookCertificatePaths certificatePaths = resolveWebhookCertificatePaths(config);
        WebhookServer webhookServer = WebhookServer.withCertWatcher(WebhookServer.DEFAULT_HOST, config.webhookPort(),
                certificatePaths.serverCertificatePath(), certificatePaths.serverPrivateKeyPath(), certificatePaths.caPath(),
                CertWatcher.DEFAULT_POLLING_INTERVAL);
        admissionHandler.register(webhookServer);
        conversionHandler.register(webhookServer);
        WebhookRegistrationConfig registrationConfig = WebhookRegistrationConfig.builder(WEBHOOK_SERVICE_NAME,
                config.operatorNamespace(), certificatePaths.caPath()).withServicePort(config.webhookServicePort()).build();
        WebhookSelfRegistration webhookSelfRegistration = new WebhookSelfRegistration(client, registrationConfig);
        EventRecorder eventRecorder = new EventRecorder(client, "echo-operator");

        // Example (commented): register the same reconciler with a secondary ConfigMap watch.
        // A ConfigMap labelled "echo-name: <echo-resource-name>" would trigger reconciliation
        // for the matching EchoResource. Keeping this commented preserves the simple default path.
        // operator.register(ControllerBuilder.forResource(EchoResource.class)
        //         .withReconciler(new EchoReconciler(client, metricsHealthServer.metricsRegistry()))
        //         .watches("configmaps", ConfigMap.class, Mappers.byLabel("echo-name"))
        //         .build());

        // Example (commented): watch Kubernetes Events for this custom resource.
        // Do not enable this with this reconciler: it emits Events for EchoResource, so
        // EventSubscriber.forInvolvedObject(EchoResource.class) can trigger an infinite
        // reconciliation loop from the reconciler's own Events.
        // operator.register(ControllerBuilder.forResource(EchoResource.class)
        //         .withReconciler(new EchoReconciler(client, metricsHealthServer.metricsRegistry(), eventRecorder))
        //         .withEventSubscriber(EventSubscriber.forInvolvedObject(EchoResource.class))
        //         .build());

        operator.register(com.example.echooperator.api.v1alpha2.EchoResource.class,
                new EchoReconciler(client, metricsHealthServer.metricsRegistry(), eventRecorder));
        EchoOperatorMain main = new EchoOperatorMain(client, operator, metricsHealthServer, config, webhookServer,
                admissionHandler, conversionHandler, webhookSelfRegistration, eventRecorder);
        main.addReadinessCheck();
        return main;
    }

    private static WebhookCertificatePaths resolveWebhookCertificatePaths(OperatorConfig config) throws IOException {
        if (config.webhookCertAutoGenerate()) {
            Files.createDirectories(config.webhookCertDirectory());
            GeneratedCertificate generated = generateWebhookCertificate(config);
            return new WebhookCertificatePaths(generated.caPath(), generated.serverCertificatePath(),
                    generated.serverPrivateKeyPath());
        }
        Path caBundlePath = config.webhookCaBundlePath();
        Path certDirectory = caBundlePath.getParent() == null ? Path.of(".") : caBundlePath.getParent();
        return new WebhookCertificatePaths(caBundlePath, certDirectory.resolve("tls.crt"), certDirectory.resolve("tls.key"));
    }

    private static GeneratedCertificate generateWebhookCertificate(OperatorConfig config) throws IOException {
        try {
            return WebhookCertificateGenerator.builder(WEBHOOK_SERVICE_NAME, config.operatorNamespace())
                    .build()
                    .generate(config.webhookCertDirectory());
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to generate webhook certificates", exception);
        }
    }

    private static com.example.echooperator.api.v1alpha1.EchoResource toV1Resource(KubernetesSerialization serialization, HasMetadata resource) {
        if (resource instanceof com.example.echooperator.api.v1alpha1.EchoResource echoResource) {
            return echoResource;
        }
        return serialization.convertValue(resource, com.example.echooperator.api.v1alpha1.EchoResource.class);
    }

    private static com.example.echooperator.api.v1alpha2.EchoResource toV2Resource(KubernetesSerialization serialization, HasMetadata resource) {
        if (resource instanceof EchoResource echoResource) {
            return echoResource;
        }
        if (resource instanceof GenericKubernetesResource genericResource) {
            return serialization.convertValue(genericResource, EchoResource.class);
        }
        return serialization.convertValue(resource, EchoResource.class);
    }

    void start() {
        webhookServer.start();
        webhookSelfRegistration.register(admissionHandler);
        LOGGER.info(() -> "Webhook server started on port " + webhookServer.address().getPort());
        metricsHealthServer.start();
        LOGGER.info(() -> "Metrics/health server started on port " + metricsHealthServer.address().getPort());

        Runnable startOperator = () -> {
            operator.start();
            LOGGER.info("Echo Operator started");
        };

        addShutdownHook();

        if (config.leaderElectionEnabled()) {
            LeaderElectionManager leaderElection = new LeaderElectionManager(client, config.leaderElectionLockName(),
                    config.leaderElectionNamespace());
            leaderElection.run(startOperator);
        } else {
            startOperator.run();
        }
    }

    private void addReadinessCheck() {
        metricsHealthServer.addReadinessCheck(() -> {
            var sources = operator.eventSources();
            return !sources.isEmpty() && sources.stream().allMatch(source -> source.getInformer().hasSynced());
        });
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "echo-operator-shutdown-hook"));
    }

    void stop() {
        LOGGER.info("Shutting down Echo Operator");
        try {
            webhookServer.stop();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Error stopping webhook server", exception);
        }
        // Operator.stop() closes the shared Kubernetes client, so the recorder must
        // flush pending counts before that.
        try {
            eventRecorder.close();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Error closing event recorder", exception);
        }
        try {
            operator.stop();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Error stopping operator", exception);
        }
        try {
            metricsHealthServer.close();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Error stopping metrics/health server", exception);
        }
        try {
            client.close();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Error closing Kubernetes client", exception);
        }
    }

    static OperatorConfig loadConfig() {
        Properties defaults = loadApplicationProperties();
        String namespace = resolveConfig("OPERATOR_NAMESPACE", defaults, "operator.namespace", DEFAULT_NAMESPACE);
        String metricsPortValue = resolveConfig("METRICS_PORT", defaults, "metrics.port", String.valueOf(DEFAULT_METRICS_PORT));
        String leaderElectionEnabledValue = resolveConfig("LEADER_ELECTION_ENABLED", defaults, "leader.election.enabled",
                String.valueOf(DEFAULT_LEADER_ELECTION_ENABLED));
        String leaderElectionNamespace = resolveConfig("LEADER_ELECTION_NAMESPACE", defaults, "leader.election.namespace", namespace);
        String leaderElectionLockName = resolveConfig("LEADER_ELECTION_LOCK_NAME", defaults, "leader.election.lock.name",
                DEFAULT_LEADER_ELECTION_LOCK_NAME);
        String webhookCaBundlePath = resolveConfig("WEBHOOK_CA_BUNDLE_PATH", defaults, "webhook.ca.bundle.path",
                DEFAULT_WEBHOOK_CA_BUNDLE_PATH);
        String webhookCertAutoGenerateValue = resolveConfig("WEBHOOK_CERT_AUTO_GENERATE", defaults,
                "webhook.cert.auto-generate", String.valueOf(DEFAULT_WEBHOOK_CERT_AUTO_GENERATE));
        String webhookCertDirectory = resolveConfig("WEBHOOK_CERT_DIRECTORY", defaults, "webhook.cert.directory",
                DEFAULT_WEBHOOK_CERT_DIRECTORY);
        String webhookPortValue = resolveConfig("WEBHOOK_PORT", defaults, "webhook.port", String.valueOf(DEFAULT_WEBHOOK_PORT));
        String webhookServicePortValue = resolveConfig("WEBHOOK_SERVICE_PORT", defaults, "webhook.service.port",
                String.valueOf(DEFAULT_WEBHOOK_SERVICE_PORT));

        int metricsPort;
        try {
            metricsPort = Integer.parseInt(metricsPortValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("METRICS_PORT must be an integer: " + metricsPortValue);
        }
        boolean leaderElectionEnabled = Boolean.parseBoolean(leaderElectionEnabledValue);
        boolean webhookCertAutoGenerate = Boolean.parseBoolean(webhookCertAutoGenerateValue);
        int webhookPort;
        try {
            webhookPort = Integer.parseInt(webhookPortValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("WEBHOOK_PORT must be an integer: " + webhookPortValue);
        }
        int webhookServicePort;
        try {
            webhookServicePort = Integer.parseInt(webhookServicePortValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("WEBHOOK_SERVICE_PORT must be an integer: " + webhookServicePortValue);
        }

        return new OperatorConfig(namespace, metricsPort, leaderElectionEnabled, leaderElectionNamespace,
                leaderElectionLockName, Path.of(webhookCaBundlePath), webhookCertAutoGenerate,
                Path.of(webhookCertDirectory), webhookPort, webhookServicePort);
    }

    private static String resolveConfig(String envVar, Properties defaults, String propertyKey, String fallback) {
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        String propertyValue = defaults.getProperty(propertyKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        return fallback;
    }

    private static Properties loadApplicationProperties() {
        Properties properties = new Properties();
        try (InputStream stream = EchoOperatorMain.class.getResourceAsStream(CONFIG_FILE)) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Failed to load " + CONFIG_FILE, exception);
        }
        return properties;
    }

    Operator operator() {
        return operator;
    }

    MetricsHealthServer metricsHealthServer() {
        return metricsHealthServer;
    }

    OperatorConfig config() {
        return config;
    }

    WebhookServer webhookServer() {
        return webhookServer;
    }

    AdmissionHandler admissionHandler() {
        return admissionHandler;
    }

    ConversionHandler conversionHandler() {
        return conversionHandler;
    }

    WebhookSelfRegistration webhookSelfRegistration() {
        return webhookSelfRegistration;
    }

    record OperatorConfig(String operatorNamespace, int metricsPort, boolean leaderElectionEnabled,
            String leaderElectionNamespace, String leaderElectionLockName, Path webhookCaBundlePath,
            boolean webhookCertAutoGenerate, Path webhookCertDirectory, int webhookPort, int webhookServicePort) {
    }

    private record WebhookCertificatePaths(Path caPath, Path serverCertificatePath, Path serverPrivateKeyPath) {
    }
}
