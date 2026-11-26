package com.zirocraft.zirologistics.wms.governance.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WmsTenantGovernancePolicyResolverTest {
    private final WmsTenantGovernancePolicyResolver resolver = new WmsTenantGovernancePolicyResolver();

    @Test
    void smallProfileAllowsOnlyReviewedRoutineSingleOperatorRules() {
        var inbound = resolver.resolve(
                WmsTenantGovernancePolicyResolver.Profile.SMALL,
                WmsTenantGovernancePolicyResolver.RuleId.INBOUND_RECEIPT_CREATOR_POSTER, null);
        var transfer = resolver.resolve(
                WmsTenantGovernancePolicyResolver.Profile.SMALL,
                WmsTenantGovernancePolicyResolver.RuleId.TRANSFER_DISPATCHER_RECEIVER, null);

        assertEquals(WmsTenantGovernancePolicyResolver.Decision.ALLOW_SAME_ACTOR_WITH_CONTROLS, inbound.decision());
        assertEquals(WmsTenantGovernancePolicyResolver.Decision.ALLOW_SAME_ACTOR_WITH_CONTROLS, transfer.decision());
        assertEquals(WmsTenantGovernancePolicyResolver.Source.PROFILE, inbound.source());
    }

    @Test
    void enterpriseRequiresIndependentActorsForConfigurableCommitRules() {
        for (var rule : new WmsTenantGovernancePolicyResolver.RuleId[] {
                WmsTenantGovernancePolicyResolver.RuleId.INBOUND_RECEIPT_CREATOR_POSTER,
                WmsTenantGovernancePolicyResolver.RuleId.OUTBOUND_SHIPMENT_CREATOR_SHIPPER,
                WmsTenantGovernancePolicyResolver.RuleId.TRANSFER_DISPATCHER_RECEIVER
        }) {
            var result = resolver.resolve(WmsTenantGovernancePolicyResolver.Profile.ENTERPRISE, rule, null);
            assertEquals(WmsTenantGovernancePolicyResolver.Decision.REQUIRE_INDEPENDENT_ACTOR, result.decision());
        }
    }

    @Test
    void mandatoryRulesCannotBeOverridden() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                WmsTenantGovernancePolicyResolver.Profile.SMALL,
                WmsTenantGovernancePolicyResolver.RuleId.INVENTORY_ADJUSTMENT_CREATOR_APPROVER,
                new WmsTenantGovernancePolicyResolver.Override(
                        WmsTenantGovernancePolicyResolver.Decision.ALLOW_SAME_ACTOR_WITH_CONTROLS)));
    }

    @Test
    void thresholdDrivenStockCountDefaultsToIndependentReviewUntilToleranceIsDefined() {
        var result = resolver.resolve(WmsTenantGovernancePolicyResolver.Profile.SMALL,
                WmsTenantGovernancePolicyResolver.RuleId.STOCK_COUNT_COUNTER_REVIEWER, null);
        assertEquals(WmsTenantGovernancePolicyResolver.Category.THRESHOLD, result.category());
        assertEquals(WmsTenantGovernancePolicyResolver.Decision.REQUIRE_INDEPENDENT_REVIEW, result.decision());
    }

    @Test
    void configurableOverridesAreBoundedAndCarryProvenance() {
        var result = resolver.resolve(WmsTenantGovernancePolicyResolver.Profile.MEDIUM,
                WmsTenantGovernancePolicyResolver.RuleId.INBOUND_RECEIPT_CREATOR_POSTER,
                new WmsTenantGovernancePolicyResolver.Override(
                        WmsTenantGovernancePolicyResolver.Decision.ALLOW_SAME_ACTOR_WITH_CONTROLS));
        assertEquals(WmsTenantGovernancePolicyResolver.Source.OVERRIDE, result.source());
        assertEquals(WmsTenantGovernancePolicyResolver.POLICY_VERSION, result.policyVersion());
    }

    @Test
    void thresholdAndAdvisoryRulesDoNotAcceptDecisionOverrides() {
        var override = new WmsTenantGovernancePolicyResolver.Override(
                WmsTenantGovernancePolicyResolver.Decision.SAME_OPERATOR_PERMITTED);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                WmsTenantGovernancePolicyResolver.Profile.SMALL,
                WmsTenantGovernancePolicyResolver.RuleId.STOCK_COUNT_COUNTER_REVIEWER, override));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                WmsTenantGovernancePolicyResolver.Profile.SMALL,
                WmsTenantGovernancePolicyResolver.RuleId.REPLENISHMENT_TASK_ASSIGNEE_CONFIRMER, override));
    }

    @Test
    void unknownProfileOrRuleFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("UNKNOWN", 
                "INBOUND_RECEIPT_CREATOR_POSTER", null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("SMALL",
                "UNKNOWN_RULE", null));
    }

    @Test
    void mandatoryReplenishmentReversalRemainsIndependentForEveryProfile() {
        for (var profile : WmsTenantGovernancePolicyResolver.Profile.values()) {
            var result = resolver.resolve(profile,
                    WmsTenantGovernancePolicyResolver.RuleId.REPLENISHMENT_CONFIRMATION_REVERSAL, null);
            assertEquals(WmsTenantGovernancePolicyResolver.Source.MANDATORY, result.source());
            assertEquals(WmsTenantGovernancePolicyResolver.Decision.REQUIRE_INDEPENDENT_ACTOR, result.decision());
        }
    }
}
