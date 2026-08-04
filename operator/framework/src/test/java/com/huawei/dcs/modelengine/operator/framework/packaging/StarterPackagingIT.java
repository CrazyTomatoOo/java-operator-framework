/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;

class StarterPackagingIT {
    private static final String FRAMEWORK_PATH = "com/huawei/dcs/modelengine/operator/framework/";
    private static final String AUTO_CONFIGURATION = FRAMEWORK_PATH
            + "autoconfigure/OperatorFrameworkAutoConfiguration.class";
    private static final Set<String> ALLOWED_ROOTS = Set.of("api", "autoconfigure", "internal");
    private static final Set<String> CONFIGURATION_KEYS = Set.of(
            "operator.framework.enabled",
            "operator.framework.mode",
            "operator.framework.controller.namespace",
            "operator.framework.controller.cluster-scoped",
            "operator.framework.controller.worker-threads",
            "operator.framework.controller.resync-period",
            "operator.framework.controller.generation-change-filter",
            "operator.framework.controller.filter-events-by-involved-object",
            "operator.framework.controller.startup-retry-delay",
            "operator.framework.leader-election.enabled",
            "operator.framework.leader-election.lease-name",
            "operator.framework.leader-election.namespace",
            "operator.framework.leader-election.lease-duration",
            "operator.framework.leader-election.renew-deadline",
            "operator.framework.leader-election.retry-period",
            "operator.framework.retry.initial-delay",
            "operator.framework.retry.max-delay",
            "operator.framework.retry.max-attempts",
            "operator.framework.rate-limit.minimum-interval",
            "operator.framework.events.enabled",
            "operator.framework.events.component",
            "operator.framework.events.aggregation-window",
            "operator.framework.events.max-cache-entries");
    private static final Set<String> LEGACY_ROOT_CLASSES = Set.of(
            "ControllerBuilder.class",
            "ControllerRegistration.class",
            "ControllerSources.class",
            "Operator.class",
            "SecondaryWatch.class");
    private static final Set<String> LEGACY_PACKAGE_ROOTS = Set.of(
            "event", "health", "leader", "metrics", "reconciler", "retry", "source", "util", "webhook");
    private static final Set<String> OLD_DOC_REFERENCES = Set.of(
            "<artifactId>operator-framework</artifactId>",
            "new Operator(",
            "Operator.register",
            "operator.start(",
            "operator.stop(",
            "MetricsHealthServer",
            "WebhookServer",
            "WebhookSelfRegistration",
            "WebhookCertificateGenerator",
            ".operator.framework.reconciler.",
            ".operator.framework.source.");

    @Test
    void packagesSpringBootImportsAndConfigurationMetadata() throws IOException {
        var classes = classesDirectory();
        var imports = classes.resolve("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        var metadata = classes.resolve("META-INF/spring-configuration-metadata.json");

        assertThat(Files.readString(imports).trim())
                .isEqualTo("com.huawei.dcs.modelengine.operator.framework.autoconfigure."
                        + "OperatorFrameworkAutoConfiguration");
        assertThat(metadata).isRegularFile();
        var properties = new ObjectMapper().readTree(Files.readString(metadata)).path("properties");
        var keys = new HashSet<String>();
        properties.forEach(property -> keys.add(property.path("name").asText()));
        assertThat(keys).containsExactlyInAnyOrderElementsOf(CONFIGURATION_KEYS);
    }

    @Test
    void jarContainsOnlySupportedFrameworkPackageRoots() throws IOException {
        try (var jar = new JarFile(jarPath().toFile())) {
            var entries = jar.stream().map(entry -> entry.getName()).toList();
            assertThat(entries).contains(
                    AUTO_CONFIGURATION,
                    "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                    "META-INF/spring-configuration-metadata.json");
            assertThat(packageRoots(entries)).containsExactlyInAnyOrderElementsOf(ALLOWED_ROOTS);
            assertThat(entries).noneMatch(this::isLegacyEntry);
        }
    }

    @Test
    void compiledOutputContainsNoLegacyPackagesOrRootClasses() {
        var framework = classesDirectory().resolve(FRAMEWORK_PATH);
        assertThat(childNames(framework)).containsExactlyInAnyOrderElementsOf(ALLOWED_ROOTS);
        LEGACY_ROOT_CLASSES.forEach(name -> assertThat(framework.resolve(name)).doesNotExist());
        LEGACY_PACKAGE_ROOTS.forEach(name -> assertThat(framework.resolve(name)).doesNotExist());
    }

    @Test
    void stressTestModuleRemainsAbsentAndExampleIsTheSpringBootOne() {
        assertThat(repositoryRoot().resolve("stress-test")).doesNotExist();
        assertThat(repositoryRoot().resolve("example/echo-operator/pom.xml")).exists();
    }

    @Test
    void currentDocumentationContainsNoLegacyManualApis() throws IOException {
        for (var document : documentation()) {
            var content = Files.readString(document);
            OLD_DOC_REFERENCES.forEach(reference -> assertThat(content)
                    .as("%s must not reference %s", repositoryRoot().relativize(document), reference)
                    .doesNotContain(reference));
        }
    }

    private Set<String> packageRoots(java.util.List<String> entries) {
        var roots = new HashSet<String>();
        entries.stream()
                .filter(name -> name.startsWith(FRAMEWORK_PATH) && name.endsWith(".class"))
                .map(name -> name.substring(FRAMEWORK_PATH.length()))
                .filter(name -> name.contains("/"))
                .map(name -> name.substring(0, name.indexOf('/')))
                .forEach(roots::add);
        return roots;
    }

    private boolean isLegacyEntry(String entry) {
        if (LEGACY_ROOT_CLASSES.stream().anyMatch(name -> entry.equals(FRAMEWORK_PATH + name))) {
            return true;
        }
        return LEGACY_PACKAGE_ROOTS.stream().anyMatch(name -> entry.startsWith(FRAMEWORK_PATH + name + "/"));
    }

    private Set<String> childNames(Path directory) {
        try (var children = Files.list(directory)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        } catch (IOException exception) {
            throw new IllegalStateException("cannot inspect compiled framework packages", exception);
        }
    }

    private java.util.List<Path> documentation() {
        return java.util.List.of(
                moduleRoot().resolve("README.md"),
                moduleRoot().resolve("README.zh-CN.md"),
                repositoryRoot().resolve("docs/dev-guide.md"),
                repositoryRoot().resolve("docs/dev-guide.zh-CN.md"));
    }

    private Path classesDirectory() {
        return Path.of(requiredProperty("framework.build.directory")).resolve("classes");
    }

    private Path jarPath() {
        return Path.of(requiredProperty("framework.build.directory"))
                .resolve(requiredProperty("framework.final.name") + ".jar");
    }

    private Path moduleRoot() {
        return Path.of(requiredProperty("framework.project.basedir")).toAbsolutePath().normalize();
    }

    private Path repositoryRoot() {
        var root = moduleRoot().resolve("../..").normalize();
        assertThat(root.resolve("operator/framework/pom.xml")).isRegularFile();
        return root;
    }

    private String requiredProperty(String name) {
        return java.util.Objects.requireNonNull(System.getProperty(name), name + " is not configured");
    }
}
