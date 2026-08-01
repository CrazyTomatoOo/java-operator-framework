/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.echooperator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.fabric8.kubernetes.api.model.authentication.UserInfoBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end admission webhook calls against the framework-served MVC endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
class EchoWebhookEndpointTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @MockitoBean
    private KubernetesClient kubernetesClient;
    @Test
    void mutatorDefaultsMessageOverHttp() throws Exception {
        var result = mvc.perform(post("/operator-framework/webhooks/mutate/echomutator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(review(null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.uid").value("request-1"))
                .andExpect(jsonPath("$.response.allowed").value(true))
                .andExpect(jsonPath("$.response.patchType").value("JSONPatch"))
                .andReturn();

        var response = mapper.readTree(result.getResponse().getContentAsByteArray());
        var patch = Base64.getDecoder().decode(response.at("/response/patch").asText());
        assertThat(new String(patch, StandardCharsets.UTF_8))
                .contains("/data").contains("hello world");
    }

    @Test
    void validatorRejectsBlankMessageOverHttp() throws Exception {
        mvc.perform(post("/operator-framework/webhooks/validate/echovalidator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(review(" "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.allowed").value(false))
                .andExpect(jsonPath("$.response.status.message")
                        .value("data.message must not be blank on echo ConfigMaps"));
    }

    @Test
    void unknownCallbackIsRejected() throws Exception {
        mvc.perform(post("/operator-framework/webhooks/validate/unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(review("hi"))))
                .andExpect(status().isBadRequest());
    }

    private AdmissionReview review(String message) {
        var builder = new ConfigMapBuilder()
                .withApiVersion("v1").withKind("ConfigMap")
                .withNewMetadata()
                .withNamespace("default").withName("greeting").withUid("uid-1")
                .withLabels(Map.of(EchoReconciler.ENABLED_LABEL, "true"))
                .endMetadata();
        var resource = message == null ? builder.build() : builder.addToData("message", message).build();
        var request = new AdmissionRequest();
        request.setUid("request-1");
        request.setOperation("CREATE");
        request.setObject(resource);
        request.setUserInfo(new UserInfoBuilder().withUsername("alice").build());
        var review = new AdmissionReview();
        review.setApiVersion("admission.k8s.io/v1");
        review.setKind("AdmissionReview");
        review.setRequest(request);
        return review;
    }
}
