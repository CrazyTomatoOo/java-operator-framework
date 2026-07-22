package com.huawei.dcs.modelengine.operator.framework.webhook.conversion;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionResultTest {
    @Test
    void convertedFactoryReturnsSuccessfulResultWithConvertedObject() {
        GenericKubernetesResource resource = new GenericKubernetesResource();

        ConversionResult result = ConversionResult.converted(resource);

        assertTrue(result.successful());
        assertSame(resource, result.convertedObject());
        assertEquals(List.of(), result.errors());
    }

    @Test
    void convertedFactoryRejectsNullObject() {
        assertThrows(NullPointerException.class, () -> ConversionResult.converted(null));
    }

    @Test
    void failedFactoryReturnsUnsuccessfulResultWithTrimmedError() {
        ConversionResult result = ConversionResult.failed(" conversion failed ");

        assertFalse(result.successful());
        assertEquals(null, result.convertedObject());
        assertEquals(List.of("conversion failed"), result.errors());
    }

    @Test
    void failedFactoryRejectsNullOrBlankError() {
        assertThrows(NullPointerException.class, () -> ConversionResult.failed(null));
        assertThrows(IllegalArgumentException.class, () -> ConversionResult.failed(""));
        assertThrows(IllegalArgumentException.class, () -> ConversionResult.failed("  \t  "));
    }
}
