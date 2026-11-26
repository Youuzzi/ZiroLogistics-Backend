package com.zirocraft.zirologistics.wms.governance.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Deterministic, read-only tenant governance policy resolver.
 *
 * <p>This component resolves workflow actor policy only. It never grants RBAC permissions
 * and does not replace transaction-local actor-history enforcement.</p>
 */
public final class WmsTenantGovernancePolicyResolver {
    public static final int POLICY_VERSION = 1;

    public enum Profile { SMALL, MEDIUM, ENTERPRISE }
    public enum Category { MANDATORY, CONFIGURABLE, THRESHOLD, ADVISORY }
    public enum Source { MANDATORY, OVERRIDE, PROFILE, DEFAULT }
    public enum RuleId {
        INBOUND_RECEIPT_CREATOR_POSTER,
        OUTBOUND_SHIPMENT_CREATOR_SHIPPER,
        TRANSFER_DISPATCHER_RECEIVER,
        STOCK_COUNT_COUNTER_REVIEWER,
        STOCK_COUNT_CREATOR_APPROVER,
        STOCK_COUNT_REVIEWER_APPROVER,
        INVENTORY_ADJUSTMENT_CREATOR_APPROVER,
        INVENTORY_ADJUSTMENT_CREATOR_POSTER,
        INVENTORY_ADJUSTMENT_APPROVER_POSTER,
        REPLENISHMENT_CONFIRMATION_REVERSAL,
        REPLENISHMENT_TASK_ASSIGNEE_CONFIRMER
    }
    public enum Decision {
        REQUIRE_INDEPENDENT_ACTOR,
        ALLOW_SAME_ACTOR_WITH_CONTROLS,
        REQUIRE_INDEPENDENT_REVIEW,
        ALLOW_SAME_ACTOR_AFTER_INDEPENDENT_APPROVAL,
        SAME_OPERATOR_PERMITTED
    }

    public record Override(Decision decision) {
        public Override {
            Objects.requireNonNull(decision, "override decision is required");
        }
    }

    public record Resolution(
            RuleId ruleId, Category category, Decision decision, Source source, int policyVersion) {
        public Resolution {
            Objects.requireNonNull(ruleId);
            Objects.requireNonNull(category);
            Objects.requireNonNull(decision);
            Objects.requireNonNull(source);
            if (policyVersion < 1) throw new IllegalArgumentException("Policy version must be positive");
        }
    }

    private record Definition(Category category, Decision small, Decision medium,
                              Decision enterprise, Decision systemDefault) {
        Decision forProfile(Profile profile) {
            return switch (profile) {
                case SMALL -> small;
                case MEDIUM -> medium;
                case ENTERPRISE -> enterprise;
            };
        }
    }

