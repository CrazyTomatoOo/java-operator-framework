package com.huawei.dcs.modelengine.operator.framework.webhook.admission;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReviewBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionHandlerTest {
    private final KubernetesSerialization serialization = new KubernetesSerialization();

    @Test
    void validatingWebhookRejectsInvalidResource() throws Exception {
        AdmissionHandler handler = new AdmissionHandler(serialization);
        handler.registerValidator("pods", Pod.class, (request, pod) -> {
            if (!pod.getMetadata().getLabels().containsKey("app")) {
                return AdmissionResult.denied("pod must have app label");
            }
            return AdmissionResult.allowed();
        });
        Pod pod = new PodBuilder().withNewMetadata().withName("invalid").endMetadata().build();

        TestExchange exchange = TestExchange.post("/validate/pods", admissionReview("validation-uid", pod));
        handler.validatingHandler("pods").handle(exchange);

        AdmissionReview response = response(exchange);
        assertEquals(200, exchange.statusCode());
        assertEquals("validation-uid", response.getResponse().getUid());
        assertFalse(response.getResponse().getAllowed());
        assertEquals("pod must have app label", response.getResponse().getStatus().getMessage());
    }

    @Test
    void mutatingWebhookReturnsBase64JsonPatch() throws Exception {
        AdmissionHandler handler = new AdmissionHandler(serialization);
        String patch = "[{\"op\":\"add\",\"path\":\"/metadata/labels/mutated\",\"value\":\"true\"}]";
        handler.registerMutator("pods", Pod.class, (request, pod) -> AdmissionResult.jsonPatch(patch));
        Pod pod = new PodBuilder().withNewMetadata().withName("valid").endMetadata().build();

        TestExchange exchange = TestExchange.post("/mutate/pods", admissionReview("mutation-uid", pod));
        handler.mutatingHandler("pods").handle(exchange);

        AdmissionReview response = response(exchange);
        assertEquals("mutation-uid", response.getResponse().getUid());
        assertTrue(response.getResponse().getAllowed());
        assertEquals("JSONPatch", response.getResponse().getPatchType());
        assertEquals(patch, new String(Base64.getDecoder().decode(response.getResponse().getPatch()), StandardCharsets.UTF_8));
    }

    @Test
    void unknownPathReturnsDeniedResponse() throws Exception {
        AdmissionHandler handler = new AdmissionHandler(serialization);
        Pod pod = new PodBuilder().withNewMetadata().withName("unknown").endMetadata().build();

        TestExchange exchange = TestExchange.post("/validate/missing", admissionReview("unknown-uid", pod));
        handler.validatingHandler().handle(exchange);

        AdmissionReview response = response(exchange);
        assertEquals("unknown-uid", response.getResponse().getUid());
        assertFalse(response.getResponse().getAllowed());
        assertEquals("No admission validator registered for path 'missing'", response.getResponse().getStatus().getMessage());
    }

    private String admissionReview(String uid, Pod pod) {
        AdmissionRequest request = new AdmissionRequest();
        request.setUid(uid);
        request.setObject(pod);
        return serialization.asJson(new AdmissionReviewBuilder()
                .withApiVersion("admission.k8s.io/v1")
                .withKind("AdmissionReview")
                .withRequest(request)
                .build());
    }

    private AdmissionReview response(TestExchange exchange) {
        return serialization.unmarshal(exchange.responseBody(), AdmissionReview.class);
    }

    private static final class TestExchange extends HttpExchange {
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayInputStream requestBody;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int statusCode;

        private TestExchange(String path, String body) {
            this.uri = URI.create(path);
            this.requestBody = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        private static TestExchange post(String path, String body) {
            return new TestExchange(path, body);
        }

        private int statusCode() {
            return statusCode;
        }

        private String responseBody() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return "POST";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int responseCode, long responseLength) {
            this.statusCode = responseCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public int getResponseCode() {
            return statusCode;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
