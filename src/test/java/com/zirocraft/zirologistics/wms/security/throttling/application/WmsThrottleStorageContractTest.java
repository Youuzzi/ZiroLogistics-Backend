package com.zirocraft.zirologistics.wms.security.throttling.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WmsThrottleStorageContractTest {

    @Test
    void preVerificationResultReportsCooldownOnlyWhenItIsAfterDecisionTime() {
        Instant decisionTime = Instant.parse("2026-11-15T20:00:00Z");
        WmsThrottlePreVerificationResult active = new WmsThrottlePreVerificationResult(
                3, decisionTime.plusSeconds(60));
        WmsThrottlePreVerificationResult expired = new WmsThrottlePreVerificationResult(
                4, decisionTime);
        WmsThrottlePreVerificationResult absent = new WmsThrottlePreVerificationResult(
                1, null);

        assertThat(active.accountCooldownActiveAt(decisionTime)).isTrue();
        assertThat(expired.accountCooldownActiveAt(decisionTime)).isFalse();
        assertThat(absent.accountCooldownActiveAt(decisionTime)).isFalse();
    }

    @Test
    void preVerificationResultRejectsInvalidSourceCount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WmsThrottlePreVerificationResult(0, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WmsThrottlePreVerificationResult(-1, null));
    }

    @Test
    void preVerificationResultRequiresDecisionTimeForCooldownEvaluation() {
        WmsThrottlePreVerificationResult result = new WmsThrottlePreVerificationResult(
                1, Instant.parse("2026-11-15T20:01:00Z"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> result.accountCooldownActiveAt(null));
    }

    @Test
    void accountFailureResultRejectsInvalidFailureCount() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new WmsThrottleAccountFailureResult(0, null));
    }
}
