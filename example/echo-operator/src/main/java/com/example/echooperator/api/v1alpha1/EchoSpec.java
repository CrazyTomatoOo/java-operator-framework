package com.example.echooperator.api.v1alpha1;

import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Required;

public class EchoSpec {

    @Required
    public String message;

    @Default("1")
    public int replicas;

    public EchoSpec() {
    }
}
