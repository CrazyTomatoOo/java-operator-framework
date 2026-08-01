package com.huawei.dcs.modelengine.operator.framework.internal.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.MutationResult;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.authentication.UserInfoBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdmissionWebhookControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private GenericApplicationContext context;
    private WebhookCallbackRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new GenericApplicationContext();
        context.registerBean("allow", AllowValidator.class, AllowValidator::new);
        context.registerBean("deny", DenyValidator.class, DenyValidator::new);
        context.registerBean("throwing", ThrowingValidator.class, ThrowingValidator::new);
        context.registerBean("mutate", DataMutator.class, DataMutator::new);
        context.registerBean("changeidentity", IdentityMutator.class, IdentityMutator::new);
        context.registerBean("nullmutator", NullMutator.class, NullMutator::new);
        context.refresh();
        registry = new WebhookCallbackRegistry(context.getBeanFactory());
        mvc = MockMvcBuilders.standaloneSetup(
                new AdmissionWebhookController(registry, mapper, new OperatorFrameworkMetrics(meters))).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void validatesAndPreservesUidAndContext() throws Exception {
        var result = mvc.perform(admission("/operator-framework/webhooks/validate/allow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.uid").value("request-1"))
                .andExpect(jsonPath("$.response.allowed").value(true))
                .andReturn();

        var callback = context.getBean(AllowValidator.class);
        assertThat(callback.context.get().uid()).isEqualTo("request-1");
        assertThat(callback.context.get().user().username()).isEqualTo("alice");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("sensitive callback detail");
    }

    @Test
    void returnsDenialAndSafeCallbackFailure() throws Exception {
        mvc.perform(admission("/operator-framework/webhooks/validate/deny"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.allowed").value(false))
                .andExpect(jsonPath("$.response.status.message").value("invalid spec"));
        mvc.perform(admission("/operator-framework/webhooks/validate/throwing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.allowed").value(false))
                .andExpect(jsonPath("$.response.status.message").value("webhook callback failed"));

        assertThat(registry.lastFailure()).contains("validator callback 'throwing' failed");
    }

    @Test
    void emitsBase64Rfc6902Patch() throws Exception {
        var result = mvc.perform(admission("/operator-framework/webhooks/mutate/mutate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.uid").value("request-1"))
                .andExpect(jsonPath("$.response.allowed").value(true))
                .andExpect(jsonPath("$.response.patchType").value("JSONPatch"))
                .andReturn();

        var review = mapper.readTree(result.getResponse().getContentAsByteArray());
        var patch = Base64.getDecoder().decode(review.at("/response/patch").asText());
        var operations = mapper.readTree(new String(patch, StandardCharsets.UTF_8));
        assertThat(operations.toString()).contains("/data/added").contains("yes");
    }

    @Test
    void preservesUnknownFieldsAndRejectsMutatedIdentity() throws Exception {
        var unknown = review();
        var raw = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.valueToTree(unknown.getRequest().getObject());
        raw.put("futureField", "preserved");
        unknown.getRequest().setObject(raw);

        var result = mvc.perform(post("/operator-framework/webhooks/mutate/mutate")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(unknown)))
                .andExpect(status().isOk()).andReturn();
        var response = mapper.readTree(result.getResponse().getContentAsByteArray());
        var patch = Base64.getDecoder().decode(response.at("/response/patch").asText());
        assertThat(new String(patch, StandardCharsets.UTF_8)).doesNotContain("futureField");

        mvc.perform(admission("/operator-framework/webhooks/mutate/changeidentity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.allowed").value(false))
                .andExpect(jsonPath("$.response.status.message")
                        .value("resource type does not match callback"));
    }

    @Test
    void deniesResourceTypeMismatchBeforeInvokingCallback() throws Exception {
        var mismatch = review();
        mismatch.getRequest().setObject(new SecretBuilder()
                .withApiVersion("v1").withKind("Secret")
                .withNewMetadata().withNamespace("default").withName("sample").endMetadata().build());

        mvc.perform(post("/operator-framework/webhooks/validate/allow")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(mismatch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.allowed").value(false))
                .andExpect(jsonPath("$.response.status.message")
                        .value("resource type does not match callback"));
        assertThat(context.getBean(AllowValidator.class).context).hasValue(null);
    }

    @Test
    void rejectsUnknownAndMalformedAndContainsNullResult() throws Exception {
        mvc.perform(admission("/operator-framework/webhooks/validate/missing"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/operator-framework/webhooks/validate/allow")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(admission("/operator-framework/webhooks/mutate/nullmutator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status.message").value("webhook callback failed"));
    }

    @Test
    void recordsCallbackMetricsPerOutcome() throws Exception {
        mvc.perform(admission("/operator-framework/webhooks/validate/allow")).andExpect(status().isOk());
        mvc.perform(admission("/operator-framework/webhooks/validate/deny")).andExpect(status().isOk());
        mvc.perform(admission("/operator-framework/webhooks/validate/throwing")).andExpect(status().isOk());
        mvc.perform(admission("/operator-framework/webhooks/mutate/mutate")).andExpect(status().isOk());
        mvc.perform(admission("/operator-framework/webhooks/mutate/changeidentity")).andExpect(status().isOk());
        mvc.perform(admission("/operator-framework/webhooks/mutate/nullmutator")).andExpect(status().isOk());

        assertThat(callbackCount("validator", "allow", "allowed")).isEqualTo(1.0);
        assertThat(callbackCount("validator", "deny", "denied")).isEqualTo(1.0);
        assertThat(callbackCount("validator", "throwing", "error")).isEqualTo(1.0);
        assertThat(callbackCount("mutator", "mutate", "mutated")).isEqualTo(1.0);
        assertThat(callbackCount("mutator", "changeidentity", "denied")).isEqualTo(1.0);
        assertThat(callbackCount("mutator", "nullmutator", "error")).isEqualTo(1.0);
        assertThat(meters.get("operator.framework.callback.duration")
                .tags("callback.type", "validator", "bean", "allow", "outcome", "allowed")
                .timer().count()).isEqualTo(1);
    }

    private double callbackCount(String type, String bean, String outcome) {
        return meters.get("operator.framework.callback.total")
                .tags("callback.type", type, "bean", bean, "outcome", outcome).counter().count();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admission(String path)
            throws Exception {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(review()));
    }

    private AdmissionReview review() {
        var resource = new ConfigMapBuilder()
                .withApiVersion("v1").withKind("ConfigMap")
                .withNewMetadata().withNamespace("default").withName("sample").withUid("resource-1").endMetadata()
                .addToData("existing", "value").build();
        var request = new AdmissionRequest();
        request.setUid("request-1");
        request.setOperation("UPDATE");
        request.setDryRun(true);
        request.setObject(resource);
        request.setUserInfo(new UserInfoBuilder().withUsername("alice").withUid("user-1")
                .withGroups("developers").build());
        var review = new AdmissionReview();
        review.setApiVersion("admission.k8s.io/v1");
        review.setKind("AdmissionReview");
        review.setRequest(request);
        return review;
    }

    static final class AllowValidator implements AdmissionValidator<ConfigMap> {
        private final AtomicReference<AdmissionContext> context = new AtomicReference<>();

        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext admissionContext) {
            context.set(admissionContext);
            return AdmissionDecision.allow();
        }
    }

    static final class DenyValidator implements AdmissionValidator<ConfigMap> {
        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) {
            return AdmissionDecision.deny("invalid spec");
        }
    }

    static final class ThrowingValidator implements AdmissionValidator<ConfigMap> {
        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) throws Exception {
            throw new Exception("sensitive callback detail");
        }
    }

    static final class DataMutator implements AdmissionMutator<ConfigMap> {
        @Override
        public MutationResult<ConfigMap> mutate(ConfigMap current, AdmissionContext context) {
            return MutationResult.mutated(new ConfigMapBuilder(current).addToData("added", "yes").build());
        }
    }

    static final class IdentityMutator implements AdmissionMutator<ConfigMap> {
        @Override
        public MutationResult<ConfigMap> mutate(ConfigMap current, AdmissionContext context) {
            return MutationResult.mutated(new ConfigMapBuilder(current)
                    .editMetadata().withName("different").endMetadata().build());
        }
    }

    static final class NullMutator implements AdmissionMutator<ConfigMap> {
        @Override
        public MutationResult<ConfigMap> mutate(ConfigMap current, AdmissionContext context) {
            return null;
        }
    }
}
