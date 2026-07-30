package com.huawei.dcs.modelengine.operator.framework.internal.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ResourceConverter;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionRequest;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionReview;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversionWebhookControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private GenericApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new GenericApplicationContext();
        context.registerBean("converter", VersionConverter.class, VersionConverter::new);
        context.registerBean("failure", FailingConverter.class, FailingConverter::new);
        context.registerBean("wrongversion", WrongVersionConverter.class, WrongVersionConverter::new);
        context.registerBean("wrongkind", WrongKindConverter.class, WrongKindConverter::new);
        context.registerBean("changedidentity", IdentityChangingConverter.class, IdentityChangingConverter::new);
        context.refresh();
        var registry = new WebhookCallbackRegistry(context.getBeanFactory());
        mvc = MockMvcBuilders.standaloneSetup(new ConversionWebhookController(registry, mapper)).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void convertsBatchInOrderAndPassesThroughSameVersion() throws Exception {
        var result = mvc.perform(conversion("converter", review("v2", resources())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.uid").value("conversion-1"))
                .andExpect(jsonPath("$.response.result.status").value("Success"))
                .andExpect(jsonPath("$.response.convertedObjects[0].metadata.name").value("already-v2"))
                .andExpect(jsonPath("$.response.convertedObjects[1].metadata.name").value("needs-conversion"))
                .andExpect(jsonPath("$.response.convertedObjects[1].apiVersion").value("v2"))
                .andReturn();

        var converter = context.getBean(VersionConverter.class);
        assertThat(converter.calls).hasValue(1);
        assertThat(converter.context.get()).isEqualTo(new ConversionContext("v1", "v2"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("sensitive");
    }

    @Test
    void anyFailureFailsWholeBatchWithoutPartialObjects() throws Exception {
        mvc.perform(conversion("failure", review("v2", resources())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.uid").value("conversion-1"))
                .andExpect(jsonPath("$.response.result.status").value("Failure"))
                .andExpect(jsonPath("$.response.result.message").value("webhook callback failed"))
                .andExpect(jsonPath("$.response.convertedObjects").doesNotExist());
    }

    @Test
    void failsWhenConverterReturnsWrongDesiredApiVersion() throws Exception {
        mvc.perform(conversion("wrongversion", review("v2", List.of(resource("source", "v1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.result.status").value("Failure"))
                .andExpect(jsonPath("$.response.result.message").value("webhook callback failed"))
                .andExpect(jsonPath("$.response.convertedObjects").doesNotExist());
    }

    @Test
    void failsWhenConverterChangesKind() throws Exception {
        mvc.perform(conversion("wrongkind", review("v2", List.of(resource("source", "v1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.result.status").value("Failure"))
                .andExpect(jsonPath("$.response.convertedObjects").doesNotExist());
    }

    @Test
    void failsWhenConverterChangesResourceIdentity() throws Exception {
        mvc.perform(conversion("changedidentity", review("v2", List.of(resource("source", "v1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.result.status").value("Failure"))
                .andExpect(jsonPath("$.response.convertedObjects").doesNotExist());
    }

    @Test
    void rejectsSourceTypeMismatchBeforeInvokingConverter() throws Exception {
        var source = resource("source", "v1");
        source.setKind("Secret");

        mvc.perform(conversion("converter", review("v2", List.of(source))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.result.status").value("Failure"))
                .andExpect(jsonPath("$.response.convertedObjects").doesNotExist());
        assertThat(context.getBean(VersionConverter.class).calls).hasValue(0);
    }

    @Test
    void rejectsUnknownAndMalformedReview() throws Exception {
        mvc.perform(conversion("missing", review("v2", resources())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/operator-framework/webhooks/convert/converter")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder conversion(
            String name,
            ConversionReview review) throws Exception {
        return post("/operator-framework/webhooks/convert/" + name)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(review));
    }

    private ConversionReview review(String desired, List<Object> resources) {
        var request = new ConversionRequest();
        request.setUid("conversion-1");
        request.setDesiredAPIVersion(desired);
        request.setObjects(resources);
        var review = new ConversionReview();
        review.setApiVersion("apiextensions.k8s.io/v1");
        review.setKind("ConversionReview");
        review.setRequest(request);
        return review;
    }

    private List<Object> resources() {
        return List.of(resource("already-v2", "v2"), resource("needs-conversion", "v1"));
    }

    private ConfigMap resource(String name, String version) {
        return new ConfigMapBuilder().withApiVersion(version).withKind("ConfigMap")
                .withNewMetadata().withName(name).endMetadata().build();
    }

    static final class IdentityChangingConverter implements ResourceConverter<ConfigMap> {
        @Override
        public ConversionResult<ConfigMap> convert(ConfigMap resource, ConversionContext context) {
            var converted = new ConfigMapBuilder(resource).withApiVersion(context.desiredVersion())
                    .editMetadata().withName("different").endMetadata().build();
            return ConversionResult.converted(converted);
        }
    }

    static final class VersionConverter implements ResourceConverter<ConfigMap> {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ConversionContext> context = new AtomicReference<>();

        @Override
        public ConversionResult<ConfigMap> convert(ConfigMap resource, ConversionContext conversionContext) {
            calls.incrementAndGet();
            context.set(conversionContext);
            return ConversionResult.converted(
                    new ConfigMapBuilder(resource).withApiVersion(conversionContext.desiredVersion()).build());
        }
    }

    static final class WrongVersionConverter implements ResourceConverter<ConfigMap> {
        @Override
        public ConversionResult<ConfigMap> convert(ConfigMap resource, ConversionContext context) {
            return ConversionResult.converted(new ConfigMapBuilder(resource).withApiVersion("v3").build());
        }
    }

    static final class WrongKindConverter implements ResourceConverter<ConfigMap> {
        @Override
        public ConversionResult<ConfigMap> convert(ConfigMap resource, ConversionContext context) {
            return ConversionResult.converted(new ConfigMapBuilder(resource)
                    .withApiVersion(context.desiredVersion()).withKind("Secret").build());
        }
    }

    static final class FailingConverter implements ResourceConverter<ConfigMap> {
        @Override
        public ConversionResult<ConfigMap> convert(ConfigMap resource, ConversionContext context) throws Exception {
            throw new Exception("sensitive conversion failure");
        }
    }
}
