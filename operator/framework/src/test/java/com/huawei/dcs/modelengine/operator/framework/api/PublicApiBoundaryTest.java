/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class PublicApiBoundaryTest {
    @Test
    void productionSourcesUseSupportedPackageRoots() throws IOException {
        var frameworkPackage = Path.of("src/main/java/com/huawei/dcs/modelengine/operator/framework");
        var allowed = java.util.Set.of("api", "autoconfigure", "internal");
        try (var files = Files.walk(frameworkPackage)) {
            assertTrue(files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(frameworkPackage::relativize)
                    .allMatch(path -> allowed.contains(path.getName(0).toString())));
        }
    }

    @Test
    void sourceTreeContainsNoCompiledClasses() throws IOException {
        try (var files = Files.walk(Path.of("src"))) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".class")));
        }
    }
}