    private static final Map<RuleId, Definition> CATALOGUE = Map.ofEntries(
            Map.entry(RuleId.INBOUND_RECEIPT_CREATOR_POSTER, configurable(
                    Decision.ALLOW_SAME_ACTOR_WITH_CONTROLS, Decision.REQUIRE_INDEPENDENT_ACTOR,
                    Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.OUTBOUND_SHIPMENT_CREATOR_SHIPPER, configurable(
                    Decision.ALLOW_SAME_ACTOR_WITH_CONTROLS, Decision.REQUIRE_INDEPENDENT_ACTOR,
                    Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.TRANSFER_DISPATCHER_RECEIVER, configurable(
                    Decision.ALLOW_SAME_ACTOR_WITH_CONTROLS, Decision.REQUIRE_INDEPENDENT_ACTOR,
                    Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.STOCK_COUNT_COUNTER_REVIEWER, threshold(
                    Decision.REQUIRE_INDEPENDENT_REVIEW)),
            Map.entry(RuleId.STOCK_COUNT_CREATOR_APPROVER, mandatory(
                    Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.STOCK_COUNT_REVIEWER_APPROVER, mandatory(
                    Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.INVENTORY_ADJUSTMENT_CREATOR_APPROVER, mandatory(
                    Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.INVENTORY_ADJUSTMENT_CREATOR_POSTER, mandatory(
                    Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.INVENTORY_ADJUSTMENT_APPROVER_POSTER, configurable(
                    Decision.ALLOW_SAME_ACTOR_AFTER_INDEPENDENT_APPROVAL,
                    Decision.REQUIRE_INDEPENDENT_ACTOR, Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.REPLENISHMENT_CONFIRMATION_REVERSAL, mandatory(
                    Decision.REQUIRE_INDEPENDENT_ACTOR)),
            Map.entry(RuleId.REPLENISHMENT_TASK_ASSIGNEE_CONFIRMER, advisory(
                    Decision.SAME_OPERATOR_PERMITTED))
    );

    public Resolution resolve(Profile profile, RuleId ruleId, Override override) {
        Objects.requireNonNull(profile, "tenant profile is required");
        Objects.requireNonNull(ruleId, "governance rule is required");
        Definition definition = CATALOGUE.get(ruleId);
        if (definition == null) throw new IllegalArgumentException("Unknown governance rule");
        if (definition.category() == Category.MANDATORY) {
            if (override != null) throw new IllegalArgumentException("Mandatory policy cannot be overridden");
            return result(ruleId, definition, definition.systemDefault(), Source.MANDATORY);
        }
        if (override != null) {
            if (definition.category() != Category.CONFIGURABLE) {
                throw new IllegalArgumentException("This policy category does not accept decision overrides");
            }
            if (!allowedOverride(ruleId, override.decision())) {
                throw new IllegalArgumentException("Override is outside the rule's safe decision set");
            }
            return result(ruleId, definition, override.decision(), Source.OVERRIDE);
        }
        Decision profileDecision = definition.forProfile(profile);
        if (profileDecision != null) return result(ruleId, definition, profileDecision, Source.PROFILE);
        return result(ruleId, definition, definition.systemDefault(), Source.DEFAULT);
    }

    public Resolution resolve(String profileCode, String ruleCode, Override override) {
        final Profile profile;
        final RuleId ruleId;
        try {
            profile = Profile.valueOf(Objects.requireNonNull(profileCode).trim().toUpperCase(java.util.Locale.ROOT));
            ruleId = RuleId.valueOf(Objects.requireNonNull(ruleCode).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Unknown tenant profile or governance rule", invalid);
        }
        return resolve(profile, ruleId, override);
    }

    private static boolean allowedOverride(RuleId ruleId, Decision decision) {
        return switch (ruleId) {
            case INBOUND_RECEIPT_CREATOR_POSTER, OUTBOUND_SHIPMENT_CREATOR_SHIPPER,
                 TRANSFER_DISPATCHER_RECEIVER ->
                    decision == Decision.ALLOW_SAME_ACTOR_WITH_CONTROLS
                            || decision == Decision.REQUIRE_INDEPENDENT_ACTOR;
            case INVENTORY_ADJUSTMENT_APPROVER_POSTER ->
                    decision == Decision.ALLOW_SAME_ACTOR_AFTER_INDEPENDENT_APPROVAL
                            || decision == Decision.REQUIRE_INDEPENDENT_ACTOR;
            default -> false;
        };
    }

    private static Resolution result(RuleId id, Definition definition, Decision decision, Source source) {
        return new Resolution(id, definition.category(), decision, source, POLICY_VERSION);
    }

    private static Definition mandatory(Decision decision) {
        return new Definition(Category.MANDATORY, decision, decision, decision, decision);
    }

    private static Definition configurable(Decision small, Decision medium, Decision enterprise) {
        return new Definition(Category.CONFIGURABLE, small, medium, enterprise, Decision.REQUIRE_INDEPENDENT_ACTOR);
    }

    private static Definition threshold(Decision safeDefault) {
        // Until an approved numeric tolerance and unit exist, every variance requires independent review.
        return new Definition(Category.THRESHOLD, safeDefault, safeDefault, safeDefault, safeDefault);
    }

    private static Definition advisory(Decision defaultDecision) {
        return new Definition(Category.ADVISORY, defaultDecision, defaultDecision, defaultDecision, defaultDecision);
    }
}
