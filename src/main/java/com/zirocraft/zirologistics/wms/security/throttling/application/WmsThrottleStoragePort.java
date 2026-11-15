package com.zirocraft.zirologistics.wms.security.throttling.application;

import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketKey;

import java.time.Instant;

/**
 * Application-owned semantic port for shared WMS login-throttle persistence.
 *
 * Implementations must provide the atomicity and failure semantics documented
 * by each operation. This interface deliberately exposes no JDBC, JPA, MySQL,
 * Redis, or provider-specific types.
 */
public interface WmsThrottleStoragePort {

    /**
     * Atomically records one source request and checks the account's active
     * cooldown as of decisionTime.
     *
     * The source and account keys must have SOURCE and ACCOUNT types
     * respectively. The source increment and account cooldown read must share
     * one transaction/atomic operation; callers must not split them into
     * independent read/write calls. The returned count is the committed
     * post-increment count for this source window.
     *
     * @param sourceKey privacy-preserving source bucket identity
     * @param accountKey privacy-preserving account bucket identity
     * @param sourceWindowStartedAt UTC instant identifying the source fixed window
     * @param decisionTime UTC instant used for the cooldown decision
     */
    WmsThrottlePreVerificationResult recordSourceAttemptAndCheckAccountCooldown(
            WmsThrottleBucketKey sourceKey,
            WmsThrottleBucketKey accountKey,
            Instant sourceWindowStartedAt,
            Instant decisionTime);

    /**
     * Atomically records one failed credential outcome for an account.
     *
     * The adapter increments the account bucket without lost updates and sets
     * the supplied absolute cooldown instant when the post-increment count
     * reaches failureThreshold. Thresholds and cooldown durations are policy
     * inputs; this port supplies no defaults or approved numeric values.
     *
     * @param accountKey ACCOUNT bucket identity
     * @param windowStartedAt UTC instant identifying the account fixed window
     * @param failureThreshold positive policy threshold
     * @param cooldownUntil absolute UTC instant to persist if threshold is reached
     */
    WmsThrottleAccountFailureResult recordFailedAccountAttempt(
            WmsThrottleBucketKey accountKey,
            Instant windowStartedAt,
            int failureThreshold,
            Instant cooldownUntil);

    /**
     * Atomically removes all ACCOUNT failure rows for the supplied account
     * bucket identity across every fixed window. The operation must not touch
     * SOURCE buckets or another key version/digest. It is called only after
     * credentials have been verified and the global user is confirmed active,
     * before a tenant-selection token is issued.
     *
     * The delete and concurrent failed-account upserts are serialized by the
     * database's transaction/row-lock ordering: failures committed before the
     * reset are removed; failures committed after it remain as new failure
     * state. Callers must not blindly retry when commit outcome is unknown.
     *
     * @param accountKey active ACCOUNT bucket identity
     * @return number of account-window rows deleted
     */
    int resetAccountFailures(WmsThrottleBucketKey accountKey);

    /**
     * Deletes at most batchSize expired rows older than retentionCutoff.
     * Rows with a cooldown later than decisionTime must be retained.
     * This bounded maintenance operation must never run on the login request
     * critical path.
     *
     * @return number of rows deleted
     */
    int deleteExpiredBuckets(
            Instant retentionCutoff,
            Instant decisionTime,
            int batchSize);
}
