package com.zirocraft.zirologistics.wms.security.throttling.application;

import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketKey;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WmsLoginThrottleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T22:40:00Z");
    private static final byte[] HMAC_SECRET = new byte[32];

    @Test
    void allowsExactlyConfiguredSourceMaximumAndDeniesNextRequest() {
        FakeStorage storage = new FakeStorage();
        WmsLoginThrottleService service = service(storage, 3, 5);

        assertTrue(service.beginAttempt(" User@Example.com ", "198.51.100.10", null).allowed());
        assertTrue(service.beginAttempt("user@example.com", "198.51.100.10", null).allowed());
        assertTrue(service.beginAttempt("user@example.com", "198.51.100.10", null).allowed());

        WmsLoginThrottleDecision denied =
                service.beginAttempt("user@example.com", "198.51.100.10", null);

        assertEquals(WmsLoginThrottleDecision.Reason.SOURCE_LIMIT_EXCEEDED, denied.reason());
        assertEquals(4, denied.sourceAttemptCount());
        assertNull(denied.attempt());
    }

    @Test
    void activeAccountCooldownDeniesBeforePasswordVerification() {
        FakeStorage storage = new FakeStorage();
        WmsLoginThrottleService service = service(storage, 10, 2);
        WmsLoginThrottleService.Attempt first =
                service.beginAttempt("user@example.com", "198.51.100.11", null).attempt();

        service.recordFailedCredential(first);
        WmsLoginThrottleService.Attempt second =
                service.beginAttempt("user@example.com", "198.51.100.12", null).attempt();
        service.recordFailedCredential(second);

        WmsLoginThrottleDecision denied =
                service.beginAttempt("user@example.com", "198.51.100.13", null);

        assertEquals(WmsLoginThrottleDecision.Reason.ACCOUNT_COOLDOWN_ACTIVE, denied.reason());
        assertNotNull(denied.accountBlockedUntil());
        assertNull(denied.attempt());
    }

    @Test
    void successfulActiveUserResetRemovesAccountFailuresButNotSourceHistory() {
        FakeStorage storage = new FakeStorage();
        WmsLoginThrottleService service = service(storage, 10, 5);
        WmsLoginThrottleDecision decision =
                service.beginAttempt("user@example.com", "198.51.100.14", null);

        service.recordFailedCredential(decision.attempt());
        assertEquals(1, storage.accountFailures.size());

        assertEquals(1, service.recordSuccessfulActiveUserAuthentication(decision.attempt()));
        assertTrue(storage.accountFailures.isEmpty());
        assertEquals(1, storage.sourceCounts.size());
    }

    @Test
    void storageFailureFailsClosedWithGenericUnavailableException() {
        FakeStorage storage = new FakeStorage();
        storage.fail = true;
        WmsLoginThrottleService service = service(storage, 10, 5);

        assertThrows(WmsThrottleUnavailableException.class,
                () -> service.beginAttempt("user@example.com", "198.51.100.15", null));
    }

    @Test
    void rejectsAttemptIssuedByAnotherServiceInstance() {
        FakeStorage storage = new FakeStorage();
        WmsLoginThrottleService first = service(storage, 10, 5);
        WmsLoginThrottleService second = service(storage, 10, 5);
        WmsLoginThrottleService.Attempt attempt =
                first.beginAttempt("user@example.com", "198.51.100.16", null).attempt();

        assertThrows(IllegalArgumentException.class,
                () -> second.recordSuccessfulActiveUserAuthentication(attempt));
    }

    @Test
    void trustedForwardedAddressIsUsedOnlyWhenSocketPeerIsTrusted() {
        FakeStorage storage = new FakeStorage();
        WmsClientAddressResolver resolver = new WmsClientAddressResolver(java.util.List.of("10.0.0.0/8"));
        WmsLoginThrottleService service = new WmsLoginThrottleService(
                new WmsThrottleKeyHasher(1, HMAC_SECRET),
                resolver,
                storage,
                policy(10, 5),
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.beginAttempt("user@example.com", "10.0.0.5", "203.0.113.9");
        service.beginAttempt("user@example.com", "192.0.2.5", "203.0.113.9");

        assertEquals(2, storage.sourceCounts.size());
    }

    private static WmsLoginThrottleService service(FakeStorage storage, int sourceLimit, int accountThreshold) {
        return new WmsLoginThrottleService(
                new WmsThrottleKeyHasher(1, HMAC_SECRET),
                new WmsClientAddressResolver(java.util.List.of()),
                storage,
                policy(sourceLimit, accountThreshold),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WmsLoginThrottlePolicy policy(int sourceLimit, int accountThreshold) {
        return new WmsLoginThrottlePolicy(
                Duration.ofSeconds(60), sourceLimit,
                Duration.ofSeconds(300), accountThreshold,
                Duration.ofSeconds(600));
    }

    private static final class FakeStorage implements WmsThrottleStoragePort {
        private final Map<String, Long> sourceCounts = new HashMap<>();
        private final Map<String, Long> accountFailures = new HashMap<>();
        private final Map<String, Instant> cooldowns = new HashMap<>();
        private boolean fail;

        @Override
        public WmsThrottlePreVerificationResult recordSourceAttemptAndCheckAccountCooldown(
                WmsThrottleBucketKey sourceKey,
                WmsThrottleBucketKey accountKey,
                Instant sourceWindowStartedAt,
                Instant decisionTime) {
            checkFailure();
            String source = key(sourceKey) + sourceWindowStartedAt;
            long count = sourceCounts.merge(source, 1L, Long::sum);
            Instant blockedUntil = cooldowns.get(key(accountKey));
            if (blockedUntil != null && !blockedUntil.isAfter(decisionTime)) {
                blockedUntil = null;
            }
            return new WmsThrottlePreVerificationResult(count, blockedUntil);
        }

        @Override
        public WmsThrottleAccountFailureResult recordFailedAccountAttempt(
                WmsThrottleBucketKey accountKey,
                Instant windowStartedAt,
                int failureThreshold,
                Instant cooldownUntil) {
            checkFailure();
            String account = key(accountKey) + windowStartedAt;
            long count = accountFailures.merge(account, 1L, Long::sum);
            if (count == failureThreshold) {
                cooldowns.put(key(accountKey), cooldownUntil);
            }
            return new WmsThrottleAccountFailureResult(count, cooldowns.get(key(accountKey)));
        }

        @Override
        public int resetAccountFailures(WmsThrottleBucketKey accountKey) {
            checkFailure();
            String prefix = key(accountKey);
            int before = accountFailures.size();
            accountFailures.keySet().removeIf(value -> value.startsWith(prefix));
            cooldowns.remove(prefix);
            return before - accountFailures.size();
        }

        @Override
        public int deleteExpiredBuckets(Instant retentionCutoff, Instant decisionTime, int batchSize) {
            throw new UnsupportedOperationException("Not used by this unit test");
        }

        private void checkFailure() {
            if (fail) {
                throw new IllegalStateException("simulated storage failure");
            }
        }

        private static String key(WmsThrottleBucketKey key) {
            return key.type() + ":" + key.keyVersion() + ":" + key.digestHex() + ":";
        }
    }
}
