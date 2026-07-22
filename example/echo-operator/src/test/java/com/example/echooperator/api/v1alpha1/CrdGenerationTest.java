package com.example.echooperator.api.v1alpha1;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrdGenerationTest {

    @Test
    void crdYamlGeneratedFromJavaClasses() throws IOException {
        Path baseDir = Path.of("").toAbsolutePath();
        Path crdPath = baseDir.resolve("target/classes/META-INF/fabric8/echoresources.example.com-v1.yml");

        assertTrue(Files.exists(crdPath), "CRD YAML should be generated at " + crdPath);

        String content = Files.readString(crdPath);
        assertAll(
                () -> assertTrue(content.contains("kind: \"EchoResource\""), "CRD should declare kind EchoResource"),
                () -> assertTrue(content.contains("group: \"example.com\""), "CRD should declare group example.com"),
                () -> assertTrue(content.contains("name: \"v1alpha1\""), "CRD should declare version v1alpha1"),
                () -> assertTrue(content.contains("name: \"v1alpha2\""), "CRD should declare version v1alpha2"),
                () -> assertTrue(content.contains("plural: \"echoresources\""), "CRD should declare plural echoresources"),
                () -> assertTrue(content.contains("shortNames:"), "CRD should declare shortNames"));
    }

    @Test
    void generatedCrdDeclaresStorageAndDeprecatedVersionFlags() throws IOException {
        Path baseDir = Path.of("").toAbsolutePath();
        Path generatedCrdPath = baseDir.resolve("target/classes/META-INF/fabric8/echoresources.example.com-v1.yml");
        Path sourceCrdPath = baseDir.resolve("src/main/resources/crd/echo-crd.yaml");

        assertTrue(Files.exists(generatedCrdPath), "Generated CRD YAML should exist at " + generatedCrdPath);
        assertTrue(Files.exists(sourceCrdPath), "Source CRD YAML should exist at " + sourceCrdPath);

        String generatedContent = Files.readString(generatedCrdPath);
        String sourceContent = Files.readString(sourceCrdPath);
        String generatedV1alpha2 = versionBlock(generatedContent, "v1alpha2");
        String sourceV1alpha1 = versionBlock(sourceContent, "v1alpha1");

        assertAll(
                () -> assertTrue(sourceV1alpha1.contains("deprecated: true"),
                        "v1alpha1 should be marked deprecated in source CRD"),
                () -> assertTrue(sourceV1alpha1.contains("storage: false"),
                        "v1alpha1 should not be the storage version in source CRD"),
                () -> assertTrue(generatedV1alpha2.contains("storage: true"),
                        "v1alpha2 should be the storage version in generated CRD"),
                () -> assertTrue(generatedV1alpha2.contains("logLevel:"),
                        "v1alpha2 schema should include spec.logLevel in generated CRD"));
    }

    @Test
    void javaClassesGeneratedFromCrdYaml() throws IOException {
        Path baseDir = Path.of("").toAbsolutePath();
        Path generatedDir = baseDir.resolve("target/generated-sources/java");

        Path resourceClass = generatedDir.resolve("com/example/v1alpha1/EchoResource.java");
        Path specClass = generatedDir.resolve("com/example/v1alpha1/EchoResourceSpec.java");
        Path statusClass = generatedDir.resolve("com/example/v1alpha1/EchoResourceStatus.java");

        assertTrue(Files.exists(resourceClass), "Generated EchoResource class should exist");
        assertTrue(Files.exists(specClass), "Generated EchoResourceSpec class should exist");
        assertTrue(Files.exists(statusClass), "Generated EchoResourceStatus class should exist");

        assertTrue(Files.readString(resourceClass).contains("public class EchoResource"),
                "EchoResource should be a public class");
        assertTrue(Files.readString(specClass).contains("public class EchoResourceSpec"),
                "EchoResourceSpec should be a public class");
        assertTrue(Files.readString(statusClass).contains("public class EchoResourceStatus"),
                "EchoResourceStatus should be a public class");
    }

    @Test
    void sourceCrdYamlExists() {
        Path baseDir = Path.of("").toAbsolutePath();
        Path sourceCrd = baseDir.resolve("src/main/resources/crd/echo-crd.yaml");
        assertTrue(Files.exists(sourceCrd), "Source CRD YAML should exist at " + sourceCrd);
    }

    private static String versionBlock(String content, String version) {
        int versionName = content.indexOf("\"" + version + "\"");
        assertTrue(versionName >= 0, "CRD should declare version " + version);

        int start = content.lastIndexOf("\n  - ", versionName);
        assertTrue(start >= 0, "CRD version " + version + " should be in versions list");
        start++;

        int next = content.indexOf("\n  - ", start + 1);
        if (next < 0) {
            return content.substring(start);
        }
        return content.substring(start, next);
    }
}
