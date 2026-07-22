package com.huawei.dcs.modelengine.operator.framework.event;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class EventRecorderTest {
    private static final Duration WINDOW = Duration.ofMillis(10);

    KubernetesClient client;
    private EventRecorder recorder;
    private ScheduledExecutorService executor;

    @AfterEach
    void closeRecorder() {
        if (recorder != null) {
            recorder.close();
        }
    }

    @Test
    void action1CreatesCoreV1NormalEventWithExpectedFields() {
        recorder = new EventRecorder(client, "test-component", "default");
        Deployment deployment = deployment("test", "deployment", "deployment-uid");

        recorder.normal(deployment, "Created", "Deployment created");

        Event event = onlyEvent("test");
        assertAll(
                () -> assertEquals("Normal", event.getType()),
                () -> assertEquals("Created", event.getReason()),
                () -> assertEquals("Deployment created", event.getMessage()),
                () -> assertEquals(1, event.getCount()),
                () -> assertEquals("apps/v1", event.getInvolvedObject().getApiVersion()),
                () -> assertEquals("Deployment", event.getInvolvedObject().getKind()),
                () -> assertEquals("deployment", event.getInvolvedObject().getName()),
                () -> assertEquals("test", event.getInvolvedObject().getNamespace()),
                () -> assertEquals("deployment-uid", event.getInvolvedObject().getUid()),
                () -> assertEquals("test-component", event.getSource().getComponent()),
                () -> assertNotNull(event.getEventTime()),
                () -> assertNotNull(event.getFirstTimestamp()),
                () -> assertNotNull(event.getLastTimestamp())
        );
    }

    @Test
    void action2SuppressesImmediateDuplicate() {
        recorder = new EventRecorder(client, "component");
        Deployment deployment = deployment("test", "deployment", "uid-2");
        recorder.normal(deployment, "Created", "Deployment created");

        recorder.normal(deployment, "Created", "Deployment created");

        assertEquals(1, onlyEvent("test").getCount());
        assertEquals(1, recorder.pendingCount());
    }

    @Test
    void action3FlushesExpiredDuplicateWithCachedResourceVersion() {
        MutableClock clock = new MutableClock();
        CachedVersionRecorder cachedVersionRecorder = new CachedVersionRecorder(client, clock, mockExecutor());
        recorder = cachedVersionRecorder;
        Deployment deployment = deployment("test", "deployment", "uid-3");
        recorder.normal(deployment, "Created", "Deployment created");
        clock.advance(Duration.ofMillis(20));

        recorder.normal(deployment, "Created", "Deployment created");

        assertAll(
                () -> assertEquals("1", cachedVersionRecorder.patch.getMetadata().getResourceVersion()),
                () -> assertEquals(2, cachedVersionRecorder.patch.getCount()),
                () -> assertEquals("2026-01-01T00:00:00.020Z", cachedVersionRecorder.patch.getLastTimestamp())
        );
    }

    @Test
    void action4UsesMessageAsPartOfAggregationKey() {
        recorder = new EventRecorder(client, "component");
        Deployment deployment = deployment("test", "deployment", "uid-4");
        recorder.normal(deployment, "Created", "Deployment created");
        recorder.normal(deployment, "Created", "Deployment updated");

        List<Event> events = events("test");
        assertEquals(2, events.size());
        assertNotEquals(events.get(0).getMetadata().getName(), events.get(1).getMetadata().getName());
    }

    @Test
    void action5SanitizesAndTruncatesNameWhilePreservingHash() {
        recorder = new EventRecorder(client, "Component");
        String objectName = "VERY_LONG.Name_With$Special#Characters-And-More-Characters-To-Force-Truncation";
        Deployment deployment = deployment("test", objectName, "uid-5");
        String message = "Uppercase & special message";

        recorder.normal(deployment, "Created!", message);

        String name = onlyEvent("test").getMetadata().getName();
        String hash = hash("Component|uid-5|Normal|Created!|" + message).substring(0, 8);
        assertAll(
                () -> assertTrue(name.matches("[a-z0-9]([-a-z0-9.]*[a-z0-9])?")),
                () -> assertTrue(name.length() <= 63),
                () -> assertTrue(name.endsWith("-" + hash))
        );
    }

    @Test
    void action6BoundsCacheAtDefaultMaximum() {
        MutableClock clock = new MutableClock();
        recorder = recorder(clock, 1000);
        Deployment deployment = deployment("test", "deployment", "uid-6");
        for (int index = 0; index < 1001; index++) {
            recorder.normal(deployment, "Created", "message-" + index);
        }
        assertEquals(1000, recorder.cacheSize());
    }

    @Test
    void action7UsesDefaultNamespaceForClusterScopedObject() {
        recorder = new EventRecorder(client, "component", "events-default");
        Node node = new NodeBuilder().withNewMetadata().withName("node-1").withUid("node-uid").endMetadata().build();

        recorder.normal(node, "Synced", "Node synced");

        assertEquals("events-default", onlyEvent("events-default").getMetadata().getNamespace());
    }

    @Test
    void action8RetriesConflictWithFreshResourceVersionAndLatestCount() {
        MutableClock clock = new MutableClock();
        ScheduledExecutorService scheduled = mockExecutor();
        RetryRecorder retryRecorder = new RetryRecorder(client, clock, scheduled);
        recorder = retryRecorder;
        Deployment deployment = deployment("test", "deployment", "uid-8");
        recorder.normal(deployment, "Created", "message");
        recorder.normal(deployment, "Created", "message");
        clock.advance(Duration.ofMillis(20));

        recorder.normal(deployment, "Created", "message");

        assertAll(
                () -> assertEquals("1", retryRecorder.firstPatch.getMetadata().getResourceVersion()),
                () -> assertEquals("2", retryRecorder.retryPatch.getMetadata().getResourceVersion()),
                () -> assertEquals(5, retryRecorder.retryPatch.getCount())
        );
    }

    @Test
    void action9AggregatesMultipleSuppressedOccurrences() {
        MutableClock clock = new MutableClock();
        recorder = recorder(clock, 1000);
        Deployment deployment = deployment("test", "deployment", "uid-9");
        recorder.normal(deployment, "Created", "message");
        recorder.normal(deployment, "Created", "message");
        recorder.normal(deployment, "Created", "message");
        recorder.normal(deployment, "Created", "message");
        clock.advance(Duration.ofMillis(20));

        recorder.normal(deployment, "Created", "message");

        assertEquals(5, onlyEvent("test").getCount());
    }

    @Test
    void action10ScheduledFlushPersistsPendingOccurrences() {
        MutableClock clock = new MutableClock();
        Runnable[] task = new Runnable[1];
        recorder = recorder(clock, 1000, task);
        Deployment deployment = deployment("test", "deployment", "uid-10");
        recorder.normal(deployment, "Created", "message");
        recorder.normal(deployment, "Created", "message");
        recorder.normal(deployment, "Created", "message");
        clock.advance(Duration.ofMillis(20));

        task[0].run();

        assertEquals(3, onlyEvent("test").getCount());
    }

    @Test
    void action11ScheduledFlushRecreatesMissingEventUsingOnlyPendingCount() {
        MutableClock clock = new MutableClock();
        Runnable[] task = new Runnable[1];
        recorder = recorder(clock, 1000, task);
        Deployment deployment = deployment("test", "deployment", "uid-11");
        recorder.normal(deployment, "Created", "message");
        Event original = onlyEvent("test");
        client.v1().events().inNamespace("test").withName(original.getMetadata().getName()).delete();
        recorder.normal(deployment, "Created", "message");
        clock.advance(Duration.ofMillis(20));

        task[0].run();

        assertEquals(1, onlyEvent("test").getCount());
    }

    @Test
    void action12SerializesConcurrentEmissionsPerKey() throws Exception {
        recorder = new EventRecorder(client, "component");
        Deployment deployment = deployment("test", "deployment", "uid-12");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 10; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    recorder.normal(deployment, "Created", "message");
                    return null;
                }));
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, events("test").size());
        assertEquals(1, onlyEvent("test").getCount());
        assertEquals(9, recorder.pendingCount());
    }

    @Test
    void action13TreatsNullWarningMessageAsEmpty() {
        recorder = new EventRecorder(client, "component");
        recorder.warning(deployment("test", "deployment", "uid-13"), "Failed", null);
        assertEquals("", onlyEvent("test").getMessage());
        assertEquals("Warning", onlyEvent("test").getType());
    }

    @Test
    void action14CancelsTaskWithoutShuttingDownExternalExecutor() {
        MutableClock clock = new MutableClock();
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        executor = mock(ScheduledExecutorService.class);
        when(executor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> future);
        recorder = new EventRecorder(client, "component", "default", WINDOW, clock, executor, 1000);
        recorder.normal(deployment("test", "deployment", "uid-14"), "Created", "message");

        recorder.close();

        verify(future).cancel(false);
        verify(executor, never()).shutdown();
    }

    @Test
    void action14bCloseSynchronouslyFlushesPendingCount() {
        recorder = new EventRecorder(client, "component");
        Deployment deployment = deployment("test", "deployment", "uid-14b");
        recorder.normal(deployment, "Created", "message");
        recorder.normal(deployment, "Created", "message");
        recorder.close();
        assertEquals(2, onlyEvent("test").getCount());
    }

    @Test
    void action14cConcurrentScheduledFlushAndCloseDoNotDoubleCount() throws Exception {
        MutableClock clock = new MutableClock();
        Runnable[] task = new Runnable[1];
        recorder = recorder(clock, 1000, task);
        Deployment deployment = deployment("test", "deployment", "uid-14c");
        recorder.normal(deployment, "Created", "message");
        recorder.normal(deployment, "Created", "message");
        clock.advance(Duration.ofMillis(20));
        EventRecorder current = recorder;

        runConcurrently(task[0], current::close);

        assertEquals(2, onlyEvent("test").getCount());
    }

    @Test
    void action14dCloseIsIdempotent() {
        recorder = new EventRecorder(client, "component");
        recorder.close();
        assertDoesNotThrow(recorder::close);
    }

    @Test
    void action14eEmissionAfterCloseIsRejected() {
        recorder = new EventRecorder(client, "component");
        recorder.close();
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> recorder.normal(deployment("test", "deployment", "uid-14e"), "Created", "message"));
        assertTrue(exception.getMessage().contains("closed"));
        assertTrue(events("test").isEmpty());
    }

    @Test
    void action14fConcurrentEmissionAndCloseHasConsistentCount() throws Exception {
        recorder = new EventRecorder(client, "component");
        Deployment deployment = deployment("test", "deployment", "uid-14f");
        recorder.normal(deployment, "Created", "message");
        EventRecorder current = recorder;
        AtomicReference<Throwable> emissionFailure = new AtomicReference<>();

        runConcurrently(current::close, () -> {
            try {
                current.normal(deployment, "Created", "message");
            } catch (Throwable throwable) {
                emissionFailure.set(throwable);
            }
        });

        Throwable failure = emissionFailure.get();
        if (failure == null) {
            assertEquals(2, onlyEvent("test").getCount());
        } else {
            assertInstanceOf(IllegalStateException.class, failure);
            assertEquals(1, onlyEvent("test").getCount());
        }
        assertEquals(1, events("test").size());
    }

    @Test
    void action15EvictionFlushesPendingCountBeforeRemoval() {
        MutableClock clock = new MutableClock();
        recorder = recorder(clock, 2);
        Deployment deployment = deployment("test", "deployment", "uid-15");
        recorder.normal(deployment, "Created", "first");
        recorder.normal(deployment, "Created", "first");
        recorder.normal(deployment, "Created", "second");
        recorder.normal(deployment, "Created", "third");

        Event first = events("test").stream().filter(event -> "first".equals(event.getMessage())).findFirst().orElseThrow();
        assertEquals(2, first.getCount());
        assertEquals(2, recorder.cacheSize());
    }

    @Test
    void action16AlreadyExistingEventIsFetchedAndPatched() {
        recorder = new EventRecorder(client, "component");
        Deployment deployment = deployment("test", "deployment", "uid-16");
        String name = expectedName("deployment", "component|uid-16|Normal|Created|message");
        Event existing = new EventBuilder()
                .withNewMetadata().withName(name).withNamespace("test").endMetadata()
                .withCount(5).withType("Normal").withReason("Created").withMessage("message")
                .build();
        client.v1().events().inNamespace("test").resource(existing).create();

        recorder.normal(deployment, "Created", "message");

        assertEquals(6, onlyEvent("test").getCount());
    }

    @Test
    void action17TtlEvictionThenExistingEventAggregatesCorrectly() {
        MutableClock clock = new MutableClock();
        recorder = recorder(clock, 1000);
        Deployment deployment = deployment("test", "deployment", "uid-17");
        recorder.normal(deployment, "Created", "message");
        clock.advance(Duration.ofMillis(21));
        recorder.normal(deployment("test", "other", "other-uid"), "Created", "other");
        assertEquals(1, recorder.cacheSize());

        recorder.normal(deployment, "Created", "message");

        Event event = events("test").stream().filter(value -> "message".equals(value.getMessage())).findFirst().orElseThrow();
        assertEquals(2, event.getCount());
    }

    @Test
    void failureChecksRejectInvalidRequiredInputsWithoutCreatingEvent() {
        recorder = new EventRecorder(client, "component");
        NullPointerException objectError = assertThrows(NullPointerException.class,
                () -> recorder.normal(null, "Created", "message"));
        NullPointerException typeError = assertThrows(NullPointerException.class,
                () -> recorder.event(deployment("test", "deployment", "uid"), null, "Created", "message"));
        NullPointerException reasonError = assertThrows(NullPointerException.class,
                () -> recorder.event(deployment("test", "deployment", "uid"), "Normal", null, "message"));
        IllegalArgumentException missingUid = assertThrows(IllegalArgumentException.class,
                () -> recorder.normal(deployment("test", "deployment", null), "Created", "message"));
        IllegalArgumentException blankUid = assertThrows(IllegalArgumentException.class,
                () -> recorder.normal(deployment("test", "deployment", "  "), "Created", "message"));

        assertAll(
                () -> assertTrue(objectError.getMessage().contains("involvedObject")),
                () -> assertTrue(typeError.getMessage().contains("type")),
                () -> assertTrue(reasonError.getMessage().contains("reason")),
                () -> assertTrue(missingUid.getMessage().contains("uid")),
                () -> assertTrue(blankUid.getMessage().contains("uid")),
                () -> assertTrue(events("test").isEmpty())
        );
    }

    private EventRecorder recorder(MutableClock clock, int maxEntries) {
        return recorder(clock, maxEntries, new Runnable[1]);
    }

    private EventRecorder recorder(MutableClock clock, int maxEntries, Runnable[] scheduledTask) {
        executor = mockExecutor(scheduledTask);
        return new EventRecorder(client, "component", "default", WINDOW, clock, executor, maxEntries);
    }

    private ScheduledExecutorService mockExecutor() {
        return mockExecutor(new Runnable[1]);
    }

    private ScheduledExecutorService mockExecutor(Runnable[] scheduledTask) {
        ScheduledExecutorService scheduled = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduled.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    scheduledTask[0] = invocation.getArgument(0);
                    return future;
                });
        return scheduled;
    }

    private Event onlyEvent(String namespace) {
        List<Event> events = events(namespace);
        assertEquals(1, events.size());
        return events.get(0);
    }

    private List<Event> events(String namespace) {
        return client.v1().events().inNamespace(namespace).list().getItems();
    }

    private static Deployment deployment(String namespace, String name, String uid) {
        return new DeploymentBuilder().withNewMetadata().withNamespace(namespace).withName(name).withUid(uid)
                .endMetadata().build();
    }

    private static void runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> firstFuture = pool.submit(() -> { start.await(); first.run(); return null; });
            Future<?> secondFuture = pool.submit(() -> { start.await(); second.run(); return null; });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            firstFuture.get();
            secondFuture.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private static String expectedName(String base, String key) {
        String sanitized = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        sanitized = sanitized.isEmpty() ? "event" : sanitized;
        String suffix = hash(key).substring(0, 8);
        return sanitized.substring(0, Math.min(sanitized.length(), 54)).replaceAll("-+$", "") + "-" + suffix;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class MutableClock implements Supplier<Instant> {
        private Instant current = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public Instant get() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }
    }

    private static final class RetryRecorder extends EventRecorder {
        private Event firstPatch;
        private Event retryPatch;
        private int patchAttempts;

        RetryRecorder(KubernetesClient client, Supplier<Instant> clock, ScheduledExecutorService executor) {
            super(client, "component", "default", WINDOW, clock, executor, 1000);
        }

        @Override
        Event createEvent(String namespace, Event event) {
            return new EventBuilder(event).editMetadata().withResourceVersion("1").endMetadata().build();
        }

        @Override
        Event getEvent(String namespace, String name) {
            return new EventBuilder(firstPatch).editMetadata().withResourceVersion("2").endMetadata()
                    .withCount(3).build();
        }

        @Override
        Event patchEvent(String namespace, String name, Event patch) {
            patchAttempts++;
            if (patchAttempts == 1) {
                firstPatch = patch;
                throw new KubernetesClientException(new StatusBuilder().withCode(409).withReason("Conflict").build());
            }
            retryPatch = patch;
            return new EventBuilder(patch).editMetadata().withResourceVersion("3").endMetadata().build();
        }
    }

    private static final class CachedVersionRecorder extends EventRecorder {
        private Event patch;

        CachedVersionRecorder(KubernetesClient client, Supplier<Instant> clock, ScheduledExecutorService executor) {
            super(client, "component", "default", WINDOW, clock, executor, 1000);
        }

        @Override
        Event createEvent(String namespace, Event event) {
            return new EventBuilder(event).editMetadata().withResourceVersion("1").endMetadata().build();
        }

        @Override
        Event patchEvent(String namespace, String name, Event patch) {
            this.patch = patch;
            return new EventBuilder(patch).editMetadata().withResourceVersion("2").endMetadata().build();
        }
    }
}
