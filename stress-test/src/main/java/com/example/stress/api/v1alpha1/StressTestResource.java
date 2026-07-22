package com.example.stress.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("stress.example.com")
@Version("v1alpha1")
@Kind("StressTestResource")
@Plural("stresstestresources")
@ShortNames({"str"})
public class StressTestResource extends CustomResource<StressTestSpec, StressTestStatus> implements Namespaced {
}
