package com.example.echooperator;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionRequest;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionReview;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EchoConversionEndpointTest {
    private static final String V1ALPHA1 = "example.com/v1alpha1";
    private static final String V1ALPHA2 = "example.com/v1alpha2";

    @TempDir
    private Path tempDir;

    private final KubernetesSerialization serialization = new KubernetesSerialization();

    @Test
    void convertEndpointConvertsEchoResourcesInBothDirectionsOverTls() throws Exception {
        TestCertificate certificate = generateCertificate();
        EchoOperatorMain.OperatorConfig config = new EchoOperatorMain.OperatorConfig("test-ns", 0, false,
                "test-ns", "echo-operator-lock", certificate.caPath(), false, tempDir.resolve("unused-certs"), 0, 443);
        EchoOperatorMain main = EchoOperatorMain.create(client(), config);

        try {
            main.webhookServer().start();

            ConversionReview toV2 = post(main, review("alpha1-uid", V1ALPHA2,
                    resource(V1ALPHA1, "echo-alpha1", null)));
            assertSuccess(toV2, "alpha1-uid");
            GenericKubernetesResource convertedV2 = convertedObject(toV2);
            assertEquals(V1ALPHA2, convertedV2.getApiVersion());
            assertEquals("echo-alpha1", convertedV2.getMetadata().getName());
            assertEquals("INFO", spec(convertedV2).get("logLevel"));

            ConversionReview toV1 = post(main, review("alpha2-uid", V1ALPHA1,
                    resource(V1ALPHA2, "echo-alpha2", "DEBUG")));
            assertSuccess(toV1, "alpha2-uid");
            GenericKubernetesResource convertedV1 = convertedObject(toV1);
            assertEquals(V1ALPHA1, convertedV1.getApiVersion());
            assertEquals("echo-alpha2", convertedV1.getMetadata().getName());
            assertFalse(spec(convertedV1).containsKey("logLevel"));
        } finally {
            main.stop();
        }
    }

    private KubernetesClient client() {
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.getKubernetesSerialization()).thenReturn(serialization);
        return client;
    }

    private ConversionReview post(EchoOperatorMain main, ConversionReview review) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(trustAllSslContext()).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:"
                        + main.webhookServer().address().getPort() + "/convert"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serialization.asJson(review)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
        return serialization.unmarshal(response.body(), ConversionReview.class);
    }

    private ConversionReview review(String uid, String desiredVersion, GenericKubernetesResource resource) {
        ConversionRequest request = new ConversionRequest();
        request.setUid(uid);
        request.setDesiredAPIVersion(desiredVersion);
        request.setObjects(List.of(resource));
        return new ConversionReviewBuilder()
                .withApiVersion("apiextensions.k8s.io/v1")
                .withKind("ConversionReview")
                .withRequest(request)
                .build();
    }

    private void assertSuccess(ConversionReview response, String uid) {
        assertNotNull(response.getResponse());
        assertEquals(uid, response.getResponse().getUid());
        assertEquals("Success", response.getResponse().getResult().getStatus());
    }

    private GenericKubernetesResource convertedObject(ConversionReview response) {
        assertNotNull(response.getResponse().getConvertedObjects());
        assertEquals(1, response.getResponse().getConvertedObjects().size());
        return serialization.convertValue(response.getResponse().getConvertedObjects().get(0),
                GenericKubernetesResource.class);
    }

    private GenericKubernetesResource resource(String apiVersion, String name, String logLevel) {
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion(apiVersion);
        resource.setKind("EchoResource");
        resource.setMetadata(new ObjectMetaBuilder().withName(name).build());
        if (logLevel == null) {
            resource.setAdditionalProperty("spec", Map.of("message", "hello", "replicas", 1));
        } else {
            resource.setAdditionalProperty("spec", Map.of("message", "hello", "replicas", 1, "logLevel", logLevel));
        }
        return resource;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec(GenericKubernetesResource resource) {
        return (Map<String, Object>) resource.getAdditionalProperties().get("spec");
    }

    private TestCertificate generateCertificate() throws IOException {
        try {
            String password = "changeit" + HexFormat.of().formatHex(new SecureRandom().generateSeed(8));
            Path keyStorePath = tempDir.resolve("webhook-" + password + ".p12");
            runKeytool(List.of("-genkeypair", "-alias", "webhook", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "7", "-storetype", "PKCS12", "-keystore", keyStorePath.toString(), "-storepass",
                    password, "-keypass", password, "-dname", "CN=localhost", "-ext",
                    "SAN=dns:localhost,ip:127.0.0.1"));

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStorePath)) {
                keyStore.load(input, password.toCharArray());
            }

            X509Certificate certificate = (X509Certificate) keyStore.getCertificate("webhook");
            Key privateKey = keyStore.getKey("webhook", password.toCharArray());
            Path certPath = tempDir.resolve("tls.crt");
            Path keyPath = tempDir.resolve("tls.key");
            Path caPath = tempDir.resolve("ca.crt");
            writePem(certPath, "CERTIFICATE", certificate.getEncoded());
            writePem(keyPath, "PRIVATE KEY", privateKey.getEncoded());
            writePem(caPath, "CERTIFICATE", certificate.getEncoded());
            return new TestCertificate(certPath, keyPath, caPath);
        } catch (Exception exception) {
            throw new IOException("Failed to generate test certificate", exception);
        }
    }

    private static void runKeytool(List<String> arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(command(arguments));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("keytool failed with exit code " + exitCode + ": " + output);
        }
    }

    private static List<String> command(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", keytoolName()).toString());
        command.addAll(arguments);
        return command;
    }

    private static String keytoolName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
    }

    private static void writePem(Path path, String label, byte[] der) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        Files.writeString(path, "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n",
                StandardCharsets.US_ASCII);
    }

    private static SSLContext trustAllSslContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return context;
    }

    private record TestCertificate(Path certPath, Path keyPath, Path caPath) {
    }
}
