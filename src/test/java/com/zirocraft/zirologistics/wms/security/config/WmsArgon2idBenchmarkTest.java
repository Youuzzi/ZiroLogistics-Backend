package com.zirocraft.zirologistics.wms.security.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Opt-in local benchmark for the configured WMS Argon2id parameters.
 *
 * This is intentionally disabled during normal unit/integration test runs.
 * Run on the target deployment class of machine before selecting login limits.
 */
class WmsArgon2idBenchmarkTest {

    private static final int WARMUP_ROUNDS = 5;
    private static final int MEASUREMENT_ROUNDS = 30;
    private static final String BENCHMARK_PASSWORD =
            "Wms-Argon2id-Benchmark-Only-Not-A-Real-Account-Password";

    @Test
    @EnabledIfSystemProperty(named = "wms.argon2.benchmark", matches = "true")
    void benchmarkConfiguredArgon2idHashAndVerification() {
        PasswordEncoder encoder = new WmsPasswordEncoderConfiguration().wmsPasswordEncoder();
        String encodedPassword = encoder.encode(BENCHMARK_PASSWORD);

        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            encoder.encode(BENCHMARK_PASSWORD);
            assertTrue(encoder.matches(BENCHMARK_PASSWORD, encodedPassword));
        }

        long[] encodeNanos = new long[MEASUREMENT_ROUNDS];
        long[] verifyNanos = new long[MEASUREMENT_ROUNDS];

        for (int i = 0; i < MEASUREMENT_ROUNDS; i++) {
            long started = System.nanoTime();
            encoder.encode(BENCHMARK_PASSWORD);
            encodeNanos[i] = System.nanoTime() - started;

            started = System.nanoTime();
            assertTrue(encoder.matches(BENCHMARK_PASSWORD, encodedPassword));
            verifyNanos[i] = System.nanoTime() - started;
        }

        printEnvironment();
        printStatistics("Argon2id encode", encodeNanos);
        printStatistics("Argon2id verify", verifyNanos);
        printStatistics("Encode + verify", add(encodeNanos, verifyNanos));
    }

    private static long[] add(long[] left, long[] right) {
        long[] totals = new long[left.length];
        for (int i = 0; i < left.length; i++) {
            totals[i] = left[i] + right[i];
        }
        return totals;
    }

    private static void printEnvironment() {
        Runtime runtime = Runtime.getRuntime();
        System.out.printf(
                "WMS_ARGON2_BENCHMARK environment java=%s vm=%s os=%s/%s processors=%d maxHeapMiB=%d%n",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                runtime.availableProcessors(),
                runtime.maxMemory() / (1024 * 1024)
        );
        System.out.printf(
                "WMS_ARGON2_BENCHMARK parameters memoryKiB=%d iterations=%d parallelism=%d saltBytes=%d hashBytes=%d warmup=%d samples=%d%n",
                19 * 1024, 2, 1, 16, 32, WARMUP_ROUNDS, MEASUREMENT_ROUNDS
        );
    }

    private static void printStatistics(String operation, long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        System.out.printf(
                "WMS_ARGON2_BENCHMARK operation=\"%s\" minMs=%.2f medianMs=%.2f p95Ms=%.2f maxMs=%.2f%n",
                operation,
                toMillis(sorted[0]),
                toMillis(percentile(sorted, 0.50)),
                toMillis(percentile(sorted, 0.95)),
                toMillis(sorted[sorted.length - 1])
        );
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double toMillis(long nanos) {
        return nanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }
}
