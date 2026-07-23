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
import com.huawei.dcs.modelengine.operator.framework.webhook.cert.WebhookCertificateSecretManager;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookRegistrationConfig;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookSelfRegistration;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.RuleWithOperationsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
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
    private static final boolean DEFAULT_WEBHOOK_ENABLED = true;
    private static final boolean DEFAULT_WEBHOOK_CERT_AUTO_GENERATE = true;
    private static final String DEFAULT_WEBHOOK_CERT_SECRET_NAME = "echo-operator-webhook-ca";
    private static final String DEFAULT_WEBHOOK_CERT_DIRECTORY = "/tmp/echo-operator/certs";
    private static final String DEFAULT_WEBHOOK_CA_BUNDLE_PATH = "/etc/echo-operator/certs/ca.crt";
    private static final int DEFAULT_WEBHOOK_PORT = WebhookServer.DEFAULT_PORT;
    private static final int DEFAULT_WEBHOOK_SERVICE_PORT = 443;
    private static final String DEFAULT_WEBHOOK_SERVICE_NAME = "echo-operator";
    private static final String WEBHOOK_NAME = "echo.example.com";
    private static final String V1ALPHA1 = "example.com/v1alpha1";
    private static final String V1ALPHA2 = "example.com/v1alpha2";

    private final KubernetesClient client;
    private final Operator operator;
    private final MetricsHealthServer metricsHealthServer;
    private final OperatorConfig config;
    private final WebhookCertificatePaths certificatePaths;
    private final WebhookServer webhookServer;
    private final AdmissionHandler admissionHandler;
    private final ConversionHandler conversionHandler;
    private final WebhookSelfRegistration webhookSelfRegistration;
    private final EventRecorder eventRecorder;

    EchoOperatorMain(KubernetesClient client, Operator operator, MetricsHealthServer metricsHealthServer,
            OperatorConfig config, WebhookCertificatePaths certificatePaths, WebhookServer webhookServer,
            AdmissionHandler admissionHandler,
            ConversionHandler conversionHandler,
            WebhookSelfRegistration webhookSelfRegistration, EventRecorder eventRecorder) {
        this.client = client;
        this.operator = operator;
        this.metricsHealthServer = metricsHealthServer;
        this.config = config;
        this.certificatePaths = certificatePaths;
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
        WebhookCertificatePaths certificatePaths = null;
        AdmissionHandler admissionHandler = null;
        ConversionHandler conversionHandler = null;
        WebhookServer webhookServer = null;
        WebhookSelfRegistration webhookSelfRegistration = null;
        if (config.webhookEnabled()) {
            admissionHandler = new AdmissionHandler(client);
            admissionHandler.registerValidator(WEBHOOK_NAME, com.example.echooperator.api.v1alpha2.EchoResource.class,
                    new EchoValidatingWebhook());
            admissionHandler.registerMutator(WEBHOOK_NAME, com.example.echooperator.api.v1alpha2.EchoResource.class,
                    new EchoMutatingWebhook());
            conversionHandler = new ConversionHandler(client);
            EchoConverter echoConverter = new EchoConverter();
            KubernetesSerialization serialization = client.getKubernetesSerialization();
            conversionHandler.register(V1ALPHA1, V1ALPHA2, (desiredVersion, resource) -> ConversionResult.converted(
                    echoConverter.toV2(toV1Resource(serialization, resource))));
            conversionHandler.register(V1ALPHA2, V1ALPHA1, (desiredVersion, resource) -> ConversionResult.converted(
                    echoConverter.toV1(toV2Resource(serialization, resource))));

            certificatePaths = resolveWebhookCertificatePaths(client, config);
            webhookServer = WebhookServer.withCertWatcher(WebhookServer.DEFAULT_HOST, config.webhookPort(),
                    certificatePaths.serverCertificatePath(), certificatePaths.serverPrivateKeyPath(), certificatePaths.caPath(),
                    CertWatcher.DEFAULT_POLLING_INTERVAL);
            admissionHandler.register(webhookServer);
            conversionHandler.register(webhookServer);
            WebhookRegistrationConfig registrationConfig = WebhookRegistrationConfig.builder(config.webhookServiceName(),
                    config.webhookServiceNamespace(), certificatePaths.caPath()).withServicePort(config.webhookServicePort())
                    .withBaseName(registrationBaseName(config)).withRules(List.of(new RuleWithOperationsBuilder()
                            .withApiGroups("example.com")
                            .withApiVersions("v1alpha1", "v1alpha2")
                            .withOperations("CREATE", "UPDATE")
                            .withResources("echoresources")
                            .withScope("Namespaced")
                            .build()))
                    .build();
            webhookSelfRegistration = new WebhookSelfRegistration(client, registrationConfig);
        }
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
        EchoOperatorMain main = new EchoOperatorMain(client, operator, metricsHealthServer, config, certificatePaths,
                webhookServer, admissionHandler, conversionHandler, webhookSelfRegistration, eventRecorder);
        main.addReadinessCheck();
        return main;
    }

    private static WebhookCertificatePaths resolveWebhookCertificatePaths(KubernetesClient client, OperatorConfig config)
            throws IOException {
        if (config.webhookCertAutoGenerate()) {
            try {
                GeneratedCertificate generated = new WebhookCertificateSecretManager(client,
                        config.webhookCertSecretName(), config.operatorPodNamespace(), config.webhookServiceName(),
                        config.webhookServiceNamespace(), config.webhookCertDirectory()).resolve();
                return new WebhookCertificatePaths(generated.caPath(), generated.serverCertificatePath(),
                        generated.serverPrivateKeyPath());
            } catch (GeneralSecurityException exception) {
                throw new IOException("Failed to resolve webhook certificates", exception);
            }
        }
        Path caBundlePath = config.webhookCaBundlePath();
        Path certDirectory = caBundlePath.getParent() == null ? Path.of(".") : caBundlePath.getParent();
        return new WebhookCertificatePaths(caBundlePath, certDirectory.resolve("tls.crt"), certDirectory.resolve("tls.key"));
    }

    static String registrationBaseName(OperatorConfig config) {
        return DEFAULT_WEBHOOK_SERVICE_NAME + "." + config.operatorNamespace();
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
        if (config.webhookEnabled()) {
            webhookServer.start();
            if (config.webhookCertAutoGenerate()) {
                webhookSelfRegistration.patchConversionWebhookClientConfig("echoresources.example.com",
                        certificatePaths.caPath(), config.webhookServiceName(), config.webhookServiceNamespace(),
                        config.webhookServicePort());
            }
            webhookSelfRegistration.register(admissionHandler);
            LOGGER.info(() -> "Webhook server started on port " + webhookServer.address().getPort());
        }
        metricsHealthServer.start();
        LOGGER.info(() -> "Metrics/health server started on port " + metricsHealthServer.address().getPort());

        Runnable startOperator = () -> {
            if (!config.webhookEnabled()) {
                unregisterStaleAdmissionWebhooks();
            }
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

    private void unregisterStaleAdmissionWebhooks() {
        String baseName = registrationBaseName(config);
        WebhookRegistrationConfig cleanupConfig = WebhookRegistrationConfig.builder(config.webhookServiceName(),
                config.webhookServiceNamespace(), config.webhookCaBundlePath()).withServicePort(config.webhookServicePort())
                .withBaseName(baseName).build();
        new WebhookSelfRegistration(client, cleanupConfig).unregisterAdmissionWebhooks(baseName,
                List.of(WEBHOOK_NAME), List.of(WEBHOOK_NAME));
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
        if (webhookServer != null) {
            try {
                webhookServer.stop();
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Error stopping webhook server", exception);
            }
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
        return loadConfig(System.getenv(), loadApplicationProperties());
    }

    static OperatorConfig loadConfig(Map<String, String> env, Properties defaults) {
        String namespace = resolveConfig("OPERATOR_NAMESPACE", env, defaults, "operator.namespace", DEFAULT_NAMESPACE);
        String operatorPodNamespace = resolveRequiredConfig("OPERATOR_POD_NAMESPACE", env, defaults,
                "operator.pod-namespace", namespace);
        String metricsPortValue = resolveConfig("METRICS_PORT", env, defaults, "metrics.port", String.valueOf(DEFAULT_METRICS_PORT));
        String leaderElectionEnabledValue = resolveConfig("LEADER_ELECTION_ENABLED", env, defaults, "leader.election.enabled",
                String.valueOf(DEFAULT_LEADER_ELECTION_ENABLED));
        String leaderElectionNamespace = resolveConfig("LEADER_ELECTION_NAMESPACE", env, defaults, "leader.election.namespace", namespace);
        String leaderElectionLockName = resolveConfig("LEADER_ELECTION_LOCK_NAME", env, defaults, "leader.election.lock.name",
                DEFAULT_LEADER_ELECTION_LOCK_NAME);
        String webhookCaBundlePath = resolveConfig("WEBHOOK_CA_BUNDLE_PATH", env, defaults, "webhook.ca.bundle.path",
                DEFAULT_WEBHOOK_CA_BUNDLE_PATH);
        boolean webhookEnabled = resolveBooleanConfig("WEBHOOK_ENABLED", env, defaults, "webhook.enabled",
                DEFAULT_WEBHOOK_ENABLED);
        boolean webhookCertAutoGenerate = resolveBooleanConfig("WEBHOOK_CERT_AUTO_GENERATE", env, defaults,
                "webhook.cert.auto-generate", DEFAULT_WEBHOOK_CERT_AUTO_GENERATE);
        String webhookCertSecretName = resolveRequiredConfig("WEBHOOK_CERT_SECRET_NAME", env, defaults,
                "webhook.cert.secret-name", DEFAULT_WEBHOOK_CERT_SECRET_NAME);
        String webhookServiceName = resolveRequiredConfig("WEBHOOK_SERVICE_NAME", env, defaults,
                "webhook.service.name", DEFAULT_WEBHOOK_SERVICE_NAME);
        String webhookServiceNamespace = resolveRequiredConfig("WEBHOOK_SERVICE_NAMESPACE", env, defaults,
                "webhook.service.namespace", operatorPodNamespace);
        String webhookCertDirectory = resolveConfig("WEBHOOK_CERT_DIRECTORY", env, defaults, "webhook.cert.directory",
                DEFAULT_WEBHOOK_CERT_DIRECTORY);
        String webhookPortValue = resolveConfig("WEBHOOK_PORT", env, defaults, "webhook.port", String.valueOf(DEFAULT_WEBHOOK_PORT));
        String webhookServicePortValue = resolveConfig("WEBHOOK_SERVICE_PORT", env, defaults, "webhook.service.port",
                String.valueOf(DEFAULT_WEBHOOK_SERVICE_PORT));

        int metricsPort;
        try {
            metricsPort = Integer.parseInt(metricsPortValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("METRICS_PORT must be an integer: " + metricsPortValue);
        }
        boolean leaderElectionEnabled = Boolean.parseBoolean(leaderElectionEnabledValue);
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

        return new OperatorConfig(namespace, operatorPodNamespace, metricsPort, leaderElectionEnabled,
                leaderElectionNamespace, leaderElectionLockName, Path.of(webhookCaBundlePath), webhookEnabled,
                webhookCertAutoGenerate, webhookCertSecretName, webhookServiceName, webhookServiceNamespace,
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

    private static String resolveConfig(String envVar, Map<String, String> env, Properties defaults, String propertyKey,
            String fallback) {
        String envValue = env.get(envVar);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        String propertyValue = defaults.getProperty(propertyKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        return fallback;
    }

    private static String resolveRequiredConfig(String envVar, Map<String, String> env, Properties defaults,
            String propertyKey, String fallback) {
        String envValue = env.get(envVar);
        if (envValue != null) {
            if (envValue.isBlank()) {
                throw new IllegalArgumentException(envVar + " must not be blank");
            }
            return envValue;
        }
        String propertyValue = defaults.getProperty(propertyKey);
        if (propertyValue != null) {
            if (propertyValue.isBlank()) {
                throw new IllegalArgumentException(propertyKey + " must not be blank");
            }
            return propertyValue;
        }
        return fallback;
    }

    private static boolean resolveBooleanConfig(String envVar, Map<String, String> env, Properties defaults,
            String propertyKey, boolean fallback) {
        String envValue = env.get(envVar);
        if (envValue != null && !envValue.isBlank()) {
            return parseBooleanConfig(envVar, envValue);
        }
        String propertyValue = defaults.getProperty(propertyKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return parseBooleanConfig(propertyKey, propertyValue);
        }
        return fallback;
    }

    private static boolean parseBooleanConfig(String configName, String value) {
        String trimmedValue = value.trim();
        if ("true".equalsIgnoreCase(trimmedValue)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmedValue)) {
            return false;
        }
        throw new IllegalArgumentException(configName + " must be true or false: " + value);
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

    record OperatorConfig(String operatorNamespace, String operatorPodNamespace, int metricsPort,
            boolean leaderElectionEnabled, String leaderElectionNamespace, String leaderElectionLockName,
            Path webhookCaBundlePath, boolean webhookEnabled, boolean webhookCertAutoGenerate,
            String webhookCertSecretName, String webhookServiceName, String webhookServiceNamespace,
            Path webhookCertDirectory, int webhookPort, int webhookServicePort) {

        OperatorConfig(String operatorNamespace, int metricsPort, boolean leaderElectionEnabled,
                String leaderElectionNamespace, String leaderElectionLockName, Path webhookCaBundlePath,
                boolean webhookCertAutoGenerate, Path webhookCertDirectory, int webhookPort, int webhookServicePort) {
            this(operatorNamespace, operatorNamespace, metricsPort, leaderElectionEnabled, leaderElectionNamespace,
                    leaderElectionLockName, webhookCaBundlePath, DEFAULT_WEBHOOK_ENABLED, webhookCertAutoGenerate,
                    DEFAULT_WEBHOOK_CERT_SECRET_NAME, DEFAULT_WEBHOOK_SERVICE_NAME, operatorNamespace,
                    webhookCertDirectory, webhookPort, webhookServicePort);
        }
    }

    private record WebhookCertificatePaths(Path caPath, Path serverCertificatePath, Path serverPrivateKeyPath) {
    }
}
