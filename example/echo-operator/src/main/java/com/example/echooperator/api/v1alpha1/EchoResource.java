package com.example.echooperator.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("example.com")
@Version(value = "v1alpha1", storage = false, served = true, deprecated = true)
@Kind("EchoResource")
@Plural("echoresources")
@ShortNames({"echo"})
public class EchoResource extends CustomResource<EchoSpec, EchoStatus> implements Namespaced {
}
