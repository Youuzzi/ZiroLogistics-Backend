package com.zirocraft.zirologistics.wms.security.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Opt-in bounded concurrency probe for the configured WMS Argon2id verifier.
 *
 * This test uses only a synthetic password and never accesses application data.
 */
class WmsArgon2idConcurrencyTest {

    private static final int WARMUP_ROUNDS = 5;
    private static final int OPERATIONS_PER_WORKER = 10;
    private static final int[] CONCURRENCY_LEVELS = {1, 2, 4};
    private static final String BENCHMARK_PASSWORD =
            "Wms-Argon2id-Benchmark-Only-Not-A-Real-Account-Password";

    @Test
    @EnabledIfSystemProperty(named = "wms.argon2.concurrency", matches = "true")
    void measureConcurrentPasswordVerification() throws Exception {
        PasswordEncoder encoder = new WmsPasswordEncoderConfiguration().wmsPasswordEncoder();
        String encodedPassword = encoder.encode(BENCHMARK_PASSWORD);

        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            assertTrue(encoder.matches(BENCHMARK_PASSWORD, encodedPassword));
        }

        printEnvironment();
        for (int concurrency : CONCURRENCY_LEVELS) {
            measureAtConcurrency(encoder, encodedPassword, concurrency);
        }
    }

    private static void measureAtConcurrency(
            PasswordEncoder encoder,
            String encodedPassword,
            int concurrency
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<long[]>> futures = new ArrayList<>();

        try {
            for (int worker = 0; worker < concurrency; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent start");

                    long[] samples = new long[OPERATIONS_PER_WORKER];
                    for (int i = 0; i < OPERATIONS_PER_WORKER; i++) {
                        long started = System.nanoTime();
                        boolean matches = encoder.matches(BENCHMARK_PASSWORD, encodedPassword);
                        samples[i] = System.nanoTime() - started;
                        assertTrue(matches, "Synthetic benchmark password should verify");
                    }
                    return samples;
                }));
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS), "Workers did not become ready");
            long batchStarted = System.nanoTime();
            start.countDown();

            List<Long> allSamples = new ArrayList<>(concurrency * OPERATIONS_PER_WORKER);
            for (Future<long[]> future : futures) {
                for (long sample : future.get(2, TimeUnit.MINUTES)) {
                    allSamples.add(sample);
                }
            }
            long elapsedNanos = System.nanoTime() - batchStarted;
            long[] samples = allSamples.stream().mapToLong(Long::longValue).toArray();

            System.out.printf(
                    "WMS_ARGON2_CONCURRENCY workers=%d operations=%d elapsedMs=%.2f throughputOpsPerSec=%.2f p50Ms=%.2f p95Ms=%.2f maxMs=%.2f%n",
                    concurrency,
                    samples.length,
                    toMillis(elapsedNanos),
                    samples.length / (elapsedNanos / 1_000_000_000.0),
                    toMillis(percentile(samples, 0.50)),
                    toMillis(percentile(samples, 0.95)),
                    toMillis(Arrays.stream(samples).max().orElseThrow())
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS),
                    "Argon2id benchmark workers did not terminate");
        }
    }

    private static void printEnvironment() {
        Runtime runtime = Runtime.getRuntime();
        System.out.printf(
                "WMS_ARGON2_CONCURRENCY environment java=%s vm=%s os=%s/%s processors=%d maxHeapMiB=%d%n",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                runtime.availableProcessors(),
                runtime.maxMemory() / (1024 * 1024)
        );
        System.out.printf(
                "WMS_ARGON2_CONCURRENCY parameters memoryKiB=%d iterations=%d parallelism=%d warmup=%d operationsPerWorker=%d levels=%s%n",
                19 * 1024, 2, 1, WARMUP_ROUNDS, OPERATIONS_PER_WORKER,
                Arrays.toString(CONCURRENCY_LEVELS)
        );
    }

    private static long percentile(long[] values, double percentile) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double toMillis(long nanos) {
        return nanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }
}
