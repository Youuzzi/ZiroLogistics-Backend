package com.zirocraft.zirologistics.wms.security.throttling.application;

import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketKey;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Application orchestration for shared WMS login throttling.
 *
 * This class deliberately has no HTTP or servlet dependency and is not
 * auto-registered as a Spring bean. A future login use case must explicitly
 * compose it with deployment-approved policy and secret configuration.
 */
public final class WmsLoginThrottleService {

    private final WmsThrottleKeyHasher keyHasher;
    private final WmsClientAddressResolver addressResolver;
    private final WmsThrottleStoragePort storage;
    private final WmsLoginThrottlePolicy policy;
    private final Clock clock;
    private final Object attemptOwner = new Object();

    public WmsLoginThrottleService(
            WmsThrottleKeyHasher keyHasher,
            WmsClientAddressResolver addressResolver,
            WmsThrottleStoragePort storage,
            WmsLoginThrottlePolicy policy,
            Clock clock) {
        this.keyHasher = Objects.requireNonNull(keyHasher, "keyHasher must not be null");
        this.addressResolver = Objects.requireNonNull(addressResolver, "addressResolver must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Records the source request before user lookup or password verification,
     * then applies the post-increment source limit and account cooldown.
     *
     * The maximum is inclusive: N requests are admitted; request N+1 is denied.
     */
    public WmsLoginThrottleDecision beginAttempt(
            String submittedEmail,
            String socketPeerAddress,
            String forwardedForHeader) {
        WmsThrottleBucketKey accountKey = keyHasher.hashAccountEmail(submittedEmail);
        String clientAddress = addressResolver.resolve(socketPeerAddress, forwardedForHeader);
        WmsThrottleBucketKey sourceKey = keyHasher.hashSourceAddress(clientAddress);
        Instant now = clock.instant();
        Instant sourceWindow = fixedWindowStart(now, policy.sourceWindow());

        final WmsThrottlePreVerificationResult state;
        try {
            state = storage.recordSourceAttemptAndCheckAccountCooldown(
                    sourceKey, accountKey, sourceWindow, now);
        } catch (RuntimeException exception) {
            throw new WmsThrottleUnavailableException(exception);
        }

        WmsLoginThrottleDecision.Reason reason;
        if (state.sourceAttemptCount() > policy.sourceMaximumRequests()) {
            reason = WmsLoginThrottleDecision.Reason.SOURCE_LIMIT_EXCEEDED;
        } else if (state.accountCooldownActiveAt(now)) {
            reason = WmsLoginThrottleDecision.Reason.ACCOUNT_COOLDOWN_ACTIVE;
        } else {
            reason = WmsLoginThrottleDecision.Reason.ALLOWED;
        }

        Attempt attempt = reason == WmsLoginThrottleDecision.Reason.ALLOWED
                ? new Attempt(attemptOwner, accountKey)
                : null;
        return new WmsLoginThrottleDecision(
                reason, state.sourceAttemptCount(), state.accountBlockedUntil(), attempt);
    }

    /**
     * Call only after a credential outcome has failed (including unknown-user
     * outcomes after equivalent password-work policy has been applied).
     *
     * The account failure is assigned to the fixed window containing the
     * failure event, not the earlier request-arrival time.
     */
    public WmsThrottleAccountFailureResult recordFailedCredential(Attempt attempt) {
        requireOwnedAttempt(attempt);
        Instant now = clock.instant();
        Instant accountWindow = fixedWindowStart(now, policy.accountFailureWindow());
        try {
            return storage.recordFailedAccountAttempt(
                    attempt.accountKey,
                    accountWindow,
                    policy.accountFailureThreshold(),
                    now.plus(policy.accountCooldown()));
        } catch (RuntimeException exception) {
            throw new WmsThrottleUnavailableException(exception);
        }
    }

    /**
     * Call only after password verification succeeds AND the global user is
     * confirmed ACTIVE, immediately before tenant-selection token issuance.
     *
     * This resets ACCOUNT history only. SOURCE request history is preserved.
     * If commit outcome is unknown, the caller must fail closed and issue no token.
     */
    public int recordSuccessfulActiveUserAuthentication(Attempt attempt) {
        requireOwnedAttempt(attempt);
        try {
            return storage.resetAccountFailures(attempt.accountKey);
        } catch (RuntimeException exception) {
            throw new WmsThrottleUnavailableException(exception);
        }
    }

    private void requireOwnedAttempt(Attempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        if (attempt.owner != attemptOwner) {
            throw new IllegalArgumentException("Attempt was not issued by this throttle service");
        }
    }

    private static Instant fixedWindowStart(Instant instant, java.time.Duration window) {
        long seconds = window.getSeconds();
        long startEpochSecond = Math.floorDiv(instant.getEpochSecond(), seconds) * seconds;
        return Instant.ofEpochSecond(startEpochSecond);
    }

    /**
     * Opaque continuation token; account digest is not exposed to callers.
     */
    public static final class Attempt {
        private final Object owner;
        private final WmsThrottleBucketKey accountKey;

        private Attempt(Object owner, WmsThrottleBucketKey accountKey) {
            this.owner = owner;
            this.accountKey = accountKey;
        }
    }
}
